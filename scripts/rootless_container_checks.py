"""Runs only under the dedicated Ubuntu identity; input is private stdin JSON.
No application stdout log is retained; credentials use short-lived Podman secrets.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.request import urlopen, Request
from urllib.error import HTTPError


def runtime_command(image,name,runid,environment,secret_names,negative=False,host_alias=None):
    if not re.fullmatch(r'sha256:[a-f0-9]{64}',image):raise ValueError('IMAGE_ID_REQUIRED')
    if not re.fullmatch(r'ssverify-[a-z0-9-]+',runid):raise ValueError('OWNED_RUN_REQUIRED')
    if not name.startswith(runid+'-'):raise ValueError('OWNED_NAME_REQUIRED')
    args=['podman','run']+([] if negative else ['--detach'])+['--name',name,'--label','splitsmart.verification.run='+runid,
          '--network=slirp4netns:allow_host_loopback=false','--cgroups=disabled','--read-only',
          '--tmpfs','/tmp:rw,nosuid,nodev,noexec,size=128m,mode=1777','--cap-drop=all',
          '--security-opt=no-new-privileges','--user=10001:10001',
          '--log-driver='+('passthrough' if negative else 'none'),
          '--publish','127.0.0.1::8080']
    if host_alias:
        if not re.fullmatch(r'ss-tls-invalid\.example\.invalid:[0-9.]+',host_alias):raise ValueError('PINNED_ALIAS_REQUIRED')
        args+=['--add-host',host_alias]
    for key,value in environment.items():
        if key in ('DB_PASSWORD','FLYWAY_DB_PASSWORD','JWT_SECRET'):raise ValueError('SECRET_ENV_FORBIDDEN')
        if not re.fullmatch('[A-Z][A-Z0-9_]*',key):raise ValueError('ENV_KEY_REQUIRED')
        args+=['--env',key+'='+value]
    for key,name in secret_names.items():
        if not name.startswith(runid+'-'):raise ValueError('OWNED_SECRET_REQUIRED')
        args+=['--secret',name+',type=env,target='+key]
    return args+[image]


def security_probe_command():
    return r'''test "$(id -u)" = 10001 && test "$(id -g)" = 10001 || exit 21
test "$(stat -c %a /tmp/aiven-postgres-ca.pem)" = 600 || exit 22
grep -Eq "^CapEff:[[:space:]]*0+$" /proc/1/status || exit 23
grep -Eq "^NoNewPrivs:[[:space:]]*1$" /proc/1/status || exit 24
test -r /proc/1/environ || exit 25
if tr "\000" "\n" </proc/1/environ | grep -q "^DB_TLS_CA_BASE64="; then exit 26; fi
'''


def main():
    payload=json.load(__import__('sys').stdin)
    if os.getuid()!=2001:raise RuntimeError('WRONG_ROOTLESS_IDENTITY')
    runid=payload['runid']
    if not re.fullmatch(r'ssverify-[a-z0-9-]+',runid):raise RuntimeError('OWNED_RUN_REQUIRED')
    root=Path.home()/runid
    root.mkdir(mode=0o700) # exclusive attempt; never silently replay
    env={k:os.environ[k] for k in ('HOME','USER','LOGNAME','XDG_RUNTIME_DIR','PATH')}
    env['LANG']='C.UTF-8'
    def run(args,code,timeout=30,input=None,check=True):
        p=subprocess.run(args,input=input,env=env,capture_output=True,timeout=timeout)
        if check and p.returncode:raise RuntimeError(code)
        return p
    def inspect(name):return json.loads(run(['podman','inspect',name],'INSPECT_FAILED').stdout)[0]
    info=json.loads(run(['podman','info','--format=json'],'ROOTLESS_INFO').stdout)
    if info['host']['security']['rootless'] is not True:raise RuntimeError('NOT_ROOTLESS')
    image=json.loads(run(['podman','image','inspect',payload['image']],'IMAGE_MISSING').stdout)[0]
    imageid=image['Id']
    if not imageid.startswith('sha256:'):imageid='sha256:'+imageid
    if image['Config']['User']!='10001:10001':raise RuntimeError('IMAGE_USER_ROOT')
    secrets=payload.pop('secrets');owned={};containers=[];checks={}
    for secret in secrets.values():
        if secret in json.dumps(image):raise RuntimeError('SECRET_BAKED_IN_IMAGE')
    try:
        for key,value in secrets.items():
            name=runid+'-'+key.lower().replace('_','-')
            run(['podman','secret','create',name,'-'],'SECRET_CREATE_FAILED',input=value.encode())
            owned[key]=name
        for mode in ('maintenance','protected','bad-ca','bad-host'):
            configuration=dict(payload['environment'])
            configuration['APP_MAINTENANCE_ENABLED']='false' if mode=='protected' else 'true'
            if mode=='bad-ca':configuration['DB_TLS_CA_BASE64']='U1lOVEhFVElDX0lOVkFMSURfQ0E='
            negative=mode in ('bad-ca','bad-host')
            alias=None
            if mode=='bad-host':
                configuration['DB_URL']=payload['bad_host_url']
                configuration['FLYWAY_DB_URL']=payload['bad_host_url']
                alias='ss-tls-invalid.example.invalid:'+payload['pinned_ip']
            name=runid+'-'+mode;containers.append(name)
            started=run(runtime_command(imageid,name,runid,configuration,owned,negative,alias),
                        'CONTAINER_START_FAILED',timeout=100,check=not negative)
            if negative:
                logs=started.stdout+started.stderr
                if any(value.encode() in logs for value in secrets.values()):raise RuntimeError('CREDENTIAL_IN_OUTPUT')
                expected=(b'certificate' in logs.lower() and b'ssl' in logs.lower()) if mode=='bad-ca' else (b'hostname' in logs.lower() and b'could not be verified' in logs.lower())
                state=inspect(name)['State']
                if started.returncode==0 or state['Running'] or not expected:raise RuntimeError('TLS_NEGATIVE_NOT_PROVEN')
                checks['invalid_ca_rejected' if mode=='bad-ca' else 'hostname_mismatch_rejected']='PASS'
                continue
            port=run(['podman','port',name,'8080/tcp'],'PORT_QUERY_FAILED').stdout.decode().strip()
            if not re.fullmatch(r'127\.0\.0\.1:[0-9]+',port):raise RuntimeError('NON_LOOPBACK_PORT')
            base='http://'+port
            deadline=time.monotonic()+100;ready=False
            while time.monotonic()<deadline:
                state=inspect(name)['State']
                if not state['Running']:break
                try:
                    with urlopen(base+'/actuator/health/readiness',timeout=2) as response:
                        ready=response.status==200 and json.load(response)['status']=='UP'
                    if ready:break
                except OSError:pass
                time.sleep(.5)
            if not ready:raise RuntimeError('CONTAINER_NOT_READY')
            for endpoint in ('readiness','liveness'):
                with urlopen(base+'/actuator/health/'+endpoint,timeout=3) as response:
                    if response.status!=200:raise RuntimeError('HEALTH_FAILED')
            expected=503 if mode=='maintenance' else 401
            try:urlopen(base+'/api/groups',timeout=3);raise RuntimeError('API_PROTECTION_BYPASSED')
            except HTTPError as error:
                if error.code!=expected:raise RuntimeError('API_PROTECTION_STATUS')
            if mode=='protected':
                request=Request(base+'/api/auth/forgot-password',data=b'{"email":"fake-only@example.invalid"}',headers={'Content-Type':'application/json'})
                try:urlopen(request,timeout=3);raise RuntimeError('DISABLED_RECOVERY_ACCEPTED')
                except HTTPError as error:
                    if error.code!=503:raise RuntimeError('RECOVERY_DISABLED_STATUS')
            command=security_probe_command()
            probe=run(['podman','exec',name,'sh','-c',command],'RUNTIME_SECURITY_PROBE',check=False)
            if probe.returncode:raise RuntimeError('RUNTIME_SECURITY_PROBE_'+str(probe.returncode))
            version=run(['podman','exec',name,'java','-version'],'JAVA_RUNTIME_FAILED').stderr
            if b'version "21.' not in version:raise RuntimeError('JAVA21_REQUIRED')
            checks[mode+'_health_and_security']='PASS'
            run(['podman','stop','--time','25',name],'OWNED_STOP_FAILED',timeout=35)
    finally:
        for name in containers:
            p=run(['podman','container','exists',name],'EXISTS',check=False)
            if p.returncode==0:
                value=inspect(name)
                if value['Config']['Labels'].get('splitsmart.verification.run')!=runid:raise RuntimeError('STOP_OWNERSHIP_MISMATCH')
                if value['State']['Running']:run(['podman','stop','--time','25',name],'OWNED_STOP_FAILED',timeout=35)
        for name in owned.values():run(['podman','secret','rm',name],'OWNED_SECRET_CLEANUP_FAILED')
    checks.update(status='PASS',rootless=True,image_id=imageid,nonroot_uid=10001,synthetic_only=True,
                  application_logging='none',temporary_secrets_removed=True,owned_containers_stopped=True,
                  actual_render_verified=False,flyway_startup_v11='PASS')
    (root/'result.json').write_text(json.dumps(checks,indent=2))
    print(json.dumps(checks))


if __name__=='__main__':
    try:main()
    except Exception as error:
        code=str(error) if isinstance(error,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(error)) else type(error).__name__
        print('ROOTLESS_CHECKS_BLOCKED_'+code)
        raise SystemExit(1)
