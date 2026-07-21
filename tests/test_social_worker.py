# -*- coding: utf-8 -*-
"""Focused harness for the real ``_social_worker`` function body."""

import ast
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

try:
    from tests.social_logic_loader import load_social_logic
except ImportError:
    from social_logic_loader import load_social_logic

social_extractor = load_social_logic()


class _SilentLog(object):
    def info(self, *args, **kwargs):
        pass

    def warning(self, *args, **kwargs):
        pass

    def error(self, *args, **kwargs):
        pass


def _load_worker_namespace(tmp_root, destination_root):
    source_path = Path(__file__).resolve().parents[1] / "nas_api_server.py"
    tree = ast.parse(source_path.read_text(encoding="utf-8"), filename=str(source_path))
    worker_node = next(
        node for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == "_social_worker"
    )
    module = ast.Module(body=[worker_node], type_ignores=[])
    ast.fix_missing_locations(module)
    namespace = social_extractor.__dict__
    def _mkdirp(path):
        os.makedirs(path, exist_ok=True)
        return True

    namespace.update({
        "_SOCIAL_TMP_ROOT": tmp_root,
        "WEBDAV_FILE_ROOT": destination_root,
        "_find_ytdlp_bin": lambda: "/usr/bin/yt-dlp",
        "_social_mkdirp": _mkdirp,
        "SOCIAL_YTDLP_TIMEOUT": 0.05,
        "log": _SilentLog(),
        "os": os,
        "shutil": shutil,
        "threading": threading,
    })
    exec(compile(module, str(source_path), "exec"), namespace)
    return namespace


class _SuccessfulDownloadProcess(object):
    returncode = 0
    last_command = None

    def __init__(self, command, **kwargs):
        _SuccessfulDownloadProcess.last_command = list(command)
        output_template = command[command.index("-o") + 1]
        output_dir = os.path.dirname(output_template)
        os.makedirs(output_dir, exist_ok=True)
        with open(os.path.join(output_dir, "video.mp4"), "wb") as output:
            output.write(b"video")
        self.stdout = self

    def readline(self):
        return b""

    def poll(self):
        return self.returncode

    def wait(self):
        return self.returncode


class _HangingDownloadProcess(object):
    instance = None

    def __init__(self, command, **kwargs):
        self.command = command
        self.kwargs = kwargs
        self.pid = 987654
        self.returncode = None
        self.stdout = self
        self.started_at = time.monotonic()
        self.signals = []
        _HangingDownloadProcess.instance = self

    def readline(self):
        time.sleep(0.01)
        return b""

    def poll(self):
        if self.returncode is None and time.monotonic() - self.started_at > 1.0:
            self.returncode = 0
        return self.returncode

    def wait(self, timeout=None):
        if self.returncode is None:
            raise subprocess.TimeoutExpired(self.command, timeout)
        return self.returncode

    def receive_group_signal(self, _pid, signum):
        self.signals.append(signum)
        self.returncode = -signum


class _ProgressDownloadProcess(object):
    emitted = threading.Event()
    release = threading.Event()

    def __init__(self, command, **kwargs):
        output_template = command[command.index("-o") + 1]
        output_dir = os.path.dirname(output_template)
        os.makedirs(output_dir, exist_ok=True)
        with open(os.path.join(output_dir, "video.mp4"), "wb") as output:
            output.write(b"video")
        self.returncode = None
        self.stdout = self
        self._sent_progress = False

    def readline(self):
        if not self._sent_progress:
            self._sent_progress = True
            self.emitted.set()
            return b"[download]  42.5% of 10.00MiB at 1.00MiB/s ETA 00:05\n"
        self.release.wait(1)
        return b""

    def poll(self):
        if self.release.is_set():
            self.returncode = 0
        return self.returncode

    def wait(self, timeout=None):
        return self.returncode


