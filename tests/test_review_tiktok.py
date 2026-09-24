"""Behavior regressions through production functions and emitted reconnect code.

No server import (which starts services), copied algorithms or extractor stubs.
Run: python -m unittest discover -s tests -p test_review_tiktok.py -v
"""
import ast
import json
import logging
import os
from pathlib import Path
import re
import types
import unittest


SOURCE = (Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py").read_text(encoding="utf-8")
TREE = ast.parse(SOURCE)


def production_namespace():
    nodes = [n for n in TREE.body if isinstance(n, ast.FunctionDef)
             and ("tiktok" in n.name or n.name in ("_dig_tiktok_room", "_eval_tiktok_room", "_eval_tiktok_status"))]
    ns = {"json": json, "re": re, "_re_module": re, "os": os}
    for node in nodes:
        node = ast.FunctionDef(**dict(node.__dict__, decorator_list=[]))
        exec(compile(ast.Module(body=[node], type_ignores=[]), "<production>", "exec"), ns)
    return ns


def page(room, extra=None):
    return "<script>window.SIGI_STATE=" + json.dumps({"LiveRoom": {"liveRoom": room}, "extra": extra or {}}) + ";</script>"


def reconnect_namespace(html):
    ns = production_namespace()
    if "_build_tiktok_reconnect_script" in ns:
        script = ns["_build_tiktok_reconnect_script"]()
    else:
        assignments = [n for n in ast.walk(TREE) if isinstance(n, ast.Assign)
                       and any(isinstance(t, ast.Name) and t.id == "loop_script" for t in n.targets)]
        script = ast.literal_eval(assignments[0].value)
    generated = ast.parse(script)
    calls = []

    def check_output(cmd, **kwargs):
        calls.append(cmd)
        return b"200" if "-w" in cmd else html.encode("utf-8")

    # A child process has only what the generated script defines, not the
    # parent's production functions; do not accidentally supply missing helpers.
    ns = {"json": json, "re": re, "_re_module": re, "os": os}
    ns.update(username="alice", initial_url="", tried_urls=set(), cookies="", ua="test",
              subprocess=types.SimpleNamespace(check_output=check_output), log=logging.getLogger("test"))
    for node in generated.body:
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            # Real imports would replace the explicit subprocess fake.
            continue
        if isinstance(node, ast.FunctionDef):
            exec(compile(ast.Module(body=[node], type_ignores=[]), "<generated-reconnect>", "exec"), ns)
    return ns, calls


class RoomMediaRegression(unittest.TestCase):
    def setUp(self):
        self.ns = production_namespace()
        self.extract = self.ns["_extract_tiktok_live_media_urls_scoped"]

    def test_preceding_closed_recommendation_is_not_target_object(self):
        html = '{"LiveRoom":{"recommendation":{"url":"https://cdn/other.flv"},"roomId":"7001"}}'
        self.assertEqual([], self.extract(html, "7001"))

    def test_missing_room_identity_never_selects_recommendation(self):
        self.assertEqual([], self.extract(page(None, {"url": "https://cdn/other.flv"}), ""))

    def test_exact_room_id_not_substring_of_other_room(self):
        html = page({"roomId": "7001", "liveRoomStatus": 1},
                    {"roomId": "170019", "url": "https://cdn/other.flv"})
        self.assertEqual([], self.extract(html, "7001"))

    def test_target_media_preserved_and_other_room_excluded(self):
        html = page({"roomId": "7001", "liveRoomStatus": 1, "url": "https://cdn/target.flv"},
                    {"roomId": "9999", "url": "https://cdn/other.flv"})
        self.assertEqual(["https://cdn/target.flv"], self.extract(html, "7001"))

    def test_null_assignment_cannot_parse_next_script(self):
        html = 'window.SIGI_STATE=null;</script><script>{"LiveRoom":{"liveRoomStatus":1,"roomId":"9999"}}</script>'
        self.assertFalse(self.ns["_parse_tiktok_live_room_state"](html)[0])


class GeneratedReconnectRegression(unittest.TestCase):
    def test_unrelated_offline_room_does_not_stop_target(self):
        html = page({"roomId": "7001", "liveRoomStatus": 1, "uniqueId": "alice", "url": "https://cdn/target.flv"},
                    {"roomId": "9999", "liveRoomStatus": 4})
        ns, _ = reconnect_namespace(html)
        self.assertEqual("https://cdn/target.flv", ns["get_media_url"]())

    def test_target_without_media_does_not_reconnect_to_other_room(self):
        html = page({"roomId": "7001", "liveRoomStatus": 1, "uniqueId": "alice"},
                    {"roomId": "9999", "url": "https://cdn/other.flv"})
        ns, _ = reconnect_namespace(html)
        self.assertEqual("", ns["get_media_url"]())

    def test_missing_identity_does_not_reconnect_to_other_room(self):
        ns, _ = reconnect_namespace(page(None, {"url": "https://cdn/other.flv"}))
        self.assertEqual("", ns["get_media_url"]())


if __name__ == "__main__":
    unittest.main()
