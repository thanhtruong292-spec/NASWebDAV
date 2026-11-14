# -*- coding: utf-8 -*-
"""Regression tests for preserving dashboard metrics after a cache refresh error."""

import ast
import unittest
from pathlib import Path


SERVER_PATH = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"
SERVER_TREE = ast.parse(SERVER_PATH.read_text(encoding="utf-8"))


def _function(name):
    return next(
        node for node in SERVER_TREE.body
        if isinstance(node, ast.FunctionDef) and node.name == name
    )


def _load_function(name):
    node = _function(name)
    module = ast.Module(body=[node], type_ignores=[])
    namespace = {}
    exec(compile(module, str(SERVER_PATH), "exec"), namespace)
    return namespace[name]


class TestStatusCacheRecovery(unittest.TestCase):
    def test_cache_error_preserves_last_good_metrics_and_marks_stale(self):
        previous = {
            "cpu": "12.0%",
            "ram": "431 MB / 978 MB",
            "disk": "74.1%|2.65 TB / 3.58 TB",
            "disk_parts": [{"mount": "/srv/data", "percent": 74.1}],
            "status": "Online",
        }

        result = _load_function("_status_cache_after_error")(previous, "metrics failed")

        self.assertEqual(result["cpu"], previous["cpu"])
        self.assertEqual(result["ram"], previous["ram"])
        self.assertEqual(result["disk"], previous["disk"])
        self.assertEqual(result["disk_parts"], previous["disk_parts"])
        self.assertEqual(result["status"], "Lỗi: metrics failed")

    def test_refresh_error_uses_preserving_cache_helper(self):
        refresh = _function("_update_status_cache")
        error_handlers = [
            handler
            for node in ast.walk(refresh)
            if isinstance(node, ast.Try)
            for handler in node.handlers
            if isinstance(handler.type, ast.Name) and handler.type.id == "Exception"
        ]
        self.assertTrue(error_handlers)
        self.assertTrue(
            any(
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id == "_status_cache_after_error"
                for handler in error_handlers
                for node in ast.walk(handler)
            ),
            "status refresh exceptions must preserve the last good snapshot",
        )


if __name__ == "__main__":
    unittest.main()
