"""Two existing product browser flows on Java21/local SYNTHETIC PostgreSQL only.

No Docker, MySQL/cloud connections, plaintext logs, deployment or database cleanup.
"""
import base64
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess as sub
import sys
import threading
import time
from urllib.request import urlopen
import local_pg_rehearsal as local
from postgres_compatibility import unprotect


def free(port):
    with socket.socket() as check:check.bind(('127.0.0.1',port))


def isolated_environment(base,overrides):
    # Direct Spring properties / JVM startup options must not override our DB_URL
    # binding through a developer's inherited shell or production tooling env.
    blocked=('SPRING_','APP_','DB_','FLYWAY_','JWT_','RECOVERY_','BREVO_','VITE_','SPLITSMART_')
    result={k:v for k,v in base.items() if not k.startswith(blocked) and k not in
            ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','NODE_OPTIONS')}
    result.update(overrides)
    return result


class Capture:
    def __init__(self,secrets,limit=6*1024*1024):
        self.secrets=secrets;self.limit=limit;self.data=bytearray();self.tail=b'';self.leaked=False
    def feed(self,chunk):
        window=self.tail+chunk
        self.leaked=self.leaked or any(secret in window for secret in self.secrets)
        self.tail=window[-max(map(len,self.secrets)):]
        self.data.extend(chunk)
        if len(self.data)>self.limit:del self.data[:len(self.data)-self.limit]


def main():
    root=local.l.BASE/'AutonomousSyntheticTransfer'
    identity=json.loads((root/'identity.json').read_text())
    local.l.require(identity.get('synthetic_only') is True and identity['database'].startswith('ss_pg_transfer_synthetic_'),'SYNTHETIC_DATABASE_ONLY')
    admin=local.Pg('ss_pg_owner',unprotect(local.CLUSTER/'owner-credential.dpapi'),'postgres')
    local.guard(admin,'postgres');admin.close()
    password=unprotect(root/'credential.dpapi')
    c=local.Pg(identity['role'],password,identity['database']);local.guard(c,identity['database']);local.safe_policy(c);c.close()
    for port in (18081,15173):free(port)
    browser=root/'browser';local.l.private_directory(browser)
    output=browser/dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    local.l.private_directory(output)
    latest=root/'browser-latest.json'
    latest.write_text(json.dumps({'status':'RUNNING','report':str(output/'result.json')}))
    java=Path.home()/'.jdks/temurin-21/bin/java.exe'
    node=shutil.which('node')
    local.l.require(java.is_file() and node,'LOCAL_RUNTIMES')
    jwt=base64.b64encode(secrets.token_bytes(32)).decode()
    env=isolated_environment(os.environ,{})
    env.update(DB_URL='jdbc:postgresql://127.0.0.1:25432/'+identity['database'],DB_USERNAME=identity['role'],DB_PASSWORD=password.decode(),JWT_SECRET=jwt,
       SPRING_PROFILES_ACTIVE='postgresql',SERVER_ADDRESS='127.0.0.1',SERVER_PORT='18081',
       APP_CORS_ALLOWED_ORIGINS='http://127.0.0.1:15173',SPRING_FLYWAY_ENABLED='false',
       APP_MAINTENANCE_ENABLED='false',RECOVERY_EMAIL_ENABLED='false',RECOVERY_EMAIL_DELIVERY_VERIFIED='false')
    frontend=isolated_environment(os.environ,{})
    frontend.update(VITE_API_BASE_URL='http://127.0.0.1:18081',VITE_EMAIL_ACTIONS_ENABLED='false',
       SPLITSMART_E2E_API_URL='http://127.0.0.1:18081',SPLITSMART_E2E_BASE_URL='http://127.0.0.1:15173')
    processes=[];buffers=[];threads=[]
    def start(args,cwd,environment):
        p=sub.Popen(args,cwd=cwd,env=environment,stdout=sub.PIPE,stderr=sub.STDOUT,creationflags=local.l.CREATE_NO_WINDOW)
        processes.append(p);buf=Capture((password,jwt.encode()));buffers.append(buf)
        def consume():
            for chunk in iter(lambda:p.stdout.read(4096),b''):
                buf.feed(chunk)
        thread=threading.Thread(target=consume,daemon=True);thread.start();threads.append(thread)
        return p
    def wait(url,seconds):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            if any(p.poll() is not None for p in processes):raise local.l.Stop('LOCAL_SERVICE_STOPPED')
            try:
                with urlopen(url,timeout=1) as response:
                    if response.status==200:return
            except OSError:pass # Bounded startup polling, never a mutation retry.
            time.sleep(.25)
        raise local.l.Stop('LOCAL_STARTUP_TIMEOUT')
    try:
        start([str(java),'-jar',str(local.WORK/'backend/target/splitsmart-backend-0.0.1-SNAPSHOT.jar')],local.WORK/'backend',env)
        wait('http://127.0.0.1:18081/actuator/health/readiness',55)
        start([node,str(local.WORK/'frontend/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','15173','--strictPort'],local.WORK/'frontend',frontend)
        wait('http://127.0.0.1:15173',15)
        result=sub.run([node,str(local.WORK/'frontend/node_modules/@playwright/test/cli.js'),'test',
            'e2e/invitations.spec.js','--config=playwright.config.js','--reporter=json'],
            cwd=local.WORK/'frontend',env=frontend,capture_output=True,timeout=240,creationflags=local.l.CREATE_NO_WINDOW)
        if result.returncode:
            (output/'failure.dpapi').write_bytes(local.l.protect(result.stdout+result.stderr))
            raise local.l.Stop('SYNTHETIC_PG_BROWSER_FAILED')
        report=json.loads(result.stdout)
        local.l.require(report['stats']['expected']==2 and report['stats']['unexpected']==0,'BROWSER_RESULTS')
    finally:
        for p in reversed(processes):
            if p.poll() is None:
                p.terminate()
                try:p.wait(timeout=12)
                except sub.TimeoutExpired:p.kill();p.wait(timeout=5)
        for thread in threads:thread.join(timeout=2)
        local.l.require(not any(t.is_alive() for t in threads),'OUTPUT_CAPTURE_NOT_FINISHED')
        leaked=any(buf.leaked for buf in buffers)
        if leaked:
            (output/'service-logs.dpapi').write_bytes(local.l.protect(b'\n'.join(bytes(b.data) for b in buffers)))
        local.l.require(not leaked,'SYNTHETIC_CREDENTIAL_LOG_LEAK')
        for port in (18081,15173):free(port)
        print('OWNED_TEST_PROCESSES_STOPPED_PORTS_FREE_DATABASE_RETAINED',flush=True)
    # Publish PASS only after cleanup and streaming credential scan passed.
    (output/'result.json').write_text(json.dumps({'status':'PASS','tests':2,'backend':'Java21','database':'synthetic local PostgreSQL','real_data_used':False,'email_enabled':False},indent=2))
    latest.write_text(json.dumps({'status':'PASS','report':str(output/'result.json')}))
    print('TWO_REAL_LOCAL_POSTGRES_BROWSER_FLOWS_PASS',flush=True)


if __name__=='__main__':
    try:main()
    except Exception as error:
        print('STOP_'+(str(error) if isinstance(error,local.l.Stop) else type(error).__name__))
        sys.exit(1)
