"""ONE approved reset + change notice. No replay after any attempt marker."""
import json,os,secrets,subprocess as s,threading,time,ssl
from pathlib import Path
from urllib.request import urlopen,Request
import local_pg_rehearsal as l
from postgres_compatibility import unprotect
from run_native_postgres_e2e import isolated_environment,Capture,free

def main():
    ledger=l.l.BASE/'ApprovedRecoveryEmail20261112'
    if not ledger.exists():l.l.private_directory(ledger)
    l.private_acl(ledger)
    l.l.require(not any(ledger.glob('*.attempt')),'EMAIL_ALREADY_ATTEMPTED_NO_REPLAY')
    # This execution lock is exclusive and is intentionally retained on interruption.
    with (ledger/'execution.lock').open('x') as lock:lock.write('one-approved-execution')
    root=l.l.BASE/'AutonomousSyntheticTransfer'
    identity=json.loads((root/'identity.json').read_text())
    l.l.require(identity.get('synthetic_only') is True and identity['database'].startswith('ss_pg_transfer_synthetic_'),'SYNTHETIC_ONLY')
    admin=l.Pg('ss_pg_owner',unprotect(l.CLUSTER/'owner-credential.dpapi'),'postgres');l.guard(admin,'postgres');admin.close()
    pw=unprotect(root/'credential.dpapi');db=l.Pg(identity['role'],pw,identity['database']);l.guard(db,identity['database']);l.safe_policy(db)
    l.l.require(db.query("SELECT count(*) FROM users WHERE email=$1",['twinklesangani@gmail.com'])==[('0',)],'RECIPIENT_ALREADY_EXISTS_REVIEW_REQUIRED');db.close()
    free(18443);free(15175)
    secret=l.l.BASE/'Secrets/brevo-api-v3.dpapi'
    q=str(secret).replace("'","''")
    key=l.l.ps("$s=ConvertTo-SecureString ([IO.File]::ReadAllText('"+q+"').Trim());$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s);try{[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}")
    l.l.require(key.startswith('xkeysib-'),'API_V3_ONLY')
    for endpoint in ('account','senders'):
        with urlopen(Request('https://api.brevo.com/v3/'+endpoint,headers={'api-key':key}),timeout=15) as response:data=json.load(response)
        if endpoint=='account':l.l.require(data.get('relay',{}).get('enabled') and any(x.get('type')=='free' for x in data.get('plan',[])),'ACTIVATED_FREE_ACCOUNT')
        else:l.l.require(any(x.get('email')=='twinklesangani@gmail.com' and x.get('active') for x in data.get('senders',[])),'ACTIVE_SENDER')
    pfx=ledger/'localhost.pfx';pfxpw=secrets.token_urlsafe(40)
    env=isolated_environment(os.environ,{'SS_PFX':str(pfx),'SS_PFX_PASSWORD':pfxpw})
    code="""$ErrorActionPreference='Stop';$cert=Get-ChildItem Cert:\\CurrentUser\\My | Where-Object {
      if(-not $_.HasPrivateKey -or $_.NotAfter -le (Get-Date) -or -not ($_.DnsNameList.Unicode -contains 'localhost')){return $false};
      $c=[Security.Cryptography.X509Certificates.X509Chain]::new();$c.ChainPolicy.RevocationMode='NoCheck';$ok=$c.Build($_);$c.Dispose();return $ok
    } | Select-Object -First 1;if(-not $cert){throw 'NO_TRUSTED_CERT'};
    Export-PfxCertificate -Cert $cert -FilePath $env:SS_PFX -Password (ConvertTo-SecureString $env:SS_PFX_PASSWORD -AsPlainText -Force) | Out-Null"""
    result=s.run([l.l.POWERSHELL,'-NoProfile','-NonInteractive','-Command',code],env=env,capture_output=True,timeout=30,creationflags=l.l.CREATE_NO_WINDOW)
    l.l.require(result.returncode==0,'LOCAL_CERT_EXPORT')
    frontenv=isolated_environment(os.environ,dict(SS_RECOVERY_HTTPS='synthetic-only',SS_PFX=str(pfx),SS_PFX_PASSWORD=pfxpw,VITE_API_BASE_URL='https://localhost:18443',VITE_EMAIL_ACTIONS_ENABLED='true'))
    frontend=s.Popen(['node',str(l.WORK/'frontend/recovery-https-server.mjs')],cwd=l.WORK/'frontend',env=frontenv,stdout=s.DEVNULL,stderr=s.DEVNULL,creationflags=l.l.CREATE_NO_WINDOW)
    process=None
    try:
        deadline=time.monotonic()+25
        while True:
            l.l.require(frontend.poll() is None,'LOCAL_FRONTEND_STOPPED')
            try:
                with urlopen('https://localhost:15175/reset-password',timeout=2,context=ssl.create_default_context()) as response:l.l.require(response.status==200,'LOCAL_HTTPS_ROUTE');break
            except OSError:l.l.require(time.monotonic()<deadline,'LOCAL_HTTPS_TIMEOUT');time.sleep(.5)
        java=Path.home()/'.jdks/temurin-21'
        env=isolated_environment(os.environ,dict(JAVA_HOME=str(java),PG_TEST_URL='jdbc:postgresql://127.0.0.1:25432/'+identity['database'],PG_TEST_USER=identity['role'],PG_TEST_PASSWORD=pw.decode(),SPRING_FLYWAY_ENABLED='false',
          SERVER_ADDRESS='127.0.0.1',SERVER_PORT='18443',SERVER_SSL_ENABLED='true',SERVER_SSL_KEY_STORE=pfx.as_uri(),SERVER_SSL_KEY_STORE_TYPE='PKCS12',SERVER_SSL_KEY_STORE_PASSWORD=pfxpw,
          APP_CORS_ALLOWED_ORIGINS='https://localhost:15175',RECOVERY_EMAIL_ENABLED='false',RECOVERY_EMAIL_DELIVERY_VERIFIED='false',BREVO_API_KEY=key,
          SS_EMAIL_APPROVAL='reset1-change1-local-only',SS_EMAIL_LEDGER=str(ledger),SS_SYNTHETIC_DATABASE=identity['database'],
          SS_FRONTEND_DIRECTORY=str(l.WORK/'frontend'),SS_RECOVERY_BROWSER_DRIVER=str(l.WORK/'frontend/recovery-email-browser.mjs')))
        env['PATH']=str(java/'bin')+';'+env['PATH']
        process=s.Popen([l.l.POWERSHELL,'-NoProfile','-NonInteractive','-Command',"& './mvnw.cmd' '-o' '-Dtest=ApprovedRecoveryEmailIT' 'test'"],cwd=l.WORK/'backend',env=env,stdout=s.PIPE,stderr=s.STDOUT,creationflags=l.l.CREATE_NO_WINDOW)
        capture=Capture((key.encode(),pw,pfxpw.encode()))
        def consume():
            for chunk in iter(lambda:process.stdout.read(4096),b''):capture.feed(chunk)
        thread=threading.Thread(target=consume,daemon=True);thread.start()
        print('CONTROLLED_TEST_RUNNING_PRIVATE_INBOX_CONFIRMATION_LEDGER',flush=True)
        rc=process.wait(timeout=2600);thread.join(timeout=3)
        if rc or capture.leaked:
            (ledger/'failure.dpapi').write_bytes(l.l.protect(bytes(capture.data)))
            raise l.l.Stop('CONTROLLED_TEST_FAILED_NO_RETRY')
        l.l.require((ledger/'test.pass').exists(),'TEST_RECEIPT')
        print('APPROVED_TWO_EMAIL_RECOVERY_PASS',flush=True)
    finally:
        for child in (process,frontend):
            if child is not None and child.poll() is None:
                child.terminate()
                try:child.wait(timeout=10)
                except s.TimeoutExpired:child.kill();child.wait(timeout=5)

if __name__=='__main__':
    try:main()
    except Exception as e:
        print('CONTROLLED_EMAIL_STOP_'+(str(e) if isinstance(e,l.l.Stop) else type(e).__name__))
        raise SystemExit(1)
