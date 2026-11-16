import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from scripts import push_server_to_nas as deploy


class DeploymentSourceGuardTests(unittest.TestCase):
    def test_current_backend_requires_packaged_coordinator(self):
        expected = Path(deploy.LOCAL_PATH).parent / 'nas_work_coordinator.py'
        self.assertEqual(str(expected), deploy.validate_server_source(deploy.LOCAL_PATH))

    def test_missing_coordinator_never_reaches_upload(self):
        required = ("api_fast_search", "api_storage_summary", "requires_auth", "_index_resolve_directory",
                    "_duplicate_cleanup", "api_duplicate_content", "api_duplicate_find", "api_duplicate_capabilities")
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / 'server.py'
            source.write_text('import nas_work_coordinator\n' + '\n'.join('def ' + name + '(): pass' for name in required), encoding='utf-8')
            with patch.object(deploy, 'LOCAL_PATH', str(source)), patch.object(deploy, 'run') as remote:
                with self.assertRaises(OSError):
                    deploy.main()
                remote.assert_not_called()

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
