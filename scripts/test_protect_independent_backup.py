import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import protect_independent_backup as b

class CiphertextCopyTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.source=self.root/'synthetic.sql.gpg'
        self.source.write_bytes(b'SYNTHETIC_CIPHERTEXT_TEST_ONLY')
        self.expected=hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.destination=self.root/'copy.sql.gpg'
    def test_exact_hash_and_source_preserved(self):
        original=self.source.read_bytes()
        b.copy_once(self.source,self.destination,self.expected)
        self.assertEqual(original,self.source.read_bytes())
        self.assertEqual(original,self.destination.read_bytes())
    def test_wrong_hash_creates_nothing(self):
        with self.assertRaisesRegex(b.SafetyStop,'SOURCE_HASH'):
            b.copy_once(self.source,self.destination,'0'*64)
        self.assertFalse(self.destination.exists())
    def test_existing_destination_unchanged(self):
        self.destination.write_bytes(b'KEEP')
        with self.assertRaisesRegex(b.SafetyStop,'DESTINATION_EXISTS'):
            b.copy_once(self.source,self.destination,self.expected)
        self.assertEqual(self.destination.read_bytes(),b'KEEP')
    def test_same_file_rejected(self):
        with self.assertRaisesRegex(b.SafetyStop,'SAME_FILE'):
            b.copy_once(self.source,self.source,self.expected)
    def test_partial_failure_never_retries_or_deletes(self):
        with patch.object(b.shutil,'copyfileobj',side_effect=OSError('SYNTHETIC_FAILURE')) as copy:
            with self.assertRaises(OSError):b.copy_once(self.source,self.destination,self.expected)
        copy.assert_called_once()
        self.assertTrue(self.destination.exists())
        self.assertTrue(self.source.exists())
    def test_plaintext_suffix_rejected(self):
        other=self.root/'unsafe.sql';other.write_bytes(b'SYNTHETIC_ONLY')
        with self.assertRaisesRegex(b.SafetyStop,'CIPHERTEXT_SOURCE'):
            b.copy_once(other,self.destination,self.expected)

    def test_plan_only_never_copies(self):
        import contextlib,io,sys
        folder=self.root/'proposed'
        with patch.object(b,'SOURCE',self.source),patch.object(b,'EXPECTED',self.expected),patch.object(b,'usb_identity',return_value={'id':'USB_TEST','free':10000000,'bus':'USB'}),patch.object(sys,'argv',['tool','--destination',str(folder)]),patch.object(b,'copy_once') as copy,contextlib.redirect_stdout(io.StringIO()):
            b.main()
        copy.assert_not_called()
        self.assertFalse(folder.exists())
    def test_execute_requires_matching_approved_disk(self):
        import sys
        folder=self.root/'proposed'
        with patch.object(b,'SOURCE',self.source),patch.object(b,'EXPECTED',self.expected),patch.object(b,'usb_identity',return_value={'id':'USB_TEST','free':10000000,'bus':'USB'}),patch.object(sys,'argv',['tool','--destination',str(folder),'--execute-approved-copy','--expected-disk-id','DIFFERENT']),patch.object(b,'copy_once') as copy:
            with self.assertRaisesRegex(b.SafetyStop,'APPROVED_USB_ID_REQUIRED'):b.main()
        copy.assert_not_called()
        self.assertFalse(folder.exists())

if __name__=='__main__':unittest.main()
