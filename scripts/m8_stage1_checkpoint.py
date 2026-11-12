"""Owner-approved M8 Stage1 only: no ALTER, migrations, deploy, resume or email.
Private receipts are outside Git. Every mutation has a persistent attempt fence.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess as sp
import sys
import time
import threading
import yaml
from urllib.request import Request,urlopen
import aiven_postgres_preflight as api
import local_database_support as l
import native_restore_rehearsal as n
from postgres_compatibility import unprotect

ROOT=l.BASE/'M8Stage1Checkpoint'
LOCAL=l.BASE/'RestoreRehearsals/mysql-synthetic-044696c3455de121'
SERVICE='srv-dbkvpt6gekts73fa6gug'
PIN='a1813f80d9c22e06ae89e2dd7f8b2ba6a0f02c16'

def save(r):
    temporary=ROOT/'receipt.new'
    temporary.write_text(json.dumps(r,indent=2),encoding='utf-8')
    os.replace(temporary,ROOT/'receipt.json')

def load():return json.loads((ROOT/'receipt.json').read_text())

def render(method='GET',suffix=''):
    conf=yaml.safe_load((Path.home()/'.render/cli.yaml').read_text())
    req=Request('https://api.render.com/v1/services/'+SERVICE+suffix,method=method,
                headers={'Authorization':'Bearer '+conf['api']['key'],'Accept':'application/json'})
    with urlopen(req,timeout=30) as response:
        body=response.read()
        return json.loads(body) if body else {}

def source(admin=True):
    cli=api.client();svc=cli.get_service(api.PROJECT,'splitsmart-mysql')
    l.require(svc['state']=='RUNNING' and svc['plan']=='free-1-1gb' and svc['service_type']=='mysql','SOURCE_SERVICE')
    env=dict(x.split('=',1) for x in (Path.home()/'.local/share/splitsmart-tools/render-private/runtime.env').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
    name='avnadmin' if admin else env['DB_USERNAME']
    if admin:
        user=cli.verify(cli.get,cli.build_path('project',api.PROJECT,'service','splitsmart-mysql','user',name),params={'include_secrets':'true'},result_key='user')
        l.require(not api.placeholder(user.get('password')),'EXISTING_ADMIN_SECRET')
        password=user['password']
    else:password=env['DB_PASSWORD']
    p=svc['service_uri_params']
    c=l.MySQL(password.encode(),host=p['host'],port=int(p['port']),user=name,database='splitsmart',ca=api.ROOT/'project-ca.pem')
    l.require(c.query('SELECT DATABASE()')==[('splitsmart',)],'DATABASE_IDENTITY')
    c.query("SET time_zone='+00:00'")
    history=c.query('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')
    l.require(history==[(str(i+1),str(v),'1') for i,v in enumerate(l.CHECKSUMS)],'SOURCE_V7_HISTORY')
    tables=c.query("SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'")
    l.require({r[0] for r in tables}==l.TABLES and all(r[1]=='InnoDB' for r in tables),'SOURCE_TABLES')
    return c,env,svc

def local():
    c=l.MySQL(unprotect(LOCAL/'credential.dpapi'))
    facts=c.query('SELECT @@datadir,@@port,@@server_uuid,@@general_log,@@slow_query_log,@@log_bin')[0]
    l.require(Path(facts[0]).resolve()==(LOCAL/'data').resolve() and facts[1]=='23307' and facts[3:]==('0','0','0'),'LOCAL_IDENTITY_AND_LOGGING')
    c.query("SET time_zone='+00:00'")
    return c,facts[2]

def fingerprint(c):
    c.query('SET TRANSACTION READ ONLY');c.query('START TRANSACTION WITH CONSISTENT SNAPSHOT')
    output={}
    try:
        for table in sorted(l.TABLES):
            cols=c.query("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION")
            l.require(cols,'PRIMARY_KEYS')
            rows=c.query('SELECT * FROM `'+table+'` ORDER BY '+','.join('`'+x[0]+'`' for x in cols))
            output[table]={'rows':len(rows),'sha256':hashlib.sha256(json.dumps(rows,separators=(',',':'),ensure_ascii=True).encode()).hexdigest()}
        totals=[c.query('SELECT COALESCE(SUM(amount),0) FROM '+t)[0][0] for t in ['expenses','expense_splits','settlements']]
        output['financial_sha256']=hashlib.sha256(json.dumps(totals).encode()).hexdigest()
        c.query('COMMIT');return output
    except BaseException:c.query('ROLLBACK');raise

def preflight():
    l.private_directory(ROOT)
    l.require(not (ROOT/'receipt.json').exists(),'STAGE1_RECEIPT_ALREADY_EXISTS')
    r={'status':'PREFLIGHT','productionFrozen':False,'exportAttempts':0,'restoreAttempts':0,'suspendAttempts':0,'revokeAttempts':0,'pin':PIN}
    save(r)
    c,env,svc=source();grants=' '.join(x[0] for x in c.query('SHOW GRANTS'))
    l.require('GRANT OPTION' in grants,'ADMIN_GRANT_OPTION')
    l.require(env['DB_USERNAME']=='splitsmart_app','RUNTIME_IDENTITY')
    runtime,_,_=source(False)
    account=runtime.query('SELECT CURRENT_USER()')[0][0]
    l.require(account=='splitsmart_app@%','RUNTIME_ACCOUNT')
    l.require(runtime.query('SELECT CURRENT_ROLE()')==[('NONE',)],'RUNTIME_ROLE')
    r['originalRuntimeGrants']=[x[0] for x in runtime.query('SHOW GRANTS')];runtime.close()
    for table,col in [('VIEWS','TABLE_SCHEMA'),('TRIGGERS','TRIGGER_SCHEMA'),('ROUTINES','ROUTINE_SCHEMA'),('EVENTS','EVENT_SCHEMA')]:
        l.require(c.query('SELECT COUNT(*) FROM information_schema.'+table+' WHERE '+col+"='splitsmart'")==[('0',)],'EXTRA_OBJECTS_'+table)
    c.close()
    lc,uuid=local();lc.close();r['localUuid']=uuid
    service=render();l.require(service['id']==SERVICE and service['autoDeploy']=='no' and service['serviceDetails']['plan']=='free','RENDER_IDENTITY_AUTO_DEPLOY')
    l.require(__import__('shutil').disk_usage(l.BASE).free>8*1024**3,'DISK_SPACE')
    key=sp.run([n.GPG,'--homedir',n.HOME,'--with-colons','--list-secret-keys',n.FPR],capture_output=True,timeout=20)
    l.require(key.returncode==0 and n.FPR.encode() in key.stdout,'GPG_KEY')
    enc=sp.run([n.GPG,'--homedir',n.HOME,'--batch','--trust-model','always','--cipher-algo','AES256','--recipient',n.FPR,'--encrypt'],input=b'M8_STAGE1_SYNTHETIC_RECOVERY_CHECK',capture_output=True,timeout=20)
    l.require(enc.returncode==0,'SYNTHETIC_ENCRYPT')
    print('SYNTHETIC_GPG_DECRYPTION_MAY_REQUIRE_NATIVE_PINENTRY',flush=True)
    dec=sp.run([n.GPG,'--homedir',n.HOME,'--pinentry-mode','ask','--decrypt'],input=enc.stdout,capture_output=True,timeout=240)
    l.require(dec.returncode==0 and dec.stdout==b'M8_STAGE1_SYNTHETIC_RECOVERY_CHECK','SYNTHETIC_GPG_RECOVERY')
    r.update(status='PREFLIGHT_PASS',syntheticGpgRecovery=True);save(r)
    print('STAGE1_PREFLIGHT_PASS_NO_FREEZE_YET')

def frozen(c):
    l.require(render()['suspended']=='suspended','RENDER_NOT_SUSPENDED')
    l.require(c.query('SELECT COUNT(*) FROM information_schema.INNODB_TRX')==[('0',)],'ACTIVE_TRANSACTIONS')
    l.require(c.query("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER='splitsmart_app'")==[('0',)],'RUNTIME_SESSIONS')

def freeze():
    r=load();l.require(r['status']=='PREFLIGHT_PASS' and not r['suspendAttempts'] and not r['revokeAttempts'],'FREEZE_ATTEMPT_FENCE')
    storage=ROOT/'storage-protection.json'
    l.require(storage.is_file() and json.loads(storage.read_text(encoding='utf-8-sig')).get('StorageProtectionGatePassed') is True,'ENCRYPTED_STORAGE_NOT_VERIFIED')
    c,_,_=source()
    with (ROOT/'freeze-attempt.lock').open('x') as lock:lock.write('ONE_APPROVED_STAGE1_ATTEMPT')
    r['suspendAttempts']=1;save(r);render('POST','/suspend')
    for _ in range(20):
        if render()['suspended']=='suspended' and c.query("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER='splitsmart_app'")==[('0',)] and c.query('SELECT COUNT(*) FROM information_schema.INNODB_TRX')==[('0',)]:break
        time.sleep(2)
    frozen(c)
    r.update(status='SUSPENDED',revokeAttempts=1);save(r)
    c.query("REVOKE INSERT, UPDATE, DELETE ON `splitsmart`.* FROM 'splitsmart_app'@'%'")
    grants=' '.join(x[0] for x in c.query("SHOW GRANTS FOR 'splitsmart_app'@'%'"))
    l.require('SELECT' in grants and not any(x in grants for x in ['INSERT','UPDATE','DELETE','ALL PRIVILEGES']),'RUNTIME_DML_FENCE')
    frozen(c);c.close()
    r.update(status='FROZEN',productionFrozen=True,frozenUtc=dt.datetime.now(dt.timezone.utc).isoformat(),runtimeDmlRevoked=True);save(r)
    print('PRODUCTION_FROZEN_V7_NO_SCHEMA_CHANGES')

def checkpoint():
    r=load();l.require(r['status']=='FROZEN' and r['exportAttempts']==r['restoreAttempts']==0,'CHECKPOINT_ATTEMPT_FENCE')
    storage=json.loads((ROOT/'storage-protection.json').read_text(encoding='utf-8-sig'))
    l.require(storage.get('StorageProtectionGatePassed') is True,'ENCRYPTED_STORAGE_NOT_VERIFIED')
    c,env,svc=source();frozen(c)
    before=fingerprint(c)
    lc,uuid=local();l.require(uuid==r['localUuid'],'LOCAL_UUID_CHANGED')
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup=l.BASE/'Backups'/('splitsmart-v7-m8-stage1-'+stamp+'.sql.gpg');db='ss_m8_stage1_restore_'+stamp.lower()
    l.require(not backup.exists() and not lc.query("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+db+"'"),'OUTPUT_COLLISION')
    cnf=ROOT/'temporary-export.cnf';l.require(not cnf.exists(),'TEMP_CONFIG_COLLISION')
    esc=lambda v:str(v).replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\r','\\r')
    params=svc['service_uri_params']
    opts={'host':params['host'],'port':params['port'],'user':env['DB_USERNAME'],'password':env['DB_PASSWORD'],'protocol':'TCP','ssl-mode':'VERIFY_IDENTITY','ssl-ca':(api.ROOT/'project-ca.pem').as_posix()}
    cnf.write_text('[client]\n'+''.join(k+'="'+esc(v)+'"\n' for k,v in opts.items()),encoding='utf-8')
    with (ROOT/'export-attempt.lock').open('x') as lock:lock.write('ONE_APPROVED_STAGE1_EXPORT')
    r.update(status='EXPORTING',exportAttempts=1,backup=str(backup),restoreDatabase=db,sourceBefore=before);save(r)
    dump=None;encrypt=None
    try:
        with backup.open('xb') as output:
            dump=sp.Popen([str(l.MYSQL/'bin/mysqldump.exe'),'--defaults-file='+str(cnf),'--single-transaction','--quick','--no-tablespaces','--set-gtid-purged=OFF','--hex-blob','--tz-utc','--default-character-set=utf8mb4','--skip-triggers','--skip-routines','--skip-events','--skip-add-drop-table','--skip-add-locks','--skip-comments','splitsmart',*sorted(l.TABLES)],stdout=sp.PIPE,stderr=sp.PIPE,creationflags=l.CREATE_NO_WINDOW)
            drain=threading.Thread(target=lambda:dump.stderr.read(),daemon=True);drain.start()
            encrypt=sp.Popen([n.GPG,'--homedir',n.HOME,'--batch','--trust-model','always','--cipher-algo','AES256','--recipient',n.FPR,'--encrypt'],stdin=dump.stdout,stdout=output,stderr=sp.PIPE,creationflags=l.CREATE_NO_WINDOW)
            dump.stdout.close();encrypt.communicate(timeout=180);rc=dump.wait(timeout=20);drain.join(timeout=5)
        l.require(rc==0 and encrypt.returncode==0 and backup.stat().st_size>128,'EXPORT_ENCRYPTION')
    finally:
        for proc in (encrypt,dump):
            if proc is not None and proc.poll() is None:proc.terminate();proc.wait(timeout=10)
        cnf.unlink(missing_ok=True)
    frozen(c);after=fingerprint(c);l.require(after==before,'FROZEN_SOURCE_CHANGED');c.close()
    sha=hashlib.sha256(backup.read_bytes()).hexdigest()
    r.update(status='EXPORTED',sha256=sha,bytes=backup.stat().st_size,sourceAfter=after);save(r)
    l.require(lc.query('SELECT @@server_uuid')==[(uuid,)],'LOCAL_IDENTITY_CHANGED')
    lc.query('CREATE DATABASE `'+db+'` CHARACTER SET utf8mb4');lc.query('USE `'+db+'`')
    r.update(status='RESTORING',restoreAttempts=1);save(r)
    creates,counts=n.decrypt_to_db(lc,backup,l.TABLES,file=True)
    l.require(set(creates)==l.TABLES,'TABLE_COVERAGE')
    restored=fingerprint(lc);l.require(restored==before,'ALL_RECORD_AND_FINANCIAL_HASHES')
    l.require({t:v['rows'] for t,v in restored.items() if t in l.TABLES}==counts,'DUMP_COUNTS')
    l.require(lc.query('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')==[(str(i+1),str(v),'1') for i,v in enumerate(l.CHECKSUMS)],'RESTORED_V7')
    l.require(lc.query('SELECT COUNT(*) FROM expenses e LEFT JOIN (SELECT expense_id,SUM(amount) total FROM expense_splits GROUP BY expense_id) s ON s.expense_id=e.id WHERE s.total IS NULL OR e.amount<>s.total')==[('0',)],'FINANCIAL_SPLITS')
    fks=lc.query('SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL')
    for table,col,parent,key in fks:
        l.require(lc.query('SELECT COUNT(*) FROM `'+table+'` c LEFT JOIN `'+parent+'` p ON c.`'+col+'`=p.`'+key+'` WHERE c.`'+col+'` IS NOT NULL AND p.`'+key+'` IS NULL')==[('0',)],'FOREIGN_KEY_ORPHANS')
    for table,ddl in creates.items():
        idx={x[0] for x in lc.query("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"'")}
        con={x[0] for x in lc.query("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"'")}
        l.require('PRIMARY' in idx and all(x in idx for x in re.findall(r'(?:UNIQUE\s+)?KEY\s+`([^`]+)`',ddl,re.I)),'INDEX_COVERAGE')
        l.require(all(x in con for x in re.findall(r'CONSTRAINT\s+`([^`]+)`',ddl,re.I)),'CONSTRAINT_COVERAGE')
    lc.close();r.update(status='LOCAL_CHECKPOINT_PASS',restored=restored,allNineTableHashesMatch=True,financialIntegrity=True,temporaryConfigRemoved=not cnf.exists());save(r)
    print('ONE_FROZEN_EXPORT_ONE_LOCAL_RESTORE_ALL_HASHES_INTEGRITY_PASS')

if __name__=='__main__':
    try:
        l.require(len(sys.argv)==2 and sys.argv[1] in ['--preflight','--execute-approved-freeze','--execute-approved-checkpoint'],'EXPLICIT_STAGE1_ACTION')
        {'--preflight':preflight,'--execute-approved-freeze':freeze,'--execute-approved-checkpoint':checkpoint}[sys.argv[1]]()
    except BaseException as e:
        code=str(e) if isinstance(e,l.Stop) else type(e).__name__
        if (ROOT/'receipt.json').exists():
            r=load();r['lastErrorCode']=code;save(r)
        print('STAGE1_STOP_'+code,flush=True);raise SystemExit(1)
