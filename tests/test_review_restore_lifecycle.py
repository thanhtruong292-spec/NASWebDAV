"""Run production functions without importing NAS startup or contacting services.

python -m unittest tests.test_review_restore_lifecycle -v
"""
import ast
import io
import json
import os
from pathlib import Path
import shutil
import signal
import tarfile
import tempfile
import threading
import time
import types
import unittest
import uuid
from unittest.mock import Mock


SERVER = Path(__file__).resolve().parents[1] / 'backend' / 'nas_api_server.py'


def production_namespace():
    # Compile actual bodies, including any extracted production helpers. Only
    # route/auth decorators are removed; module startup is intentionally omitted.
    tree = ast.parse(SERVER.read_text(encoding='utf-8'))
    nodes = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
    for node in nodes:
        node.decorator_list = []
    ns = dict(os=os, json=json, shutil=shutil, tarfile=tarfile, tempfile=tempfile,
              threading=threading, time=time, signal=signal, uuid=uuid,
              log=Mock(), sys=Mock(), subprocess=Mock())
    # Some unrelated functions have defaults evaluated at definition time.
    needed = {'api_backup_restore', '_restart_nas_api',
              '_livestream_active_job_for_key_locked'}
    selected = [node for node in nodes if node.name in needed]
    while True:
        referenced = {n.id for node in selected for n in ast.walk(node)
                      if isinstance(n, ast.Name)}
        extra = [node for node in nodes
                 if node.name in referenced and node.name not in needed]
        if not extra:
            break
        selected.extend(extra)
        needed.update(node.name for node in extra)
    # Do not recursively pull platform/logging helpers into this isolated seam.
    selected = [n for n in selected if n.name in {
        'api_backup_restore', '_restart_nas_api',
        '_livestream_active_job_for_key_locked'} or
        ('restore' in n.name or 'drain' in n.name or 'finalizer' in n.name)]
    exec(compile(ast.Module(body=selected, type_ignores=[]), str(SERVER), 'exec'), ns)
    return ns


class RestoreRollbackTests(unittest.TestCase):
    def run_restore(self, root, entries, fail_destination):
        archive = root / 'backup.tar.gz'
        with tarfile.open(archive, 'w:gz') as tar:
            for name, data in entries:
                member = tarfile.TarInfo(name)
                member.size = len(data)
                tar.addfile(member, io.BytesIO(data))
        ns = production_namespace()
        real_replace = os.replace

        def replace(src, dst):
            if Path(dst) == fail_destination and 'restore-tmp' in str(src):
                raise OSError('injected install failure after backup rename')
            return real_replace(src, dst)

        fake_os = types.SimpleNamespace(**{k: getattr(os, k) for k in dir(os)})
        fake_os.replace = replace
        ns.update(os=fake_os, request=types.SimpleNamespace(
            files={}, get_json=lambda **kw: {'filename': archive.name}),
            jsonify=lambda value: value,
            _backup_file_path=lambda name: str(archive),
            _backup_dynamic_files=lambda: [],
            _BACKUP_FILES=[(str(root / name), name, True) for name, _ in entries],
            _BACKUP_RESTORE_MAX_MEMBER_BYTES=100000,
            _BACKUP_RESTORE_COPY_CHUNK=4096,
            _add_system_log=Mock())
        result = ns['api_backup_restore']()
        self.assertIsInstance(result, tuple)
        self.assertEqual(result[1], 500)
        self.assertIn('injected install failure', str(result[0]),
                      'the production handler must reach the intended filesystem fault')
        return result

    def test_failed_install_restores_original_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dest = root / 'auth.conf'
            dest.write_bytes(b'original-current-config')
            self.run_restore(root, [('auth.conf', b'restored-config')], dest)
            self.assertTrue(dest.exists(), 'backup rename must be rolled back when install fails')
            self.assertEqual(dest.read_bytes(), b'original-current-config')

    def test_failed_transaction_removes_newly_created_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dest = root / 'existing.conf'
            dest.write_bytes(b'original')
            self.run_restore(root, [('new.conf', b'new'), ('existing.conf', b'replacement')], dest)
            self.assertFalse((root / 'new.conf').exists(), 'rollback must restore prior absence too')


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.ns = production_namespace()
        self.now = 1000.0
        self.on_sleep = lambda: None
        self.live = set()
        self.exec_live = []

        def sleep(seconds):
            self.now += max(float(seconds), 1.0)
            self.on_sleep()

        def kill(pid, sig):
            if pid not in self.live:
                raise ProcessLookupError(pid)
            if sig:
                self.live.discard(pid)

        fake_os = types.SimpleNamespace(kill=kill, killpg=kill,
            execv=Mock(side_effect=lambda *args: self.exec_live.append(set(self.live))))
        self.ns.update(os=fake_os,
            time=types.SimpleNamespace(time=lambda: self.now, sleep=sleep),
            _livestream_lock=threading.Lock(), _livestream_jobs={},
            _livestream_starting_claims={}, _livestream_finalizers={},
            _livestream_draining=False, normalize_vietnamese_message=str,
            _add_system_log=Mock(), _livestream_snapshot_registry=Mock(),
            _livestream_group_owned_by_job=lambda pid, info: (True, pid not in self.live),
            _livestream_pids_in_group=lambda pid: [(pid, 1)] if pid in self.live else [])

    def test_pending_start_claim_blocks_restart(self):
        self.ns['_livestream_starting_claims']['tiktok:alice'] = {'ts': self.now}
        self.ns['_restart_nas_api']('test pending claim')
        self.assertFalse(self.exec_live, 'unresolved starting claim must abort restart')

    def test_claim_registered_during_drain_is_drained_or_restart_aborts(self):
        claims = self.ns['_livestream_starting_claims']
        claims['tiktok:alice'] = {'ts': self.now}

        def register():
            if claims:
                self.live.add(4242)
                self.ns['_livestream_jobs']['new'] = {
                    'pid': 4242, 'status': 'recording', 'recording_key': 'tiktok:alice'}
                claims.clear()
        self.on_sleep = register
        self.ns['_restart_nas_api']('test registering claim')
        self.assertFalse(any(self.exec_live), 'exec must not orphan the recorder that finished starting')

    def test_unfinished_finalizer_deadline_aborts_restart(self):
        self.ns['_livestream_finalizers']['job'] = {'done': threading.Event()}
        self.ns['_restart_nas_api']('test remux deadline')
        self.assertFalse(self.exec_live, 'timeout is not successful finalizer completion')

    def test_finalizing_job_retains_dedup_after_recorder_exits(self):
        info = {'pid': 4242, 'status': 'finalizing', 'recording_key': 'tiktok:alice'}
        self.ns['_livestream_jobs']['job'] = info
        self.ns['_livestream_finalizers']['job'] = {'done': threading.Event()}
        job_id, found = self.ns['_livestream_active_job_for_key_locked']('tiktok:alice')
        self.assertEqual(job_id, 'job')
        self.assertEqual(info['status'], 'finalizing')


if __name__ == '__main__':
    unittest.main()
