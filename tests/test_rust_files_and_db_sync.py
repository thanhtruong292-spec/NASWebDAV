# -*- coding: utf-8 -*-
"""Tests cho GET /api/files/list (Rust scan_directory_entries) va POST /api/files/sync_mutation."""

import os
import sqlite3
import tempfile

import pytest

from backend import nas_api_server


def test_files_list_uses_rust_dir_scanner(monkeypatch):
    with tempfile.TemporaryDirectory() as tmp_dir:
        os.makedirs(os.path.join(tmp_dir, "SubA"), exist_ok=True)
        with open(os.path.join(tmp_dir, "photo.jpg"), "wb") as fp:
            fp.write(b"123456")

        class _FakeBridge(object):
            def scan_directory_entries(self, dir_path, rel_prefix):
                return {
                    "entries": [
                        (rel_prefix + "photo.jpg", "photo.jpg", "photo.jpg", 0, 6, 1700000000000, "image/jpeg", rel_prefix)
                    ],
                    "special": ["SubA"],
                }

        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: True)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "rust_bridge", _FakeBridge())

        client = nas_api_server.app.test_client()
        resp = client.get("/api/files/list")
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["ok"] is True
        assert data["engine"] == "rust_dir_scanner"
        assert data["total"] == 2
        assert data["items"][0]["name"] == "SubA"
        assert data["items"][0]["is_dir"] is True
        assert data["items"][1]["name"] == "photo.jpg"
        assert data["items"][1]["size"] == 6


def test_sync_mutation_rename_and_delete_across_all_tables_and_thumbs(monkeypatch):
    with tempfile.TemporaryDirectory() as tmp_dir:
        thumb_dir = os.path.join(tmp_dir, nas_api_server.THUMB_DIR_NAME)
        os.makedirs(thumb_dir, exist_ok=True)
        old_phys = os.path.join(tmp_dir, "old.jpg")
        new_phys = os.path.join(tmp_dir, "new.jpg")
        old_tp = nas_api_server._get_thumb_path(tmp_dir, old_phys)
        new_tp = nas_api_server._get_thumb_path(tmp_dir, new_phys)
        with open(old_tp, "wb") as fp:
            fp.write(b"THUMB")
        with open(new_phys, "wb") as fp:
            fp.write(b"NEW_CONTENT")

        db_path = os.path.join(tmp_dir, "idx.db")
        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, parent TEXT);
            CREATE TABLE file_hashes (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha256 TEXT, partial TEXT, scanned_at INTEGER);
            CREATE TABLE verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT, size INTEGER, sha256 TEXT, verified_at REAL, path TEXT);
            CREATE TABLE image_fingerprints (path TEXT PRIMARY KEY, ahash TEXT);
            CREATE TABLE ai_image_tags (path TEXT PRIMARY KEY, category TEXT);
            INSERT INTO file_index VALUES ('old.jpg', 'old.jpg', 'old.jpg', '');
            INSERT INTO file_hashes VALUES ('old.jpg', 100, 1, 'aa', 'ht64:1', 1);
            INSERT INTO verified_file_hashes VALUES ('1:10', 'v1', 100, 'aa', 1.0, 'old.jpg');
            INSERT INTO image_fingerprints VALUES ('old.jpg', 'ffff');
            INSERT INTO ai_image_tags VALUES ('old.jpg', 'Anh');
            """
        )
        conn.commit()
        conn.close()

        monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: False)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "_hash_db", lambda: sqlite3.connect(db_path))
        os.makedirs(os.path.join(tmp_dir, "Gallery"), exist_ok=True)

        client = nas_api_server.app.test_client()
        # Requires admin
        assert client.post("/api/files/sync_mutation", json={"action": "delete", "old_path": "old.jpg"}).status_code in (401, 403)

        auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
        r_ren = client.post(
            "/api/files/sync_mutation",
            json={"action": "rename", "old_path": "old.jpg", "new_path": "new.jpg"},
            headers=auth_headers,
        )
        assert r_ren.status_code == 200
        assert r_ren.get_json()["thumb_synced"] is True
        assert os.path.isfile(new_tp) and not os.path.exists(old_tp)

        c2 = sqlite3.connect(db_path)
        for tbl in ("file_index", "file_hashes", "verified_file_hashes", "image_fingerprints", "ai_image_tags"):
            assert c2.execute("SELECT path FROM %s" % tbl).fetchall() == [("new.jpg",)], tbl
        c2.close()

        if os.path.exists(new_phys):
            os.unlink(new_phys)

        r_del = client.post(
            "/api/files/sync_mutation",
            json={"action": "delete", "old_path": "new.jpg"},
            headers=auth_headers,
        )
        assert r_del.status_code == 200
        assert not os.path.exists(new_tp)
        c3 = sqlite3.connect(db_path)
        for tbl in ("file_index", "file_hashes", "verified_file_hashes", "image_fingerprints", "ai_image_tags"):
            assert c3.execute("SELECT COUNT(*) FROM %s" % tbl).fetchone()[0] == 0, tbl
        c3.close()


def test_sync_mutation_folder_rename_case_sensitive_and_legacy_webdav_prefix(monkeypatch):
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "idx.db")
        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, parent TEXT);
            CREATE TABLE file_hashes (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha256 TEXT, partial TEXT, scanned_at INTEGER);
            CREATE TABLE verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT, size INTEGER, sha256 TEXT, verified_at REAL, path TEXT);
            CREATE TABLE index_directories (path TEXT PRIMARY KEY, stamp TEXT, complete INTEGER);
            CREATE TABLE index_state (id INTEGER PRIMARY KEY, ready INTEGER, incomplete_reason TEXT, scanned_at REAL, catalogue_revision INTEGER);
            INSERT INTO file_index VALUES ('Photos/', 'Photos', 'photos', '');
            INSERT INTO file_index VALUES ('Photos/a.jpg', 'a.jpg', 'a.jpg', 'Photos/');
            INSERT INTO file_index VALUES ('photos/b.jpg', 'b.jpg', 'b.jpg', 'photos/');
            INSERT INTO file_hashes VALUES ('Photos/a.jpg', 100, 1, 'aa', 'ht64:1', 1);
            INSERT INTO file_hashes VALUES ('/webdav/Photos/legacy.jpg', 100, 1, 'bb', 'ht64:2', 1);
            INSERT INTO file_hashes VALUES ('photos/b.jpg', 100, 1, 'cc', 'ht64:3', 1);
            INSERT INTO verified_file_hashes VALUES ('1:1', 'v1', 100, 'aa', 1.0, 'Photos/a.jpg');
            INSERT INTO index_directories VALUES ('', 'old_stamp', 1);
            INSERT INTO index_state VALUES (1, 1, '', 123.0, 5);
            """
        )
        conn.commit()
        conn.close()

        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "_hash_db", lambda: sqlite3.connect(db_path))
        os.makedirs(os.path.join(tmp_dir, "Gallery"), exist_ok=True)

        res = nas_api_server._sync_file_mutation("rename", "Photos", "Gallery")
        assert res["ok"] is True

        c2 = sqlite3.connect(db_path)
        fi = dict((r[0], (r[1], r[2], r[3])) for r in c2.execute("SELECT path, name, norm_name, parent FROM file_index").fetchall())
        assert fi["Gallery/"] == ("Gallery", "gallery", "")
        assert fi["Gallery/a.jpg"] == ("a.jpg", "a.jpg", "Gallery/")
        # Case-sensitive: photos/b.jpg MUST NOT be touched!
        assert fi["photos/b.jpg"] == ("b.jpg", "b.jpg", "photos/")

        fh_paths = set(r[0] for r in c2.execute("SELECT path FROM file_hashes").fetchall())
        assert fh_paths == {"Gallery/a.jpg", "/webdav/Gallery/legacy.jpg", "photos/b.jpg"}

        vh_paths = [r[0] for r in c2.execute("SELECT path FROM verified_file_hashes").fetchall()]
        assert vh_paths == ["Gallery/a.jpg"]

        assert c2.execute("SELECT stamp, complete FROM index_directories WHERE path=''").fetchone() == (None, 0)
        st = c2.execute("SELECT ready, catalogue_revision FROM index_state WHERE id=1").fetchone()
        assert st[0] == 0 and st[1] == 6
        c2.close()


