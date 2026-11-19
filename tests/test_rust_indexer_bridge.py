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


def test_fts5_trigram_sidecar_build_and_search():
    """Kiểm tra xây dựng sidecar FTS5 trigram và đối soát kết quả tìm kiếm 100%."""
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
        db_path = f.name
    fts_path = db_path + ".fts.db"

    try:
        conn = sqlite3.connect(db_path)
        try:
            conn.execute("CREATE TABLE index_state(id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, ready INTEGER, scanned_at REAL, catalogue_revision INTEGER)")
            conn.execute("INSERT INTO index_state VALUES(1, '/srv/data', 5, 1, 1795000000.0, 12)")
            conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY, name TEXT, norm_name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT)")
            rows = [
                ("Tài liệu/Báo cáo tài chính 2026.pdf", "Báo cáo tài chính 2026.pdf", "bao cao tai chinh 2026.pdf", 0, 1024, 1000, "application/pdf"),
                ("Ảnh/Ảnh 100%_+.jpg", "Ảnh 100%_+.jpg", "anh 100%_+.jpg", 0, 2048, 2000, "image/jpeg"),
                ("Ảnh/Ảnh 100xx+.jpg", "Ảnh 100xx+.jpg", "anh 100xx+.jpg", 0, 2048, 2000, "image/jpeg"),
            ]
            conn.executemany("INSERT INTO file_index VALUES(?, ?, ?, ?, ?, ?, ?)", rows)
            conn.commit()
        finally:
            conn.close()

        built = rust_bridge.build_fts_index(db_path)
        assert built == 3
        assert os.path.isfile(fts_path)

        res = rust_bridge.search_file_index(db_path, "tài chính", "", 300)
        assert res is not None and len(res) == 1
        assert res[0][1] == "Báo cáo tài chính 2026.pdf"

        # Wildcard phải giữ nguyên nghĩa literal kể cả khi dùng FTS5 trigram
        res_wild = rust_bridge.search_file_index(db_path, "100%_+", "Ảnh", 300)
        assert res_wild is not None and len(res_wild) == 1
        assert res_wild[0][1] == "Ảnh 100%_+.jpg"
    finally:
        for p in (db_path, fts_path):
            if os.path.isfile(p):
                try:
                    os.remove(p)
                except Exception:
                    pass


def test_compute_storage_summary_parity(tmp_path):
    """Kiểm tra thống kê lưu trữ Rust C-ABI khớp 100% logic Python os.path.splitext + top 10."""
    db_path = str(tmp_path / "storage.db")
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY, name TEXT, is_dir INTEGER, size INTEGER)")
        conn.executemany("INSERT INTO file_index VALUES(?, ?, ?, ?)", [
            ("photos/", "photos", 1, 0),
            ("photos/a.JPG", "a.JPG", 0, 500),
            ("videos/b.mp4", "b.mp4", 0, 5000),
            ("videos/a.mp4", "a.mp4", 0, 5000),  # tie-break path ASC -> videos/a.mp4 truoc videos/b.mp4
            ("docs/c.pdf", "c.pdf", 0, 200),
            ("music/d.mp3", "d.mp3", 0, 300),
            ("arch/e.tar.gz", "e.tar.gz", 0, 400),
            (".jpg", ".jpg", 0, 100),  # dotfile -> other
        ])
        conn.commit()
    finally:
        conn.close()

    res = rust_bridge.compute_storage_summary(db_path)
    assert res is not None
    assert res["total_files"] == 7
    assert res["total_dirs"] == 1
    assert res["total_bytes"] == 11500
    assert res["by_type"]["image"] == {"files": 1, "bytes": 500}
    assert res["by_type"]["video"] == {"files": 2, "bytes": 10000}
    assert res["by_type"]["doc"] == {"files": 1, "bytes": 200}
    assert res["by_type"]["audio"] == {"files": 1, "bytes": 300}
    assert res["by_type"]["archive"] == {"files": 1, "bytes": 400}
    assert res["by_type"]["other"] == {"files": 1, "bytes": 100}
    assert res["largest"][0] == {"path": "videos/a.mp4", "bytes": 5000}
    assert res["largest"][1] == {"path": "videos/b.mp4", "bytes": 5000}


def test_scan_directory_entries_and_media_queries(tmp_path):
    """Kiểm tra quét thư mục (Ưu tiên 6) và truy vấn recent_media/photos_timeline (Ưu tiên 7) qua Rust C-ABI."""
    (tmp_path / "SubDir").mkdir()
    (tmp_path / ".hidden").mkdir()
    (tmp_path / "Ảnh Tết 2026.JPG").write_bytes(b"imgdata")
    (tmp_path / ".skip.jpg").write_bytes(b"skip")

    scan = rust_bridge.scan_directory_entries(str(tmp_path), "album/")
    assert scan is not None
    assert len(scan["entries"]) == 1
    entry = scan["entries"][0]
    assert entry[0] == "album/Ảnh Tết 2026.JPG"
    assert entry[2] == "anh tet 2026.jpg"
    assert entry[4] == 7
    assert entry[6] == "image/jpeg"
    assert scan["special"] == ["SubDir"]

    db_path = str(tmp_path / "media.db")
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY, name TEXT, is_dir INTEGER, size INTEGER, mtime INTEGER, mime TEXT)")
        conn.executemany("INSERT INTO file_index VALUES(?, ?, ?, ?, ?, ?)", [
            ("album/a.jpg", "a.jpg", 0, 100, 1700000000000, "image/jpeg"),
            ("album/b.png", "b.png", 0, 200, 1710000000000, "image/png"),
            ("videos/c.mp4", "c.mp4", 0, 300, 1720000000000, "video/mp4"),
            ("docs/d.pdf", "d.pdf", 0, 400, 1730000000000, "application/pdf"),
        ])
        conn.commit()
    finally:
        conn.close()

    recent_img = rust_bridge.query_recent_media(db_path, "image", "", 10)
    assert recent_img is not None and len(recent_img) == 2
    assert recent_img[0]["path"] == "/webdav/album/b.png"
    assert recent_img[1]["path"] == "/webdav/album/a.jpg"

    recent_vid = rust_bridge.query_recent_media(db_path, "video", "videos", 10)
    assert recent_vid is not None and len(recent_vid) == 1
    assert recent_vid[0]["name"] == "c.mp4"

    tl = rust_bridge.query_photos_timeline(db_path, -1, -1, 1, 0)
    assert tl is not None
    assert tl["total"] == 2
    assert len(tl["items"]) == 1
    assert tl["items"][0]["path"] == "album/b.png"
    assert tl["items"][0]["mtime"] == 1710000000
