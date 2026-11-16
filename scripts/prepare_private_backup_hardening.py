"""Patch only a reviewed, non-live NAS candidate; never deploy or run the server."""
import ast
import hashlib
import sys


def prepare(source_path, fragment_path):
    if source_path == '/opt/nas_api_server.py':
        raise ValueError('Live backend is outside this preparation step')
    with open(source_path, 'rb') as handle:
        original = handle.read()
    if hashlib.sha256(original).hexdigest() != '392fbf4923ff93688e027f8420f124e846213928be4f704bdaa86b64b68175bd':
        raise ValueError('Unexpected candidate revision')
    source = original.decode('utf-8')
    with open(fragment_path, encoding='utf-8') as handle:
        fragment = handle.read()
    tree = ast.parse(source)
    node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == '_ensure_backup_dir')
    lines = source.splitlines(True)
    next_node = next(n for n in tree.body if n.lineno > node.lineno)
    source = ''.join(lines[:node.lineno - 1]) + fragment + '\n\n' + ''.join(lines[next_node.lineno - 1:])
    old = 'with tarfile.open(full_path, "w:gz") as tar:'
    if source.count(old) != 2:
        raise ValueError('Unexpected backup archive callers')
    source = source.replace(old, 'with _open_private_backup_tar(full_path) as tar:')
    ast.parse(source)
    with open(source_path, 'w', encoding='utf-8') as handle:
        handle.write(source)
    print(hashlib.sha256(source.encode('utf-8')).hexdigest())


if __name__ == '__main__':
    prepare(sys.argv[1], sys.argv[2])
