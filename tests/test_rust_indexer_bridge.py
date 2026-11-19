#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tests/test_rust_indexer_bridge.py
Kiểm thử module cầu nối Rust nas_indexer theo chuẩn rust-anything và Ponytail.
Đảm bảo luồng JSON xuất từ Rust khớp chuẩn và fallback an toàn.
"""

import os
import sys
import json
import sqlite3
import tempfile
import time
import pytest

backend_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend"))
if backend_dir not in sys.path:
    sys.path.insert(0, backend_dir)

import rust_bridge


def _create_sample_db(db_path):
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY, name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT)")
        conn.execute("CREATE TABLE index_state(id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, ready INTEGER, scanned_at REAL, catalogue_revision INTEGER)")
        conn.execute("INSERT INTO index_state VALUES(1, '/media/shared', 42, 1, 1795000000.0, 99)")
        rows = [
            ("documents/report.pdf", "report.pdf", 0, 10240, 1795000100000, "application/pdf"),
            ("photos/vacation.jpg", "vacation.jpg", 0, 204800, 1795000200000, "image/jpeg"),
            ("music/song.mp3", "song.mp3", 0, 5242880, 1795000300000, "audio/mpeg"),
        ]
        conn.executemany("INSERT INTO file_index VALUES(?, ?, ?, ?, ?, ?)", rows)
        conn.commit()
    finally:
        conn.close()


def test_indexer_binary_available():
    """Kiểm tra binary nas_indexer có sẵn và nhận diện được."""
    avail = rust_bridge.is_indexer_accelerated()
    assert avail is True, "Rust nas_indexer binary should be available on dev machine"


def test_stream_fast_index_output():
    """Kiểm tra stream_fast_index đọc đúng cấu trúc JSON từ DB."""
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
        db_path = f.name

    try:
        _create_sample_db(db_path)
        stream = rust_bridge.stream_fast_index(db_path)
        assert stream is not None, "stream_fast_index should return a stream object"

        chunks = []
        for chunk in stream:
            chunks.append(chunk)

        full_json = "".join(chunks)
        data = json.loads(full_json)

        assert data["catalogue_protocol"] == 1
        assert data["source"] == "file_index"
        assert data["index_ready"] is True
        assert data["index_generation"] == 42
        assert data["total"] == 3
        assert len(data["files"]) == 3
        assert data["files"][0]["path"] == "/webdav/documents/report.pdf"
        assert data["files"][1]["name"] == "vacation.jpg"
    finally:
        if os.path.isfile(db_path):
            try:
                os.remove(db_path)
            except Exception:
                pass


def test_stream_fast_index_fallback_on_missing_db():
    """Kiểm tra trả về None an toàn khi DB không tồn tại."""
    fake_db = os.path.join(tempfile.gettempdir(), "non_existent_fast_index_9999.db")
    stream = rust_bridge.stream_fast_index(fake_db)
    assert stream is None, "stream_fast_index must return None on missing DB to trigger fallback"


def test_stream_fast_index_early_close():
    """Kiểm tra đóng sớm stream không gây rò rỉ tiến trình."""
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
        db_path = f.name

    try:
        _create_sample_db(db_path)
        stream = rust_bridge.stream_fast_index(db_path)
        assert stream is not None
        # Chỉ đọc 1 chunk rồi đóng ngay
        first = next(stream)
        assert len(first) > 0
        stream.close()
        assert stream._closed is True
    finally:
        if os.path.isfile(db_path):
            try:
                os.remove(db_path)
            except Exception:
                pass