def test_stream_fast_index_preserves_vietnamese_utf8_across_65536_byte_boundary(monkeypatch):
    import io
    import json
    from backend import rust_bridge

    # Build a JSON payload and adjust padding so a 3-byte Vietnamese UTF-8 char straddles byte 65536
    base_prefix = json.dumps({"ready": True, "total": 2, "files": [{"name": "PLACEHOLDER", "path": "p"}]}, ensure_ascii=False).encode("utf-8")
    pad_len = 65535 - base_prefix.index(b"PLACEHOLDER")
    straddle_name = ("a" * pad_len) + "ệ_Tiếng_Việt_Đẹp_Nhất.jpg"
    expected_entries = [
        {"name": straddle_name, "path": "Thư_Mục_Gia_Đình/" + straddle_name},
        {"name": "Ảnh_Ước_Mơ_2026.jpg", "path": "Kỷ_Niệm/Ảnh_Ước_Mơ_2026.jpg"},
    ]

    payload_bytes = json.dumps(
        {"ready": True, "total": len(expected_entries), "files": expected_entries},
        ensure_ascii=False,
    ).encode("utf-8")

    # Verify byte 65536 is indeed a UTF-8 continuation byte (0x80..0xBF) inside 'ệ'
    assert (payload_bytes[65536] & 0xC0) == 0x80, "Byte 65536 must be inside a multi-byte UTF-8 sequence"

    class _FakeProc(object):
        def __init__(self, data):
            self.stdout = io.BytesIO(data)
            self.stderr = io.BytesIO(b"")

        def poll(self):
            return 0

        def terminate(self):
            pass

        def wait(self, timeout=None):
            return 0

    with tempfile.TemporaryDirectory() as tmp_dir:
        fake_db = os.path.join(tmp_dir, "nas_index.db")
        with open(fake_db, "wb") as fp:
            fp.write(b"")

        monkeypatch.setattr(rust_bridge, "_find_indexer_binary", lambda: "fake_nas_indexer")
        monkeypatch.setattr(rust_bridge.subprocess, "Popen", lambda *a, **kw: _FakeProc(payload_bytes))

        stream = rust_bridge.stream_fast_index(fake_db)
        assert stream is not None
        decoded_text = "".join(stream)
        parsed = json.loads(decoded_text)
        assert parsed["total"] == len(expected_entries)
        assert parsed["files"] == expected_entries


