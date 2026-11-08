import unittest
from verify_linux_native import synthetic_url

class DestinationGuardTests(unittest.TestCase):
    def test_constructs_destination_from_pinned_endpoint_not_saved_url(self):
        url=synthetic_url({'host':'synthetic.example.invalid','port':1234},
            {'test_database':'splitsmart_synthetic_fake','test_url':'jdbc:postgresql://production/splitsmart'})
        self.assertEqual('jdbc:postgresql://synthetic.example.invalid:1234/splitsmart_synthetic_fake?ApplicationName=splitsmart_linux_synthetic',url)
    def test_rejects_application_and_recovery_databases(self):
        for database in ('splitsmart','ss_pg_local_real_test','postgres','splitsmart_synthetic_fake?sslmode=disable'):
            with self.subTest(database=database),self.assertRaises(ValueError):
                synthetic_url({'host':'synthetic.invalid','port':1234},{'test_database':database})
    def test_rejects_endpoint_parameter_injection(self):
        with self.assertRaises(ValueError):
            synthetic_url({'host':'synthetic.invalid/?sslmode=disable','port':1234},{'test_database':'splitsmart_synthetic_fake'})
    def test_rejects_invalid_port(self):
        with self.assertRaises(ValueError):
            synthetic_url({'host':'synthetic.invalid','port':0},{'test_database':'splitsmart_synthetic_fake'})

if __name__=='__main__':unittest.main()
