"""ONE approved local encrypted-backup rehearsal. No production/cloud client."""
import hashlib,json,os,secrets,shutil,subprocess as s,time,threading,sys,re
from pathlib import Path
from urllib.request import urlopen
import native_restore_rehearsal as n
from postgres_compatibility import unprotect
from run_native_postgres_e2e import isolated_environment,Capture,free

SHA='cb97c04e94ec129ab298fe943631d7c6b32fd2dab5caa549505624c4f0d2a0ae'
WORK=Path(__file__).resolve().parents[1]

def snapshot(db,columns):
    result={}
    for table,names in columns.items():
        rows=db.query('SELECT '+','.join('`'+x+'`' for x in names)+' FROM `'+table+'`')
        canonical=sorted(json.dumps(row,ensure_ascii=True,separators=(',',':')) for row in rows)
        result[table]={'count':len(rows),'sha256':hashlib.sha256('\n'.join(canonical).encode()).hexdigest()}
    result['financial']={table:db.query('SELECT COALESCE(SUM(amount),0) FROM '+table)[0][0] for table in ('expenses','expense_splits','settlements')}
    return result

def integrity(db,name):
    rows=db.query("SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME,CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='"+name+"' AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION")
    groups={}
    for table,column,parent,target,constraint in rows:groups.setdefault((table,parent,constraint),[]).append((column,target))
    for (table,parent,_),pairs in groups.items():
        on=' AND '.join('c.`'+a+'`=p.`'+b+'`' for a,b in pairs)
        present=' AND '.join('c.`'+a+'` IS NOT NULL' for a,b in pairs)
        n.require(db.query('SELECT COUNT(*) FROM `'+table+'` c LEFT JOIN `'+parent+'` p ON '+on+' WHERE '+present+' AND p.`'+pairs[0][1]+'` IS NULL')==[('0',)],'FOREIGN_KEY_INTEGRITY')
    for table in ('expenses','expense_splits','settlements'):n.require(db.query('SELECT COUNT(*) FROM '+table+' WHERE amount<=0')==[('0',)],'POSITIVE_AMOUNTS')
    n.require(db.query('SELECT COUNT(*) FROM expenses e LEFT JOIN (SELECT expense_id,SUM(amount) total FROM expense_splits GROUP BY expense_id) s ON s.expense_id=e.id WHERE s.total IS NULL OR e.amount<>s.total')==[('0',)],'SPLIT_TOTALS')
    n.require(db.query('SELECT COUNT(*) FROM settlements WHERE from_user_id=to_user_id')==[('0',)],'SETTLEMENT_ENDPOINTS')

