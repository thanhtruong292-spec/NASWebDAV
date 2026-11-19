#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
backend/rust_bridge.py
Module cầu nối (Hybrid Bridge) theo chuẩn rust-anything & Ponytail:
Tự động nạp Rust core engine nếu có mặt trên hệ thống để tăng tốc tính toán;
tự động fallback 100% về Python gốc nếu Rust module vắng mặt hoặc gặp lỗi.
Tương thích hoàn toàn với Python 3.5.3 (không dùng f-strings, type annotations, etc.)
"""

import os
import sys
import hashlib
import ctypes
import logging

log = logging.getLogger("nas_api.rust_bridge")

_RUST_LIB = None
_RUST_AVAILABLE = False


def _init_rust_bridge():
    global _RUST_LIB, _RUST_AVAILABLE
    if _RUST_LIB is not None:
        return _RUST_AVAILABLE

    # Các đường dẫn tìm kiếm thư viện
    candidates = [
        # Thư mục build cục bộ dev
        os.path.join(os.path.dirname(__file__), "rust_modules", "nas_hasher", "target", "release", "nas_hasher_core.dll"),
        os.path.join(os.path.dirname(__file__), "rust_modules", "nas_hasher", "target", "release", "libnas_hasher_core.so"),
        # Đường dẫn cài đặt trên NAS thực tế
        "/opt/nas_hasher/libnas_hasher_core.so",
        "/usr/local/lib/libnas_hasher_core.so",
    ]

    for p in candidates:
        if os.path.isfile(p):
            try:
                lib = ctypes.CDLL(p)
                lib.nas_hasher_sha256.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
                lib.nas_hasher_sha256.restype = ctypes.c_int
                lib.nas_hasher_md5_1mb.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
                lib.nas_hasher_md5_1mb.restype = ctypes.c_int
                _RUST_LIB = lib
                _RUST_AVAILABLE = True
                log.info("Rust accelerated engine loaded successfully from: %s", p)
                return True
            except Exception as e:
                log.warning("Failed to load Rust engine at %s: %s", p, e)

    _RUST_AVAILABLE = False
    return False


def is_rust_accelerated():
    """Kiểm tra xem hệ thống có đang được tăng tốc bằng Rust hay không."""
    return _init_rust_bridge()


def compute_fast_sha256(filepath):
    """
    Tính SHA256 toàn bộ file.
    Ưu tiên Rust qua C-ABI; tự động fallback về hashlib nếu có sự cố.
    """
    if _init_rust_bridge():
        try:
            buf = ctypes.create_string_buffer(65)
            ret = _RUST_LIB.nas_hasher_sha256(filepath.encode("utf-8"), buf)
            if ret == 0:
                return buf.value.decode("ascii")
        except Exception as e:
            log.warning("Rust sha256 failed, fallback to python: %s", e)

    # Fallback Python 3.5.3 chuẩn
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while True:
            chunk = f.read(65536)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def compute_fast_md5_1mb(filepath):
    """
    Tính MD5 của 1MB đầu tiên.
    Ưu tiên Rust qua C-ABI; tự động fallback về hashlib nếu có sự cố.
    """
    if _init_rust_bridge():
        try:
            buf = ctypes.create_string_buffer(33)
            ret = _RUST_LIB.nas_hasher_md5_1mb(filepath.encode("utf-8"), buf)
            if ret == 0:
                return buf.value.decode("ascii")
        except Exception as e:
            log.warning("Rust md5_1mb failed, fallback to python: %s", e)

    # Fallback Python 3.5.3 chuẩn
    with open(filepath, "rb") as f:
        chunk = f.read(1048576)
        return hashlib.md5(chunk).hexdigest()
