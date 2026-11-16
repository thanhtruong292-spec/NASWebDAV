"""Thumbnail retry metadata shares the NAS catalogue database."""
import ast
import hashlib
import io
import os
from pathlib import Path
import sqlite3
import time
import tempfile

import pytest
from flask import Flask, jsonify, request


SOURCE = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"


@pytest.fixture
def thumbnail_store(tmp_path):
    legacy = tmp_path / "nas_api.db"
    with sqlite3.connect(legacy) as conn:
        conn.execute("CREATE TABLE thumb_neg_cache(path TEXT PRIMARY KEY,ts REAL)")
        conn.execute("INSERT INTO thumb_neg_cache VALUES('/srv/webdav/a.mp4',10.0)")
    database = tmp_path / "nas_fast_index.db"
    node = next(node for node in ast.parse(SOURCE.read_text(encoding="utf-8")).body
                if isinstance(node, ast.FunctionDef) and node.name == "_thumb_neg_db")
    namespace = {"sqlite3": sqlite3, "os": os, "time": time,
                 "INDEX_DB_PATH": str(database), "DB_PATH": str(legacy),
                 "_cfg_sqlite_timeout": lambda _name, default: default}
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), namespace)
    return namespace["_thumb_neg_db"], database, legacy


def test_legacy_thumbnail_retry_cache_moves_into_shared_database(thumbnail_store):
    open_store, database, legacy = thumbnail_store
    conn = open_store()
    try:
        assert conn.execute("SELECT path,ts FROM thumb_neg_cache").fetchone() == ("/srv/webdav/a.mp4", 10.0)
        assert conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='thumbnail_cache_migrations'").fetchone()
    finally:
        conn.close()
    with sqlite3.connect(database) as conn:
        conn.execute("INSERT INTO thumb_neg_cache VALUES('/srv/webdav/b.mp4',20.0)")
    conn = open_store()
    try:
        assert conn.execute("SELECT COUNT(*) FROM thumb_neg_cache").fetchone()[0] == 2
        assert os.path.exists(legacy)
    finally:
        conn.close()


def test_thumbnail_upload_publishes_atomically_and_checks_the_source(tmp_path):
    app = Flask(__name__)
    src = tmp_path / "photo.jpg"
    target = tmp_path / "thumb.jpg"
    src.write_bytes(b"original")
    functions = {"_duplicate_stat_version", "_publish_thumb", "_thumb_current", "api_thumb_upload"}
    nodes = [node for node in ast.parse(SOURCE.read_text(encoding="utf-8")).body
             if isinstance(node, ast.FunctionDef) and node.name in functions]
    context = dict(os=os, hashlib=hashlib, tempfile=tempfile, app=app, request=request, jsonify=jsonify,
                   requires_auth=lambda f: f, _resolve_webdav_request_path=lambda _p: str(src),
                   get_webdav_root=lambda: str(tmp_path), _get_thumb_path=lambda *_: str(target))
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), "exec"), context)
    jpeg = b"\xff\xd8" + b"x" * 2000
    version = context["_duplicate_stat_version"](src.stat())
    client = app.test_client()

    def upload():
        return client.post("/api/thumb/upload", data={"path": "/webdav/photo.jpg", "source_version": version,
                           "thumb": (io.BytesIO(jpeg), "photo.jpg", "image/jpeg")})

    assert upload().status_code == 200
    assert context["_thumb_current"](str(src), str(target), 1200)

    def change_source(staged):
        Path(staged).write_bytes(b"partial")
        assert target.read_bytes() == jpeg
        src.write_bytes(b"replaced")
        return True

    with pytest.raises(ValueError, match="source_version_changed"):
        context["_publish_thumb"](str(src), str(target), change_source)
    assert upload().status_code == 409
    assert target.read_bytes() == jpeg and not list(tmp_path.glob(".thumb-*"))
    os.utime(str(src), (time.time() + 2, time.time() + 2))
    assert not context["_thumb_current"](str(src), str(target), 1200)
