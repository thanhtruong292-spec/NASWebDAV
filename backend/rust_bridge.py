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
                if hasattr(lib, "nas_hasher_batch_md5_1mb_json"):
                    lib.nas_hasher_batch_md5_1mb_json.argtypes = [ctypes.c_char_p, ctypes.c_int]
                    lib.nas_hasher_batch_md5_1mb_json.restype = ctypes.c_void_p
                if hasattr(lib, "nas_hasher_find_duplicates_json"):
                    lib.nas_hasher_find_duplicates_json.argtypes = [ctypes.c_char_p, ctypes.c_ulonglong, ctypes.c_int, ctypes.c_int]
                    lib.nas_hasher_find_duplicates_json.restype = ctypes.c_void_p
                if hasattr(lib, "nas_hasher_parse_mp4_json"):
                    lib.nas_hasher_parse_mp4_json.argtypes = [ctypes.c_char_p]
                    lib.nas_hasher_parse_mp4_json.restype = ctypes.c_void_p
                if hasattr(lib, "nas_hasher_generate_thumb"):
                    lib.nas_hasher_generate_thumb.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
                    lib.nas_hasher_generate_thumb.restype = ctypes.c_int
                if hasattr(lib, "nas_hasher_frame_brightness"):
                    lib.nas_hasher_frame_brightness.argtypes = [ctypes.c_char_p]
                    lib.nas_hasher_frame_brightness.restype = ctypes.c_double
                if hasattr(lib, "nas_hasher_free_string"):
                    lib.nas_hasher_free_string.argtypes = [ctypes.c_void_p]
                    lib.nas_hasher_free_string.restype = None
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
                    if hasattr(lib, "nas_indexer_build_fts"):
                        lib.nas_indexer_build_fts.argtypes = [ctypes.c_char_p]
                        lib.nas_indexer_build_fts.restype = ctypes.c_int
                    if hasattr(lib, "nas_indexer_storage_summary_json"):
                        lib.nas_indexer_storage_summary_json.argtypes = [ctypes.c_char_p]
                        lib.nas_indexer_storage_summary_json.restype = ctypes.c_void_p
                    if hasattr(lib, "nas_indexer_scan_dir_json"):
                        lib.nas_indexer_scan_dir_json.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
                        lib.nas_indexer_scan_dir_json.restype = ctypes.c_void_p
                    if hasattr(lib, "nas_indexer_recent_media_json"):
                        lib.nas_indexer_recent_media_json.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
                        lib.nas_indexer_recent_media_json.restype = ctypes.c_void_p
                    if hasattr(lib, "nas_indexer_photos_timeline_json"):
                        lib.nas_indexer_photos_timeline_json.argtypes = [ctypes.c_char_p, ctypes.c_longlong, ctypes.c_longlong, ctypes.c_int, ctypes.c_int]
                        lib.nas_indexer_photos_timeline_json.restype = ctypes.c_void_p
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


_FTS_REBUILD_LOCK = threading.Lock()


def build_fts_index(db_path):
    """
    Xây dựng sidecar FTS5 trigram index (<db_path>.fts.db) bằng Rust C-ABI.
    Trả về số dòng đã lập chỉ mục (>= 0), hoặc -1 nếu lỗi/chưa sẵn sàng.
    """
    if not _init_indexer_lib() or not db_path or not os.path.isfile(db_path):
        return -1
    if not hasattr(_INDEXER_LIB, "nas_indexer_build_fts"):
        return -1
    try:
        return int(_INDEXER_LIB.nas_indexer_build_fts(db_path.encode("utf-8")))
    except Exception as e:
        log.warning("Rust build_fts_index failed: %s", e)
        return -1


def schedule_fts_rebuild(db_path):
    """Kích hoạt luồng nền dựng lại sidecar FTS5 trigram nếu chưa có luồng nào đang chạy."""
    if not _init_indexer_lib() or not db_path or not os.path.isfile(db_path):
        return False
    if not _FTS_REBUILD_LOCK.acquire(False):
        return False

    def _worker():
        try:
            cnt = build_fts_index(db_path)
            if cnt >= 0:
                log.info("Rust FTS5 trigram sidecar rebuilt (%d rows) for %s", cnt, db_path)
        finally:
            _FTS_REBUILD_LOCK.release()

    t = threading.Thread(target=_worker, daemon=True)
    t.start()
    return True


