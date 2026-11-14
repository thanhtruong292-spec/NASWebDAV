"""Exercise the production index/search functions with real directories and SQLite."""
import ast
import builtins
import logging
import mimetypes
import os
from pathlib import Path
import re
import queue
import sqlite3
import threading
import time
import urllib.parse
import types

from flask import Flask, jsonify, request
import pytest


SOURCE = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"


@pytest.fixture
def root_resolver(tmp_path, monkeypatch):
    config = tmp_path / "config.php"
    conf_path = "/var/www/webdav/config/config.php"
    fallbacks = {"/srv/dev-disk-by-label-data/New folder", "/srv/dev-disk-by-label-data"}
    original_exists, original_isdir, original_lstat = os.path.exists, os.path.isdir, os.lstat
    monkeypatch.setattr(os.path, "exists", lambda path: original_exists(config if path == conf_path else path) or path in fallbacks)
    monkeypatch.setattr(os.path, "isdir", lambda path: path in fallbacks or original_isdir(path))
    monkeypatch.setattr(os, "lstat", lambda path, *args, **kwargs: original_lstat(config if path == conf_path else path, *args, **kwargs))
    node = next(node for node in ast.parse(SOURCE.read_text(encoding="utf-8")).body
                if isinstance(node, ast.FunctionDef) and node.name == "_get_webdav_public_dir")
    ns = {"os": os, "_re_module": re, "log": logging.getLogger("root-test"),
          "open": lambda path, *args, **kwargs: builtins.open(config if path == conf_path else path, *args, **kwargs)}
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), ns)
    return ns, config


def test_explicit_missing_public_root_never_falls_back_to_storage_parent(root_resolver, tmp_path):
    ns, config = root_resolver
    config.write_text("<?php $publicDir = '%s';" % (tmp_path / "missing").as_posix(), encoding="utf-8")
    assert ns["_get_webdav_public_dir"]() == ""


def test_valid_explicit_public_root_is_used(root_resolver, tmp_path):
    ns, config = root_resolver
    public = tmp_path / "public"
    public.mkdir()
    config.write_text("<?php $publicDir = '%s';" % public.as_posix(), encoding="utf-8")
    assert ns["_get_webdav_public_dir"]() == public.as_posix()


def test_existing_config_parse_or_read_errors_fail_closed(root_resolver):
    ns, config = root_resolver
    config.write_text("<?php // no publicDir assignment", encoding="utf-8")
    assert ns["_get_webdav_public_dir"]() == ""
    def denied(*args, **kwargs):
        raise PermissionError("test config unreadable")
    ns["open"] = denied
    assert ns["_get_webdav_public_dir"]() == ""


def test_absent_config_keeps_legacy_root_fallback(root_resolver):
    ns, config = root_resolver
    assert not config.exists()
    assert ns["_get_webdav_public_dir"]() == "/srv/dev-disk-by-label-data/New folder"


def test_uninspectable_config_fails_closed_instead_of_using_legacy_root(root_resolver, monkeypatch):
    ns, config = root_resolver
    original = os.lstat
    def denied(path, *args, **kwargs):
        if path == "/var/www/webdav/config/config.php":
            raise PermissionError(13, "test config denied")
        return original(path, *args, **kwargs)
    monkeypatch.setattr(os, "lstat", denied)
    assert ns["_get_webdav_public_dir"]() == ""


