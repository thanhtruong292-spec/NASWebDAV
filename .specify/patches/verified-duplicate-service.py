"""Internal NAS duplicate coordinator. Hash bytes on clients, validate metadata here."""
import os
import stat
import json
import time
import hashlib
import sqlite3
import threading
import signal

# Leases are polled; SIGIO must never terminate the API during a lease break.
if hasattr(signal, 'SIGIO') and threading.current_thread() is threading.main_thread():
    signal.signal(signal.SIGIO, signal.SIG_IGN)


def _duplicate_file_info(relative):
    if not isinstance(relative, str) or not relative or relative.startswith('/') or '\\' in relative:
        raise ValueError('Invalid public file path')
    if any(not part or part in ('.', '..') or part.startswith('.') for part in relative.split('/')):
        raise ValueError('Hidden or unsafe file path')
    configured = _get_webdav_public_dir()
    if not configured:
        raise ValueError('Public root unavailable')
    root = os.path.realpath(configured)
    if not root or root == os.path.abspath(os.sep):
        raise ValueError('Public root unavailable')
    logical = os.path.join(root, relative)
    parent = _index_resolve_directory(os.path.dirname(logical), root, _index_storage_boundary(root))
    if parent is None:
        raise ValueError('File is outside the public boundary')
    physical = os.path.join(parent, os.path.basename(logical))
    current = os.lstat(physical)
    if not stat.S_ISREG(current.st_mode):
        raise ValueError('Only regular files can be verified')
    return {'path': relative, 'physical': physical, 'size': current.st_size,
            'mtime_ns': current.st_mtime_ns,
            'file_id': '%s:%s' % (current.st_dev, current.st_ino),
            'version': _duplicate_stat_version(current)}


def _duplicate_stat_version(current):
    value = '%s:%s:%s:%s:%s' % (current.st_dev, current.st_ino, current.st_size,
                                current.st_mtime_ns, current.st_ctime_ns)
    return hashlib.sha256(value.encode('ascii')).hexdigest()


def _duplicate_open_lease(info):
    import fcntl
    import signal
    if signal.getsignal(signal.SIGIO) != signal.SIG_IGN:
        raise ValueError('Safe lease notification is unavailable')
    fd = os.open(info['physical'], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        # No async signal recipient: inspect F_GETLEASE and fail closed on a break.
        fcntl.fcntl(fd, fcntl.F_SETOWN, 0)
        fcntl.fcntl(fd, fcntl.F_SETLEASE, fcntl.F_RDLCK)
        fcntl.fcntl(fd, fcntl.F_SETOWN, 0)
        if fcntl.fcntl(fd, fcntl.F_GETOWN) != 0:
            raise ValueError('Unexpected lease signal owner')
        if _duplicate_stat_version(os.fstat(fd)) != info['version']:
            raise ValueError('File changed before writer exclusion')
        return fd
    except Exception:
        os.close(fd)
        raise


def _duplicate_sync_directory(directory):
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _duplicate_db():
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=5)
    conn.execute('CREATE TABLE IF NOT EXISTS verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, verified_at REAL NOT NULL)')
    conn.commit()
    return conn


def _duplicate_lookup(info):
    conn = _duplicate_db()
    try:
        row = conn.execute('SELECT sha256 FROM verified_file_hashes WHERE file_id=? AND version=? AND size=?',
                           (info['file_id'], info['version'], info['size'])).fetchone()
        return row[0] if row else ''
    finally:
        conn.close()


def _duplicate_save_hash(relative, version, sha256):
    if not isinstance(sha256, str) or len(sha256) != 64 or any(c not in '0123456789abcdef' for c in sha256):
        raise ValueError('A complete SHA256 is required')
    info = _duplicate_file_info(relative)
    if info['version'] != version:
        raise ValueError('File changed during verification')
    conn = _duplicate_db()
    try:
        conn.execute('INSERT OR REPLACE INTO verified_file_hashes VALUES(?,?,?,?,?)',
                     (info['file_id'], info['version'], info['size'], sha256, time.time()))
        conn.commit()
    finally:
        conn.close()
    return info


def _duplicate_work_dir():
    configured = _get_webdav_public_dir()
    if not configured:
        raise ValueError('Public root unavailable')
    root = os.path.realpath(configured)
    directory = os.path.join(_index_storage_boundary(root) or root, '.naswebdav', 'verified_duplicates')
    os.makedirs(directory, mode=0o700, exist_ok=True)
    if os.path.realpath(directory) != os.path.abspath(directory) or os.lstat(directory).st_uid != os.geteuid():
        raise ValueError('Unsafe duplicate work directory')
    os.chmod(directory, 0o700)
    return directory


