"""Synthetic target dump -> GPG -> new protected local database. No MySQL."""
import os,json,hashlib,subprocess as S,datetime as dt,threading,re
from pathlib import Path
import prepare_postgres_target as t
from postgres_compatibility import unprotect
from pg_target_connection import connect
import local_database_support as l

BIN=Path(r'C:\Program Files\PostgreSQL\16\bin')
GPG=r'C:\Program Files\Git\usr\bin\gpg.exe'
HOME=l.BASE/'GnuPG'
GH='/'+HOME.drive[0].lower()+HOME.as_posix()[2:]

def snapshot(db):
    result={}
    for schema,table in db.query("SELECT schemaname,tablename FROM pg_tables WHERE schemaname IN ('public','ss_verification_guard') ORDER BY 1,2"):
        rows=db.query('SELECT row_to_json(x)::text FROM '+schema+'.'+table+' x ORDER BY row_to_json(x)::text COLLATE "C"')
        result[schema+'.'+table]={'count':len(rows),'sha256':hashlib.sha256(json.dumps(rows).encode()).hexdigest()}
    for label,sql in {
        'constraints':"SELECT n.nspname,c.relname,x.conname,x.contype,x.convalidated,pg_get_constraintdef(x.oid) FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','ss_verification_guard') ORDER BY 1,2,3",
        'indexes':"SELECT schemaname,tablename,indexname,indexdef FROM pg_indexes WHERE schemaname IN ('public','ss_verification_guard') ORDER BY 1,2,3",
        'columns':"SELECT table_schema,table_name,column_name,data_type,numeric_precision,numeric_scale,is_nullable FROM information_schema.columns WHERE table_schema IN ('public','ss_verification_guard') ORDER BY table_schema,table_name,ordinal_position"
    }.items():
        rows=db.query(sql)
        if label=='constraints':
            normalized=[]
            for row in rows:
                if row[2] in ('chk_expense_category','ck_invitation_status'):
                    # PostgreSQL patch versions may deparse the same enum CHECK
                    # as an array-wide text cast or per-element text casts.
                    column='category' if row[2]=='chk_expense_category' else 'status'
                    expression=re.sub(r'\b'+column+r'\b','$1',row[5][6:])
                    probes=['FOOD_DRINKS','TRAVEL','SHOPPING','RENT_UTILITIES','ENTERTAINMENT','OTHER','PENDING','ACCEPTED','DECLINED','INVALID','pending','',None]
                    truth=[db.query('SELECT '+expression,[value])[0][0] for value in probes]
                    normalized.append(tuple(row[:5])+(json.dumps(truth),))
                else:normalized.append(row)
            rows=normalized
        result['schema_'+label]=hashlib.sha256(json.dumps(rows).encode()).hexdigest()
    result['sequences']={seq:db.query('SELECT last_value,is_called FROM public.'+seq) for (seq,) in db.query("SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename")}
    return result

def childenv(root,p,db,user,pw,ca):
    # Official libpq passfile: ephemeral, owner/SYSTEM-only on BitLocker C:.
    # No password argument/environment variable, and no SQL plaintext file.
    path=root/'temporary-client.pgpass'
    l.require(not path.exists(),'TEMPORARY_PASSFILE_OCCUPIED')
    esc=lambda x:str(x).replace('\\','\\\\').replace(':','\\:')
    path.write_text(':'.join(map(esc,[p['host'],p['port'],db,user,pw]))+'\n',encoding='utf-8')
    env=os.environ.copy();env.pop('PGPASSWORD',None)
    env.update(PGPASSFILE=str(path),PGSSLMODE='verify-full' if ca else 'disable',PGCONNECT_TIMEOUT='10')
    if ca:env['PGSSLROOTCERT']=str(ca)
    return env,path

