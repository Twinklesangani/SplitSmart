"""New private synthetic-only MySQL instance; never opens backup/recovery data."""
import os,json,secrets,re,socket,subprocess as s,time,sys
from pathlib import Path
import native_restore_rehearsal as n
from run_native_postgres_e2e import isolated_environment

def main():
    if len(sys.argv)==3 and sys.argv[1]=='--recovery-only':
        from postgres_compatibility import unprotect
        root=Path(sys.argv[2]).resolve()
        n.require(root.parent==(n.BASE/'RestoreRehearsals').resolve() and root.name.startswith('mysql-synthetic-'),'RECOVERY_ONLY_IDENTITY')
        pw=unprotect(root/'credential.dpapi');db=n.MySQL(pw)
        facts=db.query('SELECT @@datadir,@@port')[0]
        n.require(Path(facts[0]).resolve()==(root/'data').resolve() and facts[1]=='23307','SYNTHETIC_INSTANCE')
        rows=db.query("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'ss_mysql_synthetic_%'")
        n.require(len(rows)==1 and re.fullmatch('ss_mysql_synthetic_[a-f0-9]+',rows[0][0]),'SYNTHETIC_DATABASE')
        name=rows[0][0]
        n.require(db.query('SELECT MAX(CAST(version AS UNSIGNED)) FROM `'+name+'`.flyway_schema_history')==[('10',)],'MIGRATION_PREVIOUSLY_PASSED')
        db.close();checks(root,pw,name,None,('NativeMySqlRecoveryIT',));return
    if len(sys.argv)==3 and sys.argv[1]=='--resume-bootstrap':
        from postgres_compatibility import unprotect
        root=Path(sys.argv[2]).resolve()
        n.require(root.parent==(n.BASE/'RestoreRehearsals').resolve() and root.name.startswith('mysql-synthetic-') and not (root/'credential.dpapi').exists(),'BOOTSTRAP_RESUME_IDENTITY')
        with (root/'bootstrap-resume.lock').open('x') as lock:lock.write('single continuation')
        finish(root,unprotect(root/'bootstrap.dpapi'));return
    n.require(len(sys.argv)==1,'EXPLICIT_MODE')
    with socket.socket() as sock:sock.bind(('127.0.0.1',23307))
    n.require(int(n.ps('[int64](Get-PSDrive C).Free'))>8*1024**3,'STORAGE_GATE')
    root=n.BASE/'RestoreRehearsals'/('mysql-synthetic-'+secrets.token_hex(8));n.private_directory(root)
    for name in ('data','tmp','logs'):(root/name).mkdir()
    options={'basedir':n.MYSQL.as_posix(),'datadir':(root/'data').as_posix(),'tmpdir':(root/'tmp').as_posix(),
      'pid-file':(root/'mysqld.pid').as_posix(),'log-error':(root/'logs/server.err').as_posix(),
      'bind-address':'127.0.0.1','port':'23307','mysqlx':'OFF','general-log':'OFF','slow-query-log':'OFF',
      'local-infile':'OFF','secure-file-priv':'NULL','require-secure-transport':'ON','innodb-buffer-pool-size':'128M','performance-schema':'OFF'}
    ini=root/'my.ini';ini.write_text('[mysqld]\n'+'\n'.join(k+'="'+v+'"' for k,v in options.items())+'\nskip-log-bin\n')
    init=s.run([str(n.MYSQL/'bin/mysqld.exe'),'--no-defaults','--initialize','--console','--basedir='+str(n.MYSQL),'--datadir='+str(root/'data')],capture_output=True,timeout=120,creationflags=n.CREATE_NO_WINDOW)
    n.require(init.returncode==0,'SYNTHETIC_INITIALIZATION')
    found=re.search(rb'temporary password is generated for root@localhost: ([^\r\n]+)',init.stdout+init.stderr)
    n.require(found is not None,'BOOTSTRAP_CREDENTIAL')
    bootstrap=found.group(1);(root/'bootstrap.dpapi').write_bytes(n.protect(bootstrap))
    proc=s.Popen([str(n.MYSQL/'bin/mysqld.exe'),'--defaults-file='+str(ini)],stdout=s.DEVNULL,stderr=s.DEVNULL,creationflags=n.CREATE_NO_WINDOW)
    deadline=time.monotonic()+40
    while True:
        n.require(proc.poll() is None,'SYNTHETIC_SERVER_STOPPED')
        try:
            with socket.create_connection(('127.0.0.1',23307),timeout=1):break
        except OSError:n.require(time.monotonic()<deadline,'SYNTHETIC_START_TIMEOUT');time.sleep(.5)
    finish(root,bootstrap)

