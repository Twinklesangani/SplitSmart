"""Linux Java21 verification against the existing guarded SYNTHETIC target only.
Secrets cross stdin/environment only; subprocess logs remain bounded in RAM.
"""
import base64
import json
import secrets
import re
import subprocess
from pathlib import Path
import prepare_postgres_target as t
from run_native_postgres_e2e import isolated_environment

LINUX = r'''
import json,os,subprocess,threading,time,urllib.request,socket,zipfile,pathlib
v=json.load(__import__('sys').stdin)
env=v['env']; jar=v['jar']; java='/root/.local/share/splitsmart/java21/bin/java'
root=pathlib.Path('/root/.local/share/splitsmart/native-verification')
root.mkdir(mode=0o700,exist_ok=True)
assert root.stat().st_mode & 0o077 == 0
assert not (root/'result.json').exists(), 'PREVIOUS_ATTEMPT_EXISTS'
with socket.socket() as s:
 s.bind(('127.0.0.1',0));port=s.getsockname()[1]
env['SERVER_PORT']=str(port)
with zipfile.ZipFile(jar) as z:
 driver=next(n for n in z.namelist() if n.startswith('BOOT-INF/lib/postgresql-') and n.endswith('.jar'))
 (root/'postgresql.jar').write_bytes(z.read(driver))
source="""import java.sql.*; class TlsProbe { public static void main(String[] args) throws Exception {
 var p=new java.util.Properties();p.setProperty("user",System.getenv("DB_USERNAME"));p.setProperty("password",System.getenv("DB_PASSWORD"));p.setProperty("sslmode","verify-full");p.setProperty("sslrootcert",args.length==0?System.getenv("DB_TLS_CA_FILE"):"/no-such-synthetic-ca.pem");
 try(var c=DriverManager.getConnection(System.getenv("DB_URL"),p)) {
 if(args.length!=0)throw new IllegalStateException("BAD_CA_ACCEPTED");
 try(var s=c.createStatement();var r=s.executeQuery("SELECT current_database(), current_user, ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")){if(!r.next()||!r.getString(1).equals(System.getenv("EXPECTED_DATABASE"))||!r.getString(2).equals(System.getenv("DB_USERNAME"))||!r.getBoolean(3))throw new IllegalStateException("IDENTITY_TLS");}
 System.out.println("TLS_VERIFY_FULL_IDENTITY_PASS");
 }catch(SQLException e){if(args.length==0)throw new IllegalStateException("TLS_PROBE_FAILED");System.out.println("BAD_CA_REJECTED_PASS");}
}}"""
(root/'TlsProbe.java').write_text(source)
for args in ([],['bad-ca']):
 r=subprocess.run([java,'--class-path',str(root/'postgresql.jar'),str(root/'TlsProbe.java')]+args,env=env,capture_output=True,timeout=30)
 assert r.returncode==0,'TLS_CHECK_FAILED'
 print(r.stdout.decode().strip(),flush=True)
logs=bytearray(); tail=b'';leaked=False
passwords=[env[k].encode() for k in ('DB_PASSWORD','FLYWAY_DB_PASSWORD','JWT_SECRET')]
p=subprocess.Popen([java,'-jar',jar],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
def consume():
 global leaked,tail
 for chunk in iter(lambda:p.stdout.read1(4096),b''):
  window=tail+chunk;leaked=leaked or any(x in window for x in passwords);tail=window[-max(map(len,passwords)):]
  logs.extend(chunk)
  if len(logs)>4*1024*1024:del logs[:-4*1024*1024]
thread=threading.Thread(target=consume,daemon=True);thread.start()
checks={}
try:
 deadline=time.monotonic()+100
 while time.monotonic()<deadline:
  if p.poll() is not None:raise RuntimeError('BACKEND_EARLY_EXIT')
  try:
   with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health/readiness',timeout=2) as r:
    if r.status==200 and json.load(r)['status']=='UP':break
  except OSError:pass
  time.sleep(.5)
 else:raise RuntimeError('STARTUP_TIMEOUT')
 for name in ('readiness','liveness'):
  with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health/'+name,timeout=3) as r:assert r.status==200
  checks[name]='PASS'
 try:urllib.request.urlopen(f'http://127.0.0.1:{port}/api/groups',timeout=3);raise RuntimeError('GATE_BYPASSED')
 except urllib.error.HTTPError as e:assert e.code==503
 checks['maintenance_gate']='PASS'
 checks['flyway_v11_no_pending']='PASS' if b'No migration necessary' in logs and b'11' in logs else 'BLOCKED'
 assert checks['flyway_v11_no_pending']=='PASS','FLYWAY_STARTUP_NOT_VERIFIED'
finally:
 if p.poll() is None:
  p.terminate()
  try:p.wait(timeout=25)
  except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
 thread.join(timeout=3)
 assert not thread.is_alive() and not leaked,'LOG_CAPTURE_SAFETY'
with socket.socket() as s:
 s.settimeout(2);assert s.connect_ex(('127.0.0.1',port))!=0,'OWNED_LISTENER_REMAINS'
checks.update(status='PASS',linux_native=True,container_verified=False,synthetic_only=True,logs_persisted=False,tls_verify_full='PASS',bad_ca_rejected='PASS',owned_process_stopped=True)
(root/'result.json').write_text(json.dumps(checks,indent=2))
print(json.dumps(checks),flush=True)
'''

