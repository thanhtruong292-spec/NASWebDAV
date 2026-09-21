# -*- coding: utf-8 -*-
"""Regression tests for backend fixes in nas_api_server.py."""

import unittest
from unittest.mock import patch, MagicMock
from pathlib import Path
import ast

class TestBackendCodeQuality(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server_path = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"
        cls.source = server_path.read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.source)

    def test_no_unassigned_wrapper_dir_in_livestream_record(self):
        """Verify _wrapper_dir is initialized unconditionally in api_livestream_record."""
        func = next(
            node for node in self.tree.body
            if isinstance(node, ast.FunctionDef) and node.name == "api_livestream_record"
        )
        # Check that _wrapper_dir = "" exists before any if direct_tiktok_flv
        has_wrapper_dir_default = False
        for stmt in func.body:
            if isinstance(stmt, ast.Assign):
                for t in stmt.targets:
                    if isinstance(t, ast.Name) and t.id == "_wrapper_dir":
                        has_wrapper_dir_default = True
            if isinstance(stmt, ast.If):
                test = stmt.test
                if isinstance(test, ast.Name) and test.id == "direct_tiktok_flv":
                    self.assertTrue(has_wrapper_dir_default, "_wrapper_dir must be initialized before if direct_tiktok_flv")
                    break

    def test_ytdlp_bin_resolved_in_api_ytdlp_download(self):
        """Verify api_ytdlp_download resolves ytdlp_bin before building cmd."""
        func = next(
            node for node in self.tree.body
            if isinstance(node, ast.FunctionDef) and node.name == "api_ytdlp_download"
        )
        # Check that _find_ytdlp_bin is called in func
        calls = [
            node.func.id for node in ast.walk(func)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
        ]
        self.assertIn("_find_ytdlp_bin", calls)

    def test_proc_initialized_before_try_in_social_worker(self):
        """Verify proc is initialized before try block in _social_worker."""
        func = next(
            node for node in self.tree.body
            if isinstance(node, ast.FunctionDef) and node.name == "_social_worker"
        )
        # Check that proc = None is set before try block
        assigned_before_try = set()
        for stmt in func.body:
            if isinstance(stmt, ast.Assign):
                for t in stmt.targets:
                    if isinstance(t, ast.Name):
                        assigned_before_try.add(t.id)
            elif isinstance(stmt, ast.Try):
                break
        self.assertIn("proc", assigned_before_try)

    def test_no_dead_code_in_screen_record_finish(self):
        """Verify no 'if False:' dead code in api_screen_record_finish."""
        func = next(
            node for node in self.tree.body
            if isinstance(node, ast.FunctionDef) and node.name == "api_screen_record_finish"
        )
        # Should only have a return statement at the end of body
        for stmt in func.body:
            if isinstance(stmt, ast.If) and isinstance(stmt.test, ast.Constant) and stmt.test.value is False:
                self.fail("Dead code 'if False:' should not be present in api_screen_record_finish")


if __name__ == "__main__":
    unittest.main()
