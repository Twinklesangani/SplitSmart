"""Local-only read-only MySQL -> isolated PostgreSQL rehearsal; no record output."""
import ctypes as C
import datetime as dt
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess as S
import sys
import time
import local_database_support as r

PG=Path(r'C:\Program Files\PostgreSQL\16')
SOURCE=r.BASE/'RestoreRehearsals/native-20261107T155155Z'
WORK=Path(__file__).resolve().parents[1]

def unprotect(path):
    raw=path.read_bytes(); buf=(C.c_ubyte*len(raw)).from_buffer_copy(raw)
    src,dst=r.Blob(len(raw),buf),r.Blob()
    fn=C.WinDLL('crypt32',use_last_error=True).CryptUnprotectData
    fn.argtypes=[C.POINTER(r.Blob),C.c_void_p,C.c_void_p,C.c_void_p,C.c_void_p,C.c_uint32,C.POINTER(r.Blob)]
    r.require(fn(C.byref(src),None,None,None,None,1,C.byref(dst)),'DPAPI_READ')
    out=C.string_at(dst.data,dst.size); C.windll.kernel32.LocalFree(C.cast(dst.data,C.c_void_p))
    return out

class Pg:
    def __init__(self,user,password,database):
        self.directory=os.add_dll_directory(str(PG/'bin'))
        self.lib=C.CDLL(str(PG/'bin/libpq.dll'))
        p,s,i=C.c_void_p,C.c_char_p,C.c_int
        for name,restype,args in [
            ('PQconnectdbParams',p,[C.POINTER(s),C.POINTER(s),i]),('PQstatus',i,[p]),
            ('PQexec',p,[p,s]),('PQexecParams',p,[p,s,i,p,C.POINTER(s),p,p,i]),
            ('PQresultStatus',i,[p]),('PQntuples',i,[p]),('PQnfields',i,[p]),
            ('PQgetvalue',s,[p,i,i]),('PQgetisnull',i,[p,i,i]),('PQclear',None,[p]),
            ('PQfinish',None,[p]),('PQresultErrorField',s,[p,i])]:
            fn=getattr(self.lib,name); fn.restype=restype;fn.argtypes=args
        keys=[b'host',b'port',b'user',b'password',b'dbname',b'connect_timeout',b'options',None]
        vals=[b'127.0.0.1',b'25432',user.encode(),password,database.encode(),b'5',b'-c timezone=UTC',None]
        self.conn=self.lib.PQconnectdbParams((s*len(keys))(*keys),(s*len(vals))(*vals),0)
        r.require(self.conn and self.lib.PQstatus(self.conn)==0,'PG_AUTH_FAILED')
    def query(self,sql,params=None):
        if params is None: result=self.lib.PQexec(self.conn,sql.encode())
        else:
            values=[None if x is None else str(x).encode() for x in params]
            result=self.lib.PQexecParams(self.conn,sql.encode(),len(values),None,
                (C.c_char_p*len(values))(*values),None,None,0)
        r.require(result,'PG_EMPTY_RESULT')
        status=self.lib.PQresultStatus(result)
        if status not in (1,2):
            code=self.lib.PQresultErrorField(result,ord('C'))
            self.lib.PQclear(result)
            raise r.Stop('PG_SQL_'+(code.decode() if code else 'UNKNOWN'))
        rows=[]
        for row in range(self.lib.PQntuples(result)):
            rows.append(tuple(None if self.lib.PQgetisnull(result,row,col) else
                self.lib.PQgetvalue(result,row,col).decode() for col in range(self.lib.PQnfields(result))))
        self.lib.PQclear(result); return rows
    def close(self): self.lib.PQfinish(self.conn)

def normalize(value,kind):
    if value is None:return None
    if kind=='boolean':return value.lower() in ('1','t','true')
    if kind in ('timestamp','datetime','timestamp without time zone','timestamp with time zone'):
        val=dt.datetime.fromisoformat(value)
        if val.tzinfo:val=val.astimezone(dt.timezone.utc).replace(tzinfo=None)
        return val.isoformat(timespec='microseconds')
    if kind in ('decimal','numeric'):return format(Decimal(value),'f')
    return value

