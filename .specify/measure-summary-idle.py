import ast
import json
import logging
import os
import sqlite3
import threading
import time
import types

source = '/opt/nas_api_server.py.verified-duplicates-candidate-20261114-v4'
with open(source) as handle:
    tree = ast.parse(handle.read())
node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == '_build_storage_summary')
namespace = dict(os=os, json=json, sqlite3=sqlite3, threading=threading, time=time,
                 INDEX_DB_PATH='/var/lib/nas_fast_index.validation-20261114.db',
                 _STORAGE_SUMMARY_BUILD_LOCK=threading.Lock(), _index_scan_lock=threading.Lock(),
                 _refresh_file_index_for_search=lambda: None,
                 _get_webdav_public_dir=lambda: '/srv/dev-disk-by-label-data/New folder',
                 log=logging.getLogger('summary-validation'))
exec(compile(ast.Module(body=[node]), source, 'exec'), namespace)
start = time.time()
first = namespace['_build_storage_summary'](refresh=True)
print(json.dumps({'initial_sql_ms': (time.time()-start)*1000, 'files': first['total_files'],
                  'index_revision': first['index_revision']}))
namespace['time'] = types.SimpleNamespace(time=lambda: time.time()+3600)
def unexpected_refresh(**kwargs):
    raise AssertionError('Unchanged index launched another aggregate after one hour')
namespace['threading'] = types.SimpleNamespace(Thread=unexpected_refresh)
for run in range(3):
    start=time.time()
    result=namespace['_build_storage_summary']()
    assert result['summary_updated_at'] == first['summary_updated_at']
    print(json.dumps({'idle_one_hour_run':run+1,'ms':(time.time()-start)*1000,'aggregate_repeated':False}))
