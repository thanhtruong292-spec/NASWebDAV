"""Refresh only bounded search results; leave directory discovery incremental."""
import ast
import hashlib
import json
import sys


def prepare(source_path, candidate_path):
    with open(source_path, 'rb') as handle:
        original = handle.read()
    expected = '029e570964b8b5c66a502b2502d43bd74ef0fc89eb752324f07a185821369a0c'
    assert hashlib.sha256(original).hexdigest() == expected, 'Unexpected source revision'
    text = original.decode('utf-8')
    before = '        for rel_p, name, is_dir, size, mtime, mime in rows:\n'
    after = '''        import stat as _search_stat
        public_root = os.path.realpath(actual_root)
        storage_boundary = _index_storage_boundary(public_root)
        checked_parents = {}
        for rel_p, name, is_dir, size, mtime, mime in rows:
            if not is_dir:
                try:
                    if os.path.isabs(rel_p) or ".." in rel_p.split("/"):
                        raise ValueError("Unsafe indexed file path")
                    full_path = os.path.join(public_root, rel_p)
                    parent = os.path.dirname(full_path)
                    if parent not in checked_parents:
                        checked_parents[parent] = _index_resolve_directory(parent, public_root, storage_boundary)
                    if checked_parents[parent] is None:
                        raise ValueError("Indexed file parent is outside the public boundary")
                    current = os.lstat(full_path)
                    if not _search_stat.S_ISREG(current.st_mode):
                        raise ValueError("Indexed file is no longer a regular file")
                    size = current.st_size
                    mtime = int(current.st_mtime * 1000)
                except (OSError, ValueError):
                    readiness["index_ready"] = False
                    readiness["index_incomplete_reason"] = "file_metadata_unavailable"
                    continue
'''
    assert text.count(before) == 1, 'Unexpected search result loop'
    candidate = text.replace(before, after, 1)
    ast.parse(candidate)
    compile(candidate, candidate_path, 'exec')
    with open(candidate_path, 'x', encoding='utf-8') as handle:
        handle.write(candidate)
    print(json.dumps({'source_sha256': expected,
                      'candidate_sha256': hashlib.sha256(candidate.encode('utf-8')).hexdigest(),
                      'python': sys.version.split()[0], 'deployed': False}))


if __name__ == '__main__':
    prepare(sys.argv[1], sys.argv[2])
