# -*- coding: utf-8 -*-
"""Tests cho tinh nang quet trung lap 100% Rust (/api/disk/scan_duplicates/* + /api/hash/duplicates)."""

import io
import json
import os
import threading
from concurrent.futures import ThreadPoolExecutor
import sqlite3
import tempfile
import time

import pytest

from backend import nas_api_server


def _reset_dup_scan_state():
    with nas_api_server._DUP_SCAN_LOCK:
        nas_api_server._DUP_SCAN_STATE.update({
            "status": "idle",
            "job_id": 0,
            "engine": "rust_rayon",
            "path": "",
            "min_size": 1024,
            "limit": 500,
            "workers": 2,
            "started_at": 0.0,
            "finished_at": 0.0,
            "elapsed_sec": 0.0,
            "error": None,
            "result": None,
        })


def _fixture_catalogue(monkeypatch, tmp_path, paths=()):
    root = str(tmp_path.resolve())
    database = str(tmp_path / "catalogue.db")
    conn = sqlite3.connect(database)
    try:
        conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY,is_dir INTEGER)")
        conn.executemany("INSERT INTO file_index VALUES(?,0)", [(path,) for path in paths])
        conn.execute("CREATE TABLE index_state(id INTEGER PRIMARY KEY, root TEXT, generation INTEGER,"
                     "ready INTEGER, scanned_at REAL, catalogue_revision INTEGER)")
        conn.execute("INSERT INTO index_state VALUES(1,?,3,1,?,8)", (root, time.time()))
        conn.commit()
    finally:
        conn.close()
    nas_api_server.nas_work_coordinator.initialize(database)
    monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", database)
    monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: root)
    monkeypatch.setattr(nas_api_server, "_load_dup_schedule", lambda: {})
    monkeypatch.setattr(nas_api_server, "_save_dup_schedule", lambda state: state)
    return database


def test_two_clients_share_scan_and_only_initiator_can_cancel(monkeypatch, tmp_path):
    _fixture_catalogue(monkeypatch, tmp_path)
    monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
    monkeypatch.setattr(nas_api_server, "_DUP_SCAN_STATE", {"status": "idle", "job_id": 0})
    started = threading.Event()
    release = threading.Event()
    stopped = threading.Event()
    requests = threading.Barrier(2)
    calls = []

    class Bridge:
        def duplicate_scanner_available(self):
            return True

        def find_duplicates(self, root, progress_cb, **kwargs):
            calls.append(root)
            started.set()
            try:
                assert release.wait(10)
                assert progress_cb(1, 0, 0, 0, "")
                return None
            finally:
                stopped.set()

    monkeypatch.setattr(nas_api_server, "rust_bridge", Bridge())
    headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
    route = "/api/disk/scan_duplicates"
    body = {"async": True, "min_size": 0, "force": True, "limit": 0}

    def start():
        requests.wait(5)
        with nas_api_server.app.test_client() as client:
            result = client.post(route, json=body, headers=headers)
            return result.status_code, result.get_json()

    try:
        with ThreadPoolExecutor(2) as pool:
            starts = [pool.submit(start) for _ in range(2)]
            results = [future.result(6) for future in starts]
        assert started.wait(5)
        assert [code for code, _ in results] == [202, 202]
        assert {result["job_id"] for _, result in results} == {1}
        owned, = [result for _, result in results if result.get("cancel_token")]
        assert len(calls) == 1
        with nas_api_server.app.test_client() as client:
            assert "cancel_token" not in client.get(route + "/status", headers=headers).get_json()
            for changed in ({"client_cleanup": True}, {"force": False}, {"limit": 1}, {"min_size": 1}):
                assert client.post(route, json=dict(body, **changed), headers=headers).status_code == 409
            assert client.post(route + "/cancel", json={"job_id": 1}, headers=headers).status_code == 403
            cancel = {"job_id": 1, "cancel_token": owned["cancel_token"]}
            result = client.post(route + "/cancel", json=cancel, headers=headers)
            assert (result.status_code, result.get_json()["status"]) == (202, "cancelling")
            assert client.post(route, json=body, headers=headers).status_code == 409
    finally:
        release.set()
        deadline = time.monotonic() + 5
        while nas_api_server._DUP_SCAN_STATE.get("active_job") and time.monotonic() < deadline:
            time.sleep(.01)
    assert stopped.is_set()
    assert nas_api_server._DUP_SCAN_STATE["status"] == "cancelled"
    assert "active_job" not in nas_api_server._DUP_SCAN_STATE