def compute_batch_md5_1mb(pairs, workers=4):
    """
    Băm MD5 1MB song song đa luồng (Rust rayon, không GIL) cho danh sách [(url_path, real_path), ...].
    Trả về dict {url_path: md5_hex} nếu thành công, hoặc None để caller fallback về Python.
    """
    if not pairs:
        return {}
    if not _init_rust_bridge() or not hasattr(_RUST_LIB, "nas_hasher_batch_md5_1mb_json"):
        return None
    ptr = None
    try:
        payload = json.dumps([[str(u), str(p)] for u, p in pairs], ensure_ascii=False).encode("utf-8")
        ptr = _RUST_LIB.nas_hasher_batch_md5_1mb_json(payload, int(workers))
        if not ptr:
            return None
        raw_json = ctypes.string_at(ptr).decode("utf-8", errors="ignore")
        return json.loads(raw_json)
    except Exception as e:
        log.warning("Rust batch_md5_1mb failed, fallback to python: %s", e)
        return None
    finally:
        if ptr and hasattr(_RUST_LIB, "nas_hasher_free_string"):
            try:
                _RUST_LIB.nas_hasher_free_string(ptr)
            except Exception:
                pass


def find_duplicates(root_dir, min_size=1024, workers=4, limit=500):
    """
    Quét tệp trùng lặp 3 tầng đa luồng bằng Rust rayon (Size -> Partial MD5 1MB -> Full SHA-256).
    Trả về dict DuplicateReport nếu thành công, hoặc None nếu thư viện Rust chưa sẵn sàng.
    """
    if not _init_rust_bridge() or not hasattr(_RUST_LIB, "nas_hasher_find_duplicates_json"):
        return None
    if not root_dir or not os.path.isdir(root_dir):
        return None
    ptr = None
    try:
        ptr = _RUST_LIB.nas_hasher_find_duplicates_json(
            str(root_dir).encode("utf-8"),
            int(max(0, min_size)),
            int(max(1, min(4, workers))),
            int(max(0, limit))
        )
        if not ptr:
            return None
        raw_json = ctypes.string_at(ptr).decode("utf-8", errors="ignore")
        return json.loads(raw_json)
    except Exception as e:
        log.warning("Rust find_duplicates failed: %s", e)
        return None
    finally:
        if ptr and hasattr(_RUST_LIB, "nas_hasher_free_string"):
            try:
                _RUST_LIB.nas_hasher_free_string(ptr)
            except Exception:
                pass


def parse_mp4_header(filepath):
    """
    Đọc trực tiếp header MP4/MOV/M4V (moov/mvhd/tkhd/stsd) bằng Rust C-ABI, không cần fork ffprobe.
    Trả về dict Mp4HeaderInfo nếu thành công, hoặc None nếu không hỗ trợ/lỗi.
    """
    if not _init_rust_bridge() or not hasattr(_RUST_LIB, "nas_hasher_parse_mp4_json"):
        return None
    if not filepath or not os.path.isfile(filepath):
        return None
    ptr = None
    try:
        ptr = _RUST_LIB.nas_hasher_parse_mp4_json(str(filepath).encode("utf-8"))
        if not ptr:
            return None
        raw_json = ctypes.string_at(ptr).decode("utf-8", errors="ignore")
        return json.loads(raw_json)
    except Exception as e:
        log.warning("Rust parse_mp4_header failed: %s", e)
        return None
    finally:
        if ptr and hasattr(_RUST_LIB, "nas_hasher_free_string"):
            try:
                _RUST_LIB.nas_hasher_free_string(ptr)
            except Exception:
                pass


def compute_storage_summary(db_path):
    """
    Tính thống kê lưu trữ (by_type, largest top 10, total_files/bytes/dirs) trong 1 lượt quét Rust C-ABI.
    Trả về dict nếu thành công, hoặc None để caller fallback về Python UDF.
    """
    if not _init_indexer_lib() or not hasattr(_INDEXER_LIB, "nas_indexer_storage_summary_json"):
        return None
    if not db_path or not os.path.isfile(db_path):
        return None
    ptr = None
    try:
        ptr = _INDEXER_LIB.nas_indexer_storage_summary_json(str(db_path).encode("utf-8"))
        if not ptr:
            return None
        raw_json = ctypes.string_at(ptr).decode("utf-8", errors="ignore")
        return json.loads(raw_json)
    except Exception as e:
        log.warning("Rust compute_storage_summary failed, fallback to python: %s", e)
        return None
    finally:
        if ptr and hasattr(_INDEXER_LIB, "nas_indexer_free_string"):
            try:
                _INDEXER_LIB.nas_indexer_free_string(ptr)
            except Exception:
                pass


def generate_image_thumbnail(src_path, dst_path, max_size=320, quality=82):
    """
    Tạo thumbnail JPEG bằng Rust + ARM64 NEON SIMD (fast_image_resize + zune-jpeg).
    Trả về True nếu thành công, False để caller fallback về Pillow.
    """
    if not _init_rust_bridge() or not hasattr(_RUST_LIB, "nas_hasher_generate_thumb"):
        return False
    if not src_path or not dst_path or not os.path.isfile(src_path):
        return False
    try:
        ret = _RUST_LIB.nas_hasher_generate_thumb(
            str(src_path).encode("utf-8"),
            str(dst_path).encode("utf-8"),
            int(max_size),
            int(quality)
        )
        return ret == 0
    except Exception as e:
        log.warning("Rust generate_image_thumbnail failed, fallback to Pillow: %s", e)
        return False