@pytest.fixture
def engine(tmp_path):
    app = Flask(__name__)
    names = {
        "_remove_diacritics_py", "_init_index_db", "_scan_file_index",
        "_scan_file_index_pass", "_refresh_file_index_for_search",
        "_background_file_index_refresh",
        "_index_storage_boundary", "_index_resolve_directory",
        "_index_directory_stamp", "_index_readiness", "api_fast_search",
    }
    tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
    nodes = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
    namespace = dict(
        sqlite3=sqlite3, os=os, time=time, mimetypes=mimetypes,
        threading=threading,
        _index_lock=threading.Lock(), INDEX_DB_PATH=str(tmp_path / "index.sqlite"),
        _index_scan_lock=threading.Lock(),
        _cfg_sqlite_timeout=lambda name, default: default,
        log=logging.getLogger("index-test"), app=app, request=request, jsonify=jsonify,
        urllib=urllib, _get_webdav_port=lambda: 8822,
        _get_webdav_host_default=lambda: "192.168.100.254",
    )
    root = tmp_path / "public"
    root.mkdir()
    namespace["_get_webdav_public_dir"] = lambda: str(root)
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), "exec"), namespace)
    namespace["_init_index_db"]()
    return namespace, root, app.test_client()


def test_second_pass_and_restart_reuse_existing_directory_listings(engine, monkeypatch):
    ns, root, client = engine
    (root / "nested").mkdir()
    (root / "nested" / "Ảnh mới+%.jpg").write_bytes(b"image")
    first = ns["_scan_file_index"](str(root))
    assert first["index_ready"] is True
    assert first["enumerated_dirs"] == 2
    with monkeypatch.context() as patch:
        patch.setattr(os, "listdir", lambda path: pytest.fail("unchanged directory was enumerated"))
        patch.setitem(ns, "_index_resolve_directory", lambda *args: pytest.fail("unchanged directory repeated boundary resolution"))
        second = ns["_scan_file_index"](str(root))
    assert second["enumerated_dirs"] == 0
    ns["_init_index_db"]()  # startup uses the same persistent DB
    assert client.get("/api/search?q=missing").get_json()["index_ready"] is False
    assert ns["_scan_file_index"](str(root))["enumerated_dirs"] == 0
    result = client.get("/api/search", query_string={"q": "anh moi+%"}).get_json()
    assert result["index_ready"] is True
    assert [item["name"] for item in result["results"]] == ["Ảnh mới+%.jpg"]


def test_retargeted_known_alias_is_revalidated_before_enumerating_private_target(engine, monkeypatch):
    ns, root, client = engine
    alias = root / "Downloads"
    alias.mkdir()
    (alias / "public.txt").write_text("public")
    ns["_scan_file_index"](str(root))
    stamp, resolve, listing = ns["_index_directory_stamp"], ns["_index_resolve_directory"], os.listdir
    alias_key = os.path.normcase(os.path.normpath(str(alias)))
    def is_alias(path):
        return os.path.normcase(os.path.normpath(path)) == alias_key
    # Model a symlink replacement with a different target identity. Windows
    # cannot create the actual directory link without additional OS privileges.
    ns["_index_directory_stamp"] = lambda path: "changed-device-inode" if is_alias(path) else stamp(path)
    denied = []
    def outside_boundary(path, root_path, boundary):
        if is_alias(path):
            denied.append(path)
            return None
        return resolve(path, root_path, boundary)
    ns["_index_resolve_directory"] = outside_boundary
    def no_private_read(path):
        if is_alias(path):
            pytest.fail("retargeted private directory was enumerated")
        return listing(path)
    monkeypatch.setattr(os, "listdir", no_private_read)
    result = ns["_scan_file_index"](str(root))
    assert len(denied) == 1
    assert result["index_ready"] is False
    assert client.get("/api/search?q=secret&root=Downloads").get_json()["results"] == []


def test_in_place_file_growth_returns_fresh_metadata_without_reenumerating_directory(engine):
    ns, root, client = engine
    folder = root / "nested"
    folder.mkdir()
    growing = folder / "recording.ts"
    growing.write_bytes(b"old")
    ns["_scan_file_index"](str(root))
    parent_stamp = ns["_index_directory_stamp"](str(folder))
    old_stat = growing.stat()
    with growing.open("ab") as handle:
        handle.write(b"-grown-in-place")
    os.utime(str(growing), (old_stat.st_atime, old_stat.st_mtime + 2))
    assert ns["_index_directory_stamp"](str(folder)) == parent_stamp
    assert ns["_scan_file_index"](str(root))["enumerated_dirs"] == 0
    response = client.get("/api/search?q=recording").get_json()
    assert response["index_ready"] is True
    assert len(response["results"]) == 1
    result = response["results"][0]
    assert result["contentLength"] == growing.stat().st_size
    assert result["lastModified"] == int(growing.stat().st_mtime * 1000)
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        stored = conn.execute("SELECT size,mtime FROM file_index WHERE path=?", ("nested/recording.ts",)).fetchone()
    conn.close()
    assert stored == (result["contentLength"],result["lastModified"])


