"""Authenticated live checks; modify only unique disposable probe paths."""
import base64
import fcntl
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

values = {}
with open('/etc/nas/auth.conf') as handle:
    for line in handle:
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.strip().split('=', 1)
            values[key.strip()] = value.strip()
token = base64.b64encode((values['WEBDAV_USER'] + ':' + values['WEBDAV_PASS']).encode('utf-8')).decode('ascii')

def request(path, method='GET', data=None, headers=None, webdav=False):
    prefix = 'http://127.0.0.1:8822/webdav/' if webdav else 'http://127.0.0.1:5050/api/'
    if isinstance(data, dict):
        data = json.dumps(data).encode('utf-8')
        headers = dict(headers or {}, **{'Content-Type': 'application/json'})
    req = urllib.request.Request(prefix + path, data=data, method=method)
    req.add_header('Authorization', 'Basic ' + token)
    for name, value in (headers or {}).items():
        req.add_header(name, value)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, response.read(), dict((key.lower(), value) for key, value in response.headers.items())
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers)

def rpc(route, body):
    status, data, _ = request('duplicates/' + route, 'POST', body)
    assert status == 200, 'RPC %s status %s' % (route, status)
    return json.loads(data.decode('utf-8'))

assert request('ping')[0] == 200
if '--health' in sys.argv:
    print('authenticated API health passed')
    sys.exit(0)
if '--index-status' in sys.argv:
    import sqlite3
    connection = sqlite3.connect('/var/lib/nas_fast_index.db', timeout=10)
    try:
        state = connection.execute('SELECT generation,ready,incomplete_reason,built FROM index_state WHERE id=1').fetchone()
        count = connection.execute('SELECT COUNT(*) FROM file_index').fetchone()[0]
        directories = connection.execute('SELECT complete,COUNT(*) FROM index_directories GROUP BY complete').fetchall()
        print(json.dumps({'index_state': state, 'entries': count, 'directory_states': directories}))
    finally:
        connection.close()
    sys.exit(0)
if '--summary-timing' in sys.argv:
    for attempt in range(3):
        started = time.monotonic()
        status, body, _ = request('storage/summary')
        assert status == 200
        summary = json.loads(body.decode('utf-8'))
        print(json.dumps({'milliseconds': round((time.monotonic() - started) * 1000, 2),
            'total_files': summary.get('total_files'), 'total_dirs': summary.get('total_dirs'),
            'partial': summary.get('partial'), 'summary_updated_at': summary.get('summary_updated_at')}))
    sys.exit(0)
if '--search-timing' in sys.argv:
    for attempt in range(3):
        started = time.monotonic()
        status, body, _ = request('search?q=mp4')
        assert status == 200
        result = json.loads(body.decode('utf-8'))
        print(json.dumps({'milliseconds': round((time.monotonic() - started) * 1000, 2),
            'results': len(result.get('results', [])), 'index_ready': result.get('index_ready')}))
    sys.exit(0)
if '--sql-timing' in sys.argv:
    import sqlite3
    connection = sqlite3.connect('file:/var/lib/nas_fast_index.db?mode=ro', uri=True, timeout=10)
    try:
        query = 'SELECT path,name,is_dir,size,mtime,mime FROM file_index WHERE norm_name LIKE ? ORDER BY is_dir DESC,name ASC LIMIT 300'
        print(json.dumps({'plan': connection.execute('EXPLAIN QUERY PLAN ' + query, ('%mp4%',)).fetchall()}))
        for attempt in range(3):
            started = time.monotonic()
            rows = connection.execute(query, ('%mp4%',)).fetchall()
            print(json.dumps({'sql_milliseconds': round((time.monotonic()-started)*1000,2), 'rows': len(rows)}))
    finally:
        connection.close()
    sys.exit(0)

assert rpc('capabilities', {})['backup_protocol'] == 1
folder = 'NAS-Codex-Probe-' + str(uuid.uuid4())
paths = [folder + '/keeper.bin', folder + '/copy.bin']
payload = b'NAS exact duplicate fixture\x00\x01\x02'
digest = hashlib.sha256(payload).hexdigest()
try:
    assert request(folder, 'MKCOL', webdav=True)[0] == 201
    proofs = []
    for path in paths:
        assert request(path, 'PUT', payload, webdav=True)[0] in (201, 204)
        before = rpc('file', {'path': path})
        status, content, headers = request('duplicates/content', 'POST', {'path': path, 'version': before['version']})
        assert status == 200 and content == payload
        assert headers['x-nas-version'] == before['version']
        assert rpc('hash', {'path': path, 'version': before['version'], 'sha256': digest})['saved']
        cached = rpc('file', {'path': path})
        assert cached['sha256'] == digest
        proofs.append(cached)
    found = rpc('find', {'size': len(payload), 'sha256': digest, 'after': folder})
    assert set(paths).issubset(set(found['matches']))
    with open('/run/naswebdav/namespace.lock', 'rb') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        assert request(paths[0], 'PUT', b'blocked', webdav=True)[0] == 503
    assert request(paths[0], webdav=True)[1] == payload
    result = rpc('cleanup', {'victim': paths[1], 'keeper': paths[0],
        'victim_version': proofs[1]['version'], 'keeper_version': proofs[0]['version'], 'sha256': digest})
    assert result['deleted'] is True
    assert request(paths[1], webdav=True)[0] == 404
    assert request(paths[0], webdav=True)[1] == payload
    assert request(paths[0], 'PUT', b'z' * len(payload), webdav=True)[0] == 204
    changed = rpc('file', {'path': paths[0]})
    assert changed['sha256'] == '' and changed['version'] != proofs[0]['version']
    print(json.dumps({'authenticated_api': 'passed', 'webdav_upload_download': 'passed',
        'native_hash_cache': 'passed', 'shared_catalogue': 'passed', 'namespace_exclusion': 'passed',
        'verified_duplicate_cleanup': 'passed', 'overwrite_cache_invalidation': 'passed',
        'user_files_modified': False}))
finally:
    status = request(folder + '/', 'DELETE', webdav=True)[0]
    assert status in (204, 404), 'Owned probe cleanup failed: %s' % status
