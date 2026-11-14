import ast
import json
import logging
import mimetypes
import os
import resource
import sqlite3
import subprocess
import sys
import threading
import time
import unicodedata
import urllib.parse
from flask import Flask, jsonify, request

source = next((arg.split('=', 1)[1] for arg in sys.argv[1:]
               if arg.startswith('--source=')),
              '/opt/nas_api_server.py.pending-20261114-index')
path = '/var/lib/nas_fast_index.validation-20261114.db'
if '--reuse' in sys.argv:
    assert os.path.exists(path), 'Validation DB must exist for reuse'
else:
    assert not os.path.exists(path), 'Validation DB already exists; inspect before reuse'
    subprocess.check_call(['sqlite3', '/var/lib/nas_fast_index.db', '.backup ' + path])
names = {'_remove_diacritics_py', '_init_index_db', '_scan_file_index',
         '_scan_file_index_pass', '_refresh_file_index_for_search',
         '_background_file_index_refresh', '_index_storage_boundary',
         '_index_resolve_directory', '_index_directory_stamp',
         '_index_readiness', 'api_fast_search'}
nodes = [n for n in ast.parse(open(source).read()).body
         if isinstance(n, ast.FunctionDef) and n.name in names]
app = Flask('validation')
root = '/srv/dev-disk-by-label-data/New folder'
ns = dict(sqlite3=sqlite3, os=os, time=time, mimetypes=mimetypes,
          threading=threading, unicodedata=unicodedata, urllib=urllib,
          _index_lock=threading.Lock(), _index_scan_lock=threading.Lock(),
          INDEX_DB_PATH=path, _cfg_sqlite_timeout=lambda n, d: d,
          log=logging.getLogger('index-validation'), app=app, request=request,
          jsonify=jsonify, _get_webdav_port=lambda: 8822,
          _get_webdav_host_default=lambda: '192.168.100.254',
          _get_webdav_public_dir=lambda: root)
exec(compile(ast.Module(body=nodes), source, 'exec'), ns)
ns['_init_index_db']()
for n in range(2):
    start = time.time()
    result = ns['_scan_file_index'](root)
    result.update(run=n + 1, seconds=time.time() - start,
                  max_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    print(json.dumps(result))
    sys.stdout.flush()
client = app.test_client()
for scope, query in [('Livestream', 'mp4'), ('Downloads', 'mp4'),
                     ('Samsung Notes', 'unique-no-match-261114')]:
    for n in range(3):
        start = time.time()
        response = client.get('/api/search', query_string={'q': query, 'root': scope})
        body = json.loads(response.data.decode('utf-8'))
        print(json.dumps({'scope': scope, 'query': query, 'run': n + 1,
                          'ms': (time.time() - start) * 1000,
                          'status': response.status_code,
                          'count': len(body.get('results', [])),
                          'ready': body.get('index_ready')}))
        sys.stdout.flush()
time.sleep(6)
start = time.time()
response = client.get('/api/search', query_string={'q': 'unique-no-match-261114', 'root': 'Samsung Notes'})
body = json.loads(response.data.decode('utf-8'))
print(json.dumps({'post_freshness_window_ms': (time.time() - start) * 1000,
                  'status': response.status_code, 'ready': body.get('index_ready')}))
with sqlite3.connect(path) as conn:
    print(json.dumps({'total': conn.execute('SELECT COUNT(*) FROM file_index').fetchone()[0],
                      'downloads': conn.execute('SELECT COUNT(*) FROM file_index WHERE path>=? AND path<?',
                                                ('Downloads/', 'Downloads0')).fetchone()[0],
                      'unready_directories': conn.execute('SELECT path FROM index_directories WHERE complete=0 OR stamp IS NULL LIMIT 10').fetchall()}))
