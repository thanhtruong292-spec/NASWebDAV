import ast
import logging
import os
import stat
import tarfile
import tempfile
import unittest


@unittest.skipUnless(os.name == 'posix', 'NAS ownership and permission tests require POSIX')
class PrivateConfigBackupTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = os.path.join(self.work.name, 'backups')
        source = os.environ.get('NAS_BACKEND_SOURCE', os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'backend', 'nas_api_server.py')))
        with open(source, encoding='utf-8') as handle:
            tree = ast.parse(handle.read())
        nodes = [node for node in tree.body if isinstance(node, ast.FunctionDef) and
                 node.name in ('_ensure_backup_dir', '_open_private_backup_tar')]
        self.ns = dict(os=os, stat=stat, tarfile=tarfile, _BACKUP_DIR=self.root,
                       log=logging.getLogger('private-backup-test'))
        exec(compile(ast.Module(body=nodes, type_ignores=[]), source, 'exec'), self.ns)

    def test_existing_directory_and_archives_become_private(self):
        os.mkdir(self.root, 0o755)
        path = os.path.join(self.root, 'Backup_NAS old.tar.gz')
        with open(path, 'wb') as handle:
            handle.write(b'owned fixture')
        os.chmod(path, 0o644)
        self.assertTrue(self.ns['_ensure_backup_dir']())
        self.assertEqual(0o700, stat.S_IMODE(os.stat(self.root).st_mode))
        self.assertEqual(0o600, stat.S_IMODE(os.stat(path).st_mode))

    def test_archive_is_private_before_any_bytes_are_written_and_never_overwrites(self):
        path = os.path.join(self.root, 'Backup_NAS fixture.tar.gz')
        with self.ns['_open_private_backup_tar'](path) as archive:
            self.assertEqual(0o600, stat.S_IMODE(os.stat(path).st_mode))
            archive.addfile(tarfile.TarInfo('fixture'))
        with self.assertRaises(FileExistsError):
            with self.ns['_open_private_backup_tar'](path):
                self.fail('Existing archive must not be truncated')
        with tarfile.open(path) as archive:
            self.assertEqual(['fixture'], archive.getnames())

    def test_symlink_directory_and_archive_are_rejected(self):
        os.symlink(self.work.name, self.root)
        self.assertFalse(self.ns['_ensure_backup_dir']())
        os.unlink(self.root)
        os.mkdir(self.root)
        target = os.path.join(self.work.name, 'unrelated')
        with open(target, 'wb') as handle:
            handle.write(b'unchanged')
        os.chmod(target, 0o644)
        os.symlink(target, os.path.join(self.root, 'Backup_NAS link.tar.gz'))
        self.assertFalse(self.ns['_ensure_backup_dir']())
        self.assertEqual(0o644, stat.S_IMODE(os.stat(target).st_mode))


if __name__ == '__main__':
    unittest.main()
