#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tests/test_rust_hasher_bridge.py
Kiểm thử tính đúng đắn và độ bền vững của Rust Bridge theo chuẩn rust-anything.
Đảm bảo độ khớp 100% byte-for-byte với hashlib chuẩn của Python.
"""

import os
import sys
import hashlib
import tempfile
import pytest

# Thêm backend vào sys.path
backend_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "backend"))
if backend_dir not in sys.path:
    sys.path.insert(0, backend_dir)

import rust_bridge


@pytest.mark.skipif(not rust_bridge.is_rust_accelerated(), reason="Rust hasher core not built on this host")
def test_rust_bridge_availability():
    """Kiểm tra Rust bridge khởi tạo thành công trên môi trường dev."""
    # Khi đã build dll/so, is_rust_accelerated phải trả về True
    avail = rust_bridge.is_rust_accelerated()
    assert avail is True, "Rust hasher core library should be available after cargo build"


def test_sha256_parity_across_sizes():
    """Kiểm tra tính khớp byte-for-byte SHA256 trên nhiều kích thước file."""
    test_dir = tempfile.gettempdir()
    
    test_cases = [
        ("empty.bin", b""),
        ("small.txt", b"Short content for testing Rust vs Python parity."),
        ("utf8_vietnamese.txt", "Nội dung tiếng Việt kiểm tra băm file 2026".encode("utf-8")),
        ("block_64k.dat", b"A" * 65536),
        ("block_250k.dat", b"B" * (250 * 1024)),
    ]

    for fname, data in test_cases:
        p = os.path.join(test_dir, fname)
        try:
            with open(p, "wb") as f:
                f.write(data)
                
            expected = hashlib.sha256(data).hexdigest()
            actual = rust_bridge.compute_fast_sha256(p)
            assert actual == expected, "SHA256 mismatch for {}".format(fname)
        finally:
            if os.path.isfile(p):
                try:
                    os.remove(p)
                except Exception:
                    pass


def test_md5_1mb_parity():
    """Kiểm tra tính khớp byte-for-byte MD5 của 1MB đầu tiên."""
    test_dir = tempfile.gettempdir()
    
    # File 2.5MB (lớn hơn ngưỡng 1MB)
    large_data = b"X" * (2500 * 1024)
    p = os.path.join(test_dir, "test_large_md5.dat")
    try:
        with open(p, "wb") as f:
            f.write(large_data)
            
        expected = hashlib.md5(large_data[:1048576]).hexdigest()
        actual = rust_bridge.compute_fast_md5_1mb(p)
        assert actual == expected, "MD5 1MB mismatch on large file"
    finally:
        if os.path.isfile(p):
            try:
                os.remove(p)
            except Exception:
                pass


def test_fallback_on_missing_file():
    """Kiểm tra xử lý ngoại lệ khi file không tồn tại."""
    fake_path = os.path.join(tempfile.gettempdir(), "non_existent_file_99999.xyz")
    with pytest.raises(Exception):
        # Fallback về Python cũng phải raise FileNotFoundError/OSError
        rust_bridge.compute_fast_sha256(fake_path)


def test_api_status_rust_accelerated_field():
    """Kiểm tra trường rust_accelerated xuất hiện trong /api/status."""
    import nas_api_server
    nas_api_server.app.secret_key = "test_rust_secret_key"
    with nas_api_server.app.test_request_context():
        from flask import session
        session["logged_in"] = True
        session["is_admin"] = True
        unwrapped = getattr(nas_api_server.api_status, "__wrapped__", nas_api_server.api_status)
        res = unwrapped()
        data = res.get_json() or {}
        assert "rust_accelerated" in data, "rust_accelerated field must be in api_status response"
        assert data["rust_accelerated"] is True, "rust_accelerated must be True when Rust core is loaded"


def test_batch_md5_1mb_parallel_parity(tmp_path):
    """Kiểm tra băm batch MD5 1MB song song (Rust rayon) khớp 100% Python hashlib."""
    f1 = tmp_path / "one.bin"
    f2 = tmp_path / "two.bin"
    d1 = b"A" * 2048
    d2 = b"B" * (1_500_000)
    f1.write_bytes(d1)
    f2.write_bytes(d2)

    res = rust_bridge.compute_batch_md5_1mb([("/webdav/one.bin", str(f1)), ("/webdav/two.bin", str(f2))], workers=2)
    assert res is not None
    assert res["/webdav/one.bin"] == hashlib.md5(d1).hexdigest()
    assert res["/webdav/two.bin"] == hashlib.md5(d2[:1048576]).hexdigest()


def test_find_duplicates_3_stage_parallel(tmp_path):
    """Kiểm tra quét trùng lặp 3 tầng đa luồng (Size -> Partial MD5 -> Full SHA256) và bỏ qua .trash."""
    sub = tmp_path / "sub"
    trash = tmp_path / ".trash"
    sub.mkdir()
    trash.mkdir()

    dup_bytes = b"DUP_DATA_" * 512  # 4608 bytes
    same_size_diff = b"DIFF_DAT_" * 512  # 4608 bytes
    unique_bytes = b"UNIQ" * 400  # 1600 bytes

    (tmp_path / "a1.bin").write_bytes(dup_bytes)
    (sub / "a2.bin").write_bytes(dup_bytes)
    (tmp_path / "b1.bin").write_bytes(same_size_diff)
    (tmp_path / "c1.bin").write_bytes(unique_bytes)
    (trash / "a3.bin").write_bytes(dup_bytes)

    report = rust_bridge.find_duplicates(str(tmp_path), min_size=1024, workers=2, limit=100)
    assert report is not None
    assert report["total_files_scanned"] == 4
    assert report["size_candidates"] == 3
    assert report["partial_candidates"] == 2
    assert report["total_groups"] == 1
    assert report["total_wasted_bytes"] == len(dup_bytes)
    assert report["groups"][0]["sha256"] == hashlib.sha256(dup_bytes).hexdigest()
    assert report["groups"][0]["files"] == ["a1.bin", "sub/a2.bin"]


def test_parse_mp4_header_c_abi(tmp_path):
    """Kiểm tra đọc header MP4 (moov/mvhd/tkhd/stsd) bằng Rust C-ABI không cần ffprobe."""
    import struct

    def _box(btype, payload):
        return struct.pack(">I", 8 + len(payload)) + btype + payload

    ftyp = _box(b"ftyp", b"isom\x00\x00\x02\x00isommp41")
    mdat = _box(b"mdat", b"\x00" * 65536)

    mvhd_p = bytearray(24)
    struct.pack_into(">II", mvhd_p, 12, 1000, 45250)  # 45.25s
    mvhd = _box(b"mvhd", bytes(mvhd_p))

    tkhd_p = bytearray(84)
    struct.pack_into(">II", tkhd_p, 76, 1920 << 16, 1080 << 16)
    tkhd = _box(b"tkhd", bytes(tkhd_p))

    hdlr_p = bytearray(24)
    hdlr_p[8:12] = b"vide"
    hdlr = _box(b"hdlr", bytes(hdlr_p))

    stsd_p = bytearray(24)
    struct.pack_into(">II", stsd_p, 4, 1, 16)
    stsd_p[12:16] = b"hvc1"
    stsd = _box(b"stsd", bytes(stsd_p))

    mdia = _box(b"mdia", hdlr + _box(b"minf", _box(b"stbl", stsd)))
    moov = _box(b"moov", mvhd + _box(b"trak", tkhd + mdia))

    good_mp4 = tmp_path / "sample.mp4"
    good_mp4.write_bytes(ftyp + mdat + moov)

    info = rust_bridge.parse_mp4_header(str(good_mp4))
    assert info is not None
    assert info["valid"] is True
    assert info["has_moov"] is True
    assert info["major_brand"] == "isom"
    assert abs(info["duration_secs"] - 45.25) < 0.001
    assert info["width"] == 1920
    assert info["height"] == 1080
    assert info["video_codec"] == "hevc"

    broken_mp4 = tmp_path / "broken.mp4"
    broken_mp4.write_bytes(ftyp + mdat)
    binfo = rust_bridge.parse_mp4_header(str(broken_mp4))
    assert binfo is not None
    assert binfo["valid"] is False
    assert binfo["has_moov"] is False


def test_image_thumbnail_and_brightness_c_abi(tmp_path):
    """Kiểm tra tạo thumbnail JPEG SIMD và tính độ sáng trung bình bằng Rust C-ABI."""
    from PIL import Image

    src_png = tmp_path / "src_photo.png"
    dst_jpg = tmp_path / "out_thumb.jpg"
    Image.new("RGB", (1000, 500), (180, 180, 180)).save(str(src_png), "PNG")

    ok = rust_bridge.generate_image_thumbnail(str(src_png), str(dst_jpg), max_size=320, quality=82)
    assert ok is True
    assert dst_jpg.is_file() and dst_jpg.stat().st_size > 100

    with Image.open(str(dst_jpg)) as thumb:
        assert thumb.size == (320, 160)

    brightness = rust_bridge.compute_frame_brightness(str(dst_jpg))
    assert brightness is not None
    assert abs(brightness - 180.0) < 3.0

