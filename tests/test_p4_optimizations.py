# -*- coding: utf-8 -*-
"""
Focused tests for P4 optimizations (ANTIGRAVITY-021):
- P4.1: Rust dir_usage_rust (walkdir zero-GIL, excludes .nas_meta/.thumbnails/@eaDir, early stop)
- P4.2: Rust scan_broken_mp4_candidates_rust (walkdir + rayon + mp4_parser, skips _broken_quarantine)
"""
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
import nas_api_server
import rust_bridge


def test_p4_dir_usage_rust_and_safe_dir_usage():
    with tempfile.TemporaryDirectory() as tmp_dir:
        os.makedirs(os.path.join(tmp_dir, ".thumbnails"))
        os.makedirs(os.path.join(tmp_dir, ".nas_meta"))
        os.makedirs(os.path.join(tmp_dir, "photos"))
        with open(os.path.join(tmp_dir, "a.bin"), "wb") as f:
            f.write(b"1" * 120)
        with open(os.path.join(tmp_dir, "photos", "b.bin"), "wb") as f:
            f.write(b"2" * 80)
        with open(os.path.join(tmp_dir, ".thumbnails", "skip.jpg"), "wb") as f:
            f.write(b"x" * 500)

        total, count, partial = nas_api_server._safe_dir_usage(tmp_dir, max_files=100, max_seconds=5.0)
        assert total == 200
        assert count == 2
        assert partial is False

        # Test early stop max_files=1
        total_p, count_p, partial_p = nas_api_server._safe_dir_usage(tmp_dir, max_files=1, max_seconds=5.0)
        assert partial_p is True
        assert count_p == 1
        assert total_p in (80, 120)


def test_p4_scan_broken_mp4_candidates_rust():
    with tempfile.TemporaryDirectory() as tmp_dir:
        os.makedirs(os.path.join(tmp_dir, "_broken_quarantine_20261120"))
        os.makedirs(os.path.join(tmp_dir, "clips"))
        bad_mp4 = os.path.join(tmp_dir, "clips", "broken.mp4")
        with open(bad_mp4, "wb") as f:
            f.write(b"corrupted mp4 without moov")
        with open(os.path.join(tmp_dir, "_broken_quarantine_20261120", "old.mp4"), "wb") as f:
            f.write(b"already quarantined")

        res = rust_bridge.scan_broken_mp4_candidates_rust(tmp_dir, workers=2)
        assert res is not None
        assert res["total_scanned"] == 1
        assert len(res["suspect_files"]) == 1
        assert res["suspect_files"][0].endswith("broken.mp4")
