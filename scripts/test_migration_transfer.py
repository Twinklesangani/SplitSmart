"""Synthetic-only transfer ordering/failure tests; never opens a real connection."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import migration_transfer as m

SCHEMA=Path(__file__).resolve().parents[1]/'backend/src/main/resources/db/postgresql'


def synthetic_originals():
    from local_database_support import CHECKSUMS
    # Row-free business tables isolate controller behavior from field validation,
    # which has its own 23 tests and a real synthetic database test.
    tables=['users','expense_groups','group_members','expenses','expense_splits',
            'settlements','expense_creation_operations','group_invitations']
    data={t:(['id'],['bigint'],[]) for t in tables}
    data['legacy_mysql_flyway_history']=(['version','checksum','success'],['text']*3,
        [(str(i+1),str(c),'1') for i,c in enumerate(CHECKSUMS)])
    return data


class Fake:
    def __init__(self,failure=None):self.calls=[];self.failure=failure
    def query(self,sql,params=None):
        self.calls.append(sql)
        failures=[self.failure] if isinstance(self.failure,str) else (self.failure or [])
        if any(f in sql for f in failures):raise RuntimeError('SYNTHETIC_PRIVATE_RECORD')
        if sql.startswith('SELECT current_database()'):return [('ss_pg_transfer_synthetic_fixture','fixture','25432')]
        if sql=='SELECT host(inet_server_addr())':return [('127.0.0.1',)]
        if sql.startswith('SELECT pg_try'):return [('t',)]
        if sql.startswith('SELECT version,checksum,success'):return m.checksums(SCHEMA)
        if sql.startswith('SELECT tablename'):return [(t,) for t in ['users','expense_groups','group_members','expenses','expense_splits','settlements','expense_creation_operations','group_invitations','flyway_schema_history','password_reset_tokens','group_invite_links','email_verification_tokens','notifications']]
        if 'COUNT(*)' in sql:return [('0',)]
        if 'legacy_mysql_flyway_history' in sql and sql.startswith('SELECT'):return synthetic_originals()['legacy_mysql_flyway_history'][2]
        return []
    def close(self):pass


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.target=m.Target('ss_pg_transfer_synthetic_fixture','fixture',25432)

    def test_real_data_gate_precedes_connection_or_attempt(self):
        with self.assertRaisesRegex(m.TransferError,'REAL_DATA_EXECUTION_NOT_AUTHORIZED'):
            m.transfer(lambda:self.fail('Connected'),self.target,SCHEMA,{}, {},'unused','REAL')

    def test_cloud_write_gate_cannot_be_overridden_by_synthetic_label(self):
        with self.assertRaisesRegex(m.TransferError,'REAL_DATA_EXECUTION_NOT_AUTHORIZED'):
            m.transfer(lambda:self.fail('Connected'),m.Target('splitsmart','ss_migrator',123,True),SCHEMA,{}, {},'unused','SYNTHETIC')

    def test_metadata_preflight_wrong_identity_stops(self):
        with self.assertRaisesRegex(m.TransferError,'TARGET_IDENTITY'):
            m.metadata_preflight(Fake(),m.Target('wrong','fixture',25432),SCHEMA)

    def test_checksums_detect_schema_file_drift(self):
        class Drift(Fake):
            def query(self,sql,params=None):
                if sql.startswith('SELECT version,checksum,success'):return []
                return super().query(sql,params)
        with self.assertRaisesRegex(m.TransferError,'TARGET_FLYWAY_DRIFT'):
            m.metadata_preflight(Drift(),self.target,SCHEMA)

    def run_transfer(self,failure=None,reconnect_failure=False):
        db=Fake(failure)
        connections=0
        def factory():
            nonlocal connections
            connections+=1
            if reconnect_failure and connections>1:raise RuntimeError('SYNTHETIC_PRIVATE_RECORD')
            return db
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'attempt.json'
            with patch('local_pg_rehearsal.metadata',return_value=({}, {}, [], [])), \
                 patch('migration_validation.validate',side_effect=lambda p,*a:p), \
                 patch('local_pg_rehearsal.icu_check'), \
                 patch('local_pg_rehearsal.balances',return_value={}):
                try: result=m.transfer(factory,self.target,SCHEMA,synthetic_originals(),{},path,'SYNTHETIC')
                except m.TransferError as error: result=str(error)
            status=json.loads(path.read_text())['status']
            with self.assertRaises(FileExistsError):m.Ledger(path)
            return db,result,status

    def test_success_is_verified_and_cannot_replay(self):
        db,result,status=self.run_transfer()
        self.assertEqual('VERIFIED',status)
        self.assertEqual('PASS',result['status'])
        self.assertIn('COMMIT',db.calls)

    def test_insert_error_rolls_back_and_redacts_values(self):
        db,result,status=self.run_transfer('INSERT INTO')
        self.assertEqual('ROLLED_BACK',status)
        self.assertIn('ROLLBACK',db.calls)
        self.assertNotIn('SYNTHETIC_PRIVATE_RECORD',result)

    def test_commit_network_failure_is_not_claimed_rolled_back(self):
        db,result,status=self.run_transfer('COMMIT')
        self.assertEqual('COMMIT_UNKNOWN',status)
        self.assertNotIn('ROLLBACK',db.calls)

    def test_identifier_injection_is_rejected(self):
        with self.assertRaisesRegex(m.TransferError,'IDENTIFIER'):m.identifier('users; DROP TABLE users')

    def test_reconnect_failure_preserves_committed_uncertainty(self):
        db,result,status=self.run_transfer(reconnect_failure=True)
        self.assertEqual('COMMITTED_UNVERIFIED',status)
        self.assertNotIn('ROLLBACK',db.calls)
        self.assertNotIn('SYNTHETIC_PRIVATE_RECORD',result)

    def test_rollback_failure_is_not_claimed_success(self):
        db,result,status=self.run_transfer(('INSERT INTO','ROLLBACK'))
        self.assertEqual('ROLLBACK_UNCONFIRMED',status)

    def test_v7_mapping_cannot_silently_drop_new_token_version_fields(self):
        import local_pg_rehearsal as local
        class Catalog:
            def query(self,*args):
                return [(name,'bigint','YES',None,'64','0',None) for name in
                    ['id','auth_token_version','password_reset_requested_at','email_verified_at']]
        with self.assertRaisesRegex(local.l.Stop,'TARGET_FIELD_MAPPING'):
            local.metadata(Catalog(),{'users':[]},{'users':['id','auth_token_version']})


if __name__=='__main__':unittest.main()