def main():
    if sys.argv[1:]==['--resume-migration']:
        run=n.BASE/'ApprovedBackupMigration20261112'
        report=json.loads((run/'result.json').read_text())
        n.require(report['status']=='RESTORED' and report['restore_attempts']==1,'SINGLE_RESTORED_COPY_REQUIRED')
        name=report['database'];n.require(re.fullmatch('ss_backup_rehearsal_[a-f0-9]+',name),'REHEARSAL_DATABASE')
        native=n.BASE/'RestoreRehearsals/mysql-synthetic-044696c3455de121'
        pw=unprotect(native/'credential.dpapi');db=n.MySQL(pw)
        facts=db.query('SELECT @@datadir,@@port,@@general_log,@@slow_query_log,@@log_bin')[0]
        n.require(Path(facts[0]).resolve()==(native/'data').resolve() and facts[1:]==('23307','0','0','0'),'LOCAL_PRIVATE_INSTANCE')
        db.query('USE `'+name+'`')
        n.require(db.query('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')==[(str(i+1),str(value),'1') for i,value in enumerate(n.CHECKSUMS)],'NO_MIGRATION_OR_FAILURE_ALREADY_APPLIED')
        columns={table:[x[0] for x in db.query('SHOW COLUMNS FROM `'+table+'`')] for table in sorted(n.TABLES)}
        before=report['before'];n.require(snapshot(db,{table:cols for table,cols in columns.items() if table!='flyway_schema_history'})==before,'RESTORED_COPY_UNCHANGED')
        with (run/'migration-resume.lock').open('x') as lock:lock.write('reviewed prevalidation-only failure; no second restore')
        def save():(run/'result.json').write_text(json.dumps(report,indent=2))
        source=n.BASE/'Backups/splitsmart-v7-fresh-20261108T211100Z.sql.gpg'
        finish(run,source,name,pw,columns,before,db,report,save);return
    n.require(not sys.argv[1:],'EXPLICIT_MODE')
    run=n.BASE/'ApprovedBackupMigration20261112'
    n.require(not run.exists(),'APPROVED_ATTEMPT_ALREADY_EXISTS')
    source=n.BASE/'Backups/splitsmart-v7-fresh-20261108T211100Z.sql.gpg'
    n.require(hashlib.sha256(source.read_bytes()).hexdigest()==SHA,'ORIGINAL_CIPHERTEXT_CHECKSUM')
    n.require(int(n.ps('[int64](Get-PSDrive C).Free'))>8*1024**3,'CAPACITY')
    native=n.BASE/'RestoreRehearsals/mysql-synthetic-044696c3455de121'
    pw=unprotect(native/'credential.dpapi');db=n.MySQL(pw)
    facts=db.query('SELECT @@datadir,@@port,@@general_log,@@slow_query_log,@@log_bin')[0]
    n.require(Path(facts[0]).resolve()==(native/'data').resolve() and facts[1:]==('23307','0','0','0'),'LOCAL_PRIVATE_INSTANCE')
    n.private_directory(run);cipher=run/'approved-copy.sql.gpg';shutil.copyfile(source,cipher)
    n.require(hashlib.sha256(cipher.read_bytes()).hexdigest()==SHA,'PRIVATE_CIPHER_COPY')
    keys=s.run([n.GPG,'--homedir',n.HOME,'--with-colons','--list-secret-keys',n.FPR],capture_output=True,timeout=20)
    n.require(keys.returncode==0 and n.FPR.encode() in keys.stdout,'EXISTING_GPG_RECIPIENT')
    name='ss_backup_rehearsal_'+secrets.token_hex(8)
    n.require(not db.query("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+name+"'"),'NEW_DATABASE_REQUIRED')
    report={'status':'ISOLATED','database':name,'restore_attempts':0,'source_sha256':SHA,'production_contacted':False}
    def save():(run/'result.json').write_text(json.dumps(report,indent=2))
    save();db.query('CREATE DATABASE `'+name+'` CHARACTER SET utf8mb4');db.query('USE `'+name+'`')
    n.require(db.query('SELECT DATABASE(),@@port')==[(name,'23307')],'PRE_DECRYPT_DATABASE_IDENTITY')
    report.update(status='RESTORING',restore_attempts=1);save()
    print('ISOLATION_VERIFIED_ONE_PRIVATE_STREAMING_RESTORE_STARTED',flush=True)
    creates,counts=n.decrypt_to_db(db,cipher,n.TABLES,file=True)
    n.require(set(creates)==n.TABLES,'NINE_TABLES')
    n.require(db.query('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')==[(str(i+1),str(value),'1') for i,value in enumerate(n.CHECKSUMS)],'V7_HISTORY')
    columns={table:[x[0] for x in db.query('SHOW COLUMNS FROM `'+table+'`')] for table in sorted(n.TABLES)}
    before=snapshot(db,{table:cols for table,cols in columns.items() if table!='flyway_schema_history'})
    for table in n.TABLES:n.require(int(db.query('SELECT COUNT(*) FROM `'+table+'`')[0][0])==counts[table],'DUMP_ROW_COUNTS')
    integrity(db,name);report.update(status='RESTORED',restoration='PASS',before=before);save()
    finish(run,source,name,pw,columns,before,db,report,save)

def finish(run,source,name,pw,columns,before,db,report,save):
    native=n.BASE/'RestoreRehearsals/mysql-synthetic-044696c3455de121'
    java=Path.home()/'.jdks/temurin-21'
    env=isolated_environment(os.environ,{'JAVA_HOME':str(java),'SS_BACKUP_REHEARSAL_URL':'jdbc:mysql://127.0.0.1:23307/'+name+'?sslMode=REQUIRED','SS_BACKUP_REHEARSAL_PASSWORD':pw.decode(),'SS_BACKUP_REHEARSAL_DATADIR':str(native/'data')})
    env['PATH']=str(java/'bin')+';'+env['PATH']
    result=s.run([n.POWERSHELL,'-NoProfile','-NonInteractive','-Command',"& './mvnw.cmd' '-o' '-Dtest=ApprovedBackupMigrationIT' 'test'"],cwd=WORK/'backend',env=env,capture_output=True,timeout=240,creationflags=n.CREATE_NO_WINDOW)
    n.require(pw not in result.stdout+result.stderr,'PRIVATE_MIGRATION_LOG_SCAN')
    if result.returncode:
        (run/'migration-failure.dpapi').write_bytes(n.protect(result.stdout+result.stderr));raise n.Stop('MIGRATION_FAILED_KEEP_COPY_NO_RETRY')
    after=snapshot(db,{table:cols for table,cols in columns.items() if table!='flyway_schema_history'})
    n.require(before==after,'ORIGINAL_RECORD_AND_FINANCIAL_PRESERVATION');integrity(db,name)
    n.require(db.query('SELECT COUNT(*) FROM users WHERE auth_token_version<>0')==[('0',)],'LEGACY_JWT_VERSION_COMPATIBILITY')
    db.close();report.update(status='MIGRATED',migration='V7_TO_V10_PASS',original_rows_and_financial='PASS',after=after,auth_version_zero='PASS');save()
    # Maintenance health only: no restored-account login or reset, no mail, no Flyway writes.
    manifest=json.loads((WORK/'docs/release-candidate-artifacts.json').read_text());jar=WORK/'backend/target/splitsmart-backend-0.0.1-SNAPSHOT.jar'
    n.require(hashlib.sha256(jar.read_bytes()).hexdigest()==manifest['backendJarSha256'],'PINNED_BACKEND_ARTIFACT')
    free(18445);jwt=__import__('base64').b64encode(secrets.token_bytes(32)).decode()
    env=isolated_environment(os.environ,dict(DB_URL='jdbc:mysql://127.0.0.1:23307/'+name+'?sslMode=REQUIRED',DB_USERNAME='root',DB_PASSWORD=pw.decode(),JWT_SECRET=jwt,SERVER_ADDRESS='127.0.0.1',SERVER_PORT='18445',SPRING_FLYWAY_ENABLED='false',APP_MAINTENANCE_ENABLED='true',RECOVERY_EMAIL_ENABLED='false',RECOVERY_EMAIL_DELIVERY_VERIFIED='false'))
    process=s.Popen([str(java/'bin/java.exe'),'-jar',str(jar)],env=env,stdout=s.PIPE,stderr=s.STDOUT,creationflags=n.CREATE_NO_WINDOW)
    capture=Capture((pw,jwt.encode()))
    def consume():
        for chunk in iter(lambda:process.stdout.read(4096),b''):capture.feed(chunk)
    thread=threading.Thread(target=consume,daemon=True);thread.start()
    try:
        deadline=time.monotonic()+60
        while True:
            n.require(process.poll() is None,'PINNED_BACKEND_STOPPED')
            try:
                with urlopen('http://127.0.0.1:18445/actuator/health/readiness',timeout=2) as response:n.require(response.status==200,'HEALTH');break
            except OSError:n.require(time.monotonic()<deadline,'HEALTH_TIMEOUT');time.sleep(.5)
    finally:
        process.terminate()
        try:process.wait(timeout=15)
        except s.TimeoutExpired:process.kill();process.wait(timeout=5)
        thread.join(timeout=3)
        n.require(not capture.leaked,'PRIVATE_RUNTIME_LOG_SCAN');free(18445)
    db=n.MySQL(pw);db.query('USE `'+name+'`');n.require(snapshot(db,{table:cols for table,cols in columns.items() if table!='flyway_schema_history'})==before,'POST_STARTUP_PRESERVATION');db.close()
    n.require(hashlib.sha256(source.read_bytes()).hexdigest()==SHA,'ORIGINAL_BACKUP_UNCHANGED')
    report.update(status='PASS',pinned_backend_health='PASS',plaintext_sql_files_created=False,real_account_logins_performed=False,emails_sent=0);save()
    print('APPROVED_LOCAL_BACKUP_V7_TO_V10_REHEARSAL_PASS_NO_EMAILS',flush=True)

if __name__=='__main__':
    try:main()
    except Exception as e:print('APPROVED_REHEARSAL_STOP_'+(str(e) if isinstance(e,n.Stop) else type(e).__name__));raise SystemExit(1)
