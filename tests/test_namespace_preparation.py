import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.prepare_namespace_guard import prepare


class NamespacePreparationTests(unittest.TestCase):
    def test_prepares_hashed_controller_guard_and_boot_manifest(self):
        project = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            source = root / 'index.php'
            source.write_text("<?php\n$server = require_once __DIR__ . '/../bootstrap/server.php';\n$server->exec();\n", encoding='utf-8')
            guard = project / 'backend/nas_namespace_guard.php'
            output = root / 'prepared'
            prepare(str(source), str(guard), str(output))
            candidate = (output / 'index.php.candidate').read_bytes()
            self.assertEqual(1, candidate.count(b'nas_namespace_guard();'))
            manifest = json.loads((output / 'namespace-manifest.json').read_text())
            self.assertEqual('/run/naswebdav/namespace.lock', manifest['lock_path'])
            self.assertEqual(hashlib.sha256(candidate).hexdigest(), manifest['files']['/var/www/webdav/public/index.php'])
            self.assertEqual(hashlib.sha256(guard.read_bytes()).hexdigest(), manifest['files']['/var/www/webdav/bootstrap/nas_namespace_guard.php'])
            self.assertIn('/etc/tmpfiles.d/naswebdav-namespace.conf', manifest['files'])

    def test_unknown_entrypoint_is_rejected_without_preparing_output(self):
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / 'index.php'
            source.write_text('<?php echo "unexpected";', encoding='utf-8')
            output = Path(work) / 'prepared'
            with self.assertRaises(ValueError):
                prepare(str(source), 'unused.php', str(output))
            self.assertFalse(output.exists())
