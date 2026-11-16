import ast
import hashlib
import json
import logging
import os
import re
import shutil
import stat
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path


class UsbOverwriteIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        source = Path(os.environ.get('NAS_BACKEND_SOURCE', str(Path(__file__).resolve().parents[1] / 'backend/nas_api_server.py')))
        names = {'_usb_import_file_version', '_usb_import_copy_file_with_progress',
                 '_usb_import_write_file_with_progress', '_usb_import_copy_tree'}
        nodes = [n for n in ast.parse(source.read_text(encoding='utf-8')).body
                 if isinstance(n, ast.FunctionDef) and n.name in names]
        proxy = types.SimpleNamespace(**vars(os))
        proxy.sync = lambda: None
        self.state = {}
        self.ns = dict(os=proxy, hashlib=hashlib, json=json, log=logging.getLogger('usb-test'),
                       shutil=shutil, tempfile=tempfile, threading=threading, time=time,
                       _re_module=re, _usb_import_cancel=threading.Event(),
                       _usb_import_lock=threading.Lock(), _usb_import_state=self.state,
                       WEBDAV_FILE_ROOT=str(self.root / 'public'),
                       _usb_import_set_state=lambda **values: self.state.update(values),
                       _duplicate_open_namespace_lock=lambda exclusive: os.open(str(self.root / 'lock'), os.O_RDONLY | os.O_CREAT))
        exec(compile(ast.Module(body=nodes, type_ignores=[]), str(source), 'exec'), self.ns)
        self.src = self.root / 'source.bin'
        self.dst = self.root / 'destination.bin'
        self.src.write_bytes(b'new contents')
        self.dst.write_bytes(b'old contents')

    def totals(self):
        return dict(bytes_done=0, bytes_processed=0, current_file_done=0,
                    done=0, skipped=0, failed=0, bytes_total=12, verify_checksum=True)

    def test_complete_overwrite_replaces_contents_and_leaves_no_staging_file(self):
        digest = self.ns['_usb_import_copy_file_with_progress'](str(self.src), str(self.dst), self.totals())
        self.assertEqual(self.src.read_bytes(), self.dst.read_bytes())
        self.assertEqual(hashlib.sha256(self.src.read_bytes()).hexdigest(), digest)
        self.assertFalse(list(self.root.glob('.nas-usb-*')))

    def test_commit_syncs_file_before_publish_and_directory_after(self):
        events = []
        proxy = self.ns['os']
        def sync(fd):
            events.append('directory' if stat.S_ISDIR(os.fstat(fd).st_mode) else 'file')
            os.fsync(fd)
        def replace(src, dst):
            self.assertEqual(['file'], events)
            events.append('publish')
            os.replace(src, dst)
        proxy.fsync = sync
        proxy.replace = replace
        self.ns['_usb_import_copy_file_with_progress'](str(self.src), str(self.dst), self.totals())
        self.assertEqual(['file', 'publish'] + ([] if os.name == 'nt' else ['directory']), events)

    def test_cancel_and_copy_failure_preserve_old_destination(self):
        self.ns['_usb_import_cancel'].set()
        with self.assertRaises(InterruptedError):
            self.ns['_usb_import_copy_file_with_progress'](str(self.src), str(self.dst), self.totals())
        self.assertEqual(b'old contents', self.dst.read_bytes())
        self.ns['_usb_import_cancel'].clear()
        def broken_copy(src, staged, totals):
            Path(staged).write_bytes(b'partial')
            raise OSError('simulated read failure')
        self.ns['_usb_import_write_file_with_progress'] = broken_copy
        with self.assertRaises(OSError):
            self.ns['_usb_import_copy_file_with_progress'](str(self.src), str(self.dst), self.totals())
        self.assertEqual(b'old contents', self.dst.read_bytes())
        self.assertFalse(list(self.root.glob('.nas-usb-*')))

    def test_same_inode_and_new_only_never_truncate_existing_data(self):
        with self.assertRaises(FileExistsError):
            self.ns['_usb_import_copy_file_with_progress'](str(self.src), str(self.dst), self.totals(), overwrite=False)
        self.assertEqual(b'old contents', self.dst.read_bytes())
        self.dst.unlink()
        os.link(str(self.src), str(self.dst))
        with self.assertRaises(ValueError):
            self.ns['_usb_import_copy_file_with_progress'](str(self.src), str(self.dst), self.totals())
        self.assertEqual(b'new contents', self.src.read_bytes())

    def configure_tree(self, mode):
        src_root = self.root / 'USB'
        src_root.mkdir()
        source = src_root / 'file.bin'
        source.write_bytes(b'new contents')
        dest_root = self.root / 'public' / 'Destination' / 'USB'
        dest_root.mkdir(parents=True)
        dest = dest_root / 'file.bin'
        dest.write_bytes(b'old contents')
        noop = lambda *args, **kwargs: None
        for name in ('_set_thumbnail_auto_block', '_usb_import_apply_path_permissions',
                     '_usb_import_remove_plan_files', '_usb_import_add_history',
                     '_add_system_log', '_usb_import_mark_stopped', '_usb_import_save_state',
                     '_index_mutated_directories'):
            self.ns[name] = noop
        self.ns['_usb_import_ensure_dir'] = lambda path: os.makedirs(path, exist_ok=True)
        self.ns['_usb_import_build_or_reuse_plan'] = lambda *args: (str(self.root / 'plan'), '', 1, 12, False)
        self.ns['_usb_import_plan_prefix_stats'] = lambda *args: (0, 0)
        self.ns['_usb_import_iter_plan'] = lambda *args: iter([(0, str(source), str(dest), 'file.bin', 12)])
        self.ns['_usb_import_conflict_item'] = lambda src, dst, base, size: {'source': src, 'dest': dst}
        candidate = {'mountpoint': str(src_root), 'id': 'usb-id', 'label': 'USB'}
        settings = {'dest_folder': 'Destination', 'copy_mode': mode}
        return candidate, settings, dest

    def test_tree_consumes_overwrite_setting(self):
        candidate, settings, dest = self.configure_tree('overwrite')
        self.ns['_usb_import_copy_tree'](candidate, settings)
        self.assertEqual(b'new contents', dest.read_bytes())
        self.assertEqual(1, self.state['files_done'])
        self.assertEqual(0, self.state['pending_conflicts_count'])

    def test_tree_new_only_keeps_destination_and_reports_conflict(self):
        candidate, settings, dest = self.configure_tree('new_only')
        self.ns['_usb_import_copy_tree'](candidate, settings)
        self.assertEqual(b'old contents', dest.read_bytes())
        self.assertEqual(1, self.state['pending_conflicts_count'])

    def test_resume_cannot_borrow_checkpoint_from_other_destination_or_mode(self):
        candidate, settings, dest = self.configure_tree('overwrite')
        previous = self.root / 'previous'
        previous.mkdir()
        self.state.update(dest_dir=str(previous), active_id='usb-id', status='cancelled', plan_index=42)
        class Captured(Exception):
            pass
        decisions = []
        def capture(src, dst, session, resume):
            decisions.append(resume)
            raise Captured()
        self.ns['_usb_import_build_or_reuse_plan'] = capture
        with self.assertRaises(Captured):
            self.ns['_usb_import_copy_tree'](candidate, settings)
        self.assertEqual([False], decisions)
        self.state['dest_dir'] = str(dest.parent)
        self.state['session_id'] = hashlib.sha1(('usb-id|%s|new_only' % dest.parent).encode('utf-8')).hexdigest()[:12]
        with self.assertRaises(Captured):
            self.ns['_usb_import_copy_tree'](candidate, settings)
        self.assertEqual([False, False], decisions)


if __name__ == '__main__':
    unittest.main()
