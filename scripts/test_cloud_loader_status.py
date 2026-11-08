import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from cloud_loader_preflight import failure_code,record_result


class StatusTests(unittest.TestCase):
    def test_auth_error_never_prints_provider_message(self):
        error=RuntimeError('SYNTHETIC_PRIVATE_SERVER_MESSAGE');error.status=401
        self.assertEqual('CLOUD_PREFLIGHT_MANAGEMENT_AUTH_REQUIRED_401_NO_UPLOAD',failure_code(error))

    def test_failed_preflight_replaces_stale_pass_and_preserves_history(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);root=base/'CloudReadiness';root.mkdir()
            (root/'loader-metadata-preflight.json').write_text('{"status":"PASS"}')
            with patch('local_database_support.BASE',base),patch('local_database_support.private_directory'):
                record_result({'status':'BLOCKED','error_code':'SYNTHETIC_AUTH_ERROR'})
            self.assertEqual('BLOCKED',json.loads((root/'loader-metadata-preflight.json').read_text())['status'])
            self.assertEqual('PASS',json.loads((root/'loader-metadata-preflight-prior-success.json').read_text())['status'])


if __name__=='__main__':unittest.main()
