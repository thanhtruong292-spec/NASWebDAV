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

