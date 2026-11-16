"""Leased computer work can only read a fresh bounded page from file_index."""
import ast
from pathlib import Path
import os
import re
import sqlite3
import threading
import time

from flask import Flask, jsonify, request
import pytest

from backend import nas_work_coordinator as work


SOURCE = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"


@pytest.fixture
def work_client(tmp_path):
    database = tmp_path / "nas_fast_index.db"
    root = tmp_path / "public"
    root.mkdir()
    now = time.time()
    with sqlite3.connect(database) as conn:
        conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY,name TEXT,is_dir INTEGER,size INTEGER,mtime INTEGER,mime TEXT)")
        conn.executemany("INSERT INTO file_index VALUES(?,?,0,1,2,'application/octet-stream')", [
            ("a.jpg", "a.jpg"), ("b.mp4", "b.mp4"), ("notes.txt", "notes.txt")])
        conn.execute("CREATE TABLE index_state(id INTEGER PRIMARY KEY,root TEXT,generation INTEGER,ready INTEGER,scanned_at REAL,catalogue_revision INTEGER)")
        conn.execute("INSERT INTO index_state VALUES(1,?,7,1,?,11)", (str(root), now))
    work.initialize(str(database))
    tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
    node = next(item for item in tree.body if isinstance(item, ast.FunctionDef) and item.name == "api_nas_work_files")
    node.decorator_list = []
    app = Flask(__name__)
    namespace = {
        "request": request, "jsonify": jsonify, "sqlite3": sqlite3, "os": os, "time": time,
        "nas_work_coordinator": work, "INDEX_DB_PATH": str(database),
        "_cfg_sqlite_timeout": lambda _name, default: default,
        "_get_webdav_public_dir": lambda: str(root),
        "_index_scan_lock": threading.Lock(),
        "_refresh_file_index_for_search": lambda: None,
        "FILE_INDEX_FRESHNESS_SECONDS": 60,
        "MEDIA_ALL_EXTS": {".jpg", ".mp4"},
    }
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), namespace)
    app.add_url_rule("/api/work/files", view_func=namespace["api_nas_work_files"], methods=["POST"])
    work.request_job(str(database), "thumbnail", str(root), priority=20, now=now)
    lease = work.claim_next(str(database), "client:pc", True, now=now + 1, job_name="thumbnail")
    return app.test_client(), database, lease


def test_thumbnail_worker_reads_ordered_bounded_shared_catalogue(work_client):
    client, database, lease = work_client
    body = {"name": "thumbnail", "owner": lease["owner"], "token": lease["token"],
            "cursor": "", "limit": 1, "catalogue_revision": 11}
    first = client.post("/api/work/files", json=body)
    assert first.status_code == 200
    assert first.json["source"] == "file_index"
    assert first.json["total"] == 2
    assert len(first.json["files"]) == 1
    assert first.json["next_cursor"] == "a.jpg"
    body["cursor"] = first.json["next_cursor"]
    body["include_total"] = False
    second = client.post("/api/work/files", json=body)
    assert second.status_code == 200
    assert second.json["total"] is None
    assert second.json["files"][0]["path"] == "/webdav/b.mp4"
    assert second.json["eof"] is False
    body["cursor"] = second.json["next_cursor"]
    third = client.post("/api/work/files", json=body)
    assert third.status_code == 200
    assert third.json["files"] == []
    assert third.json["eof"] is True
    with sqlite3.connect(database) as conn:
        conn.execute("UPDATE index_state SET catalogue_revision=12")
    assert client.post("/api/work/files", json=body).status_code == 409


def test_work_file_page_rejects_expired_or_foreign_lease(work_client):
    client, _database, lease = work_client
    body = {"name": "thumbnail", "owner": "client:other", "token": lease["token"], "cursor": "", "limit": 2}
    assert client.post("/api/work/files", json=body).status_code == 409


def test_checkpoint_pauses_and_releases_lease_when_nas_becomes_busy(tmp_path):
    database = tmp_path / "nas_fast_index.db"
    root = tmp_path / "public"
    root.mkdir()
    with sqlite3.connect(database) as conn:
        conn.execute("CREATE TABLE index_state(id INTEGER PRIMARY KEY,root TEXT,catalogue_revision INTEGER)")
        conn.execute("INSERT INTO index_state VALUES(1,?,11)", (str(root),))
    work.initialize(str(database))
    work.request_job(str(database), "duplicates", str(root), priority=10)
    lease = work.claim_next(str(database), "client:phone", True, job_name="duplicates")
    gate = {"allowed": True}
    tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
    node = next(item for item in tree.body if isinstance(item, ast.FunctionDef) and item.name == "api_nas_work_checkpoint")
    node.decorator_list = []
    namespace = {
        "request": request, "jsonify": jsonify, "nas_work_coordinator": work,
        "INDEX_DB_PATH": str(database), "_cfg_sqlite_timeout": lambda _name, default: default,
        "_re_module": re,
        "_background_heavy_work_allowed": lambda: gate["allowed"],
    }
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), namespace)
    app = Flask(__name__)
    app.add_url_rule("/api/work/checkpoint", view_func=namespace["api_nas_work_checkpoint"], methods=["POST"])
    client = app.test_client()
    body = {"name": "duplicates", "owner": lease["owner"], "token": lease["token"],
            "cursor": "file-25", "completed": 25, "total": 100, "state": "running"}

    assert client.post("/api/work/checkpoint", json=body).json["accepted"] is True
    gate["allowed"] = False
    paused = client.post("/api/work/checkpoint", json=body)
    assert paused.status_code == 423
    assert work.get_status(str(database), "duplicates")[0]["state"] == "paused"
    assert not work.owns_lease(str(database), "duplicates", lease["owner"], lease["token"])
    assert client.post("/api/work/checkpoint", json=body).status_code == 409

def test_duplicate_pages_exclude_unique_sizes(work_client):
    client, database, thumbnail = work_client
    work.checkpoint(str(database), "thumbnail", thumbnail["owner"], thumbnail["token"],
                    "", 0, 0, state="complete")
    with sqlite3.connect(database) as conn:
        conn.execute("UPDATE file_index SET size=10 WHERE path='notes.txt'")
        root = conn.execute("SELECT root FROM index_state").fetchone()[0]
    work.request_job(str(database), "duplicates", root)
    lease = work.claim_next(str(database), "client:pc", True, job_name="duplicates")
    page = client.post("/api/work/files", json={"name":"duplicates", "owner":lease["owner"],
                       "token":lease["token"]}).json
    assert page["total"] == 2
    assert [row["name"] for row in page["files"]] == ["a.jpg", "b.mp4"]