def test_sync_mutation_folder_rename_child_thumbs_vietnamese_fallback_and_search(monkeypatch):
    with tempfile.TemporaryDirectory() as tmp_dir:
        old_dir = os.path.join(tmp_dir, "Ảnh_Cũ")
        new_dir = os.path.join(tmp_dir, "Ảnh_Mới")
        os.makedirs(new_dir, exist_ok=True)
        child_new = os.path.join(new_dir, "Hoa_Đẹp.jpg")
        with open(child_new, "wb") as fp:
            fp.write(b"IMGDATA")

        thumb_dir = os.path.join(tmp_dir, nas_api_server.THUMB_DIR_NAME)
        os.makedirs(thumb_dir, exist_ok=True)
        child_old = os.path.join(old_dir, "Hoa_Đẹp.jpg")
        old_tp = nas_api_server._get_thumb_path(tmp_dir, child_old)
        new_tp = nas_api_server._get_thumb_path(tmp_dir, child_new)
        with open(old_tp, "wb") as fp:
            fp.write(b"CHILD_THUMB")

        db_path = os.path.join(tmp_dir, "idx.db")
        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT, parent TEXT);
            CREATE TABLE index_directories (path TEXT PRIMARY KEY, stamp TEXT, complete INTEGER);
            CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, ready INTEGER, incomplete_reason TEXT, scanned_at REAL, built INTEGER, catalogue_revision INTEGER);
            INSERT INTO file_index VALUES ('Ảnh_Cũ/', 'Ảnh_Cũ', 'anh_cu', 1, 0, 1, 'folder', '');
            INSERT INTO file_index VALUES ('Ảnh_Cũ/Hoa_Đẹp.jpg', 'Hoa_Đẹp.jpg', 'hoa_dep.jpg', 0, 7, 1, 'image/jpeg', 'Ảnh_Cũ/');
            INSERT INTO index_directories VALUES ('', 's1', 1);
            INSERT INTO index_state VALUES (1, '%s', 1, 1, '', 1700000000.0, 1, 1);
            """ % os.path.realpath(tmp_dir).replace("'", "''")
        )
        conn.commit()
        conn.close()

        class _NoneRustBridge(object):
            def remove_diacritics(self, s):
                return None

            def search_file_index(self, *a, **kw):
                return None

        monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", db_path)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "_hash_db", lambda: sqlite3.connect(db_path))
        os.makedirs(os.path.join(tmp_dir, "Gallery"), exist_ok=True)
        monkeypatch.setattr(nas_api_server, "rust_bridge", _NoneRustBridge())
        monkeypatch.setattr(nas_api_server, "_refresh_file_index_for_search", lambda: None)

        res = nas_api_server._sync_file_mutation("rename", "Ảnh_Cũ", "Ảnh_Mới")
        assert res["ok"] is True
        assert res["thumb_synced"] is True
        assert os.path.isfile(new_tp) and not os.path.exists(old_tp)

        c2 = sqlite3.connect(db_path)
        row = c2.execute("SELECT path, name, norm_name FROM file_index WHERE path='Ảnh_Mới/'").fetchone()
        assert row == ("Ảnh_Mới/", "Ảnh_Mới", "anh_moi")
        c2.close()

        from urllib.parse import unquote
        client = nas_api_server.app.test_client()
        sr = client.get("/api/search?q=hoa_dep")
        assert sr.status_code == 200
        paths = [unquote(it["path"]) for it in sr.get_json()["results"]]
        assert any("Hoa_Đẹp.jpg" in p and "Ảnh_Mới" in p for p in paths)
        assert not any("Ảnh_Cũ" in p for p in paths)


def test_sync_mutation_batch_partial_same_size_overwrite_late_and_retry_journal(monkeypatch):
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "idx.db")
        f_over = os.path.join(tmp_dir, "same.bin")
        f_still = os.path.join(tmp_dir, "still_here.txt")
        with open(f_over, "wb") as fp:
            fp.write(b"NEW_DATA")  # 8 bytes (same size as old)
        with open(f_still, "wb") as fp:
            fp.write(b"ALIVE")

        thumb_dir = os.path.join(tmp_dir, nas_api_server.THUMB_DIR_NAME)
        os.makedirs(thumb_dir, exist_ok=True)
        tp = nas_api_server._get_thumb_path(tmp_dir, f_over)
        with open(tp, "wb") as fp:
            fp.write(b"OLD_THUMB")

        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT, parent TEXT);
            CREATE TABLE file_hashes (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha256 TEXT, partial TEXT, scanned_at INTEGER);
            CREATE TABLE verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT, size INTEGER, sha256 TEXT, verified_at REAL, path TEXT, provenance TEXT DEFAULT '');
            CREATE TABLE index_directories (path TEXT PRIMARY KEY, stamp TEXT, complete INTEGER);
            CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, ready INTEGER, incomplete_reason TEXT, scanned_at REAL, catalogue_revision INTEGER);
            INSERT INTO file_index VALUES ('same.bin', 'same.bin', 'same.bin', 0, 8, 1, 'application/octet-stream', '');
            INSERT INTO file_index VALUES ('still_here.txt', 'still_here.txt', 'still_here.txt', 0, 5, 1, 'text/plain', '');
            INSERT INTO file_hashes VALUES ('same.bin', 8, 1, 'oldsha', 'ht64:1', 1);
            INSERT INTO verified_file_hashes VALUES ('1:1', 'v1', 8, 'oldsha', 1.0, 'same.bin', 'server_lease');
            INSERT INTO index_directories VALUES ('', 's1', 1);
            INSERT INTO index_state VALUES (1, '%s', 1, '', 1.0, 1);
            """ % os.path.realpath(tmp_dir).replace("'", "''")
        )
        conn.commit()
        conn.close()

        monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", db_path)
        monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: False)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "_hash_db", lambda: sqlite3.connect(db_path))
        os.makedirs(os.path.join(tmp_dir, "Gallery"), exist_ok=True)
        monkeypatch.setattr(nas_api_server, "rust_bridge", None)

        client = nas_api_server.app.test_client()
        auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}

        # 1) Batch partial success + same-size overwrite
        r = client.post(
            "/api/files/sync_mutation",
            json={
                "mutations": [
                    {"action": "upload", "old_path": "same.bin", "new_path": "same.bin"},
                    {"action": "rename", "old_path": "../escape.txt", "new_path": "bad.txt"},
                ]
            },
            headers=auth_headers,
        )
        assert r.status_code == 207
        body = r.get_json()
        assert body["ok"] is False and body["synced"] == 1 and body["failed"] == 1
        assert not os.path.exists(tp)
        c2 = sqlite3.connect(db_path)
        assert c2.execute("SELECT COUNT(*) FROM file_hashes WHERE path='same.bin'").fetchone()[0] == 0
        assert c2.execute("SELECT COUNT(*) FROM verified_file_hashes WHERE path='same.bin'").fetchone()[0] == 0
        c2.close()

        # 2) Late delete notification when file still exists on disk -> must NOT delete row
        r_late = nas_api_server._sync_file_mutation("delete", "still_here.txt")
        assert r_late["ok"] is True and r_late["stale_notification"] is True
        c3 = sqlite3.connect(db_path)
        assert c3.execute("SELECT COUNT(*) FROM file_index WHERE path='still_here.txt'").fetchone()[0] == 1
        c3.close()

        # 3) SQLite failure -> persisted in durable journal -> drained after restart
        def _boom_db():
            raise sqlite3.OperationalError("database is locked")

        monkeypatch.setattr(nas_api_server, "_hash_db", _boom_db)
        r_fail = nas_api_server._sync_file_mutation("upload", "still_here.txt", "still_here.txt")
        assert r_fail["ok"] is False and r_fail["reconcile_queued"] is True
        assert os.path.isfile(nas_api_server._pending_mutations_path())

        monkeypatch.setattr(nas_api_server, "_hash_db", lambda: sqlite3.connect(db_path))
        drained = nas_api_server._drain_pending_file_mutations()
        assert drained["replayed"] == 1 and drained["remaining"] == 0
        assert not os.path.exists(nas_api_server._pending_mutations_path())