def main():
    s=t.state();l.require(s.get('integration')=='PASS','INTEGRATION_GATE')
    l.require(not s.get('synthetic_dump_started'),'DUMP_ALREADY_ATTEMPTED')
    p=t.params();cloud=t.connection(p,'test_migration',s['test_database']);t.guard(cloud,s,True)
    # Only this nonce-bound synthetic database may receive these fixtures.
    a=cloud.query("INSERT INTO users(name,email,password_hash) VALUES('Synthetic recovery','recovery-a@example.invalid','synthetic-not-login') RETURNING id")[0][0]
    b=cloud.query("INSERT INTO users(name,email,password_hash) VALUES('Synthetic recovery','recovery-b@example.invalid','synthetic-not-login') RETURNING id")[0][0]
    g=cloud.query("INSERT INTO expense_groups(name,created_by) VALUES('Synthetic recovery',$1) RETURNING id",[a])[0][0]
    for u in [a,b]:cloud.query('INSERT INTO group_members(group_id,user_id) VALUES($1,$2)',[g,u])
    e=cloud.query("INSERT INTO expenses(group_id,description,amount,currency_code,paid_by_user_id,created_by_user_id,expense_date,category) VALUES($1,'Synthetic recovery',50.00,'INR',$2,$2,CURRENT_DATE,'OTHER') RETURNING id",[g,a])[0][0]
    cloud.query('INSERT INTO expense_splits(expense_id,user_id,amount) VALUES($1,$2,50.00)',[e,b])
    cloud.query("INSERT INTO settlements(group_id,from_user_id,to_user_id,amount,recorded_by_user_id,request_id) VALUES($1,$2,$3,10.00,$2,'12345678-abcd-4abc-abcd-123456789012')",[g,b,a])
    cloud.query("INSERT INTO notifications(recipient_id,group_id,event_key,type,title,resource_id,created_at) VALUES($1,$2,'synthetic-recovery','EXPENSE','Synthetic recovery',$3,CURRENT_TIMESTAMP)",[b,g,e])
    expected=snapshot(cloud);cloud.close()
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    root=t.ROOT;backup=l.BASE/'Backups'/('splitsmart-postgres-synthetic-'+stamp+'.dump.gpg')
    manifest=json.loads((l.BASE/'Backups/backup-manifest-20261107T093751Z.json').read_text())
    recipient=manifest['public_recipient']
    key=S.run([GPG,'--homedir',GH,'--with-colons','--list-secret-keys',recipient],capture_output=True,timeout=20)
    l.require(key.returncode==0 and recipient.encode() in key.stdout,'GPG_KEY_GATE')
    c=t.credentials();env,passfile=childenv(root,p,s['test_database'],t.ROLES['test_migration'],c[t.ROLES['test_migration']],t.CA)
    s['synthetic_dump_started']=True;t.save(s)
    try:
        dump=S.Popen([str(BIN/'pg_dump.exe'),'-h',p['host'],'-p',str(p['port']),'-U',t.ROLES['test_migration'],'-d',s['test_database'],'--no-password','--format=custom','--no-owner','--no-acl'],env=env,stdout=S.PIPE,stderr=S.PIPE,creationflags=l.CREATE_NO_WINDOW)
        with backup.open('xb') as out:
            enc=S.Popen([GPG,'--homedir',GH,'--batch','--yes','--trust-model','always','--cipher-algo','AES256','--recipient',recipient,'--encrypt'],stdin=dump.stdout,stdout=out,stderr=S.PIPE,creationflags=l.CREATE_NO_WINDOW)
            dump.stdout.close();_,err=enc.communicate(timeout=120);dr=dump.wait(timeout=30)
        l.require(enc.returncode==0 and dr==0 and backup.stat().st_size>128,'ENCRYPTED_DUMP_FAILED')
    finally:passfile.unlink(missing_ok=True)
    s.update(synthetic_backup=str(backup),synthetic_backup_sha256=hashlib.sha256(backup.read_bytes()).hexdigest(),synthetic_snapshot=expected);t.save(s)
    print('SYNTHETIC_ENCRYPTED_DUMP_PASS',flush=True)
    localroot=l.BASE/'RestoreRehearsals/postgres-20261107T160405Z'
    lp={'host':'127.0.0.1','port':'25432','dbname':'postgres','user':'ss_pg_owner','password':unprotect(localroot/'owner-credential.dpapi').decode()}
    admin=connect(lp)
    l.require(Path(admin.query("SELECT current_setting('data_directory')")[0][0]).resolve()==(localroot/'data').resolve(),'LOCAL_STORAGE_IDENTITY')
    name='ss_pg_target_restore_'+stamp.lower()
    l.require(not admin.query('SELECT 1 FROM pg_database WHERE datname=$1',[name]),'LOCAL_NAME_OCCUPIED')
    admin.query('CREATE DATABASE '+name+' TEMPLATE template0');admin.close()
    s['local_restore_database']=name;t.save(s)
    env,passfile=childenv(root,lp,name,lp['user'],lp['password'],None)
    try:
        dec=S.Popen([GPG,'--homedir',GH,'--pinentry-mode','ask','--status-fd','2','--decrypt',str(backup)],stdout=S.PIPE,stderr=S.PIPE,creationflags=l.CREATE_NO_WINDOW)
        status=[];thread=threading.Thread(target=lambda:status.append(dec.stderr.read()),daemon=True);thread.start()
        restore=S.Popen([str(BIN/'pg_restore.exe'),'-h','127.0.0.1','-p','25432','-U',lp['user'],'-d',name,'--no-password','--no-owner','--no-acl','--single-transaction','--exit-on-error'],stdin=dec.stdout,stdout=S.PIPE,stderr=S.PIPE,env=env,creationflags=l.CREATE_NO_WINDOW)
        dec.stdout.close();print('LOCAL_RESTORE_DECRYPTION_RUNNING',flush=True)
        _,error=restore.communicate(timeout=240);rc=dec.wait(timeout=30);thread.join(timeout=5)
        l.require(restore.returncode==0 and rc==0 and b'DECRYPTION_OKAY' in b''.join(status),'RESTORE_PIPELINE_FAILED')
    finally:passfile.unlink(missing_ok=True)
    restored=connect(lp,database=name)
    l.require(restored.query('SELECT current_database()')==[(name,)],'LOCAL_DATABASE_IDENTITY')
    l.require(snapshot(restored)==expected,'RESTORED_DATA_MISMATCH')
    l.require(restored.query('SELECT nonce FROM ss_verification_guard.identity')==[(s['nonce'],)],'RESTORED_GUARD')
    l.require(restored.query('SELECT COUNT(*) FROM expenses e JOIN (SELECT expense_id,SUM(amount) total FROM expense_splits GROUP BY expense_id) x ON x.expense_id=e.id WHERE e.amount<>x.total')==[('0',)],'RESTORED_FINANCIAL_INTEGRITY')
    restored.close();s.update(backup_restore='PASS',status='VERIFIED');t.save(s)
    # Only after successful restore, remove synthetic rows from the exact guarded cloud DB.
    cloud=t.connection(p,'test_migration',s['test_database']);t.guard(cloud,s,True)
    cloud.query('TRUNCATE '+','.join('public.'+x for x in t.BUSINESS)+' RESTART IDENTITY')
    l.require(all(cloud.query('SELECT COUNT(*) FROM public.'+x)==[('0',)] for x in t.BUSINESS),'SYNTHETIC_CLEANUP')
    cloud.close();s['synthetic_cleanup']='PASS';t.save(s)
    print('BACKUP_RESTORE_AND_SYNTHETIC_CLEANUP_PASS')

if __name__=='__main__':
    try:main()
    except Exception as e:
        print('STOP_'+(str(e) if isinstance(e,l.Stop) else type(e).__name__));raise SystemExit(1)
