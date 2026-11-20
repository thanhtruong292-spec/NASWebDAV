# -*- coding: utf-8 -*-
"""
Focused tests for P1 & P2 optimizations (ANTIGRAVITY-019):
- P1.1: 128KB streaming chunk, _ytdlp_jobs reaping, Rust MP4 fast-path in _livestream_video_is_playable
- P1.2: Batch duplicate verification (no N+1 SQLite connections)
- P1.3: Rust smart_organize_scan_rust (zero-copy rusqlite)
- P2.1: Rust merge_ts_segments_rust
- P2.2: Rust find_similar_fingerprints_rust (Hamming popcount)
- P2.3: Rust fast_copy_file_rust (copy + SHA-256 + cancel callback)
"""
import inspect
import os
import sqlite3
import sys
import tempfile
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
import nas_api_server
import rust_bridge


def test_p1_iter_file_range_chunk_size_128kb():
    sig = inspect.signature(nas_api_server._iter_file_range)
    assert sig.parameters["chunk_size"].default == 128 * 1024


def test_p1_reap_ytdlp_jobs_clears_finished_and_unblocks_thumbnail(monkeypatch):
    class _DoneProc(object):
        pid = 999999

        def poll(self):
            return 0

        def wait(self, timeout=0):
            return 0

    blocked_states = []
    monkeypatch.setattr(nas_api_server, "_set_thumbnail_auto_block", lambda k, v: blocked_states.append((k, v)))
    monkeypatch.setattr(nas_api_server, "_cleanup_job_tmp", lambda d: None)
    monkeypatch.setattr(nas_api_server, "_cleanup_runtime_tmp_artifacts", lambda max_age_minutes=30: None)
    monkeypatch.setattr(nas_api_server, "_add_system_log_once", lambda *a, **kw: None)

    with nas_api_server._ytdlp_lock:
        nas_api_server._ytdlp_jobs.clear()
        nas_api_server._ytdlp_jobs["done_1"] = {
            "pid": 999999,
            "_proc": _DoneProc(),
            "url": "https://example.com/v",
            "folder": "/tmp",
            "log_file": "",
            "tmp_dir": "",
        }
        active = nas_api_server._reap_ytdlp_jobs_locked()
        assert active == {}
        assert "done_1" not in nas_api_server._ytdlp_jobs
    assert ("ytdlp", False) in blocked_states


def test_p1_livestream_playable_uses_rust_mp4_parser_no_ffprobe(monkeypatch):
    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as f:
        f.write(b"\x00" * 2048)
        mp4_path = f.name
    try:
        called_ffprobe = [False]

        def _fail_run(*args, **kwargs):
            called_ffprobe[0] = True
            raise AssertionError("ffprobe should not be called when Rust MP4 parser succeeds")

        monkeypatch.setattr(nas_api_server.subprocess, "run", _fail_run)
        monkeypatch.setattr(
            rust_bridge,
            "parse_mp4_header",
            lambda p: {"valid": True, "has_moov": True, "duration_secs": 12.5, "video_codec": "avc1"},
        )
        assert nas_api_server._livestream_video_is_playable(mp4_path) is True
        assert called_ffprobe[0] is False
    finally:
        if os.path.exists(mp4_path):
            os.remove(mp4_path)


def test_p1_hash_duplicates_batch_no_n_plus_1(monkeypatch):
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "hash.db")
        f1 = os.path.join(tmp_dir, "dup1.bin")
        f2 = os.path.join(tmp_dir, "dup2.bin")
        with open(f1, "wb") as fp:
            fp.write(b"A" * 2048)
        with open(f2, "wb") as fp:
            fp.write(b"A" * 2048)
        st1 = os.stat(f1)
        st2 = os.stat(f2)

        conn = sqlite3.connect(db_path)
        conn.execute(
            "CREATE TABLE file_hashes (path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER, mtime INTEGER)"
        )
        conn.execute("INSERT INTO file_hashes VALUES (?, ?, ?, ?)", ("/webdav/dup1.bin", "deadbeef" * 8, st1.st_size, int(st1.st_mtime)))
        conn.execute("INSERT INTO file_hashes VALUES (?, ?, ?, ?)", ("/webdav/dup2.bin", "deadbeef" * 8, st2.st_size, int(st2.st_mtime)))
        conn.commit()
        conn.close()

        open_count = [0]

        def _counted_hash_db():
            open_count[0] += 1
            return sqlite3.connect(db_path)

        monkeypatch.setattr(nas_api_server, "_hash_db", _counted_hash_db)
        monkeypatch.setattr(nas_api_server, "_resolve_webdav_request_path", lambda p: os.path.join(tmp_dir, os.path.basename(p)))
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: True)

        client = nas_api_server.app.test_client()
        resp = client.get("/api/hash/duplicates?min_size=1024")
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["total_groups"] == 1
        assert len(data["groups"][0]["files"]) == 2
        # Chi mo ket noi DB dung 1 lan duy nhat (khong N+1 cho tung file)
        assert open_count[0] == 1


