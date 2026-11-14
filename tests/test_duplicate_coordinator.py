"""Coordinator contracts without importing or running the NAS daemon."""
import ast
import hashlib
import json
import logging
import os
import sqlite3
import stat
import tempfile
import time
import types
import unittest
from unittest.mock import patch
from pathlib import Path


class DuplicateCoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = self.work.name
        source = Path(__file__).resolve().parents[1] / '.specify/patches/verified-duplicate-service.py'
        nodes = [n for n in ast.parse(source.read_text(encoding='utf-8')).body if isinstance(n, ast.FunctionDef)]
        for node in nodes:
            node.decorator_list = []
        self.ns = dict(os=os, stat=stat, json=json, time=time, hashlib=hashlib, sqlite3=sqlite3,
                       log=logging.getLogger('test'), INDEX_DB_PATH=os.path.join(self.root, 'index.sqlite'),
                       _get_webdav_public_dir=lambda: self.root,
                       _index_storage_boundary=lambda root: root,
                       _index_resolve_directory=lambda parent, root, boundary: parent if parent == root else None,
                       jsonify=lambda body: body)
        exec(compile(ast.Module(body=nodes, type_ignores=[]), str(source), 'exec'), self.ns)
        Path(self.root, 'a').write_bytes(b'abc')

    def test_hash_is_reused_only_for_same_physical_version(self):
        info = self.ns['_duplicate_file_info']('a')
        digest = hashlib.sha256(b'abc').hexdigest()
        self.ns['_duplicate_save_hash']('a', info['version'], digest)
        self.assertEqual(digest, self.ns['_duplicate_lookup'](info))
        self.assertEqual('', self.ns['_duplicate_lookup'](dict(info, version='changed')))
        self.assertEqual('', self.ns['_duplicate_lookup'](dict(info, file_id='other')))
        with self.assertRaises(ValueError):
            self.ns['_duplicate_save_hash']('a', 'stale', digest)
        with self.assertRaises(ValueError):
            self.ns['_duplicate_save_hash']('a', info['version'], 'partial')

    def test_unsafe_paths_and_missing_root_are_rejected(self):
        for path in ('../a', '.private/a', '/a', 'a//b', 'a\\b'):
            with self.assertRaises(ValueError):
                self.ns['_duplicate_file_info'](path)
        self.ns['_get_webdav_public_dir'] = lambda: None
        with self.assertRaises(ValueError):
            self.ns['_duplicate_file_info']('a')

    def test_json_arrays_are_rejected_before_any_cleanup(self):
        self.ns['request'] = types.SimpleNamespace(get_json=lambda: ['a'])
        self.ns['_duplicate_cleanup'] = lambda *args: self.fail('Cleanup must not run')
        for route in ('api_duplicate_file', 'api_duplicate_hash', 'api_duplicate_cleanup', 'api_duplicate_content'):
            body, status = self.ns[route]()
            self.assertEqual(409, status)
            self.assertEqual('JSON object required', body['error'])

    def cleanup_fixture(self):
        Path(self.root, 'copy').write_bytes(b'abc')
        digest = hashlib.sha256(b'abc').hexdigest()
        keeper = self.ns['_duplicate_file_info']('a')
        victim = self.ns['_duplicate_file_info']('copy')
        for info in (keeper, victim):
            self.ns['_duplicate_save_hash'](info['path'], info['version'], digest)
        directory = os.path.join(self.root, 'private')
        os.mkdir(directory)
        self.ns['_duplicate_work_dir'] = lambda: directory
        self.ns['_duplicate_sync_directory'] = lambda path: None
        return keeper, victim, digest, directory

    def test_busy_writer_failure_never_moves_a_file(self):
        keeper, victim, digest, directory = self.cleanup_fixture()
        self.ns['_duplicate_open_lease'] = lambda info: (_ for _ in ()).throw(OSError('Busy writer'))
        fake = types.SimpleNamespace(flock=lambda *args: None, LOCK_EX=1, LOCK_NB=2)
        with patch.dict('sys.modules', fcntl=fake):
            with self.assertRaises(OSError):
                self.ns['_duplicate_cleanup']('copy', 'a', victim['version'], keeper['version'], digest)
        self.assertEqual(b'abc', Path(self.root, 'copy').read_bytes())
        self.assertEqual(b'abc', Path(self.root, 'a').read_bytes())
        self.assertEqual(['cleanup.lock'], os.listdir(directory))

    def test_crash_after_move_keeps_recovery_mapping_and_both_contents(self):
        keeper, victim, digest, directory = self.cleanup_fixture()
        # Simulate acquired leases; kernel exclusion is checked separately on Linux.
        self.ns['_duplicate_open_lease'] = lambda info: os.open(os.path.join(directory, 'lease-fixture'), os.O_RDONLY | os.O_CREAT)
        fake = types.SimpleNamespace(flock=lambda *args: None, LOCK_EX=1, LOCK_NB=2)
        rename = os.rename
        class Crash(BaseException):
            pass
        def crash(source, destination):
            journals = list(Path(directory).glob('*.json'))
            self.assertEqual(1, len(journals))
            mapping = json.loads(journals[0].read_text())
            self.assertEqual(victim['file_id'], mapping['file_id'])
            rename(source, destination)
            raise Crash()
        with patch.dict('sys.modules', fcntl=fake), patch('os.rename', side_effect=crash):
            with self.assertRaises(Crash):
                self.ns['_duplicate_cleanup']('copy', 'a', victim['version'], keeper['version'], digest)
        mapping = json.loads(next(Path(directory).glob('*.json')).read_text())
        self.assertEqual('copy', mapping['original_path'])
        self.assertEqual(b'abc', Path(mapping['quarantine']).read_bytes())
        self.assertEqual(b'abc', Path(self.root, 'a').read_bytes())

    def test_breaking_lease_rolls_back_instead_of_unlinking(self):
        keeper, victim, digest, directory = self.cleanup_fixture()
        self.ns['_duplicate_open_lease'] = lambda info: os.open(os.path.join(directory, 'lease-fixture'), os.O_RDONLY | os.O_CREAT)
        fake = types.SimpleNamespace(flock=lambda *args: None, LOCK_EX=1, LOCK_NB=2,
                                     F_GETLEASE=3, F_RDLCK=4, fcntl=lambda *args: 5)
        with patch.dict('sys.modules', fcntl=fake):
            with self.assertRaises(ValueError):
                self.ns['_duplicate_cleanup']('copy', 'a', victim['version'], keeper['version'], digest)
        self.assertEqual(b'abc', Path(self.root, 'copy').read_bytes())
        self.assertEqual(b'abc', Path(self.root, 'a').read_bytes())
        self.assertFalse(list(Path(directory).glob('*.json')))

    def test_version_bound_stream_closes_unstarted_empty_response(self):
        Path(self.root, 'a').write_bytes(b'')
        info = self.ns['_duplicate_file_info']('a')
        self.ns['request'] = types.SimpleNamespace(get_json=lambda: {'path':'a','version':info['version']})
        fd = os.open(info['physical'], os.O_RDONLY)
        self.ns['_duplicate_open_lease'] = lambda value: fd
        class Response:
            def __init__(self, body, **kwargs):
                self.body = body
                self.headers = {}
                self.closers = []
            def call_on_close(self, callback):
                self.closers.append(callback)
        self.ns['Response'] = Response
        response = self.ns['api_duplicate_content']()
        self.assertEqual(0, response.content_length)
        self.assertEqual(info['version'], response.headers['X-NAS-Version'])
        self.assertEqual(info['file_id'], response.headers['X-NAS-File-ID'])
        for close in response.closers:
            close()
            close()
        with self.assertRaises(OSError):
            os.fstat(fd)
