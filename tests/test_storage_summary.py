import os
import runpy
import sqlite3
import tempfile
import threading
import unittest
import types
from pathlib import Path


class SharedStorageSummaryTests(unittest.TestCase):
    def test_persistent_snapshot_counts_refresh_and_partial_state(self):
        ns = runpy.run_path(str(Path(__file__).resolve().parents[1] / '.specify/patches/shared-storage-summary.py'))
        function = ns['_build_storage_summary']
        scope = function.__globals__
        with tempfile.TemporaryDirectory() as root:
            database = os.path.join(root, 'index.sqlite')
            scope.update(os=os, INDEX_DB_PATH=database,
                         time=types.SimpleNamespace(time=lambda: 100),
                         _refresh_file_index_for_search=lambda: None,
                         _get_webdav_public_dir=lambda: root,
                         _index_scan_lock=threading.Lock())
            with sqlite3.connect(database) as conn:
                conn.execute('CREATE TABLE index_state(id INTEGER,root TEXT,scanned_at REAL,ready INTEGER,catalogue_revision INTEGER)')
                conn.execute('INSERT INTO index_state VALUES(1,?,100,1,0)', (os.path.realpath(root),))
                conn.execute('CREATE TABLE file_index(path TEXT PRIMARY KEY,name TEXT,is_dir INTEGER,size INTEGER)')
                conn.executemany('INSERT INTO file_index VALUES(?,?,?,?)',
                                 [('a.JPG','a.JPG',0,10), ('sub/b.mp4','b.mp4',0,100), ('sub/','sub',1,0)])
            conn.close()
            first = function()
            self.assertEqual((first['total_files'], first['total_dirs'], first['total_bytes']), (2,1,110))
            self.assertEqual(first['by_type']['image']['files'], 1)
            self.assertEqual(first['largest'][0]['path'], 'sub/b.mp4')
            self.assertFalse(first['partial'])
            self.assertEqual(function(), first)  # reopened connection reuses durable snapshot
            with sqlite3.connect(database) as conn:
                conn.execute('ALTER TABLE file_index ADD COLUMN mtime INTEGER DEFAULT 0')
                conn.execute("INSERT OR REPLACE INTO file_index(path,name,is_dir,size,mtime) VALUES('a.JPG','a.JPG',0,10,99)")
                conn.execute('UPDATE index_state SET catalogue_revision=1')
                self.assertIsNone(conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='storage_summary_revision'").fetchone())
            conn.close()
            with sqlite3.connect(database) as conn:
                conn.execute("DELETE FROM file_index WHERE path='a.JPG'")
                conn.execute('UPDATE index_state SET scanned_at=101,ready=0')
            conn.close()
            partial = function(refresh=True)
            self.assertTrue(partial['partial'])
            self.assertEqual(partial['total_files'], 1)
            with sqlite3.connect(database) as conn:
                conn.execute('UPDATE index_state SET ready=1')
            conn.close()
            self.assertFalse(function(refresh=True)['partial'])
            jobs = []
            class DeferredThread:
                def __init__(self, target, daemon):
                    self.target = target
                def start(self):
                    jobs.append(self.target)
            scope['time'] = types.SimpleNamespace(time=lambda: 1000)
            scope['threading'] = types.SimpleNamespace(Thread=DeferredThread)
            self.assertEqual(function()['total_files'], 1)
            self.assertEqual(len(jobs), 0)  # unchanged index never repeats aggregates merely due to age
            with sqlite3.connect(database) as conn:
                conn.execute("INSERT INTO file_index(path,name,is_dir,size) VALUES('new.txt','new.txt',0,7)")
                conn.execute('UPDATE index_state SET catalogue_revision=2')
            conn.close()
            self.assertEqual(function()['total_files'], 1)
            self.assertEqual(function()['total_files'], 1)
            self.assertEqual(len(jobs), 1)  # stale reads coalesce one background rebuild
            jobs[0]()
            self.assertEqual(function()['summary_updated_at'], 1000)
            self.assertEqual(function()['total_files'], 2)
            self.assertEqual(len(jobs), 1)


if __name__ == '__main__':
    unittest.main()
