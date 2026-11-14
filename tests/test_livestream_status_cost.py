"""Exercise the real status handler without starting NAS background workers."""

import ast
import os
import threading
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock


SOURCE = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"


def status_handler(jobs, names):
    node = next(n for n in ast.parse(SOURCE.read_text(encoding="utf-8")).body
                if isinstance(n, ast.FunctionDef) and n.name == "api_livestream_status")
    node.decorator_list = []
    filesystem = SimpleNamespace(
        path=SimpleNamespace(
            join=os.path.join, basename=os.path.basename,
            isdir=Mock(return_value=True), isfile=Mock(return_value=True),
            getmtime=Mock(return_value=10), getsize=Mock(return_value=4096)),
        listdir=Mock(return_value=names), kill=Mock())
    namespace = {
        "os": filesystem, "time": SimpleNamespace(time=lambda: 1000),
        "_livestream_jobs": jobs, "_livestream_lock": threading.Lock(),
        "_LIVESTREAM_DIR": "/recordings", "jsonify": lambda data: data,
        "format_bytes": str, "_tiktok_watch_state": {},
        "_tiktok_watch_runtime": {},
    }
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), namespace)
    return namespace["api_livestream_status"], filesystem


def test_finished_history_uses_recorded_metadata_without_rescanning_disk():
    jobs = {str(i): {"status": "finished", "output_file": "old_%s.mp4" % i,
                    "file_size": 512, "started_ts": 10}
            for i in range(50)}
    handler, filesystem = status_handler(jobs, ["unrelated.mp4"] * 2000)

    result = handler()

    filesystem.listdir.assert_not_called()
    filesystem.path.isfile.assert_not_called()
    assert len(result["jobs"]) == 50
    assert all(j["file_size_bytes"] == 512 for j in result["jobs"])


def test_active_jobs_share_directory_listing_and_stat_only_their_own_files():
    jobs = {name: {"status": "recording", "pid": 1, "file_stem": name,
                   "logged_start": True, "started_ts": 10, "last_size_time": 999}
            for name in ("alice", "bob")}
    handler, filesystem = status_handler(
        jobs, ["unrelated.mp4"] * 2000 + ["alice.mp4", "bob.mp4", "alice.log"])

    result = handler()

    assert filesystem.listdir.call_count == 1
    assert filesystem.path.isfile.call_count == 2
    assert {j["output_file"] for j in result["jobs"]} == {"alice.mp4", "bob.mp4"}
    assert all(j["file_size_bytes"] == 4096 for j in result["jobs"])
    assert all(info["status"] == "recording" for info in jobs.values())


def test_job_without_file_identity_cannot_claim_another_recording():
    jobs = {"missing": {"status": "recording", "pid": 1, "logged_start": True}}
    handler, filesystem = status_handler(jobs, ["someone_else.mp4"])

    result = handler()

    assert result["jobs"][0]["output_file"] == ""
    assert result["jobs"][0]["file_size_bytes"] == 0
    filesystem.listdir.assert_not_called()


def test_known_active_output_does_not_walk_the_directory():
    jobs = {"live": {"status": "recording", "pid": 1, "logged_start": True,
                     "direct_output_path": "/recordings/live.flv"}}
    handler, filesystem = status_handler(jobs, ["unrelated.mp4"] * 2000)

    result = handler()

    filesystem.listdir.assert_not_called()
    assert result["jobs"][0]["output_file"] == "live.flv"
    assert result["jobs"][0]["file_size_bytes"] == 4096


def test_watchdog_finalization_filters_job_identity_before_disk_metadata():
    tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
    watchdog = next(n for n in tree.body if isinstance(n, ast.FunctionDef)
                    and n.name == "_livestream_watchdog")
    candidates = next(n for n in ast.walk(watchdog) if isinstance(n, ast.ListComp)
                      and {"file_stem", "out_pattern"}.issubset(
                          {v.id for v in ast.walk(n) if isinstance(v, ast.Name)}))
    checks = Mock(return_value=True)
    filesystem = SimpleNamespace(
        listdir=lambda p: ["other.mp4"] * 2000 + ["alice.mp4"],
        path=SimpleNamespace(join=os.path.join, isfile=checks))

    result = eval(compile(ast.Expression(candidates), str(SOURCE), "eval"),
                  {"os": filesystem, "out_pattern": "/recordings", "file_stem": "alice"})

    assert result == [os.path.join("/recordings", "alice.mp4")]
    assert checks.call_count == 1