def test_p1_smart_organize_scan_rust():
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "idx.db")
        now_secs = 1800000010.0
        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, catalogue_revision INTEGER, ready INTEGER, scanned_at REAL);
            CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, size INTEGER, mtime INTEGER, is_dir INTEGER);
            INSERT INTO index_state VALUES (1, '/srv/data', 1, 1, 1, 1800000000.0);
            INSERT INTO file_index VALUES ('DCIM/p1.jpg', 'p1.jpg', 1000, 1742040000000, 0);
            INSERT INTO file_index VALUES ('DCIM/v1.mp4', 'v1.mp4', 4000, 1742040000000, 0);
            INSERT INTO file_index VALUES ('2025/03/skip.jpg', 'skip.jpg', 500, 1742040000000, 0);
            """
        )
        conn.close()

        res = rust_bridge.smart_organize_scan_rust(db_path, "/srv/data", 0, now_secs, 300.0, 0)
        assert res is not None
        assert res["success"] is True
        assert res["total"] == 2
        assert res["groups"][0]["label"] == "2025/03"
        assert res["groups"][0]["size"] == 5000


def test_p2_find_similar_fingerprints_rust():
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "fp.db")
        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE image_fingerprints (path TEXT PRIMARY KEY, ahash TEXT, size INTEGER, mtime INTEGER);
            INSERT INTO image_fingerprints VALUES ('target.jpg', '0000000000000000', 100, 1000);
            INSERT INTO image_fingerprints VALUES ('near1.jpg', '0000000000000001', 200, 2000);
            INSERT INTO image_fingerprints VALUES ('near2.jpg', '0000000000000007', 300, 3000);
            INSERT INTO image_fingerprints VALUES ('far.jpg', 'ffffffffffffffff', 400, 4000);
            """
        )
        conn.close()

        matches = rust_bridge.find_similar_fingerprints_rust(db_path, "target.jpg", "0000000000000000", 5, 10)
        assert matches is not None
        assert len(matches) == 2
        assert matches[0]["path"] == "/webdav/near1.jpg"
        assert matches[0]["hamming_distance"] == 1
        assert matches[1]["path"] == "/webdav/near2.jpg"
        assert matches[1]["hamming_distance"] == 3


def test_p2_merge_ts_segments_and_fast_copy_rust():
    with tempfile.TemporaryDirectory() as tmp_dir:
        seg_dir = os.path.join(tmp_dir, "segments")
        os.makedirs(seg_dir)
        with open(os.path.join(seg_dir, "part_000000.ts"), "wb") as f:
            f.write(b"PART0_")
        with open(os.path.join(seg_dir, "part_000001.ts"), "wb") as f:
            f.write(b"PART1_END")

        merged_out = os.path.join(tmp_dir, "merged.ts")
        mres = rust_bridge.merge_ts_segments_rust(seg_dir, 2, merged_out)
        assert mres is not None
        assert mres["segments_merged"] == 2
        assert mres["total_bytes"] == 15
        with open(merged_out, "rb") as f:
            assert f.read() == b"PART0_PART1_END"

        # Test fast_copy_file_rust with SHA-256
        dst_copy = os.path.join(tmp_dir, "copied.ts")
        cres = rust_bridge.fast_copy_file_rust(merged_out, dst_copy, compute_sha256=True)
        assert cres is not None
        assert cres["bytes_copied"] == 15
        assert cres["cancelled"] is False
        assert len(cres["sha256"]) == 64
        with open(dst_copy, "rb") as f:
            assert f.read() == b"PART0_PART1_END"
