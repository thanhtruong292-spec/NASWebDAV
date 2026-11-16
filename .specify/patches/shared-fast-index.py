def generate_fast_index(force=False):
    """Stream one complete public-root SQL snapshot; never walk or cache JSON."""
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=5)
    try:
        conn.execute('BEGIN')
        state = conn.execute('SELECT root,generation,ready,scanned_at,catalogue_revision FROM index_state WHERE id=1').fetchone()
        root = _get_webdav_public_dir()
        if not root or not state or state[0] != os.path.realpath(root):
            _refresh_file_index_for_search()
            raise ValueError('catalogue_root_unavailable')
        if not state[2] or _index_scan_lock.locked():
            _refresh_file_index_for_search()
            raise ValueError('catalogue_refresh_pending')
        # Allow retry/backoff to consume a completed refresh instead of starting
        # another scan on every request after the search five-second interval.
        if time.time() - state[3] > 60:
            _refresh_file_index_for_search()
            raise ValueError('catalogue_refresh_pending')
        total = conn.execute('SELECT COUNT(*) FROM file_index WHERE is_dir=0').fetchone()[0]
        metadata = {'catalogue_protocol': 1, 'source': 'file_index', 'index_ready': True,
                    'index_generation': state[1], 'catalogue_updated_at': state[3],
                    'catalogue_revision': state[4],
                    'truncated': False, 'total': total}
        yield json.dumps(metadata, ensure_ascii=False)[:-1] + ',"files":['
        cursor = conn.execute('SELECT path,name,size,mtime,mime FROM file_index NOT INDEXED WHERE is_dir=0')
        first = True
        while True:
            rows = cursor.fetchmany(512)
            if not rows:
                break
            chunks = []
            for path, name, size, mtime, mime in rows:
                if os.path.isabs(path) or '\\' in path or any(part in ('', '.', '..') for part in path.split('/')):
                    raise ValueError('Invalid indexed path')
                chunks.append(('' if first else ',') + json.dumps({'name': name,
                    'path': '/webdav/' + path, 'size': size, 'mtime': mtime, 'mime': mime}, ensure_ascii=False))
                first = False
            yield ''.join(chunks)
        yield ']}'
    finally:
        conn.close()


def api_fast_index():
    chunks = generate_fast_index()
    try:
        first = next(chunks)
    except (ValueError, sqlite3.Error, OSError):
        chunks.close()
        return jsonify({'catalogue_protocol': 1, 'index_ready': False,
                        'error': 'catalogue_refresh_pending'}), 503, {'Retry-After': '2'}

    def stream():
        try:
            yield first
            for chunk in chunks:
                yield chunk
        finally:
            chunks.close()
    response = Response(stream(), mimetype='application/json')
    response.headers['Cache-Control'] = 'no-store'
    response.call_on_close(chunks.close)
    return response
