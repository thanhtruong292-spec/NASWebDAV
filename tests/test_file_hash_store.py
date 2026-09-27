# -*- coding: utf-8 -*-
"""Tests cho kho file_hashes + endpoint /api/hash/* (AST + logic mo phong)."""

import ast
import unittest
from pathlib import Path


def _source():
    p = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"
    return p.read_text(encoding="utf-8")


class HashStoreTests(unittest.TestCase):
    def test_routes_registered(self):
        src = _source()
        for route in ("/api/hash/save", "/api/hash/duplicates", "/api/hash/lookup"):
            self.assertIn(route, src)
        for fn in ("def api_hash_save", "def api_hash_duplicates",
                   "def api_hash_lookup", "def _hash_db"):
            self.assertIn(fn, src)

    def test_schema_has_index(self):
        src = _source()
        self.assertIn("CREATE TABLE IF NOT EXISTS file_hashes", src)
        self.assertIn("idx_hashes_sha", src)

    def test_save_validates_sha(self):
        """api_hash_save chi nhan sha256 64 ky tu hex."""
        src = _source()
        self.assertIn("len(s) != 64", src)
        self.assertIn("INSERT OR REPLACE INTO file_hashes", src)

    def test_duplicates_groups_by_sha(self):
        """api_hash_duplicates nhom theo sha256, HAVING COUNT > 1."""
        src = _source()
        self.assertIn("GROUP BY sha256 HAVING COUNT(*) > 1", src)
        self.assertIn("wasted_bytes", src)

    def test_dedup_logic_simulation(self):
        """Mo phong nhom trung bang sqlite3 that."""
        import sqlite3
        conn = sqlite3.connect(":memory:")
        conn.execute("""CREATE TABLE file_hashes (
            path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER,
            sha256 TEXT, partial TEXT, scanned_at INTEGER)""")
        rows = [
            ("/webdav/a.jpg", 100, 1, "aa" * 32, "", 1),
            ("/webdav/b.jpg", 100, 2, "aa" * 32, "", 1),
            ("/webdav/c.jpg", 50, 3, "bb" * 32, "", 1),
        ]
        conn.executemany("INSERT INTO file_hashes VALUES (?,?,?,?,?,?)", rows)
        dups = conn.execute(
            "SELECT sha256, COUNT(*) FROM file_hashes "
            "WHERE sha256 != '' GROUP BY sha256 HAVING COUNT(*) > 1").fetchall()
        self.assertEqual(len(dups), 1)
        self.assertEqual(dups[0][0], "aa" * 32)
        self.assertEqual(dups[0][1], 2)
        conn.close()

    def test_verify_helpers_exist(self):
        """_verify_hash_row + _refresh_stale_rows phai ton tai (lop 4)."""
        src = _source()
        self.assertIn("def _verify_hash_row", src)
        self.assertIn("def _refresh_stale_rows", src)
        self.assertIn('"etags"', src)
        self.assertIn("stale_refreshed", src)

    def test_stale_logic_simulation(self):
        """Mo phong: size/mtime doi -> stale, khong bao trung."""
        saved = ("/webdav/a.jpg", 100, 1000)
        current = ("/webdav/a.jpg", 120, 1000)  # size doi
        stale = (saved[1] != current[1]) or (saved[2] != current[2])
        self.assertTrue(stale)
        same = (saved[1] == 100) and (saved[2] == 1000)
        self.assertTrue(same)


if __name__ == "__main__":
    unittest.main()