def test_file_added_in_existing_nested_directory_with_unchanged_parent(engine):
    ns, root, client = engine
    folder = root / "old" / "nested"
    folder.mkdir(parents=True)
    ns["_scan_file_index"](str(root))
    root_stamp = root.stat().st_mtime
    (folder / "new.txt").write_text("new")
    assert root.stat().st_mtime == root_stamp
    result = ns["_scan_file_index"](str(root))
    assert result["enumerated_dirs"] == 1
    found = client.get("/api/search?q=new").get_json()
    assert [item["name"] for item in found["results"]] == ["new.txt"]


def test_new_directory_rename_and_deleted_subtree_are_reconciled(engine):
    ns, root, client = engine
    ns["_scan_file_index"](str(root))
    folder = root / "new-folder"
    folder.mkdir()
    (folder / "sample.txt").write_text("old")
    ns["_scan_file_index"](str(root))
    folder.rename(root / "renamed-folder")
    ns["_scan_file_index"](str(root))
    rows = client.get("/api/search?q=sample").get_json()["results"]
    assert len(rows) == 1
    assert rows[0]["path"].endswith("/renamed-folder/sample.txt")
    (root / "renamed-folder" / "sample.txt").unlink()
    (root / "renamed-folder").rmdir()
    state = ns["_scan_file_index"](str(root))
    assert state["index_ready"] is True
    assert client.get("/api/search?q=sample").get_json()["results"] == []
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        assert conn.execute("SELECT path FROM index_directories").fetchall() == [("",)]


def test_failed_directory_read_preserves_previous_rows_until_success(engine, monkeypatch):
    ns, root, client = engine
    folder = root / "kept"
    folder.mkdir()
    old = folder / "sample.txt"
    old.write_text("old")
    ns["_scan_file_index"](str(root))
    old.unlink()
    original = os.listdir
    def fail_kept(path):
        if os.path.normpath(path) == os.path.normpath(str(folder)):
            raise PermissionError("test denied")
        return original(path)
    with monkeypatch.context() as patch:
        patch.setattr(os, "listdir", fail_kept)
        state = ns["_scan_file_index"](str(root))
    assert state["index_ready"] is False
    stale = client.get("/api/search?q=sample").get_json()
    assert stale["index_ready"] is False
    assert stale["results"] == []
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        assert conn.execute("SELECT COUNT(*) FROM file_index WHERE path='kept/sample.txt'").fetchone()[0] == 1
    assert ns["_scan_file_index"](str(root))["index_ready"] is True
    assert client.get("/api/search?q=sample").get_json()["results"] == []


def test_legacy_upgrade_keeps_provisional_rows_when_root_read_fails(engine, monkeypatch):
    ns, root, client = engine
    (root / "kept.txt").write_text("kept")
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        conn.execute("DROP TABLE file_index")
        conn.execute("CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT)")
        conn.execute("INSERT INTO file_index (path,name,norm_name,is_dir) VALUES ('kept.txt','kept.txt','kept.txt',0)")
    ns["_init_index_db"]()
    with monkeypatch.context() as patch:
        def denied(path):
            raise PermissionError("test denied")
        patch.setattr(os, "listdir", denied)
        assert ns["_scan_file_index"](str(root))["index_ready"] is False
    result = client.get("/api/search?q=kept").get_json()
    assert result["index_ready"] is False
    assert [item["name"] for item in result["results"]] == ["kept.txt"]