def _duplicate_cleanup(victim_path, keeper_path, victim_version, keeper_version, sha256):
    import fcntl
    import uuid
    directory = _duplicate_work_dir()
    with open(os.path.join(directory, 'cleanup.lock'), 'a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        keeper = _duplicate_file_info(keeper_path)
        victim = _duplicate_file_info(victim_path)
        if keeper['file_id'] == victim['file_id']:
            raise ValueError('Both URLs address the same physical file')
        if keeper['version'] != keeper_version or victim['version'] != victim_version:
            raise ValueError('File version changed')
        if keeper['size'] != victim['size'] or not sha256 or _duplicate_lookup(keeper) != sha256 or _duplicate_lookup(victim) != sha256:
            raise ValueError('Full-content verification is missing')
        keeper_fd = _duplicate_open_lease(keeper)
        victim_fd = None
        try:
            victim_fd = _duplicate_open_lease(victim)
            quarantine = os.path.join(directory, uuid.uuid4().hex)
            journal = quarantine + '.json'
            with open(journal, 'x') as recovery:
                json.dump({'original_path': victim_path, 'physical': victim['physical'],
                           'quarantine': quarantine, 'file_id': victim['file_id'],
                           'version': victim_version, 'created_at': time.time()}, recovery)
                recovery.flush()
                os.fsync(recovery.fileno())
            _duplicate_sync_directory(directory)
            try:
                os.rename(victim['physical'], quarantine)
                _duplicate_sync_directory(os.path.dirname(victim['physical']))
                _duplicate_sync_directory(directory)
                moved = os.lstat(quarantine)
                if ('%s:%s' % (moved.st_dev, moved.st_ino) != victim['file_id'] or
                        moved.st_size != victim['size'] or moved.st_mtime_ns != victim['mtime_ns']):
                    raise ValueError('Candidate was replaced during cleanup')
                survivor = _duplicate_file_info(keeper_path)
                if survivor['version'] != keeper_version:
                    raise ValueError('Survivor changed during cleanup')
                if any(fcntl.fcntl(fd, fcntl.F_GETLEASE) != fcntl.F_RDLCK for fd in (keeper_fd, victim_fd)):
                    raise ValueError('File was requested for writing; cleanup cancelled')
                # shortcut: external namespace deletes need shared WebDAV locking before production acceptance.
                os.unlink(quarantine)
            except Exception:
                if os.path.lexists(quarantine) and not os.path.lexists(victim['physical']):
                    os.rename(quarantine, victim['physical'])
                    _duplicate_sync_directory(os.path.dirname(victim['physical']))
                raise
            finally:
                if not os.path.lexists(quarantine):
                    os.unlink(journal)
                _duplicate_sync_directory(directory)
        finally:
            if victim_fd is not None:
                os.close(victim_fd)
            os.close(keeper_fd)
        conn = None
        try:
            conn = _duplicate_db()
            conn.execute('DELETE FROM verified_file_hashes WHERE file_id=?', (victim['file_id'],))
            conn.execute('DELETE FROM file_index WHERE path=?', (victim_path,))
            conn.commit()
        except sqlite3.Error as error:
            log.warning('[Duplicates] Deleted file metadata refresh deferred: %s', error)
        finally:
            if conn is not None:
                conn.close()
        return {'deleted': True, 'path': victim_path, 'keeper': keeper_path,
                'logical_bytes_removed': victim['size']}


@app.route('/api/duplicates/file', methods=['POST'])
@requires_admin
def api_duplicate_file():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        info = _duplicate_file_info(body.get('path'))
        info.pop('physical')
        info['sha256'] = _duplicate_lookup(info)
        return jsonify(info)
    except (OSError, ValueError) as error:
        return jsonify({'error': str(error)}), 409


@app.route('/api/duplicates/hash', methods=['POST'])
@requires_admin
def api_duplicate_hash():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        _duplicate_save_hash(body.get('path'), body.get('version'), body.get('sha256'))
        return jsonify({'saved': True})
    except (OSError, ValueError) as error:
        return jsonify({'error': str(error)}), 409


@app.route('/api/duplicates/cleanup', methods=['POST'])
@requires_admin
def api_duplicate_cleanup():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        result = _duplicate_cleanup(body.get('victim'), body.get('keeper'), body.get('victim_version'),
                                    body.get('keeper_version'), body.get('sha256'))
        return jsonify(result)
    except (OSError, ValueError) as error:
        return jsonify({'deleted': False, 'error': str(error)}), 409


@app.route('/api/duplicates/content', methods=['POST'])
@requires_admin
def api_duplicate_content():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        info = _duplicate_file_info(body.get('path'))
        if info['version'] != body.get('version'):
            raise ValueError('File version changed')
        fd = _duplicate_open_lease(info)
    except (OSError, ValueError) as error:
        return jsonify({'error': str(error)}), 409

    handle = os.fdopen(fd, 'rb')

    def generate():
        import fcntl
        try:
            remaining = info['size']
            while remaining:
                if fcntl.fcntl(handle.fileno(), fcntl.F_GETLEASE) != fcntl.F_RDLCK:
                    raise ValueError('File requested for writing during verification')
                chunk = handle.read(min(65536, remaining))
                if not chunk:
                    raise ValueError('Incomplete verification stream')
                remaining -= len(chunk)
                yield chunk
        finally:
            handle.close()

    response = Response(generate(), mimetype='application/octet-stream')
    response.call_on_close(handle.close)
    response.content_length = info['size']
    response.headers['X-NAS-Version'] = info['version']
    response.headers['X-NAS-File-ID'] = info['file_id']
    response.headers['Cache-Control'] = 'no-store'
    return response
