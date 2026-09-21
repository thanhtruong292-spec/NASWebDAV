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

    def test_ytdlp_bin_used_in_api_ytdlp_download(self):
        """Verify api_ytdlp_download references ytdlp_bin when building the cmd.

        NOTE: NAS build does NOT call _find_ytdlp_bin() inside this handler
        (unlike the old committed local build); it relies on a module-level
        ytdlp_bin. We assert the variable is referenced, not a specific call.
        """
        func = next(
            node for node in self.tree.body
            if isinstance(node, ast.FunctionDef) and node.name == "api_ytdlp_download"
        )
        names = [
            node.id for node in ast.walk(func)
            if isinstance(node, ast.Name) and node.id == "ytdlp_bin"
        ]
        self.assertIn("ytdlp_bin", names)

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

    def test_social_worker_uses_versioned_or_legacy_route(self):
        """Smoke check: server defines the social download handler.

        The NAS build added /api/v1 dual-serve routing; this just confirms
        the handler symbol still exists so refactors don't drop it silently.
        """
        self.assertTrue(
            any(
                isinstance(node, ast.FunctionDef) and node.name == "api_social_download"
                for node in self.tree.body
            )
        )


if __name__ == "__main__":
    unittest.main()
