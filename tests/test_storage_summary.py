# -*- coding: utf-8 -*-
"""Tests cho _build_storage_summary (endpoint /api/storage/summary)."""

import ast
import os
import tempfile
import types
import unittest
from pathlib import Path


def _load_summary_fn():
    source_path = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"
    source = source_path.read_text(encoding="utf-8")
    tree = ast.parse(source)
    wanted = {"_build_storage_summary"}
    mod = types.ModuleType("_storage_summary_logic")
    mod.__dict__.update({"os": os, "time": __import__("time"),
                         "threading": __import__("threading"),
                         "log": __import__("logging").getLogger("test")})
    # Can get_webdav_root mac dinh (se duoc patch trong test) + hang skip dirs.
    fns = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in wanted]
    assert fns, "_build_storage_summary khong ton tai"
    assigns = [n for n in tree.body
               if isinstance(n, ast.Assign)
               and any(isinstance(t, ast.Name) and t.id == "_STORAGE_SKIP_DIRS"
                       for t in n.targets)]
    m = ast.Module(body=assigns + fns, type_ignores=[])
    ast.fix_missing_locations(m)
    exec(compile(m, str(source_path), "exec"), mod.__dict__)
    return mod


class StorageSummaryTests(unittest.TestCase):
    def _make_tree(self, root):
        (root / "a.jpg").write_bytes(b"x" * 100)
        (root / "b.mp4").write_bytes(b"y" * 1000)
        (root / "c.pdf").write_bytes(b"z" * 50)
        sub = root / "sub"
        sub.mkdir()
        (sub / "d.mp3").write_bytes(b"w" * 200)
        (sub / "e.zip").write_bytes(b"v" * 300)
        (sub / "f.xyz").write_bytes(b"u" * 10)
        trash = root / ".trash"
        trash.mkdir()
        (trash / "old.mp4").write_bytes(b"t" * 5000)

    def test_counts_and_largest(self):
        mod = _load_summary_fn()
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            self._make_tree(root)
            mod.__dict__["get_webdav_root"] = lambda: str(root)
            out = mod._build_storage_summary(max_seconds=30)
        self.assertEqual(out["total_files"], 6)  # .trash bi loai
        self.assertEqual(out["total_bytes"], 100 + 1000 + 50 + 200 + 300 + 10)
        self.assertEqual(out["by_type"]["image"]["files"], 1)
        self.assertEqual(out["by_type"]["video"]["files"], 1)
        self.assertEqual(out["by_type"]["video"]["bytes"], 1000)
        self.assertEqual(out["by_type"]["doc"]["files"], 1)
        self.assertEqual(out["by_type"]["audio"]["files"], 1)
        self.assertEqual(out["by_type"]["archive"]["files"], 1)
        self.assertEqual(out["by_type"]["other"]["files"], 1)
        # File lon nhat la b.mp4 (1000B), khong phai old.mp4 trong trash.
        self.assertEqual(out["largest"][0]["bytes"], 1000)
        self.assertTrue(out["largest"][0]["path"].endswith("b.mp4"))
        self.assertFalse(out["partial"])

    def test_route_registered(self):
        source_path = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"
        source = source_path.read_text(encoding="utf-8")
        self.assertIn("/api/storage/summary", source)
        self.assertIn("def api_storage_summary", source)


if __name__ == "__main__":
    unittest.main()
