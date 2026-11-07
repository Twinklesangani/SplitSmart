import unittest
from run_native_postgres_e2e import isolated_environment,Capture


class IsolationTests(unittest.TestCase):
    def test_inherited_routing_and_java_options_cannot_override_fixture(self):
        environment=isolated_environment({
            'PATH':'synthetic-path','SPRING_DATASOURCE_URL':'https://not-a-real-database.invalid',
            'SPRING_APPLICATION_JSON':'SYNTHETIC_CONFIG','JAVA_TOOL_OPTIONS':'SYNTHETIC_OVERRIDE',
            'APP_RECOVERY_EMAIL_ENABLED':'true','DB_PASSWORD':'SYNTHETIC_UNUSED',
            'NODE_OPTIONS':'SYNTHETIC_HOOK',
        },{'DB_URL':'jdbc:postgresql://127.0.0.1:25432/ss_pg_transfer_synthetic_fixture',
           'RECOVERY_EMAIL_ENABLED':'false'})
        self.assertEqual({'PATH','DB_URL','RECOVERY_EMAIL_ENABLED'},set(environment))
        self.assertEqual('false',environment['RECOVERY_EMAIL_ENABLED'])

    def test_secret_scan_survives_chunk_boundaries_and_buffer_eviction(self):
        capture=Capture((b'SYNTHETIC_SECRET',),limit=8)
        capture.feed(b'prefixSYNTHETIC_')
        capture.feed(b'SECRET')
        capture.feed(b'x'*100)
        self.assertTrue(capture.leaked)
        self.assertLessEqual(len(capture.data),8)


if __name__=='__main__':unittest.main()
