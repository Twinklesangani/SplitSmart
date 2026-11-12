import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import m8_stage1_checkpoint as m

class Stage1Guards(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
    def run_blocked(self,action,state,code):
        with patch.object(m,'ROOT',self.root),patch.object(m,'load',return_value=state),patch.object(m,'source') as source,patch.object(m,'render') as render:
            with self.assertRaisesRegex(m.l.Stop,code):action()
            source.assert_not_called();render.assert_not_called()
    def test_freeze_requires_encrypted_storage(self):
        self.run_blocked(m.freeze,dict(status='PREFLIGHT_PASS',suspendAttempts=0,revokeAttempts=0),'ENCRYPTED_STORAGE_NOT_VERIFIED')
    def test_freeze_attempt_cannot_replay(self):
        self.run_blocked(m.freeze,dict(status='FROZEN',suspendAttempts=1,revokeAttempts=1),'FREEZE_ATTEMPT_FENCE')
    def test_export_attempt_cannot_replay(self):
        self.run_blocked(m.checkpoint,dict(status='EXPORTING',exportAttempts=1,restoreAttempts=0),'CHECKPOINT_ATTEMPT_FENCE')
    def test_checkpoint_requires_protected_storage(self):
        (self.root/'storage-protection.json').write_text(json.dumps({'StorageProtectionGatePassed':False}))
        self.run_blocked(m.checkpoint,dict(status='FROZEN',exportAttempts=0,restoreAttempts=0),'ENCRYPTED_STORAGE_NOT_VERIFIED')

if __name__=='__main__':unittest.main()