def test_verified_file_hashes_provenance_reverification_blocks_tampered_legacy_and_client(monkeypatch):
    import hashlib
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "idx.db")
        f_keep = os.path.join(tmp_dir, "keep.jpg")
        f_vic = os.path.join(tmp_dir, "victim.jpg")
        with open(f_keep, "wb") as fp:
            fp.write(b"KEEP_REAL_CONTENT")
        with open(f_vic, "wb") as fp:
            fp.write(b"DIFF_REAL_CONTENT")  # Same size (17 bytes), DIFFERENT content!

        # Create upgraded legacy DB WITHOUT provenance column
        conn = sqlite3.connect(db_path)
        conn.execute(
            "CREATE TABLE verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, verified_at REAL NOT NULL, path TEXT NOT NULL DEFAULT '')"
        )
        conn.commit()
        conn.close()

        monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", db_path)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)

        # Fake fcntl for Windows test runner
        import sys, types
        fake_fcntl = types.ModuleType("fcntl")
        fake_fcntl.F_RDLCK = 0
        fake_fcntl.F_UNLCK = 2
        fake_fcntl.F_SETLEASE = 1024
        fake_fcntl.F_GETLEASE = 1025
        fake_fcntl.fcntl = lambda fd, cmd, arg=0: fake_fcntl.F_RDLCK
        monkeypatch.setitem(sys.modules, "fcntl", fake_fcntl)

        k_info = nas_api_server._duplicate_file_info("keep.jpg")
        v_info = nas_api_server._duplicate_file_info("victim.jpg")
        fake_sha = hashlib.sha256(b"KEEP_REAL_CONTENT").hexdigest()

        # Insert legacy (provenance='') tampered hash claiming victim == keeper
        c = nas_api_server._duplicate_db()
        c.execute(
            "INSERT OR REPLACE INTO verified_file_hashes(file_id,version,size,sha256,verified_at,path,provenance) VALUES(?,?,?,?,?,?,?)",
            (k_info["file_id"], k_info["version"], k_info["size"], fake_sha, 1.0, "keep.jpg", ""),
        )
        c.execute(
            "INSERT OR REPLACE INTO verified_file_hashes(file_id,version,size,sha256,verified_at,path,provenance) VALUES(?,?,?,?,?,?,?)",
            (v_info["file_id"], v_info["version"], v_info["size"], fake_sha, 1.0, "victim.jpg", ""),
        )
        c.commit()
        c.close()

        # _duplicate_lookup(require_server_provenance=True) MUST reject legacy provenance=''
        assert nas_api_server._duplicate_lookup(v_info, require_server_provenance=True) == ""

        # _duplicate_compute_hash MUST re-hash on disk instead of trusting legacy '' row
        monkeypatch.setattr(nas_api_server, "_duplicate_open_lease", lambda info: os.open(info["physical"], os.O_RDONLY))
        monkeypatch.setattr(nas_api_server, "_duplicate_stat_version", lambda _st: v_info["version"])
        monkeypatch.setattr(nas_api_server.rust_bridge, "compute_duplicate_sha256_fd",
                            lambda fd: hashlib.sha256(os.read(fd, 65536)).hexdigest())
        rehashed = nas_api_server._duplicate_compute_hash("victim.jpg", v_info["version"])
        assert rehashed["cached"] is False
        assert rehashed["sha256"] == hashlib.sha256(b"DIFF_REAL_CONTENT").hexdigest()
        assert rehashed["sha256"] != fake_sha
        # Now upgraded to server_lease -> subsequent lookup succeeds
        assert nas_api_server._duplicate_lookup(v_info, require_server_provenance=True) == rehashed["sha256"]

        # Client-submitted hash MUST NOT overwrite an existing server_lease row
        nas_api_server._duplicate_save_hash("victim.jpg", v_info["version"], fake_sha, provenance="client")
        assert nas_api_server._duplicate_lookup(v_info, require_server_provenance=True) == rehashed["sha256"]


