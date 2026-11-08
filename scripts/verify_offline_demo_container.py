"""Actual rootless static-demo execution + browser verification. No DB/provider access."""
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from urllib.request import Request,urlopen
from urllib.error import HTTPError
import local_database_support as local

WORK=Path(__file__).resolve().parents[1]
IMAGE='sha256:454abbc3a94320676a7260b78dfbb50de3015f67dfd91c6d5f614f988ddc762f'
NAME='ss-offline-demo-20261108-verified'
LABEL='splitsmart.demo.verification=20261108'
BASE=['wsl','-d','Ubuntu','--','runuser','-u','ss_oci_verify','--','env','-i',
 'HOME=/home/ss_oci_verify','USER=ss_oci_verify','LOGNAME=ss_oci_verify',
 'XDG_RUNTIME_DIR=/run/user/2001','PATH=/usr/bin:/bin','podman']

def run(args,check=True,timeout=25):
 p=subprocess.run(BASE+args,capture_output=True,timeout=timeout)
 if check and p.returncode:raise RuntimeError('OWNED_DEMO_COMMAND_FAILED')
 return p

def request(path,method='GET'):
 try:
  with urlopen(Request('http://127.0.0.1:18087'+path,method=method,
       data=b'FICTIONAL_ONLY' if method=='POST' else None),timeout=2) as r:return r.status,r.headers,r.read()
 except HTTPError as e:return e.code,e.headers,e.read()

def main():
 root=local.BASE/'SyntheticPortfolioDemo';local.private_directory(root)
 attempt=root/'container-attempt.json'
 with attempt.open('x',encoding='utf-8') as f:json.dump({'image':IMAGE,'name':NAME,'status':'STARTED','synthetic_only':True},f)
 with socket.socket() as s:s.bind(('127.0.0.1',18087))
 if run(['container','exists',NAME],check=False).returncode==0:raise RuntimeError('NAME_ALREADY_OCCUPIED')
 if run(['info','--format','{{.Host.Security.Rootless}}']).stdout.strip()!=b'true':raise RuntimeError('ROOTLESS_REQUIRED')
 created=[];report={}
 options=['--network=slirp4netns:allow_host_loopback=false','--cgroups=disabled','--read-only',
          '--cap-drop=all','--security-opt=no-new-privileges','--user=10001:10001']
 try:
  created.append(NAME)
  run(['run','--detach','--name',NAME,'--label',LABEL,'--log-driver=none',
       '--publish','127.0.0.1:18087:8080',*options,IMAGE])
  deadline=time.monotonic()+40
  while time.monotonic()<deadline:
   try:
    if request('/')[0]==200:break
   except OSError:pass
   time.sleep(.4)
  else:raise RuntimeError('DEMO_STARTUP_TIMEOUT')
  status,headers,_=request('/')
  if status!=200 or "connect-src 'none'" not in headers.get('Content-Security-Policy',''):raise RuntimeError('CSP_REQUIRED')
  for path,method,expected in [('/api/auth/login','GET',404),('/api/demo/reset','POST',405),('/.env','GET',404),('/?email=FICTIONAL_ONLY','GET',400)]:
   if request(path,method)[0]!=expected:raise RuntimeError('READ_ONLY_HTTP_BOUNDARY')
  if run(['exec',NAME,'id','-u']).stdout.strip()!=b'10001':raise RuntimeError('NONROOT_REQUIRED')
  proc=run(['exec',NAME,'cat','/proc/1/status']).stdout
  if b'CapEff:\t0000000000000000' not in proc or b'NoNewPrivs:\t1' not in proc:raise RuntimeError('CAPABILITY_BOUNDARY')
  for suffix,setting in [('mixed','DATABASE_URL=not-a-real-connection'),('disabled','SPLITSMART_DEMO_ONLY=disabled')]:
   name=NAME+'-'+suffix;created.append(name)
   result=run(['run','--name',name,'--label',LABEL,'--log-driver=passthrough',*options,'--env',setting,IMAGE],check=False,timeout=20)
   if result.returncode==0 or b'DEMO_SERVER_BLOCKED' not in result.stderr+result.stdout:raise RuntimeError('STARTUP_NEGATIVE_FAILED')
  environment={k:v for k,v in os.environ.items() if not k.startswith(('DB_','DATABASE_','PG','SPRING_','FLYWAY_','JWT_','BREVO_','RECOVERY_','VITE_'))}
  environment['SPLITSMART_DEMO_TEST_URL']='http://127.0.0.1:18087'
  browser=subprocess.run(['node','node_modules/@playwright/test/cli.js','test','--config=playwright.demo.config.js','--reporter=json'],
      cwd=WORK/'frontend',env=environment,capture_output=True,timeout=180)
  if browser.returncode:
   (root/'browser-failure.dpapi').write_bytes(local.protect(browser.stdout+browser.stderr))
   raise RuntimeError('CONTAINER_BROWSER_FAILED')
  stats=json.loads(browser.stdout)['stats']
  if stats['expected']!=4 or stats['unexpected']!=0:raise RuntimeError('BROWSER_COUNT')
  report={'status':'PASS','image':IMAGE,'rootless':True,'uid':10001,'read_only':True,
   'http_security':'PASS','mixed_environment_rejected':True,'disabled_startup_rejected':True,
   'browser_cases':4,'database_used':False,'email_enabled':False,'real_data_used':False,'actual_cloud_deployment':False}
 finally:
  for name in created:
   if run(['container','exists',name],check=False).returncode==0:
    item=json.loads(run(['inspect',name]).stdout)[0]
    if item['Config']['Labels'].get('splitsmart.demo.verification')!='20261108':raise RuntimeError('STOP_SCOPE_MISMATCH')
    if item['State']['Running']:run(['stop','--time','10',name],timeout=20)
  report['owned_containers_stopped']=True
 if report.get('status')!='PASS':raise RuntimeError('RESULT_NOT_VERIFIED')
 (root/'container-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
 print(json.dumps(report))

if __name__=='__main__':
 try:main()
 except Exception as error:
  print('OFFLINE_DEMO_CONTAINER_BLOCKED_'+(str(error) if isinstance(error,RuntimeError) else type(error).__name__))
  raise SystemExit(1)
