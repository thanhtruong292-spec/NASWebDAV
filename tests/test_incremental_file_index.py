"""Exercise the production index/search functions with real directories and SQLite."""
import ast
import datetime
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
import uuid
import types

from flask import Flask, jsonify, request
import pytest
from backend import nas_work_coordinator


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
        "_scan_file_index_pass", "_run_file_index_job", "_refresh_file_index_for_search", "_index_mutated_directories",
        "_background_file_index_refresh", "_mark_file_index_unready",
        "_index_storage_boundary", "_index_resolve_directory",
        "_index_directory_stamp", "_index_readiness", "api_fast_search", "api_photos_timeline", "api_recent_media", "api_disk_trash_batch",
    }
    tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
    nodes = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
    namespace = dict(
        sqlite3=sqlite3, os=os, time=time, mimetypes=mimetypes, datetime=datetime,
        _IMAGE_EXTS={".jpg", ".heif"}, _VIDEO_EXTS={".mp4"}, FILE_INDEX_FRESHNESS_SECONDS=60, requires_auth=lambda func: func,
        threading=threading, uuid=uuid, _index_mutation_wake=threading.Event(),
        _index_lock=threading.Lock(), INDEX_DB_PATH=str(tmp_path / "index.sqlite"),
        _index_scan_lock=threading.Lock(),
        nas_work_coordinator=nas_work_coordinator,
        _background_heavy_work_allowed=lambda ignore_user_idle=False: True,
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


def test_mutation_batch_reindexes_in_place_overwrite_and_move_into_new_directory(engine):
    ns, root, _ = engine
    (root / "a").mkdir()
    (root / "a" / "keep.jpg").write_bytes(b"old")
    (root / "a" / "move.jpg").write_bytes(b"move")
    scan = ns["_scan_file_index"]
    assert scan(str(root))["index_ready"]
    (root / "a" / "keep.jpg").write_bytes(b"replacement")
    (root / "b" / "new").mkdir(parents=True)
    (root / "a" / "move.jpg").rename(root / "b" / "new" / "moved.jpg")
    ns["_index_mutated_directories"]([str(root / "a"), str(root / "b" / "new")])
    assert ns["_index_mutation_wake"].is_set()
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        assert conn.execute("SELECT ready FROM index_state").fetchone() == (0,)
        assert scan(str(root))["index_ready"]
        assert dict(conn.execute("SELECT path,size FROM file_index WHERE is_dir=0")) == {
            "a/keep.jpg": 11, "b/new/moved.jpg": 4,
        }
        (root / "a" / "keep.jpg").write_bytes(b"x")
        ns["_index_mutated_directories"]([str(root / "a")])
        assert scan(str(root))["index_ready"]
        assert conn.execute("SELECT size FROM file_index WHERE path='a/keep.jpg'").fetchone() == (1,)


def test_unchanged_scan_preserves_catalogue_revision(engine):
    ns, root, _ = engine
    (root / 'first.jpg').write_bytes(b'image')
    with sqlite3.connect(ns['INDEX_DB_PATH']) as conn:
        ns['_scan_file_index'](str(root))
        revision = nas_work_coordinator.catalogue_revision(conn)
        assert revision > 0
        ns['_scan_file_index'](str(root))
        assert nas_work_coordinator.catalogue_revision(conn) == revision


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
    client.get("/api/search?q=new")
    with ns['_index_scan_lock']:
        pass
    updated = client.get("/api/search?q=new").get_json()
    assert updated["index_ready"] is True
    assert [item["name"] for item in updated["results"]] == ["new.txt"]
    added.unlink()
    clock[0] += 6
    client.get("/api/search?q=new")
    with ns['_index_scan_lock']:
        pass
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
            stages = {signals.get(timeout=30)[0], signals.get(timeout=30)[0]}
            assert stages == {'enumerating', 'finished'}
            concurrent = client.get("/api/search?q=new").get_json()
            assert concurrent["index_ready"] is False
            assert concurrent["index_incomplete_reason"] == "scan_in_progress"
            assert len(calls) == 1
        finally:
            release.set()
            owner.join(30)
            with ns['_index_scan_lock']:
                pass
    assert not owner.is_alive()
    assert responses[0]["index_ready"] is False
    assert [item["name"] for item in client.get('/api/search?q=new').get_json()['results']] == ['new.txt']
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


def test_search_refresh_ignores_only_its_own_interactive_idle_gate(engine):
    ns, root, _ = engine
    seen = []
    ns["_run_file_index_job"] = lambda search_root, ignore_user_idle=False: seen.append((search_root, ignore_user_idle))
    ns["_index_scan_lock"].acquire()
    ns["_background_file_index_refresh"](str(root))
    assert seen == [(str(root), True)]
    assert not ns["_index_scan_lock"].locked()


def test_nas_busy_refresh_marks_previous_catalogue_unready(engine):
    ns, root, _ = engine
    assert ns["_scan_file_index"](str(root))["index_ready"] is True
    ns["_background_heavy_work_allowed"] = lambda ignore_user_idle=False: False
    result = ns["_run_file_index_job"](str(root), ignore_user_idle=True)
    assert result["index_incomplete_reason"] == "nas_busy"
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        ready, reason = conn.execute("SELECT ready,incomplete_reason FROM index_state WHERE id=1").fetchone()
    assert ready == 0
    assert reason == "nas_busy"


def test_index_pass_pauses_and_releases_lease_when_nas_becomes_busy(engine):
    ns, root, _ = engine
    for number in range(24):
        (root / ("d%02d" % number)).mkdir()
    gates = []
    def gate(ignore_user_idle=False):
        gates.append(ignore_user_idle)
        return len(gates) == 1
    ns["_background_heavy_work_allowed"] = gate
    result = ns["_run_file_index_job"](str(root))
    assert result["work_state"] == "paused"
    assert result["index_incomplete_reason"] == "nas_busy"
    assert gates == [False, False]
    jobs = nas_work_coordinator.get_status(ns["INDEX_DB_PATH"], "index")
    assert jobs[0]["state"] == "paused"


def test_warm_search_never_waits_for_directory_refresh(engine):
    ns, root, client = engine
    (root / 'kept.txt').write_text('cached')
    ns['_scan_file_index'](str(root))
    with sqlite3.connect(ns['INDEX_DB_PATH']) as conn:
        conn.execute('UPDATE index_state SET scanned_at=0')
    jobs = []
    class DeferredThread:
        def __init__(self, target, args, daemon):
            jobs.append((target,args))
        def start(self):
            pass
    ns['threading'] = types.SimpleNamespace(Thread=DeferredThread)
    ns['_scan_file_index'] = lambda *args: pytest.fail('API request performed directory scanning inline')
    response = client.get('/api/search?q=kept').get_json()
    assert [item['name'] for item in response['results']] == ['kept.txt']
    assert response['index_ready'] is False
    assert len(jobs) == 1
    assert ns['_index_scan_lock'].locked()


def test_global_search_avoids_sorting_the_entire_file_catalogue(engine):
    ns, root, client = engine
    with sqlite3.connect(ns['INDEX_DB_PATH']) as conn:
        plan = conn.execute('EXPLAIN QUERY PLAN SELECT path,name,is_dir,size,mtime,mime FROM file_index WHERE norm_name LIKE ? ORDER BY is_dir DESC,name ASC LIMIT 300', ('%mp4%',)).fetchall()
    assert not any('TEMP B-TREE' in row[3] for row in plan)


def test_timeline_uses_shared_catalogue_for_month_pages_and_root_changes(engine):
    ns, root, client = engine
    stamp = datetime.datetime(2024, 2, 10).timestamp()
    for name in ("a.JPG", "b.heif", "c.mp4"):
        (root / name).write_bytes(b"photo")
        os.utime(str(root / name), (stamp, stamp))
    ns["_scan_file_index"](str(root))
    response = client.get("/api/photos/timeline?month=2024-02&limit=1&offset=1")
    assert response.status_code == 200
    assert response.json["total"] == 2
    assert response.json["items"] == [{"path": "b.heif", "size": 5, "mtime": int(stamp)}]
    assert client.get("/api/photos/timeline?month=2024-03").json["items"] == []
    ns["_get_webdav_public_dir"] = lambda: str(root / "other")
    ns["_refresh_file_index_for_search"] = lambda: None
    pending = client.get("/api/photos/timeline").json
    assert pending["scanning"] and pending["items"] == []


def test_trash_directory_removes_nested_hashes_in_both_path_formats(engine):
    ns, root, client = engine
    (root / "album").mkdir()
    (root / "album" / "photo.jpg").write_bytes(b"photo")
    ns["_scan_file_index"](str(root))
    ns["_trash_dir_for_relative_path"] = lambda *_: str(root / ".trash")
    with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
        for table in ("file_hashes", "verified_file_hashes"):
            conn.execute("CREATE TABLE IF NOT EXISTS " + table + "(path TEXT)")
            conn.executemany("INSERT INTO " + table + "(path) VALUES (?)",
                             [(p,) for p in ("album/photo.jpg", "/webdav/album/photo.jpg", "album2/photo.jpg")])
        conn.commit()
        response = client.post("/api/disk/trash_batch", json={"files": ["/webdav/album"]})
        assert response.json["success_count"] == 1
        for table in ("file_hashes", "verified_file_hashes"):
            assert conn.execute("SELECT path FROM " + table).fetchall() == [("album2/photo.jpg",)]


def test_recent_media_includes_nested_items_scopes_and_orders_in_milliseconds(engine):
    ns, root, client = engine
    album = root / "album"
    album.mkdir()
    (root / "album2").mkdir()
    for path in (album / "older.jpg", album / "new.JPG", album / "clip.mp4", root / "album2" / "outside.jpg"):
        path.write_bytes(b"media")
        os.utime(str(path), (100 if path.name == "older.jpg" else 200,) * 2)
    ns["_scan_file_index"](str(root))
    def recent(kind):
        return client.get("/api/media/recent", query_string={"kind": kind, "root": "album"}).json
    images = recent("image")
    assert images["index_ready"] and images["source"] == "file_index"
    assert [row["path"] for row in images["items"]] == ["/webdav/album/new.JPG", "/webdav/album/older.jpg"]
    assert images["items"][0]["mtime"] == 200000
    assert [row["name"] for row in recent("video")["items"]] == ["clip.mp4"]
    ns["_get_webdav_public_dir"] = lambda: str(root / "another")
    ns["_refresh_file_index_for_search"] = lambda: None
    assert recent("image") == {"source": "file_index", "index_ready": False, "items": []}