def test_leased_autoclean_cannot_overlap_manual_scan(monkeypatch, tmp_path):
    database = _fixture_catalogue(monkeypatch, tmp_path)
    monkeypatch.setattr(nas_api_server, "_DUP_SCAN_STATE", {"status": "idle", "job_id": 0})
    monkeypatch.setattr(nas_api_server, "_background_heavy_work_allowed", lambda: True)
    monkeypatch.setattr(nas_api_server, "check_auth", lambda *args: True)
    headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
    work = nas_api_server.nas_work_coordinator
    work.request_job(database, "duplicates", str(tmp_path.resolve()))

    class Bridge:
        def duplicate_scanner_available(self):
            return True

        def find_duplicates(self, *args, **kwargs):
            return {"groups": [], "total_groups": 0, "read_errors": 0, "total_files_scanned": 0,
                    "total_wasted_bytes": 0}

    monkeypatch.setattr(nas_api_server, "rust_bridge", Bridge())
    monkeypatch.setattr(nas_api_server, "_persist_rust_duplicate_report", lambda *args: None)
    barrier = threading.Barrier(2)

    def claim(owner):
        barrier.wait(5)
        with nas_api_server.app.test_client() as client:
            response = client.post("/api/work/claim", headers=headers,
                                   json={"name": "duplicates", "owner": owner})
            return response.status_code, response.get_json()

    with ThreadPoolExecutor(2) as pool:
        claims = [pool.submit(claim, owner) for owner in ("fixture-phone-a", "fixture-phone-b")]
        first, second = sorted(future.result(6) for future in claims)
    assert (first[0], second[0]) == (200, 423)
    permit = {key: first[1][key] for key in ("name", "owner", "token")}
    route = "/api/disk/scan_duplicates"
    with nas_api_server.app.test_client() as client:
        assert client.post(route, json={}, headers=headers).status_code == 409
        assert client.post("/api/duplicates/auto_clean", json={}, headers=headers).status_code == 409
        for invalid in (None, dict(permit, token="wrong")):
            response = client.post(route, headers=headers, json={"client_cleanup": True, "work_lease": invalid})
            assert response.status_code == 409
        response = client.post(route, headers=headers, json={
            "async": False, "client_cleanup": True, "work_lease": permit, "force": True,
        })
        assert response.status_code == 200, response.get_json()
        assert response.get_json()["server_auto_clean"] is False


def test_pending_catalogue_never_opens_rust_scanner(monkeypatch, tmp_path):
    database = _fixture_catalogue(monkeypatch, tmp_path)
    conn = sqlite3.connect(database)
    try:
        conn.execute("UPDATE index_state SET ready=0 WHERE id=1")
        conn.commit()
    finally:
        conn.close()
    monkeypatch.setattr(nas_api_server, "_DUP_SCAN_STATE", {"status": "running", "job_id": 7})
    refreshes = []
    monkeypatch.setattr(nas_api_server, "_refresh_file_index_for_search", lambda: refreshes.append(True))

    class Bridge:
        def duplicate_scanner_available(self):
            return True

        def find_duplicates(self, *args, **kwargs):
            pytest.fail("Rust opened before the shared catalogue was ready")

    monkeypatch.setattr(nas_api_server, "rust_bridge", Bridge())
    nas_api_server._run_rust_duplicate_scan_worker(7, str(tmp_path), 0, 1, 0)
    assert refreshes == [True]
    assert nas_api_server._DUP_SCAN_STATE["status"] == "error"
    assert nas_api_server._DUP_SCAN_STATE["result"] is None


def test_duplicate_manifest_uses_exact_catalogue_scope(monkeypatch, tmp_path):
    (tmp_path / "Dept%20").mkdir()
    database = _fixture_catalogue(monkeypatch, tmp_path,
                                  ["Dept%20/Ảnh+a.bin", "Dept%20/literal%2B.txt", "Dept%200/other", "dept%20/other"])
    monkeypatch.setattr(nas_api_server, "_DUP_SCAN_STATE", {"job_id": 1})
    stream = io.StringIO()
    info = nas_api_server._write_duplicate_catalogue_manifest(str(tmp_path.resolve()), "Dept%20", stream, 1)
    assert info == {"protocol": 1, "source": "file_index", "complete": True,
                    "total": 2, "generation": 3, "catalogue_revision": 8}
    assert {json.loads(line)[0] for line in stream.getvalue().splitlines()} == {
        "Dept%20/Ảnh+a.bin", "Dept%20/literal%2B.txt"}
    conn = sqlite3.connect(database)
    try:
        conn.execute("UPDATE index_state SET catalogue_revision=9")
        conn.commit()
    finally:
        conn.close()
    with pytest.raises(ValueError, match="catalogue"):
        nas_api_server._require_duplicate_catalogue_revision(str(tmp_path.resolve()), info)