def test_empty_results_only_become_authoritative_after_successful_scan(engine):
    ns, root, client = engine
    ns["_index_scan_lock"].acquire()  # background first build already pending
    try:
        assert client.get("/api/search?q=missing").get_json()["index_ready"] is False
    finally:
        ns["_index_scan_lock"].release()
    ns["_scan_file_index"](str(root))
    result = client.get("/api/search?q=missing").get_json()
    assert result["success"] is True
    assert result["results"] == []
    assert result["index_ready"] is True


def test_root_change_hides_stale_results_and_increments_generation(engine, tmp_path):
    ns, root, client = engine
    (root / "old.txt").write_text("old")
    previous = ns["_scan_file_index"](str(root))
    new_root = tmp_path / "new-root"
    new_root.mkdir()
    (new_root / "new.txt").write_text("new")
    ns["_get_webdav_public_dir"] = lambda: str(new_root)
    ns["_index_scan_lock"].acquire()
    try:
        assert client.get("/api/search?q=old").status_code == 503
    finally:
        ns["_index_scan_lock"].release()
    current = ns["_scan_file_index"](str(new_root))
    assert current["index_generation"] == previous["index_generation"] + 1
    assert current["index_ready"] is True
    assert client.get("/api/search?q=old").get_json()["results"] == []
    assert client.get("/api/search?q=new").get_json()["results"][0]["name"] == "new.txt"


def test_directory_symlink_is_not_traversed_or_marked_complete(engine, tmp_path):
    ns, root, client = engine
    outside = tmp_path / "private"
    outside.mkdir()
    (outside / "secret.txt").write_text("private")
    try:
        (root / "Downloads").symlink_to(outside, target_is_directory=True)
    except OSError as exc:
        pytest.skip("OS cannot create test symlink: %s" % exc)
    state = ns["_scan_file_index"](str(root))
    assert state["index_ready"] is False
    result = client.get("/api/search?q=secret&root=Downloads").get_json()
    assert result["results"] == []
    assert result["index_ready"] is False
    assert "symlink" in result["index_incomplete_reason"]
    assert ns["_scan_file_index"](str(root))["index_ready"] is False


def test_storage_boundary_never_expands_to_filesystem_root(engine, tmp_path, monkeypatch):
    ns, root, client = engine
    assert ns["_index_storage_boundary"](str(root)) == ""
    volume = tmp_path / "storage"
    public = volume / "public"
    media = volume / "Media"
    hidden = volume / ".nas_meta"
    other_mount = volume / "other-mount"
    public.mkdir(parents=True)
    media.mkdir()
    hidden.mkdir()
    other_mount.mkdir()
    original = os.path.ismount
    mounts = {os.path.normpath(str(volume)), os.path.normpath(str(other_mount))}
    monkeypatch.setattr(os.path, "ismount", lambda path: os.path.normpath(path) in mounts or original(path))
    assert ns["_index_storage_boundary"](str(public)) == str(volume)
    assert ns["_index_resolve_directory"](str(media), str(public), str(volume)) == str(media)
    assert ns["_index_resolve_directory"](str(tmp_path), str(public), str(volume)) is None
    assert ns["_index_resolve_directory"](str(hidden), str(public), str(volume)) is None
    assert ns["_index_resolve_directory"](str(other_mount), str(public), str(volume)) is None


