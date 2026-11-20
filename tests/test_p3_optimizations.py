# -*- coding: utf-8 -*-
"""
Focused tests for P3 optimizations (ANTIGRAVITY-020):
- P3.1: Rust photo_tagger streaming scan (no 400k Python tuples)
- P3.2: Rust TAR / TAR.GZ safe extraction (blocks Tar-Slip & symlinks)
- P3.3: Rust EBML (MKV/WebM) & FLV binary header parser (zero-fork)
"""
import io
import json
import os
import sqlite3
import struct
import sys
import tarfile
import tempfile
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
import nas_api_server
import rust_bridge


def test_p3_photo_tagger_streaming_rust():
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "idx.db")
        json_path = os.path.join(tmp_dir, "ai_tags.json")
        conn = sqlite3.connect(db_path)
        conn.executescript(
            """
            CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, catalogue_revision INTEGER, ready INTEGER, scanned_at REAL);
            CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, size INTEGER, mtime INTEGER, is_dir INTEGER);
            CREATE TABLE ai_image_tags (path TEXT PRIMARY KEY, category TEXT, catalogue_revision INTEGER, updated_at REAL);
            INSERT INTO index_state VALUES (1, '/srv/data', 1, 9, 1, 1800000000.0);
            INSERT INTO file_index VALUES ('Telegram/t1.jpg', 't1.jpg', 100, 1000, 0);
            INSERT INTO file_index VALUES ('DCIM/cam.PNG', 'cam.PNG', 200, 2000, 0);
            INSERT INTO file_index VALUES ('Other/pic.webp', 'pic.webp', 300, 3000, 0);
            INSERT INTO file_index VALUES ('Movies/m.mp4', 'm.mp4', 400, 4000, 0);
            """
        )
        conn.close()

        res = rust_bridge.scan_photo_tags_rust(
            db_path, "/srv/data", json_path, 1800000010.0, 300.0, "2026-11-20 07:10:00"
        )
        assert res is not None
        assert res["success"] is True
        assert res["total"] == 3
        assert res["catalogue_revision"] == 9
        assert os.path.isfile(json_path)
        with open(json_path, "r") as f:
            data = json.load(f)
        assert data["total"] == 3
        assert "Telegram/t1.jpg" in data["categories"]["Mang Xa Hoi"]
        assert "DCIM/cam.PNG" in data["categories"]["Album Dien Thoai"]
        assert "Other/pic.webp" in data["categories"]["Khac"]


def test_p3_extract_tar_gz_safe_and_blocks_traversal():
    with tempfile.TemporaryDirectory() as tmp_dir:
        tgz_ok = os.path.join(tmp_dir, "ok.tar.gz")
        with tarfile.open(tgz_ok, "w:gz") as tf:
            payload = b"Rust TAR.GZ safe extraction"
            ti = tarfile.TarInfo("folder/hello.txt")
            ti.size = len(payload)
            tf.addfile(ti, io.BytesIO(payload))

        dest_ok = os.path.join(tmp_dir, "out_ok")
        os.makedirs(dest_ok)
        cnt = nas_api_server._extract_tar_safe(tgz_ok, dest_ok)
        assert cnt == 1
        with open(os.path.join(dest_ok, "folder", "hello.txt"), "rb") as f:
            assert f.read() == b"Rust TAR.GZ safe extraction"

        # Test chan symlink doc hai
        tgz_bad = os.path.join(tmp_dir, "bad.tar.gz")
        with tarfile.open(tgz_bad, "w:gz") as tf:
            ti = tarfile.TarInfo("evil_link")
            ti.type = tarfile.SYMTYPE
            ti.linkname = "/etc/passwd"
            tf.addfile(ti)

        dest_bad = os.path.join(tmp_dir, "out_bad")
        os.makedirs(dest_bad)
        with pytest.raises(ValueError, match="Unsafe archive entry"):
            nas_api_server._extract_tar_safe(tgz_bad, dest_bad)


def test_p3_parse_mkv_and_flv_headers_rust():
    with tempfile.TemporaryDirectory() as tmp_dir:
        # 1. Synthetic MKV
        mkv = bytearray(b"\x1A\x45\xDF\xA3\x80\x18\x53\x80\x67\xFF")
        info = b"\x44\x89\x88" + struct.pack(">d", 15000.0)
        mkv += bytes([0x15, 0x49, 0xA9, 0x66, 0x80 | len(info)]) + info
        codec = b"V_MPEGH/ISO/HEVC"
        entry = b"\x83\x81\x01" + bytes([0x86, 0x80 | len(codec)]) + codec
        tracks = bytes([0xAE, 0x80 | len(entry)]) + entry
        mkv += bytes([0x16, 0x54, 0xAE, 0x6B, 0x80 | len(tracks)]) + tracks

        mkv_path = os.path.join(tmp_dir, "clip.mkv")
        with open(mkv_path, "wb") as f:
            f.write(mkv)

        info_mkv = rust_bridge.parse_media_header(mkv_path)
        assert info_mkv is not None
        assert info_mkv["valid"] is True
        assert info_mkv["video_codec"] == "hevc"
        assert abs(info_mkv["duration_secs"] - 15.0) < 0.05

        # 2. Synthetic FLV
        flv = bytearray(b"FLV\x01\x05\x00\x00\x00\x09\x00\x00\x00\x00")
        meta = b"\x00\x08duration\x00" + struct.pack(">d", 33.5)
        psz = len(meta)
        flv += bytes([18, (psz >> 16) & 0xFF, (psz >> 8) & 0xFF, psz & 0xFF]) + b"\x00" * 7 + meta
        flv += struct.pack(">I", psz + 11)
        flv += bytes([9, 0, 0, 1]) + b"\x00" * 7 + bytes([0x17]) + struct.pack(">I", 12)

        flv_path = os.path.join(tmp_dir, "live.flv")
        with open(flv_path, "wb") as f:
            f.write(flv)

        info_flv = rust_bridge.parse_media_header(flv_path)
        assert info_flv is not None
        assert info_flv["valid"] is True
        assert info_flv["video_codec"] == "h264"
        assert abs(info_flv["duration_secs"] - 33.5) < 0.05