def test_scan_duplicates_requires_admin(monkeypatch):
    _reset_dup_scan_state()
    monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: False)
    client = nas_api_server.app.test_client()
    for method, url in (
        ("POST", "/api/disk/scan_duplicates"),
        ("GET", "/api/disk/scan_duplicates/status"),
        ("POST", "/api/disk/scan_duplicates/cancel"),
    ):
        resp = client.open(url, method=method)
        assert resp.status_code in (401, 403), (url, resp.status_code)


def test_scan_duplicates_returns_503_without_python_fallback_when_rust_missing(monkeypatch):
    _reset_dup_scan_state()
    monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
    monkeypatch.setattr(nas_api_server, "rust_bridge", None)
    client = nas_api_server.app.test_client()
    auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
    resp = client.post("/api/disk/scan_duplicates", json={"async": False}, headers=auth_headers)
    assert resp.status_code == 503
    assert "Rust" in (resp.get_json() or {}).get("error", "")


def test_scan_duplicates_async_rust_and_persists_hashes(monkeypatch):
    _reset_dup_scan_state()
    with tempfile.TemporaryDirectory() as tmp_dir:
        f1 = os.path.join(tmp_dir, "a.bin")
        f2 = os.path.join(tmp_dir, "b.bin")
        with open(f1, "wb") as fp:
            fp.write(b"Z" * 2048)
        with open(f2, "wb") as fp:
            fp.write(b"Z" * 2048)

        from pathlib import Path
        db_path = _fixture_catalogue(monkeypatch, Path(tmp_dir), ["a.bin", "b.bin"])

        class _FakeRustBridge(object):
            def duplicate_scanner_available(self):
                return True

            def find_duplicates(self, root_dir, min_size=1024, workers=2, limit=500, **kwargs):
                with open(kwargs["catalogue_manifest"], encoding="utf-8") as manifest:
                    assert [json.loads(line)[0] for line in manifest] == ["a.bin", "b.bin"]
                return {
                    "groups": [{
                        "sha256": "ab" * 32,
                        "size": 2048,
                        "count": 2,
                        "wasted_bytes": 2048,
                        "files": ["a.bin", "b.bin"],
                    }],
                    "total_groups": 1,
                    "total_files_scanned": 2,
                    "read_errors": 0,
                    "size_candidates": 2,
                    "partial_candidates": 2,
                    "total_wasted_bytes": 2048,
                }

        def _tmp_hash_db():
            conn = sqlite3.connect(db_path)
            conn.execute(
                "CREATE TABLE IF NOT EXISTS file_hashes ("
                "path TEXT PRIMARY KEY, size INTEGER NOT NULL DEFAULT 0, "
                "mtime INTEGER NOT NULL DEFAULT 0, sha256 TEXT NOT NULL DEFAULT '', "
                "partial TEXT NOT NULL DEFAULT '', scanned_at INTEGER NOT NULL DEFAULT 0)"
            )
            return conn

        monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: True)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "_resolve_webdav_request_path", lambda p: os.path.join(tmp_dir, os.path.basename(p)))
        monkeypatch.setattr(nas_api_server, "_hash_db", _tmp_hash_db)
        monkeypatch.setattr(nas_api_server, "rust_bridge", _FakeRustBridge())

        auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
        client = nas_api_server.app.test_client()
        resp = client.post("/api/disk/scan_duplicates", json={"async": True, "min_size": 1024}, headers=auth_headers)
        assert resp.status_code == 202

        deadline = time.time() + 5.0
        snap = {}
        while time.time() < deadline:
            st_resp = client.get("/api/disk/scan_duplicates/status", headers=auth_headers)
            assert st_resp.status_code == 200
            snap = st_resp.get_json()
            if snap.get("status") == "done":
                break
            time.sleep(0.05)

        assert snap.get("status") == "done"
        assert snap["result"]["engine"] == "rust_rayon"
        assert snap["result"]["total_groups"] == 1
        assert snap["result"]["total_wasted_bytes"] == 2048

        # Kiem tra /api/hash/duplicates doc duoc du lieu Rust da luu va tinh dung wasted_bytes = (count-1)*size
        h_resp = client.get("/api/hash/duplicates?min_size=1024", headers=auth_headers)
        assert h_resp.status_code == 200
        h_data = h_resp.get_json()
        assert h_data["total_groups"] == 1
        assert h_data["groups"][0]["size"] == 2048
        assert h_data["groups"][0]["wasted_bytes"] == 2048
        assert sorted(h_data["groups"][0]["files"]) == ["a.bin", "b.bin"]