def test_public_alias_on_same_storage_mount_keeps_logical_search_paths(engine, tmp_path, monkeypatch):
    ns, old_root, client = engine
    volume = tmp_path / "storage"
    root = volume / "public"
    media = volume / "Media"
    root.mkdir(parents=True)
    media.mkdir()
    (media / "nested").mkdir()
    (media / "nested" / "sample.txt").write_text("public")
    try:
        (root / "Downloads").symlink_to(media, target_is_directory=True)
    except OSError as exc:
        pytest.skip("OS cannot create test symlink: %s" % exc)
    original = os.path.ismount
    monkeypatch.setattr(os.path, "ismount", lambda path: os.path.normpath(path) == os.path.normpath(str(volume)) or original(path))
    ns["_get_webdav_public_dir"] = lambda: str(root)
    assert ns["_scan_file_index"](str(root))["index_ready"] is True
    result = client.get("/api/search?q=sample&root=Downloads").get_json()
    assert result["index_ready"] is True
    assert len(result["results"]) == 1
    assert result["results"][0]["path"].endswith("/Downloads/nested/sample.txt")
    assert ns["_scan_file_index"](str(root))["enumerated_dirs"] == 0
    (media / "nested" / "new.txt").write_text("new")
    ns["_scan_file_index"](str(root))
    assert client.get("/api/search?q=new&root=Downloads").get_json()["results"][0]["name"] == "new.txt"


def test_alias_cycles_and_other_mount_private_targets_are_excluded(engine, tmp_path, monkeypatch):
    ns, old_root, client = engine
    volume = tmp_path / "storage"
    root = volume / "public"
    media = volume / "Media"
    private = tmp_path / "private"
    other_mount = volume / "other-mount"
    root.mkdir(parents=True)
    media.mkdir()
    private.mkdir()
    other_mount.mkdir()
    (media / "sample.txt").write_text("public")
    (private / "secret.txt").write_text("private")
    (other_mount / "secret.txt").write_text("private")
    try:
        (root / "Downloads").symlink_to(media, target_is_directory=True)
        (media / "back").symlink_to(root, target_is_directory=True)
        (media / "self").symlink_to(media, target_is_directory=True)
        (root / "private").symlink_to(private, target_is_directory=True)
        (root / "other").symlink_to(other_mount, target_is_directory=True)
    except OSError as exc:
        pytest.skip("OS cannot create test symlink: %s" % exc)
    original = os.path.ismount
    mounts = {os.path.normpath(str(volume)), os.path.normpath(str(other_mount))}
    monkeypatch.setattr(os.path, "ismount", lambda path: os.path.normpath(path) in mounts or original(path))
    ns["_get_webdav_public_dir"] = lambda: str(root)
    state = ns["_scan_file_index"](str(root))
    assert state["index_ready"] is False
    assert state["checked_dirs"] == 2
    assert client.get("/api/search?q=secret").get_json()["results"] == []
    assert len(client.get("/api/search?q=sample&root=Downloads").get_json()["results"]) == 1
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        assert set(row[0] for row in conn.execute("SELECT path FROM index_directories")) == {"", "Downloads/"}


def test_scope_is_exact_and_search_keeps_literal_wildcards_and_accents(engine):
    ns, root, client = engine
    for name in ("Tài liệu+%_", "Tài liệu+%_other", "Other"):
        folder = root / name
        folder.mkdir()
        (folder / "Ảnh 100%_+.jpg").write_bytes(b"a")
        (folder / "Ảnh 100xx+.jpg").write_bytes(b"b")
    ns["_scan_file_index"](str(root))
    result = client.get("/api/search", query_string={"q": "anh 100%_+", "root": "Tài liệu+%_"}).get_json()
    assert result["index_ready"] is True
    assert len(result["results"]) == 1
    assert result["results"][0]["name"] == "Ảnh 100%_+.jpg"
    assert urllib.parse.unquote(result["results"][0]["path"]).endswith("/Tài liệu+%_/Ảnh 100%_+.jpg")
    assert len(client.get("/api/search", query_string={"q": "100%_+"}).get_json()["results"]) == 3
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        sql = next(node.value for node in ast.walk(ast.parse(SOURCE.read_text(encoding="utf-8")))
                   if isinstance(node, ast.Constant) and isinstance(node.value, str)
                   and "WHERE path >= ? AND path < ? AND norm_name LIKE ?" in node.value)
        plan = conn.execute("EXPLAIN QUERY PLAN " + sql, ("Tài liệu+%_/", "Tài liệu+%_0", "%anh%")).fetchall()
        assert any("SEARCH" in row[3] and "path>? AND path<?" in row[3] for row in plan)


