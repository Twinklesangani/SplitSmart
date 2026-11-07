import unittest
from cloud_collation_readiness import compare, CheckFailed


class ReadinessTests(unittest.TestCase):
    def test_changed_case_semantics_fail_closed(self):
        class Fake:
            def query(self,*args):return [('false',)]
        with self.assertRaisesRegex(CheckFailed,'^SYNTHETIC_EQUALITY_MISMATCH$'):
            compare(Fake(),Fake())

    def test_uniqueness_disagreement_fails_closed(self):
        class Fake:
            def query(self,sql,*args):return [('0',)] if sql.startswith('WITH') else [('true',)]
        with self.assertRaisesRegex(CheckFailed,'^SYNTHETIC_UNIQUENESS_MISMATCH$'):
            compare(Fake(),Fake())


if __name__=='__main__':unittest.main()