def finish(root,bootstrap):
    ini=root/'my.ini'
    pid=int((root/'mysqld.pid').read_text().strip())
    code="$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+str(pid)+"';if($p.ExecutablePath -ne '"+str(n.MYSQL/'bin/mysqld.exe')+"' -or !$p.CommandLine.Contains('"+str(ini)+"')){throw 'IDENTITY'};'OK'"
    n.require(n.ps(code)=='OK','SYNTHETIC_PROCESS_IDENTITY')
    db=n.MySQL(bootstrap)
    pw=secrets.token_urlsafe(40).encode();db.query(b"ALTER USER CURRENT_USER() IDENTIFIED BY '"+pw+b"'");db.close()
    (root/'credential.dpapi').write_bytes(n.protect(pw))
    db=n.MySQL(pw)
    facts=db.query('SELECT @@datadir,@@port,@@general_log,@@slow_query_log,@@log_bin')[0]
    n.require(Path(facts[0]).resolve()==(root/'data').resolve() and facts[1:] == ('23307','0','0','0'),'ISOLATED_SYNTHETIC_INSTANCE')
    name='ss_mysql_synthetic_'+secrets.token_hex(8)
    db.query('CREATE DATABASE `'+name+'` CHARACTER SET utf8mb4');db.close()
    checks(root,pw,name,bootstrap,('NativeMySqlMigrationIT','NativeMySqlRecoveryIT'))

def checks(root,pw,name,bootstrap,tests):
    java=Path.home()/'.jdks/temurin-21'
    env=isolated_environment(os.environ,{'JAVA_HOME':str(java),'SS_MYSQL_SYNTHETIC_URL':'jdbc:mysql://127.0.0.1:23307/'+name+'?sslMode=REQUIRED','SS_MYSQL_SYNTHETIC_PASSWORD':pw.decode()})
    env['PATH']=str(java/'bin')+';'+env['PATH']
    work=Path(__file__).resolve().parents[1]
    for test in tests:
        result=s.run([n.POWERSHELL,'-NoProfile','-NonInteractive','-Command',"& './mvnw.cmd' '-o' '-Dtest="+test+"' 'test'"],cwd=work/'backend',env=env,capture_output=True,timeout=240,creationflags=n.CREATE_NO_WINDOW)
        n.require(pw not in result.stdout+result.stderr and (bootstrap is None or bootstrap not in result.stdout+result.stderr),'PRIVATE_LOG_SCAN')
        if result.returncode:
            (root/(test+'-failure.dpapi')).write_bytes(n.protect(result.stdout+result.stderr));raise n.Stop(test+'_FAILED')
        print(test+'_PASS',flush=True)
    receipt={'synthetic_only':True,'database':name,'port':23307,'root':str(root),'migration':'V7_TO_V10_PASS','recovery_tests':9,'status':'PASS'}
    (root/'result.json').write_text(json.dumps(receipt,indent=2))
    print('SYNTHETIC_MYSQL_RELEASE_PASS_INSTANCE_RETAINED',flush=True)

if __name__=='__main__':
    try:main()
    except Exception as e:print('SYNTHETIC_MYSQL_STOP_'+(str(e) if isinstance(e,n.Stop) else type(e).__name__));raise SystemExit(1)
