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


@pytest.mark.skipif(not rust_bridge.is_indexer_accelerated(), reason="Rust nas_indexer binary not available on this host")
def test_indexer_binary_available():
    """Kiểm tra binary nas_indexer có sẵn và nhận diện được."""
    avail = rust_bridge.is_indexer_accelerated()
    assert avail is True, "Rust nas_indexer binary should be available on dev machine"


@pytest.mark.skipif(not rust_bridge.is_indexer_accelerated(), reason="Rust nas_indexer binary not available on this host")
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


def test_search_c_abi_available():
    """Kiểm tra thư viện C-ABI nas_indexer_core (xóa dấu & tìm kiếm) khả dụng."""
    assert rust_bridge.is_search_accelerated() is True


def test_remove_diacritics_ground_truth_parity():
    """Đối soát 100% byte-for-byte giữa Rust C-ABI remove_diacritics và Python ground truth."""
    cases_path = os.path.join(
        os.path.dirname(__file__), "..", "backend", "rust_modules", "nas_indexer", "diacritics_cases.jsonl"
    )
    assert os.path.isfile(cases_path), "diacritics_cases.jsonl must exist"
    with open(cases_path, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            case = json.loads(line)
            assert rust_bridge.remove_diacritics(case["input"]) == case["expected"]
            assert rust_bridge.remove_diacritics(case["input_lower"]) == case["expected_lower"]


def test_search_file_index_parity_and_wildcards():
    """Kiểm tra tìm kiếm tiếng Việt không dấu, ký tự wildcard %_ và phân vùng root."""
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
        db_path = f.name

    try:
        conn = sqlite3.connect(db_path)
        try:
            conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT)")
            rows = [
                ("Tài liệu/Ảnh 100%_+.jpg", "Ảnh 100%_+.jpg", "anh 100%_+.jpg", 0, 123, 1000, "image/jpeg"),
                ("Tài liệu/Ảnh 100xx+.jpg", "Ảnh 100xx+.jpg", "anh 100xx+.jpg", 0, 123, 1000, "image/jpeg"),
                ("Other/Báo cáo quý 4.pdf", "Báo cáo quý 4.pdf", "bao cao quy 4.pdf", 0, 456, 2000, "application/pdf"),
            ]
            conn.executemany("INSERT INTO file_index VALUES(?, ?, ?, ?, ?, ?, ?)", rows)
            conn.commit()
        finally:
            conn.close()

        # Wildcard % và _ phải là ký tự thực (không match 100xx+)
        res_wild = rust_bridge.search_file_index(db_path, "ảnh 100%_+", "", 300)
        assert res_wild is not None and len(res_wild) == 1
        assert res_wild[0][1] == "Ảnh 100%_+.jpg"

        # Tìm kiếm có dấu & không dấu
        res_vn = rust_bridge.search_file_index(db_path, "Báo Cáo", "", 300)
        assert res_vn is not None and len(res_vn) == 1
        assert res_vn[0][0] == "Other/Báo cáo quý 4.pdf"

        # Lọc theo root scope
        res_scoped = rust_bridge.search_file_index(db_path, "ảnh", "Other", 300)
        assert res_scoped == []

        # Fallback an toàn khi thiếu DB
        assert rust_bridge.search_file_index(db_path + ".missing", "abc") is None
    finally:
        if os.path.isfile(db_path):
            try:
                os.remove(db_path)
            except Exception:
                pass
