import json
import os
import stat
import subprocess
import tempfile

with tempfile.TemporaryDirectory(prefix='NAS-Namespace-Boot-') as root:
    command = ['systemd-tmpfiles', '--create', '--root=' + root, '/tmp/naswebdav-namespace.tmpfiles.conf']
    subprocess.check_call(command)
    directory = os.path.join(root, 'run', 'naswebdav')
    lock = os.path.join(directory, 'namespace.lock')
    assert stat.S_IMODE(os.stat(directory).st_mode) == 0o755
    assert stat.S_IMODE(os.stat(lock).st_mode) == 0o644
    assert os.stat(lock).st_uid == 0
    with open(lock, 'wb') as handle:
        handle.write(b'preserve')
    subprocess.check_call(command)
    with open(lock, 'rb') as handle:
        assert handle.read() == b'preserve'
    os.unlink(lock)
    subprocess.check_call(command)
    assert os.path.isfile(lock)
    print(json.dumps({'tmpfiles_create': True, 'permissions': True, 'idempotent': True, 'recreates_missing_lock': True}))
