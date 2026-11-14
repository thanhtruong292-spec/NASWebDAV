"""Exercise the actual NAS coordinator using only disposable files and SQLite."""
import ast
import hashlib
import json
import logging
import multiprocessing
import os
import sqlite3
import signal
import stat
import subprocess
import sys
import tempfile
import time
from flask import Flask, jsonify, request


def run(source, storage, php_guard=None):
    with open(source, 'r', encoding='utf-8') as handle:
        code = handle.read()
    compile(code, source, 'exec')
    signal.signal(signal.SIGIO, signal.SIG_IGN)
    names = {'_duplicate_validate_namespace', '_duplicate_namespace_before_request',
             '_duplicate_namespace_teardown_request', '_duplicate_open_namespace_lock', '_duplicate_stat_version', '_duplicate_open_lease', '_duplicate_sync_directory', '_duplicate_file_info', '_duplicate_db', '_duplicate_lookup',
             '_duplicate_save_hash', '_duplicate_work_dir', '_duplicate_cleanup',
             '_index_resolve_directory'}
    nodes = [node for node in ast.parse(code).body if isinstance(node, ast.FunctionDef) and node.name in names]
    assert len(nodes) == len(names)
    with tempfile.TemporaryDirectory(prefix='NAS-Duplicate-Check-', dir=storage) as work:
        root = os.path.join(work, 'public')
        os.mkdir(root)
        ns = dict(os=os, stat=stat, json=json, time=time, hashlib=hashlib,
                  app=Flask('namespace-runtime'), request=request, jsonify=jsonify,
                  sqlite3=sqlite3, log=logging.getLogger('duplicate-test'),
                  INDEX_DB_PATH=os.path.join(work, 'index.sqlite'),
                  _get_webdav_public_dir=lambda: root,
                  _index_storage_boundary=lambda path: work)
        module = ast.Module(body=nodes)
        if hasattr(module, 'type_ignores'):
            module.type_ignores = []
        exec(compile(module, source, 'exec'), ns)
        ns['_DUPLICATE_NAMESPACE_LOCK'] = os.path.join(work, 'namespace.lock')
        protected_sources = [os.path.join(work, name) for name in ('controller.php', 'guard.php', 'boot.conf')]
        for path in protected_sources:
            with open(path, 'w') as handle:
                handle.write('namespace fixture')
            os.chmod(path, 0o644)
        ns['_DUPLICATE_NAMESPACE_FILES'] = protected_sources
        ns['_DUPLICATE_NAMESPACE_MANIFEST'] = os.path.join(work, 'manifest.json')
        manifest = {'lock_path': ns['_DUPLICATE_NAMESPACE_LOCK'], 'files': {}}
        for path in protected_sources:
            with open(path, 'rb') as handle:
                manifest['files'][path] = hashlib.sha256(handle.read()).hexdigest()
        with open(ns['_DUPLICATE_NAMESPACE_MANIFEST'], 'w') as handle:
            json.dump(manifest, handle)
        os.chmod(ns['_DUPLICATE_NAMESPACE_MANIFEST'], 0o644)
        ns['_duplicate_validate_namespace']()
        with open(protected_sources[0], 'a') as handle:
            handle.write('tampered')
        try:
            ns['_duplicate_validate_namespace']()
            raise AssertionError('Changed controller bypassed manifest')
        except ValueError:
            pass
        with open(protected_sources[0], 'w') as handle:
            handle.write('namespace fixture')
        os.chmod(ns['_DUPLICATE_NAMESPACE_MANIFEST'], 0o666)
        try:
            ns['_duplicate_validate_namespace']()
            raise AssertionError('Writable manifest was trusted')
        except ValueError:
            pass
        os.chmod(ns['_DUPLICATE_NAMESPACE_MANIFEST'], 0o644)
        os.chown(ns['_DUPLICATE_NAMESPACE_MANIFEST'], 65534, 65534)
        try:
            ns['_duplicate_validate_namespace']()
            raise AssertionError('Non-root manifest was trusted')
        except ValueError:
            pass
        os.chown(ns['_DUPLICATE_NAMESPACE_MANIFEST'], 0, 0)
        actual_manifest = ns['_DUPLICATE_NAMESPACE_MANIFEST']
        os.rename(actual_manifest, actual_manifest + '.real')
        os.symlink(actual_manifest + '.real', actual_manifest)
        try:
            ns['_duplicate_validate_namespace']()
            raise AssertionError('Symlink manifest was trusted')
        except ValueError:
            pass
        os.unlink(actual_manifest)
        os.rename(actual_manifest + '.real', actual_manifest)
        ns['_duplicate_validate_namespace']()
        fifo = os.path.join(work, 'unsafe-fifo')
        os.mkfifo(fifo)
        lock_path = ns['_DUPLICATE_NAMESPACE_LOCK']
        ns['_DUPLICATE_NAMESPACE_LOCK'] = fifo
        try:
            try:
                ns['_duplicate_open_namespace_lock'](True)
                raise AssertionError('FIFO lock was trusted')
            except ValueError:
                pass
        finally:
            ns['_DUPLICATE_NAMESPACE_LOCK'] = lock_path
        conn = ns['_duplicate_db']()
        conn.execute('CREATE TABLE file_index(path TEXT PRIMARY KEY)')
        conn.commit()
        conn.close()
        digest = hashlib.sha256(b'abc').hexdigest()

        def create(name):
            with open(os.path.join(root, name), 'wb') as handle:
                handle.write(b'abc')
            info = ns['_duplicate_file_info'](name)
            ns['_duplicate_save_hash'](name, info['version'], digest)
            assert ns['_duplicate_lookup'](info) == digest
            return info

        keeper, victim = create('keep'), create('copy')
        assert ns['_duplicate_cleanup']('copy', 'keep', victim['version'], keeper['version'], digest)['deleted']
        assert os.path.exists(os.path.join(root, 'keep')) and not os.path.exists(os.path.join(root, 'copy'))
        os.mkdir(os.path.join(root, 'nested'))
        original = create('nested/original')
        os.symlink(os.path.join(root, 'nested'), os.path.join(root, 'alias'))
        alias = ns['_duplicate_file_info']('alias/original')
        assert alias['file_id'] == original['file_id']
        try:
            ns['_duplicate_cleanup']('alias/original', 'nested/original', alias['version'], original['version'], digest)
            raise AssertionError('Physical alias was deleted')
        except ValueError:
            pass
        assert os.path.exists(os.path.join(root, 'nested/original'))
        a, b = create('changed'), create('unchanged')
        path = os.path.join(root, 'changed')
        before = os.stat(path)
        with open(path, 'wb') as handle:
            handle.write(b'xyz')
        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        assert ns['_duplicate_file_info']('changed')['version'] != a['version']
        try:
            ns['_duplicate_cleanup']('changed', 'unchanged', a['version'], b['version'], digest)
            raise AssertionError('Same-size, same-mtime changed file was deleted')
        except ValueError:
            pass
        assert os.path.exists(path)
        first, second = create('race-one'), create('race-two')
        start = multiprocessing.Event()
        results = multiprocessing.Queue()
        def race(victim_info, keeper_info):
            start.wait(5)
            try:
                result = ns['_duplicate_cleanup'](victim_info['path'], keeper_info['path'],
                                                   victim_info['version'], keeper_info['version'], digest)
                results.put(bool(result['deleted']))
            except (OSError, ValueError):
                results.put(False)
        workers = [multiprocessing.Process(target=race, args=(first, second)),
                   multiprocessing.Process(target=race, args=(second, first))]
        for worker in workers:
            worker.start()
        start.set()
        for worker in workers:
            worker.join(10)
            assert not worker.is_alive(), 'Cleanup did not finish'
        assert sum(results.get(timeout=2) for worker in workers) == 1
        assert sum(os.path.exists(os.path.join(root, name)) for name in ('race-one', 'race-two')) == 1
        busy_keeper, busy_copy = create('busy-keeper'), create('busy-copy')
        with open(os.path.join(root, 'busy-copy'), 'ab') as writer:
            try:
                ns['_duplicate_cleanup']('busy-copy', 'busy-keeper', busy_copy['version'], busy_keeper['version'], digest)
                raise AssertionError('Open writer was not excluded')
            except OSError:
                pass
        assert os.path.exists(os.path.join(root, 'busy-copy'))
        keeper, victim = create('rollback-keeper'), create('rollback-copy')
        real_info = ns['_duplicate_file_info']
        calls = [0]
        def changing_keeper(name):
            if name == 'rollback-keeper':
                calls[0] += 1
                if calls[0] == 2:
                    try:
                        writer = os.open(os.path.join(root, name), os.O_WRONLY | os.O_NONBLOCK)
                    except OSError:
                        pass
                    else:
                        os.close(writer)
                        raise AssertionError('Writer bypassed read lease')
            return real_info(name)
        ns['_duplicate_file_info'] = changing_keeper
        try:
            ns['_duplicate_cleanup']('rollback-copy', 'rollback-keeper', victim['version'], keeper['version'], digest)
            raise AssertionError('Changed survivor was accepted')
        except ValueError:
            pass
        assert os.path.exists(os.path.join(root, 'rollback-copy'))
        ns['_duplicate_file_info'] = real_info
        keeper, victim = create('crash-keeper'), create('crash-copy')
        real_rename = os.rename
        class SimulatedCrash(BaseException):
            pass
        def crash_after_move(source_path, destination_path):
            real_rename(source_path, destination_path)
            raise SimulatedCrash()
        os.rename = crash_after_move
        try:
            try:
                ns['_duplicate_cleanup']('crash-copy', 'crash-keeper', victim['version'], keeper['version'], digest)
                raise AssertionError('Crash seam did not execute')
            except SimulatedCrash:
                pass
        finally:
            os.rename = real_rename
        directory = ns['_duplicate_work_dir']()
        journals = [name for name in os.listdir(directory) if name.endswith('.json')]
        assert len(journals) == 1
        with open(os.path.join(directory, journals[0])) as handle:
            recovery = json.load(handle)
        assert recovery['original_path'] == 'crash-copy'
        assert recovery['file_id'] == victim['file_id']
        assert os.path.exists(recovery['quarantine'])
        real_rename(recovery['quarantine'], recovery['physical'])
        os.unlink(os.path.join(directory, journals[0]))
        if php_guard:
            os.chmod(work, 0o755)
            for method in ('DELETE', 'MOVE', 'PUT'):
                keeper, victim = create('php-keeper'), create('php-copy')
                program = "require $argv[1]; $_SERVER['REQUEST_METHOD']=$argv[3]; nas_namespace_guard($argv[2]); echo \"READY\\n\"; fflush(STDOUT); fgets(STDIN);"
                process = subprocess.Popen(['runuser', '-u', 'webdav', '--', 'php', '-r', program,
                                            php_guard, ns['_DUPLICATE_NAMESPACE_LOCK'], method],
                                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    assert process.stdout.readline().strip() == b'READY', 'PHP guard did not acquire shared lock'
                    try:
                        ns['_duplicate_cleanup']('php-copy', 'php-keeper', victim['version'], keeper['version'], digest)
                        raise AssertionError('Cleanup bypassed active PHP mutation')
                    except OSError:
                        pass
                    assert os.path.exists(os.path.join(root, 'php-keeper'))
                    assert os.path.exists(os.path.join(root, 'php-copy'))
                finally:
                    process.communicate(b'finish\n', timeout=5)
                assert process.returncode == 0
                assert ns['_duplicate_cleanup']('php-copy', 'php-keeper', victim['version'], keeper['version'], digest)['deleted']
            lock_fd = ns['_duplicate_open_namespace_lock'](True)
            try:
                program = "require $argv[1]; $_SERVER['REQUEST_METHOD']=$argv[3]; nas_namespace_guard($argv[2]); echo 'ALLOWED';"
                for method in ('DELETE', 'MOVE', 'PUT', 'GET'):
                    result = subprocess.check_output(['runuser', '-u', 'webdav', '--', 'php', '-r', program,
                                                       php_guard, ns['_DUPLICATE_NAMESPACE_LOCK'], method])
                    assert (result == b'ALLOWED') == (method == 'GET'), 'PHP mutation bypassed exclusive cleanup'
            finally:
                os.close(lock_fd)
        app = ns['app']
        @app.route('/api/disk/trash_batch', methods=['POST'], endpoint='api_disk_trash_batch')
        def mock_trash():
            return jsonify({'ok': True})
        client = app.test_client()
        assert client.post('/api/disk/trash_batch').status_code == 200
        held = ns['_duplicate_open_namespace_lock'](True)
        os.close(held)
        held = ns['_duplicate_open_namespace_lock'](True)
        try:
            response = client.post('/api/disk/trash_batch')
            assert response.status_code == 503
            assert response.headers.get('Retry-After') == '1'
        finally:
            os.close(held)
        assert client.post('/api/disk/trash_batch').status_code == 200
        print(json.dumps({'python': sys.version.split()[0], 'php_namespace_interop': bool(php_guard),
                          'manifest_fail_closed': True, 'trash_hook_release_and_busy_503': True, 'cache_reused': True,
                          'one_survivor': True, 'physical_alias_rejected': True,
                          'same_size_same_mtime_change_rejected': True,
                          'opposite_concurrent_cleanup_preserved_one': True,
                          'writer_break_rolled_back': True, 'open_writer_rejected': True, 'crash_recovery_mapping_persisted': True}))


if __name__ == '__main__':
    run(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