def compute_frame_brightness(img_path):
    """
    Tính độ sáng trung bình (0.0..255.0) của ảnh JPEG/PNG bằng Rust C-ABI.
    Trả về float nếu thành công, None để caller fallback về Pillow.
    """
    if not _init_rust_bridge() or not hasattr(_RUST_LIB, "nas_hasher_frame_brightness"):
        return None
    if not img_path or not os.path.isfile(img_path):
        return None
    try:
        val = float(_RUST_LIB.nas_hasher_frame_brightness(str(img_path).encode("utf-8")))
        if val >= 0.0:
            return val
    except Exception as e:
        log.warning("Rust compute_frame_brightness failed, fallback to Pillow: %s", e)
    return None


def scan_directory_entries(dir_path, rel_prefix=""):
    """
    Quét 1 thư mục bằng Rust C-ABI (`read_dir` + `lstat` + `remove_diacritics` + MIME O(1)).
    Trả về dict {"entries": [...], "special": [...]} hoặc None để caller fallback về Python.
    """
    if not _init_indexer_lib() or not hasattr(_INDEXER_LIB, "nas_indexer_scan_dir_json"):
        return None
    if not dir_path or not os.path.isdir(dir_path):
        return None
    ptr = None
    try:
        ptr = _INDEXER_LIB.nas_indexer_scan_dir_json(
            str(dir_path).encode("utf-8"),
            str(rel_prefix or "").encode("utf-8")
        )
        if not ptr:
            return None
        raw_json = ctypes.string_at(ptr).decode("utf-8", errors="ignore")
        return json.loads(raw_json)
    except Exception as e:
        log.warning("Rust scan_directory_entries failed, fallback to python: %s", e)
        return None
    finally:
        if ptr and hasattr(_INDEXER_LIB, "nas_indexer_free_string"):
            try:
                _INDEXER_LIB.nas_indexer_free_string(ptr)
            except Exception:
                pass


def query_recent_media(db_path, kind, scope="", limit=100):
    """
    Truy vấn media gần nhất bằng Rust C-ABI (trực tiếp trên index mtime DESC, dừng sớm tại limit).
    Trả về list dict items hoặc None để caller fallback về SQLite Python.
    """
    if not _init_indexer_lib() or not hasattr(_INDEXER_LIB, "nas_indexer_recent_media_json"):
        return None
    if not db_path or not os.path.isfile(db_path):
        return None
    ptr = None
    try:
        ptr = _INDEXER_LIB.nas_indexer_recent_media_json(
            str(db_path).encode("utf-8"),
            str(kind).encode("utf-8"),
            str(scope or "").encode("utf-8"),
            int(limit)
        )
        if not ptr:
            return None
        raw_json = ctypes.string_at(ptr).decode("utf-8", errors="ignore")
        return json.loads(raw_json)
    except Exception as e:
        log.warning("Rust query_recent_media failed, fallback to python: %s", e)
        return None
    finally:
        if ptr and hasattr(_INDEXER_LIB, "nas_indexer_free_string"):
            try:
                _INDEXER_LIB.nas_indexer_free_string(ptr)
            except Exception:
                pass


def query_photos_timeline(db_path, start_ms=-1, end_ms=-1, limit=500, offset=0):
    """
    Truy vấn Timeline ảnh 1 lượt quét duy nhất bằng Rust C-ABI (vừa đếm total vừa phân trang).
    Trả về dict {"total": int, "items": [...]} hoặc None để caller fallback về SQLite Python.
    """
    if not _init_indexer_lib() or not hasattr(_INDEXER_LIB, "nas_indexer_photos_timeline_json"):
        return None
    if not db_path or not os.path.isfile(db_path):
        return None
    ptr = None
    try:
        ptr = _INDEXER_LIB.nas_indexer_photos_timeline_json(
            str(db_path).encode("utf-8"),
            int(start_ms),
            int(end_ms),
            int(limit),
            int(offset)
        )
        if not ptr:
            return None
        raw_json = ctypes.string_at(ptr).decode("utf-8", errors="ignore")
        return json.loads(raw_json)
    except Exception as e:
        log.warning("Rust query_photos_timeline failed, fallback to python: %s", e)
        return None
    finally:
        if ptr and hasattr(_INDEXER_LIB, "nas_indexer_free_string"):
            try:
                _INDEXER_LIB.nas_indexer_free_string(ptr)
            except Exception:
                pass
