"""Shared SQLite statistics implementation and candidate preparation (Python3.5)."""
import ast
import hashlib
import json
import sqlite3
import sys
import threading
import time
_STORAGE_SUMMARY_BUILD_LOCK = threading.Lock()


def _build_storage_summary(max_seconds=45, refresh=False):
    """Summarize the persistent index; never walk the disk for a dashboard read."""
    _refresh_file_index_for_search()
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=5)
    try:
        conn.execute('CREATE TABLE IF NOT EXISTS storage_summary_snapshot (id INTEGER PRIMARY KEY, root TEXT, scanned_at REAL, payload TEXT)')
        conn.commit()
        conn.execute('BEGIN')
        state = conn.execute('SELECT root, scanned_at, ready FROM index_state WHERE id=1').fetchone()
        root = _get_webdav_public_dir()
        if not state or not root or state[0] != os.path.realpath(root):
            raise ValueError('File index unavailable for the configured root')
        cached = conn.execute('SELECT payload FROM storage_summary_snapshot WHERE id=1 AND root=?', (state[0],)).fetchone()
        if cached and not refresh:
            result = json.loads(cached[0])
            result['partial'] = not bool(state[2]) or _index_scan_lock.locked()
            if time.time() - result.get('summary_updated_at', result['scanned_at']) >= 300 and _STORAGE_SUMMARY_BUILD_LOCK.acquire(False):
                def rebuild():
                    try:
                        _build_storage_summary(refresh=True)
                    except Exception as error:
                        log.warning("[Storage] Background statistics refresh failed: %s", error)
                    finally:
                        _STORAGE_SUMMARY_BUILD_LOCK.release()
                threading.Thread(target=rebuild, daemon=True).start()
            return result
        groups = {
            'image': ('jpg','jpeg','png','webp','bmp','gif','heic','heif'),
            'video': ('mp4','mkv','avi','mov','mpg','mpeg','wmv','flv','ts','m4v','webm'),
            'doc': ('pdf','doc','docx','xls','xlsx','ppt','pptx','txt','md','csv'),
            'audio': ('mp3','wav','flac','m4a','ogg'),
            'archive': ('zip','rar','7z','tar','gz'),
        }
        extensions = dict(('.' + ext, category) for category, values in groups.items() for ext in values)
        conn.create_function('storage_category', 1, lambda name: extensions.get(os.path.splitext(name or '')[1].lower(), 'other'))
        category_sql = 'storage_category(name)'
        by_type = dict((category, {'files': 0, 'bytes': 0}) for category in list(groups) + ['other'])
        for category, count, size in conn.execute('SELECT ' + category_sql + ', COUNT(*), COALESCE(SUM(size),0) FROM file_index WHERE is_dir=0 GROUP BY 1'):
            by_type[category] = {'files': count, 'bytes': size}
        largest = [{'path': row[0], 'bytes': row[1]} for row in conn.execute('SELECT path,size FROM file_index WHERE is_dir=0 ORDER BY size DESC,path LIMIT 10')]
        result = {
            'total_files': sum(value['files'] for value in by_type.values()),
            'total_bytes': sum(value['bytes'] for value in by_type.values()),
            'total_dirs': conn.execute('SELECT COUNT(*) FROM file_index WHERE is_dir=1').fetchone()[0],
            'by_type': by_type, 'largest': largest,
            'partial': not bool(state[2]) or _index_scan_lock.locked(),
            'scanned_at': int(state[1]), 'source': 'file_index',
            'summary_updated_at': int(time.time()),
            'metadata_scope': 'indexed_snapshot',
        }
        conn.commit()
        if not result['partial']:
            conn.execute('INSERT OR REPLACE INTO storage_summary_snapshot(id,root,scanned_at,payload) VALUES(1,?,?,?)', (state[0], state[1], json.dumps(result)))
            conn.commit()
        return result
    finally:
        conn.close()


def prepare(source_path, target):
    with open(source_path, 'r', encoding='utf-8') as handle:
        source = handle.read()
    assert hashlib.sha256(source.encode('utf-8')).hexdigest() == 'a8bcf63a146709fb854a9fba8f416eee89496ad42c6e1fc2f9c05108e2c642d6'
    with open(__file__, 'r', encoding='utf-8') as handle:
        replacement_source = handle.read()
    lines = replacement_source.splitlines(True)
    tree = ast.parse(replacement_source)
    function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == '_build_storage_summary')
    next_function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'prepare')
    replacement = '_STORAGE_SUMMARY_BUILD_LOCK = threading.Lock()\n\n' + ''.join(lines[function.lineno-1:next_function.lineno-1])
    start = source.index('def _build_storage_summary(')
    end = source.index("@app.route('/api/storage/summary'", start)
    candidate = source[:start] + replacement + source[end:]
    compile(candidate, target, 'exec')
    with open(target, 'x', encoding='utf-8') as handle:
        handle.write(candidate)
    print(hashlib.sha256(candidate.encode('utf-8')).hexdigest())


if __name__ == '__main__':
    prepare(sys.argv[1], sys.argv[2])
