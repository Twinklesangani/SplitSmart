"""Execute the production POSIX entrypoint with dummy CA/P12 and fake Java only."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[1]/'backend/docker-entrypoint.sh'
SH=Path(r'C:\Program Files\Git\usr\bin\sh.exe')


def posix(path):
    path=Path(path).resolve()
    return '/'+path.drive[0].lower()+path.as_posix()[2:]


class StartupTests(unittest.TestCase):
    def execute(self,profiles=None,encoded=None,postgres=True,legacy_env=False):
        with tempfile.TemporaryDirectory(prefix='ss-synthetic-tls-') as name:
            root=Path(name)
            (root/'java').write_text('#!/bin/sh\n'
                'test "${DB_TLS_CA_BASE64+present}" != present || exit 31\n'
                'test "${DB_TLS_TRUST_STORE_BASE64+present}" != present || exit 32\n'
                'case ",${SPRING_PROFILES_ACTIVE:-prod}," in *,postgresql,*) test -s "${DB_TLS_CA_FILE:-}" || exit 34;; esac\n'
                'printf "SYNTHETIC_JAVA %s\\n" "$*"\n',encoding='utf-8',newline='\n')
            env={'PATH':posix(root)+':/usr/bin:/bin','SYSTEMROOT':os.environ['SYSTEMROOT'],
                 'SPLITSMART_TLS_DIRECTORY':posix(root)}
            if profiles is not None:env['SPRING_PROFILES_ACTIVE']=profiles
            key='DB_TLS_CA_BASE64' if postgres else 'DB_TLS_TRUST_STORE_BASE64'
            env[key]=encoded if encoded is not None else base64.b64encode(b'SYNTHETIC_PUBLIC_FIXTURE').decode()
            if legacy_env:env['DB_TLS_TRUST_STORE_BASE64']=base64.b64encode(b'SYNTHETIC_LEGACY_FIXTURE').decode()
            p=subprocess.run([str(SH),posix(SCRIPT)],env=env,capture_output=True,timeout=15)
            files={p.name:p.read_bytes() for p in root.iterdir() if p.name!='java'}
            return p.returncode,p.stdout,files

    def test_postgresql_materializes_public_pem_without_mysql_truststore(self):
        code,out,files=self.execute('postgresql,postgres-prod')
        self.assertEqual(0,code)
        self.assertEqual({'aiven-postgres-ca.pem':b'SYNTHETIC_PUBLIC_FIXTURE'},files)
        self.assertIn(b'-jar /app/app.jar --server.port=8080',out)

    def test_default_mysql_behavior_preserved(self):
        code,out,files=self.execute(postgres=False)
        self.assertEqual(0,code)
        self.assertEqual({'aiven-trust.p12':b'SYNTHETIC_PUBLIC_FIXTURE'},files)

    def test_postgresql_does_not_use_or_retain_legacy_truststore_input(self):
        code,out,files=self.execute('postgresql,postgres-prod',legacy_env=True)
        self.assertEqual(0,code)
        self.assertEqual({'aiven-postgres-ca.pem':b'SYNTHETIC_PUBLIC_FIXTURE'},files)

    def test_conflicting_or_incomplete_profiles_fail_before_java(self):
        for profiles in ('prod,postgresql,postgres-prod','postgresql','postgres-prod'):
            code,out,files=self.execute(profiles)
            self.assertNotEqual(0,code)
            self.assertNotIn(b'SYNTHETIC_JAVA',out)

    def test_invalid_base64_stops_before_java(self):
        code,out,files=self.execute('postgresql,postgres-prod',encoded='!INVALID!')
        self.assertNotEqual(0,code)
        self.assertNotIn(b'SYNTHETIC_JAVA',out)

    def test_empty_ca_stops_before_java(self):
        code,out,files=self.execute('postgresql,postgres-prod',encoded='')
        self.assertNotEqual(0,code)
        self.assertNotIn(b'SYNTHETIC_JAVA',out)

    def test_zero_decoded_bytes_stop_before_java(self):
        code,out,files=self.execute('postgresql,postgres-prod',encoded=' \n')
        self.assertNotEqual(0,code)
        self.assertNotIn(b'SYNTHETIC_JAVA',out)


if __name__=='__main__':unittest.main()
