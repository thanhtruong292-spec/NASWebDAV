import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from scripts import push_server_to_nas as deploy


class DeploymentSourceGuardTests(unittest.TestCase):
    def test_placeholder_never_reaches_scp_or_restart(self):
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / 'server.py'
            source.write_text('print("placeholder")', encoding='utf-8')
            with patch.object(deploy, 'LOCAL_PATH', str(source)), patch.object(deploy, 'run') as remote:
                with self.assertRaises(ValueError):
                    deploy.main()
                remote.assert_not_called()

    def test_legacy_backend_cannot_overwrite_current_protocol(self):
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / 'server.py'
            source.write_text('def api_fast_search(): pass\ndef api_storage_summary(): pass\ndef requires_auth(): pass\n', encoding='utf-8')
            with patch.object(deploy, 'LOCAL_PATH', str(source)), patch.object(deploy, 'run') as remote:
                with self.assertRaises(ValueError):
                    deploy.main()
                remote.assert_not_called()
