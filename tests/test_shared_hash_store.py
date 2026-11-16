"""Legacy client hashes migrate into the single NAS index/work database."""
import ast
import logging
import os
from pathlib import Path
import sqlite3
import time

import pytest


SOURCE = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"


@pytest.fixture
def shared_hash_store(tmp_path):
    root = tmp_path / "public"
    legacy_meta = root / ".nas_meta"
    legacy_meta.mkdir(parents=True)
    legacy = legacy_meta / "file_hashes.db"
    with sqlite3.connect(legacy) as conn:
        conn.execute("CREATE TABLE file_hashes(path TEXT PRIMARY KEY,size INTEGER,mtime INTEGER,sha256 TEXT,partial TEXT,scanned_at INTEGER)")
        conn.execute("INSERT INTO file_hashes VALUES('/webdav/a.jpg',10,20,?,'partial',30)", ("a" * 64,))
    node = next(node for node in ast.parse(SOURCE.read_text(encoding="utf-8")).body
                if isinstance(node, ast.FunctionDef) and node.name == "_hash_db")
    database = tmp_path / "nas_fast_index.db"
    namespace = {"sqlite3": sqlite3, "os": os, "time": time,
                 "INDEX_DB_PATH": str(database),
                 "_cfg_sqlite_timeout": lambda _name, default: default,
                 "_get_webdav_public_dir": lambda: str(root),
                 "log": logging.getLogger("hash-store-test")}
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), namespace)
    return namespace["_hash_db"], database, legacy


def test_legacy_hashes_are_migrated_once_into_the_shared_database(shared_hash_store):
    open_store, database, legacy = shared_hash_store
    conn = open_store()
    try:
        row = conn.execute("SELECT path,size,mtime,sha256,partial,scanned_at FROM file_hashes").fetchone()
        tables = set(item[0] for item in conn.execute("SELECT name FROM sqlite_master WHERE type='table'"))
        assert row == ("a.jpg", 10, 20, "a" * 64, "partial", 30)
        assert "file_hash_migrations" in tables
        assert os.path.exists(legacy)
    finally:
        conn.close()
    with sqlite3.connect(database) as conn:
        conn.execute("INSERT OR REPLACE INTO file_hashes VALUES('new.jpg',1,2,?,'',3)", ("b" * 64,))
    conn = open_store()
    try:
        assert conn.execute("SELECT COUNT(*) FROM file_hashes").fetchone()[0] == 2
    finally:
        conn.close()
