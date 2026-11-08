import copy
import unittest
import schema_contract as c


class SchemaContractTests(unittest.TestCase):
    def test_identical_metadata_matches(self):
        rows={section:[['synthetic',section]] for section in c.QUERIES}
        hashes={key:c.fingerprint(value) for key,value in rows.items()}
        c.verify_fingerprints(hashes,hashes)

    def test_each_drift_class_fails_closed_without_values(self):
        expected={section:c.fingerprint([['EXPECTED_SCHEMA']]) for section in c.QUERIES}
        for section in c.QUERIES:
            actual=copy.deepcopy(expected)
            actual[section]=c.fingerprint([['SYNTHETIC_PRIVATE_MARKER']])
            with self.assertRaises(c.ContractError) as error:c.verify_fingerprints(actual,expected)
            self.assertEqual('SCHEMA_CONTRACT_'+section.upper()+'_DRIFT',str(error.exception))
            self.assertNotIn('SYNTHETIC_PRIVATE_MARKER',str(error.exception))

    def test_missing_section_is_rejected(self):
        with self.assertRaisesRegex(c.ContractError,'SECTION_SET'):c.verify_fingerprints({}, {'constraints':'synthetic'})


if __name__=='__main__':unittest.main()