def test_dup_schedule_get_post_and_cron_trigger(monkeypatch):
    import datetime
    _reset_dup_scan_state()
    with tempfile.TemporaryDirectory() as tmp_dir:
        sched_file = os.path.join(tmp_dir, "dup_sched.json")
        monkeypatch.setattr(nas_api_server, "_dup_schedule_file_path", lambda: sched_file)
        monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: False)

        client = nas_api_server.app.test_client()
        assert client.get("/api/disk/scan_duplicates/schedule").status_code in (401, 403)

        auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
        r_get = client.get("/api/disk/scan_duplicates/schedule", headers=auth_headers)
        assert r_get.status_code == 200
        assert r_get.get_json()["enabled"] is True

        r_post = client.post(
            "/api/disk/scan_duplicates/schedule",
            json={"enabled": True, "hour": 4, "interval_days": 3},
            headers=auth_headers,
        )
        assert r_post.status_code == 200
        cfg = r_post.get_json()
        assert cfg["hour"] == 4
        assert cfg["interval_days"] == 3

        # Wrong hour -> does not trigger
        now_dt = datetime.datetime(2026, 11, 22, 2, 0, 0)
        assert nas_api_server._maybe_trigger_scheduled_dup_scan(now_dt, time.time()) is False


def test_prune_orphaned_hashes_and_thumbs(monkeypatch):
    with tempfile.TemporaryDirectory() as tmp_dir:
        keep_file = os.path.join(tmp_dir, "keep.jpg")
        with open(keep_file, "wb") as fp:
            fp.write(b"KEEP")
        gone_file = os.path.join(tmp_dir, "gone.jpg")

        thumb_dir = os.path.join(tmp_dir, nas_api_server.THUMB_DIR_NAME)
        os.makedirs(thumb_dir, exist_ok=True)
        keep_tp = nas_api_server._get_thumb_path(tmp_dir, keep_file)
        gone_tp = nas_api_server._get_thumb_path(tmp_dir, gone_file)
        with open(keep_tp, "wb") as fp:
            fp.write(b"TKEEP")
        with open(gone_tp, "wb") as fp:
            fp.write(b"TGONE")
        old_ts = time.time() - 7200
        os.utime(gone_tp, (old_ts, old_ts))

        db_path = os.path.join(tmp_dir, "idx.db")
        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE file_hashes (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha256 TEXT, partial TEXT, scanned_at INTEGER);
            CREATE TABLE verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT, size INTEGER, sha256 TEXT, verified_at REAL, path TEXT);
            CREATE TABLE index_state (id INTEGER PRIMARY KEY, ready INTEGER);
            CREATE TABLE file_index (path TEXT PRIMARY KEY, is_dir INTEGER);
            INSERT INTO file_hashes VALUES ('keep.jpg', 4, 1, 'aa', 'ht64:1', 1);
            INSERT INTO file_hashes VALUES ('gone.jpg', 4, 1, 'bb', 'ht64:2', 1);
            INSERT INTO verified_file_hashes VALUES ('1:1', 'v1', 4, 'aa', 1.0, 'keep.jpg');
            INSERT INTO verified_file_hashes VALUES ('1:2', 'v2', 4, 'bb', 1.0, 'gone.jpg');
            INSERT INTO index_state VALUES (1, 1);
            INSERT INTO file_index VALUES ('keep.jpg', 0);
            """
        )
        conn.commit()
        conn.close()

        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "_resolve_webdav_request_path", lambda p: os.path.join(tmp_dir, os.path.basename(p)))
        monkeypatch.setattr(nas_api_server, "_hash_db", lambda: sqlite3.connect(db_path))

        pruned_h, pruned_t = nas_api_server._prune_orphaned_hashes_and_thumbs()
        assert pruned_h == 2
        assert pruned_t >= 1
        assert os.path.isfile(keep_tp)
        assert not os.path.exists(gone_tp)
        c2 = sqlite3.connect(db_path)
        assert c2.execute("SELECT path FROM file_hashes").fetchall() == [("keep.jpg",)]
        assert c2.execute("SELECT path FROM verified_file_hashes").fetchall() == [("keep.jpg",)]
        c2.close()


def test_prune_dup_scan_state_paths_removes_deleted_and_cleans_empty_groups():
    _reset_dup_scan_state()
    with nas_api_server._DUP_SCAN_LOCK:
        nas_api_server._DUP_SCAN_STATE.update({
            "status": "done",
            "result": {
                "groups": [
                    {
                        "sha256": "11" * 32,
                        "size": 1000,
                        "count": 2,
                        "wasted_bytes": 1000,
                        "files": ["Photos/a.jpg", "Photos/b.jpg"],
                    },
                    {
                        "sha256": "22" * 32,
                        "size": 2000,
                        "count": 3,
                        "wasted_bytes": 4000,
                        "files": ["Videos/v1.mp4", "Videos/v2.mp4", "Videos/v3.mp4"],
                    },
                ],
                "total_groups": 2,
                "total_wasted_bytes": 5000,
            },
        })

    # Xoa b.jpg -> nhom 1 chi con 1 file -> nhom 1 tu dong bi loai bo
    nas_api_server._prune_dup_scan_state_paths(["/webdav/Photos/b.jpg"])
    with nas_api_server._DUP_SCAN_LOCK:
        snap = nas_api_server._snapshot_dup_scan_state_locked()
    res = snap["result"]
    assert res["total_groups"] == 1
    assert res["total_wasted_bytes"] == 4000
    assert len(res["groups"]) == 1
    assert res["groups"][0]["sha256"] == "22" * 32

    # Xoa v3.mp4 -> nhom 2 con 2 files -> giu lai nhom voi wasted_bytes = 2000
    nas_api_server._prune_dup_scan_state_paths(["Videos/v3.mp4"])
    with nas_api_server._DUP_SCAN_LOCK:
        snap = nas_api_server._snapshot_dup_scan_state_locked()
    res = snap["result"]
    assert res["total_groups"] == 1
    assert res["total_wasted_bytes"] == 2000
    assert res["groups"][0]["count"] == 2
    assert res["groups"][0]["files"] == ["Videos/v1.mp4", "Videos/v2.mp4"]

    # Xoa toan bo thu muc Videos -> nhom 2 bi loai bo het
    nas_api_server._prune_dup_scan_state_paths(["Videos"])
    with nas_api_server._DUP_SCAN_LOCK:
        snap = nas_api_server._snapshot_dup_scan_state_locked()
    res = snap["result"]
    assert res["total_groups"] == 0
    assert res["total_wasted_bytes"] == 0
    assert len(res["groups"]) == 0


def test_status_endpoint_auto_prunes_externally_deleted_files_on_the_fly(monkeypatch):
    _reset_dup_scan_state()
    with tempfile.TemporaryDirectory() as tmp_dir:
        os.mkdir(os.path.join(tmp_dir, "webdav"))
        p1 = os.path.join(tmp_dir, "webdav", "clip%20test.bin")
        p2 = os.path.join(tmp_dir, "file2.bin")
        with open(p1, "wb") as fp:
            fp.write(b"DATA" * 500)
        with open(p2, "wb") as fp:
            fp.write(b"DATA" * 500)

        with nas_api_server._DUP_SCAN_LOCK:
            nas_api_server._DUP_SCAN_STATE.update({
                "status": "done",
                "result": {
                    "groups": [{
                        "sha256": "33" * 32,
                        "size": 2000,
                        "count": 2,
                        "wasted_bytes": 2000,
                        "files": ["webdav/clip%20test.bin", "file2.bin"],
                    }],
                    "total_groups": 1,
                    "total_wasted_bytes": 2000,
                },
            })

        monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: True)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: tmp_dir)
        monkeypatch.setattr(nas_api_server, "_resolve_webdav_request_path", lambda p: os.path.join(tmp_dir, os.path.basename(p)))

        auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
        client = nas_api_server.app.test_client()
        url = "/api/disk/scan_duplicates/status"
        assert client.get(url, headers=auth_headers).get_json()["result"]["total_groups"] == 1

        # Xoa file2.bin truc tiep tren dia (vi du qua Samba / SSH)
        os.remove(p2)
        assert not os.path.exists(p2)

        # Goi GET /api/disk/scan_duplicates/status -> tu dong xac thuc dia va loai bo file da xoa
        resp = client.get(url, headers=auth_headers)
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["result"]["total_groups"] == 0
        assert len(data["result"]["groups"]) == 0
        assert data["result"]["total_wasted_bytes"] == 0


def test_sync_file_mutation_delete_prunes_dup_scan_state():
    _reset_dup_scan_state()
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "index.db")
        conn = sqlite3.connect(db_path)
        conn.execute("CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, is_dir INTEGER)")
        conn.execute("CREATE TABLE file_hashes (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha256 TEXT)")
        conn.commit()
        conn.close()

        with nas_api_server._DUP_SCAN_LOCK:
            nas_api_server._DUP_SCAN_STATE.update({
                "status": "done",
                "result": {
                    "groups": [{
                        "sha256": "44" * 32,
                        "size": 1500,
                        "count": 2,
                        "wasted_bytes": 1500,
                        "files": ["docs/dup1.pdf", "docs/dup2.pdf"],
                    }],
                    "total_groups": 1,
                    "total_wasted_bytes": 1500,
                },
            })

        orig_hash_db = nas_api_server._hash_db
        orig_webdav = nas_api_server._get_webdav_public_dir
        try:
            nas_api_server._hash_db = lambda: sqlite3.connect(db_path)
            nas_api_server._get_webdav_public_dir = lambda: tmp_dir

            # Dong bo thao tac xoa docs/dup2.pdf
            res = nas_api_server._sync_file_mutation("delete", "docs/dup2.pdf")
            assert res.get("ok") is True

            # Kiem tra _DUP_SCAN_STATE da duoc tu dong prune
            with nas_api_server._DUP_SCAN_LOCK:
                snap = nas_api_server._snapshot_dup_scan_state_locked()
            assert snap["result"]["total_groups"] == 0
            assert len(snap["result"]["groups"]) == 0
        finally:
            nas_api_server._hash_db = orig_hash_db
            nas_api_server._get_webdav_public_dir = orig_webdav


def test_requires_auth_query_param_and_www_authenticate(monkeypatch):
    import base64
    _reset_dup_scan_state()
    monkeypatch.setattr(nas_api_server, "WEBDAV_USER", "daica")
    monkeypatch.setattr(nas_api_server, "WEBDAV_PASS", "secret123")
    monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: False)
    monkeypatch.setattr(nas_api_server, "_refresh_authorized_ips_cache", lambda: set())
    client = nas_api_server.app.test_client()

    # 1. Khong co auth header -> tra 401 co WWW-Authenticate
    r1 = client.get("/api/media?path=dummy.jpg")
    assert r1.status_code == 401
    assert "Basic realm=" in r1.headers.get("WWW-Authenticate", "")

    # 2. Sai auth trong query -> 401
    bad_auth = base64.b64encode(b"wrong:pass").decode("ascii")
    r2 = client.get("/api/media?path=dummy.jpg&auth=" + bad_auth)
    assert r2.status_code == 401

    # 3. Dung auth trong query -> vuot qua gate auth (tra 400 hoac 404 tuy path ton tai hay khong, khong con 401)
    good_auth = base64.b64encode(b"daica:secret123").decode("ascii")
    r3 = client.get("/api/media?path=dummy.jpg&auth=" + good_auth)
    assert r3.status_code in (404, 200, 206)


def test_auto_clean_schedule_flag_and_endpoints_require_admin(monkeypatch):
    _reset_dup_scan_state()
    with tempfile.TemporaryDirectory() as tmp_dir:
        sched_file = os.path.join(tmp_dir, "dup_sched.json")
        monkeypatch.setattr(nas_api_server, "_dup_schedule_file_path", lambda: sched_file)
        monkeypatch.setattr(nas_api_server, "check_auth", lambda u, p: True)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: False)

        client = nas_api_server.app.test_client()
        auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}

        # 1. Endpoints require admin
        for method, url in (
            ("POST", "/api/duplicates/auto_clean"),
            ("GET", "/api/duplicates/auto_clean/status"),
            ("POST", "/api/duplicates/auto_clean/cancel"),
        ):
            resp = client.open(url, method=method)
            assert resp.status_code in (401, 403), (url, resp.status_code)

        # 2. Schedule POST with auto_clean
        r_post = client.post(
            "/api/disk/scan_duplicates/schedule",
            json={"enabled": True, "hour": 3, "interval_days": 7, "auto_clean": True},
            headers=auth_headers,
        )
        assert r_post.status_code == 200
        cfg = r_post.get_json()
        assert cfg["auto_clean"] is True

        # 3. Schedule GET returns auto_clean
        r_get = client.get("/api/disk/scan_duplicates/schedule", headers=auth_headers)
        assert r_get.status_code == 200
        assert r_get.get_json()["auto_clean"] is True

        # 4. auto_clean status & cancel endpoints
        r_stat = client.get("/api/duplicates/auto_clean/status", headers=auth_headers)
        assert r_stat.status_code == 200
        assert "status" in r_stat.get_json()

        r_cancel = client.post("/api/duplicates/auto_clean/cancel", headers=auth_headers)
        assert r_cancel.status_code == 200




def _complete_fixture_report(monkeypatch, tmp_path, groups):
    _fixture_catalogue(monkeypatch, tmp_path)
    return {"read_errors": 0, "total_groups": len(groups), "groups": groups, "total_files_scanned": 0,
            "inventory": {"protocol": 1, "source": "file_index", "complete": True,
                          "total": 0, "generation": 3, "catalogue_revision": 8}}


def test_auto_cleanup_refuses_report_without_inventory(monkeypatch):
    monkeypatch.setattr(nas_api_server, "_DUP_AUTO_CLEAN_STATE", {})
    def no_cleanup(items):
        pytest.fail("Entered destructive cleanup without inventory proof")
    monkeypatch.setattr(nas_api_server, "_execute_duplicate_cleanup_batch_items", no_cleanup)
    nas_api_server._run_auto_duplicate_cleanup_worker({"read_errors": 0, "total_groups": 0, "groups": []})
    assert nas_api_server._DUP_AUTO_CLEAN_STATE["status"] == "error"


def test_cleanup_reports_partial_for_skips_and_deferred_catalogue(monkeypatch, tmp_path):
    alerts = []
    monkeypatch.setattr(nas_api_server, "_DUP_AUTO_CLEAN_STATE", {})
    monkeypatch.setattr(nas_api_server, "_push_alert", lambda *args: alerts.append(args))
    monkeypatch.setattr(nas_api_server, "_execute_duplicate_cleanup_batch_items", lambda items: {
        "ok": True, "deleted_count": 1, "bytes_removed": 10, "skipped": 1, "metadata_pending": 1,
    })
    report = _complete_fixture_report(monkeypatch, tmp_path, [{"sha256": "ab" * 32, "files": ["a", "b", "c"]}])
    report["job_id"] = 7
    nas_api_server._run_auto_duplicate_cleanup_worker(report)
    state = nas_api_server._DUP_AUTO_CLEAN_STATE
    assert (state["status"], state["deleted"], state["skipped"], state["metadata_pending"]) == ("partial", 1, 1, 1)
    assert alerts[-1][-1] == "WARNING"
    empty_report = dict(report, job_id=8, total_groups=0, groups=[])
    nas_api_server._run_auto_duplicate_cleanup_worker(empty_report)
    assert (state["total"], state["skipped"], state["metadata_pending"]) == (0, 0, 0)


def test_full_report_survives_preview_limit_and_keeps_detected_count(monkeypatch):
    groups = [{"sha256": "%064x" % i, "size": 3, "files": ["a%d" % i, "b%d" % i]} for i in range(501)]
    original = {"groups": groups, "total_groups": 501, "detected_groups": 501}
    state = {"result": original, "limit": 500, "status": "done"}
    monkeypatch.setattr(nas_api_server, "_DUP_SCAN_STATE", state)
    monkeypatch.setattr(nas_api_server, "_verify_and_prune_dup_scan_result_locked", lambda: None)
    assert len(nas_api_server._snapshot_dup_scan_state_locked()["result"]["groups"]) == 500
    assert nas_api_server._prune_dup_scan_state_paths(["a0"])
    assert state["result"]["total_groups"] == 500
    assert state["result"]["detected_groups"] == 501
    assert len(original["groups"]) == 501


@pytest.mark.parametrize("read_errors", [None, 1])
def test_read_errors_block_auto_cleanup(monkeypatch, tmp_path, read_errors):
    _fixture_catalogue(monkeypatch, tmp_path)
    class Bridge:
        def duplicate_scanner_available(self):
            return True

        def find_duplicates(self, *args, **kwargs):
            assert kwargs["limit"] == 0
            report = {"groups": [], "total_groups": 0, "total_wasted_bytes": 0}
            if read_errors is not None:
                report["read_errors"] = read_errors
            return report

    state = {"job_id": 7}
    monkeypatch.setattr(nas_api_server, "_DUP_SCAN_STATE", state)
    monkeypatch.setattr(nas_api_server, "rust_bridge", Bridge())
    monkeypatch.setattr(nas_api_server, "_dup_schedule_file_path", lambda: str(tmp_path / "schedule"))
    monkeypatch.setattr(nas_api_server, "_load_dup_schedule", lambda: {"auto_clean": True})
    def no_cleanup(*args, **kwargs):
        raise AssertionError("Incomplete scan started cleanup")
    monkeypatch.setattr(nas_api_server.threading, "Thread", no_cleanup)
    nas_api_server._run_rust_duplicate_scan_worker(7, str(tmp_path), 0, 1, 500, force=True)
    assert state["status"] == "error"
    assert state["result"] is None
    assert "read_errors=%s" % read_errors in state["error"]


def test_receipt_replay_is_idempotent():
    conn = sqlite3.connect(":memory:")
    record = dict(original_path="backup/b", keeper="a", sha256="ab"*32, version="v1",
                  keeper_version="v2", size=4, created_at=1.0)
    try:
        for _ in range(2):
            nas_api_server._record_duplicate_deletion(conn, "receipt.json", record)
        assert conn.execute("SELECT victim,keeper,sha256 FROM duplicate_deletions").fetchall() == [
            ("backup/b", "a", record["sha256"])]
    finally:
        conn.close()

def test_auto_cleanup_retains_small_files_and_reports_partial(monkeypatch, tmp_path):
    monkeypatch.setattr(nas_api_server, "_DUP_AUTO_CLEAN_STATE", {})
    def unexpected_cleanup(items):
        raise AssertionError("Small files must not be deleted automatically")
    monkeypatch.setattr(nas_api_server, "_execute_duplicate_cleanup_batch_items", unexpected_cleanup)
    report = _complete_fixture_report(monkeypatch, tmp_path, [{"sha256": "cd"*32, "files": ["a", "b"], "size": 0}])
    nas_api_server._run_auto_duplicate_cleanup_worker(report, min_cleanup_size=1024)
    state = nas_api_server._DUP_AUTO_CLEAN_STATE
    assert (state["status"], state["retained_small"], state["deleted"]) == ("partial", 1, 0)


def test_real_rust_scans_only_catalogue_paths(monkeypatch, tmp_path):
    from backend import rust_bridge
    binary = os.environ.get("NAS_DUPLICATE_TEST_BINARY")
    if not binary:
        pytest.skip("Set NAS_DUPLICATE_TEST_BINARY to the rebuilt catalogue-aware Rust CLI")
    paths = ["Indexed/Ảnh+a.bin", "Indexed/literal%2B.bin"]
    (tmp_path / "Indexed").mkdir()
    for name in paths + ["Indexed/not-in-catalogue.bin"]:
        (tmp_path / name).write_bytes(b"same fixture content")
    _fixture_catalogue(monkeypatch, tmp_path, paths)
    monkeypatch.setattr(nas_api_server, "_DUP_SCAN_STATE", {"status": "idle", "job_id": 0})
    monkeypatch.setattr(nas_api_server, "_persist_rust_duplicate_report", lambda *args: None)
    monkeypatch.setattr(nas_api_server, "check_auth", lambda *args: True)
    monkeypatch.setattr(rust_bridge, "_find_nas_hasher_bin", lambda: binary)
    monkeypatch.setattr(nas_api_server, "rust_bridge", rust_bridge)
    headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
    client = nas_api_server.app.test_client()
    response = client.post("/api/disk/scan_duplicates", headers=headers,
                           json={"async": False, "path": "/webdav/Indexed", "min_size": 0, "force": True})
    assert response.status_code == 200, response.get_json()
    report = response.get_json()
    assert report["inventory"]["total"] == 2
    assert report["read_errors"] == 0
    assert report["total_groups"] == 1
    assert set(report["groups"][0]["files"]) == set(paths)
    (tmp_path / paths[0]).unlink()
    response = client.post("/api/disk/scan_duplicates", headers=headers,
                           json={"async": False, "path": "/webdav/Indexed", "min_size": 0, "force": True})
    assert response.status_code == 503
    assert "read_errors=1" in response.get_json()["error"]