def test_search_db_error_is_not_a_successful_authoritative_empty(engine):
    ns, root, client = engine
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        conn.execute("DROP TABLE file_index")
    response = client.get("/api/search?q=missing")
    assert response.status_code == 503
    assert response.get_json()["success"] is False
    assert response.get_json()["index_ready"] is False


def test_filesystem_root_is_rejected(engine):
    ns, root, client = engine
    with pytest.raises(ValueError):
        ns["_scan_file_index"](os.path.abspath(os.sep))


def test_search_refreshes_additions_and_deletions_after_five_seconds(engine, monkeypatch):
    ns, root, client = engine
    clock = [1000.0]
    ns["time"] = types.SimpleNamespace(time=lambda: clock[0], sleep=lambda seconds: None)
    folder = root / "existing" / "nested"
    folder.mkdir(parents=True)
    ns["_scan_file_index"](str(root))
    added = folder / "new.txt"
    added.write_text("new")
    clock[0] += 4
    with monkeypatch.context() as patch:
        patch.setattr(os, "listdir", lambda path: pytest.fail("fresh index was scanned again"))
        fresh = client.get("/api/search?q=new").get_json()
    assert fresh["results"] == []
    assert fresh["index_ready"] is True
    clock[0] += 2
    updated = client.get("/api/search?q=new").get_json()
    assert updated["index_ready"] is True
    assert [item["name"] for item in updated["results"]] == ["new.txt"]
    added.unlink()
    clock[0] += 6
    deleted = client.get("/api/search?q=new").get_json()
    assert deleted["results"] == []
    assert deleted["index_ready"] is True


def test_concurrent_search_uses_one_refresh_and_restores_readiness(engine, monkeypatch):
    ns, root, client = engine
    clock = [1000.0]
    ns["time"] = types.SimpleNamespace(time=lambda: clock[0], sleep=lambda seconds: None)
    folder = root / "existing"
    folder.mkdir()
    ns["_scan_file_index"](str(root))
    (folder / "new.txt").write_text("new")
    clock[0] += 6
    signals = queue.Queue()
    release = threading.Event()
    original = os.listdir
    calls = []
    def blocked(path):
        calls.append(path)
        signals.put(("enumerating", None))
        if not release.wait(30):
            raise OSError("test refresh did not resume")
        return original(path)
    responses = []
    def search():
        try:
            responses.append(client.application.test_client().get("/api/search?q=new").get_json())
        except Exception as exc:
            responses.append(exc)
        finally:
            signals.put(("finished", responses[-1]))
    with monkeypatch.context() as patch:
        patch.setattr(os, "listdir", blocked)
        owner = threading.Thread(target=search)
        owner.start()
        try:
            # Synchronize on enumeration; filesystem/scheduler latency is not
            # the behavior this regression test measures.
            stage, detail = signals.get(timeout=30)
            assert stage == "enumerating", "refresh finished before enumeration: %r" % (detail,)
            concurrent = client.get("/api/search?q=new").get_json()
            assert concurrent["index_ready"] is False
            assert concurrent["index_incomplete_reason"] == "scan_in_progress"
            assert len(calls) == 1
        finally:
            release.set()
            owner.join(30)
    assert not owner.is_alive()
    assert responses[0]["index_ready"] is True
    assert [item["name"] for item in responses[0]["results"]] == ["new.txt"]
    assert client.get("/api/search?q=absent").get_json()["index_ready"] is True


def test_cold_index_search_schedules_background_build_instead_of_blocking(engine):
    ns, root, client = engine
    scheduled = []
    class ThreadCapture:
        def __init__(self, target, args, daemon):
            scheduled.append((target, args, daemon))
        def start(self):
            pass
    ns["threading"] = types.SimpleNamespace(Thread=ThreadCapture)
    response = client.get("/api/search?q=missing").get_json()
    assert response["index_ready"] is False
    assert scheduled == [(ns["_background_file_index_refresh"], (str(root),), True)]