def test_duplicate_cleanup_batch_verifies_and_deletes_in_single_transaction(monkeypatch):
    import hashlib
    import sys
    import types
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "idx.db")
        f_keep = os.path.join(tmp_dir, "orig.jpg")
        f_dup = os.path.join(tmp_dir, "copy.jpg")
        f_diff = os.path.join(tmp_dir, "diff.jpg")
        payload = b"IDENTICAL_DUP_CONTENT_1234"
        with open(f_keep, "wb") as fp:
            fp.write(payload)
        with open(f_dup, "wb") as fp:
            fp.write(payload)
        with open(f_diff, "wb") as fp:
            fp.write(b"DIFFERENT_CONTENT_56789012")
        os.utime(f_keep, (1700000001, 1700000001))
        os.utime(f_dup, (1700000002, 1700000002))
        os.utime(f_diff, (1700000003, 1700000003))
        real_sha = hashlib.sha256(payload).hexdigest()

        thumb_dir = os.path.join(tmp_dir, nas_api_server.THUMB_DIR_NAME)
        os.makedirs(thumb_dir, exist_ok=True)
        dup_tp = nas_api_server._get_thumb_path(tmp_dir, f_dup)
        with open(dup_tp, "wb") as fp:
            fp.write(b"THUMB")

        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT, parent TEXT);
            CREATE TABLE file_hashes (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha256 TEXT, partial TEXT, scanned_at INTEGER);
            CREATE TABLE verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, verified_at REAL NOT NULL, path TEXT NOT NULL DEFAULT '', provenance TEXT NOT NULL DEFAULT '');
            CREATE TABLE index_directories (path TEXT PRIMARY KEY, stamp TEXT, complete INTEGER);
            CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, ready INTEGER, incomplete_reason TEXT, scanned_at REAL, built INTEGER, catalogue_revision INTEGER);
            INSERT INTO file_index VALUES ('orig.jpg', 'orig.jpg', 'orig.jpg', 0, 26, 1, 'image/jpeg', '');
            INSERT INTO file_index VALUES ('copy.jpg', 'copy.jpg', 'copy.jpg', 0, 26, 1, 'image/jpeg', '');
            INSERT INTO file_index VALUES ('diff.jpg', 'diff.jpg', 'diff.jpg', 0, 26, 1, 'image/jpeg', '');
            INSERT INTO file_hashes VALUES ('orig.jpg', 26, 1, '%s', 'ht64:1', 1);
            INSERT INTO file_hashes VALUES ('copy.jpg', 26, 1, '%s', 'ht64:1', 1);
            INSERT INTO index_directories VALUES ('', 's1', 1);
            INSERT INTO index_state VALUES (1, '%s', 1, 1, '', 1.0, 1, 3);
            """ % (real_sha, real_sha, os.path.realpath(tmp_dir).replace("'", "''"))
        )
        # file_id (st_dev:st_ino) khong the hardcode -> chen verified_file_hashes voi file_id thuc.
        keep_stat = os.lstat(f_keep)
        dup_stat = os.lstat(f_dup)
        keep_fid = '%s:%s' % (keep_stat.st_dev, keep_stat.st_ino)
        dup_fid = '%s:%s' % (dup_stat.st_dev, dup_stat.st_ino)
        conn.execute(
            "INSERT INTO verified_file_hashes(file_id,version,size,sha256,verified_at,path,provenance) VALUES(?,?,?,?,?,?,?)",
            (keep_fid, 'v1', 26, real_sha, 1.0, 'orig.jpg', 'server_lease'))
        conn.execute(
            "INSERT INTO verified_file_hashes(file_id,version,size,sha256,verified_at,path,provenance) VALUES(?,?,?,?,?,?,?)",
            (dup_fid, 'v2', 26, real_sha, 1.0, 'copy.jpg', 'server_lease'))
        conn.commit()
        conn.close()

        fake_fcntl = types.ModuleType("fcntl")
        fake_fcntl.F_RDLCK = 0
        fake_fcntl.F_UNLCK = 2
        fake_fcntl.F_SETLEASE = 1024
        fake_fcntl.F_GETLEASE = 1025
        fake_fcntl.fcntl = lambda fd, cmd, arg=0: fake_fcntl.F_RDLCK
        monkeypatch.setitem(sys.modules, "fcntl", fake_fcntl)

        monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", db_path)
        monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: False)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "_duplicate_validate_namespace", lambda: None)
        lock_file = os.path.join(tmp_dir, "ns.lock")
        with open(lock_file, "wb") as fp:
            fp.write(b"")
        monkeypatch.setattr(nas_api_server, "_duplicate_open_namespace_lock", lambda write=True: os.open(lock_file, os.O_RDONLY))
        # Mo fd cua file tạm chua cung noi dung + stat nhu victim: code moi os.read
        # toan bo noi dung tu fd co lease (fail-closed), nhung os.rename victim thuc
        # khong bi lock bo vi fd mo la file tạm (fixture Windows, khong du lieu thuc).
        lease_versions = {}
        def _fake_open_lease(info):
            tmp_lease = os.path.join(tmp_dir, "lease_" + os.path.basename(info["physical"]))
            with open(tmp_lease, "wb") as fp:
                fp.write(open(info["physical"], "rb").read())
            os.utime(tmp_lease, (info.get("mtime", 1), info.get("mtime", 1)))
            fd = os.open(tmp_lease, os.O_RDONLY)
            st = os.fstat(fd)
            lease_versions[(st.st_dev, st.st_ino, st.st_size, getattr(st, "st_mtime_ns", int(st.st_mtime)))] = info["version"]
            return fd

        monkeypatch.setattr(nas_api_server, "_duplicate_open_lease", _fake_open_lease)
        monkeypatch.setattr(nas_api_server.rust_bridge, "compute_duplicate_sha256_fd",
                            lambda fd: hashlib.sha256(os.read(fd, 65536)).hexdigest())
        orig_stat_ver = nas_api_server._duplicate_stat_version
        monkeypatch.setattr(
            nas_api_server,
            "_duplicate_stat_version",
            lambda st: lease_versions.get((st.st_dev, st.st_ino, st.st_size, getattr(st, "st_mtime_ns", int(st.st_mtime))), orig_stat_ver(st)),
        )

        client = nas_api_server.app.test_client()
        auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
        resp = client.post(
            "/api/duplicates/cleanup_batch",
            json={
                "items": [
                    {"victim": "copy.jpg", "keeper": "orig.jpg", "sha256": real_sha},
                    {"victim": "diff.jpg", "keeper": "orig.jpg", "sha256": real_sha},
                ]
            },
            headers=auth_headers,
        )
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["ok"] is True
        assert data["deleted_paths"] == ["copy.jpg"]
        assert data["deleted_count"] == 1
        assert data["skipped"] == 1
        assert os.path.isfile(f_keep)
        assert not os.path.exists(f_dup)
        assert os.path.isfile(f_diff)
        assert not os.path.exists(dup_tp)

        c2 = sqlite3.connect(db_path)
        assert c2.execute("SELECT COUNT(*) FROM file_index WHERE path='copy.jpg'").fetchone()[0] == 0
        assert c2.execute("SELECT COUNT(*) FROM file_hashes WHERE path='copy.jpg'").fetchone()[0] == 0
        assert c2.execute("SELECT catalogue_revision FROM index_state WHERE id=1").fetchone()[0] == 4
        c2.close()





def test_batch_journal_replay_preserves_replacement_and_retries_catalogue(tmp_path, monkeypatch):
    import json

    root = tmp_path / "public"
    root.mkdir()
    work = tmp_path / "journal"
    work.mkdir()
    physical = root / "original.txt"
    quarantine = work / "entry"
    journal = work / "entry.json"
    quarantine.write_bytes(b"original")
    current = quarantine.stat()
    journal.write_text(json.dumps({
        "kind": "duplicate_batch_v1",
        "original_path": physical.name,
        "physical": str(physical),
        "quarantine": str(quarantine),
        "file_id": "%s:%s" % (current.st_dev, current.st_ino),
        "size": current.st_size,
        "mtime_ns": current.st_mtime_ns,
    }), encoding="utf-8")
    lock = work / "lock"
    lock.touch()
    monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: str(root))
    monkeypatch.setattr(nas_api_server, "_duplicate_work_dir", lambda: str(work))
    monkeypatch.setattr(nas_api_server, "_duplicate_open_namespace_lock", lambda exclusive: os.open(str(lock), os.O_RDONLY))
    attempts = []

    def sync(action, relative, _allow_enqueue):
        attempts.append((action, relative, _allow_enqueue))
        return {"ok": len(attempts) == 2}

    monkeypatch.setattr(nas_api_server, "_sync_file_mutation", sync)
    physical.write_bytes(b"replacement")
    nas_api_server._replay_duplicate_batch_journals()
    assert physical.read_bytes() == b"replacement"
    assert quarantine.read_bytes() == b"original"
    assert journal.exists()
    assert attempts == []

    physical.unlink()
    nas_api_server._replay_duplicate_batch_journals()
    assert physical.read_bytes() == b"original"
    assert not quarantine.exists()
    assert journal.exists()

    nas_api_server._replay_duplicate_batch_journals()
    assert not journal.exists()
    assert attempts == [("delete", physical.name, False)] * 2


@pytest.mark.parametrize("failure", ["keeper_changed", "lease_broken"])
def test_batch_cleanup_immediate_rollback_and_preserves_replacement_on_error(tmp_path, monkeypatch, failure):
    import hashlib
    import types
    import sys

    root = tmp_path / "public"
    root.mkdir()
    work = tmp_path / "journal_work"
    work.mkdir()
    f_keep = root / "keep.jpg"
    f_dup = root / "copy.jpg"
    payload = b"BATCH_ROLLBACK_TEST_PAYLOAD"
    f_keep.write_bytes(payload)
    f_dup.write_bytes(payload)
    sha = hashlib.sha256(payload).hexdigest()

    db_path = tmp_path / "idx.db"
    conn = sqlite3.connect(str(db_path))
    conn.executescript(
        """
        CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT, parent TEXT);
        CREATE TABLE file_hashes (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha256 TEXT, partial TEXT, scanned_at INTEGER);
        CREATE TABLE verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, verified_at REAL NOT NULL, path TEXT NOT NULL DEFAULT '', provenance TEXT NOT NULL DEFAULT '');
        CREATE TABLE index_directories (path TEXT PRIMARY KEY, stamp TEXT, complete INTEGER);
        CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, ready INTEGER, incomplete_reason TEXT, scanned_at REAL, built INTEGER, catalogue_revision INTEGER);
        """
    )
    k_stat = f_keep.stat()
    d_stat = f_dup.stat()
    k_fid = "%s:%s" % (k_stat.st_dev, k_stat.st_ino)
    d_fid = "%s:%s" % (d_stat.st_dev, d_stat.st_ino)
    conn.execute("INSERT INTO verified_file_hashes VALUES(?,?,?,?,?,?,?)", (k_fid, "v1", len(payload), sha, 1.0, "keep.jpg", "server_lease"))
    conn.execute("INSERT INTO verified_file_hashes VALUES(?,?,?,?,?,?,?)", (d_fid, "v1", len(payload), sha, 1.0, "copy.jpg", "server_lease"))
    conn.commit()
    conn.close()

    fake_fcntl = types.ModuleType("fcntl")
    fake_fcntl.F_RDLCK = 0
    fake_fcntl.F_UNLCK = 2
    fake_fcntl.F_SETLEASE = 1024
    fake_fcntl.F_GETLEASE = 1025
    def get_lease(fd, cmd, arg=0):
        if failure == "lease_broken" and cmd == fake_fcntl.F_GETLEASE and not f_dup.exists():
            return fake_fcntl.F_UNLCK
        return fake_fcntl.F_RDLCK

    fake_fcntl.fcntl = get_lease
    monkeypatch.setitem(sys.modules, "fcntl", fake_fcntl)

    lock_file = tmp_path / "ns.lock"
    lock_file.touch()

    monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", str(db_path))
    monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: str(root))
    monkeypatch.setattr(nas_api_server, "_duplicate_work_dir", lambda: str(work))
    monkeypatch.setattr(nas_api_server, "_duplicate_validate_namespace", lambda: None)
    monkeypatch.setattr(nas_api_server, "_duplicate_open_namespace_lock", lambda write=True: os.open(str(lock_file), os.O_RDONLY))

    lease_versions = {}
    def _fake_open_lease(info):
        tmp_lease = tmp_path / ("lease_" + os.path.basename(info["physical"]))
        tmp_lease.write_bytes(open(info["physical"], "rb").read())
        fd = os.open(str(tmp_lease), os.O_RDONLY)
        st = os.fstat(fd)
        lease_versions[(st.st_dev, st.st_ino, st.st_size, getattr(st, "st_mtime_ns", int(st.st_mtime)))] = info["version"]
        return fd

    monkeypatch.setattr(nas_api_server, "_duplicate_open_lease", _fake_open_lease)
    orig_stat_ver = nas_api_server._duplicate_stat_version
    monkeypatch.setattr(
        nas_api_server,
        "_duplicate_stat_version",
        lambda st: lease_versions.get((st.st_dev, st.st_ino, st.st_size, getattr(st, "st_mtime_ns", int(st.st_mtime))), orig_stat_ver(st)),
    )

    # When survivor changes during cleanup: immediate rollback restores original
    orig_file_info = nas_api_server._duplicate_file_info
    call_count = [0]
    def _flaky_file_info(rel):
        res = orig_file_info(rel)
        if rel == "keep.jpg":
            call_count[0] += 1
            if failure == "keeper_changed" and call_count[0] > 1:
                res["version"] = "v_MODIFIED"
        return res

    monkeypatch.setattr(nas_api_server, "_duplicate_file_info", _flaky_file_info)

    res = nas_api_server._execute_duplicate_cleanup_batch_items([
        {"victim": "copy.jpg", "keeper": "keep.jpg", "sha256": sha}
    ])
    assert res["skipped"] == 1
    # Victim file must be restored immediately by in-request rollback!
    assert f_dup.exists()
    assert f_dup.read_bytes() == payload
    assert f_keep.read_bytes() == payload
    with sqlite3.connect(str(db_path)) as conn:
        assert conn.execute(
            "SELECT sha256 FROM verified_file_hashes WHERE file_id=?", (k_fid,)
        ).fetchone() == (sha,)
        assert conn.execute(
            "SELECT 1 FROM sqlite_master WHERE name='duplicate_deletions'"
        ).fetchone() is None
    # Quarantine directory must be clean (no leftover files)
    assert not list(work.glob("*.json"))


def test_sync_mutation_late_rename_both_exist_or_both_missing_triggers_stale(tmp_path, monkeypatch):
    root = tmp_path / "public"
    root.mkdir()
    db_path = tmp_path / "idx.db"
    conn = sqlite3.connect(str(db_path))
    conn.executescript(
        """
        CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT, parent TEXT);
        CREATE TABLE file_hashes (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha256 TEXT, partial TEXT, scanned_at INTEGER);
        CREATE TABLE verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, verified_at REAL NOT NULL, path TEXT NOT NULL DEFAULT '', provenance TEXT NOT NULL DEFAULT '');
        CREATE TABLE index_directories (path TEXT PRIMARY KEY, stamp TEXT, complete INTEGER);
        CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, ready INTEGER, incomplete_reason TEXT, scanned_at REAL, built INTEGER, catalogue_revision INTEGER);
        INSERT INTO index_state VALUES (1, '%s', 1, 1, '', 1.0, 1, 1);
        """ % str(root).replace("'", "''")
    )
    conn.commit()
    conn.close()

    monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", str(db_path))
    monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: str(root))
    monkeypatch.setattr(nas_api_server, "_hash_db", lambda: sqlite3.connect(str(db_path)))

    # 1) Ca hai tep deu mat tren dia -> stale_notification = True
    r_both_missing = nas_api_server._sync_file_mutation("rename", "missing_old.txt", "missing_new.txt")
    assert r_both_missing["ok"] is True
    assert r_both_missing["stale_notification"] is True
    assert r_both_missing["reconcile_queued"] is True

    # 2) Ca hai tep deu ton tai tren dia (conflict/late) -> stale_notification = True
    f_old = root / "both_old.txt"
    f_new = root / "both_new.txt"
    f_old.write_bytes(b"OLD")
    f_new.write_bytes(b"NEW")
    r_both_exist = nas_api_server._sync_file_mutation("rename", "both_old.txt", "both_new.txt")
    assert r_both_exist["ok"] is True
    assert r_both_exist["stale_notification"] is True
    assert r_both_exist["reconcile_queued"] is True

    # 3) Tep cu con tren dia, tep moi chua co (rename chua xay ra tren dia) -> stale_notification = True
    f_new.unlink()
    r_old_exists = nas_api_server._sync_file_mutation("rename", "both_old.txt", "both_new.txt")
    assert r_old_exists["ok"] is True
    assert r_old_exists["stale_notification"] is True
    assert r_old_exists["reconcile_queued"] is True


def test_pending_mutation_journal_fsync_failure_handling(tmp_path, monkeypatch):
    import json
    db_path = tmp_path / "idx.db"
    monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", str(db_path))

    # 1) Enqueue: khi os.fsync loi, enqueue bao that bai (return False)
    def _broken_fsync(fd):
        raise OSError("Simulated I/O or disk full error on fsync")

    monkeypatch.setattr(os, "fsync", _broken_fsync)
    success = nas_api_server._enqueue_mutation_reconcile("upload", "file.txt", "file.txt", "err")
    assert success is False

    # 2) Drain: khi fsync tren file tam .tmp bi loi, drain phai giu nguyen journal goc, khong replace
    p = nas_api_server._pending_mutations_path()
    orig_content = json.dumps({"action": "create", "old_path": "test.txt", "new_path": "test.txt", "ts": 100.0, "error": ""}) + "\n"
    with open(p, "w", encoding="utf-8") as fp:
        fp.write(orig_content)

    # Mock _sync_file_mutation that bai de leftover con nguyen
    monkeypatch.setattr(nas_api_server, "_sync_file_mutation", lambda *args, **kwargs: {"ok": False})
    drained = nas_api_server._drain_pending_file_mutations()
    # Journal goc p van ton tai va giu nguyen noi dung ban dau!
    assert os.path.isfile(p)
    assert open(p, "r", encoding="utf-8").read() == orig_content
    # File tam .tmp phai duoc don dep
    assert not os.path.exists(p + ".tmp")
