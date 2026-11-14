"""Prepare reviewable PHP/manifest artifacts; never install or restart anything."""
import hashlib
import json
import os
import sys


def prepare(original, guard, output):
    with open(original, 'r', encoding='utf-8') as handle:
        source = handle.read()
    marker = "$server = require_once __DIR__ . '/../bootstrap/server.php';"
    if source.count(marker) != 1 or source.count('$server->exec();') != 1:
        raise ValueError('Unexpected WebDAV entrypoint; inspect before preparing')
    include = "require_once __DIR__ . '/../bootstrap/nas_namespace_guard.php';\nnas_namespace_guard();\n\n"
    candidate = source.replace(marker, include + marker, 1)
    with open(guard, 'rb') as handle:
        guard_bytes = handle.read()
    with open(os.path.join(os.path.dirname(__file__), '..', 'backend', 'naswebdav-namespace.tmpfiles.conf'), 'rb') as handle:
        boot_bytes = handle.read()
    manifest = {'lock_path': '/run/naswebdav/namespace.lock', 'files': {
        '/var/www/webdav/public/index.php': hashlib.sha256(candidate.encode('utf-8')).hexdigest(),
        '/var/www/webdav/bootstrap/nas_namespace_guard.php': hashlib.sha256(guard_bytes).hexdigest(),
        '/etc/tmpfiles.d/naswebdav-namespace.conf': hashlib.sha256(boot_bytes).hexdigest()}}
    os.makedirs(output, exist_ok=True)
    with open(os.path.join(output, 'index.php.candidate'), 'w', encoding='utf-8', newline='\n') as handle:
        handle.write(candidate)
    with open(os.path.join(output, 'namespace-manifest.json'), 'w', encoding='utf-8') as handle:
        json.dump(manifest, handle, sort_keys=True)


if __name__ == '__main__':
    prepare(sys.argv[1], sys.argv[2], sys.argv[3])
