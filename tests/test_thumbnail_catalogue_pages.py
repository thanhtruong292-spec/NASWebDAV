"""Thumbnail discovery must consume the canonical NAS file index in bounded pages."""
import ast
import os
from pathlib import Path
import sqlite3

import pytest


SOURCE = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"


@pytest.fixture
def page_reader(tmp_path):
    database = tmp_path / "index.sqlite"
    with sqlite3.connect(database) as conn:
        conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY,name TEXT,is_dir INTEGER)")
        conn.executemany("INSERT INTO file_index VALUES(?,?,0)", [
            ("a/one.jpg", "one.jpg"), ("a/two.mp4", "two.mp4"), ("b/three.png", "three.png")])
        conn.execute("CREATE TABLE index_state(id INTEGER PRIMARY KEY,root TEXT,ready INTEGER,catalogue_revision INTEGER)")
        conn.execute("INSERT INTO index_state VALUES(1,?,1,9)", (str(tmp_path / "public"),))
    node = next(node for node in ast.parse(SOURCE.read_text(encoding="utf-8")).body
                if isinstance(node, ast.FunctionDef) and node.name == "_thumbnail_catalogue_page")
    namespace = {"sqlite3": sqlite3, "os": os, "INDEX_DB_PATH": str(database),
                 "_cfg_sqlite_timeout": lambda _name, default: default}
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), "exec"), namespace)
    return namespace["_thumbnail_catalogue_page"]


def test_reads_all_rows_as_ordered_bounded_pages(page_reader):
    first = page_reader("", 2)
    second = page_reader(first["rows"][-1][0], 2)
    assert first["ready"] and second["ready"]
    assert first["root"] == second["root"]
    assert first["revision"] == second["revision"] == 9
    assert [row[0] for row in first["rows"] + second["rows"]] == [
        "a/one.jpg", "a/two.mp4", "b/three.png"]
    assert len(first["rows"]) == 2


def test_rejects_unsafe_cursor_and_page_size(page_reader):
    with pytest.raises(ValueError):
        page_reader("../outside", 2)
    with pytest.raises(ValueError):
        page_reader("", 5000)
