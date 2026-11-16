import ast
import json
import logging
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from flask import Flask, Response, jsonify, request


class FastIndexCatalogueTests(unittest.TestCase):
    def connect(self):
        connection = sqlite3.connect(self.db)
        self.addCleanup(connection.close)
        return connection

    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = self.work.name
        self.db = os.path.join(self.root, 'index.sqlite')
        self.app = Flask(__name__)
        source = Path(os.environ.get('NAS_BACKEND_SOURCE', str(Path(__file__).resolve().parents[1] / 'backend/nas_api_server.py')))
        nodes = [node for node in ast.parse(source.read_text(encoding='utf-8')).body
                 if isinstance(node, ast.FunctionDef) and node.name in ('generate_fast_index', 'api_fast_index')]
        for node in nodes:
            node.decorator_list = []
        self.ns = dict(sqlite3=sqlite3, os=os, json=json, Response=Response, jsonify=jsonify,
            request=request, INDEX_DB_PATH=self.db, _index_scan_lock=threading.Lock(),
            _get_webdav_public_dir=lambda: self.root, _refresh_file_index_for_search=lambda: None,
            get_webdav_root=lambda: self.root, CACHE_FILE=os.path.join(self.root, 'legacy.json'),
            log=logging.getLogger('catalogue-test'), time=time)
        module = ast.Module(body=nodes, type_ignores=[]) if sys.version_info >= (3, 8) else ast.Module(body=nodes)
        exec(compile(module, str(source), 'exec'), self.ns)
        self.app.add_url_rule('/api/disk/fast_index', view_func=self.ns['api_fast_index'])
        with self.connect() as conn:
            conn.execute('PRAGMA journal_mode=WAL')
            conn.execute('CREATE TABLE file_index(path TEXT PRIMARY KEY,name TEXT,is_dir INTEGER,size INTEGER,mtime INTEGER,mime TEXT)')
            conn.execute('CREATE TABLE index_state(id INTEGER,root TEXT,generation INTEGER,ready INTEGER,scanned_at REAL,catalogue_revision INTEGER)')
            conn.execute('INSERT INTO index_state VALUES(1,?,7,1,?,11)', (self.root,time.time()))
            conn.executemany('INSERT INTO file_index VALUES(?,?,0,3,123,?)',
                [('notes.txt','notes.txt','text/plain'), ('Ảnh + #%.jpg','Ảnh + #%.jpg','image/jpeg'), ('data.bin','data.bin','application/octet-stream')])

    def test_stream_uses_shared_database_without_walk_or_legacy_cache(self):
        Path(self.ns['CACHE_FILE']).write_text('{"files":[],"total":0}')
        with patch.object(os, 'walk', side_effect=AssertionError('Second filesystem walk is forbidden')):
            body = json.loads(''.join(self.ns['generate_fast_index']()))
        self.assertEqual(1, body['catalogue_protocol'])
        self.assertEqual('file_index', body['source'])
        self.assertTrue(body['index_ready'])
        self.assertFalse(body['truncated'])
        self.assertEqual(7, body['index_generation'])
        self.assertEqual(11, body['catalogue_revision'])
        self.assertEqual(3, body['total'])
        self.assertEqual({'/webdav/notes.txt','/webdav/Ảnh + #%.jpg','/webdav/data.bin'}, {row['path'] for row in body['files']})

    def test_more_than_one_hundred_thousand_files_are_not_truncated(self):
        with self.connect() as conn:
            conn.execute('DELETE FROM file_index')
            conn.executemany('INSERT INTO file_index VALUES(?,?,0,1,123,?)',
                ((str(index)+'.bin',str(index)+'.bin','application/octet-stream') for index in range(100001)))
        body = json.loads(''.join(self.ns['generate_fast_index']()))
        self.assertEqual(100001, body['total'])
        self.assertEqual(body['total'], len(body['files']))
        self.assertFalse(body['truncated'])

    def test_count_and_rows_share_one_snapshot(self):
        stream = self.ns['generate_fast_index']()
        prefix = next(stream)
        with self.connect() as conn:
            conn.execute("INSERT INTO file_index VALUES('late.bin','late.bin',0,1,123,'application/octet-stream')")
        body = json.loads(prefix + ''.join(stream))
        self.assertEqual(3, body['total'])
        self.assertEqual(3, len(body['files']))

    def test_incomplete_root_change_or_busy_catalogue_is_not_success(self):
        client = self.app.test_client()
        with self.connect() as conn:
            conn.execute('UPDATE index_state SET ready=0')
        self.assertEqual(503, client.get('/api/disk/fast_index').status_code)
        with self.connect() as conn:
            conn.execute('UPDATE index_state SET ready=1,root=?', ('/other',))
        refreshes = []
        self.ns['_refresh_file_index_for_search'] = lambda: refreshes.append(True)
        self.assertEqual(503, client.get('/api/disk/fast_index').status_code)
        self.assertEqual([True], refreshes)
        with self.connect() as conn:
            conn.execute('UPDATE index_state SET root=?', (self.root,))
        self.ns['_index_scan_lock'].acquire()
        try:
            self.assertEqual(503, client.get('/api/disk/fast_index').status_code)
        finally:
            self.ns['_index_scan_lock'].release()


if __name__ == '__main__':
    unittest.main()
