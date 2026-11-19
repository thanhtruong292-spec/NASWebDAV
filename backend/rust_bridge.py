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
import json
import hashlib
import ctypes
import logging
import subprocess
import threading

log = logging.getLogger("nas_api.rust_bridge")

_RUST_LIB = None
_RUST_AVAILABLE = False
_RUST_INIT_LOCK = threading.Lock()


def _init_rust_bridge():
    global _RUST_LIB, _RUST_AVAILABLE
    if _RUST_LIB is not None:
        return _RUST_AVAILABLE

    with _RUST_INIT_LOCK:
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


def _find_indexer_binary():
    candidates = [
        os.path.join(os.path.dirname(__file__), "rust_modules", "nas_indexer", "target", "release", "nas_indexer.exe"),
        os.path.join(os.path.dirname(__file__), "rust_modules", "nas_indexer", "target", "release", "nas_indexer"),
        "/usr/local/bin/nas_indexer",
        "/opt/nas_hasher/nas_indexer",
    ]
    for p in candidates:
        if os.path.isfile(p):
            return p
    return None


def is_indexer_accelerated():
    """Kiểm tra xem module nas_indexer (Rust) có khả dụng hay không."""
    return _find_indexer_binary() is not None


class _RustIndexerStream(object):
    """
    Iterator đọc luồng JSON theo khối 64KB từ stdout của tiến trình nas_indexer.
    Tương thích Python 3.5.3, dọn dẹp tiến trình an toàn khi kết thúc hoặc GeneratorExit.
    """
    def __init__(self, proc, first_chunk):
        self._proc = proc
        self._first_chunk = first_chunk
        self._closed = False

    def __iter__(self):
        return self

    def __next__(self):
        if self._closed:
            raise StopIteration
        if self._first_chunk is not None:
            chunk = self._first_chunk
            self._first_chunk = None
            return chunk
        raw = self._proc.stdout.read(65536)
        if not raw:
            self.close()
            raise StopIteration
        return raw.decode("utf-8", errors="ignore")

    def next(self):
        return self.__next__()

    def close(self):
        if not self._closed:
            self._closed = True
            try:
                self._proc.stdout.close()
            except Exception:
                pass
            try:
                self._proc.stderr.close()
            except Exception:
                pass
            if self._proc.poll() is None:
                try:
                    self._proc.terminate()
                except Exception:
                    pass


def stream_fast_index(db_path):
    """
    Tạo stream JSON trực tiếp từ nas_indexer core (Rust).
    Trả về _RustIndexerStream nếu đọc được chunk đầu hợp lệ;
    Trả về None nếu không có binary hoặc lỗi đọc để tự động fallback về Python.
    """
    bin_path = _find_indexer_binary()
    if not bin_path or not os.path.isfile(db_path):
        return None

    try:
        stdin_dest = getattr(subprocess, "DEVNULL", None) or subprocess.PIPE
        proc = subprocess.Popen(
            [bin_path, "stream", db_path],
            stdin=stdin_dest,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        first_raw = proc.stdout.read(65536)
        if not first_raw:
            err_raw = proc.stderr.read()
            err_msg = err_raw.decode("utf-8", errors="ignore") if err_raw else ""
            log.warning("Rust nas_indexer produced no data (code %s): %s", proc.poll(), err_msg)
            try:
                proc.stdout.close()
                proc.stderr.close()
            except Exception:
                pass
            if proc.poll() is None:
                try:
                    proc.terminate()
                except Exception:
                    pass
            return None

        first_chunk = first_raw.decode("utf-8", errors="ignore")
        return _RustIndexerStream(proc, first_chunk)
    except Exception as e:
        log.warning("Failed to start Rust nas_indexer: %s", e)
        return None


_INDEXER_LIB = None
_INDEXER_LIB_CHECKED = False
_INDEXER_INIT_LOCK = threading.Lock()


def _init_indexer_lib():
    global _INDEXER_LIB, _INDEXER_LIB_CHECKED
    if _INDEXER_LIB_CHECKED:
        return _INDEXER_LIB is not None

    with _INDEXER_INIT_LOCK:
        if _INDEXER_LIB_CHECKED:
            return _INDEXER_LIB is not None

        candidates = [
            os.path.join(os.path.dirname(__file__), "rust_modules", "nas_indexer", "target", "release", "nas_indexer_core.dll"),
            os.path.join(os.path.dirname(__file__), "rust_modules", "nas_indexer", "target", "release", "libnas_indexer_core.so"),
            "/opt/nas_hasher/libnas_indexer_core.so",
            "/usr/local/lib/libnas_indexer_core.so",
        ]

        for p in candidates:
            if os.path.isfile(p):
                try:
                    lib = ctypes.CDLL(p)
                    lib.nas_indexer_remove_diacritics.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
                    lib.nas_indexer_remove_diacritics.restype = ctypes.c_int
                    lib.nas_indexer_search_json.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
                    lib.nas_indexer_search_json.restype = ctypes.c_void_p
                    lib.nas_indexer_free_string.argtypes = [ctypes.c_void_p]
                    lib.nas_indexer_free_string.restype = None
                    _INDEXER_LIB = lib
                    _INDEXER_LIB_CHECKED = True
                    log.info("Rust indexer C-ABI loaded successfully from: %s", p)
                    return True
                except Exception as e:
                    log.warning("Failed to load Rust indexer C-ABI at %s: %s", p, e)

        _INDEXER_LIB_CHECKED = True
        return False


def is_search_accelerated():
    """Kiểm tra xem thư viện C-ABI tìm kiếm & xóa dấu (Rust) có khả dụng hay không."""
    return _init_indexer_lib()


def remove_diacritics(text):
    """
    Xóa dấu tiếng Việt & dấu phụ Latin bằng Rust C-ABI.
    Trả về None nếu thư viện Rust chưa sẵn sàng để caller fallback về Python.
    """
    if not text:
        return ""
    if not _init_indexer_lib():
        return None
    try:
        raw = str(text).encode("utf-8")
        buf_size = len(raw) + 16
        buf = ctypes.create_string_buffer(buf_size)
        ret = _INDEXER_LIB.nas_indexer_remove_diacritics(raw, buf, buf_size)
        if ret >= 0:
            return buf.raw[:ret].decode("utf-8", errors="ignore")
    except Exception as e:
        log.warning("Rust remove_diacritics failed, fallback to python: %s", e)
    return None


def search_file_index(db_path, query, root_param="", limit=300):
    """
    Tìm kiếm trong bảng file_index SQLite bằng Rust C-ABI (READ_ONLY).
    Trả về list các tuple [path, name, is_dir, size, mtime, mime] nếu thành công,
    hoặc None để caller fallback về Python.
    """
    if not _init_indexer_lib() or not db_path or not os.path.isfile(db_path):
        return None
    ptr = None
    try:
        ptr = _INDEXER_LIB.nas_indexer_search_json(
            db_path.encode("utf-8"),
            str(query).encode("utf-8"),
            str(root_param or "").encode("utf-8"),
            int(limit)
        )
        if not ptr:
            return None
        raw_json = ctypes.string_at(ptr).decode("utf-8", errors="ignore")
        return json.loads(raw_json)
    except Exception as e:
        log.warning("Rust search_file_index failed, fallback to python: %s", e)
        return None
    finally:
        if ptr:
            try:
                _INDEXER_LIB.nas_indexer_free_string(ptr)
            except Exception:
                pass
