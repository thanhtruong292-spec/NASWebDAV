# -*- coding: utf-8 -*-
"""Endpoint-contract tests using the real route function bodies."""

import ast
from pathlib import Path
import threading
import time
import types
import unittest


SOURCE_PATH = Path(__file__).resolve().parents[1] / "nas_api_server.py"


def _load_route(name, namespace):
    source = SOURCE_PATH.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(SOURCE_PATH))
    node = next(
        item for item in tree.body
        if isinstance(item, ast.FunctionDef) and item.name == name
    )
    node.decorator_list = []
    module = ast.Module(body=[node], type_ignores=[])
    ast.fix_missing_locations(module)
    exec(compile(module, str(SOURCE_PATH), "exec"), namespace)
    return namespace[name]


class _Request(object):
    def __init__(self, data):
        self.data = data

    def get_json(self, **_kwargs):
        return dict(self.data)


class _Log(object):
    def error(self, *_args, **_kwargs):
        pass


class _StartedThread(object):
    started = False

    def __init__(self, **_kwargs):
        pass

    def start(self):
        type(self).started = True


class _ImmediateThread(object):
    def __init__(self, target, args, **_kwargs):
        self.target = target
        self.args = args

    def start(self):
        self.target(*self.args)


def _route_namespace(**overrides):
    namespace = {
        "jsonify": lambda value: value,
        "log": _Log(),
        "request": _Request({}),
        "threading": types.SimpleNamespace(Thread=_StartedThread),
        "time": time,
        "_social_worker": lambda *_args: None,
        "_social_create_job": lambda _url, _folder: ("job-1", None),
        "_social_get_job": lambda _job_id: {
            "job_id": "job-1",
            "folder": "Downloads/social",
            "status": "completed",
            "progress": 100,
            "filename": "video.mp4",
            "size": 5,
            "platform": "youtube",
            "error_reason": None,
            "started_at": 1,
            "finished_at": 2,
        },
        "_social_update_job": lambda _job_id, **_changes: True,
    }
    namespace.update(overrides)
    return namespace


class TestSocialRouteContracts(unittest.TestCase):
    def setUp(self):
        _StartedThread.started = False

    def test_valid_post_returns_202_and_starts_worker(self):
        namespace = _route_namespace(
            request=_Request({"url": "https://www.youtube.com/watch?v=abc"})
        )
        route = _load_route("api_social_download", namespace)

        body, status = route()

        self.assertEqual(status, 202)
        self.assertEqual(body["job_id"], "job-1")
        self.assertTrue(_StartedThread.started)

    def test_concurrency_rejection_returns_429(self):
        namespace = _route_namespace(
            request=_Request({"url": "https://www.youtube.com/watch?v=abc"}),
            _social_create_job=lambda _url, _folder: (
                None,
                "Max concurrent downloads reached (2).",
            ),
        )
        route = _load_route("api_social_download", namespace)

        body, status = route()

        self.assertEqual(status, 429)
        self.assertIn("Max concurrent", body["error"])

    def test_status_returns_terminal_job(self):
        namespace = _route_namespace()
        route = _load_route("api_social_status", namespace)

        body, status = route("job-1")

        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "completed")
        self.assertEqual(body["progress"], 100)

    def test_valid_post_can_be_polled_to_terminal_state(self):
        jobs = {
            "job-1": {
                "job_id": "job-1",
                "folder": "Downloads/social",
                "status": "queued",
                "progress": 0,
                "filename": None,
                "size": 0,
                "platform": "youtube",
                "error_reason": None,
                "started_at": 1,
                "finished_at": None,
            }
        }

        def complete_job(job_id, _url, _folder):
            jobs[job_id].update({
                "status": "completed",
                "progress": 100,
                "filename": "video.mp4",
                "size": 5,
                "finished_at": 2,
            })

        namespace = _route_namespace(
            request=_Request({"url": "https://www.youtube.com/watch?v=abc"}),
            threading=types.SimpleNamespace(Thread=_ImmediateThread),
            _social_worker=complete_job,
            _social_get_job=lambda job_id: dict(jobs[job_id]),
        )
        download_route = _load_route("api_social_download", namespace)
        status_route = _load_route("api_social_status", namespace)

        post_body, post_status = download_route()
        poll_body, poll_status = status_route(post_body["job_id"])

        self.assertEqual(post_status, 202)
        self.assertEqual(poll_status, 200)
        self.assertEqual(poll_body["status"], "completed")

    def test_thread_start_failure_marks_job_error(self):
        updates = []

        class _FailingThread(_StartedThread):
            def start(self):
                raise RuntimeError("cannot start worker")

        namespace = _route_namespace(
            request=_Request({"url": "https://www.youtube.com/watch?v=abc"}),
            threading=types.SimpleNamespace(Thread=_FailingThread),
            _social_update_job=lambda job_id, **changes: updates.append(
                (job_id, changes)
            ),
        )
        route = _load_route("api_social_download", namespace)

        _body, status = route()

        self.assertEqual(status, 500)
        self.assertEqual(len(updates), 1, "queued job was not transitioned to error")
        self.assertEqual(updates[0][0], "job-1")
        self.assertEqual(updates[0][1]["status"], "error")


class TestSocialRuntimeWiring(unittest.TestCase):
    def test_cron_worker_periodically_expires_social_jobs(self):
        source = SOURCE_PATH.read_text(encoding="utf-8")
        tree = ast.parse(source, filename=str(SOURCE_PATH))
        cron = next(
            item for item in tree.body
            if isinstance(item, ast.FunctionDef) and item.name == "_cron_worker"
        )
        called_names = {
            node.func.id
            for node in ast.walk(cron)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
        }
        self.assertIn("_social_remove_expired_jobs", called_names)


if __name__ == "__main__":
    unittest.main()
