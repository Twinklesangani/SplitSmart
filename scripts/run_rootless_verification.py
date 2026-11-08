"""Windows controller: guarded existing cloud SYNTHETIC DB, no real-data source."""
import base64
import json
from pathlib import Path
import secrets
import socket
import subprocess
import prepare_postgres_target as t
from migration_transfer import checksums
from verify_linux_native import synthetic_url


def rootless_launch_command():
    return ['wsl','-d','Ubuntu','--','runuser','-u','ss_oci_verify','--','env','-i',
             'HOME=/home/ss_oci_verify','USER=ss_oci_verify','LOGNAME=ss_oci_verify',
             'XDG_RUNTIME_DIR=/run/user/2001','PATH=/usr/bin:/bin','python3','-I',
             '/home/ss_oci_verify/tools/rootless_container_checks.py']


def main():
    params=t.params();state=t.state()
    c=t.connection(params,'test_migration',state['test_database'])
    try:
        t.guard(c,state,True)
        expected=checksums(t.WORK/'backend/src/main/resources/db/postgresql')
        if c.query('SELECT version,checksum,success FROM public.flyway_schema_history ORDER BY installed_rank')!=expected:
            raise RuntimeError('SYNTHETIC_FLYWAY_IDENTITY')
    finally:c.close()
    credentials=t.credentials();runtime=t.ROLES['test_runtime'];migration=t.ROLES['test_migration']
    url=synthetic_url(params,state)
    payload={'runid':'ssverify-20261108-oci4','image':'localhost/splitsmart-verify:qualified-20261108',
        'secrets':{'DB_PASSWORD':credentials[runtime],'FLYWAY_DB_PASSWORD':credentials[migration],
                   'JWT_SECRET':base64.b64encode(secrets.token_bytes(32)).decode()},
        'pinned_ip':socket.getaddrinfo(params['host'],params['port'],socket.AF_INET,socket.SOCK_STREAM)[0][4][0],
        'bad_host_url':f"jdbc:postgresql://ss-tls-invalid.example.invalid:{int(params['port'])}/{state['test_database']}?ApplicationName=splitsmart_linux_synthetic",
        'environment':{'DB_URL':url,'FLYWAY_DB_URL':url,'DB_USERNAME':runtime,'FLYWAY_DB_USERNAME':migration,
            'SPRING_PROFILES_ACTIVE':'postgresql,postgres-prod','PORT':'8080',
            'APP_CORS_ALLOWED_ORIGINS':'http://127.0.0.1:15173','RECOVERY_EMAIL_ENABLED':'false',
            'RECOVERY_EMAIL_DELIVERY_VERIFIED':'false','JAVA_TOOL_OPTIONS':'-Xmx256m',
            'DB_TLS_CA_BASE64':base64.b64encode(t.CA.read_bytes()).decode()}}
    code=(Path(__file__).with_name('rootless_container_checks.py')).read_text(encoding='utf-8')
    # Never pass executable Python containing shell syntax through WSL -c.
    # Install PUBLIC source separately; only JSON with private values uses stdin.
    copy="import pathlib,sys; p=pathlib.Path('/home/ss_oci_verify/tools'); p.mkdir(mode=0o700,exist_ok=True); f=p/'rootless_container_checks.py'; assert not f.is_symlink(); f.write_bytes(sys.stdin.buffer.read())"
    copied=subprocess.run(['wsl','-d','Ubuntu','--','runuser','-u','ss_oci_verify','--','python3','-I','-c',copy],
                          input=code.encode(),capture_output=True,timeout=15)
    if copied.returncode:raise RuntimeError('PUBLIC_VERIFIER_COPY_FAILED')
    command=rootless_launch_command()
    result=subprocess.run(command,input=json.dumps(payload).encode(),capture_output=True,timeout=500)
    output=result.stdout.decode(errors='replace')
    if result.returncode:
        # Preserve diagnostics only as DPAPI ciphertext; never echo raw stderr.
        (t.ROOT/'rootless-check-failure.dpapi').write_bytes(t.local.protect(result.stdout+result.stderr))
        allowed=[line for line in output.splitlines() if line.startswith('ROOTLESS_CHECKS_BLOCKED_')]
        print(allowed[-1] if allowed else 'ROOTLESS_CHECKS_BLOCKED_PRIVATE_DIAGNOSTIC')
        raise SystemExit(1)
    report=json.loads(output)
    if report.get('status')!='PASS' or report.get('synthetic_only') is not True:raise RuntimeError('INVALID_RESULT')
    c=t.connection(params,'test_migration',state['test_database'])
    try:
        t.guard(c,state,True)
        if c.query('SELECT version,checksum,success FROM public.flyway_schema_history ORDER BY installed_rank')!=expected:
            raise RuntimeError('POST_CONTAINER_FLYWAY_DRIFT')
    finally:c.close()
    (t.ROOT/'rootless-check-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report))

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('ROOTLESS_CONTROLLER_STOP_'+type(error).__name__)
        raise SystemExit(1)