def digest(rows,kinds):
    canonical=[[normalize(v,k) for v,k in zip(row,kinds)] for row in rows]
    return hashlib.sha256(json.dumps(canonical,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()

def maven(root,password,user,database,target=None,tests=None):
    env=os.environ.copy()
    env.update(PG_TEST_USER=user,PG_TEST_PASSWORD=password.decode(),
               PG_TEST_URL='jdbc:postgresql://127.0.0.1:25432/'+database)
    if target:
        env.update(PG_MIGRATION_URL=env['PG_TEST_URL'],PG_MIGRATION_TARGET=str(target))
        tests='PostgresSchemaIT'
    command="& './mvnw.cmd' '-q' '-Dtest="+tests+"' 'test'"
    proc=S.run([r.POWERSHELL,'-NoProfile','-NonInteractive','-Command',command],cwd=WORK/'backend',
               env=env,capture_output=True,timeout=360,creationflags=r.CREATE_NO_WINDOW)
    # Raw Maven output may contain SQL error details. Preserve only DPAPI ciphertext.
    if proc.returncode:
        (root/'maven-failure.dpapi').write_bytes(r.protect(proc.stdout+proc.stderr))
        raise r.Stop('MAVEN_SCHEMA_FAILED' if target else 'MAVEN_APP_TESTS_FAILED')

def main():
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    resume=('--resume' in sys.argv)
    root=Path(sys.argv[sys.argv.index('--resume')+1]) if resume else r.BASE/'RestoreRehearsals'/('postgres-'+stamp)
    if resume:
        r.require(root.parent==r.BASE/'RestoreRehearsals' and root.name.startswith('postgres-'),'RESUME_PATH')
        stamp=root.name.removeprefix('postgres-')
        if (root/'compatibility-result.json').exists() and json.loads((root/'compatibility-result.json').read_text()).get('data_migration')=='PASS':
            print('DATA_ALREADY_MIGRATED_USE_TESTS_ONLY_OR_VALIDATE_COPY');return 1
    report={'status':'PREFLIGHT','root':str(root),'port':25432,'production_accessed':False,'mysql_data_modified':False}
    src=None; pg=None
    def save():
        if root.exists(): (root/'compatibility-result.json').write_text(json.dumps(report,indent=2))
    try:
        data=root/'data'
        if not resume:
            r.require(not root.exists(),'PG_DEST_EXISTS')
            sock=socket.socket();sock.bind(('127.0.0.1',25432));sock.close()
            r.require(int(r.ps('[int64](Get-PSDrive C).Free'))>8*1024**3,'PG_STORAGE')
            r.private_directory(root)
            result=S.run([str(PG/'bin/initdb.exe'),'-D',str(data),'-U','ss_pg_owner','-A','reject',
                      '--encoding=UTF8','--locale=C','--data-checksums'],capture_output=True,timeout=120,
                     creationflags=r.CREATE_NO_WINDOW)
            r.require(result.returncode==0,'PG_INIT_FAILED')
            config="\nlisten_addresses='127.0.0.1'\nport=25432\ntimezone='UTC'\nlog_timezone='UTC'\nlog_statement='none'\nlog_min_error_statement='panic'\nlog_parameter_max_length_on_error=0\nlogging_collector=off\nshared_buffers='64MB'\nmax_connections=25\n"
            with (data/'postgresql.conf').open('a') as f:f.write(config)
            password=secrets.token_urlsafe(36).encode()
            # Offline single-user bootstrap: no socket, no trust-auth window, SQL only stdin.
            setup=S.run([str(PG/'bin/postgres.exe'),'--single','-D',str(data),'postgres'],
                    input=b"ALTER ROLE ss_pg_owner WITH PASSWORD '"+password+b"';\n",
                    capture_output=True,timeout=30,creationflags=r.CREATE_NO_WINDOW)
            r.require(setup.returncode==0 and b'ERROR' not in setup.stdout+setup.stderr,'PG_OFFLINE_AUTH_FAILED')
            (root/'owner-credential.dpapi').write_bytes(r.protect(password))
            (data/'pg_hba.conf').write_text('host all all 127.0.0.1/32 scram-sha-256\nhost all all 0.0.0.0/0 reject\nhost all all ::/0 reject\n')
            start=S.run([str(PG/'bin/pg_ctl.exe'),'start','-D',str(data),'-l',str(root/'server.log'),'-w','-t','30'],
                    stdout=S.DEVNULL,stderr=S.DEVNULL,timeout=40,creationflags=r.CREATE_NO_WINDOW)
            r.require(start.returncode==0,'PG_START_FAILED')
        else:
            password=unprotect(root/'owner-credential.dpapi')
        pg=Pg('ss_pg_owner',password,'postgres')
        identity=pg.query('SELECT current_setting(\'data_directory\'),host(inet_server_addr()),inet_server_port()::text,pg_postmaster_start_time()::text')[0]
        r.require(Path(identity[0]).resolve()==data.resolve() and identity[1:3]==('127.0.0.1','25432'),'PG_ISOLATION')
        pid=int((data/'postmaster.pid').read_text().splitlines()[0]);report['pid']=pid
        r.require(r.ps("$x=@(Get-NetTCPConnection -State Listen | Where-Object {$_.OwningProcess -eq "+str(pid)+"}); if($x.Count -ne 1 -or $x[0].LocalAddress -ne '127.0.0.1' -or $x[0].LocalPort -ne 25432){throw 'LISTENER'}; 'OK'")=='OK','PG_LISTENER')
        user='ss_pg_test'
        testpass=unprotect(root/'test-credential.dpapi') if (root/'test-credential.dpapi').exists() else secrets.token_urlsafe(36).encode()
        # URL-safe generated secret contains no SQL quote; DDL has no protocol
        # placeholders. This SQL exists only in RAM; statement/error logging is off.
        if not pg.query("SELECT 1 FROM pg_roles WHERE rolname='"+user+"'"):
            pg.query('CREATE ROLE '+user+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"+testpass.decode()+"'")
        stampdb=stamp.lower()
        copydb='ss_pg_copy_'+stampdb;testdb='ss_pg_test_'+stampdb
        for name in [copydb,testdb]:
            if not pg.query('SELECT 1 FROM pg_database WHERE datname=$1',[name]):
                pg.query('CREATE DATABASE '+name+' OWNER '+user)
        report.update(copy_database=copydb,test_database=testdb,username=user,isolation='PASS')
        (root/'test-credential.dpapi').write_bytes(r.protect(testpass));save()
        pg.close();pg=None
        print('PG_ISOLATION_PASS',flush=True)
        maven(root,testpass,user,copydb,target=7)
        src=r.MySQL(unprotect(SOURCE/'local-credential.dpapi'))
        facts=src.query('SELECT @@datadir,@@port')[0]
        r.require(Path(facts[0]).resolve()==(SOURCE/'data').resolve() and facts[1]=='23307','SOURCE_IDENTITY')
        src.query('SET SESSION time_zone=\'+00:00\'')
        src.query('SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        src.query('SET TRANSACTION READ ONLY');src.query('START TRANSACTION WITH CONSISTENT SNAPSHOT')
        src.query('USE splitsmart_restore_20261107t155155z')
        payload={}
        order=['users','expense_groups','group_members','expenses','expense_splits','settlements','expense_creation_operations','group_invitations','flyway_schema_history']
        for table in order:
            cols=src.query("SELECT COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='"+table+"' ORDER BY ORDINAL_POSITION")
            names=[c[0] for c in cols];kinds=[('boolean' if (table=='expense_groups' and c[0]=='archived') or (table=='flyway_schema_history' and c[0]=='success') else c[1]) for c in cols]
            rows=src.query('SELECT '+','.join('`'+c+'`' for c in names)+' FROM `'+table+'` ORDER BY `'+('installed_rank' if table=='flyway_schema_history' else 'id')+'`')
            payload[table]=(names,kinds,rows)
        source_next={table:int(mark) for table,mark in src.query("SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND AUTO_INCREMENT IS NOT NULL")}
        history=payload['flyway_schema_history']
        idx={name:i for i,name in enumerate(history[0])}
        r.require([(row[idx['version']],row[idx['checksum']],row[idx['success']]) for row in history[2]]==
                  [(str(i+1),str(c),'1') for i,c in enumerate(r.CHECKSUMS)],'SOURCE_V7_HISTORY_REQUIRED')
        src.query('COMMIT');src.close();src=None
        pg=Pg(user,testpass,copydb);pg.query('BEGIN')
        pg.query('CREATE TABLE legacy_mysql_flyway_history (installed_rank integer PRIMARY KEY,version varchar(50),description varchar(200) NOT NULL,type varchar(20) NOT NULL,script varchar(1000) NOT NULL,checksum integer,installed_by varchar(100) NOT NULL,installed_on timestamp(6) NOT NULL,execution_time integer NOT NULL,success boolean NOT NULL)')
        counts={};hashes={}
        for table in order:
            names,kinds,rows=payload[table]
            target='legacy_mysql_flyway_history' if table=='flyway_schema_history' else table
            params=','.join('$'+str(i+1) for i in range(len(names)))
            statement='INSERT INTO '+target+' ('+','.join(names)+') VALUES ('+params+')'
            for row in rows:
                values=[('true' if normalize(v,k) else 'false') if k=='boolean' and v is not None else v for v,k in zip(row,kinds)]
                if table=='group_invitations':
                    for col in ['expires_at']:
                        idx=names.index(col)
                        if values[idx]:values[idx]+='+00'
                pg.query(statement,values)
            restored=pg.query('SELECT '+','.join(names)+' FROM '+target+' ORDER BY '+('installed_rank' if table=='flyway_schema_history' else 'id'))
            r.require(digest(rows,kinds)==digest(restored,kinds),'DATA_EQUIVALENCE_'+table)
            counts[table]=len(rows);hashes[table]=digest(rows,kinds)
            if table!='flyway_schema_history':
                maximum=int(pg.query('SELECT COALESCE(MAX(id),0) FROM '+target)[0][0])
                nextid=max(source_next[target],maximum+1)
                r.require(pg.query("SELECT setval(pg_get_serial_sequence('"+target+"','id'),$1,false)",[nextid])==[(str(nextid),)],'SEQUENCE_STATE')
        # Constraints are enabled for every insert. Compare financial totals exactly.
        for table in ['expenses','expense_splits','settlements']:
            names,kinds,rows=payload[table]
            expected=sum((Decimal(row[names.index('amount')]) for row in rows),Decimal('0.00'))
            actual=Decimal(pg.query('SELECT COALESCE(SUM(amount),0) FROM '+table)[0][0])
            r.require(expected==actual,'FINANCIAL_SUM_'+table)
        r.require(pg.query('SELECT COUNT(*) FROM expenses e LEFT JOIN (SELECT expense_id,SUM(amount) total FROM expense_splits GROUP BY expense_id) s ON s.expense_id=e.id WHERE s.total IS NULL OR s.total<>e.amount')==[('0',)],'PG_SPLIT_INTEGRITY')
        pg.query('COMMIT');pg.close();pg=None
        report.update(data_migration='PASS',row_counts=counts,row_digests=hashes,financial_aggregates='PASS',legacy_history='PRESERVED_SEPARATELY');save()
        print('DATA_AND_FINANCIAL_EQUIVALENCE_PASS',flush=True)
        maven(root,testpass,user,copydb,target=10)
        report['imported_copy_v8_v10']='PASS';save()
        maven(root,testpass,user,copydb,target=11)
        report['postgresql_v11_identifier_compatibility']='PASS';save()
        maven(root,testpass,user,testdb,tests='PostgresRecoveryIT,PostgresInvitationIT,PostgresNotificationIT,PostgresFinancialIT')
        report.update(status='PASS',application_tests='PASS');save()
        print('POSTGRES_COMPATIBILITY_PASS',flush=True)
    except BaseException as ex:
        report['status']='BLOCKED';report['error_code']=str(ex) if isinstance(ex,r.Stop) else type(ex).__name__;save()
        print('STOP_'+report['error_code'],flush=True);return 1
    finally:
        if src:src.close()
        if pg:pg.close()
        print('PRIVATE_REPORT='+str(root/'compatibility-result.json'),flush=True)
    return 0

if __name__=='__main__':
    if '--secret-scan' in sys.argv:
        root=Path(sys.argv[sys.argv.index('--secret-scan')+1])
        r.require(root.parent==r.BASE/'RestoreRehearsals' and root.name.startswith('postgres-'),'SCAN_PATH')
        passwords=[unprotect(root/'owner-credential.dpapi'),unprotect(root/'test-credential.dpapi')]
        files=[p for p in (WORK/'backend/target/surefire-reports').glob('*') if p.is_file()]
        files += [p for p in root.rglob('*') if p.is_file() and p.suffix in ('.log','.json','.txt','.conf')]
        r.require(not any(any(secret in p.read_bytes() for secret in passwords) for p in files),'PLAINTEXT_SECRET_FOUND')
        print('PRIVATE_CREDENTIAL_LOG_SCAN_PASS')
    elif '--advance-schema' in sys.argv:
        root=Path(sys.argv[sys.argv.index('--advance-schema')+1])
        r.require(root.parent==r.BASE/'RestoreRehearsals' and root.name.startswith('postgres-'),'SCHEMA_PATH')
        report=json.loads((root/'compatibility-result.json').read_text())
        maven(root,unprotect(root/'test-credential.dpapi'),report['username'],report['copy_database'],target=11)
        report['postgresql_v11_identifier_compatibility']='PASS'
        (root/'compatibility-result.json').write_text(json.dumps(report,indent=2))
        print('POSTGRES_SCHEMA_V11_PASS')
    elif '--validate-copy' in sys.argv:
        root=Path(sys.argv[sys.argv.index('--validate-copy')+1])
        r.require(root.parent==r.BASE/'RestoreRehearsals' and root.name.startswith('postgres-'),'VALIDATION_PATH')
        report=json.loads((root/'compatibility-result.json').read_text())
        source=r.MySQL(unprotect(SOURCE/'local-credential.dpapi'))
        r.require(Path(source.query('SELECT @@datadir')[0][0]).resolve()==(SOURCE/'data').resolve(),'SOURCE_PATH')
        pg=Pg(report['username'],unprotect(root/'test-credential.dpapi'),report['copy_database'])
        for table,expected in report['row_digests'].items():
            cols=source.query("SELECT COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='splitsmart_restore_20261107t155155z' AND TABLE_NAME='"+table+"' ORDER BY ORDINAL_POSITION")
            names=[c[0] for c in cols]
            kinds=[('boolean' if (table=='expense_groups' and c[0]=='archived') or (table=='flyway_schema_history' and c[0]=='success') else c[1]) for c in cols]
            target='legacy_mysql_flyway_history' if table=='flyway_schema_history' else table
            rows=pg.query('SELECT '+','.join(names)+' FROM '+target+' ORDER BY '+('installed_rank' if table=='flyway_schema_history' else 'id'))
            r.require(digest(rows,kinds)==expected,'POST_V10_DATA_CHANGED')
        r.require(pg.query('SELECT COUNT(*) FROM users WHERE auth_token_version<>0 OR email_verified_at IS NOT NULL')==[('0',)],'LEGACY_AUTH_DEFAULTS')
        r.require(pg.query('SELECT version FROM flyway_schema_history ORDER BY installed_rank')==[(str(i),) for i in range(1,12)],'PG_HISTORY_1_11')
        source.close();pg.close();report['post_v10_original_columns']='PASS'
        (root/'compatibility-result.json').write_text(json.dumps(report,indent=2))
        print('POST_V10_DATA_AND_HISTORY_PASS')
    elif '--identity-check' in sys.argv:
        root=Path(sys.argv[sys.argv.index('--identity-check')+1])
        r.require(root.parent==r.BASE/'RestoreRehearsals' and root.name.startswith('postgres-'),'IDENTITY_PATH')
        report=json.loads((root/'compatibility-result.json').read_text())
        r.require(report.get('data_migration')=='PASS','DATA_GATE')
        source=r.MySQL(unprotect(SOURCE/'local-credential.dpapi'))
        r.require(Path(source.query('SELECT @@datadir')[0][0]).resolve()==(SOURCE/'data').resolve(),'SOURCE_PATH')
        marks=source.query("SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='splitsmart_restore_20261107t155155z' AND AUTO_INCREMENT IS NOT NULL")
        source.close()
        pg=Pg(report['username'],unprotect(root/'test-credential.dpapi'),report['copy_database'])
        checks={}
        for table,mark in marks:
            r.require(table in r.TABLES and table!='flyway_schema_history','SEQUENCE_TABLE')
            highest=int(pg.query('SELECT COALESCE(MAX(id),0) FROM '+table)[0][0])
            nextid=max(int(mark),highest+1)
            r.require(pg.query("SELECT setval(pg_get_serial_sequence('"+table+"','id'),$1,false)",[nextid])==[(str(nextid),)],'SEQUENCE_STATE')
            checks[table]='PASS'
        pg.close();report['identity_high_water_marks']=checks
        (root/'compatibility-result.json').write_text(json.dumps(report,indent=2))
        print('IDENTITY_HIGH_WATER_MARKS_PASS')
    elif '--tests-only' in sys.argv:
        root=Path(sys.argv[sys.argv.index('--tests-only')+1])
        r.require(root.parent==r.BASE/'RestoreRehearsals' and root.name.startswith('postgres-'),'TEST_PATH')
        report=json.loads((root/'compatibility-result.json').read_text())
        r.require(report.get('data_migration')=='PASS','DATA_GATE')
        password=unprotect(root/'test-credential.dpapi')
        check=Pg('ss_pg_owner',unprotect(root/'owner-credential.dpapi'),'postgres')
        r.require(Path(check.query("SELECT current_setting('data_directory')")[0][0]).resolve()==(root/'data').resolve(),'TEST_ISOLATION')
        check.close()
        tests=sys.argv[sys.argv.index('--test-list')+1] if '--test-list' in sys.argv else 'PostgresRecoveryIT,PostgresInvitationIT,PostgresNotificationIT,PostgresFinancialIT'
        try:
            maven(root,password,report['username'],report['test_database'],tests=tests)
            report.update(status='PASS',application_tests='PASS')
            (root/'compatibility-result.json').write_text(json.dumps(report,indent=2))
            print('POSTGRES_APPLICATION_TESTS_PASS')
        except r.Stop as ex:
            print(str(ex));sys.exit(1)
    elif '--diagnose-maven' in sys.argv:
        root=Path(sys.argv[sys.argv.index('--diagnose-maven')+1])
        text=unprotect(root/'maven-failure.dpapi').decode(errors='replace')
        import re
        seen=set()
        for line in text.splitlines():
            if any(x in line.lower() for x in ['password','token','secret','jdbc:','insert into']):continue
            if re.search(r'^Caused by:|Schema-validation:|expected:|SQLState:|SQL Error:|^\[ERROR\] (Tests run:|  Postgres)|JAVA_HOME',line) and line not in seen:
                seen.add(line)
                print(line[:600].encode('ascii',errors='replace').decode())
                if len(seen)>=16:break
    else:sys.exit(main())