class TestSocialWorker(unittest.TestCase):
    def setUp(self):
        social_extractor._social_download_jobs.clear()
        self.validation_patcher = patch.object(
            social_extractor, "_social_validate_url", return_value=True
        )
        self.validation_patcher.start()
        _SuccessfulDownloadProcess.last_command = None
        _ProgressDownloadProcess.emitted.clear()
        _ProgressDownloadProcess.release.clear()

    def tearDown(self):
        self.validation_patcher.stop()
        social_extractor._social_download_jobs.clear()

    def _seed_job(self, job_id):
        social_extractor._social_download_jobs[job_id] = {
            "job_id": job_id,
            "status": "queued",
            "progress": 0,
            "finished_at": None,
        }

    def test_move_failure_marks_job_as_error(self):
        job_id = "movefail"
        self._seed_job(job_id)
        with tempfile.TemporaryDirectory() as tmp_root, tempfile.TemporaryDirectory() as destination:
            worker = _load_worker_namespace(tmp_root, destination)["_social_worker"]
            with patch("subprocess.Popen", _SuccessfulDownloadProcess), patch(
                "shutil.move", side_effect=OSError("disk write failed")
            ):
                worker(job_id, "https://www.youtube.com/watch?v=test", "Downloads/social")
            self.assertFalse(os.path.exists(os.path.join(tmp_root, job_id)))

        job = social_extractor._social_download_jobs[job_id]
        self.assertEqual(job["status"], "error")
        self.assertIn("disk write failed", job["error_reason"])

    def test_timeout_terminates_process_group_and_marks_error(self):
        job_id = "timeout"
        self._seed_job(job_id)
        with tempfile.TemporaryDirectory() as tmp_root, tempfile.TemporaryDirectory() as destination:
            worker = _load_worker_namespace(tmp_root, destination)["_social_worker"]
            with patch.object(social_extractor, "SOCIAL_YTDLP_TIMEOUT", 0.05), patch(
                "subprocess.Popen", _HangingDownloadProcess
            ), patch(
                "os.killpg",
                side_effect=lambda pid, signum: _HangingDownloadProcess.instance.receive_group_signal(pid, signum),
                create=True,
            ):
                worker_thread = threading.Thread(
                    target=worker,
                    args=(job_id, "https://www.youtube.com/watch?v=test", "Downloads/social"),
                )
                worker_thread.daemon = True
                worker_thread.start()
                worker_thread.join(0.5)

            self.assertFalse(worker_thread.is_alive())
            self.assertFalse(os.path.exists(os.path.join(tmp_root, job_id)))

        process = _HangingDownloadProcess.instance
        self.assertTrue(process.kwargs.get("start_new_session"))
        self.assertIn(signal.SIGTERM, process.signals)
        job = social_extractor._social_download_jobs[job_id]
        self.assertEqual(job["status"], "error")
        self.assertIn("timeout", job["error_reason"].lower())

    def test_progress_is_visible_while_download_is_running(self):
        job_id = "progress"
        self._seed_job(job_id)
        with tempfile.TemporaryDirectory() as tmp_root, tempfile.TemporaryDirectory() as destination:
            worker = _load_worker_namespace(tmp_root, destination)["_social_worker"]
            with patch("subprocess.Popen", _ProgressDownloadProcess):
                worker_thread = threading.Thread(
                    target=worker,
                    args=(job_id, "https://www.youtube.com/watch?v=test", "Downloads/social"),
                )
                worker_thread.start()
                self.assertTrue(_ProgressDownloadProcess.emitted.wait(0.5))
                time.sleep(0.1)
                observed_progress = social_extractor._social_download_jobs[job_id]["progress"]
                _ProgressDownloadProcess.release.set()
                worker_thread.join(1)

        self.assertFalse(worker_thread.is_alive())
        self.assertEqual(observed_progress, 42)
        self.assertEqual(social_extractor._social_download_jobs[job_id]["platform"], "youtube")

    def test_facebook_download_uses_cookies_and_uses_default_best_format(self):
        job_id = "facebook"
        self._seed_job(job_id)
        original_isfile = os.path.isfile

        def fake_isfile(path):
            return str(path).endswith("cookies.txt") or original_isfile(path)

        with tempfile.TemporaryDirectory() as tmp_root, tempfile.TemporaryDirectory() as destination:
            worker = _load_worker_namespace(tmp_root, destination)["_social_worker"]
            with patch("subprocess.Popen", _SuccessfulDownloadProcess), patch(
                "os.path.exists", side_effect=fake_isfile
            ), patch(
                "os.access", return_value=True
            ):
                worker(job_id, "https://www.facebook.com/reel/123", "Downloads/social")

        command = _SuccessfulDownloadProcess.last_command
        self.assertIn("--cookies", command)
        self.assertNotIn("136/135/134/bestvideo", command)

    def test_worker_revalidates_url_before_spawning_ytdlp(self):
        job_id = "dns_rebind"
        self._seed_job(job_id)
        with tempfile.TemporaryDirectory() as tmp_root, tempfile.TemporaryDirectory() as destination:
            worker = _load_worker_namespace(tmp_root, destination)["_social_worker"]
            with patch.object(
                social_extractor, "_social_validate_url", return_value=False
            ), patch("subprocess.Popen", _SuccessfulDownloadProcess):
                worker(job_id, "https://rebind.example.test/video", "Downloads/social")

        self.assertIsNone(_SuccessfulDownloadProcess.last_command)
        job = social_extractor._social_download_jobs[job_id]
        self.assertEqual(job["status"], "error")
        self.assertIn("URL", job["error_reason"])

    def test_reader_start_failure_terminates_and_reaps_process(self):
        job_id = "reader_start_failure"
        self._seed_job(job_id)

        class _BrokenReaderThread(object):
            daemon = False

            def __init__(self, *args, **kwargs):
                pass

            def start(self):
                raise RuntimeError("reader thread failed")

        with tempfile.TemporaryDirectory() as tmp_root, tempfile.TemporaryDirectory() as destination:
            worker = _load_worker_namespace(tmp_root, destination)["_social_worker"]
            with patch("subprocess.Popen", _HangingDownloadProcess), patch(
                "threading.Thread", _BrokenReaderThread
            ), patch(
                "os.killpg",
                side_effect=lambda pid, signum: _HangingDownloadProcess.instance.receive_group_signal(pid, signum),
                create=True,
            ):
                worker(job_id, "https://www.youtube.com/watch?v=test", "Downloads/social")

        process = _HangingDownloadProcess.instance
        self.assertIsNotNone(process)
        self.assertIsNotNone(process.returncode, "child process was left running")
        self.assertIn(signal.SIGTERM, process.signals)
        self.assertEqual(
            social_extractor._social_download_jobs[job_id]["status"], "error"
        )


if __name__ == "__main__":
    unittest.main()
