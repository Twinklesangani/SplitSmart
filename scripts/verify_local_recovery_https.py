"""Non-sending HTTPS preflight against guarded synthetic PostgreSQL only."""
import base64,json,os,secrets,subprocess as s,threading,time,ssl
from pathlib import Path
from urllib.request import urlopen
import local_pg_rehearsal as local
from postgres_compatibility import unprotect
from run_native_postgres_e2e import isolated_environment,Capture,free

def main():
    root=local.l.BASE/'AutonomousSyntheticTransfer'
    identity=json.loads((root/'identity.json').read_text())
    local.l.require(identity.get('synthetic_only') is True and identity['database'].startswith('ss_pg_transfer_synthetic_'),'SYNTHETIC_ONLY')
    admin=local.Pg('ss_pg_owner',unprotect(local.CLUSTER/'owner-credential.dpapi'),'postgres')
    local.guard(admin,'postgres');admin.close()
    password=unprotect(root/'credential.dpapi')
    db=local.Pg(identity['role'],password,identity['database'])
    local.guard(db,identity['database']);local.safe_policy(db);db.close()
    free(18443);free(15175)
    out=root/('https-'+secrets.token_hex(8));local.l.private_directory(out)
    pfx=out/'localhost.pfx';pfxpw=secrets.token_urlsafe(40)
    env=isolated_environment(os.environ,{'SS_PFX':str(pfx),'SS_PFX_PASSWORD':pfxpw})
    code="""$ErrorActionPreference='Stop';
    $cert=Get-ChildItem Cert:\\CurrentUser\\My | Where-Object {
      if (-not $_.HasPrivateKey -or $_.NotAfter -le (Get-Date) -or -not ($_.DnsNameList.Unicode -contains 'localhost')) { return $false }
      $c=[Security.Cryptography.X509Certificates.X509Chain]::new();$c.ChainPolicy.RevocationMode='NoCheck';$ok=$c.Build($_);$c.Dispose();return $ok
    } | Select-Object -First 1;
    if (-not $cert) { throw 'NO_TRUSTED_LOCALHOST_CERT' }
    Export-PfxCertificate -Cert $cert -FilePath $env:SS_PFX -Password (ConvertTo-SecureString $env:SS_PFX_PASSWORD -AsPlainText -Force) | Out-Null
    """
    result=s.run([local.l.POWERSHELL,'-NoProfile','-NonInteractive','-Command',code],env=env,capture_output=True,timeout=30,creationflags=local.l.CREATE_NO_WINDOW)
    local.l.require(result.returncode==0 and pfx.is_file(),'TRUSTED_CERT_EXPORT')
    jwt=base64.b64encode(secrets.token_bytes(32)).decode()
    env=isolated_environment(os.environ,dict(DB_URL='jdbc:postgresql://127.0.0.1:25432/'+identity['database'],DB_USERNAME=identity['role'],DB_PASSWORD=password.decode(),JWT_SECRET=jwt,
      SPRING_PROFILES_ACTIVE='postgresql',SPRING_FLYWAY_ENABLED='false',SERVER_ADDRESS='127.0.0.1',SERVER_PORT='18443',
      SERVER_SSL_ENABLED='true',SERVER_SSL_KEY_STORE=pfx.as_uri(),SERVER_SSL_KEY_STORE_TYPE='PKCS12',SERVER_SSL_KEY_STORE_PASSWORD=pfxpw,
      APP_CORS_ALLOWED_ORIGINS='https://localhost:15175',
      RECOVERY_EMAIL_ENABLED='false',RECOVERY_EMAIL_DELIVERY_VERIFIED='false'))
    java=Path.home()/'.jdks/temurin-21/bin/java.exe'
    capture=Capture((password,jwt.encode(),pfxpw.encode()))
    process=s.Popen([str(java),'-jar',str(local.WORK/'backend/target/splitsmart-backend-0.0.1-SNAPSHOT.jar')],env=env,stdout=s.PIPE,stderr=s.STDOUT,creationflags=local.l.CREATE_NO_WINDOW)
    def consume():
        for chunk in iter(lambda:process.stdout.read(4096),b''):capture.feed(chunk)
    thread=threading.Thread(target=consume,daemon=True);thread.start()
    frontend=None
    try:
        deadline=time.monotonic()+65
        while True:
            local.l.require(process.poll() is None,'HTTPS_APP_STOPPED')
            try:
                with urlopen('https://localhost:18443/actuator/health/readiness',timeout=2,context=ssl.create_default_context()) as response:
                    local.l.require(response.status==200,'HTTPS_HEALTH');break
            except OSError:
                local.l.require(time.monotonic()<deadline,'HTTPS_TRUST_OR_STARTUP');time.sleep(.5)
        frontenv=isolated_environment(os.environ,dict(SS_RECOVERY_HTTPS='synthetic-only',SS_PFX=str(pfx),SS_PFX_PASSWORD=pfxpw,
            VITE_API_BASE_URL='https://localhost:18443',VITE_EMAIL_ACTIONS_ENABLED='false'))
        frontend=s.Popen(['node',str(local.WORK/'frontend/recovery-https-server.mjs')],cwd=local.WORK/'frontend',env=frontenv,
            stdout=s.DEVNULL,stderr=s.DEVNULL,creationflags=local.l.CREATE_NO_WINDOW)
        deadline=time.monotonic()+25
        while True:
            local.l.require(frontend.poll() is None,'REACT_HTTPS_STOPPED')
            try:
                with urlopen('https://localhost:15175/reset-password',timeout=2,context=ssl.create_default_context()) as response:
                    local.l.require(response.status==200 and b'root' in response.read(),'REACT_HTTPS_ROUTE');break
            except OSError:
                local.l.require(time.monotonic()<deadline,'REACT_HTTPS_TRUST_OR_STARTUP');time.sleep(.5)
        print('SPRING_JAVA21_AND_REACT_HTTPS_TRUST_ROUTING_PASS_EMAIL_DISABLED',flush=True)
    finally:
        if frontend is not None:
            frontend.terminate()
            try:frontend.wait(timeout=10)
            except s.TimeoutExpired:frontend.kill();frontend.wait(timeout=5)
        process.terminate()
        try:process.wait(timeout=15)
        except s.TimeoutExpired:process.kill();process.wait(timeout=5)
        thread.join(timeout=3)
        local.l.require(not capture.leaked,'PRIVATE_CONFIGURATION_LOG_LEAK')
        free(18443);free(15175)
    (out/'result.json').write_text(json.dumps({'https':'PASS','health':'PASS','emails_attempted':0,'synthetic_only':True}))

if __name__=='__main__':
    try:main()
    except Exception as e:
        print('HTTPS_PREFLIGHT_STOP_'+(str(e) if isinstance(e,local.l.Stop) else type(e).__name__))
        raise SystemExit(1)