def linux_path(path):
    path=Path(path).resolve()
    return '/mnt/'+path.drive[0].lower()+'/'+path.as_posix()[3:]

def synthetic_url(params,state):
    database=state['test_database']
    if not re.fullmatch(r'splitsmart_synthetic_[a-z0-9_]+',database):
        raise ValueError('SYNTHETIC_DATABASE_REQUIRED')
    host=params['host']
    if not re.fullmatch(r'[A-Za-z0-9.-]+',host):
        raise ValueError('PINNED_HOST_REQUIRED')
    port=int(params['port'])
    if not 1 <= port <= 65535:raise ValueError('PINNED_PORT_REQUIRED')
    return f'jdbc:postgresql://{host}:{port}/{database}?ApplicationName=splitsmart_linux_synthetic'


def main():
    p=t.params();s=t.state()
    c=t.connection(p,'test_migration',s['test_database'])
    try:
        t.guard(c,s,True)
        assert c.query('SELECT max(version::integer) FROM public.flyway_schema_history WHERE success')==[('11',)]
    finally:c.close()
    role=t.ROLES['test_runtime'];migration=t.ROLES['test_migration'];credentials=t.credentials()
    url=synthetic_url(p,s)
    env=isolated_environment({},dict(PATH='/usr/bin:/bin',HOME='/root',LANG='C.UTF-8',
        DB_URL=url,DB_USERNAME=role,DB_PASSWORD=credentials[role],
        FLYWAY_DB_URL=url,FLYWAY_DB_USERNAME=migration,FLYWAY_DB_PASSWORD=credentials[migration],
        JWT_SECRET=base64.b64encode(secrets.token_bytes(32)).decode(),
        DB_TLS_CA_FILE=linux_path(t.CA),EXPECTED_DATABASE=s['test_database'],
        SPRING_PROFILES_ACTIVE='postgresql,postgres-prod',SERVER_ADDRESS='127.0.0.1',SERVER_PORT='18082',
        APP_CORS_ALLOWED_ORIGINS='http://127.0.0.1:15173',APP_MAINTENANCE_ENABLED='true',
        RECOVERY_EMAIL_ENABLED='false',RECOVERY_EMAIL_DELIVERY_VERIFIED='false'))
    payload={'env':env,'jar':linux_path(t.WORK/'backend/target/splitsmart-backend-0.0.1-SNAPSHOT.jar')}
    result=subprocess.run(['wsl','-d','Ubuntu','--','python3','-I','-c',LINUX],input=json.dumps(payload).encode(),capture_output=True,timeout=220)
    if result.returncode:
        # Never print raw Java/Python output containing environment or SQL errors.
        codes=('TLS_CHECK_FAILED','BACKEND_EARLY_EXIT','STARTUP_TIMEOUT','FLYWAY_STARTUP_NOT_VERIFIED','LOG_CAPTURE_SAFETY','PREVIOUS_ATTEMPT_EXISTS')
        code=next((x for x in codes if x.encode() in result.stderr),'LINUX_SUBPROCESS_FAILED')
        print('LINUX_NATIVE_VERIFICATION_BLOCKED_'+code)
        (t.ROOT/'linux-failure.dpapi').write_bytes(t.local.protect(result.stdout+result.stderr))
        raise SystemExit(1)
    print(result.stdout.decode())

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('LINUX_NATIVE_STOP_'+type(error).__name__)
        raise SystemExit(1)
