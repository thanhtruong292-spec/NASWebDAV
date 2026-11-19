#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NAS API Server cho Chainedbox L1 Pro (rk3328)
Phục vụ dữ liệu hệ thống real-time cho ứng dụng Android NAS WebDAV.

Cài đặt: pip3 install flask psutil tornado
Chạy:    python3 nas_api_server.py
Tự động: Thêm vào /etc/rc.local hoặc tạo systemd service

Port: 5050 (HTTP API), 5051 (WebSocket)
"""

import os
import sys
# THÊM DÒNG NÀY ĐỂ TRỊ BỆNH 1.5GB RAM ẢO CỦA LINUX GLIBC
os.environ["MALLOC_ARENA_MAX"] = "2"
import json
import gc
import ctypes
import concurrent.futures
import mimetypes
import time
import uuid
import subprocess
import threading
import logging
import re
import re as _re_module
import shutil
import hashlib
import tempfile
from functools import wraps
import sqlite3
import base64
import urllib.request
import urllib.error
import urllib.parse
try:
    from backend import nas_work_coordinator
except ImportError:
    import nas_work_coordinator
try:
    import psutil
except ImportError:
    psutil = None

try:
    import ssl
except ImportError:
    ssl = None

import socket
import socket as _socket

def _get_process_name(pid_int):
    if psutil is not None:
        try:
            name = (psutil.Process(pid_int).name() or "").strip()
            if name:
                return name.lower()
        except Exception:
            pass
    # Fallback to reading /proc/<pid>/comm or /proc/<pid>/cmdline directly
    try:
        with open("/proc/%d/comm" % pid_int, "r") as f:
            return f.read().strip().lower()
    except Exception:
        try:
            with open("/proc/%d/cmdline" % pid_int, "r") as f:
                raw = f.read().replace("\x00", " ").strip()
                if raw:
                    return os.path.basename(raw.split()[0]).lower()
        except Exception:
            pass
    return ""

def _get_qbt_credentials():
    user = os.environ.get("QBT_USER")
    password = os.environ.get("QBT_PASS")
    if not user or not password:
        conf_path = "/etc/nas/qbt.conf"
        if os.path.exists(conf_path):
            try:
                with open(conf_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line.startswith("user="):
                            user = user or line.split("=", 1)[1].strip()
                        elif line.startswith("password="):
                            password = password or line.split("=", 1)[1].strip()
            except Exception as e:
                log.warning("[Security] Khong doc duoc file qbt.conf: %s", e)
    user = user or "admin"
    if not password:
        raise ValueError("Chua thiet lap mat khau qBittorrent. Vui long khai bao QBT_PASS trong environment hoac file /etc/nas/qbt.conf.")
    return user, password

def sanitize_log_input(text):
    if not text: return str(text)
    return _re_module.sub(r'[\r\n]+', ' ', str(text))

def normalize_vietnamese_message(text):
    """Chuẩn hoá các thông báo/nhật ký cũ còn không dấu trước khi hiển thị."""
    if text is None:
        return ""
    result = str(text)
    replacements = (
        ("stream URL stale/khong probe duoc", "URL stream đã hết hạn hoặc không kiểm tra được"),
        ("stream URL stale/không probe được", "URL stream đã hết hạn hoặc không kiểm tra được"),
        ("no live stream detected", "Không phát hiện livestream đang chạy"),
        ("tiktok empty", "TikTok trả về trang rỗng"),
        ("CANH BAO", "CẢNH BÁO"),
        ("CANH_BAO", "CẢNH_BÁO"),
        ("Thieu", "Thiếu"),
        ("thieu", "thiếu"),
        ("chua", "chưa"),
        ("Chua", "Chưa"),
        ("phai", "phải"),
        ("Phai", "Phải"),
        ("boi", "bởi"),
        ("Boi", "Bởi"),
        ("qua cao", "quá cao"),
        ("Qua cao", "Quá cao"),
        ("Khong", "Không"),
        ("khong", "không"),
        ("Dang", "Đang"),
        ("dang", "đang"),
        ("Da ", "Đã "),
        (" da ", " đã "),
        ("Loi", "Lỗi"),
        ("loi", "lỗi"),
        ("Hoan tat", "Hoàn tất"),
        ("hoan tat", "hoàn tất"),
        ("Tam dung", "Tạm dừng"),
        ("tam dung", "tạm dừng"),
        ("ket thuc", "kết thúc"),
        ("Ket thuc", "Kết thúc"),
        ("khoi dong", "khởi động"),
        ("Khoi dong", "Khởi động"),
        ("don dep", "dọn dẹp"),
        ("Don dep", "Dọn dẹp"),
        ("don tmp", "dọn tmp"),
        ("Don tmp", "Dọn tmp"),
        ("ghi hinh", "ghi hình"),
        ("Ghi hinh", "Ghi hình"),
        ("luong ghi", "luồng ghi"),
        ("Luong ghi", "Luồng ghi"),
        ("dong bo", "đồng bộ"),
        ("Dong bo", "Đồng bộ"),
        ("dien thoai", "điện thoại"),
        ("Dien thoai", "Điện thoại"),
        ("nhiet do", "nhiệt độ"),
        ("Nhiet do", "Nhiệt độ"),
        ("Thung rac", "Thùng rác"),
        ("thung rac", "thùng rác"),
        ("thu muc", "thư mục"),
        ("Thu muc", "Thư mục"),
        ("tep", "tệp"),
        ("Tep", "Tệp"),
        ("file rong", "tệp rỗng"),
        ("File rong", "Tệp rỗng"),
        ("thu muc rong", "thư mục rỗng"),
        ("FLV hong", "FLV hỏng"),
        ("tai xong", "tải xong"),
        ("dang tai", "đang tải"),
        ("Da tai", "Đã tải"),
        ("phan loai", "phân loại"),
        ("Phan loai", "Phân loại"),
        ("sắp xep", "sắp xếp"),
        ("sap xep", "sắp xếp"),
        ("di chuyen", "di chuyển"),
        ("Di chuyen", "Di chuyển"),
        ("ton tai", "tồn tại"),
        ("Ton tai", "Tồn tại"),
        ("hop le", "hợp lệ"),
        ("Hop le", "Hợp lệ"),
        ("tu choi", "từ chối"),
        ("Tu choi", "Từ chối"),
        ("yeu cau", "yêu cầu"),
        ("Yeu cau", "Yêu cầu"),
        ("nguoi dung", "người dùng"),
        ("Nguoi dung", "Người dùng"),
        ("truy cap", "truy cập"),
        ("Truy cap", "Truy cập"),
        ("he thong", "hệ thống"),
        ("He thong", "Hệ thống"),
        ("o cung", "ổ cứng"),
        ("O cung", "Ổ cứng"),
        ("mang", "mạng"),
        ("Mang", "Mạng"),
        ("duoc", "được"),
        ("Duoc", "Được"),
        ("cap nhat", "cập nhật"),
        ("Cap nhat", "Cập nhật"),
        ("dang nhap", "đăng nhập"),
        ("Dang nhap", "Đăng nhập"),
        ("ko ", "không "),
        ("KO ", "KHÔNG "),
    )
    for src, dst in replacements:
        result = result.replace(src, dst)
    return result
import datetime
import hashlib
import signal
import faulthandler
import atexit

# ============ LOGGING TIÊU CHUẨN ============
# Ghi log ra file /var/log/nas_api.log + console, có rotation
# FIX-ZRAM: /var/log là zram 49M (đã đầy 100% ngày 2026-09-15) — rotation
# 5MB x 3 = 20MB quá lớn. Giảm còn 1MB x 2 = tối đa 3MB.
_log_formatter = logging.Formatter(
    "%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%d/%m/%Y %H:%M:%S"
)
_log_handler_console = logging.StreamHandler(sys.stdout)
_log_handler_console.setFormatter(_log_formatter)
try:
    from logging.handlers import RotatingFileHandler
    _log_handler_file = RotatingFileHandler(
        "/var/log/nas_api.log", maxBytes=1*1024*1024, backupCount=2, encoding="utf-8"
    )
    _log_handler_file.setFormatter(_log_formatter)
except Exception:
    _log_handler_file = None

log = logging.getLogger("NasAPI")
log.setLevel(logging.INFO)
log.propagate = False
log.addHandler(_log_handler_console)
if _log_handler_file:
    log.addHandler(_log_handler_file)
try:
    _thread_dump_file = open("/tmp/nas_api_threads.log", "a")
    faulthandler.register(signal.SIGUSR1, file=_thread_dump_file, all_threads=True)
except Exception:
    pass

# SPEC_LIVESTREAM_LIMITS §3.1: config loader (Python 3.5-compatible).
# Thu tu uu tien: env NAS_* > /etc/nas/nas_api.conf (JSON) > default.
# Khong dung f-string/walrus. Doc 1 lan luc start, PATCH cap nhat runtime.
_NAS_LIMITS_CONF_PATH = os.environ.get("NAS_CONFIG", "/etc/nas/nas_api.conf")
_NAS_LIMITS_CFG = {}
_NAS_LIMITS_LOCK = threading.Lock()
try:
    if os.path.exists(_NAS_LIMITS_CONF_PATH):
        with open(_NAS_LIMITS_CONF_PATH, "r") as _f:
            _loaded = json.load(_f)
            if isinstance(_loaded, dict):
                _NAS_LIMITS_CFG = _loaded
except Exception:
    _NAS_LIMITS_CFG = {}
_NAS_LIMITS_RUNTIME = {}


def _cfg_limit(name_lower, default):
    try:
        env_key = "NAS_" + str(name_lower).upper()
        if env_key in os.environ and str(os.environ.get(env_key, "")) != "":
            return os.environ.get(env_key)
        _lck = globals().get("_NAS_LIMITS_LOCK")
        if _lck is not None:
            with _lck:
                if name_lower in _NAS_LIMITS_RUNTIME:
                    return _NAS_LIMITS_RUNTIME.get(name_lower)
                if name_lower in _NAS_LIMITS_CFG:
                    return _NAS_LIMITS_CFG.get(name_lower)
        else:
            if name_lower in _NAS_LIMITS_RUNTIME:
                return _NAS_LIMITS_RUNTIME.get(name_lower)
            if name_lower in _NAS_LIMITS_CFG:
                return _NAS_LIMITS_CFG.get(name_lower)
    except Exception:
        pass
    return default


def _cfg_int(name_lower, default, low, high):
    try:
        raw = _cfg_limit(name_lower, default)
        val = int(str(raw).strip())
    except Exception:
        return default
    try:
        if val < low:
            return low
        if val > high:
            return high
        return val
    except Exception:
        return default


def _get_social_max_concurrent():
    return _cfg_int("social_max_concurrent", 2, 1, 16)


def _get_ytdlp_max_concurrent():
    # CC-004 P2-5: cap rieng yt-dlp (mac dinh 2, toi da 4) — yt-dlp vua tai vua
    # remux/merge an RAM/CPU khac voi social-download nhe. Dung chung cap social
    # (toi da 16) se giet RK3328 1GB khi user bam tai hang loat.
    return _cfg_int("ytdlp_max_concurrent", 2, 1, 4)


def _get_livestream_max_concurrent():
    return _cfg_int("livestream_max_concurrent", 2, 1, 8)


def _get_livestream_max_hours():
    return _cfg_int("livestream_max_hours", 12, 1, 72)


def _get_live_err_cooldown_s():
    return _cfg_int("live_err_cooldown_s", 300, 10, 86400)


def _get_live_stall_cooldown_s():
    return _cfg_int("live_stall_cooldown_s", 7200, 60, 86400 * 7)


def _get_ytdlp_timeout_s():
    return _cfg_int("ytdlp_timeout_s", 300, 60, 1800)


def _get_user_idle_s():
    return _cfg_int("user_idle_s", 90, 10, 600)


def _get_ram_block_pct():
    return _cfg_int("ram_block_pct", 78, 50, 95)


def _get_load_block():
    try:
        raw = _cfg_limit("load_block", 2.5)
        val = float(str(raw).strip())
    except Exception:
        return 2.5
    if val < 1.0:
        return 1.0
    if val > 8.0:
        return 8.0
    return val


def _get_cpu_block_pct():
    return _cfg_int("cpu_block_pct", 75, 50, 100)


def _get_heavy_gate_cache_s():
    try:
        raw = _cfg_limit("heavy_gate_cache_s", 5.0)
        val = float(str(raw).strip())
    except Exception:
        return 5.0
    if val < 1.0:
        return 1.0
    if val > 60.0:
        return 60.0
    return val


def _get_social_cleanup_interval_s():
    return _cfg_int("social_cleanup_interval_s", 300, 30, 86400)


def _get_social_job_ttl_s():
    return _cfg_int("social_job_ttl_s", 3600, 300, 86400 * 7)


def _get_tiktok_watch_s():
    return _cfg_int("tiktok_watch_s", 60, 15, 300)


def _get_tiktok_html_cap():
    return _cfg_int("tiktok_html_cap", 786432, 65536, 8388608)


def _get_tiktok_html_timeout():
    return _cfg_int("tiktok_html_timeout", 25, 5, 120)


def _get_social_max_filesize():
    try:
        raw = _cfg_limit("social_max_filesize", "2G")
        val = str(raw).strip()
        if not val:
            return "2G"
        return val
    except Exception:
        return "2G"


# CC-005 P1/P2: gom port/path/timeout hard-code ve ENV (giữ default khi ENV trống).
# Quy ước: NAS_<TEN_UPPER> > runtime > conf > default. Không đổi hành vi khi ENV trống.
def _cfg_str(name_lower, default):
    try:
        raw = _cfg_limit(name_lower, default)
        val = str(raw).strip() if raw is not None else ""
        return val if val else default
    except Exception:
        return default


def _cfg_float(name_lower, default, low, high):
    try:
        raw = _cfg_limit(name_lower, default)
        val = float(str(raw).strip())
    except Exception:
        return default
    try:
        if val < low:
            return low
        if val > high:
            return high
        return val
    except Exception:
        return default


def _cfg_sqlite_timeout(name_lower, default):
    # CC-005 P1/P2: timeout sqlite (10/20/30/5) unify qua ENV NAS_SQLITE_*_TIMEOUT.
    # Clamp 1..120s để tránh busy-loop (0) hay treo worker (quá lớn).
    try:
        return _cfg_float(name_lower, float(default), 1.0, 120.0)
    except Exception:
        return float(default)


def _get_webdav_port():
    # CC-005 P1: port WebDAV 8822 hard-code -> ENV NAS_WEBDAV_PORT (default 8822).
    return _cfg_int("webdav_port", 8822, 1, 65535)


def _get_webdav_host_default():
    # CC-005 P1: IP default trong Host header fallback -> ENV NAS_WEBDAV_HOST_DEFAULT.
    return _cfg_str("webdav_host_default", "192.168.100.254")


def _get_guest_ftp_root():
    # CC-005 P2: GUEST_FTP_ROOT hard-code -> ENV NAS_GUEST_FTP_ROOT.
    return _cfg_str("guest_ftp_root", "/srv/dev-disk-by-label-data")


def _get_vsftpd_user_dir():
    # CC-005 P2: VSFTPD_USER_DIR hard-code -> ENV NAS_VSFTPD_USER_DIR.
    return _cfg_str("vsftpd_user_dir", "/etc/vsftpd/userconf")


def _recommended_caps_by_ram():
    try:
        if psutil is not None:
            total_mb = int(psutil.virtual_memory().total / (1024 * 1024))
        else:
            total_mb = 1024
    except Exception:
        total_mb = 1024
    if total_mb <= 1500:
        rec_live, rec_social = 2, 2
    elif total_mb <= 3000:
        rec_live, rec_social = 3, 4
    else:
        rec_live, rec_social = 4, 8
    return total_mb, rec_live, rec_social

import tornado.ioloop
import tornado.web
import tornado.websocket

main_loop = None # Gắn với main thread để các background thread gọi callback



from flask import Flask, request, jsonify, Response

try:
    import rust_bridge
except ImportError:
    try:
        from backend import rust_bridge
    except ImportError:
        rust_bridge = None

try:
    import psutil
except ImportError:
    print("Thiếu thư viện psutil. Cài đặt: pip3 install psutil")
    sys.exit(1)

app = Flask(__name__)

# CLIENT-FIRST: user dang duyet (API hit gan day) -> daemon nang (thumb,
# cleanup) nhuong CPU de serve bytes. May tu render, NAS chi doc file.
_LAST_USER_HIT = {"time": 0.0}
# SPEC_LIVESTREAM_LIMITS §3.1: giu ten bien de khong vo code cu; runtime doc qua
# _get_user_idle_s() (env NAS_USER_IDLE_S > conf > 90). _background_heavy_work_allowed
# da dung getter, bien nay chi la default.
_USER_IDLE_S = 90  # khong co request nao 90s moi cho daemon chay

_NON_INTERACTIVE_HIT_PREFIXES = (
    "/api/ping", "/api/v1/ping",
    "/api/work/", "/api/v1/work/",
    "/api/system/idle", "/api/v1/system/idle",
    "/api/disk/fast_index", "/api/v1/disk/fast_index",
    "/api/duplicates/file", "/api/v1/duplicates/file",
    "/api/duplicates/hash", "/api/v1/duplicates/hash",
    "/api/duplicates/content", "/api/v1/duplicates/content",
    "/api/thumb/exists", "/api/v1/thumb/exists",
    "/api/thumb/upload", "/api/v1/thumb/upload",
    "/api/thumb/activity", "/api/v1/thumb/activity",
    "/api/alerts/poll", "/api/v1/alerts/poll",
    "/api/status", "/api/v1/status",
    "/api/tools/smart_organize/", "/api/v1/tools/smart_organize/",
    "/api/screen_record/", "/api/v1/screen_record/",
    "/api/thumb/status", "/api/v1/thumb/status",
    "/api/admin/limits", "/api/v1/admin/limits",
    "/api/livestream/status", "/api/v1/livestream/status",
    "/api/ytdlp/status", "/api/v1/ytdlp/status",
    "/api/cron/status", "/api/v1/cron/status",
    "/api/disk/health", "/api/v1/disk/health",
)

@app.before_request
def _stamp_user_hit():
    try:
        req_path = request.path or ""
        if any(req_path.startswith(p) for p in _NON_INTERACTIVE_HIT_PREFIXES):
            return
        _LAST_USER_HIT["time"] = time.time()
    except Exception:
        pass

FILE_INDEX_FRESHNESS_SECONDS = 60.0
_index_lock = threading.Lock()
_index_scan_lock = threading.Lock()
_index_mutation_wake = threading.Event()

def _remove_diacritics_py(s):
    if not s:
        return ""
    bridge = globals().get("rust_bridge")
    if bridge and hasattr(bridge, "remove_diacritics"):
        res = bridge.remove_diacritics(s)
        if res is not None:
            return res
    import unicodedata
    nfd = unicodedata.normalize('NFD', str(s))
    s_clean = "".join(c for c in nfd if unicodedata.category(c) != 'Mn')
    return s_clean.replace('đ', 'd').replace('Đ', 'd')

def _init_index_db():
    try:
        conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        try:
            conn.execute("PRAGMA journal_mode=WAL;")
            conn.execute("""
            CREATE TABLE IF NOT EXISTS file_index (
                path TEXT PRIMARY KEY,
                name TEXT,
                norm_name TEXT,
                is_dir INTEGER,
                size INTEGER,
                mtime INTEGER,
                mime TEXT
            );
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_norm_name ON file_index(norm_name);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_file_search_order ON file_index(is_dir DESC,name ASC,norm_name)")
            if "parent" not in [row[1] for row in conn.execute("PRAGMA table_info(file_index)")]:
                conn.execute("ALTER TABLE file_index ADD COLUMN parent TEXT")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_file_parent ON file_index(parent)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_file_time_path ON file_index(mtime DESC,path) WHERE is_dir=0")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_file_size_path ON file_index(size,is_dir,path)")
            conn.execute("CREATE TABLE IF NOT EXISTS index_directories (path TEXT PRIMARY KEY, stamp TEXT, complete INTEGER NOT NULL DEFAULT 0)")
            conn.execute("CREATE TABLE IF NOT EXISTS index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER NOT NULL, ready INTEGER NOT NULL, incomplete_reason TEXT)")
            state_columns = [row[1] for row in conn.execute("PRAGMA table_info(index_state)")]
            if "scanned_at" not in state_columns:
                conn.execute("ALTER TABLE index_state ADD COLUMN scanned_at REAL NOT NULL DEFAULT 0")
            if "built" not in state_columns:
                conn.execute("ALTER TABLE index_state ADD COLUMN built INTEGER NOT NULL DEFAULT 0")
            conn.execute("UPDATE index_state SET ready=0, incomplete_reason='scan_pending'")
            # Moc 1: bang luu chu ky anh (aHash) chung cho tat ca client (Android/Web/PC).
            # Tung client chi can gui path + aHash len NAS, cac client khac tai su dung
            # thay tinh toan lai lan luot -> giam tai NAS va tien luong mang.
            conn.execute("""CREATE TABLE IF NOT EXISTS image_fingerprints (
                path TEXT PRIMARY KEY,
                source_version TEXT NOT NULL DEFAULT '',
                ahash TEXT NOT NULL DEFAULT '',
                size INTEGER NOT NULL DEFAULT 0,
                mtime INTEGER NOT NULL DEFAULT 0,
                updated_at REAL NOT NULL DEFAULT 0
            )""")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_image_fps_ahash ON image_fingerprints(ahash)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_image_fps_version ON image_fingerprints(source_version)")
            # Moc 2: bang luu tag phan loai anh (AI tags) chung cho catalogue.
            # Tag duoc luu lien ket voi catalogue_revision de client biet khi nao can reload.
            conn.execute("""CREATE TABLE IF NOT EXISTS ai_image_tags (
                path TEXT PRIMARY KEY,
                category TEXT NOT NULL DEFAULT 'Khac',
                catalogue_revision INTEGER NOT NULL DEFAULT 0,
                updated_at REAL NOT NULL DEFAULT 0
            )""")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_ai_tags_category ON ai_image_tags(category)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_ai_tags_revision ON ai_image_tags(catalogue_revision)")
            conn.commit()
        finally:
            conn.close()
        nas_work_coordinator.initialize(INDEX_DB_PATH)
    except Exception as e:
        log.warning("[FileIndex] DB init error: %s" % str(e))


def _index_directory_stamp(path):
    st = os.stat(path)
    return "%s:%s:%s:%s" % (st.st_dev, st.st_ino, repr(st.st_mtime), repr(st.st_ctime))


def _index_storage_boundary(path):
    candidate = os.path.realpath(path)
    while True:
        parent = os.path.dirname(candidate)
        if parent == candidate:
            return ""  # never expand a public directory to the filesystem root
        if os.path.ismount(candidate):
            return candidate
        candidate = parent


def _index_resolve_directory(path, root, storage_boundary):
    resolved = os.path.realpath(path)
    allowed = [root] + ([storage_boundary] if storage_boundary else [])
    if not any(os.path.normcase(resolved) == os.path.normcase(base) or
               os.path.normcase(resolved).startswith(os.path.normcase(base + os.sep)) for base in allowed):
        return None
    scope = root if (os.path.normcase(resolved) == os.path.normcase(root) or
                     os.path.normcase(resolved).startswith(os.path.normcase(root + os.sep))) else storage_boundary
    parts = os.path.relpath(resolved, scope).split(os.sep)
    if any(part != "." and (part.startswith(".") or part in ("@eaDir", "#recycle")) for part in parts):
        return None
    if _index_storage_boundary(resolved) != storage_boundary:
        return None
    if os.path.normcase(resolved) == os.path.normcase(os.path.abspath(path)):
        return resolved
    # Keep logical public aliases, but do not return to any resolved ancestor.
    parent = os.path.dirname(os.path.abspath(path))
    root_key = os.path.normcase(os.path.abspath(root))
    while os.path.normcase(parent) == root_key or os.path.normcase(parent).startswith(root_key + os.sep):
        if os.path.normcase(os.path.realpath(parent)) == os.path.normcase(resolved):
            return None
        if os.path.normcase(parent) == root_key:
            break
        parent = os.path.dirname(parent)
    return resolved


def _index_readiness(conn):
    row = conn.execute("SELECT root, generation, ready, incomplete_reason FROM index_state WHERE id=1").fetchone()
    if not row:
        return {"index_ready": False, "index_generation": 0, "index_incomplete_reason": "scan_pending"}
    return {"index_ready": bool(row[2]), "index_generation": row[1], "index_incomplete_reason": row[3] or ""}


def _scan_file_index(search_root):
    if not _index_scan_lock.acquire(False):
        return {"index_ready": False, "index_incomplete_reason": "scan_in_progress", "checked_dirs": 0, "enumerated_dirs": 0}
    try:
        return _run_file_index_job(search_root)
    finally:
        _index_scan_lock.release()


def _background_file_index_refresh(search_root):
    try:
        # Search updates the interactive-idle timer; keep NAS resource gates
        # while allowing its own demand refresh to run.
        _run_file_index_job(search_root, ignore_user_idle=True)
    except Exception as e:
        log.warning("[FileIndex] Background refresh incomplete: %s", e)
    finally:
        _index_scan_lock.release()


def _mark_file_index_unready(root, reason):
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        with _index_lock:
            conn.execute("UPDATE index_state SET ready=0,incomplete_reason=? WHERE id=1 AND root=?", (reason, root))
            conn.commit()
    finally:
        if conn is not None:
            conn.close()


def _index_mutated_directories(directories):
    """Invalidate the nearest indexed parents after a successful batch of writes."""
    if not directories:
        return
    conn = None
    try:
        root = _get_webdav_public_dir()
        if not root:
            return
        root = os.path.realpath(root)
        parents = set()
        for directory in directories:
            directory = os.path.abspath(directory)
            if directory == root or directory.startswith(root + os.sep):
                rel = os.path.relpath(directory, root)
                parents.add("" if rel == "." else rel.replace(os.sep, "/") + "/")
        if not parents:
            return
        conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
        marker = "mutation-" + uuid.uuid4().hex
        with _index_lock:
            conn.execute("BEGIN IMMEDIATE")
            if not conn.execute("SELECT 1 FROM index_state WHERE id=1 AND root=?", (root,)).fetchone():
                return
            for parent in parents:
                while not conn.execute("UPDATE index_directories SET stamp=?,complete=0 WHERE path=?",
                                       (marker, parent)).rowcount and parent:
                    parent = parent.rstrip("/").rpartition("/")[0]
                    parent = parent + "/" if parent else ""
            nas_work_coordinator.bump_catalogue_revision(conn)
            conn.execute("UPDATE index_state SET ready=0,incomplete_reason='file_mutation_pending',scanned_at=0 WHERE id=1")
            conn.commit()
    except (OSError, ValueError, sqlite3.Error) as error:
        log.warning("[FileIndex] Cannot record changed directories: %s", error)
    finally:
        if conn is not None:
            conn.close()
        _index_mutation_wake.set()


def _run_file_index_job(search_root, ignore_user_idle=False):
    root = os.path.realpath(search_root) if search_root else ""
    if not root:
        return {"index_ready": False, "index_incomplete_reason": "invalid_root", "checked_dirs": 0, "enumerated_dirs": 0}
    nas_work_coordinator.request_job(INDEX_DB_PATH, "index", root, priority=100)
    if not _background_heavy_work_allowed(ignore_user_idle=ignore_user_idle):
        _mark_file_index_unready(root, "nas_busy")
        return {"index_ready": False, "index_incomplete_reason": "nas_busy", "checked_dirs": 0, "enumerated_dirs": 0}
    lease = nas_work_coordinator.claim_next(
        INDEX_DB_PATH, "nas:index", True, lease_seconds=3600, job_name="index")
    if not lease:
        _mark_file_index_unready(root, "maintenance_busy")
        return {"index_ready": False, "index_incomplete_reason": "maintenance_busy", "checked_dirs": 0, "enumerated_dirs": 0}
    try:
        result = _scan_file_index_pass(root, lease, ignore_user_idle=ignore_user_idle)
        if result.get("work_state") == "paused":
            return result
        conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
        try:
            total = conn.execute("SELECT COUNT(*) FROM index_directories").fetchone()[0]
        finally:
            conn.close()
        checked = result.get("checked_dirs", 0)
        complete = bool(result.get("index_ready"))
        nas_work_coordinator.checkpoint(
            INDEX_DB_PATH, "index", lease["owner"], lease["token"], "",
            min(checked, total), total, state="complete" if complete else "retry",
            error="" if complete else result.get("index_incomplete_reason", "incomplete"))
        result["work_state"] = "complete" if complete else "retry"
        return result
    except Exception as exc:
        nas_work_coordinator.checkpoint(
            INDEX_DB_PATH, "index", lease["owner"], lease["token"], "", 0, 0,
            state="retry", error=str(exc))
        raise


def _refresh_file_index_for_search():
    """Warm indexes validate directory changes at most once every five seconds."""
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        row = conn.execute("SELECT root, scanned_at, built FROM index_state WHERE id=1").fetchone()
    finally:
        if conn is not None:
            conn.close()
    # A first build/root migration belongs to the background watchdog, not an
    # API worker: the existing NAS tree can take minutes to enumerate initially.
    current_root = _get_webdav_public_dir()
    if not current_root or _index_scan_lock.locked():
        return
    same_root = bool(row and row[0] == os.path.realpath(current_root))
    if same_root and time.time() - float(row[1]) < 5.0:
        return
    if not _index_scan_lock.acquire(False):
        return
    try:
        threading.Thread(target=_background_file_index_refresh, args=(current_root,), daemon=True).start()
    except Exception:
        _index_scan_lock.release()
        raise


def _scan_file_index_pass(search_root, work_lease=None, ignore_user_idle=False):
    """Stat known directories; reconcile only stable successful directory reads."""
    import stat
    root = os.path.realpath(search_root) if search_root else ""
    if not root or root == os.path.abspath(os.sep) or not os.path.isdir(root):
        raise ValueError("Invalid WebDAV index root")
    storage_boundary = _index_storage_boundary(root)
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_30", 30.0))
    enumerated = 0
    checked = 0
    try:
        with _index_lock:
            state = conn.execute("SELECT root, generation, built FROM index_state WHERE id=1").fetchone()
            generation = state[1] if state else 1
            built = bool(state and state[2])
            if state and state[0] != root:
                generation += 1
                built = False
                conn.execute("DELETE FROM file_index")
                conn.execute("DELETE FROM index_directories")
                nas_work_coordinator.bump_catalogue_revision(conn)
            if state:
                conn.execute("UPDATE index_state SET root=?, generation=?, ready=0, incomplete_reason='scan_pending', built=?, scanned_at=0 WHERE id=1", (root, generation, int(built)))
            else:
                conn.execute("INSERT INTO index_state (id,root,generation,ready,incomplete_reason,built) VALUES (1, ?, ?, 0, 'scan_pending', ?)", (root, generation, int(built)))
            conn.execute("INSERT OR IGNORE INTO index_directories (path) VALUES ('')")
            conn.commit()
        # One-time legacy migration preserves provisional search results and uses
        # bounded commits; no file stat is needed to recover each row's parent.
        while True:
            rows = conn.execute("SELECT path FROM file_index WHERE parent IS NULL LIMIT 1000").fetchall()
            if not rows:
                break
            parents = []
            for row in rows:
                bare = row[0].rstrip("/")
                parent = bare.rsplit("/", 1)[0] + "/" if "/" in bare else ""
                parents.append((parent, row[0]))
            with _index_lock:
                conn.executemany("UPDATE file_index SET parent=? WHERE path=?", parents)
                nas_work_coordinator.bump_catalogue_revision(conn)
                conn.commit()
        if not state:
            with _index_lock:
                conn.execute("INSERT OR IGNORE INTO index_directories (path) SELECT path FROM file_index WHERE is_dir=1")
                conn.commit()
        pending = [row[0] for row in conn.execute("SELECT path FROM index_directories ORDER BY path")]
        visited = set()
        def check_batch(rel):
            directory_total = conn.execute("SELECT COUNT(*) FROM index_directories").fetchone()[0]
            if not _background_heavy_work_allowed(ignore_user_idle=ignore_user_idle):
                with _index_lock:
                    conn.execute("UPDATE index_state SET ready=0,incomplete_reason='nas_busy' WHERE id=1 AND root=?", (root,))
                    conn.commit()
                if not nas_work_coordinator.checkpoint(
                    INDEX_DB_PATH, "index", work_lease["owner"], work_lease["token"], rel,
                    min(checked, directory_total), directory_total, state="paused", error="nas_busy"):
                    raise RuntimeError("index_work_lease_lost")
                return {"index_ready": False, "index_incomplete_reason": "nas_busy",
                        "checked_dirs": checked, "enumerated_dirs": enumerated, "work_state": "paused"}
            if not nas_work_coordinator.checkpoint(
                INDEX_DB_PATH, "index", work_lease["owner"], work_lease["token"], rel,
                min(checked, directory_total), directory_total, state="running"):
                raise RuntimeError("index_work_lease_lost")

        for rel in pending:
            if rel in visited:
                continue
            visited.add(rel)
            saved = conn.execute("SELECT stamp, complete FROM index_directories WHERE path=?", (rel,)).fetchone()
            if saved is None:  # an earlier successful parent read pruned this subtree
                continue
            path = os.path.join(root, *rel.rstrip("/").split("/")) if rel else root
            checked += 1
            if work_lease and checked % 25 == 0:
                paused = check_batch(rel)
                if paused:
                    return paused
            try:
                if ".." in rel.split("/") or rel.startswith(("/", "\\")):
                    raise ValueError("Unsafe directory path")
                before = _index_directory_stamp(path)
                if saved[0] == before and not os.path.islink(path):
                    continue
                if _index_resolve_directory(path, root, storage_boundary) is None:
                    raise ValueError("Excluded directory boundary or ancestor cycle")
                if saved[0] == before:
                    continue
                entries = []
                children = []
                complete = True
                _dir_bridge = globals().get("rust_bridge")
                _rust_dir = None
                if _dir_bridge and hasattr(_dir_bridge, "scan_directory_entries"):
                    _rust_dir = _dir_bridge.scan_directory_entries(path, rel)
                if _rust_dir is not None:
                    entries = [tuple(e) for e in _rust_dir.get("entries", [])]
                    names = _rust_dir.get("special", [])
                else:
                    names = os.listdir(path)
                for offset, name in enumerate(names):
                    if work_lease and offset and offset % 1024 == 0:
                        paused = check_batch(rel)
                        if paused:
                            return paused
                    if name.startswith(".") or name in ("@eaDir", "#recycle"):
                        continue
                    full = os.path.join(path, name)
                    st = os.lstat(full)
                    if stat.S_ISLNK(st.st_mode):
                        if (not os.path.isdir(full) or
                                _index_resolve_directory(full, root, storage_boundary) is None):
                            complete = False
                            continue
                        st = os.stat(full)
                    is_dir = stat.S_ISDIR(st.st_mode)
                    if is_dir and _index_resolve_directory(full, root, storage_boundary) is None:
                        complete = False
                        continue
                    child = rel + name + ("/" if is_dir else "")
                    entries.append((child, name, _remove_diacritics_py(name.lower()), int(is_dir),
                                    0 if is_dir else st.st_size, int(st.st_mtime * 1000),
                                    "folder" if is_dir else (mimetypes.guess_type(name)[0] or "application/octet-stream"), rel))
                    if is_dir:
                        children.append(child)
                if before != _index_directory_stamp(path):
                    raise OSError("Directory changed during enumeration")
                enumerated += 1
                present = set(entry[0] for entry in entries)
                with _index_lock:
                    if not conn.execute("SELECT 1 FROM index_directories WHERE path=? AND stamp IS ?", (rel, saved[0])).fetchone():
                        continue
                    old = conn.execute("SELECT path,name,norm_name,is_dir,size,mtime,mime,parent FROM file_index WHERE parent=?", (rel,)).fetchall()
                    previous = dict((row[0], row) for row in old)
                    removed = False
                    for old_row in old:
                        old_path, old_dir = old_row[0], old_row[3]
                        if old_path in present:
                            continue
                        removed = True
                        if old_dir:
                            conn.execute("DELETE FROM file_index WHERE path>=? AND path<?", (old_path, old_path.rstrip("/") + "0"))
                            conn.execute("DELETE FROM index_directories WHERE path>=? AND path<?", (old_path, old_path.rstrip("/") + "0"))
                        else:
                            conn.execute("DELETE FROM file_index WHERE path=?", (old_path,))
                    changed = [entry for entry in entries if previous.get(entry[0]) != entry]
                    conn.executemany("INSERT OR REPLACE INTO file_index (path,name,norm_name,is_dir,size,mtime,mime,parent) VALUES (?,?,?,?,?,?,?,?)", changed)
                    conn.executemany("INSERT OR IGNORE INTO index_directories (path) VALUES (?)", [(child,) for child in children])
                    conn.execute("UPDATE index_directories SET stamp=?, complete=? WHERE path=?", (before, int(complete), rel))
                    if removed or changed:
                        nas_work_coordinator.bump_catalogue_revision(conn)
                    conn.commit()
                pending.extend(child for child in children if child not in visited)
            except (OSError, ValueError) as e:
                # Keep the previous listing, but retry this directory next pass.
                with _index_lock:
                    conn.execute("UPDATE index_directories SET stamp=NULL, complete=0 WHERE path=?", (rel,))
                    conn.commit()
                log.warning("[FileIndex] Directory read incomplete: %s (%s)", rel, e)
            time.sleep(0.01)
        with _index_lock:
            incomplete = conn.execute("SELECT COUNT(*) FROM index_directories WHERE complete=0 OR stamp IS NULL").fetchone()[0]
            unread = conn.execute("SELECT COUNT(*) FROM index_directories WHERE stamp IS NULL").fetchone()[0]
            conn.execute("UPDATE index_state SET ready=?, incomplete_reason=?, scanned_at=?, built=? WHERE id=1",
                         (int(incomplete == 0), "unreadable_or_symlink_scope" if incomplete else "", time.time(), int(built or unread == 0)))
            conn.commit()
        result = _index_readiness(conn)
        result.update({"checked_dirs": checked, "enumerated_dirs": enumerated})
        if result.get("index_ready"):
            bridge = globals().get("rust_bridge")
            if bridge and hasattr(bridge, "schedule_fts_rebuild"):
                bridge.schedule_fts_rebuild(INDEX_DB_PATH)
        return result
    finally:
        if conn is not None:
            conn.close()

def _get_webdav_public_dir():
    import errno
    conf = "/var/www/webdav/config/config.php"
    try:
        os.lstat(conf)
    except OSError as e:
        if e.errno != errno.ENOENT:
            log.error("[FileIndex] Cannot inspect WebDAV config: %s", e)
            return ""
    else:
        try:
            with open(conf, "r", encoding="utf-8") as f:
                content = f.read()
                m = _re_module.search(r'\$publicDir\s*=\s*[\'"]([^\'"]+)[\'"]', content)
                if (m and os.path.isdir(m.group(1)) and
                        os.path.realpath(m.group(1)) != os.path.abspath(os.sep)):
                    return m.group(1)
            log.error("[FileIndex] Configured WebDAV publicDir is unavailable or invalid")
        except Exception as e:
            log.error("[FileIndex] Cannot read WebDAV config: %s", e)
        return ""  # a configured scope must never expand to a broader fallback

    base_dirs = ["/srv/dev-disk-by-label-data/New folder", "/srv/dev-disk-by-label-data", "/sharedfolders/Data", "/var/www/webdav/public"]
    for bd in base_dirs:
        if os.path.isdir(bd):
            return bd
    # CC-004 P0-1: KHONG bao gio fallback "/" — indexer os.walk("/") se quet
    # toan filesystem, batch REPLACE SQLite vo han, CPU/RAM/timeout. Tra ""
    # de watchdog return som + log ERROR.
    log.error("[FileIndex] Khong tim thay WebDAV root hop le (config.php + 4 base_dir deu miss)")
    return ""

def _file_indexer_watchdog():
    """Reuse directory listings across passes and daemon restarts."""
    while True:
        _index_mutation_wake.clear()
        try:
            result = _scan_file_index(_get_webdav_public_dir())
            log.info("[FileIndex] Checked %s directories, enumerated %s, ready=%s",
                     result["checked_dirs"], result["enumerated_dirs"], result["index_ready"])
        except Exception as e:
            log.warning("[FileIndex] Index pass incomplete: %s", e)
        if _index_mutation_wake.wait(1800):
            time.sleep(5)  # Coalesce completed writes before invoking the idle-gated index job.

@app.route('/api/search', methods=['GET'])
def api_fast_search():
    query = request.args.get('q', '').strip()
    root_param = request.args.get('root', '').strip('/')
    if not query:
        return jsonify({"success": True, "results": [], "index_ready": False})
    
    norm_query = _remove_diacritics_py(query.lower())

    # CC-004 P0-3: chan Host-header injection + LIKE-wildcard + '..'.
    # host_header do client gui, khong duoc dap tho vao URL tra ve.
    # CC-005 P1/P2: WebDAV port/host hard-code -> ENV NAS_WEBDAV_PORT /
    # NAS_WEBDAV_HOST_DEFAULT (default 8822 / 192.168.100.254).
    _cc005_wd_port = _get_webdav_port()
    _cc005_wd_host_dflt = _get_webdav_host_default()
    host_header = request.headers.get("Host", "%s:%s" % (_cc005_wd_host_dflt, _cc005_wd_port))
    host_ip = host_header.split(":")[0].strip()
    import re as _re_host
    if not _re_host.match(r'^[A-Za-z0-9.\-]+$', host_ip or ""):
        host_ip = "127.0.0.1"
    scheme = getattr(request, "scheme", "http")
    if scheme not in ("http", "https"):
        scheme = "http"
    webdav_base = "%s://%s:%s/webdav/" % (scheme, host_ip, _cc005_wd_port)
    # root_param chi la scope, nhung '..', '\', '/' dan toi lo ten file
    # ngoai scope -> tra rong thay vi mo rong pham vi.
    if ".." in root_param.split("/") or "\\" in root_param:
        return jsonify({"success": False, "results": [], "index_ready": False}), 400

    def _escape_like(s):
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    results = []
    try:
        _refresh_file_index_for_search()
        conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
        try:
            conn.execute("BEGIN")
            readiness = _index_readiness(conn)
            if _index_scan_lock.locked():
                readiness["index_ready"] = False
                readiness["index_incomplete_reason"] = "scan_in_progress"
            state = conn.execute("SELECT root FROM index_state WHERE id=1").fetchone()
            actual_root = _get_webdav_public_dir()
            if not actual_root or (state and state[0] != os.path.realpath(actual_root)):
                return jsonify({"success": False, "results": [], "index_ready": False,
                                "index_incomplete_reason": "root_changed_or_unavailable"}), 503
            rows = None
            bridge = globals().get("rust_bridge")
            if bridge and hasattr(bridge, "search_file_index"):
                rows = bridge.search_file_index(INDEX_DB_PATH, query, root_param, 300)
            if rows is None:
                cur = conn.cursor()
                like_pattern = "%%%s%%" % _escape_like(norm_query)
                if root_param:
                    cur.execute("""
                        SELECT path, name, is_dir, size, mtime, mime FROM file_index
                        WHERE path >= ? AND path < ? AND norm_name LIKE ? ESCAPE '\\'
                        ORDER BY is_dir DESC, name ASC LIMIT 300
                    """, (root_param + "/", root_param + "0", like_pattern))
                else:
                    cur.execute("SELECT path, name, is_dir, size, mtime, mime FROM file_index WHERE norm_name LIKE ? ESCAPE '\\' ORDER BY is_dir DESC, name ASC LIMIT 300", (like_pattern,))

                rows = cur.fetchall()
        finally:
            conn.close()

        import stat as _search_stat
        public_root = os.path.realpath(actual_root)
        storage_boundary = _index_storage_boundary(public_root)
        checked_parents = {}
        metadata_updates = []
        for rel_p, name, is_dir, size, mtime, mime in rows:
            if not is_dir:
                try:
                    if os.path.isabs(rel_p) or ".." in rel_p.split("/"):
                        raise ValueError("Unsafe indexed file path")
                    full_path = os.path.join(public_root, rel_p)
                    parent = os.path.dirname(full_path)
                    if parent not in checked_parents:
                        checked_parents[parent] = _index_resolve_directory(parent, public_root, storage_boundary)
                    if checked_parents[parent] is None:
                        raise ValueError("Indexed file parent is outside the public boundary")
                    current = os.lstat(full_path)
                    if not _search_stat.S_ISREG(current.st_mode):
                        raise ValueError("Indexed file is no longer a regular file")
                    previous_size, previous_mtime = size, mtime
                    size = current.st_size
                    mtime = int(current.st_mtime * 1000)
                    if (size, mtime) != (previous_size, previous_mtime):
                        metadata_updates.append((size, mtime, rel_p, previous_size, previous_mtime, public_root))
                except (OSError, ValueError):
                    readiness["index_ready"] = False
                    readiness["index_incomplete_reason"] = "file_metadata_unavailable"
                    continue
            # FIX-SEARCH-ENCODE: rel_p trong DB la raw (space/dau tieng Viet/#?&
            # giu nguyen). Ghep tho vao URL gay cut cuon o '#'/'?' va hong VLC/
            # ExoPlayer (space raw). Encode theo segment, giu '/' de client dung
            # truc tiep lam playback URL, khop toValidUrl/encodeWebDavSegment.
            try:
                quoted_rel = urllib.parse.quote(rel_p, safe="/")
            except Exception:
                quoted_rel = rel_p
            webdav_url = webdav_base.rstrip('/') + '/' + quoted_rel.lstrip('/')
            results.append({
                "name": name,
                "path": webdav_url,
                "isDirectory": bool(is_dir),
                "contentType": mime,
                "contentLength": size,
                "lastModified": mtime
            })
        if metadata_updates:
            cache_conn = None
            try:
                cache_conn = sqlite3.connect(INDEX_DB_PATH, timeout=0.1)
                cursor = cache_conn.executemany("UPDATE file_index SET size=?,mtime=? WHERE path=? AND size=? AND mtime=? AND EXISTS (SELECT 1 FROM index_state WHERE id=1 AND root=?)", metadata_updates)
                if cursor.rowcount > 0:
                    nas_work_coordinator.bump_catalogue_revision(cache_conn)
                cache_conn.commit()
            except sqlite3.Error as error:
                log.debug('[SearchAPI] Metadata persistence deferred: %s', error)
            finally:
                if cache_conn is not None:
                    cache_conn.close()
    except Exception as e:
        log.warning("[SearchAPI] Lỗi truy vấn SQLite index: %s" % str(e))
        return jsonify({"success": False, "results": [], "index_ready": False,
                        "index_incomplete_reason": "index_unavailable"}), 503

    payload = {"success": True, "results": results}
    payload.update(readiness)
    return jsonify(payload)

# -- Social Extractor Logic BEGIN ----------------------------------------------
# Keep this pure-stdlib block in the single production artifact. Tests load this
# marked block without importing the full Flask/Tornado daemon.
import copy as _social_copy
import ipaddress as _social_ipaddress
import socket as _social_socket
try:
    from urllib.parse import urlparse as _social_urlparse
except ImportError:
    from urlparse import urlparse as _social_urlparse

SOCIAL_MAX_CONCURRENT = 2
# HW-BUDGET (rk3328 4xA53/1GB): mỗi livestream = 1 ffmpeg + 1 yt-dlp.
# Không cap tổng thì 10 user live cùng lúc giết NAS — cap 2 job recording.
# SPEC_LIVESTREAM_LIMITS §3.1: default giu 2, runtime doc qua _get_social_max_concurrent()
# (env NAS_SOCIAL_MAX_CONCURRENT > conf > default, clamp 1-16). Khoi social block duoc
# load co lap trong tests (social_logic_loader) nen phai fallback khi ham loader chung
# chua ton tai.
LIVESTREAM_MAX_CONCURRENT = 2


def _social_effective_max_concurrent():
    try:
        _fn = globals().get("_get_social_max_concurrent")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    try:
        import os as _os_cap
        _raw = _os_cap.environ.get("NAS_SOCIAL_MAX_CONCURRENT", "")
        if str(_raw).strip() != "":
            _v = int(str(_raw).strip())
            if _v < 1:
                return 1
            if _v > 16:
                return 16
            return _v
    except Exception:
        pass
    try:
        return int(SOCIAL_MAX_CONCURRENT)
    except Exception:
        return 2


def _social_effective_job_ttl_s():
    try:
        _fn = globals().get("_get_social_job_ttl_s")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    return 3600


def _social_effective_cleanup_interval_s():
    try:
        _fn = globals().get("_get_social_cleanup_interval_s")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    return 300


def _social_effective_max_filesize():
    try:
        _fn = globals().get("_get_social_max_filesize")
        if callable(_fn):
            _v = str(_fn()).strip()
            if _v:
                return _v
    except Exception:
        pass
    try:
        import os as _os_fs
        _raw = _os_fs.environ.get("NAS_SOCIAL_MAX_FILESIZE", "")
        if str(_raw).strip() != "":
            return str(_raw).strip()
    except Exception:
        pass
    try:
        return str(SOCIAL_MAX_FILESIZE)
    except Exception:
        return "2G"


def _social_effective_ytdlp_timeout_s():
    try:
        _fn = globals().get("_get_ytdlp_timeout_s")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    try:
        import os as _os_to
        _raw = _os_to.environ.get("NAS_YTDLP_TIMEOUT_S", "")
        if str(_raw).strip() != "":
            _v = int(str(_raw).strip())
            if _v < 60:
                return 60
            if _v > 1800:
                return 1800
            return _v
    except Exception:
        pass
    try:
        return int(SOCIAL_YTDLP_TIMEOUT)
    except Exception:
        return 300
SOCIAL_MAX_FILESIZE = "2G"
SOCIAL_YTDLP_TIMEOUT = 300
SOCIAL_DEFAULT_FOLDER = "Downloads/social/"
_SOCIAL_TMP_ROOT = "/var/tmp/social_downloads"
_social_download_jobs = {}
_social_download_lock = threading.Lock()


def _social_jobs_cleanup_daemon():
    """P1-7: Background daemon to periodically evict expired social download jobs."""
    while True:
        try:
            time.sleep(_social_effective_cleanup_interval_s())
            _social_remove_expired_jobs(max_age_sec=_social_effective_job_ttl_s())
        except Exception:
            pass


def _social_is_public_ip(address):
    try:
        ip_obj = _social_ipaddress.ip_address(str(address).split("%", 1)[0])
    except ValueError:
        return False
    return not (
        ip_obj.is_private
        or ip_obj.is_loopback
        or ip_obj.is_link_local
        or ip_obj.is_reserved
        or ip_obj.is_multicast
        or ip_obj.is_unspecified
    )


def _social_host_matches(host, base_domain):
    return host == base_domain or host.endswith("." + base_domain)


def _social_is_supported_content_url(parsed, host):
    """Restrict yt-dlp entry points to content URLs on trusted platforms."""
    path = parsed.path or "/"
    path_lower = path.lower()
    query_lower = (parsed.query or "").lower()

    if host == "youtu.be":
        return bool(path.strip("/")) and "/" not in path.strip("/")
    if _social_host_matches(host, "youtube.com"):
        return (
            (path_lower == "/watch" and "v=" in query_lower)
            or path_lower.startswith(("/shorts/", "/live/", "/embed/"))
            or (path_lower.startswith("/@") and path_lower.rstrip("/").endswith("/live"))
        )

    if _social_host_matches(host, "tiktok.com"):
        if host in ("vm.tiktok.com", "vt.tiktok.com"):
            return bool(path.strip("/"))
        if path_lower.startswith("/t/"):
            return bool(path_lower[3:].strip("/"))
        return path_lower.startswith("/@") and (
            "/video/" in path_lower
            or "/photo/" in path_lower
            or path_lower.rstrip("/").endswith("/live")
        )

    if _social_host_matches(host, "douyin.com"):
        return bool(path.strip("/"))

    if _social_host_matches(host, "instagram.com"):
        return path_lower.startswith(("/reel/", "/p/", "/tv/", "/stories/"))

    if host == "fb.watch":
        return bool(path.strip("/"))
    if _social_host_matches(host, "facebook.com"):
        return (
            "/videos/" in path_lower
            or path_lower.startswith((
                "/reel/", "/stories/", "/s/", "/share/r/", "/share/v/", "/story.php", "/story/"
            ))
            or (path_lower in ("/watch", "/watch/") and "v=" in query_lower)
            or (path_lower == "/photo.php" and "v=" in query_lower)
        )

    return False


def _social_validate_url(url):
    """Accept supported public content URLs whose A/AAAA results are public."""
    if not url or not isinstance(url, str):
        return False
    url = url.strip()
    try:
        parsed = _social_urlparse(url)
        scheme = (parsed.scheme or "").lower()
        if scheme not in ("http", "https") or not parsed.netloc or "@" in parsed.netloc:
            return False
        host = (parsed.hostname or "").rstrip(".")
        port = parsed.port or (443 if scheme == "https" else 80)
    except Exception:
        return False
    if not host:
        return False
    try:
        host.encode("ascii")
    except UnicodeError:
        return False
    host = host.lower()
    if not _social_is_supported_content_url(parsed, host):
        return False
    try:
        resolved = _social_socket.getaddrinfo(host, port, 0, _social_socket.SOCK_STREAM)
    except (TypeError, ValueError, OSError, _social_socket.gaierror):
        return False
    return bool(resolved) and all(
        entry[4] and _social_is_public_ip(entry[4][0]) for entry in resolved
    )


def _social_sanitize_folder(folder):
    """Return a safe relative destination with at most two path levels."""
    if not folder or not isinstance(folder, str):
        return SOCIAL_DEFAULT_FOLDER
    folder = folder.strip().replace("\\", "/")
    if not folder or folder.startswith("/") or (len(folder) >= 2 and folder[1] == ":"):
        return SOCIAL_DEFAULT_FOLDER
    folder = folder.strip("/")
    parts = folder.split("/")
    if (
        not parts
        or len(parts) > 2
        or any(not part or part in (".", "..") for part in parts)
        or not _re_module.match(r"^[a-zA-Z0-9/_\- ]+$", folder)
    ):
        return SOCIAL_DEFAULT_FOLDER
    return "/".join(parts)


def _social_destination_dir(folder):
    """Resolve a WebDAV folder and reject symlink/path escapes from the root."""
    try:
        root = os.path.realpath(WEBDAV_FILE_ROOT)
        candidate = os.path.realpath(os.path.join(root, folder))
        if os.path.commonpath([root, candidate]) != root:
            return None
        return candidate
    except (AttributeError, OSError, ValueError):
        return None


def _social_destination_path(destination_dir, filename, job_id):
    """Allocate a collision-proof filename for one download job."""
    filename = os.path.basename(filename)
    base, extension = os.path.splitext(filename)
    safe_job_id = _re_module.sub(r"[^a-zA-Z0-9_-]", "", str(job_id))
    return os.path.join(destination_dir, "%s_%s%s" % (
        base or "download", safe_job_id, extension))


def _social_remove_expired_jobs(max_age_sec=3600):
    now = time.time()
    with _social_download_lock:
        expired = []
        for job_id, job in _social_download_jobs.items():
            timestamp = job.get("finished_at") or job.get("started_at", 0)
            if timestamp and now - timestamp > max_age_sec:
                expired.append(job_id)
        for job_id in expired:
            del _social_download_jobs[job_id]
    return len(expired)


def _social_create_job(url, folder):
    if not _social_validate_url(url):
        return None, "URL khong hop le hoac la private/internal."
    safe_folder = _social_sanitize_folder(folder)
    _social_remove_expired_jobs(max_age_sec=_social_effective_job_ttl_s())
    with _social_download_lock:
        active_count = sum(
            1 for job in _social_download_jobs.values()
            if job.get("status") in ("queued", "downloading")
        )
        _cap = _social_effective_max_concurrent()
        if active_count >= _cap:
            return None, "Max concurrent downloads reached (%d). Vui long thu lai sau." % _cap
        job_id = str(uuid.uuid4())
        _social_download_jobs[job_id] = {
            "job_id": job_id,
            "url": url,
            "folder": safe_folder,
            "status": "queued",
            "progress": 0,
            "filename": None,
            "size": 0,
            "platform": "other",
            "error_reason": None,
            "started_at": time.time(),
            "finished_at": None,
        }
    return job_id, None


def _social_get_job(job_id):
    with _social_download_lock:
        job = _social_download_jobs.get(job_id)
        return _social_copy.deepcopy(job) if job is not None else None


def _social_update_job(job_id, **changes):
    with _social_download_lock:
        job = _social_download_jobs.get(job_id)
        if job is None:
            return False
        job.update(changes)
        return True


def _social_mkdirp(path):
    try:
        os.makedirs(path, exist_ok=True)
        return True
    except OSError:
        return False
# -- Social Extractor Logic END ------------------------------------------------

def _get_webdav_root():
    """Đọc WebDAV root từ biến môi trường hoặc config OMV (/var/www/webdav/config/config.php)"""
    env_root = (os.environ.get("WEBDAV_ROOT") or "").strip()
    if env_root:
        return env_root.rstrip("/")
    config_file = "/var/www/webdav/config/config.php"
    try:
        if os.path.exists(config_file):
            with open(config_file, "r") as f:
                for line in f:
                    # Tìm dòng: $publicDir = '/path/to/dir';
                    if "$publicDir" in line and "=" in line:
                        path = line.split("'")[1] if "'" in line else line.split('"')[1]
                        return path.rstrip("/")
    except Exception:
        pass
    for candidate in (
        "/srv/dev-disk-by-label-data/New folder",
        "/sharedfolders/Data/New folder",
        "/var/www/webdav/public",
        "/srv/dev-disk-by-label-data",
    ):
        try:
            if os.path.isdir(candidate):
                return candidate
        except Exception:
            continue
    return "/srv/dev-disk-by-label-data/New folder"
WEBDAV_FILE_ROOT = _get_webdav_root()

SYSTEM_DB_DIR = "/var/lib/nas_api"
os.makedirs(SYSTEM_DB_DIR, exist_ok=True)
DB_PATH = INDEX_DB_PATH = os.path.join(SYSTEM_DB_DIR, "nas_index.db")

# Tự động migrate cơ sở dữ liệu cũ từ HDD sang bộ nhớ hệ thống OS partition (nếu có)
try:
    _old_db_path = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "nas_index.db")
    if os.path.exists(_old_db_path) and not os.path.exists(DB_PATH):
        # SQLite's online backup includes committed WAL pages. Publish only a complete snapshot.
        with tempfile.NamedTemporaryFile(dir=SYSTEM_DB_DIR, prefix=".system-db-", delete=False) as tmp_db:
            snapshot = tmp_db.name
        try:
            subprocess.check_call(["sqlite3", _old_db_path, ".backup " + snapshot])
            os.link(snapshot, DB_PATH)
        finally:
            os.unlink(snapshot)
        log.info("[DB] Đã di chuyển nas_index.db từ HDD sang bộ nhớ hệ thống OS: %s", DB_PATH)
except Exception as _mig_err:
    log.error("[DB] Lỗi khi copy DB cũ: %s", _mig_err)
    raise

_legacy_fast_index_db = "/var/lib/nas_fast_index.db"
nas_work_coordinator.import_legacy_catalogue(DB_PATH, _legacy_fast_index_db)
# Sau khi import catalogue legado thanh cong, doi ten db cu thanh .db.migrated
# de server khoi dong lan sau khong import lai va giam tai bo nho.
try:
    if os.path.isfile(_legacy_fast_index_db):
        _li_conn = sqlite3.connect(DB_PATH, timeout=30)
        try:
            _li_already = _li_conn.execute(
                "SELECT 1 FROM catalogue_imports WHERE source='nas_fast_index'").fetchone()
        finally:
            _li_conn.close()
        if _li_already:
            os.rename(_legacy_fast_index_db, _legacy_fast_index_db + ".migrated")
except Exception as _li_err:
    log.warning("[DB] Khong the doi ten nas_fast_index.db sau import: %s", _li_err)

PID_FILE = "/var/run/nas_api_server.pid"
LAN_WHITELIST_PATH = "/etc/nas/lan_whitelist.conf"
WEBDAV_LOG = "/var/log/nginx/openmediavault-webgui_access.log"
AI_TAGS_PATH = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "ai_tags.json")
NAS_TMP_ROOT = "/tmp/nas_meta_tmp"

def _get_hdd_tmp_root():
    """Tự động tìm thư mục tạm trên HDD để tránh tràn RAM tmpfs (/tmp)."""
    hdd_dir = _get_hdd_config_dir()
    if hdd_dir:
        tmp_root = os.path.join(hdd_dir, "nas_meta_tmp")
        try:
            os.makedirs(tmp_root, mode=0o700, exist_ok=True)
            return tmp_root
        except Exception:
            pass
    fallback_root = "/tmp/nas_meta_tmp"
    os.makedirs(fallback_root, mode=0o700, exist_ok=True)
    return fallback_root

def _make_hdd_tmp_dir(prefix):
    """Tạo thư mục tmp riêng trên HDD để tránh làm đầy /tmp tmpfs."""
    safe_prefix = _re_module.sub(r"[^A-Za-z0-9_.-]+", "_", str(prefix or "job")).strip("_") or "job"
    try:
        root_dir = _get_hdd_tmp_root()
        tmp_dir = os.path.join(
            root_dir,
            "%s_%d_%s" % (safe_prefix, int(time.time() * 1000), uuid.uuid4().hex[:8])
        )
        os.makedirs(tmp_dir, exist_ok=True)
        return tmp_dir
    except Exception as e:
        log.warning("[TMP] Không tạo được thư mục tạm trên HDD: %s", e)
        return ""

def _job_env_with_tmp(tmp_dir):
    env = os.environ.copy()
    if tmp_dir:
        env["TMPDIR"] = tmp_dir
        env["TMP"] = tmp_dir
        env["TEMP"] = tmp_dir
    return env

def _cleanup_job_tmp(tmp_dir):
    if not tmp_dir:
        return
    try:
        root_real = os.path.realpath(NAS_TMP_ROOT)
        tmp_real = os.path.realpath(tmp_dir)
        if tmp_real.startswith(root_real + os.sep) and os.path.isdir(tmp_real):
            shutil.rmtree(tmp_real, ignore_errors=True)
            log.info("[TMP] Đã dọn thư mục tạm của tác vụ: %s", tmp_real)
    except Exception as e:
        log.warning("[TMP] Lỗi dọn thư mục tạm của tác vụ: %s", e)

def _cleanup_stale_job_tmp(max_age_hours=24):
    try:
        if not os.path.isdir(NAS_TMP_ROOT):
            return 0
        now = time.time()
        max_age = max_age_hours * 3600
        deleted = 0
        for name in os.listdir(NAS_TMP_ROOT):
            path = os.path.join(NAS_TMP_ROOT, name)
            try:
                if os.path.isdir(path) and (now - os.path.getmtime(path)) > max_age:
                    shutil.rmtree(path, ignore_errors=True)
                    deleted += 1
            except Exception:
                continue
        return deleted
    except Exception:
        return 0


def _cleanup_livestream_junk():
    """Xoa file rac trong Livestream: .tmpchunk va .ts 0B"""
    try:
        if not os.path.isdir(_LIVESTREAM_DIR):
            return
        active_paths = set()
        active_basenames = set()
        try:
            with _livestream_lock:
                for info in _livestream_jobs.values():
                    if info.get("status") == "recording":
                        dp = info.get("direct_output_path", "")
                        if dp:
                            active_paths.add(dp)
                            active_basenames.add(os.path.basename(dp))
                        out_file = info.get("output_file", "")
                        if out_file:
                            active_basenames.add(out_file)
                            active_paths.add(os.path.join(info.get("output_dir", _LIVESTREAM_DIR), out_file))
        except Exception:
            pass
        removed = 0
        remuxed_orphans = 0
        can_remux_orphan = not active_paths
        
        # Don file rac trong thu muc Livestream
        for fname in os.listdir(_LIVESTREAM_DIR):
            fpath = os.path.join(_LIVESTREAM_DIR, fname)
            if not os.path.isfile(fpath):
                continue
            if fname.endswith(".tmpchunk"):
                if fpath not in active_paths:
                    try:
                        os.remove(fpath)
                        removed += 1
                    except Exception:
                        pass
                continue
            if fname.endswith(".ts") and fpath not in active_paths:
                try:
                    fsize = os.path.getsize(fpath)
                    if fsize == 0:
                        os.remove(fpath)
                        removed += 1
                    elif fsize > 1024 and can_remux_orphan and remuxed_orphans < 1:
                        # Orphaned .ts file (sau khi restart NAS hoac bi bo quen)
                        # Remux đọc/ghi rất nặng; mỗi vòng chỉ xử lý 1 file và
                        # bỏ qua khi đang có recording để tránh giành I/O với HDD.
                        mtime = os.path.getmtime(fpath)
                        if time.time() - mtime > 60: # Khong bi sua trong 60s qua
                            mp4_path = _remux_flv_to_mp4(fpath)
                            if mp4_path:
                                remuxed_orphans += 1
                                log.info("[Livestream] Remuxed orphaned file: %s", fname)
                except Exception:
                    pass
                    
        # Don file rac trong /tmp/
        try:
            if os.path.exists("/tmp"):
                # FIX-ENOSPC: wrapper loop_*.py bản cũ ghi thẳng /tmp tmpfs.
                # Bản mới ghi sang HDD tmp, nhưng phải dọn file mồ côi còn sót.
                try:
                    with _livestream_lock:
                        _active_wrappers = set()
                        for _info in _livestream_jobs.values():
                            if _info.get("status") == "recording":
                                _wd = _info.get("wrapper_dir", "")
                                if _wd:
                                    _active_wrappers.add(os.path.realpath(_wd))
                except Exception:
                    _active_wrappers = set()
                for fname in os.listdir("/tmp"):
                    if fname.startswith("livestream_tmp_") and fname.endswith(".tmpchunk"):
                        fpath = os.path.join("/tmp", fname)
                        orig_basename = fname.replace("livestream_tmp_", "").replace(".tmpchunk", "")
                        if orig_basename not in active_basenames:
                            try:
                                os.remove(fpath)
                                removed += 1
                            except Exception:
                                pass
                    elif fname.startswith("loop_") and fname.endswith(".py"):
                        # Wrapper livestream mồ côi (job đã chết, file còn lại).
                        fpath = os.path.join("/tmp", fname)
                        try:
                            if (time.time() - os.path.getmtime(fpath)) > 3600:
                                os.remove(fpath)
                                removed += 1
                        except Exception:
                            pass
        except Exception:
            pass

        if removed:
            log.info("[Livestream] Don %d file rac trong Livestream va /tmp.", removed)
    except Exception as e:
        log.warning("[Livestream] Loi don file rac: %s", e)

def _cleanup_runtime_tmp_artifacts(max_age_minutes=30):
    """Dọn rác tmp do PyInstaller/ffmpeg để lại, không đụng vào socket hệ thống."""
    deleted = 0
    now = time.time()
    max_age = max_age_minutes * 60
    scan_roots = ["/tmp", "/var/tmp/yt-dlp-tmp"]
    for root in scan_roots:
        try:
            if not os.path.isdir(root):
                continue
            for name in os.listdir(root):
                if not (name.startswith("_MEI") or name.startswith("ffmpeg") or name.startswith("flv_repair")):
                    continue
                path = os.path.join(root, name)
                try:
                    if (now - os.path.getmtime(path)) < max_age:
                        continue
                    if os.path.isdir(path):
                        shutil.rmtree(path, ignore_errors=True)
                        deleted += 1
                    elif os.path.isfile(path):
                        os.remove(path)
                        deleted += 1
                except Exception:
                    continue
        except Exception:
            continue
    if deleted:
        log.info("[TMP] Đã dọn %d tệp tạm trong /tmp và /var/tmp.", deleted)
    return deleted

def init_db():
    """FIX: KHÔNG được crash server khi HDD bi I/O error.

    Truoc day: init_db chay ngay khi import module va goi sqlite3.connect tren
    DB_PATH (nam tren HDD). Neu HDD bi lỗi (filesystem ro/inode hong) thi
    OperationalError -> module import fail -> systemd restart loop vinh vien.

    Logic moi: bat het exception, log warning, return False. Server van len
    được, cac endpoint dùng @requires_auth se fallback DB-less va van dang
    nhap được bang Basic Auth.
    """
    try:
        try:
            os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
        except OSError as e:
            log.warning("[init_db] Không tạo được thư mục (HDD có thể lỗi): %s", e)
            # Không return — th? ti?p connect xem co the DB file van con OK
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        cur = conn.cursor()
        try:
            cur.execute("PRAGMA journal_mode=WAL")
            cur.execute("PRAGMA synchronous=NORMAL")
            cur.execute("PRAGMA busy_timeout=5000")
        except Exception:
            pass
        cur.execute('CREATE TABLE IF NOT EXISTS banned_ips (ip TEXT PRIMARY KEY, reason TEXT, banned_at DATETIME)')
        cur.execute('CREATE TABLE IF NOT EXISTS auth_attempts (ip TEXT PRIMARY KEY, count INTEGER)')
        cur.execute('CREATE TABLE IF NOT EXISTS authorized_ips (ip TEXT PRIMARY KEY, added_at DATETIME)')
        cur.execute('CREATE TABLE IF NOT EXISTS system_logs (id INTEGER PRIMARY KEY, type TEXT, module TEXT, message TEXT, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)')
        cur.execute('CREATE INDEX IF NOT EXISTS idx_system_logs_id ON system_logs (id DESC)')
        cur.execute('CREATE INDEX IF NOT EXISTS idx_system_logs_type ON system_logs (type)')
        cur.execute('CREATE TABLE IF NOT EXISTS system_temperature_history (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, cpu_temp REAL, hdd_temp REAL)')
        cur.execute('''
            CREATE TABLE IF NOT EXISTS system_metrics_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                cpu_percent REAL,
                ram_percent REAL,
                cpu_temp REAL,
                hdd_temp REAL,
                net_rx_kbps REAL,
                net_tx_kbps REAL
            )
        ''')
        cur.execute('''
            CREATE TABLE IF NOT EXISTS daily_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                report_date TEXT UNIQUE,
                report_json TEXT,
                generated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        cur.execute('''
            CREATE TABLE IF NOT EXISTS hardware_status (
                key TEXT PRIMARY KEY,
                payload_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        ''')
        cur.execute('''
            CREATE TABLE IF NOT EXISTS disk_health_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                device TEXT,
                score INTEGER,
                smart_status TEXT,
                temp_c INTEGER,
                payload_json TEXT NOT NULL
            )
        ''')
        cur.execute('CREATE INDEX IF NOT EXISTS idx_disk_health_history_ts ON disk_health_history(ts)')
        cur.execute('''
            CREATE TABLE IF NOT EXISTS scheduler_state (
                key TEXT PRIMARY KEY,
                payload_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        ''')
        cur.execute('''
            CREATE TABLE IF NOT EXISTS runtime_state (
                key TEXT PRIMARY KEY,
                payload_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        ''')
        conn.commit()
        conn.close()
        log.info("[init_db] DB san sang.")
    except Exception as e:
        # HDD/DB không dùng được -> server van phai len. Login se fallback
        # DB-less mode (chi Basic Auth, khong remember IP).
        log.error("[init_db] DB KHÔNG mở được — server chay che do DB-less: %s", e)

    # DỌN DẸP RÁC RAM (TMPFS) LỊCH SỬ KHI KHỞI ĐỘNG CỦA LỖI OOM
    try:
        shutil.rmtree("/tmp/nas_transcode", ignore_errors=True)
    except Exception:
        pass

init_db()

# ============ LAN IP WHITELIST ============
# Đọc danh sách IP LAN được phep truy cap (không cần Tailscale)
# File /etc/nas/lan_whitelist.conf, moi dong 1 IP hoac CIDR (vd: 192.168.1.0/24)
# Regex validate input de chong Command Injection
_RE_SAFE_IP = _re_module.compile(r'^(\d{1,3}\.){3}\d{1,3}$')
_RE_SAFE_CIDR = _re_module.compile(r'^(\d{1,3}\.){3}\d{1,3}/\d{1,2}$')

def _validate_ip(ip):
    """Validate IP address format. Return True if valid."""
    if not (ip and _RE_SAFE_IP.match(str(ip))):
        return False
    try:
        import ipaddress
        addr = ipaddress.ip_address(str(ip))
        if addr.is_unspecified or addr.is_multicast or str(addr) == "255.255.255.255":
            return False
        return True
    except Exception:
        return False

def _validate_cidr(cidr):
    """Validate CIDR subnet format. Return True if valid."""
    if not (cidr and _RE_SAFE_CIDR.match(str(cidr))):
        return False
    try:
        import ipaddress
        net = ipaddress.ip_network(str(cidr), strict=False)
        if net.prefixlen < 8 or net.is_unspecified or net.is_multicast:
            return False
        return True
    except Exception:
        return False

_lan_whitelist = set()
_lan_subnets = []

def _load_lan_whitelist():
    """Đọc file lan_whitelist.conf va cap nhat danh sách IP/subnet."""
    global _lan_whitelist, _lan_subnets
    _lan_whitelist = set()
    _lan_subnets = []
    try:
        if os.path.exists(LAN_WHITELIST_PATH):
            with open(LAN_WHITELIST_PATH) as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith('#'):
                        continue
                    if '/' in line:
                        # Subnet CIDR: 192.168.1.0/24
                        # FIX-AUDIT-BUG2b: validate khi load tu disk. Ban cu nhan
                        # nguyen cai line -> entry rach do bug cu (truoc BUG2) hoac
                        # file bi sua tay sai dinh dang se persist va duoc
                        # _apply_iptables_for_whitelist apply lai (iptables reject
                        # nhung van nam trong _lan_whitelist -> match sai). Reject
                        # entry khong hop le, ghi warning.
                        if _validate_cidr(line):
                            _lan_subnets.append(line)
                        else:
                            log.warning("[Firewall] Bo qua subnet whitelist sai dinh dang: %s", line)
                    else:
                        if _validate_ip(line):
                            _lan_whitelist.add(line)
                        else:
                            log.warning("[Firewall] Bo qua IP whitelist sai dinh dang: %s", line)
    except Exception as e:
        log.error("Lỗi đọc danh sách LAN whitelist: %s", e)

def _ip_in_whitelist(ip):
    """Kiem tra IP co trong whitelist (exact match hoac subnet match)."""
    if _ip_in_trusted_proxy(ip):
        return False
    if ip in _lan_whitelist:
        return True
    # Check subnet CIDR
    try:
        import ipaddress
        ip_obj = ipaddress.ip_address(ip if not isinstance(ip, bytes) else ip.decode())
        for subnet in _lan_subnets:
            if ip_obj in ipaddress.ip_network(subnet if not isinstance(subnet, bytes) else subnet.decode(), strict=False):
                return True
    except Exception:
        pass
    return False

def _save_lan_whitelist():
    """Ghi danh sách IP/subnet ra file."""
    try:
        os.makedirs(os.path.dirname(LAN_WHITELIST_PATH), exist_ok=True)
        with open(LAN_WHITELIST_PATH, 'w') as f:
            f.write('# Danh sách IP/subnet LAN được truy cap NAS API (không cần Tailscale)\n')
            f.write('# Moi dong 1 IP hoac CIDR subnet\n')
            f.write('# Vi du: 192.168.1.100 hoac 192.168.1.0/24\n\n')
            for subnet in sorted(_lan_subnets):
                f.write(subnet + '\n')
            for ip in sorted(_lan_whitelist):
                f.write(ip + '\n')
    except Exception as e:
        log.error("Lỗi ghi danh sách LAN whitelist: %s", e)

_load_lan_whitelist()

def _apply_iptables_for_whitelist():
    """?p dùng iptables ACCEPT cho tất c? IP/subnet trong whitelist.
    Goi khi startup va khi them/xoá IP de dam bao firewall dong bo voi file config."""
    for ip in list(_lan_whitelist):
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
        except Exception:
            pass
        try:
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', ip, '-j', 'ACCEPT'])
        except Exception as e:
            log.warning("[Firewall] Không áp dụng được iptables ACCEPT cho IP %s: %s", ip, e)
    for subnet in list(_lan_subnets):
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', subnet, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
        except Exception:
            pass
        try:
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', subnet, '-j', 'ACCEPT'])
        except Exception as e:
            log.warning("[Firewall] Không áp dụng được iptables ACCEPT cho subnet %s: %s", subnet, e)
    # Cố định luôn luôn mở cho dải Tailscale VPN (100.64.0.0/10) và Localhost
    for builtin_net in ['100.64.0.0/10', '127.0.0.1']:
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', builtin_net, '-p', 'tcp', '--dport', '5050', '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
        except Exception:
            pass
        try:
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', builtin_net, '-p', 'tcp', '--dport', '5050', '-j', 'ACCEPT'])
            # Mở thêm cho WebSockets (5051)
            subprocess.run(['iptables', '-D', 'INPUT', '-s', builtin_net, '-p', 'tcp', '--dport', '5051', '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', builtin_net, '-p', 'tcp', '--dport', '5051', '-j', 'ACCEPT'])
        except Exception as e:
            log.warning("[Firewall] Không áp dụng được iptables ACCEPT cho mạng mặc định %s: %s", builtin_net, e)
            
    if _lan_whitelist or _lan_subnets:
        log.info("[Firewall] Đã áp dụng iptables ACCEPT cho %d IP, %d subnet trong whitelist", len(_lan_whitelist), len(_lan_subnets))

if __name__ == "__main__":
    _apply_iptables_for_whitelist()

clients = set()
clients_lock = threading.Lock()

def broadcast(data):
    payload = json.dumps(data)
    with clients_lock:
        targets = list(clients)
    dead = []
    for c in targets:
        try: c.write_message(payload)
        except Exception: dead.append(c)
    if dead:
        with clients_lock:
            for c in dead:
                clients.discard(c)

_ws_unauth_log_cache = {}
_ws_unauth_log_lock = threading.Lock()

class AlertWebSocket(tornado.websocket.WebSocketHandler):
    def check_origin(self, origin):
        if not origin:
            return True
        try:
            parsed = urllib.parse.urlparse(origin)
            host = (parsed.hostname or "").lower()
            if not host:
                return True
            if host in ("localhost", "127.0.0.1") or host.startswith(("192.168.", "10.", "100.", "172.")):
                return True
            if _ip_in_whitelist(host):
                return True
            req_host = (self.request.host or "").split(":")[0].lower()
            if req_host and host == req_host:
                return True
            return False
        except Exception:
            return False

    def open(self):
        ip = (self.request.remote_ip or "").strip()
        token = (
            self.get_argument("token", "") or
            self.request.headers.get("X-NAS-Token", "") or
            self.request.headers.get("Sec-WebSocket-Protocol", "")
        )

        auth_header = self.request.headers.get("Authorization", "")
        if auth_header and auth_header.startswith("Basic "):
            try:
                raw_b64 = auth_header.split(" ", 1)[1]
                decoded = base64.b64decode(raw_b64).decode("utf-8")
                if ":" in decoded:
                    u, p = decoded.split(":", 1)
                    if check_auth(u, p):
                        token = p
            except Exception:
                pass

        is_authorized = (
            _ip_in_whitelist(ip) or
            (ip and ip in _refresh_authorized_ips_cache()) or
            (token and check_auth(WEBDAV_USER, token))
        )
        if not is_authorized:
            now_ts = time.time()
            with _ws_unauth_log_lock:
                last_logged = _ws_unauth_log_cache.get(ip, 0)
                if now_ts - last_logged > 60:
                    _ws_unauth_log_cache[ip] = now_ts
                    log.warning("[AlertWebSocket] Tu choi ket noi WebSocket tu IP unauthorized: %s", ip)
            self.close(code=4001, reason="Unauthorized")
            return

        with clients_lock:
            clients.add(self)

    def on_close(self):
        with clients_lock:
            clients.discard(self)

def get_ip_geo(ip):
    if ip.startswith(("192.168.", "10.", "172.", "127.")): return "LOCAL", "LAN"
    if os.environ.get("NAS_ENABLE_EXTERNAL_IP_GEO", "").lower() not in ("1", "true", "yes"):
        return "UN", "Unknown"
    try:
        import urllib.request
        resp = urllib.request.urlopen("http://ip-api.com/json/{}".format(ip), timeout=2)
        res = json.loads(resp.read().decode("utf-8"))
        return res.get("countryCode", "UN"), res.get("country", "Unknown")
    except Exception: return "UN", "Unknown"

def ban_ip_permanently(ip):
    # Co che fail2ban da bi V? HI?U HO? theo yeu cau ng??i dùng.
    # Không cần g?i iptables DROP de trảnh chan nham IP Tailscale/LAN cua chảnh ch?.
    # Chi ghi log de admin biet co request ban (visibility) nhung khong thuc thi.
    try:
        now = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        try:
            cur = conn.cursor()
            safe_msg = sanitize_log_input("[{}] [DISABLED] Đã bỏ qua yêu cầu chặn IP {} vì fail2ban đang tắt.".format(now, ip))
            cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                       ("WARNING", "Firewall", safe_msg))
            conn.commit()
        finally:
            conn.close()
    except Exception as e:
        log.error("[Firewall] Lỗi ghi log yêu cầu chặn IP %s: %s", ip, e)

def handle_auth_failure(ip):
    # Co che fail2ban da bi V? HI?U HO? theo yeu cau ng??i dùng.
    # Chi ghi nhan so lan thất bại vao auth_attempts de admin theo doi,
    # KHONG con tu dong th?m banned_ips/iptables DROP nua.
    conn = None
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        cur = conn.cursor()
        cur.execute('INSERT OR IGNORE INTO auth_attempts VALUES (?, 0)', (ip,))
        cur.execute('UPDATE auth_attempts SET count = count + 1 WHERE ip=?', (ip,))
        conn.commit()
    except Exception as e:
        log.warning("[Auth] Khong ghi duoc auth_attempts cho IP %s: %s", ip, e)
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:
                pass

recent_auth_ips = {}
_ARP_LOOKUP_CACHE = {}
_ARP_LOOKUP_TTL = 60

def monitor_scanners():
    if not os.path.exists(WEBDAV_LOG): return
    with open(WEBDAV_LOG, "r") as f:
        f.seek(0, os.SEEK_END)
        while True:
            line = f.readline()
            if not line:
                time.sleep(1)
                continue
            
            # 1. Quét dò mật khẩu (Anti-BruteForce)
            if " 401 " in line:
                parts = line.split()
                if len(parts) > 0:
                    ip = parts[0]
                    if main_loop: main_loop.add_callback(handle_auth_failure, ip)
            
            # 2. Ghi nhận truy cập hợp lệ (Bất kể thiết bị nào)
            elif " 200 " in line or " 207 " in line:
                parts = line.split()
                if len(parts) > 0:
                    ip = parts[0]
                    # Chỉ log 1 lần mỗi 30 phút cho mỗi IP để tránh spam Database
                    now = time.time()
                    last_seen = recent_auth_ips.get(ip, 0)
                    if now - last_seen > 1800:
                        recent_auth_ips[ip] = now
                        # P1-4: Evict entries older than 1 hour to prevent unbounded growth
                        stale_ips = [k for k, v in recent_auth_ips.items() if now - v > 3600]
                        for _k in stale_ips:
                            del recent_auth_ips[_k]
                        # A. Lấy thông tin MAC Address bằng lệnh arp
                        mac_address = "Không rõ"
                        try:
                            # P1-5: Evict stale ARP entries before lookup
                            cached = _ARP_LOOKUP_CACHE.get(ip)
                            if cached and time.time() - float(cached.get("ts", 0) or 0) < _ARP_LOOKUP_TTL:
                                mac_address = cached.get("mac", "Không rõ")
                            else:
                                arp_out = subprocess.check_output(["arp", "-n", ip], stderr=subprocess.DEVNULL, timeout=1.5).decode('utf-8')
                                match = _re_module.search(r'([0-9a-fA-F]{2}[:-]){5}([0-9a-fA-F]{2})', arp_out)
                                if match:
                                    mac_address = match.group(0).upper()
                                _ARP_LOOKUP_CACHE[ip] = {"mac": mac_address, "ts": time.time()}
                        except Exception:
                            _ARP_LOOKUP_CACHE[ip] = {"mac": mac_address, "ts": time.time()}
                            
                        # B. Lọc Tên Thiết Bị từ User Agent String (Dalvik, Windows, WebDAVFS...)
                        device_info = "Thiết bị ngoại tuyến"
                        try:
                            parts_quote = line.split('"')
                            if len(parts_quote) >= 6:
                                ua = parts_quote[5]
                                if "Android" in ua and "Build/" in ua:
                                    match_model = _re_module.search(r';\s*([^;]+)\s+Build/', ua)
                                    if match_model:
                                        device_info = "Android - " + match_model.group(1).strip()
                                    else:
                                        device_info = "Android Client"
                                elif "Macintosh" in ua or "Darwin" in ua:
                                    device_info = "Apple Mac/iOS"
                                elif "Windows" in ua or "Microsoft-WebDAV-MiniRedir" in ua:
                                    device_info = "Máy tính Windows"
                                elif "okhttp" in ua.lower():
                                    device_info = "App NAS WebDAV"
                                else:
                                    device_info = ua.split(" ")[0][:20]
                        except Exception:
                            pass
                            
                        # Tổng hợp chuỗi hiển thị
                        log_msg_json = json.dumps({"event": "WEBDAV_SUCCESS", "ip": ip, "mac": mac_address, "device": device_info}, ensure_ascii=False)
                        log.info("ACCESS_LOG_WEBDAV: " + log_msg_json)
                        
                        def _commit_access_log(m_msg):
                            # (1) Lưu vào SQL Server cục bộ trên NAS
                            try:
                                conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
                                try:
                                    cur = conn.cursor()
                                    cur.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("INFO", "AccessLog", m_msg))
                                    conn.commit()
                                finally:
                                    conn.close()
                            except Exception:
                                pass
                            # (2) Phát sự kiện WebSocket cho Android App
                            try:
                                broadcast({"type": "ACCESS_LOG", "message": m_msg})
                            except Exception:
                                pass
                        if main_loop:
                            main_loop.add_callback(_commit_access_log, log_msg_json)
                        else:
                            _commit_access_log(log_msg_json)
                                

def monitor_journalctl():
    """Lang nghe h? thỏng theo thoi gian thuc tu journalctl (sshd, kernel, smartd)"""
    cmd = ["journalctl", "-f", "-q", "-n", "0"]
    proc = None
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for line_b in iter(proc.stdout.readline, b''):
            line = line_b.decode('utf-8', errors='ignore')
            
            # 1. SSH Failed Password
            match_fail = _re_module.search(r'sshd\[\d+\]: Failed password for (?:invalid user )?(\S+) from (\S+)', line)
            if match_fail:
                user = match_fail.group(1)
                ip = match_fail.group(2)
                if main_loop: main_loop.add_callback(handle_auth_failure, ip)
                
                log_json = json.dumps({"event": "SSH_FAIL", "ip": ip, "user": user}, ensure_ascii=False)
                def _commit_ssh_fail(m_msg):
                    try:
                        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
                        try:
                            conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("ERROR", "Security", m_msg))
                            conn.commit()
                        finally:
                            conn.close()
                        broadcast({"type": "ACCESS_LOG", "message": m_msg})
                    except Exception as e: log.warning("[Monitor] SSH_FAIL log commit failed: %s", e)
                if main_loop: main_loop.add_callback(_commit_ssh_fail, log_json)
                continue
                
            # 2. SSH Accepted
            match_acc = _re_module.search(r'sshd\[\d+\]: Accepted (password|publickey) for (\S+) from (\S+)', line)
            if match_acc:
                method = match_acc.group(1)
                user = match_acc.group(2)
                ip = match_acc.group(3)
                log_json = json.dumps({"event": "SSH_SUCCESS", "ip": ip, "user": user, "method": method}, ensure_ascii=False)
                def _commit_ssh_acc(m_msg):
                    try:
                        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
                        try:
                            conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("INFO", "AccessLog", m_msg))
                            conn.commit()
                        finally:
                            conn.close()
                        broadcast({"type": "ACCESS_LOG", "message": m_msg})
                    except Exception as e: log.warning("[Monitor] SSH_SUCCESS log commit failed: %s", e)
                if main_loop: main_loop.add_callback(_commit_ssh_acc, log_json)
                continue
            
            # 3. Kernel CPU Nhiệt độ
            if 'kernel:' in line and 'temperature above threshold' in line:
                log_json = json.dumps({"event": "CPU_TEMP_WARN"}, ensure_ascii=False)
                def _commit_cpu_warn(m_msg):
                    try:
                        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
                        try:
                            conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("WARNING", "Hardware", m_msg))
                            conn.commit()
                        finally:
                            conn.close()
                        broadcast({"type": "ACCESS_LOG", "message": m_msg})
                    except Exception as e: log.warning("[Monitor] CPU_TEMP_WARN log commit failed: %s", e)
                if main_loop: main_loop.add_callback(_commit_cpu_warn, log_json)
                continue
                
            # 4. Smartd Warning
            match_smart = _re_module.search(r'smartd\[\d+\]: Device: (\S+), (.+)', line)
            if match_smart:
                dev = match_smart.group(1)
                msg = match_smart.group(2)
                log_json = json.dumps({"event": "SMART_WARN", "device": dev, "error": msg}, ensure_ascii=False)
                def _commit_smart_warn(m_msg):
                    try:
                        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
                        try:
                            conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("WARNING", "Hardware", m_msg))
                            conn.commit()
                        finally:
                            conn.close()
                        broadcast({"type": "ACCESS_LOG", "message": m_msg})
                    except Exception as e: log.warning("[Monitor] SMART_WARN log commit failed: %s", e)
                if main_loop: main_loop.add_callback(_commit_smart_warn, log_json)
                continue

    except Exception as e:
        log.error("Trình giám sát journalctl đã dừng: %s", e)
    finally:
        try:
            if proc is not None and proc.stdout is not None:
                proc.stdout.close()
        except Exception:
            pass
        try:
            if proc is not None and proc.poll() is None:
                proc.terminate()
        except Exception:
            pass


# ============ CAU HINH ============
# Đọc thong tin xac thuc tu file bao mat /etc/nas/auth.conf (chmod 600)
# Format file auth.conf:
#   WEBDAV_USER=daica
#   WEBDAV_PASS=your_password_here
AUTH_CONFIG_PATH = "/etc/nas/auth.conf"

# Chi l?y S.M.A.R.T cua o dữ liệu NAS. Khong quet /dev/sdb vi day co the la
# o USB import, lam nhieu dashboard bang trạng thái cua o ngoai.
TARGET_HDD_DEVICE = os.environ.get("NAS_TARGET_HDD_DEVICE", "/dev/sda")
SMART_DISKS = [TARGET_HDD_DEVICE]
TARGET_HDD_MOUNTPOINTS = ("/srv/dev-disk-by-label-data", "/sharedfolders/Data")
TARGET_HDD_MODEL_HINTS = ("TOSHIBA", "MG04", "N300")
TARGET_HDD_SERIAL_HINTS = ("X6N7KALWFVLC",)

def _read_first_existing_text(paths):
    for path in paths:
        try:
            if os.path.exists(path):
                with open(path, "r", encoding="utf-8", errors="ignore") as f:
                    return f.read().strip()
        except Exception:
            continue
    return ""

def _parent_disk_from_device(device):
    device = str(device or "").strip()
    if not device.startswith("/dev/"):
        return ""
    base = os.path.basename(device)
    if base.startswith(("nvme", "mmcblk")):
        parent = _re_module.sub(r"p\d+$", "", base)
    else:
        parent = _re_module.sub(r"\d+$", "", base)
    return "/dev/" + parent if parent else ""

def _target_hdd_device_path():
    dev = TARGET_HDD_DEVICE
    if not _validate_disk_path(dev) or not os.path.exists(dev):
        return ""
    name = os.path.basename(dev)
    model = _read_first_existing_text(("/sys/block/%s/device/model" % name,))
    serial = _read_first_existing_text((
        "/sys/block/%s/device/serial" % name,
        "/sys/block/%s/device/wwid" % name,
    ))
    blob = ("%s %s" % (model, serial)).upper()
    if not any(h in blob for h in TARGET_HDD_MODEL_HINTS) and not any(h in blob for h in TARGET_HDD_SERIAL_HINTS):
        log.info("[DiskTarget] %s khong khop model/serial N300 (model=%s serial=%s) nhung van tiep tuc su dung.", dev, model, serial)
    return dev

def _target_hdd_devname():
    return os.path.basename(_target_hdd_device_path() or "")

def _target_hdd_mountpoint():
    for mountpoint in TARGET_HDD_MOUNTPOINTS:
        try:
            if os.path.ismount(mountpoint) or os.path.isdir(mountpoint):
                return mountpoint
        except Exception:
            continue
    return TARGET_HDD_MOUNTPOINTS[0]

def _is_target_hdd_omv_device(dev):
    if not isinstance(dev, dict):
        return False
    devname = str(dev.get("devicename", "") or "")
    devicefile = str(dev.get("devicefile", "") or "")
    if "mmc" in devname.lower() or "mmc" in devicefile.lower():
        return False
    target_path = _target_hdd_device_path()
    target_name = os.path.basename(target_path)
    if devicefile == target_path or devname == target_name:
        return True
    blob = " ".join(str(dev.get(k, "") or "") for k in ("vendor", "model", "serialnumber", "description")).upper()
    if any(h in blob for h in TARGET_HDD_MODEL_HINTS) or any(h in blob for h in TARGET_HDD_SERIAL_HINTS):
        return True
    if devname.startswith("sd") or devname.startswith("nvme") or "sd" in devicefile or "nvme" in devicefile:
        return True
    return False

def _select_target_omv_smart_device(devices):
    for dev in devices or []:
        if _is_target_hdd_omv_device(dev):
            return dev
    return None

# File tam de do toc do ổ cứng
# CC-005 P1/P2: SPEED_TEST path hard-code -> ENV NAS_SPEED_TEST_FILE
# (default giữ nguyên <WEBDAV_ROOT>/.naswebdav/tmp/nas_speed_test.bin).
SPEED_TEST_FILE = _cfg_str("speed_test_file", os.path.join(WEBDAV_FILE_ROOT, ".naswebdav", "tmp", "nas_speed_test.bin"))


def _load_credentials():
    """Đọc user/pass tu file cau hinh bao mat, fallback sang bien moi truong."""
    user = os.environ.get("WEBDAV_USER", "")
    passwd = os.environ.get("WEBDAV_PASS", "")
    try:
        if os.path.exists(AUTH_CONFIG_PATH):
            with open(AUTH_CONFIG_PATH) as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("#") or "=" not in line:
                        continue
                    key, val = line.split("=", 1)
                    key, val = key.strip(), val.strip()
                    if key == "WEBDAV_USER":
                        user = val
                    elif key == "WEBDAV_PASS":
                        passwd = val
    except Exception as e:
        log.error("Không đọc được %s: %s", AUTH_CONFIG_PATH, e)
    if not user or not passwd:
        log.warning("Chưa cấu hình WEBDAV_USER/WEBDAV_PASS. Hãy tạo file auth.conf với WEBDAV_USER=... WEBDAV_PASS=...")
    return user, passwd


WEBDAV_USER, WEBDAV_PASS = _load_credentials()

_AUTHORIZED_IPS_CACHE = {"ts": 0.0, "ips": set()}
_AUTHORIZED_IPS_CACHE_TTL = 30.0
_TRUSTED_PROXY_CACHE = {"ts": 0.0, "nets": []}
_TRUSTED_PROXY_CACHE_TTL = 60.0
_TRUSTED_PROXY_KEYS = ("TRUSTED_PROXY_CIDRS", "TRUSTED_PROXY_IPS", "TRUSTED_PROXIES")

def _remember_authorized_ip(ip):
    if not ip:
        return
    try:
        _AUTHORIZED_IPS_CACHE["ips"].add(ip)
        _AUTHORIZED_IPS_CACHE["ts"] = time.time()
    except Exception:
        pass

def _refresh_authorized_ips_cache(force=False):
    now = time.time()
    try:
        cache_ts = float(_AUTHORIZED_IPS_CACHE.get("ts") or 0)
        if not force and now - cache_ts < _AUTHORIZED_IPS_CACHE_TTL:
            return _AUTHORIZED_IPS_CACHE["ips"]
    except Exception:
        pass
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
        try:
            cur = conn.cursor()
            cutoff = (datetime.datetime.now() - datetime.timedelta(hours=24)).strftime("%Y-%m-%d %H:%M:%S")
            cur.execute('DELETE FROM authorized_ips WHERE added_at < ?', (cutoff,))
            cur.execute('SELECT ip FROM authorized_ips WHERE added_at >= ?', (cutoff,))
            ips = set()
            for row in cur.fetchall():
                if row and row[0]:
                    ips.add(str(row[0]))
            _AUTHORIZED_IPS_CACHE["ips"] = ips
            _AUTHORIZED_IPS_CACHE["ts"] = now
            conn.commit()
        finally:
            conn.close()
    except Exception as e:
        try:
            log.warning("[Auth] Khong load duoc authorized_ips cache: %s", e)
        except Exception:
            pass
    return _AUTHORIZED_IPS_CACHE.get("ips", set())

def _load_trusted_proxy_networks(force=False):
    now = time.time()
    try:
        cache_ts = float(_TRUSTED_PROXY_CACHE.get("ts") or 0)
        if not force and _TRUSTED_PROXY_CACHE.get("nets") and now - cache_ts < _TRUSTED_PROXY_CACHE_TTL:
            return _TRUSTED_PROXY_CACHE["nets"]
    except Exception:
        pass

    raw_values = []
    for key in _TRUSTED_PROXY_KEYS:
        val = os.environ.get(key, "")
        if val:
            raw_values.append(val)

    try:
        if os.path.exists(AUTH_CONFIG_PATH):
            with open(AUTH_CONFIG_PATH, "r", encoding="utf-8", errors="ignore") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, val = line.split("=", 1)
                    if key.strip().upper() in _TRUSTED_PROXY_KEYS and val.strip():
                        raw_values.append(val.strip())
    except Exception as e:
        log.warning("[Auth] Could not read trusted proxy config: %s", e)

    import ipaddress
    networks = []
    seen = set()
    for raw in raw_values:
        for item in _re_module.split(r"[,\s;]+", raw):
            item = item.strip()
            if not item:
                continue
            try:
                net = ipaddress.ip_network(item, strict=False)
            except Exception:
                log.warning("[Auth] Skip invalid trusted proxy entry: %s", item)
                continue
            key = str(net)
            if key not in seen:
                seen.add(key)
                networks.append(net)

    _TRUSTED_PROXY_CACHE["nets"] = networks
    _TRUSTED_PROXY_CACHE["ts"] = now
    return networks

def _ip_in_trusted_proxy(ip):
    if not ip:
        return False
    try:
        import ipaddress
        ip_obj = ipaddress.ip_address(ip if not isinstance(ip, bytes) else ip.decode())
        for net in _load_trusted_proxy_networks():
            if ip_obj in net:
                return True
    except Exception:
        pass
    return False

def _request_client_ip():
    remote = request.remote_addr
    if isinstance(remote, bytes):
        remote = remote.decode("utf-8", errors="ignore")
    remote = (remote or "").strip()
    if not remote:
        return ""

    if not _ip_in_trusted_proxy(remote):
        return remote

    candidates = []
    forwarded = request.headers.get("X-Forwarded-For", "")
    if forwarded:
        candidates.extend([part.strip() for part in forwarded.split(",")])

    real_ip = request.headers.get("X-Real-IP", "").strip()
    if real_ip:
        candidates.append(real_ip)

    import ipaddress
    for candidate in candidates:
        if not candidate or candidate == remote:
            continue
        try:
            ip_obj = ipaddress.ip_address(candidate if not isinstance(candidate, bytes) else candidate.decode())
        except Exception:
            continue
        candidate_ip = str(ip_obj)
        if _ip_in_trusted_proxy(candidate_ip):
            continue
        return candidate_ip

    # CC-004 P0-2: khong tim duoc candidate hop le thi tra ve remote (proxy IP)
    # thay vi "". Tra "" khien whitelist/cache sai: LAN khong-auth bi 401 oan,
    # dong thoi khong phan biet duoc client that sau proxy.
    return remote

# ============ XAC THUC ============
def check_auth(username, password):
    import hmac
    u_ok = hmac.compare_digest(str(username or ""), str(WEBDAV_USER or ""))
    p_ok = hmac.compare_digest(str(password or ""), str(WEBDAV_PASS or ""))
    return bool(u_ok and p_ok)

def _async_persist_trusted_ip(ip):
    def _worker():
        try:
            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
            try:
                cur = conn.cursor()
                cur.execute('INSERT OR REPLACE INTO authorized_ips VALUES (?, ?)', (ip, datetime.datetime.now()))
                conn.commit()
            finally:
                conn.close()
        except Exception:
            pass
    threading.Thread(target=_worker, daemon=True).start()

def requires_auth(f):
    """Robust voi loi disk/DB. Whitelist va cache IP tin cay truoc, DB chi dung de persist IP moi."""
    @wraps(f)
    def decorated(*args, **kwargs):
        ip = _request_client_ip()
        auth = request.authorization

        if auth is not None:
            if check_auth(auth.username, auth.password):
                if ip and ip not in _AUTHORIZED_IPS_CACHE.get("ips", set()):
                    _remember_authorized_ip(ip)
                    _async_persist_trusted_ip(ip)
                return f(*args, **kwargs)
            else:
                return jsonify({"detail": "Sai mat khau"}), 401

        # LAN whitelist bypass (chi danh cho request chua co header Auth)
        if _ip_in_whitelist(ip):
            return f(*args, **kwargs)

        # RAM cache: khong mo SQLite moi request
        if ip and ip in _refresh_authorized_ips_cache():
            return f(*args, **kwargs)

        return jsonify({"detail": "Chua xac thuc"}), 401

    return decorated

def requires_admin(f):
    """SPEC_LIVESTREAM_LIMITS §3.2 (Q3 admin-only): PATCH limits bat buoc Basic
    auth dung tai khoan WEBDAV_USER (daica). Chan LAN-whitelist bypass va
    authorized_ips cache — IP tung login khong tu nang quyen. Sai -> 401."""
    @wraps(f)
    def decorated(*args, **kwargs):
        try:
            auth = request.authorization
        except Exception:
            auth = None
        if auth is None:
            return jsonify({"detail": "Chua xac thuc"}), 401
        try:
            ok = check_auth(auth.username, auth.password)
        except Exception:
            ok = False
        if not ok:
            return jsonify({"detail": "Sai mat khau"}), 401
        return f(*args, **kwargs)
    return decorated

# ============ SPEC_LIVESTREAM_LIMITS §3.2 + §3.4: admin limits (GET/PATCH) ============
# Dinh nghia: (key, default, low, high, kieu). social_max_filesize la str dac biet.
_ADMIN_LIMIT_DEFS = (
    ("social_max_concurrent", 2, 1, 16, "int"),
    ("ytdlp_max_concurrent", 2, 1, 4, "int"),
    ("livestream_max_concurrent", 2, 1, 8, "int"),
    ("social_max_filesize", "2G", None, None, "str"),
    ("ytdlp_timeout_s", 300, 60, 1800, "int"),
    ("livestream_max_hours", 12, 1, 72, "int"),
    ("live_err_cooldown_s", 300, 10, 86400, "int"),
    ("live_stall_cooldown_s", 7200, 60, 604800, "int"),
    ("user_idle_s", 90, 10, 600, "int"),
    ("ram_block_pct", 78, 50, 95, "int"),
    ("load_block", 2.5, 1.0, 8.0, "float"),
    ("cpu_block_pct", 75, 50, 100, "int"),
    ("heavy_gate_cache_s", 5.0, 1.0, 60.0, "float"),
    ("social_cleanup_interval_s", 300, 30, 86400, "int"),
    ("social_job_ttl_s", 3600, 300, 604800, "int"),
    ("tiktok_watch_s", 60, 15, 300, "int"),
    ("tiktok_html_cap", 786432, 65536, 8388608, "int"),
    ("tiktok_html_timeout", 25, 5, 120, "int"),
)


def _admin_current_limits():
    """Doc toan bo key dot 1 qua loader chung (env > runtime > conf > default)."""
    vals = {}
    for key, default, low, high, kind in _ADMIN_LIMIT_DEFS:
        try:
            if kind == "int":
                vals[key] = _cfg_int(key, default, low, high)
            elif kind == "float":
                try:
                    v = float(str(_cfg_limit(key, default)).strip())
                except Exception:
                    v = default
                if v < low:
                    v = low
                if v > high:
                    v = high
                vals[key] = v
            elif key == "social_max_filesize":
                vals[key] = _get_social_max_filesize()
            else:
                vals[key] = _cfg_limit(key, default)
        except Exception:
            vals[key] = default
    return vals


def _admin_save_limits_file(cfg):
    """Ghi conf atomically (tmp + rename), chmod 600. Best-effort, tra bool."""
    try:
        d = os.path.dirname(_NAS_LIMITS_CONF_PATH)
        if d and not os.path.exists(d):
            os.makedirs(d, exist_ok=True)
        tmp = _NAS_LIMITS_CONF_PATH + ".tmp"
        with open(tmp, "w") as f:
            json.dump(cfg, f, indent=2, sort_keys=True)
        os.rename(tmp, _NAS_LIMITS_CONF_PATH)
        try:
            os.chmod(_NAS_LIMITS_CONF_PATH, 0o600)
        except Exception:
            pass
        return True
    except Exception as e:
        try:
            log.warning("[AdminLimits] Ghi %s that bai: %s", _NAS_LIMITS_CONF_PATH, e)
        except Exception:
            pass
        return False


@app.route("/api/admin/limits", methods=["GET"])
@requires_auth
def api_admin_limits_get():
    """Xem gioi han dong + RAM/khuyen nghi + heavy-gate (moi client auth LAN)."""
    vals = _admin_current_limits()
    try:
        ram_mb, rec_live, rec_social = _recommended_caps_by_ram()
    except Exception:
        ram_mb, rec_live, rec_social = 1024, 2, 2
    try:
        _gate_fn = globals().get("_background_heavy_work_allowed")
        gate_allowed = bool(_gate_fn()) if callable(_gate_fn) else True
    except Exception:
        gate_allowed = True
    vals["ram_mb"] = ram_mb
    vals["recommended_live_max"] = rec_live
    vals["recommended_social_max"] = rec_social
    vals["heavy_gate_allowed"] = gate_allowed
    return jsonify(vals)


@app.route("/api/admin/limits", methods=["PATCH"])
@requires_admin
def api_admin_limits_patch():
    """Doi gioi han luc runtime (khong restart). Chi admin daica (Basic auth).
    Validate range/clamp, ghi conf atomically. Vuot khuyen nghi RAM -> warning."""
    try:
        body = request.get_json(force=True) or {}
    except Exception:
        return jsonify({"error": "Body JSON khong hop le."}), 400
    if not isinstance(body, dict) or not body:
        return jsonify({"error": "Body JSON rong."}), 400
    try:
        ram_mb, rec_live, rec_social = _recommended_caps_by_ram()
    except Exception:
        ram_mb, rec_live, rec_social = 1024, 2, 2
    updated = {}
    for key, default, low, high, kind in _ADMIN_LIMIT_DEFS:
        if key not in body:
            continue
        raw = body.get(key)
        try:
            if kind == "int":
                v = int(str(raw).strip())
                if v < low:
                    v = low
                if v > high:
                    v = high
            elif kind == "float":
                v = float(str(raw).strip())
                if v < low:
                    v = low
                if v > high:
                    v = high
            else:
                v = str(raw).strip() or default
                if key == "social_max_filesize":
                    if not _re_module.match(r"^\d+(\.\d+)?[KMGTPkmgtp]?[Bb]?$", v):
                        return jsonify({"error": "Gia tri khong hop le cho %s." % key}), 400
        except Exception:
            return jsonify({"error": "Gia tri khong hop le cho %s." % key}), 400
        with _NAS_LIMITS_LOCK:
            _NAS_LIMITS_RUNTIME[key] = v
            _NAS_LIMITS_CFG[key] = v
        updated[key] = v
    if not updated:
        return jsonify({"error": "Khong co key hop le (xem GET /api/admin/limits)."}), 400
    with _NAS_LIMITS_LOCK:
        _cfg_snapshot = dict(_NAS_LIMITS_CFG)
    persisted = _admin_save_limits_file(_cfg_snapshot)
    over = False
    try:
        if "livestream_max_concurrent" in updated and int(updated["livestream_max_concurrent"]) > int(rec_live):
            over = True
        if "social_max_concurrent" in updated and int(updated["social_max_concurrent"]) > int(rec_social):
            over = True
    except Exception:
        pass
    if over:
        try:
            log.warning("[AdminLimits] Cap vuot khuyen nghi RAM %sMB (live %s/%s, social %s/%s).",
                        ram_mb, updated.get("livestream_max_concurrent", "-"), rec_live,
                        updated.get("social_max_concurrent", "-"), rec_social)
        except Exception:
            pass
    resp = {"updated": updated, "limits": _admin_current_limits(),
            "requires_restart": False, "persisted": bool(persisted),
            "ram_mb": ram_mb, "recommended_live_max": rec_live,
            "recommended_social_max": rec_social}
    if over:
        resp["warning"] = "over_recommended"
    return jsonify(resp)


# ============ SPEC_LIVESTREAM_LIMITS §4.1 (Q2 server-side queue, HDD persist) ============
_LIVESTREAM_QUEUE_MAX = 20
_LIVESTREAM_QUEUE_TTL_S = 86400
_livestream_queue = []
_livestream_queue_lock = threading.Lock()
_livestream_queue_loaded = {"done": False}


def _livestream_queue_file():
    """File queue tren HDD (.naswebdav), song qua restart. Fallback /tmp."""
    try:
        _hdd_fn = globals().get("_get_hdd_config_dir")
        if callable(_hdd_fn):
            d = _hdd_fn()
            if d:
                return os.path.join(d, "livestream_queue.json")
    except Exception:
        pass
    try:
        base = globals().get("WEBDAV_FILE_ROOT") or ""
        if base:
            return os.path.join(base, ".naswebdav", "livestream_queue.json")
    except Exception:
        pass
    return "/tmp/nas_api_livestream_queue.json"


def _livestream_queue_ensure_loaded():
    if _livestream_queue_loaded.get("done"):
        return
    with _livestream_queue_lock:
        if _livestream_queue_loaded.get("done"):
            return
        try:
            p = _livestream_queue_file()
            if p and os.path.exists(p):
                with open(p, "r") as f:
                    data = json.load(f)
                if isinstance(data, list):
                    for e in data:
                        if isinstance(e, dict) and e.get("queue_id") and e.get("url"):
                            e.setdefault("status", "queued")
                            _livestream_queue.append(e)
        except Exception as e:
            try:
                log.warning("[LiveQueue] Load that bai: %s", e)
            except Exception:
                pass
        _livestream_queue_loaded["done"] = True


def _livestream_queue_save_locked():
    try:
        p = _livestream_queue_file()
        d = os.path.dirname(p)
        if d and not os.path.exists(d):
            os.makedirs(d, exist_ok=True)
        tmp = p + ".tmp"
        with open(tmp, "w") as f:
            json.dump(list(_livestream_queue), f)
        os.rename(tmp, p)
        try:
            os.chmod(p, 0o600)
        except Exception:
            pass
    except Exception as e:
        try:
            log.warning("[LiveQueue] Save that bai: %s", e)
        except Exception:
            pass


def _livestream_queue_prune_locked(now=None):
    now = now or time.time()
    kept = []
    removed = 0
    for e in list(_livestream_queue):
        try:
            ts = float(e.get("ts", 0) or 0)
        except Exception:
            ts = 0
        if now - ts > _LIVESTREAM_QUEUE_TTL_S:
            removed += 1
            continue
        kept.append(e)
    if removed:
        del _livestream_queue[:]
        _livestream_queue.extend(kept)
        _livestream_queue_save_locked()
    return removed


def _livestream_queue_active_count():
    n = 0
    try:
        for j in _livestream_jobs.values():
            if j.get("status") in ("recording", "starting", "stopping", "timeout", "finalizing"):
                n += 1
        for c in _livestream_starting_claims.values():
            try:
                if time.time() - float(c.get("ts", 0) or 0) < 180:
                    n += 1
            except Exception:
                pass
    except Exception:
        pass
    return n


def _livestream_queue_try_promote():
    """Thu nhac entry dau khi co slot trong. Di qua flow record noi bo de giu
    moi guard (cap/HW/dedup). 429 full-slot -> giu queue cho vong sau."""
    _livestream_queue_ensure_loaded()
    try:
        _cap_fn = globals().get("_get_livestream_max_concurrent")
        cap = int(_cap_fn()) if callable(_cap_fn) else 2
    except Exception:
        cap = 2
    if cap < 1:
        cap = 1
    if cap > 8:
        cap = 8
    with _livestream_queue_lock:
        _livestream_queue_prune_locked()
        if not _livestream_queue:
            return ""
        with _livestream_lock:
            active = _livestream_queue_active_count()
        if active >= cap:
            return ""
        entry = dict(_livestream_queue[0])
    url = str(entry.get("url", "") or "")
    if not url:
        with _livestream_queue_lock:
            if _livestream_queue and _livestream_queue[0].get("queue_id") == entry.get("queue_id"):
                _livestream_queue.pop(0)
            _livestream_queue_save_locked()
        return ""
    try:
        payload = {"url": url, "quality": str(entry.get("quality", "") or "best")}
        if entry.get("watch_username"):
            payload["watch_username"] = str(entry.get("watch_username"))
        if entry.get("max_hours"):
            try:
                payload["max_hours"] = int(entry.get("max_hours"))
            except Exception:
                pass
        view_func = getattr(api_livestream_record, "__wrapped__", api_livestream_record)
        with app.test_request_context("/api/livestream/record", method="POST",
                                      data=json.dumps(payload),
                                      content_type="application/json"):
            result = view_func()
        code = 200
        obj = result
        if isinstance(result, tuple):
            obj = result[0]
            if len(result) > 1 and isinstance(result[1], int):
                code = result[1]
        data = {}
        if hasattr(obj, "get_json"):
            try:
                data = obj.get_json(silent=True) or {}
            except Exception:
                data = {}
        if 200 <= code < 300 and data.get("job_id"):
            with _livestream_queue_lock:
                for i, e in enumerate(list(_livestream_queue)):
                    if e.get("queue_id") == entry.get("queue_id"):
                        _livestream_queue.pop(i)
                        break
                _livestream_queue_save_locked()
            try:
                log.info("[LiveQueue] Da nhac %s -> job %s.", entry.get("queue_id"), data.get("job_id"))
            except Exception:
                pass
            return str(data.get("job_id"))
        err = str(data.get("reason", "") or data.get("code", "") or data.get("error", ""))
        if code == 429 or "max_concurrent" in err or "MAX_STREAMS" in err:
            return ""
        with _livestream_queue_lock:
            for idx_q, e in enumerate(list(_livestream_queue)):
                if e.get("queue_id") == entry.get("queue_id"):
                    e["last_error"] = err[:200]
                    attempts = int(e.get("attempts", 0) or 0) + 1
                    e["attempts"] = attempts
                    _livestream_queue.pop(idx_q)
                    if attempts < 3:
                        _livestream_queue.append(e)
                    break
            _livestream_queue_save_locked()
        return ""
    except Exception as e:
        try:
            log.warning("[LiveQueue] Promote loi: %s", e)
        except Exception:
            pass
        return ""


def _livestream_queue_promoter():
    """Daemon 60s/vong: tu nhac queue khi co slot (cung nhip watchdog)."""
    try:
        time.sleep(60)
        _livestream_queue_ensure_loaded()
    except Exception:
        pass
    while True:
        try:
            time.sleep(60)
            _livestream_queue_try_promote()
        except Exception:
            pass


@app.route("/api/livestream/queue", methods=["POST"])
@requires_auth
def api_livestream_queue_add():
    """Cho URL vao hang doi server-side (persist HDD, TTL 24h, cap 20)."""
    _livestream_queue_ensure_loaded()
    try:
        body = request.get_json(force=True) or {}
    except Exception:
        return jsonify({"error": "Body JSON khong hop le."}), 400
    url = str(body.get("url", "") or "").strip()
    quality = str(body.get("quality", "") or "best").strip() or "best"
    watch_username = str(body.get("watch_username", "") or "").strip()
    try:
        max_hours = int(body.get("max_hours", 0) or 0)
    except Exception:
        max_hours = 0
    if not url:
        return jsonify({"error": "Thieu URL livestream."}), 400
    try:
        if callable(globals().get("_social_validate_url")) and not _social_validate_url(url):
            return jsonify({"error": "URL livestream khong duoc ho tro hoac khong an toan."}), 400
    except Exception:
        return jsonify({"error": "URL livestream khong duoc ho tro hoac khong an toan."}), 400
    if max_hours and (max_hours < 1 or max_hours > 72):
        return jsonify({"error": "max_hours phai 1-72."}), 400
    with _livestream_queue_lock:
        _livestream_queue_prune_locked()
        for i, e in enumerate(list(_livestream_queue)):
            if str(e.get("url", "")).strip().lower() == url.lower():
                return jsonify({"queue_id": e.get("queue_id"), "position": i + 1,
                                "status": "queued", "duplicate": True,
                                "message": "URL da co trong hang doi."})
        if len(_livestream_queue) >= _LIVESTREAM_QUEUE_MAX:
            return jsonify({"error": "Hang doi da day (%d)." % _LIVESTREAM_QUEUE_MAX,
                            "reason": "queue_full", "code": "QUEUE_FULL",
                            "cap": _LIVESTREAM_QUEUE_MAX,
                            "active": len(_livestream_queue)}), 429
        qid = "q_%d_%s" % (int(time.time() * 1000), uuid.uuid4().hex[:6])
        entry = {"queue_id": qid, "ts": time.time(), "url": url, "quality": quality,
                 "status": "queued", "last_error": ""}
        if watch_username:
            entry["watch_username"] = watch_username
        if max_hours:
            entry["max_hours"] = max_hours
        _livestream_queue.append(entry)
        _livestream_queue_save_locked()
        pos = len(_livestream_queue)
    return jsonify({"queue_id": qid, "position": pos, "status": "queued"}), 202


@app.route("/api/livestream/queue", methods=["GET"])
@requires_auth
def api_livestream_queue_list():
    """Xem hang doi (so thu tu + nut huy o client)."""
    _livestream_queue_ensure_loaded()
    with _livestream_queue_lock:
        _livestream_queue_prune_locked()
        items = []
        for i, e in enumerate(list(_livestream_queue)):
            d = dict(e)
            d["position"] = i + 1
            items.append(d)
    return jsonify({"queue": items, "count": len(items), "cap": _LIVESTREAM_QUEUE_MAX})


@app.route("/api/livestream/queue", methods=["DELETE"])
@requires_auth
def api_livestream_queue_del():
    """Huy 1 entry (body {queue_id} hoac ?queue_id=)."""
    _livestream_queue_ensure_loaded()
    try:
        body = request.get_json(force=True) or {}
    except Exception:
        body = {}
    if not isinstance(body, dict):
        body = {}
    qid = str(body.get("queue_id", "") or "").strip()
    if not qid:
        try:
            qid = str(request.args.get("queue_id", "") or "").strip()
        except Exception:
            qid = ""
    if not qid:
        return jsonify({"error": "Thieu queue_id."}), 400
    with _livestream_queue_lock:
        for i, e in enumerate(list(_livestream_queue)):
            if e.get("queue_id") == qid:
                _livestream_queue.pop(i)
                _livestream_queue_save_locked()
                return jsonify({"queue_id": qid, "status": "cancelled"})
    return jsonify({"error": "Khong tim thay queue_id: %s" % qid}), 404


try:
    threading.Thread(target=_livestream_queue_promoter, daemon=True).start()
except Exception:
    pass

# FIX-SECURITY-SEARCH-AUTH: api_fast_search (dang ky route o tren, dong 364)
# duoc dinh nghia TRUOC requires_auth nen khong gan decorator truc tiep duoc
# (NameError -> crash-loop NAS ngay khi restart). Boc decorator TAI DAY, sau
# khi requires_auth da ton tai. Android app da gui Authorization header
# (FileBrowserViewModel:236) nen khong break. Attacker LAN khong auth -> 401.
# CC-004 P0-6: patch bang endpoint literal 'api_fast_search' (Flask endpoint
# mac dinh = ten ham luc @app.route), khong phu thuoc __name__ (vo neu ai
# them decorator khong @wraps hoac doi ten ham). Patch ca 2 key de chac chan.
_wrapped_search = requires_auth(api_fast_search)
api_fast_search = _wrapped_search
# Flask da dang ky ham GOC vao app.view_functions tai dong 364 (@app.route
# chay truoc khi requires_auth ton tai). Patch view da dang ky sang ban boc
# auth, neu khong endpoint van chay khong xac thuc du bien module da duoc boc.
try:
    app.view_functions["api_fast_search"] = _wrapped_search
    try:
        app.view_functions[_wrapped_search.__name__] = _wrapped_search
    except Exception:
        pass
except Exception as _e:
    log.warning("[Auth] Khong patch duoc view auth cho /api/search: %s", _e)

# ============ API VERSIONING (B1) ============
# Route cũ /api/* giữ nguyên (app cũ vẫn chạy). Route mới dual-serve thêm
# dưới /api/v1/* — cùng handler, response thêm "api_version": "v1".
# Quy ước: endpoint mới chỉ tạo dưới /api/v1, không tạo /api/* phẳng nữa.
# Khi đủ app mới, route /api/* phẳng gắn header Deprecated rồi gỡ sau.
API_VERSION = "v1"

def _versioned(path):
    """'/api/ping' -> '/api/v1/ping'. Chỉ dùng cho path bắt đầu /api/."""
    assert path.startswith("/api/"), path
    return "/api/v1" + path[4:]

def api_route(path, **kwargs):
    """Dual-serve: đăng ký cả /api/* (legacy) và /api/v1/* (mới)."""
    def deco(f):
        app.route(path, **kwargs)(f)
        app.route(_versioned(path), **kwargs)(f)
        return f
    return deco

# ============ RESPONSE ENVELOPE (B3) ============
# Chuẩn mới cho mọi endpoint TẠO MỚI dưới /api/v1:
#   success: {"ok": true, "data": {...}, "api_version": "v1"}
#   error:   {"ok": false, "error": "<message>", "code": "<snake_code>", "api_version": "v1"}
# Route legacy /api/* GIỮ NGUYÊN shape cũ (app cũ parse trực tiếp) — không migrate
# hàng loạt để tránh vỡ client. Chỉ route mới + route auth core dùng envelope.
def api_ok(data=None, status=200):
    payload = {"ok": True, "data": data if data is not None else {}}
    return jsonify(_with_version(payload)), status

def api_fail(message, code="error", status=400):
    payload = {"ok": False, "error": message, "code": code}
    return jsonify(_with_version(payload)), status

def _with_version(payload):
    """Gắn api_version vào dict response (route v1 gọi qua cùng handler)."""
    try:
        if request.path.startswith("/api/v1/") and isinstance(payload, dict):
            payload = dict(payload)
            payload.setdefault("api_version", API_VERSION)
    except Exception:
        pass
    return payload

# ============ TIEN ICH ============

# Regex validate input de chong Command Injection
_RE_SAFE_CONTAINER = _re_module.compile(r'^[a-zA-Z0-9][a-zA-Z0-9_.\-]{0,127}$')
_RE_SAFE_IP = _re_module.compile(r'^(\d{1,3}\.){3}\d{1,3}$')
_RE_SAFE_CIDR = _re_module.compile(r'^(\d{1,3}\.){3}\d{1,3}/\d{1,2}$')
_RE_SAFE_DISK = _re_module.compile(r'^/dev/[a-z]{2,4}[0-9]?$')

def _validate_ip(ip):
    """Validate IP address format. Return True if valid."""
    if not (ip and _RE_SAFE_IP.match(str(ip))):
        return False
    try:
        import ipaddress
        addr = ipaddress.ip_address(str(ip))
        if addr.is_unspecified or addr.is_multicast or str(addr) == "255.255.255.255":
            return False
        return True
    except Exception:
        return False

def _validate_cidr(cidr):
    """Validate CIDR subnet format. Return True if valid."""
    if not (cidr and _RE_SAFE_CIDR.match(str(cidr))):
        return False
    try:
        import ipaddress
        net = ipaddress.ip_network(str(cidr), strict=False)
        if net.prefixlen < 8 or net.is_unspecified or net.is_multicast:
            return False
        return True
    except Exception:
        return False

def _validate_container_name(name):
    """Validate Docker container name/ID. Return True if safe."""
    return bool(name and _RE_SAFE_CONTAINER.match(name))

def _validate_disk_path(path):
    """Validate disk device path. Return True if safe."""
    return bool(path and _RE_SAFE_DISK.match(path))

def _validate_file_path(path):
    """Validate file path: must be under WEBDAV_FILE_ROOT, no '..' traversal."""
    if not path:
        return False
    real = os.path.realpath(path)
    base = os.path.realpath(WEBDAV_FILE_ROOT)
    return real == base or real.startswith(base + os.sep)

def _archive_member_is_safe(member_name):
    if not member_name:
        return False
    name = str(member_name).strip().replace("\\", "/")
    if not name or name.startswith("/") or name.startswith("//"):
        return False
    if len(name) >= 2 and name[1] == ":" and name[0].isalpha():
        return False
    parts = []
    for part in name.split("/"):
        if not part or part == ".":
            continue
        if part == "..":
            return False
        parts.append(part)
    return bool(parts)

def _archive_member_target_path(dest_dir, member_name):
    return os.path.realpath(os.path.join(dest_dir, str(member_name).replace("\\", "/")))

def _archive_member_within_dest(dest_dir, member_name):
    dest_real = os.path.realpath(dest_dir)
    target_real = _archive_member_target_path(dest_dir, member_name)
    return target_real == dest_real or target_real.startswith(dest_real + os.sep)

def _extract_zip_safe(archive_path, dest_dir):
    import stat
    import zipfile
    with zipfile.ZipFile(archive_path) as zf:
        members = zf.infolist()
        unsafe = []
        for info in members:
            name = info.filename
            if not _archive_member_is_safe(name) or not _archive_member_within_dest(dest_dir, name):
                unsafe.append(name)
                continue
            mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode):
                unsafe.append(name)
        if unsafe:
            raise ValueError("Unsafe archive entry: %s" % ", ".join([u for u in unsafe if u][:5]))
        extracted = 0
        for info in members:
            name = info.filename
            target_path = _archive_member_target_path(dest_dir, name)
            if info.is_dir():
                os.makedirs(target_path, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(target_path), exist_ok=True)
            with zf.open(info, "r") as src, open(target_path, "wb") as dst:
                shutil.copyfileobj(src, dst, length=1024 * 1024)
            extracted += 1
    return extracted

def _extract_tar_safe(archive_path, dest_dir):
    import tarfile
    with tarfile.open(archive_path, "r:*") as tf:
        members = tf.getmembers()
        unsafe = []
        for member in members:
            name = member.name
            if not _archive_member_is_safe(name) or not _archive_member_within_dest(dest_dir, name):
                unsafe.append(name)
                continue
            if member.issym() or member.islnk() or member.isdev():
                unsafe.append(name)
        if unsafe:
            raise ValueError("Unsafe archive entry: %s" % ", ".join([u for u in unsafe if u][:5]))
        extracted = 0
        for member in members:
            name = member.name
            target_path = _archive_member_target_path(dest_dir, name)
            if member.isdir():
                os.makedirs(target_path, exist_ok=True)
                continue
            if member.isfile():
                os.makedirs(os.path.dirname(target_path), exist_ok=True)
                src = tf.extractfile(member)
                if src is None:
                    raise ValueError("Cannot read archive member: %s" % name)
                with src, open(target_path, "wb") as dst:
                    shutil.copyfileobj(src, dst, length=1024 * 1024)
                extracted += 1
                continue
            raise ValueError("Unsupported tar entry type: %s" % name)
    return extracted

def _extract_external_archive_safe(archive_path, dest_dir, tool_path):
    archive_real = os.path.realpath(archive_path)
    dest_real = os.path.realpath(dest_dir)
    tool_base = os.path.basename(tool_path).lower()
    if tool_base not in ("7z", "7zz", "7z.exe", "7zz.exe"):
        raise ValueError("Unsupported archive tool: %s" % tool_base)
    list_cmd = [tool_path, "l", "-slt", archive_real]
    extract_cmd = [tool_path, "x", archive_real, "-o" + dest_real, "-y"]
    listing = subprocess.run(list_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if listing.returncode != 0:
        raise ValueError("Archive listing failed: %s" % listing.stderr.decode("utf-8", errors="replace")[:240])
    unsafe = []
    seen_entries = []
    stdout_text = listing.stdout.decode("utf-8", errors="replace")
    current_path = ""
    current_type = ""
    for line in stdout_text.splitlines():
        if line.startswith("Path = "):
            current_path = line[7:].strip()
        elif line.startswith("Type = "):
            current_type = line[7:].strip().lower()
            if current_path:
                seen_entries.append((current_path, current_type))
                current_path = ""
                current_type = ""
    if current_path:
        seen_entries.append((current_path, current_type))
    for name, entry_type in seen_entries:
        if not _archive_member_is_safe(name) or not _archive_member_within_dest(dest_dir, name):
            unsafe.append(name)
            continue
        if entry_type and entry_type not in ("file", "folder", "directory", "dir"):
            unsafe.append(name)
    if unsafe:
        raise ValueError("Unsafe archive entry: %s" % ", ".join([u for u in unsafe if u][:5]))
    result = subprocess.run(extract_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
    if result.returncode != 0:
        raise ValueError("Archive extraction failed: %s" % result.stderr.decode("utf-8", errors="replace")[:240])
    return len(seen_entries)

def run_cmd(cmd_list, timeout=10, merge_stderr=False):
    """Chay lenh AN TOAN bang list args (KHONG dùng shell=True).
    cmd_list: list of strings, vd: ["docker", "ps", "-a"]
    merge_stderr: True de gop stderr vao stdout (thay cho 2>&1)
    """
    try:
        stderr_dest = subprocess.STDOUT if merge_stderr else subprocess.PIPE
        result = subprocess.run(
            cmd_list, shell=False,
            stdout=subprocess.PIPE,
            stderr=stderr_dest,
            timeout=timeout
        )
        return result.stdout.decode("utf-8", errors="replace").strip()
    except subprocess.TimeoutExpired:
        log.warning("Lệnh quá hạn (%d giây): %s", timeout, cmd_list[:3])
        return ""
    except Exception as e:
        log.error("Lệnh thất bại: %s — %s", cmd_list[:3], e)
        return ""

def safe_run_cmd(cmd_list, timeout=10, merge_stderr=False):
    """Chay lenh AN TOAN bang list args (KHONG dung shell=True) va validate input.
    cmd_list: list of strings, vd: ["docker", "ps", "-a"]
    merge_stderr: True de gop stderr vao stdout (thay cho 2>&1)
    """
    allowed_flags = {"-A", "-d", "-n", "-o", "-o+", "-y", "-C", "-q", "-aq", "-s", "-j", "-D", "-I", "1", "sat", "input", "output", "eth0", "TCP", "-x", "-m", "tcp", "--dport", "-R", "--set-file=-", "--time-format=raw"}
    for idx, arg in enumerate(cmd_list):
        str_arg = str(arg)
        if not str_arg or not str_arg.strip():
            log.warning("safe_run_cmd: tham so lenh khong hop le: %s", arg)
            return ""
        if idx > 0 and str_arg.startswith("-") and str_arg not in allowed_flags:
            log.warning("safe_run_cmd: flag khong duoc phep (%s)", str_arg)
            return ""
    try:
        stderr_dest = subprocess.STDOUT if merge_stderr else subprocess.PIPE
        result = subprocess.run(
            cmd_list,
            shell=False,
            stdout=subprocess.PIPE,
            stderr=stderr_dest,
            timeout=timeout,
        )
        return result.stdout.decode("utf-8", errors="replace").strip()
    except subprocess.TimeoutExpired:
        log.warning("safe_run_cmd: timeout (%ds): %s", timeout, cmd_list[:3])
        return ""
    except Exception as e:
        log.error("safe_run_cmd: failed: %s - %s", cmd_list[:3], e)
        return ""

def _run_acl_copy(source_dir, target_dir):
    """Sao chep ACL tu source_dir sang target_dir an toan (thay the shell pipe)."""
    try:
        acl_result = subprocess.run(
            ["getfacl", source_dir],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10
        )
        if acl_result.returncode == 0:
            subprocess.run(
                ["setfacl", "-R", "--set-file=-", target_dir],
                input=acl_result.stdout,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30
            )
    except Exception as e:
        log.warning("Lỗi sao chép ACL %s -> %s: %s", source_dir, target_dir, e)


def get_cpu_temp():
    """L?y nhi?t để CPU tu thermal zone (Chainedbox rk3328)."""
    # Phuong phap 1: psutil - uu tien sensor CPU/SoC
    try:
        temps = psutil.sensors_temperatures()
        # Uu tien cac ten sensor CPU tren rk3328
        cpu_sensor_names = ["soc-thermal", "soc_thermal", "cpu-thermal",
                           "cpu_thermal", "package_id_0", "core_0",
                           "tsadc", "rockchip-thermal"]
        for pref_name in cpu_sensor_names:
            for name, entries in temps.items():
                if pref_name.lower() in name.lower():
                    for entry in entries:
                        if entry.current > 0:
                            return "%d\u00b0C" % int(entry.current)
        # Fallback: l?y bat ky sensor nao co gia tri hop ly (20-120 do)
        for name, entries in temps.items():
            # B? qua sensor ổ cứng
            if "drive" in name.lower() or "hdd" in name.lower():
                continue
            for entry in entries:
                if 20 < entry.current < 120:
                    return "%d\u00b0C" % int(entry.current)
    except Exception:
        pass
    # Phuong phap 2: Đọc truc tiep tu sysfs (Chainedbox rk3328)
    # Quet tất c? thermal zone de tim zone cua CPU
    try:
        import glob
        thermal_zones = sorted(glob.glob("/sys/class/thermal/thermal_zone*/"))
        for zone_dir in thermal_zones:
            try:
                # Kiểm tra type cua thermal zone
                type_path = os.path.join(zone_dir, "type")
                temp_path = os.path.join(zone_dir, "temp")
                zone_type = ""
                if os.path.exists(type_path):
                    with open(type_path) as f:
                        zone_type = f.read().strip().lower()
                # Uu tien zone CPU / SoC
                if any(k in zone_type for k in ["cpu", "soc", "tsadc"]):
                    with open(temp_path) as f:
                        temp_milli = int(f.read().strip())
                        if temp_milli > 1000:  # millidegree format
                            return "%d\u00b0C" % (temp_milli // 1000)
                        elif 0 < temp_milli < 150:  # degree format
                            return "%d\u00b0C" % temp_milli
            except Exception:
                continue
        # Fallback: Đọc zone0 (thuong la CPU tren ARM SoC)
        with open("/sys/class/thermal/thermal_zone0/temp") as f:
            temp_milli = int(f.read().strip())
            if temp_milli > 1000:
                return "%d\u00b0C" % (temp_milli // 1000)
            elif 0 < temp_milli < 150:
                return "%d\u00b0C" % temp_milli
    except Exception:
        pass
    return "--\u00b0C"


_HDD_TEMP_CACHE = {"value": "--°C", "ts": None, "attempt": None, "paused": True, "cool": 0}
_HDD_TEMP_CACHE_TTL = 60
_HDD_TEMP_REFRESH_LOCK = threading.Lock()


def _cache_hdd_temp(value):
    temp = int(value.replace("°C", ""))
    paused = _HDD_TEMP_CACHE["paused"]
    cool = min(2, _HDD_TEMP_CACHE["cool"] + 1) if temp <= 40 else 0
    if temp >= 42:
        paused = True
    elif cool == 2:
        paused = False
    _HDD_TEMP_CACHE.update(value=value, ts=time.monotonic(), paused=paused, cool=cool)
    return value


def get_hdd_temp():
    now = time.monotonic()
    ts = _HDD_TEMP_CACHE["ts"]
    if ts is not None and now - ts < _HDD_TEMP_CACHE_TTL:
        return _HDD_TEMP_CACHE["value"]
    # Share an in-flight SMART sample before reporting sensor failure.
    if not _HDD_TEMP_REFRESH_LOCK.acquire(timeout=2.0):
        return "--°C"
    try:
        now = time.monotonic()
        ts = _HDD_TEMP_CACHE["ts"]
        if ts is not None and now - ts < _HDD_TEMP_CACHE_TTL:
            return _HDD_TEMP_CACHE["value"]
        last = _HDD_TEMP_CACHE["attempt"]
        if last is not None and now - last < _HDD_TEMP_CACHE_TTL:
            return "--°C"
        _HDD_TEMP_CACHE["attempt"] = now
        try:
            value = _get_hdd_temp_uncached()
        except Exception:
            value = None
        if not value or value == "--°C":
            _HDD_TEMP_CACHE.update(paused=True, cool=0)
            return "--°C"
        return value
    finally:
        _HDD_TEMP_REFRESH_LOCK.release()


def _hdd_maintenance_allowed():
    return get_hdd_temp() != "--°C" and not _HDD_TEMP_CACHE["paused"]


def _get_hdd_temp_uncached():
    """Refresh HDD temperature once. Caller must hold _HDD_TEMP_REFRESH_LOCK."""
    import re
    disk = _target_hdd_device_path()
    if not disk or not _validate_disk_path(disk):
        return "--°C"
    for device_args in (["-d", "sat"], []):
        report = run_cmd(["sudo", "smartctl", "-i", "-A"] + device_args + [disk], merge_stderr=True) or ""
        serial = re.search(r"^Serial Number:\s*(\S+)", report, re.M | re.I)
        if not serial:
            continue
        if serial.group(1).upper() not in TARGET_HDD_SERIAL_HINTS:
            return "--°C"
        for line in report.splitlines():
            fields = line.split()
            if len(fields) >= 10 and fields[0] == "194" and fields[9].isdigit():
                temp = int(fields[9])
                if 0 < temp < 100:
                    return _cache_hdd_temp("%d°C" % temp)
    return "--°C"


def get_uptime():
    """Format uptime thảnh dang de doc."""
    try:
        up_seconds = time.time() - psutil.boot_time()
        months = int(up_seconds // (30 * 86400))
        rem = up_seconds % (30 * 86400)
        days = int(rem // 86400)
        rem %= 86400
        hours = int(rem // 3600)
        rem %= 3600
        minutes = int(rem // 60)
        seconds = int(rem % 60)
        
        parts = []
        if months > 0: parts.append("%d tháng" % months)
        if days > 0: parts.append("%d ngày" % days)
        if hours > 0: parts.append("%d giờ" % hours)
        if minutes > 0: parts.append("%d phút" % minutes)
        parts.append("%d giây" % seconds)
        return ", ".join(parts)
    except Exception:
        return "--"


def format_bytes(b):
    """Format bytes thảnh don vi de doc."""
    if b < 1024:
        return "%d B" % b
    elif b < 1024 ** 2:
        return "%.1f KB" % (b / 1024.0)
    elif b < 1024 ** 3:
        return "%.1f MB" % (b / (1024.0 ** 2))
    elif b < 1024 ** 4:
        return "%.2f GB" % (b / (1024.0 ** 3))
    elif b < 1024 ** 5:
        return "%.2f TB" % (b / (1024.0 ** 4))
    else:
        return "%.2f PB" % (b / (1024.0 ** 5))


def format_speed(bps):
    """Format bytes/sec thảnh toc do."""
    if bps < 1024:
        return "%.0f B/s" % bps
    elif bps < 1024 ** 2:
        return "%.1f KB/s" % (bps / 1024.0)
    else:
        return "%.1f MB/s" % (bps / (1024.0 ** 2))


# L?u tru bang thong mang cho tinh toan delta
_last_net = {"rx": 0, "tx": 0, "time": 0}
_net_stats_lock = threading.Lock()


def get_network_speed():
    """Tinh toc do mang tu delta giua 2 lan goi."""
    global _last_net
    try:
        counters = psutil.net_io_counters()
        now = time.time()
        rx_speed = 0
        tx_speed = 0
        with _net_stats_lock:
            if _last_net["time"] > 0:
                dt = now - _last_net["time"]
                if dt > 0:
                    rx_speed = (counters.bytes_recv - _last_net["rx"]) / dt
                    tx_speed = (counters.bytes_sent - _last_net["tx"]) / dt
            _last_net = {"rx": counters.bytes_recv, "tx": counters.bytes_sent, "time": now}
        return format_speed(max(0, rx_speed)), format_speed(max(0, tx_speed))
    except Exception:
        return "0 B/s", "0 B/s"


def get_torrents():
    """L?y danh sách torrent tu qBittorrent Web API (neu co)."""
    try:
        import urllib.request
        url = "http://127.0.0.1:8080/api/v2/torrents/info"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            result = []
            for t in data:
                result.append({
                    "name": t.get("name", "Unknown"),
                    "progress": round(t.get("progress", 0), 3),
                    "speed": format_speed(t.get("dlspeed", 0)),
                    "hash": t.get("hash", ""),
                    "state": t.get("state", ""),
                    "save_path": t.get("save_path", "")
                })
            return result
    except Exception:
        return []


def get_main_disk_usage():
    """Tim phân vùng dữ liệu chinh (lon nhat) va tr? v? % s? dùng.
    Thay vi Đọc '/' (root eMMC nho), tim phân vùng data HDD that su."""
    try:
        best_usage = None
        best_total = 0
        for partition in psutil.disk_partitions(all=False):
            # B? qua lỗi do Docker overl?y hoac cac FS ao gay fluctuation RAM/ROM
            if partition.fstype in ["overlay", "squashfs", "tmpfs", "devtmpfs"]:
                continue
            if partition.mountpoint.startswith(("/var/lib/docker", "/snap")):
                continue
            try:
                usage = psutil.disk_usage(partition.mountpoint)
                # Chon phân vùng co tong dung lượng lon nhat (= ổ cứng data)
                if usage.total > best_total:
                    best_total = usage.total
                    best_usage = usage
            except (PermissionError, OSError):
                continue
        if best_usage and best_total > 0:
            return "%.1f%%|%s / %s" % (best_usage.percent, format_bytes(best_usage.used), format_bytes(best_usage.total))
    except Exception:
        pass
    # Fallback ve root neu không tìm thấy
    try:
        disk = psutil.disk_usage("/")
        return "%.1f%%|%s / %s" % (disk.percent, format_bytes(disk.used), format_bytes(disk.total))
    except Exception:
        return "--%|"


def get_disk_partitions():
    """L?y thong tin phân vùng o dia (loc bo trung lap va nho)."""
    parts = []
    # Cac thư mục can b? qua (log, ram, overlay, bind mount)
    SKIP_PREFIXES = ("/var/log", "/run/", "/dev/", "/proc/", "/sys/", "/tmp/")
    seen_sizes = {}  # Track duplicate (total_bytes, percent) de loc trung lap
    try:
        for partition in psutil.disk_partitions(all=False):
            mnt = partition.mountpoint
            # B? qua cac thư mục h? thỏng va log nho
            skip = False
            for prefix in SKIP_PREFIXES:
                if mnt.startswith(prefix):
                    skip = True
                    break
            if skip:
                continue
            try:
                usage = psutil.disk_usage(mnt)
                # B? qua phân vùng qua nho (duoi 500MB)
                if usage.total < 500 * 1024 * 1024:
                    continue
                # B? qua phân vùng trung lap (cung dung lượng va % voi phân vùng da co)
                sig = (usage.total, round(usage.percent))
                if sig in seen_sizes:
                    continue
                seen_sizes[sig] = mnt
                parts.append({
                    "mount": mnt,
                    "percent": round(usage.percent, 1),
                    "total": format_bytes(usage.total),
                    "used": format_bytes(usage.used),
                    "free": format_bytes(usage.free)
                })
            except (PermissionError, OSError):
                continue
    except Exception:
        pass
    return parts


def _safe_dir_usage(path, max_files=8000, max_seconds=1.5):
    """Tính nhanh dung lượng thư mục, giới hạn để không làm NAS bị nặng.
    CC-003: cap cũ (4000 file/2s) khiến thư mục Facebook/Backup lớn luôn
    trả partial=True (số thiếu); cap mới 8000/1.5s mỗi thư mục + deadline
    tổng ở get_storage_usage_summary để ~30 thư mục không treo request."""
    start = time.time()
    total = 0
    count = 0
    if not os.path.exists(path):
        return 0, 0, False
    try:
        for root, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if d not in (".nas_meta", ".thumbnails", "@eaDir")]
            for name in files:
                if count >= max_files or (time.time() - start) > max_seconds:
                    return total, count, True
                try:
                    total += os.path.getsize(os.path.join(root, name))
                    count += 1
                except OSError:
                    continue
    except OSError:
        return total, count, True
    return total, count, False


def _iter_hdd_trash_dirs(webdav_root):
    """Yield per-drive .trash directories; never use WEBDAV root .trash."""
    try:
        root_real = os.path.realpath(webdav_root)
        for name in os.listdir(webdav_root):
            drive_dir = os.path.realpath(os.path.join(webdav_root, name))
            if not os.path.isdir(drive_dir):
                continue
            if drive_dir == root_real or not drive_dir.startswith(root_real + os.sep):
                continue
            if name.startswith(".") or name in (".nas_meta", ".thumbnails", "@eaDir"):
                continue
            yield name, os.path.join(drive_dir, ".trash")
    except Exception:
        return


def _trash_dir_for_relative_path(webdav_root, rel_path):
    rel_norm = str(rel_path or "").replace("\\", "/").lstrip("/")
    drive_name = rel_norm.split("/", 1)[0].strip()
    if not drive_name or drive_name in (".trash", ".nas_meta", ".thumbnails"):
        return None
    root_real = os.path.realpath(webdav_root)
    drive_dir = os.path.realpath(os.path.join(webdav_root, drive_name))
    if drive_dir == root_real or not drive_dir.startswith(root_real + os.sep):
        return None
    if not os.path.isdir(drive_dir):
        return None
    return os.path.join(drive_dir, ".trash")


def get_storage_usage_summary():
    """Tóm tắt dung lượng các thư mục lớn để app hiển thị khuyến nghị dọn dẹp.
    CC-003: liệt kê ĐỘNG thư mục cấp 1 của WebDAV root thay vì cứng
    Livestream/Downloads/Backup (root thật là 'New folder' chứa
    'Thư mục 1', Facebook, ... nên số cũ luôn 0/thiếu)."""
    try:
        root = WEBDAV_FILE_ROOT or get_webdav_root()
    except Exception:
        root = WEBDAV_FILE_ROOT
    label_map = {
        "Livestream": "Livestream",
        "Downloads": "Tải xuống",
        "Backup": "Sao lưu",
        "Facebook": "Facebook",
    }
    skip = {".trash", ".thumbs", ".thumbnails", ".nas_meta", ".git",
            ".recycle", "@eaDir", "#recycle", ".cache", ".naswebdav"}
    rels = []
    try:
        for name in os.listdir(root):
            if not name or name.startswith("."):
                continue
            if name in skip:
                continue
            full = os.path.join(root, name)
            try:
                if not os.path.isdir(full):
                    continue
            except Exception:
                continue
            rels.append(name)
    except Exception:
        rels = ["Livestream", "Downloads", "Backup"]
    result = []
    # CC-003: deadline tổng 12s cho cả vòng — root thật có ~30 thư mục cấp 1,
    # mỗi thư mục tối đa 1.5s thì vòng lặp vẫn có thể kéo dài quá timeout HTTP.
    # Hết giờ thì dừng, đánh dấu partial ở các mục chưa quét.
    usage_deadline = time.time() + 12.0
    for rel in sorted(rels):
        if time.time() >= usage_deadline:
            result.append({
                "name": label_map.get(rel, rel),
                "path": rel,
                "size_bytes": 0,
                "size": format_bytes(0),
                "files": 0,
                "partial": True,
            })
            continue
        path = os.path.join(root, rel)
        size, files, partial = _safe_dir_usage(path)
        result.append({
            "name": label_map.get(rel, rel),
            "path": rel,
            "size_bytes": size,
            "size": format_bytes(size),
            "files": files,
            "partial": partial,
        })
    trash_size = 0
    trash_files = 0
    trash_partial = False
    for _drive, trash_path in _iter_hdd_trash_dirs(root):
        size, files, partial = _safe_dir_usage(trash_path)
        trash_size += size
        trash_files += files
        trash_partial = trash_partial or partial
    result.append({
        "name": "Thùng rác",
        "path": ".trash",
        "size_bytes": trash_size,
        "size": format_bytes(trash_size),
        "files": trash_files,
        "partial": trash_partial,
    })
    result.sort(key=lambda x: x.get("size_bytes", 0), reverse=True)
    return result


_STORAGE_USAGE_CACHE = {"ts": 0.0, "data": None}
_STORAGE_USAGE_CACHE_LOCK = threading.Lock()
_STORAGE_USAGE_CACHE_TTL = 300


def _build_storage_usage_payload():
    try:
        _root = WEBDAV_FILE_ROOT or get_webdav_root()
    except Exception:
        _root = WEBDAV_FILE_ROOT
    try:
        usage = psutil.disk_usage(_root)
    except Exception:
        usage = psutil.disk_usage(WEBDAV_FILE_ROOT)
    return {
        "ok": True,
        "root": _root,
        "total": format_bytes(usage.total),
        "used": format_bytes(usage.used),
        "free": format_bytes(usage.free),
        "percent": round(usage.percent, 1),
        "folders": get_storage_usage_summary(),
    }


def _refresh_storage_usage_cache_locked():
    try:
        _STORAGE_USAGE_CACHE["data"] = _build_storage_usage_payload()
        _STORAGE_USAGE_CACHE["ts"] = time.time()
    finally:
        _STORAGE_USAGE_CACHE_LOCK.release()



def get_fan_info():
    """Lấy thông tin quạt làm mát - Chainedbox rk3328 dùng PWM pwmchip0."""
    PWM_DIR = "/sys/class/pwm/pwmchip0/pwm0"
    try:
        duty_path = os.path.join(PWM_DIR, "duty_cycle")
        period_path = os.path.join(PWM_DIR, "period")
        
        # Đọc setting tu JSON
        settings = {"mode": "auto", "on_temp": FAN_DEFAULT_ON_TEMP, "off_temp": FAN_DEFAULT_OFF_TEMP}
        try:
            if os.path.exists("/opt/fan_custom.json"):
                with open("/opt/fan_custom.json", "r") as f:
                    settings.update(json.load(f))
        except Exception:
            pass
            
        mode = settings.get("mode", "auto")

        if os.path.exists(duty_path):
            with open(duty_path) as f:
                duty = int(f.read().strip())
            period = 10000
            if os.path.exists(period_path):
                try:
                    with open(period_path) as f:
                        period = max(int(f.read().strip()), 1)
                except Exception:
                    pass
            raw_percent = int((duty * 100.0) / period)

            # FIX: Đọc th?m enable de bao cao "Tat" chinh xac khi PWM da bi cat hen
            enable_path = os.path.join(PWM_DIR, "enable")
            enable_val = 1
            try:
                if os.path.exists(enable_path):
                    with open(enable_path) as f:
                        enable_val = int(f.read().strip() or "1")
            except Exception:
                pass

            if mode not in ["auto", "custom"]:
                if duty == 0 or enable_val == 0: mode = "off"
                else: mode = "on"

            percent = _fan_pwm_level(raw_percent if enable_val == 1 else 0)
            payload = {
                "rpm": _fan_rpm_for_percent(percent),
                "percent": percent,
                "mode": mode,
                "on_temp": settings.get("on_temp", FAN_DEFAULT_ON_TEMP),
                "off_temp": settings.get("off_temp", FAN_DEFAULT_OFF_TEMP)
            }
            payload["status"] = _fan_status_for_percent(percent)
            return payload
    except Exception:
        pass

    # Fallback: hwmon fan1_input (RPM truc tiep - neu co)
    try:
        import glob
        for hwmon_dir in glob.glob("/sys/class/hwmon/hwmon*/"):
            fan_path = os.path.join(hwmon_dir, "fan1_input")
            if os.path.exists(fan_path):
                with open(fan_path) as f:
                    rpm = int(f.read().strip())
                return {"rpm": rpm, "status": "Đang chạy" if rpm > 0 else "Dừng", "percent": None}
    except Exception:
        pass

    return {"rpm": None, "status": "Không đo được", "percent": None}



_EXCLUDED_SYSTEM_PROCS = (
    "kworker", "systemd", "rcu", "migration", "ksoftirqd", "init", "kthreadd",
    "journald", "udevd", "dbus", "sshd", "getty", "cron", "syslog",
    "rsyslog", "networkmanager", "avahi", "polkit", "containeractions",
    "containerd", "dockerd", "php-fpm", "omv-engined", "anacron", "atd",
    "smartd", "watchdog", "ntpd", "tailscaled", "nginx", "nas_api_server",
    "rpcbind", "rngd", "sd-pam", "bash", "ps", "sh"
)

def get_top_processes(n=3):
    """Lấy top N tiến trình ứng dụng (không thuộc hệ thống OS) tiêu hao RAM/CPU nhiều nhất (Python 3.5)."""
    procs = []
    try:
        num_cores = psutil.cpu_count() or 1
        active_procs = []

        for proc in psutil.process_iter():
            try:
                if _is_kernel_or_system_proc(proc):
                    continue
                proc.cpu_percent()
                active_procs.append(proc)
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue

        time.sleep(0.1)

        for proc in active_procs:
            try:
                cpu = proc.cpu_percent() / num_cores
                mem = proc.memory_percent()
                name = proc.name()
                # Chọn chỉ số cao nhất giữa CPU % và RAM % để hiển thị đúng thực tế tiêu hao tài nguyên hệ thống
                resource_pct = round(max(cpu, mem), 1)
                procs.append({"name": name, "cpu": resource_pct, "mem": round(mem, 1)})
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue

        procs.sort(key=lambda x: x["cpu"], reverse=True)
        # Gom nhóm tiến trình cùng tên
        grouped = {}
        for p in procs:
            key = p["name"]
            if key in grouped:
                grouped[key]["cpu"] = min(round(grouped[key]["cpu"] + p["cpu"], 1), 100.0)
                grouped[key]["mem"] = min(round(grouped[key]["mem"] + p["mem"], 1), 100.0)
            else:
                p["cpu"] = min(p["cpu"], 100.0)
                grouped[key] = dict(p)
        
        user_cpu_sum = sum(p["cpu"] for p in grouped.values())
        user_mem_sum = sum(p["mem"] for p in grouped.values())

        total_sys_cpu = round(psutil.cpu_percent(interval=None), 1)
        try:
            total_sys_mem = round(psutil.virtual_memory().percent, 1)
        except Exception:
            total_sys_mem = 0.0

        sys_cpu = max(0.0, round(total_sys_cpu - user_cpu_sum, 1))
        sys_mem = max(0.0, round(total_sys_mem - user_mem_sum, 1))

        grouped["Hệ thống (System OS)"] = {
            "name": "Hệ thống (System OS)",
            "cpu": sys_cpu,
            "mem": sys_mem,
            "is_system": True
        }

        result = sorted(grouped.values(), key=lambda x: x["cpu"], reverse=True)
        return result[:n]
    except Exception:
        return []


def _ensure_trash_directories():
    """Tự động kiểm tra và tạo thư mục .trash/ trên các phân vùng đĩa cứng ngoài để WebDAV MOVE hoạt động 100%."""
    try:
        if os.path.exists("/srv"):
            for item in os.listdir("/srv"):
                if item.startswith("dev-disk-by-"):
                    disk_path = os.path.join("/srv", item)
                    trash_dir = os.path.join(disk_path, ".trash")
                    if os.path.isdir(disk_path) and not os.path.exists(trash_dir):
                        try:
                            os.makedirs(trash_dir, mode=0o777, exist_ok=True)
                            safe_run_cmd(["chown", "-R", "daica:webdav-users", trash_dir])
                            os.chmod(trash_dir, 0o777)
                            log.info("[DiskInit] Đã tự động tạo thư mục Thùng rác: %s", trash_dir)
                        except Exception as e:
                            log.warning("[DiskInit] Không thể tạo %s: %s", trash_dir, e)
    except Exception:
        pass


# ============ BACKGROUND CACHE (Ph?n h?i API tuc thi) ============
_status_cache = {"status": "Đang khởi động..."}
_cache_lock = threading.Lock()

def _status_cache_after_error(previous, error):
    data = dict(previous)
    data["status"] = "Lỗi: %s" % error
    return data

def _update_status_cache():
    """Background thread: cập nhật dữ liệu hệ thống mỗi 2 giây."""
    global _status_cache
    loop_count = 0
    cached_top = []
    cached_disk_parts = []
    cached_disk = "--%|"
    cached_torrents = []
    cached_fan = {}
    cached_hdd_temp = "--\u00b0C"

    while True:
        try:
            cpu_percent = psutil.cpu_percent(interval=1)
            mem = psutil.virtual_memory()
            
            # FIX LOGIC UI: psutil's mem.used does not match mem.percent on Linux because of cache differences. 
            # We must use (total - available) as the displayed 'used' memory so the math (percent) matches correctly.
            actual_used = mem.total - getattr(mem, 'available', mem.free)
            ram_used = format_bytes(actual_used)
            ram_total = format_bytes(mem.total)
            net_rx, net_tx = get_network_speed()

            # Cap nhat thong tin Fan (moi 4 giay - doc 2 sysfs file nhe)
            cached_fan = get_fan_info()

            # Cap nhat top_processes va torrents (moi 2 phut) - Giam tai CPU
            if loop_count % 24 == 0:
                cached_top = get_top_processes(3)
                cached_torrents = get_torrents()
            
            # Cap nhat thong tin ổ cứng (moi 10 phut) - Giam tai I/O
            if loop_count % 120 == 0:
                cached_disk_parts = get_disk_partitions()
                cached_disk = get_main_disk_usage()
            
            # Nhi?t để HDD: L?y tu cache SMART de khong spin-up ổ cứng (SMART được cache 24h)
            # HDD temp: live sensor first, then disk health, then SMART cache.
            try:
                cached_hdd_temp = get_hdd_temp()
            except Exception:
                cached_hdd_temp = "--°C"
            actual_ram_pct = round((float(actual_used) / float(mem.total)) * 100.0, 1)
            data = {
                "temperature": cached_hdd_temp,
                "cpu": "%.1f%%" % cpu_percent,
                "cpu_temp": get_cpu_temp(),
                "ram": "%s / %s" % (ram_used, ram_total),
                "ram_used": ram_used,
                "ram_total": ram_total,
                "ram_percent": str(int(round(actual_ram_pct))),
                "mem_used": ram_used,
                "mem_total": ram_total,
                "mem_percent": str(int(round(actual_ram_pct))),
                "disk": cached_disk,
                "net_rx": net_rx,
                "net_tx": net_tx,
                "uptime": get_uptime(),
                "status": "Online",
                "fan_rpm": cached_fan.get("rpm"),
                "fan_percent": cached_fan.get("percent"),
                "fan_status": cached_fan.get("status", "--"),
                "fan_mode": cached_fan.get("mode", "auto"),
                "fan_on_temp": cached_fan.get("on_temp", FAN_DEFAULT_ON_TEMP),
                "fan_off_temp": cached_fan.get("off_temp", FAN_DEFAULT_OFF_TEMP),
                "top_processes": cached_top,
                "torrents": cached_torrents,
                "disk_parts": cached_disk_parts
            }
            with _cache_lock:
                _status_cache = data
            if loop_count % 24 == 0:
                _db_set_json("hardware_status", "latest", data)
                _update_process_state(
                    "hardware",
                    cpu_percent=round(float(cpu_percent or 0), 1),
                    cpu_temp=data.get("cpu_temp"),
                    hdd_temp=cached_hdd_temp,
                    ram_percent=int(round(mem.percent)),
                    ram_used=ram_used,
                    ram_total=ram_total,
                    net_rx=net_rx,
                    net_tx=net_tx,
                    uptime=data.get("uptime"),
                    fan=cached_fan,
                    disk=cached_disk,
                    disk_parts=cached_disk_parts,
                    top_processes=cached_top,
                )
            
            loop_count = (loop_count + 1) % 600
        except Exception as e:
            with _cache_lock:
                _status_cache = _status_cache_after_error(_status_cache, str(e))
        time.sleep(4)


# ============ CRON WORKER (TU DONG HOA) ============
# Chay nen moi 1 gio: Don dep Thung rac, kick AI scan luc 2h sang
# TU?N TH? hardware constraints RK3328: Khong poll CPU qua 10s, I/O nh? nhan

# Bien toan cuc l?u trạng thái cầnh b?o (cho alert polling cua Android)
_alert_state_lock = threading.Lock()
_alert_states = {
    "hdd_temp_alerted": False,      # để g?i cầnh b?o nhi?t để ch?a?
    "offline_alerted": False,        # để g?i cầnh b?o offline ch?a?
    "last_torrent_states": {},       # {hash: progress} - so sảnh phat hien hoan thảnh
    "last_alerts": [],               # Danh sách cầnh b?o moi (Android poll)
    "ai_scan_running": False,        # Đang qu?t ảnh hay khong
    "ai_last_scan": 0,               # Thoi gian lan quet AI cuoi cung (epoch)
    "trash_last_clean": 0,           # Thoi gian lan don rac cuoi cung (epoch)
    "empty_last_clean": 0,           # Thoi gian lan don file/folder rong cuoi cung (epoch)
}

def _push_alert(alert_type, message, severity="INFO"):
    """Th?m cầnh b?o vao hang doi de Android l?y qua /api/alerts/poll"""
    message = normalize_vietnamese_message(sanitize_log_input(message))
    alert_type = sanitize_log_input(alert_type)
    with _alert_state_lock:
        # Giu toi da 50 cầnh b?o gần nh?t
        _alert_states["last_alerts"].append({
            "type": alert_type,
            "message": message,
            "severity": severity,
            "timestamp": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
        })
        if len(_alert_states["last_alerts"]) > 50:
            _alert_states["last_alerts"] = _alert_states["last_alerts"][-50:]
    # Ghi vao DB log
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        cur = conn.cursor()
        cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                    (severity, "Alert", message))
        conn.commit()
        conn.close()
    except Exception:
        pass
    # Broadcast WebSocket cho client dang ket noi
    try:
        if main_loop:
            main_loop.add_callback(
                lambda: broadcast({"type": alert_type, "message": message, "severity": severity})
            )
    except Exception:
        pass


def _clean_trash(webdav_root, max_age_days=30):
    """Xoá các file trong thư mục .trash/ quá N ngày. Không wake spin-up HDD không cần thiết.

    P1-6: moc thoi gian phai la LUC DUA VAO TRASH, khong phai mtime noi dung.
    Ban cu dung mtime: os.rename() giu nguyen mtime -> anh sua tu thang truoc,
    vua xoa hom nay, bi coi la "qua 30 ngay" ngay dot don sau. Quy tac moi:
    dung max(mtime, ctime) — rename vao trash CAP NHAT ctime (= luc dua vao
    trash). File cu trong trash tu truoc (chua tung rename lai) giu hanh vi cu.
    """
    try:
        now = time.time()
        max_age_sec = max_age_days * 86400
        deleted = 0
        for _drive, trash_dir in _iter_hdd_trash_dirs(webdav_root):
            if not os.path.exists(trash_dir):
                continue
            for fname in os.listdir(trash_dir):
                fpath = os.path.join(trash_dir, fname)
                try:
                    st = os.stat(fpath)
                    trashed_at = max(st.st_mtime, st.st_ctime)
                    age = now - trashed_at
                    if age > max_age_sec:
                        if os.path.isdir(fpath):
                            shutil.rmtree(fpath, ignore_errors=True)
                        else:
                            os.remove(fpath)
                        deleted += 1
                except Exception:
                    continue
        return deleted
    except Exception:
        return 0




# Map ten thư mục -> dảnh muc gallery (Python 3.5 compat, KHÔNG cần Docker/TFLite)
_FOLDER_CATEGORY_MAP = {
    "Khuon Mat":           ["selfie", "portrait", "face", "avatar"],
    "Mang Xa Hoi":         ["facebook", "tiktok", "telegram", "instagram", "zalo", "messenger"],
    "Thien Nhien / Bien":  ["beach", "sea", "ocean", "nature", "mountain", "sunset", "sky"],
    "Video":               ["video", "movie", "film", "clip"],
    "Tai Lieu":            ["document", "doc", "scan", "notes", "samsung notes", "pdf"],
    "Camera / Giam Sat":   ["surveillance", "camera", "cctv", "security"],
    "ảnh Tai Ve":          ["download", "downloads", "saved"],
    "Album Dien Thoai":    ["dcim", "camera", "screenshot", "album"],
}
_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic"}


def _scan_photos_lightweight():
    """
    Quet va phan loai anh theo ten thu muc — Python 3.5 thuan, KHONG can Docker.
    Chay truc tiep tren NAS, DOC danh sach anh tu file_index cua catalogue (nas_index.db)
    thay boi os.walk toan bo thu muc. Luu tag vao bang ai_image_tags trong nas_index.db
    (lien ket voi catalogue_revision), loai bo file ai_tags.json cu hoac dong bo hai chieu.
    """
    try:
        with _alert_state_lock:
            if _alert_states["ai_scan_running"]:
                return False  # Dang chay roi
            _alert_states["ai_scan_running"] = True

        root = _get_webdav_public_dir()
        if not root:
            log.error("[AI Scan] WebDAV root chua san sang")
            with _alert_state_lock:
                _alert_states["ai_scan_running"] = False
            return False
        root_real = os.path.realpath(root)

        conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        try:
            state = conn.execute("SELECT root,generation,ready,catalogue_revision,scanned_at FROM index_state WHERE id=1").fetchone()
            if (not state or state[0] != root_real or not state[2] or
                    time.time() - float(state[4] or 0) > FILE_INDEX_FRESHNESS_SECONDS or _index_scan_lock.locked()):
                _refresh_file_index_for_search()
                # Lay lai state sau khi refresh
                state = conn.execute("SELECT root,generation,ready,catalogue_revision,scanned_at FROM index_state WHERE id=1").fetchone()
                if not state or state[0] != root_real or not state[2]:
                    log.error("[AI Scan] Catalogue chua san sang (root=%s)", root)
                    with _alert_state_lock:
                        _alert_states["ai_scan_running"] = False
                    return False
            conn.execute("BEGIN")
            catalogue_revision = state[3] or 0

            # Doc tat ca anh tu file_index (da duoc index boi file_index daemon).
            # Chi lay anh (co duan hinh anh).
            ext_predicate = " OR ".join(["lower(name) LIKE ?"] * len(_IMAGE_EXTS))
            ext_params = ["%%%s" % ext for ext in sorted(_IMAGE_EXTS)]
            rows = conn.execute(
                "SELECT path,name,size,mtime FROM file_index WHERE is_dir=0 AND (" + ext_predicate + ") "
                "ORDER BY path",
                tuple(ext_params)).fetchall()

            # Xoa tag cu cua catalogue_revision nay truoc khi ghi moi (tranh bo sinh).
            conn.execute("DELETE FROM ai_image_tags WHERE catalogue_revision=?", (catalogue_revision,))

            categories = {}  # {"Mang Xa Hoi": ["Facebook/img1.jpg", ...], ...}
            total = 0
            now = time.time()
            tag_rows = []
            for row in rows:
                fpath, fname, fsize, fmtime = row[0], row[1], row[2], row[3]
                total += 1
                rel_path = fpath  # file_index.path luu da la relative duoi root

                # Phan loai dua ten thu muc (giong logic cu, khong can Docker/TFLite).
                matched = False
                cat_name = "Khac"
                for cname, keywords in _FOLDER_CATEGORY_MAP.items():
                    for kw in keywords:
                        if kw in fpath.lower():
                            cat_name = cname
                            matched = True
                            break
                    if matched:
                        break

                if cat_name not in categories:
                    categories[cat_name] = []
                categories[cat_name].append(rel_path)

                tag_rows.append((rel_path, cat_name, catalogue_revision, now))

            # Ghi batch tag moi (atomic trong transaction cua conn).
            if tag_rows:
                conn.executemany(
                    "INSERT OR REPLACE INTO ai_image_tags(path,category,catalogue_revision,updated_at) "
                    "VALUES(?,?,?,?)", tag_rows)
            conn.commit()

            # Giu lai file ai_tags.json cu de client cu van doc duoc (dong bo hai chieu trong giai cap phat).
            try:
                _write_ai_tags_json(categories, total, catalogue_revision)
            except Exception:
                pass

            categories = None
            tag_rows = None
            _release_memory_to_os()

            with _alert_state_lock:
                _alert_states["ai_scan_running"] = False
                _alert_states["ai_last_scan"] = time.time()
                _alert_states["ai_catalogue_revision"] = catalogue_revision

            return True
        finally:
            conn.close()
    except Exception as e:
        with _alert_state_lock:
            _alert_states["ai_scan_running"] = False
        log.error("[AI Scan] Loi: %s", e)
        return False


def _write_ai_tags_json(categories, total, catalogue_revision):
    """Ghi (hoac xoa neu co) ai_tags.json de client cu van doc duoc trong giai cap phat.
    Khi co du lieu moi trong DB, ghi JSON tu DB; neu categories trong, xoa file cu."""
    try:
        parent_dir = os.path.dirname(AI_TAGS_PATH)
        if parent_dir and not os.path.exists(parent_dir):
            os.makedirs(parent_dir, exist_ok=True)
        if not categories or total == 0:
            # Xoa file cu neu khong co du lieu moi
            if os.path.exists(AI_TAGS_PATH):
                try: os.remove(AI_TAGS_PATH)
                except Exception: pass
            return
        ai_data = {
            "categories": categories,
            "total": total,
            "generated_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "catalogue_revision": catalogue_revision,
            "source": "file_index_db",
        }
        tmp_path = AI_TAGS_PATH + ".tmp"
        with open(tmp_path, "w") as f:
            json.dump(ai_data, f)
        os.rename(tmp_path, AI_TAGS_PATH)
    except Exception:
        pass


_background_heavy_gate_cache = {"time": 0.0, "allowed": True}

def _heavy_background_processes():
    """Detect heavy jobs that may outlive NAS API in-memory state after restart."""
    heavy = []
    own_pid = os.getpid()
    tokens = ("yt-dlp", "rsync", "rclone", "scp")
    try:
        for proc in psutil.process_iter(["pid", "name", "cmdline"]):
            try:
                pid = int(proc.info.get("pid") or 0)
                if pid == own_pid:
                    continue
                name = (proc.info.get("name") or "").lower()
                cmdline = " ".join(proc.info.get("cmdline") or [])
                cmd = cmdline.lower()
                if "/tmp/loop_" in cmd and "livestream" in cmd:
                    heavy.append({"pid": pid, "name": name or "python", "reason": "livestream"})
                elif name == "ffmpeg" or "/ffmpeg" in cmd:
                    heavy.append({"pid": pid, "name": "ffmpeg", "reason": "ffmpeg"})
                elif any(t in name or t in cmd for t in tokens):
                    heavy.append({"pid": pid, "name": name or "process", "reason": "transfer"})
                if len(heavy) >= 8:
                    break
            except Exception:
                continue
    except Exception:
        pass
    return heavy

def _background_heavy_work_allowed(ignore_user_idle=False):
    try:
        if not _hdd_maintenance_allowed():
            return False
        now = time.time()
        try:
            _gate_cache_s = float(globals().get("_get_heavy_gate_cache_s", lambda: 5.0)())
        except Exception:
            _gate_cache_s = 5.0
        if not ignore_user_idle and (now - _background_heavy_gate_cache.get("time", 0.0) < _gate_cache_s):
            return bool(_background_heavy_gate_cache.get("allowed", True))
        allowed = True
        if bool(globals().get("_usb_import_running", False)):
            allowed = False
        # CLIENT-FIRST: user dang duyet -> daemon nghi, nhuong CPU cho serve.
        if allowed and not ignore_user_idle:
            try:
                try:
                    _idle_s = float(globals().get("_get_user_idle_s", lambda: 90)())
                except Exception:
                    _idle_s = 90.0
                if now - float(_LAST_USER_HIT.get("time", 0.0)) < _idle_s:
                    allowed = False
            except Exception:
                pass
        lock = globals().get("_livestream_lock")
        jobs = globals().get("_livestream_jobs", {})
        if allowed and lock:
            with lock:
                if any(j.get("status") == "recording" for j in jobs.values()):
                    allowed = False
        if allowed and _heavy_background_processes():
            allowed = False
        try:
            _ram_block = float(globals().get("_get_ram_block_pct", lambda: 78)())
        except Exception:
            _ram_block = 78.0
        if allowed and psutil.virtual_memory().percent > _ram_block:
            allowed = False
        if allowed:
            try:
                try:
                    _load_block = float(globals().get("_get_load_block", lambda: 2.5)())
                except Exception:
                    _load_block = 2.5
                # Sử dụng Load Average (1 phút) của Linux. RK3328 có 4 nhân.
                # Nếu tải trung bình 1 phút vượt quá 2.5, tức là máy đang thực sự bận rộn lâu dài.
                if os.getloadavg()[0] > _load_block:
                    allowed = False
            except AttributeError:
                # Fallback nếu chạy trên Windows (không có getloadavg)
                try:
                    _cpu_block = float(globals().get("_get_cpu_block_pct", lambda: 75)())
                except Exception:
                    _cpu_block = 75.0
                if psutil.cpu_percent(interval=1.0) > _cpu_block:
                    allowed = False

        if not ignore_user_idle:
            _background_heavy_gate_cache.update({"time": now, "allowed": allowed})
        return allowed
    except Exception:
        return False


# F2: vung TAM DO APP QUAN LY — khong doan bang ten tren toan WebDAV root.
# - Backend tu tao tmp duoi _get_hdd_tmp_root() (nas_meta_tmp): toan bo vung
#   nay la app-managed -> don theo tuoi, khong can pattern.
# - Android dat tmp tren WebDAV qua ten co dinh: "__upload*" (prefix),
#   ".restore-tmp.<tag>" (infix phan tach ro). Chi hai dang nay duoc don.
# - Bo han cac pattern substring (.part/.tmp/.lock/.pid/.segment/.chunk/
#   .download/.crdownload): "chapter.part1.txt", "temp_notes.txt" cua user
#   khop nham -> mat du lieu. File ngoai hai dang tren -> GIU.
_TEMP_ZERO_BYTE_PREFIXES = (
    "__upload",
)
_TEMP_ZERO_BYTE_CONTAINS = (
    ".restore-tmp.",  # staging restore co tx_tag giua ten
)

def _is_app_temp_zero_byte(fname):
    """True chi khi ten file khop CHAT pattern tam app (prefix/delimited)."""
    lower = (fname or "").lower()
    if not lower or lower.startswith("."):
        return False
    for prefix in _TEMP_ZERO_BYTE_PREFIXES:
        if lower.startswith(prefix):
            return True
    for infix in _TEMP_ZERO_BYTE_CONTAINS:
        if infix in lower:
            return True
    return False

def _is_app_temp_dirname(dname):
    """Thu muc tam app: chi ten chat temp (khong nhan ten chung nhu tmp/temp)."""
    lower = (dname or "").lower()
    if not lower or lower.startswith("."):
        return False
    if lower.startswith("__upload"):
        return True
    if ".restore-tmp." in lower:
        return True
    return False

def _clean_managed_tmp_root(max_seconds=20, min_age_seconds=7200):
    """F2: don vung tmp do app quan ly (_get_hdd_tmp_root + fallback /tmp).
    Toan bo vung nay la app-managed nen don theo tuoi, khong doan ten.
    Tra ve so entry da xoa."""
    removed = 0
    deadline = time.time() + max_seconds
    roots = []
    try:
        roots.append(_get_hdd_tmp_root())
    except Exception:
        pass
    if "/tmp/nas_meta_tmp" not in roots:
        roots.append("/tmp/nas_meta_tmp")
    for root in roots:
        if time.time() >= deadline or not root:
            continue
        try:
            real_root = os.path.realpath(root)
            if not os.path.isdir(real_root):
                continue
            for entry in os.listdir(real_root):
                if time.time() >= deadline:
                    break
                p = os.path.join(real_root, entry)
                try:
                    if time.time() - os.path.getmtime(p) < min_age_seconds:
                        continue
                    if os.path.isdir(p) and not os.path.islink(p):
                        shutil.rmtree(p, ignore_errors=True)
                    else:
                        os.remove(p)
                    removed += 1
                except (FileNotFoundError, PermissionError, OSError):
                    continue
        except (PermissionError, OSError):
            continue
    if removed:
        log.info("[NASCleanup] Removed %d stale managed-tmp entries", removed)
    return removed

def _clean_empty_files_and_dirs(root_dir, exclude_dirs=None, max_entries=12000, max_seconds=15, min_age_seconds=3600):
    """Don dep file TAM 0-byte co nguon goc ro + FLV hong cu + thu muc rong.
    B? qua cac thu muc h? thong: .trash, .nas_meta, .thumbnails, .git, .recycle.

    P1-5: ban cu xoa MOI file 0-byte khong-dotfile (ke ca file cua user nhu
    empty.txt, file danh dau) va moi thu muc rong (ke ca thu muc user vua tao
    chua copy file vao). Quy tac moi:
    - File 0-byte: chi xoa khi ten khop pattern tam app VA tuoi >= min_age
      (mac dinh 1h, tranh dua voi upload dang chay).
    - Thu muc rong: chi xoa khi ten khop pattern tam HOAC tuoi >= 24h (thu muc
      user moi tao chua kip dung khong bi xoa).

    Tr? v? tuple (so file rong da xoa, so thu muc da xoa, so FLV hong da xoa).
    """
    if exclude_dirs is None:
        exclude_dirs = {".trash", ".nas_meta", ".thumbnails", ".git", ".recycle", "@eaDir"}
    if not os.path.isdir(root_dir):
        return (0, 0, 0)
    deleted_files = 0
    deleted_dirs = 0
    deleted_broken_flv = 0
    scanned = 0
    now = time.time()
    deadline = now + max_seconds
    # Walk bottom-up de xoá thu muc tu trong ra ngoai
    for dirpath, dirnames, filenames in os.walk(root_dir, topdown=False):
        if scanned >= max_entries or time.time() >= deadline or not _background_heavy_work_allowed():
            break
        scanned += len(filenames) + 1
        # B? qua cac thu muc system
        rel = os.path.relpath(dirpath, root_dir)
        parts = rel.split(os.sep)
        if any(p in exclude_dirs for p in parts):
            continue
        # 1) Xo? file 0-byte TAM (co pattern + du tuoi)
        for fname in filenames:
            if fname.startswith("."):
                continue  # b? qua dotfile (.DS_Store, .gitkeep, .nomedia, etc.)
            fpath = os.path.join(dirpath, fname)
            try:
                if not os.path.isfile(fpath):
                    continue
                lower_name = fname.lower()
                is_broken_flv = lower_name.endswith(".broken.flv") or ".flv.broken" in lower_name
                if is_broken_flv:
                    # FLV hong: chi xoa khi cu (>= 24h) de tranh dua voi recorder.
                    try:
                        if now - os.path.getmtime(fpath) >= 86400:
                            os.remove(fpath)
                            deleted_broken_flv += 1
                    except (FileNotFoundError, PermissionError, OSError):
                        pass
                elif os.path.getsize(fpath) == 0 and _is_app_temp_zero_byte(fname):
                    try:
                        if now - os.path.getmtime(fpath) >= min_age_seconds:
                            os.remove(fpath)
                            deleted_files += 1
                    except (FileNotFoundError, PermissionError, OSError):
                        pass
            except Exception:
                pass
        # 2) R2: chi xoa thu muc rong do APP quan ly (ten temp chat). Bo nhanh
        # "rong >24h" — thu muc rong cua user (vua tao chua copy file vao, thu
        # muc giu cau truc) khong phai rac. Thu muc tam that su co ten temp.
        try:
            if dirpath == root_dir:
                continue  # không xoá root
            if not os.listdir(dirpath):
                dname = os.path.basename(dirpath)
                if _is_app_temp_dirname(dname):
                    try:
                        if now - os.path.getmtime(dirpath) >= min_age_seconds:
                            os.rmdir(dirpath)
                            deleted_dirs += 1
                    except (FileNotFoundError, PermissionError, OSError):
                        pass
        except Exception:
            pass
    if deleted_files or deleted_dirs or deleted_broken_flv:
        _index_mutated_directories([root_dir])
    return (deleted_files, deleted_dirs, deleted_broken_flv)


def _check_torrent_completion():
    """Phat hien torrent vua hoan thảnh (progress 1.0) so voi lan poll truoc."""
    new_completed = []
    try:
        current = get_torrents()  # L?y trạng thái mới nh?t
        with _alert_state_lock:
            prev_states = dict(_alert_states["last_torrent_states"])
            # Cap nhat trạng thái hien tai
            new_states = {}
            for t in current:
                h = t.get("hash", "")
                if h:
                    new_states[h] = t.get("progress", 0)
            _alert_states["last_torrent_states"] = new_states

        for t in current:
            h = t.get("hash", "")
            if not h:
                continue
            prev_prog = prev_states.get(h, -1)
            curr_prog = t.get("progress", 0)
            # Chi bao khi chuyen tu <1.0 sang >=1.0 (vua hoan thảnh)
            if prev_prog >= 0 and prev_prog < 1.0 and curr_prog >= 1.0:
                name = t.get("name", "Unknown")
                new_completed.append(name)
    except Exception:
        pass
    return new_completed


def _check_hdd_temp_alert(threshold=45):
    value = get_hdd_temp()
    if value == "--°C":
        return 0, False
    temp = int(value.replace("°C", ""))
    return temp, temp >= threshold


def _cron_worker():
    """
    Background cron thread chay mới 60 gi?y.
    Dam nhiem cac viec: don rac, phat hien cầnh b?o, kick AI 2h sang.
    Tuan thu STRICT: khong poll HDD/proc qua thuong, interval >= 60s
    """
    # Doi 60 giay sau khi server khoi dong de trảnh trảnh tai I/O luc boot
    time.sleep(60)
    check_interval = 0  # Dem so vong de don rac (moi 3600s / 60 = 60 vong)
    while True:
        try:
            now_ts = time.time()
            now_dt = datetime.datetime.now()
            _social_remove_expired_jobs(max_age_sec=3600)

            # --- 1. Kiểm tra torrent hoan thảnh (mới 60 gi?y) ---
            completed = _check_torrent_completion()
            for name in completed:
                msg = "Torrent đã tải xong: %s" % name
                _push_alert("TORRENT_DONE", msg, "SUCCESS")

            # --- 2. Kiểm tra nhi?t để HDD (mới 60 gi?y) ---
            # NOTE: get_hdd_temp() co cache 60s rieng, không wake HDD th?m lan nua
            temp_val, is_hot = _check_hdd_temp_alert()
            with _alert_state_lock:
                prev_alerted = _alert_states["hdd_temp_alerted"]
            if is_hot and not prev_alerted:
                msg = "CẢNH BÁO: Nhiệt độ HDD đang cao: %d\u00b0C!" % temp_val
                _push_alert("HDD_TEMP_HIGH", msg, "ERROR")
                with _alert_state_lock:
                    _alert_states["hdd_temp_alerted"] = True
            elif temp_val and not is_hot:
                with _alert_state_lock:
                    _alert_states["hdd_temp_alerted"] = False

            # --- 3. Ghi Lịch sử Metrics vào SQLite (mỗi 60s — dùng cho biểu đồ real-time) ---
            try:
                with _cache_lock:
                    snap = dict(_status_cache)
                cpu_pct = float(snap.get("cpu", "0%").replace("%", "").strip() or 0)
                ram_pct = float(snap.get("ram_percent", 0) or 0)
                cpu_raw = snap.get("cpu_temp", "0°C")
                hdd_raw = snap.get("temperature", "0°C")
                cpu_t = float(cpu_raw.replace("°C", "").strip()) if cpu_raw and "°C" in cpu_raw else 0.0
                hdd_t = float(hdd_raw.replace("°C", "").strip()) if hdd_raw and "°C" in hdd_raw else 0.0
                rx_str = snap.get("net_rx", "0 B/s")
                tx_str = snap.get("net_tx", "0 B/s")
                def _parse_speed_kbps(s):
                    try:
                        s = s.strip()
                        if "MB/s" in s: return float(s.replace("MB/s","").strip()) * 1024
                        if "KB/s" in s: return float(s.replace("KB/s","").strip())
                        if "GB/s" in s: return float(s.replace("GB/s","").strip()) * 1024 * 1024
                        return float(s.split()[0])
                    except Exception: return 0.0
                rx_kbps = _parse_speed_kbps(rx_str)
                tx_kbps = _parse_speed_kbps(tx_str)
                conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
                cur = conn.cursor()
                cur.execute("INSERT INTO system_metrics_history (cpu_percent, ram_percent, cpu_temp, hdd_temp, net_rx_kbps, net_tx_kbps) VALUES (?,?,?,?,?,?)",
                            (cpu_pct, ram_pct, cpu_t, hdd_t, rx_kbps, tx_kbps))
                # Tương thích Python 3.5: dùng date string thay vì f-string
                cur.execute("DELETE FROM system_metrics_history WHERE timestamp <= datetime('now', '-30 days')")
                # Giữ lại bảng nhiệt độ cũ để tương thích
                if cpu_t > 0 or hdd_t > 0:
                    cur.execute("INSERT INTO system_temperature_history (cpu_temp, hdd_temp) VALUES (?, ?)", (cpu_t, hdd_t))
                    cur.execute("DELETE FROM system_temperature_history WHERE timestamp <= datetime('now', '-30 days')")
                conn.commit()
                conn.close()
                _update_process_state(
                    "metrics_history",
                    db_path=DB_PATH,
                    retention_days=30,
                    last_insert_at=int(time.time()),
                    cpu_percent=round(cpu_pct, 1),
                    ram_percent=round(ram_pct, 1),
                    cpu_temp_c=cpu_t,
                    hdd_temp_c=hdd_t,
                    net_rx_kbps=round(rx_kbps, 1),
                    net_tx_kbps=round(tx_kbps, 1),
                )
            except Exception:
                pass

            # --- 4. Don dep Thung rac (mới 24h) ---
            check_interval += 1
            with _alert_state_lock:
                last_clean = _alert_states["trash_last_clean"]
            if (now_ts - last_clean) > 86400:  # 24h
                deleted = _clean_trash(WEBDAV_FILE_ROOT, max_age_days=30)
                if deleted > 0:
                    msg = "Tự động dọn dẹp: Đã xóa %d tệp trong Thùng rác (quá 30 ngày)." % deleted
                    _push_alert("TRASH_CLEANED", msg, "INFO")
                with _alert_state_lock:
                    _alert_states["trash_last_clean"] = now_ts
                _update_process_state(
                    "maintenance",
                    trash_last_clean=now_ts,
                    trash_deleted_last=int(deleted or 0),
                )

            # --- 4b. Don dep file tam app-managed + FLV hong cu (moi 24h) ---
            # F2: chi xoa tmp co nguon goc ro (__upload*, .restore-tmp.*) va vung
            # tmp backend (nas_meta_tmp). File 0-byte / thu muc rong cua user
            # KHONG bao gio bi xoa du ten co chua chuoi tam.
            with _alert_state_lock:
                last_empty = _alert_states["empty_last_clean"]
            if (now_ts - last_empty) > 86400:  # 24h
                try:
                    df, dd, db = _clean_empty_files_and_dirs(WEBDAV_FILE_ROOT)
                    dt = _clean_managed_tmp_root()
                    if df + dd + db + dt > 0:
                        msg = "Tự động dọn dẹp: Đã xóa %d tệp tạm, %d thư mục tạm (trong đó tmp backend: %d)." % (df, dd, dt)
                        if db > 0:
                            msg = msg + " FLV hỏng cũ: %d." % db
                        _push_alert("EMPTY_CLEANED", msg, "INFO")
                except Exception as e:
                    log.warning("[Cron] Lỗi dọn tệp rỗng/thư mục rỗng: %s", e)
                with _alert_state_lock:
                    _alert_states["empty_last_clean"] = now_ts
                _update_process_state(
                    "maintenance",
                    empty_last_clean=now_ts,
                    empty_files_deleted_last=int(df or 0) if "df" in locals() else 0,
                    empty_dirs_deleted_last=int(dd or 0) if "dd" in locals() else 0,
                    broken_flv_deleted_last=int(db or 0) if "db" in locals() else 0,
                )

            # --- 5. Quet phan loai ảnh nh? luc 3h sang ---
            with _alert_state_lock:
                last_ai = _alert_states["ai_last_scan"]
            is_3am = (now_dt.hour == 3 and now_dt.minute < 5)
            if is_3am and (now_ts - last_ai) > 82800:
                started = _scan_photos_lightweight()
                if started:
                    _push_alert("AI_SCAN_STARTED", "Smart Gallery: Đã phân loại ảnh theo thư mục.", "INFO")
                    _update_process_state("smart_gallery", ai_last_scan=now_ts, ai_scan_started=True)

            # --- 6. Tạo Báo Cáo Hàng Ngày lúc 6h sáng ---
            is_6am = (now_dt.hour == 6 and now_dt.minute < 2)
            if is_6am:
                yesterday = (now_dt - datetime.timedelta(days=1)).strftime("%Y-%m-%d")
                try:
                    conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
                    cur = conn.cursor()
                    cur.execute("SELECT COUNT(*) FROM daily_reports WHERE report_date = ?", (yesterday,))
                    already_done = cur.fetchone()[0] > 0
                    conn.close()
                except Exception:
                    already_done = False
                if not already_done:
                    _generate_daily_report(yesterday)

        except Exception:
            pass
        time.sleep(60)


def _generate_daily_report(report_date):
    """Tổng hợp số liệu 24h của report_date, lưu vào DB và push alert cho Android.
    report_date: chuỗi 'YYYY-MM-DD' của ngày cần tổng hợp.
    """
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        cur = conn.cursor()
        # Lấy dữ liệu metrics trong ngày
        cur.execute("""
            SELECT cpu_percent, ram_percent, cpu_temp, hdd_temp, net_rx_kbps, net_tx_kbps
            FROM system_metrics_history
            WHERE date(timestamp) = ?
        """, (report_date,))
        rows = cur.fetchall()

        # Lấy số lượng cảnh báo trong ngày
        cur.execute("""
            SELECT type, COUNT(*) as cnt FROM system_logs
            WHERE date(timestamp) = ? AND type IN ('ERROR','WARNING')
            GROUP BY type
        """, (report_date,))
        alert_counts = {r[0]: r[1] for r in cur.fetchall()}
        conn.close()

        if not rows:
            return  # Không có dữ liệu

        # Thống kê
        cpu_vals = [r[0] for r in rows if r[0] is not None and r[0] > 0]
        ram_vals = [r[1] for r in rows if r[1] is not None and r[1] > 0]
        cput_vals = [r[2] for r in rows if r[2] is not None and r[2] > 0]
        hddt_vals = [r[3] for r in rows if r[3] is not None and r[3] > 0]
        rx_vals   = [r[4] for r in rows if r[4] is not None]
        tx_vals   = [r[5] for r in rows if r[5] is not None]

        def _avg(lst): return round(sum(lst)/len(lst), 1) if lst else 0
        def _max(lst): return round(max(lst), 1) if lst else 0
        def _sum_mb(lst): return round(sum(lst) * 60 / 1024, 1) if lst else 0  # KB/s * 60s = KB/min → MB

        report = {
            "date": report_date,
            "samples": len(rows),
            "cpu": {"avg": _avg(cpu_vals), "peak": _max(cpu_vals)},
            "ram": {"avg": _avg(ram_vals), "peak": _max(ram_vals)},
            "cpu_temp": {"avg": _avg(cput_vals), "peak": _max(cput_vals)},
            "hdd_temp": {"avg": _avg(hddt_vals), "peak": _max(hddt_vals)},
            "network": {
                "total_download_mb": _sum_mb(rx_vals),
                "total_upload_mb": _sum_mb(tx_vals)
            },
            "alerts": {
                "errors": alert_counts.get("ERROR", 0),
                "warnings": alert_counts.get("WARNING", 0)
            },
            "health_score": _calc_health_score(
                _max(cput_vals), _max(hddt_vals),
                _max(cpu_vals), _max(ram_vals),
                alert_counts.get("ERROR", 0)
            )
        }

        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        cur = conn.cursor()
        cur.execute(
            "INSERT OR REPLACE INTO daily_reports (report_date, report_json) VALUES (?, ?)",
            (report_date, json.dumps(report, ensure_ascii=False))
        )
        conn.commit()
        conn.close()

        # Xây dựng message tóm tắt
        score = report["health_score"]
        score_icon = "🟢" if score >= 80 else ("🟡" if score >= 60 else "🔴")
        msg = (
            "Báo cáo %s %s Sức khỏe: %d%% | CPU đỉnh: %.0f%% | RAM đỉnh: %.0f%% | "
            "Nhiệt CPU max: %.0f°C | Tải về: %.0f MB | Lỗi: %d"
        ) % (
            report_date, score_icon, score,
            report["cpu"]["peak"], report["ram"]["peak"],
            report["cpu_temp"]["peak"],
            report["network"]["total_download_mb"],
            report["alerts"]["errors"]
        )
        _push_alert("DAILY_REPORT", msg, "INFO")
        log.info("[Report] Đã tạo báo cáo ngày %s — Điểm sức khỏe: %d%%", report_date, score)
    except Exception as e:
        log.error("[Report] Lỗi tạo báo cáo: %s", e)


def _calc_health_score(cpu_temp_peak, hdd_temp_peak, cpu_peak, ram_peak, error_count):
    """Tính điểm sức khỏe NAS từ 0-100. Cao là tốt."""
    score = 100
    # Trừ điểm theo nhiệt độ CPU (ngưỡng an toàn < 75°C)
    if cpu_temp_peak > 85: score -= 25
    elif cpu_temp_peak > 75: score -= 15
    elif cpu_temp_peak > 65: score -= 5
    # Trừ điểm theo nhiệt độ HDD (ngưỡng an toàn < 50°C với ST4000VX)
    if hdd_temp_peak > 60: score -= 20
    elif hdd_temp_peak > 50: score -= 10
    elif hdd_temp_peak > 45: score -= 5
    # Trừ điểm CPU & RAM
    if cpu_peak > 90: score -= 10
    if ram_peak > 90: score -= 10
    # Trừ điểm theo số lỗi
    if error_count > 10: score -= 20
    elif error_count > 3: score -= 10
    elif error_count > 0: score -= 5
    return max(0, min(100, score))


# ============ API ENDPOINTS ============

# ─── Biểu đồ giám sát real-time ────────────────────────────────────────────
@app.route("/api/metrics/history")
@requires_auth
def api_metrics_history():
    """Trả về lịch sử metrics cho biểu đồ Android.
    ?hours=N  — số giờ cần lấy (mặc định 1, tối đa 24).
    Resample về tối đa 120 điểm để giảm tải băng thông.
    """
    try:
        hours = min(int(request.args.get("hours", 1)), 24)
    except (ValueError, TypeError):
        hours = 1
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        cur = conn.cursor()
        # QUAN TR?NG: Khong dung % operator vi conflict voi %Y, %m... trong strftime SQLite
        # Tinh san WHERE time filter bang Python roi truyen vao query
        hours_filter = "-%d hours" % hours
        cur.execute(
            "SELECT strftime('%Y-%m-%dT%H:%M:%S', timestamp, 'localtime'),"
            " cpu_percent, ram_percent, cpu_temp, hdd_temp,"
            " net_rx_kbps, net_tx_kbps"
            " FROM system_metrics_history"
            " WHERE timestamp >= datetime('now', ?)"
            " ORDER BY timestamp ASC",
            (hours_filter,)
        )
        rows = cur.fetchall()
        conn.close()

    except Exception as e:
        return jsonify({"error": str(e)}), 500

    # Resample: nếu nhiều hơn 120 điểm thì lấy đều nhau
    if len(rows) > 120:
        step = len(rows) // 120
        rows = rows[::step]

    result = {
        "timestamps": [r[0] for r in rows],
        "cpu_percent": [round(r[1] or 0, 1) for r in rows],
        "ram_percent": [round(r[2] or 0, 1) for r in rows],
        "cpu_temp":    [round(r[3] or 0, 1) for r in rows],
        "hdd_temp":    [round(r[4] or 0, 1) for r in rows],
        "net_rx_kbps": [round(r[5] or 0, 1) for r in rows],
        "net_tx_kbps": [round(r[6] or 0, 1) for r in rows],
        "hours": hours,
        "count": len(rows)
    }
    return jsonify(result)


@app.route("/api/report/daily")
@requires_auth
def api_report_daily():
    """Lấy báo cáo ngày. ?date=YYYY-MM-DD (mặc định hôm qua)."""
    date_str = request.args.get("date", "")
    if not date_str:
        yesterday = datetime.datetime.now() - datetime.timedelta(days=1)
        date_str = yesterday.strftime("%Y-%m-%d")
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        cur = conn.cursor()
        cur.execute("SELECT report_json, generated_at FROM daily_reports WHERE report_date = ?", (date_str,))
        row = cur.fetchone()
        conn.close()
        if not row or not row[0]:
            _generate_daily_report(date_str)
            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
            cur = conn.cursor()
            cur.execute("SELECT report_json, generated_at FROM daily_reports WHERE report_date = ?", (date_str,))
            row = cur.fetchone()
            conn.close()
        if row and row[0]:
            report = json.loads(row[0])
            report["generated_at"] = row[1]
            return jsonify(report)
        else:
            return jsonify({"error": "Chưa có báo cáo cho ngày %s" % date_str, "date": date_str}), 404
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/report/generate", methods=["POST"])
@requires_auth
def api_report_generate():
    """Tạo báo cáo thủ công cho một ngày. Body: {"date": "YYYY-MM-DD"} hoặc để trống = hôm qua."""
    data = request.get_json(force=True, silent=True) or {}
    date_str = data.get("date", "")
    if not date_str:
        yesterday = datetime.datetime.now() - datetime.timedelta(days=1)
        date_str = yesterday.strftime("%Y-%m-%d")
    threading.Thread(target=_generate_daily_report, args=(date_str,), daemon=True).start()
    return jsonify({"result": "ok", "date": date_str, "message": "Đang tạo báo cáo ngầm..."})



@app.route("/api/status")
@requires_auth
def api_status():
    """Trạng thái h? thỏng - tr? v? cache tuc thi (kem cầnh b?o neu co)."""
    with _cache_lock:
        data = dict(_status_cache)
        
    global _system_alert
    try:
        if _system_alert:
            if data.get("status", "Online") == "Online":
                data["status"] = _system_alert.strip()
            else:
                data["status"] = data["status"] + " | " + _system_alert
    except NameError:
        pass

    try:
        data["rust_accelerated"] = bool(rust_bridge and rust_bridge.is_rust_accelerated())
        data["rust_indexer_accelerated"] = bool(rust_bridge and hasattr(rust_bridge, "is_indexer_accelerated") and rust_bridge.is_indexer_accelerated())
        data["rust_search_accelerated"] = bool(rust_bridge and hasattr(rust_bridge, "is_search_accelerated") and rust_bridge.is_search_accelerated())
    except Exception:
        data["rust_accelerated"] = False
        
    return jsonify(_with_version(data))


@app.route("/api/v1/status")
@requires_auth
def api_status_v1():
    """Alias versioned cua /api/status (B1)."""
    return api_status()


def _metric_float(raw, default=0.0):
    try:
        text = str(raw or "").replace("%", "").replace("\u00b0C", "").strip()
        if not text or text == "--":
            return default
        return float(text)
    except Exception:
        return default


def _speed_to_kbps(raw):
    try:
        text = str(raw or "0 B/s").strip()
        parts = text.split()
        if not parts:
            return 0.0
        value = float(parts[0])
        unit = parts[1] if len(parts) > 1 else "B/s"
        if unit.startswith("GB"):
            return value * 1024.0 * 1024.0
        if unit.startswith("MB"):
            return value * 1024.0
        if unit.startswith("KB"):
            return value
        return value / 1024.0
    except Exception:
        return 0.0


@app.route("/api/status/realtime")
@requires_auth
def api_status_realtime():
    """Diem metrics realtime nhe: chi doc cache nen khong cham SMART/disk/proc."""
    with _cache_lock:
        snap = dict(_status_cache)
    now = datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S")
    resp = jsonify({
        "timestamp": now,
        "cpu_percent": round(_metric_float(snap.get("cpu")), 1),
        "ram_percent": round(_metric_float(snap.get("ram_percent")), 1),
        "cpu_temp": round(_metric_float(snap.get("cpu_temp")), 1),
        "hdd_temp": round(_metric_float(snap.get("temperature")), 1),
        "net_rx_kbps": round(_speed_to_kbps(snap.get("net_rx")), 1),
        "net_tx_kbps": round(_speed_to_kbps(snap.get("net_tx")), 1),
        "fan_percent": snap.get("fan_percent"),
        "fan_rpm": snap.get("fan_rpm"),
        "status": snap.get("status", "Online")
    })
    resp.headers["Cache-Control"] = "public, max-age=3"
    return resp


@app.route("/api/storage/usage")
@requires_auth
def api_storage_usage():
    """Dung lượng cac thư mục lon. Chi Đọc metadata, gioi han thoi gian quet."""
    try:
        now = time.time()
        cached = _STORAGE_USAGE_CACHE.get("data")
        cached_ts = float(_STORAGE_USAGE_CACHE.get("ts", 0) or 0)
        if cached is not None and now - cached_ts < _STORAGE_USAGE_CACHE_TTL:
            return jsonify(cached)
        if cached is not None:
            if _STORAGE_USAGE_CACHE_LOCK.acquire(False):
                threading.Thread(target=_refresh_storage_usage_cache_locked, daemon=True, name="StorageUsageRefresh").start()
            return jsonify(cached)
        if not _STORAGE_USAGE_CACHE_LOCK.acquire(False):
            return jsonify(cached or {"ok": True, "root": WEBDAV_FILE_ROOT, "folders": []})
        try:
            data = _build_storage_usage_payload()
            _STORAGE_USAGE_CACHE["data"] = data
            _STORAGE_USAGE_CACHE["ts"] = now
            return jsonify(data)
        finally:
            _STORAGE_USAGE_CACHE_LOCK.release()
    except Exception as e:
        return jsonify({"ok": False, "error": "Không tải được dung lượng thư mục: %s" % normalize_vietnamese_message(str(e)), "folders": []}), 500


@app.route("/api/ping", methods=["GET", "HEAD"])
@requires_auth
def api_ping():
    """Lightweight authenticated ping endpoint for LAN/Tailscale latency checks."""
    if request.method == "HEAD":
        return ("", 204)
    return jsonify(_with_version({"ok": True, "ts": time.time()}))


@app.route("/api/v1/ping", methods=["GET", "HEAD"])
@requires_auth
def api_ping_v1():
    """Alias versioned của /api/ping (B1)."""
    return api_ping()


@app.route("/api/system/idle")
@requires_auth
def api_system_idle():
    """Tr? v? trạng thái 'rảnh' cua NAS de quyet dinh co nen chay tac vu n?ng
    (vi du quet trung lap) hay khong.

    Idle = TRUE khi:
      - CPU usage < 50%
      - Load average 1 phut < cores * 0.7
      - RAM free > 150 MB
      - Khong co livestream nao dang recording
      - Khong co backup/restore/torrent dang chay n?ng
    """
    try:
        # CC-004 P2-3: interval=None (non-blocking) thay vi interval=0.4 (block
        # request 400ms). Monitor nen da goi cpu_percent truoc do nen gia tri
        # co y nghia; lan dau sau boot co the 0.0 — chap nhan duoc cho idle-gate.
        cpu_pct = psutil.cpu_percent(interval=None)
    except Exception:
        cpu_pct = 0.0
    try:
        mem = psutil.virtual_memory()
        mem_free_mb = mem.available / (1024 * 1024)
        mem_pct = mem.percent
    except Exception:
        mem_free_mb = 9999
        mem_pct = 0.0
    try:
        cores = max(1, psutil.cpu_count(logical=True) or 1)
        load1 = os.getloadavg()[0]
    except Exception:
        cores = 1
        load1 = 0.0
    try:
        with _livestream_lock:
            recording_streams = sum(1 for j in _livestream_jobs.values() if j.get("status") == "recording")
    except Exception:
        recording_streams = 0
    try:
        with _ytdlp_lock:
            ytdlp_jobs = len(_ytdlp_jobs)
    except Exception:
        ytdlp_jobs = 0
    try:
        with _thumb_gate_lock:
            sync_jobs = 1 if "sync" in _thumb_auto_block_reasons else 0
    except Exception:
        sync_jobs = 0

    reasons = []
    if cpu_pct > 50: reasons.append("CPU %.0f%% > 50%%" % cpu_pct)
    if load1 > cores * 0.7: reasons.append("Load %.2f > %.2f" % (load1, cores * 0.7))
    if mem_free_mb < 150: reasons.append("RAM trong %.0f MB < 150 MB" % mem_free_mb)
    if recording_streams > 0: reasons.append("Có %d livestream đang ghi" % recording_streams)
    if ytdlp_jobs > 0: reasons.append("Có %d tác vụ yt-dlp đang tải" % ytdlp_jobs)
    if sync_jobs > 0: reasons.append("Đang đồng bộ từ điện thoại")

    is_idle = len(reasons) == 0
    return jsonify({
        "idle": is_idle,
        "reason": ", ".join(reasons) if reasons else "",
        "cpu_pct": round(cpu_pct, 1),
        "load_avg_1min": round(load1, 2),
        "cores": cores,
        "mem_free_mb": int(mem_free_mb),
        "mem_pct": round(mem_pct, 1),
        "recording_streams": recording_streams,
        "ytdlp_jobs": ytdlp_jobs,
        "sync_jobs": sync_jobs
    })


# CC-005 P1/P2: unify TTL cache tránh spin-up HDD.
#   _smart_cache           TTL 86400s (1 ngày — smartctl/OMV RPC đánh thức HDD)
#   _omv_overview_cache    TTL 900s   (15 phút — OMV RPC nặng)
#   _STORAGE_SUMMARY_CACHE TTL 300s   (5 phút — quét full disk, xem dưới)
# Quy tắc chung: cache HIT -> trả ngay, KHÔNG gọi smartctl/omv-rpc/quét disk;
# fetching flag chống stampede (request 2 khi đang fetch trả dữ liệu cũ).
_SMART_TTL = 86400.0  # CC-005 P1/P2: TTL smart (tránh spin-up HDD mỗi lần mở app)
_smart_cache = {"data": None, "time": 0.0, "fetching": False}
_smart_lock = threading.Lock()

@app.route("/api/disk/smart")
@requires_auth
def api_smart():
    """Thong tin S.M.A.R.T ổ cứng — uu tien l?y tu OMV, fallback smartctl."""
    global _smart_cache
    with _smart_lock:
        now = time.time()
        # CC-005 P1/P2: cache HIT -> trả ngay, KHÔNG gọi smartctl (tránh spin-up HDD)
        if now - _smart_cache["time"] < _SMART_TTL and _smart_cache["data"]:
            return jsonify(_smart_cache["data"])
        if _smart_cache["fetching"]:
            return jsonify(_smart_cache["data"] or {"status": "Đang tải...", "temperature": "--°C", "raw_log": ""})
        _smart_cache["fetching"] = True
    raw_log = ""
    status = "Unknown"
    temperature = "--\u00b0C"

    # === Phuong phap 1: L?y tu OMV RPC (chinh xac nhat) ===
    try:
        # Disabled here for the same reason as get_hdd_temp(): OMV Smart
        # enumerateDevices scans broken USB devices and makes the API timeout.
        omv_out = ""
        if omv_out and omv_out.strip():
            raw_parsed = json.loads(omv_out)
            # OMV co the tr? v? object {"1": {...}} hoac array [{...}]
            if isinstance(raw_parsed, dict):
                devices = list(raw_parsed.values())
            else:
                devices = raw_parsed
            dev = _select_target_omv_smart_device(devices)
            if dev:
                overall = dev.get("overallstatus", "")
                if overall.upper() == "GOOD":
                    status = "PASSED"
                elif overall.upper() == "BAD":
                    status = "FAILED"
                else:
                    status = overall if overall else "Unknown"
                # Nhi?t để tu OMV (co the la "31°C" hoac "31")
                temp_val = str(dev.get("temperature", "")).strip()
                if temp_val and temp_val != "0":
                    if "\u00b0" in temp_val:
                        temperature = temp_val
                    else:
                        temperature = "%s\u00b0C" % temp_val
                # Model + Serial de hien thi
                vendor = dev.get("vendor", "")
                model = dev.get("model", "")
                serial = dev.get("serialnumber", "")
                devname = dev.get("devicename", "")
                full_model = ("%s %s" % (vendor, model)).strip()
                raw_log = "Thiet bi: /dev/%s\nModel: %s\nSerial: %s\nTrạng thái OMV: %s\nNhiệt độ: %s" % (
                    devname, full_model, serial, overall, temperature
                )
                # L?y SMART attributes tu OMV
                try:
                    dev_file = dev.get("devicefile", "/dev/%s" % devname)
                    attr_params = json.dumps({"devicefile": dev_file, "type": ""})
                    attr_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "Smart", "getAttributes", attr_params], timeout=15)
                    if attr_out and attr_out.strip():
                        attrs_raw = json.loads(attr_out)
                        if isinstance(attrs_raw, dict):
                            attrs = list(attrs_raw.values())
                        else:
                            attrs = attrs_raw
                        raw_log += "\n\n=== S.M.A.R.T Attributes ===\n"
                        raw_log += "%-4s %-24s %-6s %-6s %-6s %s\n" % ("ID", "Attribute", "Value", "Worst", "Thresh", "Raw")
                        for a in attrs:
                            if isinstance(a, dict):
                                raw_log += "%-4s %-24s %-6s %-6s %-6s %s\n" % (
                                    a.get("id", ""), a.get("attrname", ""),
                                    a.get("value", ""), a.get("worst", ""),
                                    a.get("threshold", ""), a.get("rawvalue", "")
                                )
                except Exception:
                    pass
                live_temp = get_hdd_temp()
                if live_temp and live_temp != "--\u00b0C":
                    temperature = live_temp
                elif temperature == "--\u00b0C":
                    temperature = live_temp
                res_data = {
                    "status": status,
                    "temperature": temperature,
                    "raw_log": raw_log,
                    "device": dev.get("devicefile", "/dev/%s" % devname),
                    "model": full_model,
                    "serial": serial,
                    "target_disk": True
                }
                with _smart_lock:
                    _smart_cache["data"] = res_data
                    _smart_cache["time"] = time.time()
                    _smart_cache["fetching"] = False
                return jsonify(res_data)
            else:
                res_data = {"status": "Unknown", "temperature": "--\u00b0C", "raw_log": "Không tìm thấy ổ dữ liệu NAS Toshiba trong danh sách SMART OMV.", "target_disk": False}
                with _smart_lock:
                    _smart_cache["data"] = res_data
                    _smart_cache["time"] = time.time()
                    _smart_cache["fetching"] = False
                return jsonify(res_data)
    except Exception:
        pass

    # === Phuong phap 2: Fallback smartctl truc tiep ===
    has_real_hdd = False
    for disk_path in [_target_hdd_device_path()]:
        try:
            if not _validate_disk_path(disk_path):
                continue
            log_out = run_cmd(["sudo", "smartctl", "-a", disk_path, "-d", "sat"], merge_stderr=True)
            if not log_out or "open device" in log_out.lower():
                log_out = run_cmd(["sudo", "smartctl", "-a", disk_path], merge_stderr=True)
            if log_out and ("PASSED" in log_out or "FAILED" in log_out or "Temperature" in log_out):
                raw_log = log_out
                has_real_hdd = True
                break
        except Exception:
            continue

    if not has_real_hdd:
        raw_log = "Không tìm thấy ổ cứng HDD/SSD. Hệ thống đang chạy trên eMMC/SD."
        status = "eMMC Only"
    else:
        if "test result: PASSED" in raw_log:
            status = "PASSED"
        elif "test result: FAILED" in raw_log:
            status = "FAILED"
        else:
            status = "Unknown"

    try:
        temperature = get_hdd_temp()
    except Exception:
        pass

    res_data = {"status": status, "temperature": temperature, "raw_log": raw_log}
    with _smart_lock:
        _smart_cache["data"] = res_data
        _smart_cache["time"] = time.time()
        _smart_cache["fetching"] = False

    return jsonify(res_data)


_OMV_OVERVIEW_TTL = 900.0  # CC-005 P1/P2: TTL OMV overview (tránh RPC nặng mỗi lần mở app)
_omv_overview_cache = {"data": None, "time": 0.0, "fetching": False}
_omv_overview_lock = threading.Lock()

def _get_smb_runtime_state():
    smb_active = False
    try:
        status_out = subprocess.check_output(["systemctl", "is-active", "smbd"], stderr=subprocess.STDOUT).decode("utf-8").strip()
        smb_active = status_out == "active"
    except Exception:
        pass
    is_enabled = False
    try:
        with open("/etc/samba/smb.conf", "r") as f:
            is_enabled = "# --- BEGIN NASWEBDAV SMB ---" in f.read()
    except Exception:
        pass
    return {
        "enabled": bool(is_enabled),
        "active": bool(smb_active),
        "running": bool(smb_active),
        "effective_enabled": bool(is_enabled and smb_active)
    }

@app.route("/api/omv/overview")
@requires_auth
def api_omv_overview():
    """Tong hop thong tin tu OMV RPC: h? thỏng, dich vu, mang, filesystem, ổ cứng."""
    global _omv_overview_cache
    with _omv_overview_lock:
        now = time.time()
        # CC-005 P1/P2: cache HIT -> tra ngay, KHONG goi omv-rpc (tranh spin-up/HDD tai)
        if now - _omv_overview_cache["time"] < _OMV_OVERVIEW_TTL and _omv_overview_cache["data"]:
            return jsonify(_omv_overview_cache["data"])
        if _omv_overview_cache["fetching"]:
            return jsonify(_omv_overview_cache["data"] or {})
        _omv_overview_cache["fetching"] = True

    result = {}

    # 1. System Information (Hostname, Version, Kernel, Uptime, CPU, RAM, Load)
    try:
        sys_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "System", "getInformation", "{}"], timeout=15)
        if sys_out:
            sys_data = json.loads(sys_out)
            sys_info = {}
            for item in sys_data:
                name = item.get("name", "")
                val = item.get("value", "")
                if name == "Hostname": sys_info["hostname"] = val
                elif name == "Version": sys_info["omv_version"] = val
                elif name == "Kernel": sys_info["kernel"] = val
                elif name == "Uptime": sys_info["uptime"] = val
                elif name == "Load average": sys_info["load_average"] = val
                elif name == "CPU usage":
                    sys_info["cpu_usage_text"] = val.get("text", "") if isinstance(val, dict) else str(val)
                    sys_info["cpu_usage_percent"] = val.get("value", 0) if isinstance(val, dict) else 0
                elif name == "Memory usage":
                    sys_info["mem_usage_text"] = val.get("text", "") if isinstance(val, dict) else str(val)
                    sys_info["mem_usage_percent"] = val.get("value", 0) if isinstance(val, dict) else 0
            result["system"] = sys_info
    except Exception:
        result["system"] = {}

    # 2. Services Status (SSH, FTP, SMB, NFS, Rsync)
    try:
        svc_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "Services", "getStatus", "{}"], timeout=10)
        if svc_out:
            svc_data = json.loads(svc_out)
            services = []
            for s in svc_data.get("data", []):
                name = s.get("name", "")
                title = s.get("title", "")
                enabled = bool(s.get("enabled", False))
                running = bool(s.get("running", False))
                effective_enabled = bool(enabled and running)
                if name == "samba":
                    smb_state = _get_smb_runtime_state()
                    enabled = smb_state["enabled"]
                    running = smb_state["running"]
                    effective_enabled = smb_state["effective_enabled"]
                services.append({
                    "name": name,
                    "title": title,
                    "enabled": enabled,
                    "running": running,
                    "effective_enabled": effective_enabled
                })
            result["services"] = services
    except Exception:
        result["services"] = []

    # 3. Network Interfaces
    try:
        net_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "Network", "enumerateDevices", "{}"], timeout=10)
        if net_out:
            net_data = json.loads(net_out)
            interfaces = []
            for iface in net_data:
                if iface.get("type") == "loopback":
                    continue
                interfaces.append({
                    "name": iface.get("devicename", ""),
                    "address": iface.get("address", ""),
                    "netmask": iface.get("netmask", ""),
                    "gateway": iface.get("gateway", ""),
                    "mac": iface.get("ether", ""),
                    "state": iface.get("state", ""),
                    "speed": iface.get("speed", -1),
                    "mtu": iface.get("mtu", ""),
                    "wol": iface.get("wol", False)
                })
            result["network"] = interfaces
    except Exception:
        result["network"] = []

    # 4. Filesystems
    try:
        fs_out = ""
        if fs_out:
            fs_data = json.loads(fs_out)
            filesystems = []
            for fs in fs_data:
                # B? qua zram (log2ram) va phân vùng < 500MB
                size = int(fs.get("size", 0) or 0)
                if size < 500 * 1024 * 1024:
                    continue
                filesystems.append({
                    "device": fs.get("devicefile", ""),
                    "label": fs.get("label", ""),
                    "type": fs.get("type", ""),
                    "mountpoint": fs.get("mountpoint", ""),
                    "used": fs.get("used", ""),
                    "size_bytes": size,
                    "percentage": fs.get("percentage", 0),
                    "description": fs.get("description", "")
                })
            result["filesystems"] = filesystems
    except Exception:
        result["filesystems"] = []

    # 5. Disk Devices
    try:
        disk_out = ""
        if disk_out:
            disk_data = json.loads(disk_out)
            disks = []
            for d in disk_data:
                vendor = d.get("vendor", "")
                model = d.get("model", "")
                devicefile = d.get("devicefile", "")
                is_target = _is_target_hdd_omv_device(d)
                disks.append({
                    "name": d.get("devicename", ""),
                    "device": devicefile,
                    "model": ("%s %s" % (vendor, model)).strip(),
                    "serial": d.get("serialnumber", ""),
                    "size": d.get("size", "0"),
                    "description": d.get("description", ""),
                    "is_root": d.get("isroot", False),
                    "is_target_hdd": is_target,
                    "is_usb_import": (not is_target and str(devicefile).startswith("/dev/sdb"))
                })
            result["disks"] = disks
    except Exception:
        result["disks"] = []

    if not result.get("filesystems"):
        try:
            mp = _target_hdd_mountpoint()
            st = os.statvfs(mp)
            size = int(st.f_blocks * st.f_frsize)
            free = int(st.f_bavail * st.f_frsize)
            used = max(0, size - free)
            result["filesystems"] = [{
                "device": _target_hdd_device_path(),
                "label": "data",
                "type": "",
                "mountpoint": mp,
                "used": used,
                "size_bytes": size,
                "percentage": round((used * 100.0 / size), 1) if size else 0,
                "description": "HDD chinh NAS N300"
            }]
        except Exception:
            result["filesystems"] = []
    if not result.get("disks"):
        try:
            dev = _target_hdd_device_path()
            name = os.path.basename(dev) if dev else ""
            # Dùng lsblk để lấy dung lượng (bytes) và serial chính xác
            model, serial, size_bytes = "", "", "0"
            if name:
                try:
                    lsblk_out = run_cmd(["lsblk", "-J", "-b", "-d", "-o", "NAME,SIZE,SERIAL,MODEL", dev], timeout=5)
                    if lsblk_out:
                        lsblk_data = json.loads(lsblk_out)
                        bdev = lsblk_data.get("blockdevices", [])[0]
                        model = str(bdev.get("model") or "").strip()
                        serial = str(bdev.get("serial") or "").strip()
                        size_bytes = str(bdev.get("size") or "0")
                except Exception:
                    # Fallback cuối cùng dùng sysfs (size sysfs trả về số blocks 512-byte)
                    model = _read_first_existing_text(("/sys/block/%s/device/model" % name,))
                    serial = _read_first_existing_text(("/sys/block/%s/device/serial" % name, "/sys/block/%s/device/wwid" % name))
                    size_blocks = _read_first_existing_text(("/sys/block/%s/size" % name,))
                    if size_blocks.isdigit():
                        size_bytes = str(int(size_blocks) * 512)

            result["disks"] = [{
                "name": name,
                "device": dev,
                "model": model,
                "serial": serial,
                "size": size_bytes,
                "description": "HDD chinh NAS N300",
                "is_root": False,
                "is_target_hdd": bool(dev),
                "is_usb_import": False
            }]
        except Exception:
            result["disks"] = []

    # 6. Shared Folders
    try:
        share_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "ShareMgmt", "enumerateSharedFolders", "{}"], timeout=10)
        if share_out:
            share_data = json.loads(share_out)
            shares = []
            for s in share_data:
                shares.append({
                    "name": s.get("name", ""),
                    "description": s.get("description", ""),
                    "device": s.get("device", ""),
                    "path": s.get("reldirpath", "")
                })
            result["shared_folders"] = shares
    except Exception:
        result["shared_folders"] = []

    # 7. Power Management
    try:
        pwr_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "PowerMgmt", "get", "{}"], timeout=5)
        if pwr_out:
            result["power"] = json.loads(pwr_out)
    except Exception:
        result["power"] = {}

    with _omv_overview_lock:
        _omv_overview_cache["data"] = result
        _omv_overview_cache["time"] = time.time()
        _omv_overview_cache["fetching"] = False

    return jsonify(result)

_processes_cache = {"data": None, "time": 0.0}
_processes_lock = threading.Lock()

def _is_kernel_or_system_proc(proc):
    """Kiểm tra tiến trình có thuộc Kernel OS hoặc Daemon hệ thống hay không (Python 3.5)."""
    try:
        pid = getattr(proc, 'pid', 0)
        if pid <= 300 or pid == os.getpid():
            return True
        try:
            if proc.ppid() <= 2:
                return True
        except Exception:
            return True
        exe_exists = False
        try:
            exe_target = os.readlink("/proc/%d/exe" % pid)
            if exe_target:
                exe_exists = True
        except Exception:
            exe_exists = False
        if not exe_exists:
            return True
        name = (proc.name() or "").lower()
        if any(name.startswith(sys_name) for sys_name in _EXCLUDED_SYSTEM_PROCS):
            return True
        return False
    except Exception:
        return True


@app.route("/api/processes", methods=["GET"])
@requires_auth
def api_processes():
    """Lấy danh sách tiến trình ứng dụng + Hệ thống OS với toán học khớp 100% với Dashboard Status."""
    try:
        sort_by = request.args.get("sort", "cpu")
        try:
            limit = int(request.args.get("limit", 100))
        except Exception:
            limit = 100
        limit = max(1, min(limit, 200))
        num_cores = psutil.cpu_count() or 1
        
        # 1. Lấy dữ liệu live từ _status_cache để đồng bộ 100% với biểu đồ vòng tròn trên Dashboard
        with _cache_lock:
            cached_status = dict(_status_cache)
        
        total_sys_cpu = _metric_float(cached_status.get("cpu", "0.0"))
        if total_sys_cpu <= 0.0:
            total_sys_cpu = round(psutil.cpu_percent(interval=None), 1)

        total_sys_ram = _metric_float(cached_status.get("ram_percent", "0.0"))
        if total_sys_ram <= 0.0:
            mem = psutil.virtual_memory()
            actual_used = mem.total - getattr(mem, 'available', mem.free)
            total_sys_ram = round((actual_used / float(mem.total)) * 100.0, 1)

        active_procs = []
        for p in psutil.process_iter():
            try:
                if _is_kernel_or_system_proc(p):
                    continue
                p.cpu_percent()
                active_procs.append(p)
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
                
        time.sleep(0.1)
        
        user_procs = []
        for p in active_procs:
            try:
                info = p.as_dict(attrs=['pid', 'name', 'username', 'status', 'memory_percent'])
                pid = info.get('pid', 0)
                name = info.get('name', 'unknown')
                cpu = round(p.cpu_percent() / num_cores, 1)
                mem_pct = round(info.get('memory_percent', 0.0) or 0.0, 1)
                st = str(info.get('status', 'running'))
                user_procs.append({
                    "pid": pid,
                    "name": name,
                    "user": info.get('username', 'root') or "root",
                    "status": st,
                    "cpu": cpu,
                    "mem": mem_pct
                })
            except (psutil.NoSuchProcess, psutil.AccessDenied, KeyError):
                continue

        # 2. Tính tổng số % đã hiển thị của user procs
        user_cpu_sum = round(sum(p["cpu"] for p in user_procs), 1)
        user_mem_sum = round(sum(p["mem"] for p in user_procs), 1)

        if user_cpu_sum > total_sys_cpu:
            total_sys_cpu = user_cpu_sum

        if user_mem_sum > total_sys_ram:
            total_sys_ram = user_mem_sum

        # 3. Tính % Hệ thống (System OS) = Dashboard total - User procs sum
        sys_cpu = max(0.0, round(total_sys_cpu - user_cpu_sum, 1))
        sys_mem = max(0.0, round(total_sys_ram - user_mem_sum, 1))

        # 4. Tạo hàng đại diện duy nhất cho Hệ thống (System OS)
        sys_entry = {
            "pid": -1,
            "name": "Hệ thống (System OS)",
            "user": "root",
            "status": "running",
            "cpu": sys_cpu,
            "mem": sys_mem,
            "is_system": True
        }

        all_procs = [sys_entry] + user_procs

        if sort_by == "mem":
            all_procs.sort(key=lambda x: x["mem"], reverse=True)
        else:
            all_procs.sort(key=lambda x: x["cpu"], reverse=True)
            
        return jsonify({
            "status": "success",
            "total_cpu": "%.1f%%" % total_sys_cpu,
            "total_ram": "%.1f%%" % total_sys_ram,
            "data": all_procs[:limit]
        })
    except Exception as e:
        log.error("Lỗi API danh sách tiến trình: %s", e)
        return jsonify({"error": str(e)}), 500

@app.route("/api/processes/kill", methods=["POST"])
@requires_admin
def api_process_kill():
    """Kill process by PID"""
    try:
        data = request.get_json(silent=True) or {}
        pid = data.get("pid")
        if not pid:
            return jsonify({"error": "Missing pid"}), 400

        try:
            pid_int = int(pid)
        except (ValueError, TypeError):
            return jsonify({"error": "PID không hợp lệ"}), 400

        if pid_int <= 100 or pid_int == os.getpid() or pid_int == os.getppid():
            return jsonify({"error": "Tiến trình hệ thống không thể kill"}), 403

        pname = _get_process_name(pid_int)
        # CC-005 P1: mo rong blocklist (ffmpeg/yt-dlp/transcode worker bi kill
        # -> DoS livestream/ytdlp/thumb, mat du lieu dang ghi) + doi sang
        # requires_admin o decorator.
        _kill_blocked = ("nginx", "sshd", "systemd", "init", "bash", "sh",
                         "ffmpeg", "ffprobe", "yt-dlp", "yt_dlp", "postgres",
                         "mysqld", "dockerd", "qbittorrent", "omv-engined")
        if pname in _kill_blocked or "nas_api" in (pname or "") or \
                "transcode" in (pname or ""):
            return jsonify({"error": "Tiến trình hệ thống được bảo vệ: %s" % (pname or str(pid_int))}), 403

        import signal
        os.kill(pid_int, signal.SIGKILL)
        return jsonify({"status": "success", "message": "Đã kill tiến trình %d" % pid_int})
    except ProcessLookupError:
        return jsonify({"error": "Tiến trình không tồn tại"}), 404
    except PermissionError:
        return jsonify({"error": "Không có quyền kill tiến trình này"}), 403
    except Exception as e:
        log.error("Lỗi API kill tiến trình: %s", e)
        return jsonify({"error": str(e)}), 500
@app.route("/api/smb/status", methods=["GET"])
@requires_auth
def api_smb_status():
    try:
        smb_state = _get_smb_runtime_state()
        return jsonify({
            "status": "success",
            "enabled": smb_state["enabled"],
            "active": smb_state["active"],
            "effective_enabled": smb_state["effective_enabled"],
            "share": "NAS_Data",
            "user": "daica"
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/backup/scan_broken", methods=["POST"])
@requires_auth
def api_scan_broken_mp4():
    """Quét MP4 trong AutoBackup, phát hiện file thiếu moov atom, di chuyển vào quarantine.

    POST body (optional):
        {"scan_dir": "/srv/.../AutoBackup/DCIM/Screen recordings", "dry_run": true}

    Returns:
        {"quarantined": [...], "total_scanned": N, "broken_count": K}
    """
    try:
        import subprocess as _sp

        data = request.get_json(silent=True) or {}
        dry_run = data.get("dry_run", False)

        # Base paths to scan — all DCIM subdirs under AutoBackup
        auto_backup_root = "/srv/dev-disk-by-label-data/New folder/AutoBackup"
        scan_dirs = []

        # CC-005 P0: custom_dir phai nam trong AutoBackup (realpath
        # containment) — truoc day chi check isdir nen attacker quet /etc//root
        # gay CPU/ffprobe DoS + rename (mat) moi .mp4 ngoai pham vi.
        custom_dir = (data.get("scan_dir") or "").strip()
        if custom_dir:
            try:
                _ab_root = os.path.realpath(auto_backup_root)
                _cd_real = os.path.realpath(custom_dir)
                if os.path.isdir(_cd_real) and (
                        _cd_real == _ab_root or
                        _cd_real.startswith(_ab_root + os.sep)):
                    scan_dirs.append(_cd_real)
            except Exception:
                pass
        if not scan_dirs:
            # Auto-discover all DCIM subdirs (Python 3.5.3 safe: os.listdir instead of os.scandir)
            dcim_dir = os.path.join(auto_backup_root, "DCIM")
            if os.path.isdir(dcim_dir):
                for _entry_name in os.listdir(dcim_dir):
                    if _entry_name.startswith("."):
                        continue
                    _entry_path = os.path.join(dcim_dir, _entry_name)
                    if os.path.isdir(_entry_path):
                        scan_dirs.append(_entry_path)

        quarantined = []
        changed_dirs = set()
        total_scanned = 0

        for scan_dir in scan_dirs:
            if not os.path.isdir(scan_dir):
                continue

            # Quarantine folder sits inside the same parent directory
            quarantine_name = "_broken_quarantine_{0}".format(time.strftime('%Y%m%d'))
            quarantine_dir = os.path.join(os.path.dirname(scan_dir), quarantine_name)

            for root, _dirs, files in os.walk(scan_dir):
                # Skip quarantine dirs themselves
                if "_broken_quarantine" in root:
                    continue
                for fname in files:
                    if not fname.lower().endswith(".mp4"):
                        continue
                    total_scanned += 1
                    fpath = os.path.join(root, fname)

                    # Fast path: Rust MP4 atom parser (<0.1ms, khong fork ffprobe cho file lanh).
                    # File nghi ngo hong van de ffprobe xac nhan truoc khi cach ly (0 false positive).
                    _mp4_bridge = globals().get("rust_bridge")
                    if _mp4_bridge and hasattr(_mp4_bridge, "parse_mp4_header"):
                        try:
                            _mp4_info = _mp4_bridge.parse_mp4_header(fpath)
                            if _mp4_info and _mp4_info.get("valid") and _mp4_info.get("has_moov"):
                                continue
                        except Exception:
                            pass

                    # ffprobe: check for moov atom (Python 3.5 compatible: no capture_output)
                    try:
                        result = _sp.run(
                            ["ffprobe", "-v", "error",
                             "-show_entries", "format=format_name",
                             "-of", "csv=p=0", fpath],
                            stdout=_sp.PIPE, stderr=_sp.PIPE, timeout=30, check=False
                        )
                        format_name = (result.stdout or b"").decode("utf-8", errors="ignore").strip()
                        if result.returncode == 0 and format_name:
                            # moov atom present — file OK
                            continue
                    except Exception as _probe_err:
                        # ffprobe could not execute or timed out — do NOT quarantine healthy files!
                        log.warning("[BrokenScan] Skip %s (ffprobe error: %s)", fpath, _probe_err)
                        continue

                    # File is broken — quarantine
                    rel_path = os.path.relpath(fpath, scan_dir)
                    dest = os.path.join(quarantine_dir, rel_path)
                    dest_dir = os.path.dirname(dest)

                    if dry_run:
                        quarantined.append({
                            "path": rel_path,
                            "size": os.path.getsize(fpath),
                            "action": "would_quarantine"
                        })
                    else:
                        try:
                            os.makedirs(dest_dir, exist_ok=True)
                            if os.path.exists(dest):
                                dest = dest + ".{0}".format(int(time.time()))
                            os.rename(fpath, dest)
                            changed_dirs.update((os.path.dirname(fpath), os.path.dirname(dest)))
                            quarantined.append({
                                "path": rel_path,
                                "size": os.path.getsize(dest) if os.path.exists(dest) else 0,
                                "action": "quarantined",
                                "dest": os.path.relpath(dest, scan_dir)
                            })
                        except Exception as e:
                            quarantined.append({
                                "path": rel_path,
                                "action": "error",
                                "error": str(e)
                            })

        _index_mutated_directories(changed_dirs)
        log.info("[BackupScan] scanned=%d broken=%d dry_run=%s", total_scanned, len(quarantined), dry_run)
        return jsonify({
            "status": "success",
            "total_scanned": total_scanned,
            "broken_count": len(quarantined),
            "quarantined": quarantined,
            "dry_run": dry_run
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


def _telegram_send(bot_token: str, chat_id: str, message: str) -> dict:
    """Gửi tin nhắn qua Telegram Bot API.

    Args:
        bot_token: Token từ @BotFather (đã được validate bởi caller).
        chat_id:   Chat/Group ID nhận tin.
        message:   Nội dung (tối đa 4096 ký tự — caller nên truncate trước).

    Returns:
        dict với key "message_id" và "chat_id" nếu thành công.

    Raises:
        ValueError: Telegram từ chối (bad token/chat_id, permission denied…).
        RuntimeError: Network/HTTP lỗi.
    """
    url = "https://api.telegram.org/bot{token}/sendMessage".format(token=bot_token)
    payload = json.dumps({
        "chat_id": chat_id,
        "text": message,
        "parse_mode": "HTML",
    }).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        ctx = ssl.create_default_context() if ssl is not None else None
        with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
            body = json.loads(resp.read().decode("utf-8", errors="ignore") or "{}")
    except urllib.error.HTTPError as e:
        err_body = ""
        try:
            err_body = e.read().decode("utf-8", errors="ignore")[:300]
        except Exception:
            pass
        raise RuntimeError("Telegram trả về HTTP %d: %s" % (e.code, err_body or e.reason))
    except urllib.error.URLError as e:
        raise RuntimeError("Không kết nối được Telegram: %s" % e.reason)

    if not body.get("ok"):
        raise ValueError("Telegram từ chối: %s" % (body.get("description") or "unknown"))

    result = body.get("result") or {}
    return {"message_id": result.get("message_id"), "chat_id": chat_id}


@app.route("/api/telegram/test", methods=["POST"])
@requires_auth
def api_telegram_test():
    """Gửi tin nhắn test qua Telegram Bot API để kiểm tra cấu hình.

    POST body:
        {
            "bot_token": "123456:ABC...",   # Telegram bot token
            "chat_id": "123456789",         # Chat ID nhận tin
            "message": "Test từ NASWebDAV"  # Optional
        }

    Returns:
        {"status": "success", "message_id": N} | {"error": "..."}
    """
    try:
        data = request.get_json() or {}
        bot_token = (data.get("bot_token") or "").strip()
        chat_id = (data.get("chat_id") or "").strip()
        message = (data.get("message") or "🔔 Test từ NASWebDAV — server đã nhận cấu hình Telegram.").strip()

        if not bot_token:
            return jsonify({"error": "bot_token rỗng — vui lòng nhập token từ @BotFather"}), 400
        if not chat_id:
            return jsonify({"error": "chat_id rỗng — vui lòng nhập chat ID từ @userinfobot"}), 400
        # Telegram token format: <bot_id>:<44-char hex/alnum>
        if not _re_module.match(r'^\d+:[A-Za-z0-9_-]{35,}$', bot_token):
            return jsonify({"error": "bot_token sai định dạng (phải có dạng 123456:ABCDEF...)"}), 400
        if len(message) > 4096:
            message = message[:4096]

        try:
            result = _telegram_send(bot_token, chat_id, message)
        except ValueError as e:
            return jsonify({"error": str(e)}), 502
        except RuntimeError as e:
            return jsonify({"error": str(e)}), 502

        log.info("[TelegramTest] chat_id=%s message_id=%s", chat_id, result.get("message_id"))
        return jsonify({"status": "success", **result})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/smb/toggle", methods=["POST"])
@requires_admin
def api_smb_toggle():
    try:
        data = request.get_json() or {}
        enable = data.get("enable", False)
        
        conf_path = "/etc/samba/smb.conf"
        try:
            with open(conf_path, "r") as f:
                content = f.read()
        except Exception:
            content = ""
            
        marker_start = "# --- BEGIN NASWEBDAV SMB ---"
        marker_end = "# --- END NASWEBDAV SMB ---"
        
        if marker_start in content and marker_end in content:
            before = content.split(marker_start)[0]
            after = content.split(marker_end)[1]
            content = before + after
            
        if enable:
            block = "\n{0}\n[NAS_Data]\n   path = /srv/dev-disk-by-label-data\n   read only = no\n   guest ok = no\n   valid users = daica\n   force user = root\n   force group = root\n{1}\n".format(marker_start, marker_end)
            content = content.rstrip() + block
            
        with open(conf_path, "w") as f:
            f.write(content)
            
        import subprocess
        subprocess.run(["systemctl", "restart", "smbd"], check=False)
        if enable:
            subprocess.run(["systemctl", "enable", "smbd"], check=False)
        else:
            subprocess.run(["systemctl", "disable", "smbd"], check=False)
            subprocess.run(["systemctl", "stop", "smbd"], check=False)
        with _omv_overview_lock:
            _omv_overview_cache["time"] = 0.0
            _omv_overview_cache["data"] = None
            
        smb_active = False
        try:
            status_out = subprocess.check_output(["systemctl", "is-active", "smbd"], stderr=subprocess.STDOUT).decode("utf-8").strip()
            smb_active = status_out == "active"
        except Exception:
            pass
        return jsonify({"status": "success", "enabled": enable, "active": smb_active, "effective_enabled": bool(enable and smb_active)})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/service/toggle", methods=["POST"])
@requires_admin
def api_service_toggle():
    try:
        data = request.get_json(force=True, silent=True) or {}
        name = str(data.get("name", "")).strip().lower()
        enable = bool(data.get("enable", False))
        service_map = {
            "ftp": "proftpd",
            "nfs": "nfs-kernel-server",
            "rsyncd": "rsync",
            "ssh": "ssh",
        }
        if name == "samba":
            with app.test_request_context(
                "/api/smb/toggle",
                method="POST",
                data=json.dumps({"enable": enable}),
                content_type="application/json",
            ):
                return api_smb_toggle.__wrapped__()
        unit = service_map.get(name)
        if not unit:
            return jsonify({"error": "Dịch vụ không hợp lệ"}), 400
        if enable:
            subprocess.run(["systemctl", "enable", unit], check=False)
            subprocess.run(["systemctl", "start", unit], check=False)
        else:
            subprocess.run(["systemctl", "disable", unit], check=False)
            subprocess.run(["systemctl", "stop", unit], check=False)
        actual_enabled = False
        actual_running = False
        try:
            _res_en = subprocess.run(["systemctl", "is-enabled", unit], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            actual_enabled = (_res_en.stdout or b"").decode("utf-8", errors="ignore").strip() == "enabled"
        except Exception:
            pass
        try:
            _res_act = subprocess.run(["systemctl", "is-active", unit], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            actual_running = (_res_act.stdout or b"").decode("utf-8", errors="ignore").strip() == "active"
        except Exception:
            pass
        with _omv_overview_lock:
            _omv_overview_cache["time"] = 0.0
            _omv_overview_cache["data"] = None
        return jsonify({
            "status": "success",
            "name": name,
            "enabled": actual_enabled,
            "running": actual_running,
            "effective_enabled": bool(actual_enabled and actual_running)
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# CC-005 P1: rate-limit speedtest (module-level)
_SPEEDTEST_LAST = {}
_SPEEDTEST_LOCK = threading.Lock()
_SPEEDTEST_MIN_INTERVAL = 300.0

@app.route("/api/disk/speedtest", methods=["POST"])
@requires_auth
def api_speedtest():
    """Do toc do doc/ghi bang pure Python, an toan va Đọc lap voi he dieu hảnh."""
    try:
        import time, os
        # CC-005 P1: rate-limit 5 phut/IP (ghi 50MB moi POST day HDD + spin-up)
        try:
            _cli = _request_client_ip() or 'unknown'
        except Exception:
            _cli = 'unknown'
        with _SPEEDTEST_LOCK:
            _last = float(_SPEEDTEST_LAST.get(_cli, 0.0) or 0.0)
            if time.time() - _last < _SPEEDTEST_MIN_INTERVAL:
                return jsonify({'write_speed': 'Rate-limited', 'read_speed': 'Rate-limited',
                                'retry_after': int(_SPEEDTEST_MIN_INTERVAL - (time.time() - _last))}), 429
            _SPEEDTEST_LAST[_cli] = time.time()
        # Tim duong dan ổ cứng that de do (Uu tien /srv/dev-disk vi /sharedfolders hay bi lỗi IO Errno 5 tren OMV)
        test_file = SPEED_TEST_FILE
        best_total = 0
        for p in psutil.disk_partitions(all=False):
            try:
                u = psutil.disk_usage(p.mountpoint)
                # Uu tien thư mục srv/dev-disk cua OMV
                if u.total > best_total and ("/srv/dev-disk" in p.mountpoint or "/mnt/" in p.mountpoint):
                    best_total = u.total
                    test_file = os.path.join(p.mountpoint, "nas_speed_test.bin")
            except (PermissionError, OSError):
                continue
        # Neu khong tim được srv, th? fallback
        if best_total == 0:
            if os.path.exists("/srv/dev-disk-by-label-data"):
                test_file = "/srv/dev-disk-by-label-data/nas_speed_test.bin"
            elif os.path.exists("/sharedfolders/Data"):
                test_file = "/sharedfolders/Data/nas_speed_test.bin"
            
        test_size_mb = 20  # CC-005 P1: 50->20MB (do day HDD yeu)
        chunk = b'\x00' * (1024 * 1024) # 1MB chunk
        
        # 1. Do toc do GHI (Pure Python)
        start_write = time.time()
        try:
            with open(test_file, "wb") as f:
                for _ in range(test_size_mb):
                    f.write(chunk)
                f.flush()
                os.fsync(f.fileno()) # Ép dữ liệu ghi thẳng xuống đĩa vật lý
            write_time = time.time() - start_write
            write_speed = "%.1f MB/s" % (test_size_mb / write_time) if write_time > 0 else "0 MB/s"
        except Exception as we:
            write_speed = "Lỗi ghi: " + str(we)

        # 2. Do toc do DOC (Pure Python)
        start_read = time.time()
        try:
            read_bytes = 0
            with open(test_file, "rb") as f:
                while True:
                    data = f.read(1024 * 1024)
                    if not data:
                        break
                    read_bytes += len(data)
            read_time = time.time() - start_read
            read_mb = read_bytes / (1024 * 1024)
            read_speed = "%.1f MB/s" % (read_mb / read_time) if read_time > 0 else "0 MB/s"
        except Exception as re:
            read_speed = "Lỗi đọc: " + str(re)

        # 3. Don dep
        try:
            os.remove(test_file)
        except Exception:
            pass

        return jsonify({
            "write_speed": write_speed,
            "read_speed": read_speed
        })
    except Exception as e:
        return jsonify({"write_speed": "Lỗi hệ thống", "read_speed": str(e)})
        
# ============ QUAN LY NGUON ============

def _enable_wake_on_lan_before_sleep():
    """Best-effort: keep NIC armed for the next Wake-on-LAN boot."""
    ethtool_bin = shutil.which("ethtool") or "/sbin/ethtool"
    try:
        net_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "Network", "enumerateDevices", "{}"], timeout=5)
        interfaces = json.loads(net_out) if net_out else []
    except Exception:
        interfaces = []

    candidates = []
    for iface in interfaces:
        name = iface.get("devicename", "")
        mac = iface.get("ether", "")
        if name and name != "lo" and mac and mac != "00:00:00:00:00:00":
            candidates.append(name)
    if not candidates:
        candidates = [name for name in os.listdir("/sys/class/net") if name != "lo"]

    enabled = []
    for name in candidates:
        try:
            subprocess.run([ethtool_bin, "-s", name, "wol", "g"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3)
            wakeup_path = "/sys/class/net/{}/device/power/wakeup".format(name)
            if os.path.exists(wakeup_path):
                with open(wakeup_path, "w", encoding="utf-8") as fh:
                    fh.write("enabled\n")
            enabled.append(name)
        except Exception:
            continue
    return enabled

def _supported_sleep_states():
    try:
        with open("/sys/power/state", "r", encoding="utf-8") as fh:
            return fh.read().strip().split()
    except Exception:
        return []

def _run_system_suspend():
    try:
        _enable_wake_on_lan_before_sleep()
        subprocess.run(["sync"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        subprocess.run(["systemctl", "suspend"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    except Exception as exc:
        logging.warning("Suspend failed: %s", exc)

def _schedule_suspend_response(legacy_endpoint=None):
    states = _supported_sleep_states()
    if states and not any(state in states for state in ("mem", "freeze")):
        return jsonify({
            "result": "error",
            "message": "Suspend is not supported by this kernel",
            "power_states": states
        }), 501

    wol_interfaces = _enable_wake_on_lan_before_sleep()
    threading.Timer(2.0, _run_system_suspend).start()
    payload = {
        "result": "ok",
        "mode": "suspend",
        "wol_interfaces": wol_interfaces,
        "power_states": states
    }
    if legacy_endpoint:
        payload["legacy_endpoint"] = legacy_endpoint
    return jsonify(payload)

@app.route("/api/power/reboot", methods=["POST"])
@requires_admin
def api_reboot():
    threading.Timer(2.0, lambda: subprocess.run(["reboot"])).start()
    return jsonify({"result": "ok"})

@app.route("/api/power/suspend", methods=["POST"])
@requires_admin
def api_suspend():
    return _schedule_suspend_response()

@app.route("/api/power/shutdown", methods=["POST"])
@requires_admin
def api_shutdown():
    return _schedule_suspend_response(legacy_endpoint="shutdown")


@app.route("/api/auth/authorize", methods=["POST"])
@app.route("/api/v1/auth/authorize", methods=["POST"])
@requires_auth
def api_auth_authorize():
    """Xac thuc va cap phep IP ket noi (Android handshake)."""
    ip = _request_client_ip()
    if not ip or not _validate_ip(ip):
        return api_fail("Khong xac dinh duoc client IP sau trusted proxy",
                        code="no_client_ip", status=400)

    # 1. Mo khoa firewall (iptables) lap tuc cho IP nay (Bypass moi rule chan WebDAV LAN)
    try:
        subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
        subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', ip, '-j', 'ACCEPT'], check=True)
        subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'DROP'], stderr=subprocess.DEVNULL)
    except Exception as e:
        log.warning("Loi mo khoa iptables cho IP %s: %s", ip, e)

    # 2. Them vao lan_whitelist de ben vung
    if ip not in _lan_whitelist:
        _lan_whitelist.add(ip)
        _save_lan_whitelist()
        try:
            subprocess.run(["sudo", "systemctl", "reload", "nginx"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        except Exception as e:
            log.warning("Reload Nginx that bai: %s", e)

    # 3. Ghi log vao Database
    conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
    try:
        cur = conn.cursor()
        _remember_authorized_ip(ip)
        cur.execute('INSERT OR REPLACE INTO authorized_ips VALUES (?, ?)', (ip, datetime.datetime.now()))
        cur.execute('DELETE FROM banned_ips WHERE ip=?', (ip,))
        cur.execute('DELETE FROM auth_attempts WHERE ip=?', (ip,))
        conn.commit()
    finally:
        if conn is not None:
            conn.close()

    # B3: envelope mới cho route v1, shape cũ cho legacy (app cũ parse trực tiếp).
    if request.path.startswith("/api/v1/"):
        return api_ok({"status": "Trusted", "ip": ip})
    return jsonify({"status": "Trusted", "result": "ok"})

# ============ DOCKER ============

@app.route("/api/docker/containers")
@requires_auth
def api_docker_list():
    try:
        output = run_cmd(['/usr/bin/docker', 'ps', '-a', '--format', '{{.ID}}|{{.Names}}|{{.State}}'])
        containers = []
        for line in output.strip().split("\n"):
            if "|" in line:
                parts = line.split("|")
                if len(parts) >= 3:
                    containers.append({
                        "id": parts[0],
                        "name": parts[1],
                        "status": parts[2]
                    })
        return jsonify(containers)
    except Exception:
        return jsonify([])


@app.route("/api/docker/control", methods=["POST"])
@requires_admin
def api_docker_control():
    try:
        data = request.get_json(force=True) or {}
        action = data.get("action", "")
        container = data.get("container", "")
        if action in ("start", "stop", "restart") and container:
            if not _validate_container_name(container):
                return jsonify({"error": "Tên container không hợp lệ"}), 400
            run_cmd(["/usr/bin/docker", action, container], timeout=30)
            return jsonify({"result": "ok"})
        return jsonify({"error": "Hành động không hợp lệ"}), 400
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ============ FAN CONTROL ============

FAN_SETTINGS_FILE = "/opt/fan_custom.json"
def _load_fan_settings():
    try:
        if os.path.exists(FAN_SETTINGS_FILE):
            with open(FAN_SETTINGS_FILE, "r") as f:
                return json.load(f)
    except Exception as e: log.debug("[M4] Ignored exception: %s", e)
    return {"mode": "auto", "on_temp": FAN_DEFAULT_ON_TEMP, "off_temp": FAN_DEFAULT_OFF_TEMP}

def _save_fan_settings(settings):
    try:
        with open(FAN_SETTINGS_FILE, "w") as f:
            json.dump(settings, f)
    except Exception as e: log.debug("[M4] Ignored exception: %s", e)


# ============================================================================
# Fan PWM low-level helpers (FIX: nut Tat trong app phai cat hen 5V chu khong
# chi set duty=0 — kernel PWM peripheral khi enable=1 + duty=0 van co the giu
# transistor o trạng thái khong xac dinh tuy phan cung Chainedbox).
# ============================================================================
PWM_PATH = "/sys/class/pwm/pwmchip0/pwm0"
FAN_POWER_GPIO = "79"
FAN_DEFAULT_ON_TEMP = 42.0
FAN_DEFAULT_OFF_TEMP = 40.0
FAN_CPU_FORCE_ON_TEMP = 75.0
FAN_HDD_FORCE_ON_TEMP = 60.0
FAN_MAX_RPM = 4300


def _pwm_write(node, value):
    """Ghi gia tri vao 1 sysfs node PWM. Im lang neu không tồn tại."""
    path = os.path.join(PWM_PATH, node)
    try:
        if not os.path.exists(path):
            return False
        # Write sysfs directly; avoid invoking a shell for fixed hardware paths.
        with open(path, "w") as f:
            f.write(str(value))
        return True
    except Exception as e:
        log.warning("[Fan] PWM write %s=%s lỗi: %s", node, value, e)
        return False


def _pwm_export_if_needed():
    """Mot so kernel can echo 0 > pwmchip0/export truoc khi /pwm0 ton tai."""
    try:
        if not os.path.isdir(PWM_PATH):
            export_path = "/sys/class/pwm/pwmchip0/export"
            if os.path.exists(export_path):
                with open(export_path, "w") as f:
                    f.write("0")
    except Exception:
        pass


def _fan_power_set(enabled):
    """Dieu khien chan enable nguon quat cua Chainedbox."""
    gpio_dir = "/sys/class/gpio/gpio%s" % FAN_POWER_GPIO
    try:
        if not os.path.isdir(gpio_dir) and os.path.exists("/sys/class/gpio/export"):
            with open("/sys/class/gpio/export", "w") as f:
                f.write(FAN_POWER_GPIO)
        direction = os.path.join(gpio_dir, "direction")
        if os.path.exists(direction):
            with open(direction, "w") as f:
                f.write("high" if enabled else "low")
        value = os.path.join(gpio_dir, "value")
        if os.path.exists(value):
            with open(value, "w") as f:
                f.write("1" if enabled else "0")
    except Exception as e:
        log.warning("[Fan] GPIO%s set %s lỗi: %s", FAN_POWER_GPIO, enabled, e)


def _fan_temp_value(raw):
    try:
        return float(_re_module.sub(r"[^0-9.\-]", "", str(raw).strip()) or "0")
    except Exception:
        return 0.0


def _fan_pwm_level(percent):
    try:
        value = int(round(float(percent)))
    except Exception:
        value = 0
    if value <= 10:
        return 0
    if value <= 25:
        return 25
    if value <= 50:
        return 50
    if value <= 75:
        return 75
    return 100


def _fan_pwm_duty(percent):
    return int(max(0, min(100, int(percent))) * 100)


def _fan_rpm_for_percent(percent):
    level = _fan_pwm_level(percent)
    if level == 0:
        return 0
    return int(round(FAN_MAX_RPM * level / 100.0))


def _fan_status_for_percent(percent):
    level = _fan_pwm_level(percent)
    rpm = _fan_rpm_for_percent(percent)  # BUG FIX: truyen percent, khong phai level (da bi convert)
    if level == 0:
        return "Dừng"
    return "Đang chạy %d%% - Tốc độ: %d rpm" % (level, rpm)


def _pwm_apply_level(percent):
    """Bật/tắt PWM an toàn theo đúng thứ tự Kernel RK3328: export -> period -> duty -> enable -> GPIO power."""
    try:
        pct = max(0, min(100, int(percent)))
    except Exception:
        pct = 0

    if pct <= 0:
        _pwm_apply_off()
        return

    _fan_power_set(True)
    _pwm_export_if_needed()

    period = 10000
    duty = _fan_pwm_duty(pct)

    try:
        with open(os.path.join(PWM_PATH, "period")) as f:
            cur_period = int(f.read().strip() or "0")
    except Exception:
        cur_period = 0

    if cur_period < duty or cur_period != period:
        _pwm_write("period", period)

    _pwm_write("duty_cycle", duty)
    _pwm_write("enable", 1)


def _pwm_apply_off():
    """Tắt hoàn toàn PWM: duty=0 trước, enable=0 sau để pin về LOW và peripheral ngưng output."""
    _pwm_export_if_needed()
    _pwm_write("duty_cycle", 0)
    _pwm_write("enable", 0)
    _fan_power_set(False)


def _pwm_apply_on(duty=10000, period=10000):
    """Bật 100% PWM."""
    _pwm_apply_level(100)


# ============================================================================
# PHOTO TIMELINE — Group ảnh theo Year/Month/Day cho UI Google-Photos-style
# ============================================================================
# Endpoint nh? — chi liet ke path + mtime, KHONG mo tung file de Đọc EXIF
# (trảnh stress disk). Client tu group theo mtime client-side.
# ============================================================================
# Sau khi giải phóng cấu trúc lớn (list 385K+ ảnh), glibc thường GIỮ pages trong
# arena thay vì trả về OS -> RSS không giảm trên NAS RAM ~1GB. gc.collect() thu
# hồi vòng tham chiếu, malloc_trim(0) ép glibc trả heap rảnh về kernel. ctypes là
# stdlib (không thêm dependency). Best-effort: lỗi thì bỏ qua.
_libc_for_trim = None
def _release_memory_to_os():
    global _libc_for_trim
    try:
        gc.collect()
    except Exception:
        pass
    try:
        if _libc_for_trim is None:
            _libc_for_trim = ctypes.CDLL("libc.so.6")
        _libc_for_trim.malloc_trim(0)
    except Exception:
        pass


@app.route('/api/media/recent', methods=['GET'])
@requires_auth
def api_recent_media():
    kind = request.args.get("kind")
    if kind not in ("image", "video"):
        return jsonify({"error": "Expected image or video"}), 400
    scope = request.args.get("root", "")
    if scope and ("\\" in scope or any(p in ("", ".", "..") for p in scope.split("/"))):
        return jsonify({"error": "Invalid media root"}), 400
    extensions = _IMAGE_EXTS if kind == "image" else _VIDEO_EXTS
    where = "is_dir=0 AND (" + " OR ".join(["lower(name) LIKE ?"] * len(extensions)) + ")"
    params = ["%" + ext for ext in sorted(extensions)]
    if scope:
        where += " AND path>=? AND path<?"
        params.extend([scope + "/", scope + "0"])
    root = _get_webdav_public_dir()
    if not root:
        return jsonify({"error": "catalogue_root_unavailable"}), 503
    items = []
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        conn.execute("BEGIN")
        state = conn.execute("SELECT root,ready,scanned_at FROM index_state WHERE id=1").fetchone()
        same_root = state and state[0] == os.path.realpath(root)
        ready = bool(same_root and state[1] and time.time() - state[2] <= FILE_INDEX_FRESHNESS_SECONDS)
        if same_root:
            _media_bridge = globals().get("rust_bridge")
            _rust_items = None
            if _media_bridge and hasattr(_media_bridge, "query_recent_media"):
                _rust_items = _media_bridge.query_recent_media(INDEX_DB_PATH, kind, scope, 100)
            if _rust_items is not None:
                items = _rust_items
            else:
                rows = conn.execute("SELECT path,name,mtime,size,mime FROM file_index WHERE " + where +
                                    " ORDER BY mtime DESC,path LIMIT 100", params)
                items = [{"path": "/webdav/" + path, "name": name, "mtime": mtime, "size": size, "mime": mime}
                         for path, name, mtime, size, mime in rows]
    finally:
        if conn is not None:
            conn.close()
    if not ready:
        _refresh_file_index_for_search()
    return jsonify({"source": "file_index", "index_ready": ready, "items": items})


@app.route('/api/photos/timeline', methods=['GET'])
@requires_auth
def api_photos_timeline():
    """List ảnh trong WEBDAV_FILE_ROOT, sort by mtime desc, group-ready.
    Query params:
      - month: "YYYY-MM" -> chi tra ảnh trong thang do
      - limit: max items (default 500, max 2000)
      - offset: pagination
    """
    month_filter = request.args.get("month", "").strip()
    try:
        limit = int(request.args.get("limit", "500"))
    except Exception:
        limit = 500
    limit = max(1, min(2000, limit))
    try:
        offset = int(request.args.get("offset", "0"))
    except Exception:
        offset = 0

    predicate = "is_dir=0 AND (" + " OR ".join(["lower(name) LIKE ?"] * len(_IMAGE_EXTS)) + ")"
    params = ["%" + ext for ext in sorted(_IMAGE_EXTS)]
    start_ms, end_ms = -1, -1
    if month_filter:
        try:
            start = datetime.datetime.strptime(month_filter, "%Y-%m")
            end = datetime.datetime(start.year + (start.month == 12), start.month % 12 + 1, 1)
            start_ms = int(time.mktime(start.timetuple())) * 1000
            end_ms = int(time.mktime(end.timetuple())) * 1000
            params.extend([start_ms, end_ms])
            predicate += " AND mtime>=? AND mtime<?"
        except (ValueError, OverflowError, OSError):
            return jsonify({"error": "Invalid month (YYYY-MM)"}), 400

    total, items, age = 0, [], None
    refresh = True
    root = _get_webdav_public_dir()
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        conn.execute("BEGIN")
        state = conn.execute("SELECT root,ready,scanned_at FROM index_state WHERE id=1").fetchone()
        if root and state and state[0] == os.path.realpath(root):
            age = int(max(0, time.time() - state[2])) if state[2] else None
            refresh = not state[1] or age is None or age >= 300
            _tl_bridge = globals().get("rust_bridge")
            _rust_tl = None
            if _tl_bridge and hasattr(_tl_bridge, "query_photos_timeline"):
                _rust_tl = _tl_bridge.query_photos_timeline(INDEX_DB_PATH, start_ms, end_ms, limit, offset)
            if _rust_tl is not None:
                total = _rust_tl["total"]
                items = _rust_tl["items"]
            else:
                total = conn.execute("SELECT COUNT(*) FROM file_index WHERE " + predicate, params).fetchone()[0]
                rows = conn.execute("SELECT path,mtime,size FROM file_index WHERE " + predicate +
                                    " ORDER BY mtime DESC,path LIMIT ? OFFSET ?", params + [limit, offset])
                items = [{"path": path, "mtime": mtime // 1000, "size": size} for path, mtime, size in rows]
    finally:
        if conn is not None:
            conn.close()
    if refresh and root:
        _refresh_file_index_for_search()
    return jsonify({"total": total, "offset": offset, "limit": limit, "items": items,
                    "filter_month": month_filter or None, "cache_age_seconds": age,
                    "scanning": bool(refresh and root), "error": None if root else "catalogue_root_unavailable"})


@app.route('/api/media/exif', methods=['GET'])
@requires_auth
def api_media_exif():
    """Doc sieu du lieu EXIF (ngay chup, camera, GPS, orientation) cua 1 file anh.
    Query: ?path=/webdav/path/to/image.jpg
    """
    raw_p = (request.args.get("path") or "").strip()
    if not raw_p or ".." in raw_p or "\\" in raw_p:
        return jsonify({"error": "Invalid path"}), 400
    clean_p = raw_p[len("/webdav/"):] if raw_p.startswith("/webdav/") else raw_p
    full = _resolve_webdav_request_path(clean_p)
    if not full or not os.path.isfile(full):
        return jsonify({"error": "File not found"}), 404

    bridge = globals().get("rust_bridge")
    exif = None
    if bridge and hasattr(bridge, "read_image_exif"):
        exif = bridge.read_image_exif(full)

    return jsonify({
        "ok": True,
        "path": "/webdav/" + clean_p,
        "exif": exif
    })


@app.route('/api/media/exif/batch', methods=['POST'])
@requires_auth
def api_media_exif_batch():
    """Doc EXIF hang loat da luong bang Rust cho mot danh sach anh.
    Body JSON: {"paths": ["/webdav/1.jpg", "/webdav/2.jpg", ...]} (toi da 200 items)
    """
    body = request.get_json(force=True, silent=True) or {}
    paths = body.get("paths", [])
    if not isinstance(paths, list) or len(paths) > 200:
        return jsonify({"error": "paths phai la list <= 200"}), 400

    full_map = {}
    for p in paths:
        if not isinstance(p, str) or ".." in p or "\\" in p:
            continue
        clean_p = p[len("/webdav/"):] if p.startswith("/webdav/") else p
        full = _resolve_webdav_request_path(clean_p)
        if full and os.path.isfile(full):
            full_map[full] = "/webdav/" + clean_p

    results = {}
    if full_map:
        bridge = globals().get("rust_bridge")
        batch_exif = {}
        if bridge and hasattr(bridge, "read_batch_exif"):
            batch_exif = bridge.read_batch_exif(list(full_map.keys()), workers=4)
        else:
            for full in full_map:
                if bridge and hasattr(bridge, "read_image_exif"):
                    batch_exif[full] = bridge.read_image_exif(full)

        for full, exif_data in batch_exif.items():
            web_p = full_map.get(full)
            if web_p:
                results[web_p] = exif_data

    return jsonify({
        "ok": True,
        "total": len(results),
        "results": results
    })



# ============================================================================
# DISK HEALTH MONITOR — Theo doi suc khoe HDD truoc khi qua muon
# ============================================================================
# Sample SMART + dmesg + io stats mới 5 ph?t, ghi append vao .jsonl tren eMMC.
# Auto-alert qua system_logs khi vuot threshold. UI app Đọc /api/disk/health
# (snapshot hien tai) hoac /api/disk/health/history?days=N (time series).
# ============================================================================
_DMESG_WINDOW_SEC = 1800  # Doc log kernel 30 phut gan nhat khi SMART scan chay theo lich.
_DISK_HEALTH_RETENTION_DAYS = 30
_DISK_HEALTH_HEALTHY_INTERVAL_SEC = 30 * 86400
_DISK_HEALTH_WARNING_INTERVAL_SEC = 7 * 86400
_DISK_HEALTH_CRITICAL_INTERVAL_SEC = 24 * 3600
_disk_health_last_sample = {}   # giu sample gần nh?t trong RAM cho /api/disk/health
_disk_health_lock = threading.Lock()
_SQLITE_STATE_TABLES = set(["hardware_status", "scheduler_state", "runtime_state"])


def _db_set_json(table, key, payload):
    if table not in _SQLITE_STATE_TABLES:
        log.warning("[SQLiteState] Bang khong hop le: %s", table)
        return False
    conn = None
    try:
        now = int(time.time())
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
        cur = conn.cursor()
        cur.execute(
            "INSERT OR REPLACE INTO %s (key, payload_json, updated_at) VALUES (?, ?, ?)" % table,
            (key, json.dumps(payload, ensure_ascii=False), now)
        )
        conn.commit()
        return True
    except Exception as e:
        log.warning("[SQLiteState] Không ghi được %s/%s: %s", table, key, e)
        return False
    finally:
        try:
            if conn is not None:
                conn.close()
        except Exception:
            pass


def _db_get_json(table, key, default=None):
    if table not in _SQLITE_STATE_TABLES:
        log.warning("[SQLiteState] Bang khong hop le: %s", table)
        return default
    conn = None
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
        cur = conn.cursor()
        cur.execute("SELECT payload_json FROM %s WHERE key=?" % table, (key,))
        row = cur.fetchone()
        if row and row[0]:
            return json.loads(row[0])
    except Exception:
        pass
    finally:
        try:
            if conn is not None:
                conn.close()
        except Exception:
            pass
    return default


def _disk_health_interval_for_sample(sample):
    score = int((sample or {}).get("score", 0) or 0)
    warnings = (sample or {}).get("warnings") or []
    if score < 70 or (sample or {}).get("smart_status") == "FAILED":
        return _DISK_HEALTH_CRITICAL_INTERVAL_SEC
    if score < 90 or warnings:
        return _DISK_HEALTH_WARNING_INTERVAL_SEC
    return _DISK_HEALTH_HEALTHY_INTERVAL_SEC


def _disk_health_scheduler_state(sample=None):
    state = _db_get_json("scheduler_state", "disk_health", {}) or {}
    if sample:
        interval = _disk_health_interval_for_sample(sample)
        state.update({
            "last_scan_at": int(sample.get("ts") or time.time()),
            "next_scan_at": int(sample.get("ts") or time.time()) + interval,
            "interval_sec": interval,
            "score": int(sample.get("score", 0) or 0),
            "smart_status": sample.get("smart_status", "Unknown"),
        })
        _db_set_json("scheduler_state", "disk_health", state)
    return state


def _read_dmesg_recent(seconds=300):
    """Dem so EXT4 error va SATA reset trong dmesg trong N giay gần đầy."""
    try:
        out = safe_run_cmd(["dmesg", "--time-format=raw"], timeout=8)
        if not out:
            # Không c? --time-format raw -> fallback parse [seconds] o dau dong
            out = safe_run_cmd(["dmesg"], timeout=8)
    except Exception:
        return {"ext4_errors": 0, "sata_resets": 0, "io_errors": 0}
    ext4 = 0
    sata = 0
    ioerr = 0
    # Đọc tu duoi len, dem den khi vuot ngoai window
    try:
        with open("/proc/uptime") as f:
            uptime_sec = float(f.read().split()[0])
    except Exception:
        uptime_sec = 0.0
    threshold_sec = uptime_sec - seconds
    for line in out.splitlines():
        # Format: "[ 12345.678] kernel: EXT4-fs error..."
        m = _re_module.match(r"\[\s*(\d+)\.\d+\]", line)
        if m:
            ts = int(m.group(1))
            if ts < threshold_sec:
                continue
        low = line.lower()
        if "ext4-fs error" in low or "ext4-fs warning" in low:
            ext4 += 1
        if "ata" in low and ("reset" in low or "link is slow" in low or "exception" in low):
            sata += 1
        if (
            "i/o error" in low
            or "critical target error" in low
            or "critical medium error" in low
            or "uas_eh_device_reset_handler" in low
            or ("usb" in low and "reset" in low)
        ):
            ioerr += 1
    return {"ext4_errors": ext4, "sata_resets": sata, "io_errors": ioerr}


def _read_io_stats(devname="sda"):
    """Đọc /sys/class/block/<dev>/stat: io wait time, sectors r/w."""
    try:
        with open("/sys/class/block/%s/stat" % devname) as f:
            fields = f.read().split()
        # Fields: 0:reads_completed 1:reads_merged 2:sectors_read 3:read_time
        #         4:writes_completed 5:writes_merged 6:sectors_written 7:write_time
        #         8:ios_in_progress 9:io_time 10:weighted_io_time
        if len(fields) >= 11:
            return {
                "reads_completed": int(fields[0]),
                "sectors_read": int(fields[2]),
                "writes_completed": int(fields[4]),
                "sectors_written": int(fields[6]),
                "ios_in_progress": int(fields[8]),
                "io_time_ms": int(fields[9]),
                "weighted_io_ms": int(fields[10]),
            }
    except Exception:
        pass
    return {}


def _parse_smart_attributes():
    """L?y 3 metric quan trong tu smartctl: Reallocated, Pending, UDMA CRC, temp."""
    result = {
        "smart_status": "Unknown",
        "device": _target_hdd_device_path(),
        "temp_c": None,
        "reallocated_sectors": None,
        "pending_sectors": None,
        "udma_crc_err": None,
        "power_on_hours": None,
    }
    dev = _target_hdd_device_path()
    if not dev or not os.path.exists(dev):
        return result
    # FIX: safe_run_cmd block '-H' flag (security whitelist). Goi subprocess
    # truc tiep voi danh sách args co dinh (khong co user input) -> an toan.
    out = ""
    for cmd_attempt in (["sudo", "smartctl", "-A", "-H", dev],
                        ["smartctl", "-A", "-H", dev]):
        try:
            r = subprocess.run(cmd_attempt, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
            if r.stdout:
                out = r.stdout.decode("utf-8", errors="ignore")
                if "SMART" in out or "Attribute" in out:
                    break
        except Exception:
            continue
    if not out:
        return result
    try:
        for line in out.splitlines():
            low = line.lower()
            if "smart overall-health" in low or "smart health status" in low:
                if "passed" in low or "ok" in low:
                    result["smart_status"] = "PASSED"
                elif "failed" in low:
                    result["smart_status"] = "FAILED"
            # Attribute lines layout:
            # ID# ATTRIBUTE_NAME    FLAG    VALUE  WORST  THRESH  TYPE     UPDATED  WHEN_FAILED  RAW_VALUE  [extra fields]
            # parts[0]=ID  parts[1]=name  ...  parts[9]=RAW_VALUE
            # FIX: dung parts[9] (raw value column) thay vi parts[-1] (sai cho line temp co "(Min/Max 30/34)")
            parts = line.split()
            if len(parts) < 10: continue
            try:
                attr_id = int(parts[0])
            except Exception:
                continue
            raw = parts[9]
            try:
                if attr_id == 5:     result["reallocated_sectors"] = int(raw)
                elif attr_id == 197: result["pending_sectors"] = int(raw)
                elif attr_id == 198: result["offline_uncorrectable"] = int(raw)
                elif attr_id == 199: result["udma_crc_err"] = int(raw)
                elif attr_id == 194 or attr_id == 190: result["temp_c"] = int(raw)
                elif attr_id == 9:   result["power_on_hours"] = int(raw)
                elif attr_id == 188: result["command_timeout"] = int(raw)
            except Exception:
                pass
    except Exception as e:
        log.warning("[DiskHealth] smartctl lỗi: %s", e)
    return result


def _compute_health_score(sample):
    """Tinh diem suc khoe 0-100 + nhan cầnh b?o."""
    score = 100
    warnings = []
    if sample.get("smart_status") == "FAILED":
        score -= 50; warnings.append("Tự kiểm tra ổ cứng báo lỗi nghiêm trọng, ổ cứng có dấu hiệu hỏng")
    elif sample.get("smart_status") not in ("PASSED",):
        score -= 5
    realloc = sample.get("reallocated_sectors") or 0
    if realloc > 0:
        score -= min(20, realloc)
        warnings.append("%d vùng dữ liệu đã được ổ cứng thay thế bằng vùng dự phòng" % realloc)
    pending = sample.get("pending_sectors") or 0
    if pending > 0:
        score -= min(30, pending * 2)
        warnings.append("%d vùng dữ liệu đang chờ xử lý — dấu hiệu ổ cứng sắp hỏng" % pending)
    offline_unc = sample.get("offline_uncorrectable") or 0
    if offline_unc > 0:
        score -= min(20, offline_unc * 2)
        warnings.append("%d vùng dữ liệu không thể sửa khi ổ cứng tự quét nền" % offline_unc)
    cmd_to = sample.get("command_timeout") or 0
    if cmd_to > 10000:
        score -= 10
        warnings.append("%d lần ổ cứng phản hồi quá hạn — kết nối SATA không ổn định" % cmd_to)
    crc = sample.get("udma_crc_err") or 0
    if crc > 0:
        score -= min(10, crc)
        warnings.append("%d lỗi truyền dữ liệu qua cáp SATA, cần kiểm tra cáp hoặc cổng kết nối" % crc)
    temp = sample.get("temp_c") or 0
    if temp > 60:
        score -= 20; warnings.append("Nhiệt độ %d°C quá nóng" % temp)
    elif temp > 50:
        score -= 8; warnings.append("Nhiệt độ %d°C cao" % temp)
    ext4 = sample.get("ext4_errors_recent") or 0
    if ext4 > 0:
        score -= min(30, ext4 * 5)
        warnings.append("%d lỗi EXT4-fs trong 5 phút gần đây" % ext4)
    sata = sample.get("sata_resets_recent") or 0
    if sata > 0:
        score -= min(40, sata * 15)
        warnings.append("%d lần kết nối SATA bị đặt lại hoặc phát sinh lỗi trong 5 phút — có thể do cáp hoặc nguồn không ổn định" % sata)
    ioerr = sample.get("io_errors_recent") or 0
    if ioerr > 0:
        score -= min(30, ioerr * 10)
        warnings.append("%d lỗi I/O trong 5 phút" % ioerr)
    return max(0, score), warnings


def _disk_health_sample_once():
    """Sample 1 lan, append vao .jsonl, update RAM cache, alert neu can."""
    global _disk_health_last_sample
    try:
        smart = _parse_smart_attributes()
        dmesg = _read_dmesg_recent(seconds=_DMESG_WINDOW_SEC)
        target_devname = _target_hdd_devname()
        io = _read_io_stats(target_devname)
        sample = {
            "ts": int(time.time()),
            "datetime": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "device": "/dev/%s" % target_devname,
        }
        sample.update(smart)
        sample["ext4_errors_recent"] = dmesg.get("ext4_errors", 0)
        sample["sata_resets_recent"] = dmesg.get("sata_resets", 0)
        sample["io_errors_recent"] = dmesg.get("io_errors", 0)
        sample["io_stats"] = io
        score, warnings = _compute_health_score(sample)
        normalized_warnings = [normalize_vietnamese_message(w) for w in warnings]
        sample["score"] = score
        sample["warnings"] = normalized_warnings


        with _disk_health_lock:
            _disk_health_last_sample = sample
        _update_process_state(
            "disk_health",
            current=sample,
            score=score,
            warnings=normalized_warnings,
            dmesg_window_sec=_DMESG_WINDOW_SEC,
        )

        # Ghi vao SQLite chung. JSONL cu chi giu fallback doc lich su cu.
        try:
            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
            cur = conn.cursor()
            cur.execute(
                "INSERT INTO disk_health_history (ts, device, score, smart_status, temp_c, payload_json) VALUES (?, ?, ?, ?, ?, ?)",
                (
                    int(sample.get("ts") or time.time()),
                    sample.get("device", ""),
                    int(sample.get("score", 0) or 0),
                    sample.get("smart_status", "Unknown"),
                    sample.get("temp_c"),
                    json.dumps(sample, ensure_ascii=False)
                )
            )
            conn.commit()
            conn.close()
            _db_set_json("hardware_status", "disk_health_current", sample)
            _disk_health_scheduler_state(sample)
        except Exception as e:
            log.warning("[DiskHealth] Không ghi SQLite history: %s", e)

        # Alert qua system_logs neu score xuong duoi nguong hoac co warning critical
        if score < 60:
            try:
                _add_system_log("WARNING", "DiskHealth",
                    "Điểm sức khỏe HDD: %d/100. Cảnh báo: %s" % (score, "; ".join(normalized_warnings[:3])))
            except Exception:
                pass
        if dmesg.get("sata_resets", 0) > 0 or dmesg.get("io_errors", 0) > 0:
            try:
                _add_system_log("ERROR", "DiskHealth",
                    "Phát hiện lỗi kết nối ổ cứng: %d lần đặt lại SATA, %d lỗi đọc/ghi dữ liệu trong 5 phút. Kiểm tra cáp SATA và nguồn ngay." % (
                        dmesg.get("sata_resets", 0), dmesg.get("io_errors", 0)))
            except Exception:
                pass
    except Exception as e:
        log.error("[DiskHealth] Sample lỗi: %s", e)


def _disk_health_prune_old_records():
    """Xoa cac mau disk health cu hon retention trong SQLite."""
    try:
        cutoff = int(time.time()) - (_DISK_HEALTH_RETENTION_DAYS * 86400)
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        cur = conn.cursor()
        cur.execute("DELETE FROM disk_health_history WHERE ts <= ?", (cutoff,))
        conn.commit()
        conn.close()
    except Exception as e:
        log.warning("[DiskHealth] Prune lỗi: %s", e)


def _disk_health_watchdog():
    """Background daemon: SMART scan theo lich thong minh, khong quet lien tuc."""
    global _disk_health_last_sample
    time.sleep(30)  # cho server on dinh
    while True:
        try:
            now_ts = int(time.time())
            with _disk_health_lock:
                has_ram_sample = bool(_disk_health_last_sample)
            if not has_ram_sample:
                saved = _db_get_json("hardware_status", "disk_health_current", {}) or {}
                if saved:
                    with _disk_health_lock:
                        _disk_health_last_sample = saved
            state = _disk_health_scheduler_state()
            next_scan_at = int(state.get("next_scan_at") or 0)
            if next_scan_at <= 0 or now_ts >= next_scan_at:
                _disk_health_sample_once()
                _disk_health_prune_old_records()
                state = _disk_health_scheduler_state()
                next_scan_at = int(state.get("next_scan_at") or 0)
            sleep_for = max(3600, min(21600, int((next_scan_at or now_ts + 3600) - now_ts)))
            time.sleep(sleep_for)
        except Exception as e:
            log.warning("[DiskHealth] Watchdog lỗi: %s", e)
            time.sleep(3600)


def _add_system_log(level, module, message, timestamp=None):
    """Helper: th?m log vao bang system_logs neu DB available."""
    try:
        message = normalize_vietnamese_message(sanitize_log_input(message))
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_3", 3.0))
        cur = conn.cursor()
        if timestamp is None:
            cur.execute(
                "INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                (level, module, message)
            )
        else:
            if isinstance(timestamp, (int, float)):
                timestamp = datetime.datetime.utcfromtimestamp(float(timestamp)).strftime("%Y-%m-%d %H:%M:%S")
            cur.execute(
                "INSERT INTO system_logs (type, module, message, timestamp) VALUES (?, ?, ?, ?)",
                (level, module, message, str(timestamp)[:19])
            )
        conn.commit()
        conn.close()
    except Exception:
        pass

_system_log_once_cache = {}
_SYSTEM_LOG_ONCE_CACHE_MAX = 500

def _add_system_log_once(key, level, module, message, cooldown_sec=300, timestamp=None):
    """Log important repeated events without flooding system_logs."""
    now = time.time()
    last = float(_system_log_once_cache.get(key, 0) or 0)
    if now - last < cooldown_sec:
        return
    _system_log_once_cache[key] = now
    if len(_system_log_once_cache) > _SYSTEM_LOG_ONCE_CACHE_MAX:
        stale = sorted(_system_log_once_cache.items(), key=lambda item: item[1])
        for old_key, _old_ts in stale[:len(_system_log_once_cache) - _SYSTEM_LOG_ONCE_CACHE_MAX]:
            _system_log_once_cache.pop(old_key, None)
    _add_system_log(level, module, message, timestamp=timestamp)

_PROCESS_STATE_FILE = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "process_state.json")
_process_state_lock = threading.Lock()
_process_state_cache = None

def _load_process_state():
    global _process_state_cache
    with _process_state_lock:
        if _process_state_cache is not None:
            return dict(_process_state_cache)
        try:
            with open(_PROCESS_STATE_FILE, "r", encoding="utf-8", errors="ignore") as f:
                data = json.load(f)
            if not isinstance(data, dict):
                data = {}
        except Exception:
            data = _db_get_json("runtime_state", "process_state", {}) or {}
        _process_state_cache = data
        return dict(data)

def _save_process_state_locked(data):
    try:
        os.makedirs(os.path.dirname(_PROCESS_STATE_FILE), exist_ok=True)
        tmp = _PROCESS_STATE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2, sort_keys=True)
        os.replace(tmp, _PROCESS_STATE_FILE)
        return True
    except Exception as e:
        log.warning("[ProcessState] Khong ghi duoc %s: %s", _PROCESS_STATE_FILE, e)
        return False

def _update_process_state(name, **kwargs):
    """Persist lightweight progress for long-running background workers."""
    global _process_state_cache
    now = int(time.time())
    with _process_state_lock:
        data = _process_state_cache
        if data is None:
            try:
                with open(_PROCESS_STATE_FILE, "r", encoding="utf-8", errors="ignore") as f:
                    data = json.load(f)
                if not isinstance(data, dict):
                    data = {}
            except Exception:
                data = {}
        item = data.get(name)
        if not isinstance(item, dict):
            item = {}
        item.update(kwargs)
        item["updated_at"] = now
        data[name] = item
        _process_state_cache = data
        persist_data = dict(data)
        result = dict(item)
    _save_process_state_locked(persist_data)
    _db_set_json("runtime_state", "process_state", persist_data)
    return result

def _get_process_state(name, default=None):
    data = _load_process_state()
    item = data.get(name, default if default is not None else {})
    return dict(item) if isinstance(item, dict) else item

def _livestream_job_label(jid, info):
    user = info.get("watch_username", "") or ""
    src = ("@%s" % user) if user else (info.get("original_url") or info.get("url") or "")
    out = info.get("output_file", "") or "chua co file"
    return "%s pid=%s src=%s file=%s" % (jid, info.get("pid"), src, out)

def _log_livestream_event(level, jid, info, message, once_key="", timestamp=None):
    user = info.get("watch_username", "") or ""
    prefix = ("[@" + user + "] ") if user else "[Livestream] "
    detail = prefix + message
    if once_key:
        _add_system_log_once("livestream:%s:%s" % (jid, once_key), level, "Livestream", detail, 600, timestamp=timestamp)
    else:
        _add_system_log(level, "Livestream", detail, timestamp=timestamp)

def _restart_nas_api(reason):
    """Restart only after every admitted recorder and finalizer has drained."""
    global _livestream_draining
    reason = normalize_vietnamese_message(str(reason))[:240]
    with _livestream_lock:
        if _livestream_draining:
            return False
        _livestream_draining = True
    try:
        # Claims are removed atomically with registration (or on start failure).
        # Wait BEFORE snapshotting jobs so a just-registered recorder is included.
        deadline = time.time() + 30
        while True:
            with _livestream_lock:
                pending = bool(_livestream_starting_claims)
                if not pending:
                    live = [(jid, dict(info)) for jid, info in _livestream_jobs.items()
                            if info.get("status") in
                            ("recording", "starting", "stopping", "timeout", "finalizing")]
                    break
            if time.time() >= deadline:
                raise RuntimeError("starting claims have not completed")
            time.sleep(1)

        def writers(info):
            pid = int(info.get("pid") or 0)
            if not pid:
                return False
            proc = info.get("_proc")
            if proc is not None:
                proc.poll()  # Reap the parent before probing its group.
            if _livestream_pids_in_group(pid):
                return True
            try:
                os.kill(pid, 0)
                return True
            except ProcessLookupError:
                return False

        def terminate(info, sig):
            pid = int(info.get("pid") or 0)
            if not pid or not writers(info):
                return
            owned, _ = _livestream_group_owned_by_job(pid, info)
            if not owned:
                raise RuntimeError("cannot establish recorder ownership")
            try:
                os.killpg(pid, sig)
            except ProcessLookupError:
                pass

        for jid, info in live:
            terminate(info, signal.SIGTERM)
        deadline = time.time() + 45
        remaining = list(live)
        while remaining:
            remaining = [(jid, info) for jid, info in remaining if writers(info)]
            if not remaining or time.time() >= deadline:
                break
            time.sleep(1)
        for jid, info in remaining:
            terminate(info, signal.SIGKILL)
            proc = info.get("_proc")
            if proc is not None:
                proc.wait(timeout=5)
        if any(writers(info) for _, info in live):
            raise RuntimeError("recorder still alive after drain")

        # The finalizing state is reserved under the same lock as its tracker.
        # Include state as well as tracker to fail closed if finalization stalls.
        deadline = time.time() + 600
        while True:
            with _livestream_lock:
                busy = (bool(_livestream_starting_claims) or
                        any(not fx["done"].is_set() for fx in _livestream_finalizers.values()) or
                        any(info.get("status") == "finalizing" or info.get("_remux_pending")
                            for info in _livestream_jobs.values()))
                if not busy:
                    # Watchdog must not schedule a new finalizer between this
                    # check and exec. It checks the same draining flag.
                    _livestream_snapshot_registry()
                    break
            if time.time() >= deadline:
                raise RuntimeError("finalizers have not completed")
            time.sleep(1)
        _add_system_log("WARNING", "NasAPI", "Restart after successful drain: %s" % reason)
        sys.stdout.flush()
        sys.stderr.flush()
        os.execv(sys.executable, [sys.executable, "/opt/nas_api_server.py"])
    except Exception as exc:
        log.error("[NasAPI] Restart cancelled; drain failed: %s", exc)
        with _livestream_lock:
            _livestream_draining = False
        return False


@app.errorhandler(Exception)
def _log_unhandled_flask_error(e):
    try:
        path = getattr(request, "path", "")
        _add_system_log_once(
            "flask_error:%s:%s" % (path, type(e).__name__),
            "ERROR",
            "NasAPI",
            "Unhandled API error path=%s type=%s detail=%s" % (
                path,
                type(e).__name__,
                normalize_vietnamese_message(str(e))[:220]
            ),
            60
        )
    except Exception:
        pass
    try:
        from werkzeug.exceptions import HTTPException
        if isinstance(e, HTTPException):
            return e
    except Exception:
        pass
    return jsonify({"error": "Loi NAS API: %s" % normalize_vietnamese_message(str(e))[:200]}), 500


@app.route('/api/disk/health', methods=['GET'])
@requires_auth
def api_disk_health():
    """Snapshot suc khoe HDD hien tai tu RAM/SQLite, khong kich hoat SMART scan."""
    with _disk_health_lock:
        sample = dict(_disk_health_last_sample) if _disk_health_last_sample else None
    has_live_sample = bool(sample)
    if not sample:
        sample = _db_get_json("hardware_status", "disk_health_current", {}) or {}
    schedule = _disk_health_scheduler_state()
    return jsonify({
        "current": sample or {},
        "sample_interval_sec": int(schedule.get("interval_sec") or _DISK_HEALTH_HEALTHY_INTERVAL_SEC),
        "next_scan_at": int(schedule.get("next_scan_at") or 0),
        "sampling": not has_live_sample and not bool(sample),
    })


@app.route('/api/disk/health/history', methods=['GET'])
@requires_auth
def api_disk_health_history():
    """Time-series suc khoe HDD trong N ngay gần đầy (default 7)."""
    try:
        days = int(request.args.get("days", "7"))
    except Exception:
        days = 7
    days = max(1, min(30, days))
    cutoff = int(time.time()) - (days * 86400)
    target_device = _target_hdd_device_path()
    items = []
    conn = None
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        cur = conn.cursor()
        cur.execute(
            "SELECT payload_json FROM disk_health_history WHERE ts >= ? AND device = ? ORDER BY ts ASC",
            (cutoff, target_device)
        )
        rows = cur.fetchall()
        for row in rows:
            try:
                items.append(json.loads(row[0]))
            except Exception:
                pass
    except Exception as e:
        log.warning("[DiskHealth] Read SQLite history lỗi: %s", e)
    finally:
        try:
            if conn is not None:
                conn.close()
        except Exception:
            pass
    return jsonify({
        "days": days,
        "count": len(items),
        "samples": items,
    })


# ============================================================================
# NAS SLEEP SCHEDULE — HDD spindown / full suspend theo lich
# ============================================================================
# Muc dich: giam hao mon HDD (ich biet voi o cu nhieu pending sectors) bang
# cach tu dong spindown ngoai gio dung. Khong tat NAS hoan toan (van ping được),
# chi parking head + ngung quay platter.
# ============================================================================
_DATA_FLOW_LAST_SAMPLE = {"ts": 0, "io": {}, "net": {}}


def _read_disk_health_history(days=7):
    try:
        days = max(1, min(30, int(days)))
    except Exception:
        days = 7
    cutoff = int(time.time()) - (days * 86400)
    target_device = _target_hdd_device_path()
    items = []
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        cur = conn.cursor()
        cur.execute(
            "SELECT payload_json FROM disk_health_history WHERE ts >= ? AND device = ? ORDER BY ts ASC",
            (cutoff, target_device)
        )
        rows = cur.fetchall()
        conn.close()
        for row in rows:
            try:
                items.append(json.loads(row[0]))
            except Exception:
                pass
    except Exception as e:
        log.warning("[Insights] Read SQLite disk history lỗi: %s", e)
    return items


def _disk_health_trend(days=7):
    with _disk_health_lock:
        current = dict(_disk_health_last_sample) if _disk_health_last_sample else {}
    items = _read_disk_health_history(days)
    scores = [int(x.get("score", 0) or 0) for x in items if x.get("score") is not None]
    temps = [int(x.get("temp_c", 0) or 0) for x in items if x.get("temp_c")]
    first = items[0] if items else current
    last = items[-1] if items else current
    trend = {
        "device": current.get("device", _target_hdd_device_path()),
        "score": int(current.get("score", 0) or 0),
        "smart_status": current.get("smart_status", "Unknown"),
        "temp_c": current.get("temp_c"),
        "power_on_hours": current.get("power_on_hours"),
        "sample_count": len(items),
        "min_score": min(scores) if scores else int(current.get("score", 0) or 0),
        "max_score": max(scores) if scores else int(current.get("score", 0) or 0),
        "score_delta": int(last.get("score", 0) or 0) - int(first.get("score", 0) or 0) if first and last else 0,
        "max_temp_c": max(temps) if temps else current.get("temp_c"),
        "warnings": current.get("warnings", []) or [],
        "watch_fields": {
            "reallocated_sectors": current.get("reallocated_sectors"),
            "pending_sectors": current.get("pending_sectors"),
            "offline_uncorrectable": current.get("offline_uncorrectable"),
            "udma_crc_err": current.get("udma_crc_err"),
            "command_timeout": current.get("command_timeout"),
        },
    }
    if trend["score"] >= 90 and not trend["warnings"]:
        trend["status_text"] = "Trạng thái HDD: Hoạt động ổn định."
    elif trend["score"] >= 70:
        trend["status_text"] = "Trạng thái HDD: Khuyến nghị giám sát thêm."
    else:
        trend["status_text"] = "Trạng thái HDD: Yêu cầu kiểm tra chẩn đoán."
    return trend


def _active_livestream_count():
    try:
        with _livestream_lock:
            return sum(1 for j in _livestream_jobs.values() if j.get("status") == "recording")
    except Exception:
        return 0


def _workload_coordinator():
    cpu_pct = 0.0
    mem_pct = 0.0
    temp_c = None
    try:
        cpu_pct = float(psutil.cpu_percent(interval=0.1))
        mem_pct = float(psutil.virtual_memory().percent)
    except Exception:
        pass
    try:
        with _disk_health_lock:
            temp_c = (_disk_health_last_sample or {}).get("temp_c")
    except Exception:
        pass
    usb = _usb_import_public_state() if "_usb_import_public_state" in globals() else {}
    usb_active = str(usb.get("status", "")).lower() in ("copying", "cancelling")
    live_count = _active_livestream_count()
    pressure = 0
    reasons = []
    if cpu_pct >= 80:
        pressure += 2; reasons.append("Tải CPU hệ thống cao")
    elif cpu_pct >= 60:
        pressure += 1; reasons.append("Mức sử dụng CPU ở ngưỡng cảnh báo")
    if mem_pct >= 85:
        pressure += 2; reasons.append("Mức sử dụng RAM ở ngưỡng nguy hiểm")
    elif mem_pct >= 70:
        pressure += 1; reasons.append("Mức sử dụng RAM ở ngưỡng cảnh báo")
    if temp_c and temp_c >= 50:
        pressure += 2; reasons.append("Nhiệt độ HDD vượt ngưỡng an toàn")
    elif temp_c and temp_c >= 45:
        pressure += 1; reasons.append("Nhiệt độ HDD ở ngưỡng cảnh báo")
    if live_count > 0:
        pressure += 1; reasons.append("%d luồng ghi hình trực tiếp đang hoạt động" % live_count)
    if usb_active:
        pressure += 1; reasons.append("Tác vụ nhập dữ liệu USB đang thực thi")
    if pressure >= 5:
        mode = "protect"; recommendation = "Khuyến nghị tạm ngưng cấp phát tác vụ mới; ưu tiên duy trì luồng ghi hình và sao chép dữ liệu hiện hành."
    elif pressure >= 3:
        mode = "balanced"; recommendation = "Khuyến nghị giới hạn số lượng tác vụ nền; tránh thực thi đồng thời quét hoặc sao chép dữ liệu dung lượng lớn."
    else:
        mode = "normal"; recommendation = "Tài nguyên hệ thống ở ngưỡng an toàn, đáp ứng tốt các phiên làm việc nền cơ bản."
    return {
        "mode": mode, "pressure": pressure, "cpu_pct": round(cpu_pct, 1),
        "mem_pct": round(mem_pct, 1), "hdd_temp_c": temp_c,
        "active_livestreams": live_count, "usb_import_active": usb_active,
        "reasons": reasons, "recommendation": recommendation,
    }


def _path_usage(path):
    try:
        usage = shutil.disk_usage(path)
        pct = int((usage.used * 100) / max(1, usage.total))
        return {"path": path, "total": usage.total, "used": usage.used, "free": usage.free, "percent": pct}
    except Exception:
        return {"path": path, "total": 0, "used": 0, "free": 0, "percent": 0}


def _folder_size_limited(path, max_files=5000):
    total = 0
    files = 0
    try:
        for root, dirs, names in os.walk(path):
            for name in names:
                files += 1
                if files > max_files:
                    return total, files, True
                try:
                    total += os.path.getsize(os.path.join(root, name))
                except Exception:
                    pass
    except Exception:
        pass
    return total, files, False


def _emmc_guard():
    root = _path_usage("/")
    log_usage = _path_usage("/var/log")
    state_size, state_files, state_partial = _folder_size_limited("/etc/nas/state")
    # .nas_meta nằm trên HDD dữ liệu; không walk thư mục này trong dashboard vì
    # có thể chứa nhiều cache/thumb/log và làm HDD phải đọc metadata không cần thiết.
    meta_size, meta_files, meta_partial = 0, 0, False
    warnings = []
    recommendations = []
    if root.get("percent", 0) >= 85:
        warnings.append("eMMC root gần đầy")
        recommendations.append("Dọn package cache/log cũ và chuyển cache lớn sang HDD.")
    if log_usage.get("percent", 0) >= 80:
        warnings.append("log2ram/zram log gần đầy")
        recommendations.append("Giảm mức log hoặc prune log thường xuyên.")
    if state_size > 100 * 1024 * 1024:
        warnings.append("state trên eMMC lớn")
        recommendations.append("Rút gọn history hoặc chuyển history dài ngày sang HDD.")
    if not recommendations:
        recommendations.append("eMMC đang an toàn; tiếp tục tránh ghi log/cache lớn vào root.")
    return {
        "root": root, "log": log_usage, "state_bytes": state_size,
        "state_files": state_files, "state_partial": state_partial,
        "meta_bytes": meta_size, "meta_files": meta_files,
        "meta_partial": meta_partial, "warnings": warnings,
        "recommendations": recommendations,
    }



def _data_flow_snapshot():
    global _DATA_FLOW_LAST_SAMPLE
    now = time.time()
    devname = _target_hdd_devname()
    io = _read_io_stats(devname)
    try:
        net = psutil.net_io_counters()._asdict()
    except Exception:
        net = {}
    last = _DATA_FLOW_LAST_SAMPLE or {}
    last_ts = float(last.get("ts") or 0)
    read_bps = write_bps = rx_bps = tx_bps = 0
    if last_ts > 0 and (now - last_ts) > 0:
        dt = max(0.1, now - last_ts)
        last_io = last.get("io") or {}
        last_net = last.get("net") or {}
        try:
            read_bps = int(max(0, io.get("sectors_read", 0) - last_io.get("sectors_read", 0)) * 512 / dt)
            write_bps = int(max(0, io.get("sectors_written", 0) - last_io.get("sectors_written", 0)) * 512 / dt)
            rx_bps = int(max(0, net.get("bytes_recv", 0) - last_net.get("bytes_recv", 0)) / dt)
            tx_bps = int(max(0, net.get("bytes_sent", 0) - last_net.get("bytes_sent", 0)) / dt)
        except Exception:
            pass
    _DATA_FLOW_LAST_SAMPLE = {"ts": now, "io": io, "net": net}
    usb = _usb_import_public_state() if "_usb_import_public_state" in globals() else {}
    current_tasks = []
    active_task_write_bps = 0

    if str(usb.get("status", "")).lower() == "copying":
        usb_speed = usb.get("copy_speed_bps", 0) or 0
        active_task_write_bps += usb_speed
        current_tasks.append({
            "type": "usb_import", "label": "USB Import",
            "file": usb.get("current_file", ""), "source": usb.get("current_source", ""),
            "dest": usb.get("current_dest", ""), "speed_bps": usb_speed,
            "progress": int((usb.get("bytes_processed", 0) or 0) * 100 / max(1, usb.get("bytes_total", 0) or 0)),
        })
    try:
        with _livestream_lock:
            for job_id, job in list(_livestream_jobs.items())[:5]:
                if job.get("status") == "recording":
                    cur_size = 0
                    out_file = job.get("output_file") or ""
                    out_dir = job.get("output_dir") or _LIVESTREAM_DIR
                    try:
                        if out_file and os.path.exists(os.path.join(out_dir, out_file)):
                            cur_size = os.path.getsize(os.path.join(out_dir, out_file))
                        elif os.path.isdir(out_dir):
                            ls_files = [os.path.join(out_dir, f) for f in os.listdir(out_dir) if not f.endswith('.log')]
                            if ls_files:
                                cur_size = os.path.getsize(max(ls_files, key=os.path.getmtime))
                    except Exception: pass

                    last_job_size = job.get("last_calc_size", 0)
                    last_job_ts = job.get("last_calc_ts", 0)
                    job_speed = 0
                    if last_job_ts > 0 and now > last_job_ts and cur_size >= last_job_size:
                        job_speed = int((cur_size - last_job_size) / max(0.1, now - last_job_ts))
                    job["last_calc_size"] = cur_size
                    job["last_calc_ts"] = now
                    active_task_write_bps += job_speed

                    current_tasks.append({
                        "type": "livestream", "label": "Livestream",
                        "file": job.get("output_file") or job.get("filename") or job.get("output") or job_id,
                        "source": job.get("url", ""), "dest": job.get("output_file", ""),
                        "speed_bps": job_speed, "progress": 0,
                    })
    except Exception:
        pass
    try:
        with _thumb_stats_lock:
            if _thumb_stats.get("running"):
                file_label = _thumb_stats.get("last_file", "")
                if "/" in file_label:
                    file_label = file_label.split("/")[-1]
                prog = 0
                if _thumb_stats.get("total_in_batch", 0) > 0:
                    prog = int((_thumb_stats.get("generated", 0) + _thumb_stats.get("errors", 0)) * 100 / _thumb_stats.get("total_in_batch", 1))
                current_tasks.append({
                    "type": "thumbnail", "label": "Tạo ảnh thu nhỏ",
                    "file": file_label, "source": "",
                    "dest": "", "speed_bps": 0, "progress": prog,
                })
    except Exception:
        pass

    if active_task_write_bps > write_bps:
        write_bps = active_task_write_bps

    return {
        "ts": int(now), "device": "/dev/%s" % devname,
        "disk_read_bps": read_bps, "disk_write_bps": write_bps,
        "net_rx_bps": rx_bps, "net_tx_bps": tx_bps,
        "io_in_progress": io.get("ios_in_progress", 0),
        "current_tasks": current_tasks,
    }


def _maintenance_advisor():
    health = _disk_health_trend(7)
    workload = _workload_coordinator()
    emmc = _emmc_guard()
    usb = _usb_import_public_state() if "_usb_import_public_state" in globals() else {}
    actions = []
    if health.get("score", 0) < 80 or health.get("warnings"):
        actions.append({"priority": "high", "title": "Kiểm tra HDD Toshiba", "detail": health.get("status_text", "")})
    if workload.get("mode") == "protect":
        actions.append({"priority": "high", "title": "Tối ưu hoá tải hệ thống", "detail": workload.get("recommendation", "")})
    if emmc.get("warnings"):
        actions.append({"priority": "medium", "title": "Tối ưu hoá tuổi thọ eMMC", "detail": "; ".join(emmc.get("recommendations", [])[:2])})
    if str(usb.get("status", "")).lower() in ("done", "cancelled", "error") and usb.get("last_error"):
        actions.append({"priority": "medium", "title": "Kiểm tra USB Import", "detail": str(usb.get("last_error", ""))[:180]})
    if not actions:
        actions.append({"priority": "low", "title": "Bảo trì định kỳ", "detail": "Có thể chạy backup cấu hình và dọn rác khi NAS nhàn rỗi."})
    return {"generated_at": int(time.time()), "summary": actions[0]["detail"] if actions else "", "actions": actions[:8]}


@app.route('/api/disk/health/trend', methods=['GET'])
@requires_auth
def api_disk_health_trend():
    try:
        return jsonify(_disk_health_trend(request.args.get("days", "7")))
    except Exception as e:
        log.error("[API] Error in api_disk_health_trend: %s", e)
        return jsonify({"error": str(e), "history": []}), 500


@app.route('/api/system/workload', methods=['GET'])
@requires_auth
def api_system_workload():
    try:
        return jsonify(_workload_coordinator())
    except Exception as e:
        log.error("[API] Error in api_system_workload: %s", e)
        return jsonify({"error": str(e), "status": "unknown"}), 500


@app.route('/api/system/emmc_guard', methods=['GET'])
@requires_auth
def api_system_emmc_guard():
    try:
        return jsonify(_emmc_guard())
    except Exception as e:
        log.error("[API] Error in api_system_emmc_guard: %s", e)
        return jsonify({"error": str(e), "status": "unknown"}), 500


@app.route('/api/system/data_flow', methods=['GET'])
@requires_auth
def api_system_data_flow():
    try:
        return jsonify(_data_flow_snapshot())
    except Exception as e:
        log.error("[API] Error in api_system_data_flow: %s", e)
        return jsonify({"error": str(e)}), 500


_STORAGE_SUMMARY_CACHE = {"ts": 0.0, "data": None}
_STORAGE_SUMMARY_LOCK = threading.Lock()
# CC-005 P1/P2: unify TTL cache tranh spin-up HDD (xem _SMART_TTL/_OMV_OVERVIEW_TTL):
# smart 86400s / omv 900s / storage 300s — cache HIT thi KHONG quet disk/goi RPC.
_STORAGE_SUMMARY_TTL = 300  # 5 phut — quet disk nang, khong the moi lan mo app
# CC-004 P1-7: chong spam ?force=1 (quet full disk 45s/HDD spin-up).
# Moi IP chi duoc force 1 lan/phut — force lien tiep tra cache + retry_after.
_STORAGE_SUMMARY_FORCE_TS = {}
_STORAGE_SUMMARY_FORCE_LOCK = threading.Lock()
_STORAGE_SUMMARY_FORCE_MIN_INTERVAL = 60.0

_STORAGE_SKIP_DIRS = {".thumbs", ".thumbnails", ".trash", ".nas_meta", ".git",
                      ".recycle", "@eaDir", "#recycle", ".cache"}


_STORAGE_SUMMARY_BUILD_LOCK = threading.Lock()

def _build_storage_summary(max_seconds=45, refresh=False):
    """Summarize the persistent index; never walk the disk for a dashboard read."""
    _refresh_file_index_for_search()
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=5)
    try:
        conn.execute('CREATE TABLE IF NOT EXISTS storage_summary_snapshot (id INTEGER PRIMARY KEY, root TEXT, scanned_at REAL, payload TEXT)')
        for row in conn.execute("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'storage_summary_%'").fetchall():
            conn.execute('DROP TRIGGER IF EXISTS "%s"' % row[0].replace('"', '""'))
        if conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='storage_summary_revision'").fetchone():
            conn.execute('DROP TABLE storage_summary_revision')
        conn.commit()
        conn.execute('BEGIN')
        state = conn.execute('SELECT root, scanned_at, ready, catalogue_revision FROM index_state WHERE id=1').fetchone()
        root = _get_webdav_public_dir()
        if not state or not root or state[0] != os.path.realpath(root):
            raise ValueError('File index unavailable for the configured root')
        revision = state[3]
        cached = conn.execute('SELECT payload FROM storage_summary_snapshot WHERE id=1 AND root=?', (state[0],)).fetchone()
        if cached and not refresh:
            result = json.loads(cached[0])
            revision_changed = result.get('catalogue_revision', -1) != revision
            result['partial'] = not bool(state[2]) or _index_scan_lock.locked() or revision_changed
            if revision_changed and _STORAGE_SUMMARY_BUILD_LOCK.acquire(False):
                def rebuild():
                    try:
                        _build_storage_summary(refresh=True)
                    except Exception as error:
                        log.warning("[Storage] Background statistics refresh failed: %s", error)
                    finally:
                        _STORAGE_SUMMARY_BUILD_LOCK.release()
                threading.Thread(target=rebuild, daemon=True).start()
            return result
        rust_summary = None
        bridge = globals().get("rust_bridge")
        if bridge and hasattr(bridge, "compute_storage_summary"):
            rust_summary = bridge.compute_storage_summary(INDEX_DB_PATH)

        if rust_summary is not None:
            by_type = rust_summary["by_type"]
            largest = rust_summary["largest"]
            total_files = rust_summary["total_files"]
            total_bytes = rust_summary["total_bytes"]
            total_dirs = rust_summary["total_dirs"]
        else:
            groups = {
                'image': ('jpg','jpeg','png','webp','bmp','gif','heic','heif'),
                'video': ('mp4','mkv','avi','mov','mpg','mpeg','wmv','flv','ts','m4v','webm'),
                'doc': ('pdf','doc','docx','xls','xlsx','ppt','pptx','txt','md','csv'),
                'audio': ('mp3','wav','flac','m4a','ogg'),
                'archive': ('zip','rar','7z','tar','gz'),
            }
            extensions = dict(('.' + ext, category) for category, values in groups.items() for ext in values)
            conn.create_function('storage_category', 1, lambda name: extensions.get(os.path.splitext(name or '')[1].lower(), 'other'))
            category_sql = 'storage_category(name)'
            by_type = dict((category, {'files': 0, 'bytes': 0}) for category in list(groups) + ['other'])
            for category, count, size in conn.execute('SELECT ' + category_sql + ', COUNT(*), COALESCE(SUM(size),0) FROM file_index WHERE is_dir=0 GROUP BY 1'):
                by_type[category] = {'files': count, 'bytes': size}
            largest = [{'path': row[0], 'bytes': row[1]} for row in conn.execute('SELECT path,size FROM file_index WHERE is_dir=0 ORDER BY size DESC,path LIMIT 10')]
            total_files = sum(value['files'] for value in by_type.values())
            total_bytes = sum(value['bytes'] for value in by_type.values())
            total_dirs = conn.execute('SELECT COUNT(*) FROM file_index WHERE is_dir=1').fetchone()[0]

        result = {
            'total_files': total_files,
            'total_bytes': total_bytes,
            'total_dirs': total_dirs,
            'by_type': by_type, 'largest': largest,
            'partial': not bool(state[2]) or _index_scan_lock.locked(),
            'scanned_at': int(state[1]), 'source': 'file_index',
            'summary_updated_at': int(time.time()), 'index_revision': revision,
            'catalogue_revision': revision,
            'metadata_scope': 'indexed_snapshot',
        }
        conn.commit()
        if not result['partial']:
            conn.execute('INSERT OR REPLACE INTO storage_summary_snapshot(id,root,scanned_at,payload) VALUES(1,?,?,?)', (state[0], state[1], json.dumps(result)))
            conn.commit()
        return result
    finally:
        if conn is not None:
            conn.close()




"""Internal NAS duplicate coordinator. Hash bytes on clients, validate metadata here."""
import os
import stat
import json
import time
import hashlib
import sqlite3
import threading
import signal

# Leases are polled; SIGIO must never terminate the API during a lease break.
if hasattr(signal, 'SIGIO') and threading.current_thread() is threading.main_thread():
    signal.signal(signal.SIGIO, signal.SIG_IGN)


_DUPLICATE_NAMESPACE_LOCK = '/run/naswebdav/namespace.lock'
_DUPLICATE_NAMESPACE_MANIFEST = '/var/lib/naswebdav-namespace-guard.json'
_DUPLICATE_NAMESPACE_FILES = ('/var/www/webdav/public/index.php',
                              '/var/www/webdav/bootstrap/nas_namespace_guard.php',
                              '/etc/tmpfiles.d/naswebdav-namespace.conf')


def _duplicate_validate_namespace():
    current = os.lstat(_DUPLICATE_NAMESPACE_MANIFEST)
    if not stat.S_ISREG(current.st_mode) or current.st_uid != 0 or current.st_mode & 0o022:
        raise ValueError('Untrusted namespace guard manifest')
    with open(_DUPLICATE_NAMESPACE_MANIFEST, 'r') as handle:
        manifest = json.load(handle)
    expected = set(_DUPLICATE_NAMESPACE_FILES)
    files = manifest.get('files', {})
    if manifest.get('lock_path') != _DUPLICATE_NAMESPACE_LOCK or set(files) != expected:
        raise ValueError('WebDAV namespace guard is not installed')
    for path, digest in files.items():
        current = os.lstat(path)
        if not stat.S_ISREG(current.st_mode) or current.st_uid != 0 or current.st_mode & 0o022:
            raise ValueError('Untrusted namespace guard source')
        with open(path, 'rb') as handle:
            actual = hashlib.sha256(handle.read()).hexdigest()
        if actual != digest:
            raise ValueError('WebDAV namespace guard source changed')


def _duplicate_open_namespace_lock(exclusive):
    import fcntl
    parent = os.lstat(os.path.dirname(_DUPLICATE_NAMESPACE_LOCK))
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != 0 or parent.st_mode & 0o022:
        raise ValueError('Unsafe namespace lock directory')
    fd = os.open(_DUPLICATE_NAMESPACE_LOCK, os.O_RDONLY | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o644)
    try:
        current = os.fstat(fd)
        if not stat.S_ISREG(current.st_mode) or current.st_uid != 0 or current.st_mode & 0o022 or current.st_nlink != 1:
            raise ValueError('Unsafe namespace lock file')
        operation = fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH
        fcntl.flock(fd, operation | fcntl.LOCK_NB)
        return fd
    except Exception:
        os.close(fd)
        raise


@app.before_request
def _duplicate_namespace_before_request():
    if request.endpoint == 'api_disk_trash_batch' and request.method == 'POST':
        try:
            request.environ['nas.namespace.fd'] = _duplicate_open_namespace_lock(False)
        except (OSError, ValueError):
            response = jsonify({'error': 'NAS cleanup in progress'})
            response.status_code = 503
            response.headers['Retry-After'] = '1'
            return response


@app.teardown_request
def _duplicate_namespace_teardown_request(error):
    fd = request.environ.pop('nas.namespace.fd', None)
    if fd is not None:
        os.close(fd)


def _duplicate_file_info(relative):
    if not isinstance(relative, str) or not relative or relative.startswith('/') or '\\' in relative:
        raise ValueError('Invalid public file path')
    if any(not part or part in ('.', '..') or part.startswith('.') for part in relative.split('/')):
        raise ValueError('Hidden or unsafe file path')
    configured = _get_webdav_public_dir()
    if not configured:
        raise ValueError('Public root unavailable')
    root = os.path.realpath(configured)
    if not root or root == os.path.abspath(os.sep):
        raise ValueError('Public root unavailable')
    logical = os.path.join(root, relative)
    parent = _index_resolve_directory(os.path.dirname(logical), root, _index_storage_boundary(root))
    if parent is None:
        raise ValueError('File is outside the public boundary')
    physical = os.path.join(parent, os.path.basename(logical))
    current = os.lstat(physical)
    if not stat.S_ISREG(current.st_mode):
        raise ValueError('Only regular files can be verified')
    return {'path': relative, 'physical': physical, 'size': current.st_size,
            'mtime_ns': current.st_mtime_ns,
            'file_id': '%s:%s' % (current.st_dev, current.st_ino),
            'version': _duplicate_stat_version(current)}


def _duplicate_stat_version(current):
    value = '%s:%s:%s:%s:%s' % (current.st_dev, current.st_ino, current.st_size,
                                current.st_mtime_ns, current.st_ctime_ns)
    return hashlib.sha256(value.encode('ascii')).hexdigest()


def _duplicate_open_lease(info):
    import fcntl
    import signal
    if signal.getsignal(signal.SIGIO) != signal.SIG_IGN:
        raise ValueError('Safe lease notification is unavailable')
    fd = os.open(info['physical'], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        # No async signal recipient: inspect F_GETLEASE and fail closed on a break.
        fcntl.fcntl(fd, fcntl.F_SETOWN, 0)
        fcntl.fcntl(fd, fcntl.F_SETLEASE, fcntl.F_RDLCK)
        fcntl.fcntl(fd, fcntl.F_SETOWN, 0)
        if fcntl.fcntl(fd, fcntl.F_GETOWN) != 0:
            raise ValueError('Unexpected lease signal owner')
        if _duplicate_stat_version(os.fstat(fd)) != info['version']:
            raise ValueError('File changed before writer exclusion')
        return fd
    except Exception:
        os.close(fd)
        raise


def _duplicate_sync_directory(directory):
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _duplicate_db():
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=5)
    conn.execute("CREATE TABLE IF NOT EXISTS verified_file_hashes (file_id TEXT PRIMARY KEY, version TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, verified_at REAL NOT NULL, path TEXT NOT NULL DEFAULT '')")
    if 'path' not in [row[1] for row in conn.execute('PRAGMA table_info(verified_file_hashes)')]:
        try:
            conn.execute("ALTER TABLE verified_file_hashes ADD COLUMN path TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            if 'path' not in [row[1] for row in conn.execute('PRAGMA table_info(verified_file_hashes)')]:
                conn.close()
                raise
    conn.execute('CREATE INDEX IF NOT EXISTS verified_sha_size_path ON verified_file_hashes(sha256,size,path)')
    conn.commit()
    return conn


def _duplicate_lookup(info):
    conn = _duplicate_db()
    try:
        row = conn.execute('SELECT sha256,path FROM verified_file_hashes WHERE file_id=? AND version=? AND size=?',
                           (info['file_id'], info['version'], info['size'])).fetchone()
        if row and row[1] != info['path']:
            valid_path = False
            if row[1]:
                try:
                    previous = _duplicate_file_info(row[1])
                    valid_path = previous['file_id'] == info['file_id'] and previous['version'] == info['version']
                except (OSError, ValueError):
                    pass
            if not valid_path:
                conn.execute('UPDATE verified_file_hashes SET path=? WHERE file_id=?', (info['path'],info['file_id']))
                conn.commit()
        return row[0] if row else ''
    finally:
        if conn is not None:
            conn.close()


def _duplicate_save_hash(relative, version, sha256):
    if not isinstance(sha256, str) or len(sha256) != 64 or any(c not in '0123456789abcdef' for c in sha256):
        raise ValueError('A complete SHA256 is required')
    info = _duplicate_file_info(relative)
    if info['version'] != version:
        raise ValueError('File changed during verification')
    conn = _duplicate_db()
    try:
        conn.execute('INSERT OR REPLACE INTO verified_file_hashes(file_id,version,size,sha256,verified_at,path) VALUES(?,?,?,?,?,?)',
                     (info['file_id'], info['version'], info['size'], sha256, time.time(), info['path']))
        conn.commit()
    finally:
        if conn is not None:
            conn.close()
    return info


def _duplicate_work_dir():
    configured = _get_webdav_public_dir()
    if not configured:
        raise ValueError('Public root unavailable')
    root = os.path.realpath(configured)
    directory = os.path.join(_index_storage_boundary(root) or root, '.naswebdav', 'verified_duplicates')
    os.makedirs(directory, mode=0o700, exist_ok=True)
    if os.path.realpath(directory) != os.path.abspath(directory) or os.lstat(directory).st_uid != os.geteuid():
        raise ValueError('Unsafe duplicate work directory')
    os.chmod(directory, 0o700)
    return directory


def _duplicate_cleanup(victim_path, keeper_path, victim_version, keeper_version, sha256, work_lease=None):
    import fcntl
    import uuid
    _duplicate_validate_namespace()
    directory = _duplicate_work_dir()
    def require_work_lease():
        if work_lease is not None:
            if (not isinstance(work_lease, dict) or work_lease.get('name') != 'duplicates' or
                    not isinstance(work_lease.get('owner'), str) or len(work_lease.get('owner')) > 128 or
                    not isinstance(work_lease.get('token'), str) or len(work_lease.get('token')) > 128 or
                    not nas_work_coordinator.owns_lease(INDEX_DB_PATH, 'duplicates',
                                                        work_lease.get('owner'), work_lease.get('token'))):
                raise ValueError('NAS duplicate maintenance lease expired or is not owned')
    with os.fdopen(_duplicate_open_namespace_lock(True), 'r') as lock:
        require_work_lease()
        keeper = _duplicate_file_info(keeper_path)
        victim = _duplicate_file_info(victim_path)
        if keeper['file_id'] == victim['file_id']:
            raise ValueError('Both URLs address the same physical file')
        if keeper['version'] != keeper_version or victim['version'] != victim_version:
            raise ValueError('File version changed')
        if keeper['size'] != victim['size'] or not sha256 or _duplicate_lookup(keeper) != sha256 or _duplicate_lookup(victim) != sha256:
            raise ValueError('Full-content verification is missing')
        keeper_fd = _duplicate_open_lease(keeper)
        victim_fd = None
        try:
            victim_fd = _duplicate_open_lease(victim)
            quarantine = os.path.join(directory, uuid.uuid4().hex)
            journal = quarantine + '.json'
            with open(journal, 'x') as recovery:
                json.dump({'original_path': victim_path, 'physical': victim['physical'],
                           'quarantine': quarantine, 'file_id': victim['file_id'],
                           'version': victim_version, 'created_at': time.time()}, recovery)
                recovery.flush()
                os.fsync(recovery.fileno())
            _duplicate_sync_directory(directory)
            try:
                require_work_lease()
                os.rename(victim['physical'], quarantine)
                _duplicate_sync_directory(os.path.dirname(victim['physical']))
                _duplicate_sync_directory(directory)
                moved = os.lstat(quarantine)
                if ('%s:%s' % (moved.st_dev, moved.st_ino) != victim['file_id'] or
                        moved.st_size != victim['size'] or moved.st_mtime_ns != victim['mtime_ns']):
                    raise ValueError('Candidate was replaced during cleanup')
                survivor = _duplicate_file_info(keeper_path)
                if survivor['version'] != keeper_version:
                    raise ValueError('Survivor changed during cleanup')
                if any(fcntl.fcntl(fd, fcntl.F_GETLEASE) != fcntl.F_RDLCK for fd in (keeper_fd, victim_fd)):
                    raise ValueError('File was requested for writing; cleanup cancelled')
                # shortcut: external root/SSH mutations are outside the managed namespace lock.
                require_work_lease()
                os.unlink(quarantine)
            except Exception:
                if os.path.lexists(quarantine) and not os.path.lexists(victim['physical']):
                    os.rename(quarantine, victim['physical'])
                    _duplicate_sync_directory(os.path.dirname(victim['physical']))
                raise
            finally:
                if not os.path.lexists(quarantine):
                    os.unlink(journal)
                _duplicate_sync_directory(directory)
        finally:
            if victim_fd is not None:
                os.close(victim_fd)
            os.close(keeper_fd)
        conn = None
        try:
            conn = _duplicate_db()
            conn.execute('DELETE FROM verified_file_hashes WHERE file_id=?', (victim['file_id'],))
            conn.execute('DELETE FROM file_index WHERE path=?', (victim_path,))
            if conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='file_hashes'").fetchone():
                conn.execute('DELETE FROM file_hashes WHERE path IN (?,?)',
                             (victim_path, '/webdav/' + victim_path))
            if conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='thumb_neg_cache'").fetchone():
                conn.execute('DELETE FROM thumb_neg_cache WHERE path=?', (victim['physical'],))
            parent = victim_path.rsplit('/', 1)[0] + '/' if '/' in victim_path else ''
            conn.execute('UPDATE index_directories SET stamp=NULL,complete=0 WHERE path=?', (parent,))
            nas_work_coordinator.bump_catalogue_revision(conn)
            conn.execute("UPDATE index_state SET ready=0,incomplete_reason='file_mutation_pending',scanned_at=0 WHERE id=1")
            conn.commit()
        except sqlite3.Error as error:
            log.warning('[Duplicates] Deleted file metadata refresh deferred: %s', error)
        finally:
            if conn is not None:
                conn.close()
        return {'deleted': True, 'path': victim_path, 'keeper': keeper_path,
                'logical_bytes_removed': victim['size']}


def _duplicate_find_matches(sha256, size, after=''):
    if not isinstance(sha256, str) or len(sha256) != 64 or any(c not in '0123456789abcdef' for c in sha256):
        raise ValueError('Complete SHA256 required')
    if not isinstance(size, int) or size < 0 or size > 9223372036854775807 or not isinstance(after, str) or len(after) > 4096:
        raise ValueError('Invalid size or cursor')
    _refresh_file_index_for_search()
    conn = _duplicate_db()
    try:
        state = conn.execute('SELECT root,ready FROM index_state WHERE id=1').fetchone()
        public = _get_webdav_public_dir()
        ready = bool(state and public and state[0] == os.path.realpath(public) and state[1] and not _index_scan_lock.locked())
        rows = conn.execute('SELECT path FROM file_index WHERE is_dir=0 AND size=? AND path>? UNION SELECT path FROM verified_file_hashes WHERE sha256=? AND size=? AND path>? ORDER BY path LIMIT 64', (size,after,sha256,size,after)).fetchall()
    finally:
        if conn is not None:
            conn.close()
    matches, unverified = [], []
    for row in rows:
        path = row[0]
        try:
            info = _duplicate_file_info(path)
            if info['size'] != size:
                continue
            known = _duplicate_lookup(info)
            if known == sha256:
                matches.append(path)
            elif not known:
                unverified.append(path)
        except (OSError, ValueError):
            pass
    return {'matches': matches, 'unverified': unverified, 'index_ready': ready,
            'next_cursor': rows[-1][0] if len(rows) == 64 else ''}


@app.route('/api/duplicates/capabilities', methods=['GET', 'POST'])
@requires_admin
def api_duplicate_capabilities():
    return jsonify({'backup_protocol': 1, 'native_versions': True, 'shared_hash_catalogue': True})


@app.route('/api/duplicates/find', methods=['POST'])
@requires_admin
def api_duplicate_find():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        return jsonify(_duplicate_find_matches(body.get('sha256'), body.get('size'), body.get('after', '')))
    except (OSError, ValueError) as error:
        return jsonify({'error': str(error)}), 409


@app.route('/api/duplicates/file', methods=['POST'])
@requires_admin
def api_duplicate_file():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        info = _duplicate_file_info(body.get('path'))
        info.pop('physical')
        info['sha256'] = _duplicate_lookup(info)
        return jsonify(info)
    except (OSError, ValueError) as error:
        return jsonify({'error': str(error)}), 409


@app.route('/api/duplicates/hash', methods=['POST'])
@requires_admin
def api_duplicate_hash():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        _duplicate_save_hash(body.get('path'), body.get('version'), body.get('sha256'))
        return jsonify({'saved': True})
    except (OSError, ValueError) as error:
        return jsonify({'error': str(error)}), 409


@app.route('/api/duplicates/cleanup', methods=['POST'])
@requires_admin
def api_duplicate_cleanup():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        result = _duplicate_cleanup(body.get('victim'), body.get('keeper'), body.get('victim_version'),
                                    body.get('keeper_version'), body.get('sha256'), body.get('work_lease'))
        return jsonify(result)
    except (OSError, ValueError) as error:
        return jsonify({'deleted': False, 'error': str(error)}), 409


@app.route('/api/duplicates/content', methods=['POST'])
@requires_admin
def api_duplicate_content():
    try:
        body = request.get_json() or {}
        if not isinstance(body, dict):
            raise ValueError('JSON object required')
        info = _duplicate_file_info(body.get('path'))
        if info['version'] != body.get('version'):
            raise ValueError('File version changed')
        fd = _duplicate_open_lease(info)
    except (OSError, ValueError) as error:
        return jsonify({'error': str(error)}), 409

    handle = os.fdopen(fd, 'rb')

    def generate():
        import fcntl
        try:
            remaining = info['size']
            while remaining:
                if fcntl.fcntl(handle.fileno(), fcntl.F_GETLEASE) != fcntl.F_RDLCK:
                    raise ValueError('File requested for writing during verification')
                chunk = handle.read(min(65536, remaining))
                if not chunk:
                    raise ValueError('Incomplete verification stream')
                remaining -= len(chunk)
                yield chunk
        finally:
            handle.close()

    response = Response(generate(), mimetype='application/octet-stream')
    response.call_on_close(handle.close)
    response.content_length = info['size']
    response.headers['X-NAS-Version'] = info['version']
    response.headers['X-NAS-File-ID'] = info['file_id']
    response.headers['Cache-Control'] = 'no-store'
    return response


@app.route('/api/storage/summary', methods=['GET'])
@requires_auth
def api_storage_summary():
    """Thong ke file toan NAS (cache 5 phut). ?force=1 de quet lai ngay."""
    try:
        force = (request.args.get("force", "") or "").strip() == "1"
        now = time.time()
        # CC-004 P1-7: rate-limit ?force=1 theo IP (quet full disk 45s + spin-up HDD).
        if force:
            try:
                cli_ip = _request_client_ip() or "unknown"
            except Exception:
                cli_ip = "unknown"
            try:
                with _STORAGE_SUMMARY_FORCE_LOCK:
                    last = float(_STORAGE_SUMMARY_FORCE_TS.get(cli_ip, 0.0) or 0.0)
                    if now - last < _STORAGE_SUMMARY_FORCE_MIN_INTERVAL:
                        with _STORAGE_SUMMARY_LOCK:
                            cached = _STORAGE_SUMMARY_CACHE["data"]
                        if cached:
                            out = dict(cached)
                            out["cached"] = True
                            out["rate_limited"] = True
                            out["retry_after"] = int(_STORAGE_SUMMARY_FORCE_MIN_INTERVAL - (now - last))
                            return jsonify(out), 429
                    _STORAGE_SUMMARY_FORCE_TS[cli_ip] = now
            except Exception:
                pass
        with _STORAGE_SUMMARY_LOCK:
            cached = _STORAGE_SUMMARY_CACHE["data"]
            if not force and cached and now - _STORAGE_SUMMARY_CACHE["ts"] < _STORAGE_SUMMARY_TTL:
                out = dict(cached)
                out["cached"] = True
                return jsonify(out)
        data = _build_storage_summary()
        with _STORAGE_SUMMARY_LOCK:
            _STORAGE_SUMMARY_CACHE["data"] = data
            _STORAGE_SUMMARY_CACHE["ts"] = time.time()
        out = dict(data)
        out["cached"] = False
        return jsonify(out)
    except Exception as e:
        log.error("[API] Error in api_storage_summary: %s", e)
        return jsonify({"error": str(e)}), 500


_SYSTEM_INSIGHTS_CACHE = {
    "ts": 0.0,
    "data": None,
}
_SYSTEM_INSIGHTS_CACHE_LOCK = threading.Lock()
_SYSTEM_INSIGHTS_CACHE_TTL = 15


def _build_system_insights_snapshot():
    return {
        "health_trend": _disk_health_trend(7),
        "workload": _workload_coordinator(),
        "usb_import": _usb_import_summary_state() if "_usb_import_summary_state" in globals() else {},
        "emmc_guard": _emmc_guard(),
        "data_flow": _data_flow_snapshot(),
        "maintenance": _maintenance_advisor(),
    }


def _refresh_system_insights_cache_locked():
    try:
        data = _build_system_insights_snapshot()
        _SYSTEM_INSIGHTS_CACHE["data"] = data
        _SYSTEM_INSIGHTS_CACHE["ts"] = time.time()
    finally:
        _SYSTEM_INSIGHTS_CACHE_LOCK.release()


@app.route('/api/system/maintenance_advisor', methods=['GET'])
@requires_auth
def api_system_maintenance_advisor():
    return jsonify(_maintenance_advisor())


@app.route('/api/system/insights', methods=['GET'])
@requires_auth
def api_system_insights():
    now = time.time()
    cached = _SYSTEM_INSIGHTS_CACHE.get("data")
    cached_ts = float(_SYSTEM_INSIGHTS_CACHE.get("ts", 0) or 0)
    if cached is None or (now - cached_ts >= _SYSTEM_INSIGHTS_CACHE_TTL):
        if _SYSTEM_INSIGHTS_CACHE_LOCK.acquire(False):
            try:
                _SYSTEM_INSIGHTS_CACHE["data"] = _build_system_insights_snapshot()
                _SYSTEM_INSIGHTS_CACHE["ts"] = now
            finally:
                _SYSTEM_INSIGHTS_CACHE_LOCK.release()

    res = dict(_SYSTEM_INSIGHTS_CACHE.get("data") or _build_system_insights_snapshot())
    res["data_flow"] = _data_flow_snapshot()
    return jsonify(res)


def _invalidate_system_insights_cache():
    _SYSTEM_INSIGHTS_CACHE["ts"] = 0.0


_SLEEP_SCHEDULE_FILE = "/etc/nas/state/sleep_schedule.json"
_SLEEP_SCHEDULE_DEFAULT = {
    "enabled": False,
    "mode": "spindown",       # "spindown" (HDD only) | "suspend" (full NAS)
    "start_hour": 23,         # 0-23
    "end_hour": 7,            # 0-23 — if < start_hour, span overnight
    "idle_only": True,        # only sleep when CPU < 30% + no recording + no backup
    "last_action_ts": 0,
    "last_action_state": "",  # "spindown_active" | "spindown_woken" | etc
}


def _load_sleep_schedule():
    try:
        if os.path.exists(_SLEEP_SCHEDULE_FILE):
            with open(_SLEEP_SCHEDULE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                merged = dict(_SLEEP_SCHEDULE_DEFAULT)
                merged.update(data)
                return merged
    except Exception as e:
        log.warning("[SleepSchedule] Load lỗi: %s", e)
    return dict(_SLEEP_SCHEDULE_DEFAULT)


def _save_sleep_schedule(state):
    try:
        os.makedirs(os.path.dirname(_SLEEP_SCHEDULE_FILE), exist_ok=True)
        tmp = _SLEEP_SCHEDULE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)
        os.replace(tmp, _SLEEP_SCHEDULE_FILE)
        return True
    except Exception as e:
        log.error("[SleepSchedule] Save lỗi: %s", e)
        return False


def _is_in_sleep_window(sched, now=None):
    """Tr? v? True neu thoi diem hien tai nam trong khung gio sleep."""
    if now is None:
        now = datetime.datetime.now()
    start = int(sched.get("start_hour", 23))
    end = int(sched.get("end_hour", 7))
    hour = now.hour
    if start == end:
        return False
    if start < end:
        # vd 13:00 - 17:00 trong ngay
        return start <= hour < end
    else:
        # vd 23:00 - 07:00 qua dem
        return hour >= start or hour < end


def _system_is_idle():
    """Idle khi: CPU < 30% + RAM free > 200 MB + khong co recording + khong co backup."""
    try:
        cpu = psutil.cpu_percent(interval=0.5)
        if cpu > 30: return False
    except Exception:
        pass
    try:
        mem = psutil.virtual_memory()
        if mem.available < 200 * 1024 * 1024: return False
    except Exception:
        pass
    # Không c? recording
    try:
        with _livestream_lock:
            for j in _livestream_jobs.values():
                if j.get("status") == "recording":
                    return False
    except Exception:
        pass
    return True


def _hdd_spindown():
    """Spindown ổ dữ liệu NAS bằng hdparm -y. Trả (ok, msg)."""
    try:
        dev = _target_hdd_device_path()
        if not dev:
            return False, "Không xác nhận được HDD N300 chính"
        r = subprocess.run(["hdparm", "-y", dev], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if r.returncode == 0:
            return True, "spundown OK"
        return False, (r.stderr or b"").decode("utf-8", errors="ignore")[:200]
    except Exception as e:
        return False, str(e)[:200]


def _hdd_get_power_state():
    """Đọc hdparm -C ổ dữ liệu NAS → 'active/idle', 'standby', 'sleeping'."""
    try:
        dev = _target_hdd_device_path()
        if not dev:
            return "unknown"
        r = subprocess.run(["hdparm", "-C", dev], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        if r.returncode == 0:
            out = (r.stdout or b"").decode("utf-8", errors="ignore")
            for line in out.splitlines():
                if "drive state is:" in line:
                    return line.split(":", 1)[1].strip()
    except Exception:
        pass
    return "unknown"


def _sleep_schedule_worker():
    """Daemon: kiểm tra mỗi 60s, trigger sleep neu dieu kien dat."""
    time.sleep(120)  # cho server on dinh
    while True:
        try:
            sched = _load_sleep_schedule()
            if not sched.get("enabled"):
                time.sleep(60)
                continue
            in_window = _is_in_sleep_window(sched)
            if not in_window:
                time.sleep(60)
                continue
            # In window — kiểm tra dieu kien idle
            if sched.get("idle_only", True) and not _system_is_idle():
                # Busy — b? qua tick nay, check lai sau 60s
                time.sleep(60)
                continue
            # Trigger sleep action
            mode = sched.get("mode", "spindown")
            if mode == "spindown":
                # Chi spindown neu HDD dang quay
                state = _hdd_get_power_state()
                if "standby" in state or "sleeping" in state:
                    # để spindown r?i, skip
                    time.sleep(60)
                    continue
                ok, msg = _hdd_spindown()
                sched["last_action_ts"] = int(time.time())
                sched["last_action_state"] = "spindown_active" if ok else "spindown_failed: " + msg
                _save_sleep_schedule(sched)
                if ok:
                    _add_system_log("INFO", "SleepSchedule", "HDD spindown thành công (giờ %d-%d)" % (sched.get("start_hour"), sched.get("end_hour")))
                else:
                    _add_system_log("WARNING", "SleepSchedule", "HDD spindown lỗi: %s" % msg)
            elif mode == "suspend":
                # Full suspend - dùng systemctl
                sched["last_action_ts"] = int(time.time())
                sched["last_action_state"] = "suspend_initiated"
                _save_sleep_schedule(sched)
                _add_system_log("INFO", "SleepSchedule", "NAS suspend (full) gio %d-%d. Wake bang WoL." % (sched.get("start_hour"), sched.get("end_hour")))
                # Don't actually suspend — too aggressive; user must opt in via explicit endpoint
                # subprocess.run(["systemctl", "suspend"])
            time.sleep(60)
        except Exception as e:
            log.error("[SleepSchedule] Worker lỗi: %s", e)
            time.sleep(60)


@app.route('/api/system/sleep_schedule', methods=['GET'])
@requires_auth
def api_sleep_schedule_get():
    sched = _load_sleep_schedule()
    sched["current_hdd_state"] = _hdd_get_power_state()
    sched["in_window_now"] = _is_in_sleep_window(sched)
    return jsonify(sched)


@app.route('/api/system/sleep_schedule', methods=['POST'])
@requires_auth
def api_sleep_schedule_set():
    body = request.get_json(force=True) or {}
    sched = _load_sleep_schedule()
    for key in ("enabled", "mode", "start_hour", "end_hour", "idle_only"):
        if key in body:
            sched[key] = body[key]
    sched["enabled"] = bool(sched.get("enabled"))
    sched["idle_only"] = bool(sched.get("idle_only", True))
    if sched.get("mode") not in ("spindown", "suspend"):
        sched["mode"] = "spindown"
    try: sched["start_hour"] = max(0, min(23, int(sched.get("start_hour", 23))))
    except Exception: sched["start_hour"] = 23
    try: sched["end_hour"] = max(0, min(23, int(sched.get("end_hour", 7))))
    except Exception: sched["end_hour"] = 7
    ok = _save_sleep_schedule(sched)
    return jsonify({"saved": ok, "schedule": sched})


@app.route('/api/system/hdd_spindown_now', methods=['POST'])
@requires_auth
def api_hdd_spindown_now():
    """Manual trigger: spindown ngay (test button)."""
    ok, msg = _hdd_spindown()
    state = _hdd_get_power_state()
    return jsonify({"ok": ok, "msg": msg, "hdd_state": state})


# ============================================================================
# SCHEDULED AUTO-BACKUP — Backup theo lich + retention + rclone OneDrive
# ============================================================================
_BACKUP_SCHEDULE_FILE = "/etc/nas/state/backup_schedule.json"
_BACKUP_SCHEDULE_DEFAULT = {
    "enabled": False,
    "frequency": "weekly",   # daily | weekly | monthly
    "hour": 3,               # 0-23, gio chay (1 gio rieng dem)
    "retention_count": 7,    # giu N backup gần nh?t
    "rclone_remote": "",     # vd "onedrive:" — empty = khong upload
    "rclone_path": "/NASBackup/",  # path tren remote
    "last_run_ts": 0,
    "last_run_result": "",   # "success" | "failed: <msg>"
    "last_run_file": "",
}


def _load_backup_schedule():
    try:
        if os.path.exists(_BACKUP_SCHEDULE_FILE):
            with open(_BACKUP_SCHEDULE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                merged = dict(_BACKUP_SCHEDULE_DEFAULT)
                merged.update(data)
                return merged
    except Exception as e:
        log.warning("[BackupSchedule] Load lỗi: %s", e)
    return dict(_BACKUP_SCHEDULE_DEFAULT)


def _save_backup_schedule(state):
    try:
        os.makedirs(os.path.dirname(_BACKUP_SCHEDULE_FILE), exist_ok=True)
        tmp = _BACKUP_SCHEDULE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)
        os.replace(tmp, _BACKUP_SCHEDULE_FILE)
        return True
    except Exception as e:
        log.error("[BackupSchedule] Save lỗi: %s", e)
        return False


def _create_backup_tarball():
    """Tạo 1 backup tar.gz, return path. Tách ra để scheduled job dùng lại."""
    if not _ensure_backup_dir():
        raise IOError("Không tạo được thư mục backup")
    timestamp = datetime.datetime.now().strftime("%d%m%Y %H%M%S")
    filename = "Backup_NAS %s.tar.gz" % timestamp
    full_path = os.path.join(_BACKUP_DIR, filename)
    manifest = {
        "created_at": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
        "hostname": os.uname()[1] if hasattr(os, "uname") else "unknown",
        "webdav_root": WEBDAV_FILE_ROOT,
        "scheduled": True,
        "files": [],
    }
    all_files = list(_BACKUP_FILES) + _backup_dynamic_files()
    with _open_private_backup_tar(full_path) as tar:
        for src, arcname, _crit in all_files:
            if not os.path.exists(src): continue
            try:
                tar.add(src, arcname=arcname)
                sz = 0
                try: sz = os.path.getsize(src)
                except Exception as e: log.debug("[M4] Ignored exception: %s", e)
                manifest["files"].append({"src": src, "archive_path": arcname, "size": sz})
            except Exception as e:
                log.warning("[Backup] Skip %s: %s", src, e)
        manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8")
        info = tarfile.TarInfo(name="manifest.json")
        info.size = len(manifest_bytes)
        info.mtime = int(time.time())
        import io as _io
        tar.addfile(info, _io.BytesIO(manifest_bytes))
    return full_path, filename


def _apply_backup_retention(keep_count):
    """Xo? cac backup cu, chi giu N file gần nh?t."""
    try:
        items = []
        for name in os.listdir(_BACKUP_DIR):
            if not name.startswith("Backup_NAS"): continue
            full = os.path.join(_BACKUP_DIR, name)
            try:
                items.append((os.path.getmtime(full), full))
            except Exception:
                continue
        items.sort(reverse=True)
        for _mtime, path in items[keep_count:]:
            try:
                os.remove(path)
                log.info("[BackupSchedule] Retention: xoá %s", os.path.basename(path))
            except Exception as e:
                log.warning("[BackupSchedule] Không xoá được %s: %s", path, e)
    except Exception as e:
        log.warning("[BackupSchedule] Retention lỗi: %s", e)


def _rclone_upload_backup(local_path, remote, remote_path):
    """Upload 1 backup file len rclone remote. Tra (ok, msg)."""
    if not remote:
        return True, "skip — chưa cấu hình rclone remote"
    remote_clean = str(remote or "").strip().rstrip(":")
    path_clean = str(remote_path or "").strip().lstrip("/")
    if not _re_module.match(r"^[A-Za-z0-9_\-]{1,64}$", remote_clean):
        return False, "rclone remote không hợp lệ"
    if path_clean.startswith("-") or ".." in path_clean.split("/"):
        return False, "rclone path không hợp lệ"
    rclone_bin = "/usr/bin/rclone"
    if not os.path.exists(rclone_bin):
        return False, "rclone không được cai"
    try:
        full_remote = remote_clean + ":" + path_clean
        r = subprocess.run(
            [rclone_bin, "copy", "--quiet", "--timeout=300s", "--", local_path, full_remote],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600
        )
        if r.returncode == 0:
            return True, "uploaded to " + full_remote
        return False, (r.stderr or b"").decode("utf-8", errors="ignore")[:200]
    except Exception as e:
        return False, str(e)[:200]


def _scheduled_backup_worker():
    """Daemon kiểm tra lịch mỗi 5 phút, chạy backup đúng giờ."""
    time.sleep(60)
    while True:
        try:
            sched = _load_backup_schedule()
            if not sched.get("enabled"):
                time.sleep(300)
                continue
            now = datetime.datetime.now()
            last_ts = int(sched.get("last_run_ts", 0))
            last_dt = datetime.datetime.fromtimestamp(last_ts) if last_ts > 0 else None
            should_run = False
            target_hour = int(sched.get("hour", 3))
            freq = sched.get("frequency", "weekly")
            if now.hour == target_hour and (last_dt is None or last_dt.date() != now.date()):
                # để t?i gi? chay va ch?a chay hom nay
                if freq == "daily":
                    should_run = True
                elif freq == "weekly":
                    # Chay vao chu nhat (weekday=6)
                    if now.weekday() == 6:
                        should_run = True
                elif freq == "monthly":
                    # Chay vao ngay 1
                    if now.day == 1:
                        should_run = True
            if should_run:
                log.info("[BackupSchedule] Trigger backup theo lich (%s)", freq)
                try:
                    path, fname = _create_backup_tarball()
                    sched["last_run_result"] = "success"
                    sched["last_run_file"] = fname
                    _apply_backup_retention(int(sched.get("retention_count", 7)))
                    if sched.get("rclone_remote"):
                        ok, msg = _rclone_upload_backup(path, sched["rclone_remote"], sched.get("rclone_path", "/NASBackup/"))
                        sched["last_run_result"] = "success — " + msg if ok else "uploaded fail: " + msg
                    _add_system_log("SUCCESS", "BackupSchedule",
                        "Backup theo lich xong: %s" % fname)
                except Exception as e:
                    sched["last_run_result"] = "failed: " + str(e)[:200]
                    _add_system_log("ERROR", "BackupSchedule",
                        "Backup theo lịch lỗi: %s" % str(e)[:200])
                sched["last_run_ts"] = int(time.time())
                _save_backup_schedule(sched)
            time.sleep(300)  # check mỗi 5 phút
        except Exception as e:
            log.error("[BackupSchedule] Worker lỗi: %s", e)
            time.sleep(300)


# ============================================================================
# USB IMPORT - tu phat hien o USB va copy vao NAS
# ============================================================================
_USB_IMPORT_SETTINGS_FILE = "/etc/nas/state/usb_import_settings.json"
_USB_IMPORT_STATE_FILE = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "usb_import_state.json")
_USB_IMPORT_HISTORY_FILE = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "usb_import_history.json")
_USB_IMPORT_ALLOWED_FS = set(["exfat", "ntfs", "ntfs3", "vfat", "fat32", "ext2", "ext3", "ext4"])
_USB_IMPORT_SKIP_MOUNT_PREFIXES = (
    "/boot", "/dev", "/proc", "/run", "/sys", "/tmp", "/var",
    "/srv/dev-disk-by-label-data", WEBDAV_FILE_ROOT,
)
_usb_import_lock = threading.Lock()
_usb_import_cancel = threading.Event()
_usb_import_running = False
_usb_import_state = {
    "enabled": True,
    "status": "idle",
    "message": "Đang chờ ổ USB.",
    "active_device": "",
    "active_mount": "",
    "dest_dir": os.path.join(WEBDAV_FILE_ROOT, "USB Import"),
    "started_at": 0,
    "finished_at": 0,
    "files_total": 0,
    "files_done": 0,
    "files_skipped": 0,
    "files_failed": 0,
    "bytes_done": 0,
    "bytes_processed": 0,
    "bytes_total": 0,
    "current_file": "",
    "current_source": "",
    "current_dest": "",
    "current_file_bytes_done": 0,
    "current_file_bytes_total": 0,
    "copy_speed_bps": 0,
    "eta_seconds": 0,
    "last_progress_at": 0,
    "last_error": "",
    "active_id": "",
    "session_id": "",
    "resume_enabled": True,
    "detected_devices": [],
    "seen_devices": [],
    "pending_conflicts": [],
    "pending_conflicts_count": 0,
    "pending_errors": [],
    "pending_errors_count": 0,
    "needs_action": False,
    "plan_file": "",
    "plan_index": 0,
    "conflict_file": "",
}


def _usb_import_try_mark_running():
    global _usb_import_running
    with _usb_import_lock:
        if _usb_import_running:
            return False
        _usb_import_running = True
        return True


def _usb_import_mark_stopped():
    global _usb_import_running
    with _usb_import_lock:
        _usb_import_running = False


def _usb_import_load_settings():
    settings = {
        "enabled": True,
        "dest_folder": "USB Import",
        "copy_mode": "new_only",
        "auto_mount": True,
        "mount_readonly": True,
        "poll_seconds": 15,
        "resume_enabled": True,
        "verify_checksum": False,
    }
    try:
        if os.path.exists(_USB_IMPORT_SETTINGS_FILE):
            with open(_USB_IMPORT_SETTINGS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                settings.update(data)
    except Exception as e:
        log.warning("[USBImport] Load settings lỗi: %s", e)
    settings["enabled"] = bool(settings.get("enabled", True))
    settings["auto_mount"] = bool(settings.get("auto_mount", True))
    settings["mount_readonly"] = bool(settings.get("mount_readonly", True))
    settings["resume_enabled"] = bool(settings.get("resume_enabled", True))
    settings["verify_checksum"] = bool(settings.get("verify_checksum", False))
    settings["dest_folder"] = _re_module.sub(r"[\\/:*?\"<>|]+", "_", str(settings.get("dest_folder") or "USB Import")).strip() or "USB Import"
    if settings.get("copy_mode") not in ("new_only", "overwrite"):
        settings["copy_mode"] = "new_only"
    try:
        settings["poll_seconds"] = max(5, min(300, int(settings.get("poll_seconds", 15))))
    except Exception:
        settings["poll_seconds"] = 15
    return settings


def _usb_import_save_settings(settings):
    try:
        os.makedirs(os.path.dirname(_USB_IMPORT_SETTINGS_FILE), exist_ok=True)
        tmp = _USB_IMPORT_SETTINGS_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(settings, f, ensure_ascii=False, indent=2)
        os.replace(tmp, _USB_IMPORT_SETTINGS_FILE)
        return True
    except Exception as e:
        log.error("[USBImport] Save settings lỗi: %s", e)
        return False


_usb_import_last_save = 0.0

def _usb_import_load_state():
    global _usb_import_state
    try:
        saved_db = _db_get_json("runtime_state", "usb_import_state", None)
        if isinstance(saved_db, dict):
            with _usb_import_lock:
                _usb_import_state.update(saved_db)
            log.info("[USBImport] Đã tải trạng thái từ SQLite runtime_state")
        elif os.path.exists(_USB_IMPORT_STATE_FILE):
            with open(_USB_IMPORT_STATE_FILE, "r", encoding="utf-8") as f:
                saved = json.load(f)
                with _usb_import_lock:
                    _usb_import_state.update(saved)
            log.info("[USBImport] Đã tải trạng thái từ %s", _USB_IMPORT_STATE_FILE)
    except Exception as e:
        log.warning("[USBImport] Không thể đọc state: %s", e)

def _usb_import_save_state():
    global _usb_import_last_save
    now = time.time()
    if now - _usb_import_last_save < 3.0:
        return
    _usb_import_last_save = now
    try:
        os.makedirs(os.path.dirname(_USB_IMPORT_STATE_FILE), exist_ok=True)
        tmp = _USB_IMPORT_STATE_FILE + ".tmp"
        with _usb_import_lock:
            payload = dict(_usb_import_state)
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        os.replace(tmp, _USB_IMPORT_STATE_FILE)
        _db_set_json("runtime_state", "usb_import_state", payload)
    except Exception as e:
        log.warning("[USBImport] Save state lỗi: %s", e)


def _usb_import_load_history():
    try:
        saved_db = _db_get_json("runtime_state", "usb_import_history", None)
        if isinstance(saved_db, list):
            return saved_db[-50:]
        if os.path.exists(_USB_IMPORT_HISTORY_FILE):
            with open(_USB_IMPORT_HISTORY_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                return data[-50:]
    except Exception as e:
        log.warning("[USBImport] Load history lỗi: %s", e)
    return []


def _usb_import_add_history(record):
    try:
        os.makedirs(os.path.dirname(_USB_IMPORT_HISTORY_FILE), exist_ok=True)
        items = _usb_import_load_history()
        items.append(record)
        tmp = _USB_IMPORT_HISTORY_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(items[-50:], f, ensure_ascii=False, indent=2)
        os.replace(tmp, _USB_IMPORT_HISTORY_FILE)
        _db_set_json("runtime_state", "usb_import_history", items[-50:])
    except Exception as e:
        log.warning("[USBImport] Save history lỗi: %s", e)


def _usb_import_set_state(**kwargs):
    # Không bao giờ giữ toàn bộ danh sách trùng/lỗi trong state runtime.
    # USB import có thể gặp hàng chục nghìn file trùng; nếu nhét hết vào state rồi
    # JSON dump mỗi vài giây thì Python ăn CPU/RAM và các API status bị timeout.
    conflicts = kwargs.get("pending_conflicts")
    if isinstance(conflicts, list):
        kwargs["pending_conflicts_count"] = int(kwargs.get("pending_conflicts_count") or len(conflicts))
        kwargs["pending_conflicts"] = conflicts[:200]
    errors = kwargs.get("pending_errors")
    if isinstance(errors, list):
        kwargs["pending_errors_count"] = int(kwargs.get("pending_errors_count") or len(errors))
        kwargs["pending_errors"] = errors[-200:]
    with _usb_import_lock:
        _usb_import_state.update(kwargs)
        state_summary = {
            "status": _usb_import_state.get("status", ""),
            "message": _usb_import_state.get("message", ""),
            "active_device": _usb_import_state.get("active_device", ""),
            "active_mount": _usb_import_state.get("active_mount", ""),
            "dest_dir": _usb_import_state.get("dest_dir", ""),
            "files_done": int(_usb_import_state.get("files_done") or 0),
            "files_total": int(_usb_import_state.get("files_total") or 0),
            "files_failed": int(_usb_import_state.get("files_failed") or 0),
            "files_skipped": int(_usb_import_state.get("files_skipped") or 0),
            "bytes_done": int(_usb_import_state.get("bytes_done") or 0),
            "bytes_total": int(_usb_import_state.get("bytes_total") or 0),
            "copy_speed_bps": int(_usb_import_state.get("copy_speed_bps") or 0),
            "pending_conflicts_count": int(_usb_import_state.get("pending_conflicts_count") or 0),
            "pending_errors_count": int(_usb_import_state.get("pending_errors_count") or 0),
            "last_error": _usb_import_state.get("last_error", ""),
            "last_progress_at": int(_usb_import_state.get("last_progress_at") or 0),
        }
    _usb_import_save_state()
    try:
        _update_process_state("usb_import", **state_summary)
    except Exception:
        pass


def _usb_import_public_state(compact=False):
    settings = _usb_import_load_settings() if not compact else {}
    with _usb_import_lock:
        state = dict(_usb_import_state)
    if int(state.get("files_total") or 0) <= 0:
        visible_total = int(state.get("files_done") or 0) + int(state.get("files_skipped") or 0) + int(state.get("files_failed") or 0)
        state["files_total"] = visible_total
    if int(state.get("bytes_total") or 0) <= 0:
        current_total = int(state.get("current_file_bytes_total") or 0)
        processed = int(state.get("bytes_processed") or 0)
        state["bytes_total"] = processed + max(0, current_total - int(state.get("current_file_bytes_done") or 0))
    actual_dest = state.get("dest_dir", "")
    if not compact:
        state["settings"] = settings
        state["history"] = _usb_import_load_history()[-10:]
    pending_conflicts = state.get("pending_conflicts") or []
    pending_errors = state.get("pending_errors") or []
    state["pending_conflicts_count"] = int(state.get("pending_conflicts_count") or (len(pending_conflicts) if isinstance(pending_conflicts, list) else 0))
    state["pending_errors_count"] = int(state.get("pending_errors_count") or (len(pending_errors) if isinstance(pending_errors, list) else 0))
    if compact:
        state["pending_conflicts"] = []
        state["pending_errors"] = []
        state["detected_devices"] = []
        settings_dest = _USB_IMPORT_SETTINGS_FILE and "USB Import"
        state["dest_dir"] = state.get("dest_dir") or os.path.join(WEBDAV_FILE_ROOT, settings_dest)
        return state
    if isinstance(pending_conflicts, list) and len(pending_conflicts) > 200:
        state["pending_conflicts"] = pending_conflicts[:200]
    if isinstance(pending_errors, list) and len(pending_errors) > 200:
        state["pending_errors"] = pending_errors[:200]
    detected_devices = state.get("detected_devices")
    if isinstance(detected_devices, list):
        state["detected_devices"] = [
            d for d in detected_devices
            if isinstance(d, dict) and not _usb_import_is_target_hdd_node(d)
        ][:12]
    state["dest_dir"] = os.path.join(WEBDAV_FILE_ROOT, settings.get("dest_folder", "USB Import"))
    if state.get("status") in ("copying", "cancelled", "error", "done", "needs_action") and actual_dest:
        state["dest_dir"] = actual_dest
    return state


def _usb_import_summary_state():
    """Small USB import snapshot for dashboard insights; avoids huge conflict lists."""
    state = _usb_import_public_state()
    for key in ("pending_conflicts", "pending_errors", "history"):
        if key in state and isinstance(state.get(key), list):
            state[key] = state[key][:5]
    return state


def _usb_import_is_safe_mount(mountpoint):
    if not mountpoint:
        return False
    try:
        real = os.path.realpath(mountpoint)
        webdav_real = os.path.realpath(WEBDAV_FILE_ROOT)
        if real == webdav_real or real.startswith(webdav_real + os.sep):
            return False
        for prefix in _USB_IMPORT_SKIP_MOUNT_PREFIXES:
            prefix_real = os.path.realpath(prefix)
            if real == prefix_real or real.startswith(prefix_real.rstrip("/") + os.sep):
                return False
        return os.path.isdir(real)
    except Exception:
        return False


def _usb_import_lsblk():
    columns = [
        "NAME,PATH,TYPE,TRAN,HOTPLUG,RM,FSTYPE,LABEL,UUID,MOUNTPOINTS,SIZE,MODEL,SERIAL",
        "NAME,PATH,TYPE,TRAN,FSTYPE,LABEL,UUID,MOUNTPOINTS,SIZE,MODEL,SERIAL",
    ]
    try:
        for cols in columns:
            r = subprocess.run(
                ["lsblk", "-J", "-o", cols],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10
            )
            if r.returncode != 0:
                continue
            data = json.loads(r.stdout.decode("utf-8", errors="ignore") or "{}")
            return _usb_import_normalize_lsblk_nodes(data.get("blockdevices", []) if isinstance(data, dict) else [])
        r = subprocess.run(["lsblk", "-J"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if r.returncode == 0:
            data = json.loads(r.stdout.decode("utf-8", errors="ignore") or "{}")
            return _usb_import_normalize_lsblk_nodes(data.get("blockdevices", []) if isinstance(data, dict) else [])
    except Exception as e:
        log.warning("[USBImport] lsblk lỗi: %s", e)
    return []


def _usb_import_normalize_lsblk_nodes(nodes):
    normalized = []
    for node in nodes or []:
        item = dict(node)
        name = str(item.get("name") or "")
        if name and not item.get("path"):
            item["path"] = "/dev/%s" % name
        if "mountpoint" in item and "mountpoints" not in item:
            item["mountpoints"] = [item.get("mountpoint")] if item.get("mountpoint") else []
        item["children"] = _usb_import_normalize_lsblk_nodes(item.get("children") or [])
        normalized.append(item)
    return normalized


def _usb_import_blkid_info(dev_path):
    info = {}
    if not dev_path:
        return info
    try:
        r = subprocess.run(["blkid", dev_path], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=8)
        if r.returncode != 0:
            return info
        text = (r.stdout or b"").decode("utf-8", errors="ignore")
        for key, val in _re_module.findall(r'([A-Z0-9_]+)="([^"]*)"', text):
            key_l = key.lower()
            if key_l == "type":
                info["fstype"] = val.lower()
            elif key_l in ("label", "uuid", "partuuid", "partlabel"):
                info[key_l] = val
    except Exception as e:
        log.warning("[USBImport] blkid %s lỗi: %s", dev_path, e)
    return info


def _usb_import_sysfs_is_usb(dev_name):
    if not dev_name:
        return False
    name = os.path.basename(str(dev_name)).strip()
    if not name:
        return False
    try:
        real = os.path.realpath(os.path.join("/sys/class/block", name))
        return "/usb" in real.lower() or "/usb" in real.replace("\\", "/").lower()
    except Exception:
        return False


def _usb_import_node_has_usb_signal(node, parent_usb=False):
    tran = str(node.get("tran") or "").lower()
    hotplug = str(node.get("hotplug") or "").lower()
    removable = str(node.get("rm") or "").lower()
    name = node.get("name") or os.path.basename(str(node.get("path") or ""))
    return (
        parent_usb
        or tran == "usb"
        or hotplug in ("1", "true", "yes")
        or removable in ("1", "true", "yes")
        or _usb_import_sysfs_is_usb(name)
    )


def _usb_import_is_target_hdd_node(node):
    path = str(node.get("path") or "")
    name = str(node.get("name") or os.path.basename(path) or "")
    dev_path = path or ("/dev/%s" % name if name else "")
    parent = _parent_disk_from_device(dev_path)
    target = _target_hdd_device_path()
    return bool(target and (dev_path == target or parent == target))


def _usb_import_flatten_devices(nodes, parent_usb=False):
    out = []
    for node in nodes or []:
        if _usb_import_is_target_hdd_node(node):
            continue
        is_usb = _usb_import_node_has_usb_signal(node, parent_usb)
        if node.get("type") in ("part", "disk") and is_usb:
            out.append(node)
        out.extend(_usb_import_flatten_devices(node.get("children") or [], is_usb))
    return out


def _usb_import_mount_device(dev, settings):
    mountpoints = dev.get("mountpoints") or []
    if isinstance(mountpoints, str):
        mountpoints = [mountpoints]
    for mnt in mountpoints:
        if _usb_import_is_safe_mount(mnt):
            return mnt, False
    if any(mountpoints):
        return "", False
    if not settings.get("auto_mount"):
        return "", False
    dev_path = dev.get("path") or ""
    fs_type = str(dev.get("fstype") or "").lower()
    if dev_path and not fs_type:
        blkid_info = _usb_import_blkid_info(dev_path)
        if blkid_info:
            dev.update(blkid_info)
            fs_type = str(dev.get("fstype") or "").lower()
    uuid_value = str(dev.get("uuid") or dev.get("name") or uuid.uuid4().hex)
    if not dev_path or fs_type not in _USB_IMPORT_ALLOWED_FS:
        return "", False
    safe_name = _re_module.sub(r"[^A-Za-z0-9_.-]+", "_", uuid_value).strip("_") or "usb"
    mountpoint = os.path.join("/mnt/usb-import", safe_name)
    try:
        os.makedirs(mountpoint, exist_ok=True)
        opts = "nosuid,nodev,noexec"
        if settings.get("mount_readonly", True):
            opts = "ro," + opts
        r = subprocess.run(["mount", "-o", opts, dev_path, mountpoint],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        if r.returncode == 0 and _usb_import_is_safe_mount(mountpoint):
            log.info("[USBImport] Mounted %s tai %s", dev_path, mountpoint)
            return mountpoint, True
        err = (r.stderr or b"").decode("utf-8", errors="ignore")[:200]
        log.warning("[USBImport] Mount %s lỗi: %s", dev_path, err)
    except Exception as e:
        log.warning("[USBImport] Mount exception %s: %s", dev_path, e)
    return "", False


def _usb_import_find_candidates(settings):
    candidates = []
    nodes = _usb_import_lsblk()
    flattened = _usb_import_flatten_devices(nodes)
    detected = []
    for dev in flattened:
        fs_type = str(dev.get("fstype") or "").lower()
        if (not fs_type) and dev.get("path"):
            blkid_info = _usb_import_blkid_info(dev.get("path"))
            if blkid_info:
                dev.update(blkid_info)
                fs_type = str(dev.get("fstype") or "").lower()
        info = {
            "path": dev.get("path") or "",
            "type": dev.get("type") or "",
            "tran": dev.get("tran") or "",
            "hotplug": dev.get("hotplug") or "",
            "rm": dev.get("rm") or "",
            "fstype": fs_type,
            "label": dev.get("label") or "",
            "size": dev.get("size") or "",
            "model": dev.get("model") or "",
            "reason": "",
        }
        if fs_type and fs_type not in _USB_IMPORT_ALLOWED_FS:
            info["reason"] = "filesystem không hỗ trợ: %s" % fs_type
            detected.append(info)
            continue
        mountpoint, mounted_by_us = _usb_import_mount_device(dev, settings)
        if not mountpoint:
            info["reason"] = "không mount được hoặc chưa có phân vùng/filesystem"
            detected.append(info)
            continue
        ident = str(dev.get("uuid") or dev.get("serial") or dev.get("path") or mountpoint)
        info["reason"] = "hop le"
        detected.append(info)
        candidates.append({
            "id": ident,
            "path": dev.get("path") or "",
            "label": dev.get("label") or "",
            "mountpoint": mountpoint,
            "fstype": fs_type,
            "size": dev.get("size") or "",
            "model": dev.get("model") or "",
            "mounted_by_us": mounted_by_us,
        })
    if not candidates:
        all_count = len(nodes or [])
        usb_count = len(flattened or [])
        msg = "lsblk thấy %d block device, %d có dấu hiệu USB/hotplug/removable." % (all_count, usb_count)
        if detected:
            msg += " " + "; ".join(
                "%s %s %s" % (d.get("path") or "?", d.get("fstype") or "no-fs", d.get("reason") or "")
                for d in detected[:4]
            )
        _usb_import_set_state(detected_devices=detected[:12], last_error=msg)
    else:
        _usb_import_set_state(detected_devices=detected[:12], last_error="")
    return candidates


def _usb_import_scan_files(src_root):
    # Khách hàng yêu cầu bỏ qua vòng lặp đếm tổng số file vì nó quá chậm (tới 10 phút)
    # Trả về 0,0 luôn để copy ngay lập tức. Progress bar sẽ chuyển sang trạng thái indeterminate
    return 0, 0


def _usb_import_plan_paths(dest_base, session_id):
    return (
        os.path.join(dest_base, ".usb_import_plan_%s.jsonl" % session_id),
        os.path.join(dest_base, ".usb_import_plan_%s.meta.json" % session_id),
    )


def _usb_import_write_json_file(path, payload):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def _usb_import_append_jsonl(path, payload):
    if not path:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8", buffering=1024 * 1024) as f:
        f.write(json.dumps(payload, ensure_ascii=False) + "\n")


def _usb_import_load_jsonl(path, limit=0):
    items = []
    if not path or not os.path.exists(path):
        return items
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    item = json.loads(line)
                    if isinstance(item, dict):
                        items.append(item)
                        if limit and len(items) >= limit:
                            break
                except Exception:
                    continue
    except Exception:
        return items
    return items


def _usb_import_write_jsonl(path, items):
    if not path:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", buffering=1024 * 1024) as f:
        for item in items or []:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
    os.replace(tmp, path)


def _usb_import_load_plan_meta(meta_file):
    try:
        if os.path.exists(meta_file):
            with open(meta_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                return int(data.get("files_total") or 0), int(data.get("bytes_total") or 0)
    except Exception:
        pass
    return 0, 0


def _usb_import_format_bytes(value):
    try:
        n = float(value or 0)
    except Exception:
        n = 0.0
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024.0 or unit == "TB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % int(n)
        n /= 1024.0
    return "%d B" % int(value or 0)


def _usb_import_build_or_reuse_plan(src_root, dest_base, session_id, can_resume):
    plan_file, meta_file = _usb_import_plan_paths(dest_base, session_id)
    if can_resume and os.path.exists(plan_file):
        files_total, bytes_total = _usb_import_load_plan_meta(meta_file)
        if files_total <= 0:
            with open(plan_file, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        item = json.loads(line)
                        files_total += 1
                        bytes_total += int(item.get("size") or 0)
                    except Exception:
                        pass
            _usb_import_write_json_file(meta_file, {
                "files_total": files_total,
                "bytes_total": bytes_total,
                "created_at": int(time.time()),
            })
        return plan_file, meta_file, files_total, bytes_total, True

    _usb_import_ensure_dir(dest_base)
    tmp = plan_file + ".tmp"
    files_total = 0
    bytes_total = 0
    _usb_import_set_state(
        status="copying",
        message="Đang rà soát toàn bộ file USB lần đầu để tạo danh sách copy.",
        files_total=0,
        bytes_total=0,
        copy_speed_bps=0,
        eta_seconds=0,
        last_progress_at=int(time.time()),
    )
    last_emit = time.monotonic()
    with open(tmp, "w", encoding="utf-8") as f:
        for root, dirs, files in os.walk(src_root):
            if _usb_import_cancel.is_set():
                raise InterruptedError("USB import cancelled")
            dirs[:] = [d for d in dirs if d not in (".Trash-1000", "$RECYCLE.BIN", "System Volume Information")]
            rel_dir = os.path.relpath(root, src_root)
            if rel_dir == ".":
                rel_dir = ""
            for name in files:
                if _usb_import_cancel.is_set():
                    raise InterruptedError("USB import cancelled")
                src = os.path.join(root, name)
                try:
                    if os.path.islink(src):
                        continue
                    size = os.path.getsize(src)
                except Exception:
                    size = 0
                rel = os.path.join(rel_dir, name) if rel_dir else name
                f.write(json.dumps({"rel": rel, "size": int(size or 0)}, ensure_ascii=False) + "\n")
                files_total += 1
                bytes_total += int(size or 0)
                now = time.monotonic()
                if now - last_emit >= 1.0:
                    _usb_import_set_state(
                        message="Đang rà soát USB: %d file, %s." % (files_total, _usb_import_format_bytes(bytes_total)),
                        files_total=files_total,
                        bytes_total=bytes_total,
                        last_progress_at=int(time.time()),
                    )
                    last_emit = now
    os.replace(tmp, plan_file)
    _usb_import_write_json_file(meta_file, {
        "files_total": files_total,
        "bytes_total": bytes_total,
        "created_at": int(time.time()),
    })
    return plan_file, meta_file, files_total, bytes_total, False


def _usb_import_iter_plan(plan_file, src_root, dest_base, start_index=0):
    with open(plan_file, "r", encoding="utf-8") as f:
        for idx, line in enumerate(f):
            if idx < start_index:
                continue
            try:
                item = json.loads(line)
            except Exception:
                continue
            rel = str(item.get("rel") or "").strip()
            if not rel:
                continue
            size = int(item.get("size") or 0)
            yield idx, os.path.join(src_root, rel), os.path.join(dest_base, rel), rel, size


def _usb_import_plan_prefix_stats(plan_file, stop_index):
    files_done = 0
    bytes_done = 0
    if stop_index <= 0:
        return 0, 0
    try:
        with open(plan_file, "r", encoding="utf-8") as f:
            for idx, line in enumerate(f):
                if idx >= stop_index:
                    break
                try:
                    item = json.loads(line)
                    bytes_done += int(item.get("size") or 0)
                except Exception:
                    pass
                files_done += 1
    except Exception:
        pass
    return files_done, bytes_done


def _usb_import_remove_plan_files(plan_file):
    for path in (plan_file, plan_file + ".tmp"):
        try:
            if path and os.path.exists(path):
                os.remove(path)
        except Exception:
            pass
    if plan_file.endswith(".jsonl"):
        meta_file = plan_file[:-6] + ".meta.json"
        try:
            if os.path.exists(meta_file):
                os.remove(meta_file)
        except Exception:
            pass


def _usb_import_unique_dest(path):
    if not os.path.exists(path):
        return path
    base, ext = os.path.splitext(path)
    for i in range(1, 1000):
        candidate = "%s_copy%d%s" % (base, i, ext)
        if not os.path.exists(candidate):
            return candidate
    return "%s_copy_%s%s" % (base, uuid.uuid4().hex[:8], ext)


def _usb_import_file_size(path):
    try:
        return os.path.getsize(path)
    except Exception:
        return 0


def _usb_import_rel(path, base):
    try:
        return os.path.relpath(path, base)
    except Exception:
        return os.path.basename(path)


def _usb_import_conflict_item(src, dst, dest_base, source_size=None):
    if source_size is None:
        source_size = _usb_import_file_size(src)
    dest_size = _usb_import_file_size(dst)
    return {
        "source": src,
        "dest": dst,
        "rel": _usb_import_rel(dst, dest_base),
        "source_name": os.path.basename(src),
        "dest_name": os.path.basename(dst),
        "source_size": int(source_size or 0),
        "dest_size": int(dest_size or 0),
    }


def _usb_import_error_item(src, dst, dest_base, err, source_size=None):
    if source_size is None:
        source_size = _usb_import_file_size(src)
    return {
        "source": src,
        "dest": dst,
        "rel": _usb_import_rel(dst, dest_base),
        "source_name": os.path.basename(src),
        "dest_name": os.path.basename(dst),
        "source_size": int(source_size or 0),
        "error": str(err)[:300],
    }


def _usb_import_move_partial(dst):
    try:
        if dst and os.path.exists(dst):
            bad_path = dst + ".partial"
            if os.path.exists(bad_path):
                bad_path = bad_path + "." + str(int(time.time()))
            os.rename(dst, bad_path)
    except Exception:
        pass


_usb_import_owner_cache = None

def _usb_import_apply_path_permissions(path, is_dir=False):
    """Áp quyền ngay trên file/thư mục mới tạo, tránh chown/chmod -R cuối phiên."""
    global _usb_import_owner_cache
    if not path:
        return
    try:
        if _usb_import_owner_cache is None:
            import pwd
            import grp
            _usb_import_owner_cache = (
                pwd.getpwnam("daica").pw_uid,
                grp.getgrnam("webdav-users").gr_gid,
            )
        uid, gid = _usb_import_owner_cache
        os.chown(path, uid, gid)
        os.chmod(path, 0o2775 if is_dir else 0o664)
    except Exception:
        pass


def _usb_import_ensure_dir(path):
    if not path:
        return
    existed = os.path.isdir(path)
    os.makedirs(path, exist_ok=True)
    if not existed:
        _usb_import_apply_path_permissions(path, is_dir=True)


def _usb_import_copy_error_message(err, src):
    if getattr(err, "errno", None) == 5:
        return "I/O error khi đọc USB. Kernel đang báo lỗi đọc thiết bị, thường là sector lỗi/ổ USB hỏng hoặc box/cáp rớt kết nối. File đã được bỏ qua: %s" % os.path.basename(src)
    return str(err)


def _usb_import_file_version(path):
    current = os.stat(path)
    return (current.st_dev, current.st_ino, current.st_size,
            current.st_mtime_ns, current.st_ctime_ns)


def _usb_import_copy_file_with_progress(src, dst, totals, overwrite=True):
    if os.path.exists(dst) and os.path.samefile(src, dst):
        raise ValueError('Source and destination refer to the same file')
    if not overwrite and os.path.lexists(dst):
        raise FileExistsError(dst)
    source_version = _usb_import_file_version(src)
    temporary = tempfile.NamedTemporaryFile(prefix='.nas-usb-', dir=os.path.dirname(dst), delete=False)
    staged = temporary.name
    temporary.close()
    try:
        checksum = _usb_import_write_file_with_progress(src, staged, totals)
        if _usb_import_cancel.is_set():
            raise InterruptedError('USB import cancelled')
        if _usb_import_file_version(src) != source_version:
            raise ValueError('Source changed during USB copy')
        if os.path.getsize(staged) != source_version[2]:
            raise IOError('Incomplete USB copy')
        with open(staged, 'r+b') as handle:
            os.fsync(handle.fileno())
        with os.fdopen(_duplicate_open_namespace_lock(False), 'r') as lock:
            if _usb_import_cancel.is_set():
                raise InterruptedError('USB import cancelled')
            if overwrite:
                os.replace(staged, dst)
            else:
                os.link(staged, dst)
                os.unlink(staged)
            if os.name != 'nt':
                parent_fd = os.open(os.path.dirname(dst), os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(parent_fd)
                finally:
                    os.close(parent_fd)
        totals.setdefault("changed_dirs", set()).add(os.path.dirname(dst))
        return checksum
    finally:
        if os.path.exists(staged):
            os.unlink(staged)


def _usb_import_write_file_with_progress(src, dst, totals):
    buf_size = 8 * 1024 * 1024  # 8MB buffer — tối ưu cho USB 3.0 sequential read
    file_size = 0
    try:
        file_size = os.path.getsize(src)
    except Exception:
        file_size = 0
    current_name = os.path.basename(src)
    totals["current_file_done"] = 0
    _usb_import_set_state(
        current_file=current_name,
        current_source=src,
        current_dest=dst,
        current_file_bytes_done=0,
        current_file_bytes_total=file_size,
        last_progress_at=int(time.time()),
    )
    last_emit = time.monotonic()
    # FIX: Dùng rolling window 10s để tính tốc độ thay vì trung bình cộng dồn từ đầu.
    # Trung bình cộng dồn làm speed hiển thị càng lúc càng giảm dù tốc độ thực tế ổn.
    speed_window_bytes = 0
    speed_window_start = time.monotonic()
    SPEED_WINDOW_SEC = 10.0
    # Flush dữ liệu theo cả phiên copy, không fsync từng file. Ép sync mỗi file sẽ làm
    # NAS ARM + HDD chậm nặng khi copy hàng chục nghìn file nhỏ.
    SYNC_EVERY = 512 * 1024 * 1024  # sync nhẹ mỗi 512MB đã ghi
    digest = hashlib.sha256() if totals.get("verify_checksum") else None
    with open(src, "rb") as fin, open(dst, "wb") as fout:
        while True:
            if _usb_import_cancel.is_set():
                raise InterruptedError("USB import cancelled")
            chunk = fin.read(buf_size)
            if not chunk:
                break
            fout.write(chunk)
            if digest is not None:
                digest.update(chunk)
            n = len(chunk)
            totals["bytes_done"] += n
            totals["bytes_processed"] += n
            totals["current_file_done"] += n
            speed_window_bytes += n
            totals["bytes_since_sync"] = totals.get("bytes_since_sync", 0) + n
            if totals["bytes_since_sync"] >= SYNC_EVERY:
                fout.flush()
                if hasattr(os, "fdatasync"):
                    os.fdatasync(fout.fileno())
                else:
                    os.fsync(fout.fileno())
                totals["bytes_since_sync"] = 0
            now = time.monotonic()
            if now - last_emit >= 1.0:
                # Rolling window speed: reset sau mỗi SPEED_WINDOW_SEC
                window_elapsed = now - speed_window_start
                if window_elapsed >= SPEED_WINDOW_SEC:
                    speed = int(speed_window_bytes / window_elapsed)
                    speed_window_bytes = 0
                    speed_window_start = now
                else:
                    speed = int(speed_window_bytes / max(0.001, window_elapsed))
                if totals.get("bytes_total", 0) > 0:
                    remaining = max(0, totals["bytes_total"] - totals["bytes_processed"])
                else:
                    remaining = max(0, file_size - totals.get("current_file_done", 0))
                eta = int(remaining / speed) if speed > 0 else 0
                _usb_import_set_state(
                    files_done=totals["done"],
                    files_skipped=totals["skipped"],
                    files_failed=totals["failed"],
                    bytes_done=totals["bytes_done"],
                    bytes_processed=totals["bytes_processed"],
                    current_file_bytes_done=totals["current_file_done"],
                    copy_speed_bps=speed,
                    eta_seconds=eta,
                    last_progress_at=int(time.time()),
                )
                last_emit = now
        # Đẩy buffer Python ra kernel; để kernel gom flush tối ưu thay vì fsync từng file.
        fout.flush()
    try:
        shutil.copystat(src, dst, follow_symlinks=True)
    except Exception:
        pass
    _usb_import_set_state(
        current_file_bytes_done=file_size,
        bytes_done=totals["bytes_done"],
        bytes_processed=totals["bytes_processed"],
        last_progress_at=int(time.time()),
    )
    return digest.hexdigest() if digest is not None else ""


def _usb_import_copy_tree(candidate, settings):
    global _usb_import_running
    src_root = candidate["mountpoint"]
    ident = candidate.get("id") or candidate.get("path") or src_root
    label = candidate.get("label") or os.path.basename(src_root.rstrip("/")) or "USB"
    safe_label = _re_module.sub(r"[^A-Za-z0-9_. -]+", "_", label).strip() or "USB"
    with _usb_import_lock:
        prev_dest = _usb_import_state.get("dest_dir", "")
        prev_id = _usb_import_state.get("active_id", "")
        prev_session = _usb_import_state.get("session_id", "")
        prev_status = _usb_import_state.get("status", "")
        prev_plan_index = int(_usb_import_state.get("plan_index") or 0)
        prev_pending_conflicts = list(_usb_import_state.get("pending_conflicts") or [])
        prev_pending_errors = list(_usb_import_state.get("pending_errors") or [])
    dest_base = os.path.join(WEBDAV_FILE_ROOT, settings.get("dest_folder", "USB Import"), safe_label)
    copy_mode = settings.get("copy_mode", "new_only")
    session_id = hashlib.sha1(("%s|%s|%s" % (ident, dest_base, copy_mode)).encode("utf-8", errors="ignore")).hexdigest()[:12]
    can_resume = bool(settings.get("resume_enabled", True) and prev_id == ident and
                      prev_status in ("copying", "cancelled", "error") and prev_dest and
                      os.path.realpath(prev_dest) == os.path.realpath(dest_base) and
                      prev_session == session_id and os.path.isdir(prev_dest))
    plan_file, plan_meta_file, files_total, bytes_total, plan_reused = _usb_import_build_or_reuse_plan(src_root, dest_base, session_id, can_resume)
    conflict_file = os.path.join(dest_base, ".usb_import_conflicts_%s.jsonl" % session_id)
    if not plan_reused:
        try:
            if os.path.exists(conflict_file):
                os.remove(conflict_file)
        except Exception:
            pass
    start_index = prev_plan_index if plan_reused else 0
    resume_files_done, resume_bytes_done = _usb_import_plan_prefix_stats(plan_file, start_index)
    pending_conflicts = prev_pending_conflicts if plan_reused else []
    conflict_count = int(_usb_import_state.get("pending_conflicts_count") or len(pending_conflicts)) if plan_reused else len(pending_conflicts)
    pending_errors = prev_pending_errors if plan_reused else []
    _usb_import_set_state(
        status="copying", message="Đang copy tiếp dữ liệu từ USB." if can_resume else "Đang copy dữ liệu từ USB.",
        active_device=candidate.get("path", ""), active_mount=src_root, dest_dir=dest_base,
        active_id=ident, session_id=session_id, resume_enabled=bool(settings.get("resume_enabled", True)),
        started_at=int(time.time()), finished_at=0, files_total=files_total,
        files_done=resume_files_done, files_skipped=0, files_failed=0, bytes_done=resume_bytes_done,
        bytes_processed=resume_bytes_done, bytes_total=bytes_total,
        current_file="", current_source="", current_dest="",
        current_file_bytes_done=0, current_file_bytes_total=0,
        copy_speed_bps=0, eta_seconds=0, last_progress_at=int(time.time()),
        last_error="", pending_conflicts=pending_conflicts, pending_conflicts_count=conflict_count,
        pending_errors=pending_errors, pending_errors_count=len(pending_errors), needs_action=False,
        plan_file=plan_file, plan_index=start_index, conflict_file=conflict_file
    )
    os.makedirs(dest_base, exist_ok=True)
    # Block thumbnail generator trong lúc copy USB để tránh tranh giành CPU/IO
    # (ffmpeg tạo thumbnail cũng đọc file vừa được copy → copy chậm như rùa)
    _set_thumbnail_auto_block("usb_import", True)
    log.info("[USBImport] Đã tạm dừng thumbnail generator trong lúc copy USB.")
    done = resume_files_done
    skipped = failed = 0
    bytes_done = resume_bytes_done
    bytes_processed = resume_bytes_done
    totals = {
        "done": done,
        "skipped": 0,
        "failed": 0,
        "bytes_done": bytes_done,
        "bytes_processed": bytes_processed,
        "bytes_total": bytes_total,
        "current_file_done": 0,
        "speed_started_at": time.monotonic(),
        "speed_start_bytes": 0,
        "bytes_since_sync": 0,
        "verify_checksum": bool(settings.get("verify_checksum", False)),
    }
    manifest_handle = None
    conflict_handle = None
    retry_queue = []
    pending_error_count = len(pending_errors)
    try:
        if totals["verify_checksum"]:
            manifest_handle = open(os.path.join(dest_base, ".usb_import_manifest.jsonl"), "a", encoding="utf-8", buffering=1024 * 1024)
        conflict_handle = open(conflict_file, "a", encoding="utf-8", buffering=1024 * 1024)
        last_conflict_emit = time.monotonic()
        for plan_idx, src, dst, rel, src_size in _usb_import_iter_plan(plan_file, src_root, dest_base, start_index):
            if _usb_import_cancel.is_set():
                _usb_import_set_state(status="cancelled", message="Đã huỷ copy USB.", finished_at=int(time.time()))
                return
            try:
                _usb_import_ensure_dir(os.path.dirname(dst))
                if os.path.exists(dst) and copy_mode != "overwrite":
                    conflict_item = _usb_import_conflict_item(src, dst, dest_base, src_size)
                    if conflict_handle is not None:
                        conflict_handle.write(json.dumps(conflict_item, ensure_ascii=False) + "\n")
                    else:
                        _usb_import_append_jsonl(conflict_file, conflict_item)
                    conflict_count += 1
                    if len(pending_conflicts) < 200:
                        pending_conflicts.append(conflict_item)
                    skipped += 1
                    bytes_processed += src_size
                    totals["skipped"] = skipped
                    totals["bytes_processed"] = bytes_processed
                    now_emit = time.monotonic()
                    if conflict_count % 200 == 0 or now_emit - last_conflict_emit >= 2.0:
                        if conflict_handle is not None:
                            conflict_handle.flush()
                        _usb_import_set_state(
                            files_skipped=skipped,
                            bytes_processed=bytes_processed,
                            pending_conflicts=pending_conflicts,
                            pending_conflicts_count=conflict_count,
                            needs_action=False,
                            plan_index=plan_idx + 1,
                            last_progress_at=int(time.time()),
                        )
                        last_conflict_emit = now_emit
                    continue
                checksum = _usb_import_copy_file_with_progress(src, dst, totals, overwrite=(copy_mode == "overwrite"))
                _usb_import_apply_path_permissions(dst, is_dir=False)
                done += 1
                bytes_done = totals["bytes_done"]
                bytes_processed = totals["bytes_processed"]
                totals["done"] = done
                if checksum and manifest_handle is not None:
                    try:
                        manifest_handle.write(json.dumps({
                            "rel": os.path.relpath(dst, dest_base),
                            "size": src_size,
                            "sha256": checksum,
                            "ts": int(time.time())
                        }, ensure_ascii=False) + "\n")
                    except Exception:
                        pass
                _usb_import_set_state(plan_index=plan_idx + 1)
            except InterruptedError:
                raise
            except Exception as e:
                retry_item = _usb_import_error_item(src, dst, dest_base, e, src_size)
                if getattr(e, "errno", None) == 5:
                    failed += 1
                    totals["failed"] = failed
                    pending_error_count += 1
                    pending_errors.append(retry_item)
                    pending_errors = pending_errors[-200:]
                else:
                    retry_queue.append(retry_item)
                _usb_import_set_state(
                    last_error=_usb_import_copy_error_message(e, src)[:240],
                    pending_errors=pending_errors,
                    pending_errors_count=pending_error_count + len(retry_queue),
                    plan_index=plan_idx + 1,
                )
            if (done + skipped + failed) % 20 == 0:
                _usb_import_set_state(
                    files_done=done, files_skipped=skipped, files_failed=failed,
                    bytes_done=bytes_done, bytes_processed=bytes_processed
                )
        if retry_queue and not _usb_import_cancel.is_set():
            _usb_import_set_state(
                message="Đang thử lại các file lỗi sau khi copy xong lượt đầu.",
                pending_errors=(pending_errors + retry_queue)[-200:],
                pending_errors_count=pending_error_count + len(retry_queue),
                last_error="Đang thử lại %d file lỗi." % len(retry_queue),
            )
            for item in list(retry_queue):
                if _usb_import_cancel.is_set():
                    _usb_import_set_state(status="cancelled", message="Đã huỷ copy USB.", finished_at=int(time.time()))
                    return
                src = item.get("source") or ""
                dst = item.get("dest") or ""
                src_size = int(item.get("source_size") or 0)
                try:
                    if not src or not os.path.exists(src):
                        raise FileNotFoundError(src or item.get("source_name") or "source")
                    if os.path.exists(dst) and copy_mode != "overwrite":
                        conflict_item = _usb_import_conflict_item(src, dst, dest_base, src_size)
                        if conflict_handle is not None:
                            conflict_handle.write(json.dumps(conflict_item, ensure_ascii=False) + "\n")
                        else:
                            _usb_import_append_jsonl(conflict_file, conflict_item)
                        conflict_count += 1
                        if len(pending_conflicts) < 200:
                            pending_conflicts.append(conflict_item)
                        skipped += 1
                        totals["skipped"] = skipped
                        continue
                    checksum = _usb_import_copy_file_with_progress(src, dst, totals, overwrite=(copy_mode == "overwrite"))
                    _usb_import_apply_path_permissions(dst, is_dir=False)
                    done += 1
                    bytes_done = totals["bytes_done"]
                    bytes_processed = totals["bytes_processed"]
                    totals["done"] = done
                    if checksum and manifest_handle is not None:
                        try:
                            manifest_handle.write(json.dumps({
                                "rel": os.path.relpath(dst, dest_base),
                                "size": src_size,
                                "sha256": checksum,
                                "ts": int(time.time())
                            }, ensure_ascii=False) + "\n")
                        except Exception:
                            pass
                except InterruptedError:
                    raise
                except Exception as e:
                    failed += 1
                    totals["failed"] = failed
                    pending_error_count += 1
                    pending_errors.append(_usb_import_error_item(src, dst, dest_base, e, src_size))
                    pending_errors = pending_errors[-200:]
                    _usb_import_set_state(last_error=_usb_import_copy_error_message(e, src)[:240])
                _usb_import_set_state(
                    files_done=done, files_skipped=skipped, files_failed=failed,
                    bytes_done=bytes_done, bytes_processed=totals["bytes_processed"],
                    pending_conflicts=pending_conflicts,
                    pending_conflicts_count=conflict_count,
                    pending_errors=pending_errors,
                    pending_errors_count=pending_error_count,
                    needs_action=False,
                )
        if manifest_handle is not None:
            manifest_handle.flush()
            os.fsync(manifest_handle.fileno())
            manifest_handle.close()
            manifest_handle = None
        if conflict_handle is not None:
            conflict_handle.flush()
            conflict_handle.close()
            conflict_handle = None
        try:
            _usb_import_apply_path_permissions(dest_base, is_dir=True)
        except Exception:
            pass
        bytes_done = totals["bytes_done"]
        bytes_processed = totals["bytes_processed"]
        processed_total = done + skipped + failed
        plan_check_error = ""
        if files_total > 0 and processed_total < files_total:
            plan_check_error = "Soát lại theo danh sách ban đầu: còn thiếu %d file chưa xử lý." % (files_total - processed_total)
            pending_error_count += 1
            pending_errors.append({
                "source": "",
                "dest": "",
                "rel": "",
                "source_name": "usb_import_plan",
                "dest_name": "",
                "source_size": 0,
                "error": plan_check_error,
            })
            pending_errors = pending_errors[-200:]
            failed += files_total - processed_total
            totals["failed"] = failed
        final_status = "needs_action" if conflict_count > 0 else "done"
        final_message = (
            "Đã copy xong phần không trùng. Cần xử lý %d file trùng tên." % conflict_count
            if conflict_count > 0 else
            ("Đã copy xong USB, còn %d file lỗi sau khi thử lại." % pending_error_count if pending_error_count else "Đã copy xong USB.")
        )
        _usb_import_remove_plan_files(plan_file)
        _usb_import_set_state(
            status=final_status, message=final_message,
            files_done=done, files_skipped=skipped, files_failed=failed,
            bytes_done=bytes_done, bytes_processed=bytes_processed,
            current_file="", current_source="", current_dest="",
            current_file_bytes_done=0, current_file_bytes_total=0,
            copy_speed_bps=0, eta_seconds=0,
            pending_conflicts=pending_conflicts,
            pending_conflicts_count=conflict_count,
            pending_errors=pending_errors,
            pending_errors_count=pending_error_count,
            needs_action=bool(conflict_count > 0),
            last_error=plan_check_error[:240],
            plan_file="", plan_index=0, conflict_file=conflict_file if conflict_count > 0 else "",
            finished_at=int(time.time())
        )
        _usb_import_add_history({
            "id": ident,
            "label": label,
            "device": candidate.get("path", ""),
            "dest_dir": dest_base,
            "status": final_status,
            "started_at": _usb_import_state.get("started_at", 0),
            "finished_at": int(time.time()),
            "files_done": done,
            "files_skipped": skipped,
            "files_failed": failed,
            "bytes_done": bytes_done,
            "resumed": can_resume,
            "checksum": bool(settings.get("verify_checksum", False)),
        })
        _add_system_log("SUCCESS", "USBImport", "Đã copy USB vào %s: %d file, skip %d, lỗi %d, trùng %d" % (dest_base, done, skipped, failed, conflict_count))
    except InterruptedError:
        _usb_import_set_state(status="cancelled", message="Đã huỷ copy USB.", finished_at=int(time.time()))
        _usb_import_add_history({
            "id": ident,
            "label": label,
            "device": candidate.get("path", ""),
            "dest_dir": dest_base,
            "status": "cancelled",
            "started_at": _usb_import_state.get("started_at", 0),
            "finished_at": int(time.time()),
            "files_done": done,
            "files_skipped": skipped,
            "files_failed": failed,
            "bytes_done": bytes_done,
            "resumed": can_resume,
            "checksum": bool(settings.get("verify_checksum", False)),
        })
    finally:
        _index_mutated_directories(totals.get("changed_dirs"))
        if manifest_handle is not None:
            try:
                manifest_handle.close()
            except Exception:
                pass
        if conflict_handle is not None:
            try:
                conflict_handle.close()
            except Exception:
                pass
        if candidate.get("mounted_by_us"):
            try:
                if conflict_count <= 0:
                    subprocess.run(["umount", src_root], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            except Exception:
                pass
        with _usb_import_lock:
            seen = list(_usb_import_state.get("seen_devices") or [])
            ident = candidate.get("id")
            status_for_seen = _usb_import_state.get("status")
            if ident and status_for_seen in ("done", "needs_action") and ident not in seen:
                seen.append(ident)
                _usb_import_state["seen_devices"] = seen[-50:]
        _usb_import_mark_stopped()
        _usb_import_save_state()
        # Mở khoá thumbnail generator sau khi copy USB xong
        _set_thumbnail_auto_block("usb_import", False)
        log.info("[USBImport] Đã mở khoá thumbnail generator sau khi copy USB.")


def _usb_import_path_under(path, root):
    try:
        path_real = os.path.realpath(path)
        root_real = os.path.realpath(root)
        return path_real == root_real or path_real.startswith(root_real.rstrip(os.sep) + os.sep)
    except Exception:
        return False


def _usb_import_resolve_conflicts_worker(action, selected_keys):
    global _usb_import_running
    selected = set(selected_keys or [])
    with _usb_import_lock:
        conflicts = list(_usb_import_state.get("pending_conflicts") or [])
        conflict_file = _usb_import_state.get("conflict_file", "")
        active_mount = _usb_import_state.get("active_mount", "")
        dest_base = _usb_import_state.get("dest_dir", "")
    file_conflicts = _usb_import_load_jsonl(conflict_file)
    if file_conflicts:
        conflicts = file_conflicts
    remaining_conflicts = []
    resolved = 0
    failed = 0
    errors = []
    totals = {
        "done": int(_usb_import_state.get("files_done") or 0),
        "skipped": int(_usb_import_state.get("files_skipped") or 0),
        "failed": int(_usb_import_state.get("files_failed") or 0),
        "bytes_done": int(_usb_import_state.get("bytes_done") or 0),
        "bytes_processed": int(_usb_import_state.get("bytes_processed") or 0),
        "bytes_total": int(_usb_import_state.get("bytes_total") or 0),
        "current_file_done": 0,
        "bytes_since_sync": 0,
        "verify_checksum": False,
    }
    try:
        _set_thumbnail_auto_block("usb_import", True)
        _usb_import_set_state(status="copying", message="Đang xử lý file trùng tên.", last_error="")
        for item in conflicts:
            key = item.get("rel") or item.get("dest") or item.get("source")
            if selected and key not in selected and item.get("dest") not in selected and item.get("source") not in selected:
                remaining_conflicts.append(item)
                continue
            src = item.get("source") or ""
            dst = item.get("dest") or ""
            try:
                if not _usb_import_path_under(src, active_mount) or not _usb_import_path_under(dst, dest_base):
                    raise ValueError("Đường dẫn file trùng không hợp lệ.")
                if action == "skip":
                    resolved += 1
                    continue
                final_dst = dst
                if action == "rename":
                    final_dst = _usb_import_unique_dest(dst)
                elif action != "overwrite":
                    raise ValueError("Hành động không hợp lệ.")
                _usb_import_ensure_dir(os.path.dirname(final_dst))
                _usb_import_copy_file_with_progress(src, final_dst, totals, overwrite=(action == "overwrite"))
                _usb_import_apply_path_permissions(final_dst, is_dir=False)
                totals["done"] += 1
                resolved += 1
            except InterruptedError:
                raise
            except Exception as e:
                failed += 1
                totals["failed"] += 1
                errors.append(_usb_import_error_item(src, dst, dest_base, e, item.get("source_size")))
                _usb_import_set_state(last_error=_usb_import_copy_error_message(e, src)[:240])
        status = "needs_action" if remaining_conflicts else "done"
        message = (
            "Còn %d file trùng tên chưa xử lý." % len(remaining_conflicts)
            if remaining_conflicts else
            "Đã xử lý xong %d file trùng tên." % resolved
        )
        if errors:
            message += " Còn %d file lỗi." % len(errors)
        try:
            if conflict_file:
                if remaining_conflicts:
                    _usb_import_write_jsonl(conflict_file, remaining_conflicts)
                elif os.path.exists(conflict_file):
                    os.remove(conflict_file)
        except Exception:
            pass
        _usb_import_set_state(
            status=status,
            message=message,
            files_done=totals["done"],
            files_failed=totals["failed"],
            bytes_done=totals["bytes_done"],
            bytes_processed=totals["bytes_processed"],
            current_file="", current_source="", current_dest="",
            current_file_bytes_done=0, current_file_bytes_total=0,
            copy_speed_bps=0, eta_seconds=0,
            pending_conflicts=remaining_conflicts[:200],
            pending_conflicts_count=len(remaining_conflicts),
            pending_errors=errors,
            pending_errors_count=len(errors),
            needs_action=bool(remaining_conflicts),
            conflict_file=conflict_file if remaining_conflicts else "",
            finished_at=int(time.time()),
        )
        try:
            if dest_base:
                _usb_import_apply_path_permissions(dest_base, is_dir=True)
        except Exception:
            pass
    except InterruptedError:
        _usb_import_set_state(status="cancelled", message="Đã huỷ xử lý file trùng.", finished_at=int(time.time()))
    finally:
        _index_mutated_directories(totals.get("changed_dirs"))
        _usb_import_mark_stopped()
        _set_thumbnail_auto_block("usb_import", False)
        _usb_import_save_state()


def _usb_import_watchdog():
    global _usb_import_running
    time.sleep(45)
    _usb_import_load_state()
    log.info("[USBImport] Trình phát hiện USB đã khởi động.")
    while True:
        settings = _usb_import_load_settings()
        try:
            if not settings.get("enabled"):
                _usb_import_set_state(enabled=False, status="disabled", message="USB import đang tắt.")
                time.sleep(settings.get("poll_seconds", 15))
                continue
            _usb_import_set_state(enabled=True)
            if not _usb_import_running:
                candidates = _usb_import_find_candidates(settings)
                with _usb_import_lock:
                    seen = set(_usb_import_state.get("seen_devices") or [])
                    active_id = _usb_import_state.get("active_id")
                    curr_status = _usb_import_state.get("status")
                for candidate in candidates:
                    candidate_id = candidate.get("id")
                    can_resume_seen = bool(
                        settings.get("resume_enabled", True)
                        and candidate_id
                        and candidate_id == active_id
                        and curr_status in ("copying", "cancelled", "error")
                    )
                    if candidate_id in seen and not can_resume_seen:
                        continue
                    if not _usb_import_try_mark_running():
                        break
                    _usb_import_cancel.clear()
                    threading.Thread(target=_usb_import_copy_tree, args=(candidate, settings), daemon=True, name="USBImportCopy").start()
                    break
                else:
                    with _usb_import_lock:
                        curr_status = _usb_import_state.get("status")
                    if not candidates:
                        _usb_import_set_state(status="idle", message="Đang chờ ổ USB hợp lệ.", active_device="", active_mount="")
                    elif curr_status in ("copying", "cancelling"):
                        _usb_import_set_state(status="cancelled", message="Đã huỷ", finished_at=int(time.time()))
        except Exception as e:
            _usb_import_mark_stopped()
            log.error("[USBImport] Watchdog lỗi: %s", e)
            _usb_import_set_state(status="error", message="Lỗi USB import.", last_error=str(e)[:200], finished_at=int(time.time()))
        time.sleep(settings.get("poll_seconds", 15))


@app.route("/api/usb_import/status", methods=["GET"])
@requires_auth
def api_usb_import_status():
    compact = str(request.args.get("compact", "")).lower() in ("1", "true", "yes")
    return jsonify(_usb_import_public_state(compact=compact))


@app.route("/api/usb_import/settings", methods=["POST"])
@requires_auth
def api_usb_import_settings():
    global _usb_import_running
    current = _usb_import_load_settings()
    body = request.get_json(silent=True) or {}
    requested_disable = ("enabled" in body and not bool(body.get("enabled")))
    for key in ("enabled", "auto_mount", "mount_readonly", "resume_enabled", "verify_checksum"):
        if key in body:
            current[key] = bool(body.get(key))
    if "dest_folder" in body:
        current["dest_folder"] = _re_module.sub(r"[\\/:*?\"<>|]+", "_", str(body.get("dest_folder") or "USB Import")).strip() or "USB Import"
    if body.get("copy_mode") in ("new_only", "overwrite"):
        current["copy_mode"] = body.get("copy_mode")
    if "poll_seconds" in body:
        try:
            current["poll_seconds"] = max(5, min(300, int(body.get("poll_seconds"))))
        except Exception:
            pass
    saved = _usb_import_save_settings(current)
    if requested_disable:
        _usb_import_cancel.set()
        if _usb_import_running:
            _usb_import_set_state(enabled=False, status="cancelling", message="Đang tắt USB Import và huỷ phiên copy hiện tại.")
        else:
            _usb_import_set_state(enabled=False, status="disabled", message="USB Import đang tắt.")
    return jsonify({"saved": saved, "settings": current, "state": _usb_import_public_state()})


@app.route("/api/usb_import/start", methods=["POST"])
@requires_auth
def api_usb_import_start():
    try:
        global _usb_import_running
        if _usb_import_running:
            return jsonify({"ok": True, "already_running": True, "message": "USB Import đang chạy, không khởi tạo phiên trùng.", "state": _usb_import_public_state()})
        settings = _usb_import_load_settings()
        candidates = _usb_import_find_candidates(settings)
        if not candidates:
            return jsonify({"ok": False, "message": "Không tìm thấy ổ USB hợp lệ", "state": _usb_import_public_state()}), 404
        if not _usb_import_try_mark_running():
            return jsonify({"ok": True, "already_running": True, "message": "USB Import đang chạy, không khởi tạo phiên trùng.", "state": _usb_import_public_state()})
        _usb_import_cancel.clear()
        threading.Thread(target=_usb_import_copy_tree, args=(candidates[0], settings), daemon=True, name="USBImportManualCopy").start()
        return jsonify({"ok": True, "message": "Đã bắt đầu copy USB", "state": _usb_import_public_state()})
    except Exception as e:
        log.error("[API] Error in api_usb_import_start: %s", e)
        return jsonify({"ok": False, "message": str(e), "state": _usb_import_public_state()}), 500


@app.route("/api/usb_import/cancel", methods=["POST"])
@requires_auth
def api_usb_import_cancel():
    try:
        _usb_import_cancel.set()
        if _usb_import_running:
            _usb_import_set_state(status="cancelling", message="Đang huỷ copy USB.")
        else:
            _usb_import_set_state(status="cancelled", message="Đã huỷ copy USB.")
        return jsonify({"ok": True, "state": _usb_import_public_state()})
    except Exception as e:
        log.error("[API] Error in api_usb_import_cancel: %s", e)
        return jsonify({"ok": False, "message": str(e)}), 500


@app.route("/api/usb_import/resolve_conflicts", methods=["POST"])
@requires_auth
def api_usb_import_resolve_conflicts():
    try:
        global _usb_import_running
        if _usb_import_running:
            return jsonify({"ok": False, "message": "USB import đang chạy", "state": _usb_import_public_state()}), 409
        body = request.get_json(silent=True) or {}
        action = str(body.get("action") or "").strip().lower()
        if action not in ("overwrite", "rename", "skip"):
            return jsonify({"ok": False, "message": "action phải là overwrite, rename hoặc skip", "state": _usb_import_public_state()}), 400
        with _usb_import_lock:
            conflicts = list(_usb_import_state.get("pending_conflicts") or [])
        if not conflicts:
            return jsonify({"ok": False, "message": "Không có file trùng tên cần xử lý", "state": _usb_import_public_state()}), 404
        selected = body.get("items")
        if selected is not None and not isinstance(selected, list):
            return jsonify({"ok": False, "message": "items phải là danh sách rel/dest/source", "state": _usb_import_public_state()}), 400
        if not _usb_import_try_mark_running():
            return jsonify({"ok": False, "message": "USB import đang chạy", "state": _usb_import_public_state()}), 409
        _usb_import_cancel.clear()
        threading.Thread(
            target=_usb_import_resolve_conflicts_worker,
            args=(action, selected or []),
            daemon=True,
            name="USBImportResolveConflicts",
        ).start()
        return jsonify({"ok": True, "message": "Đã bắt đầu xử lý file trùng tên", "state": _usb_import_public_state()})
    except Exception as e:
        log.error("[API] Error in api_usb_import_resolve_conflicts: %s", e)
        return jsonify({"ok": False, "message": str(e)}), 500


@app.route('/api/backup/schedule', methods=['GET'])
@requires_auth
def api_backup_schedule_get():
    return jsonify(_load_backup_schedule())


@app.route('/api/backup/schedule', methods=['POST'])
# CC-005 P1/P2: dieu khien lich backup (dan toi tao backup chua credential)
# -> requires_admin. GET lich (chi doc) giu requires_auth.
@requires_admin
def api_backup_schedule_set():
    body = request.get_json(force=True) or {}
    sched = _load_backup_schedule()
    # Chi cho update mot so field
    for key in ("enabled", "frequency", "hour", "retention_count", "rclone_remote", "rclone_path"):
        if key in body:
            sched[key] = body[key]
    # Sanitize
    sched["enabled"] = bool(sched.get("enabled"))
    try: sched["hour"] = max(0, min(23, int(sched.get("hour", 3))))
    except Exception: sched["hour"] = 3
    try: sched["retention_count"] = max(1, min(50, int(sched.get("retention_count", 7))))
    except Exception: sched["retention_count"] = 7
    if sched.get("frequency") not in ("daily", "weekly", "monthly"):
        sched["frequency"] = "weekly"
    _rc_rem = str(sched.get("rclone_remote", "") or "").strip().rstrip(":")
    _rc_path = str(sched.get("rclone_path", "") or "").strip()
    if _rc_rem and not _re_module.match(r"^[A-Za-z0-9_\-]{1,64}$", _rc_rem):
        return jsonify({"error": "rclone_remote khong hop le."}), 400
    if _rc_path.startswith("-") or ".." in _rc_path.replace("\\", "/").split("/"):
        return jsonify({"error": "rclone_path khong hop le."}), 400
    sched["rclone_remote"] = _rc_rem
    sched["rclone_path"] = _rc_path
    ok = _save_backup_schedule(sched)
    return jsonify({"saved": ok, "schedule": sched})


# ============================================================================
# BACKUP / RESTORE — Sao l?u va khoi phuc cau hinh NAS
# ============================================================================
# Backup tarball ch?a moi config/state cua NAS API + WebDAV + fan + watcher.
# L?u vao /etc/nas/backups (eMMC, an toan khi HDD chet).
# Filename: "Backup_NAS DDMMYYYY HHMMSS.tar.gz"
#
# Cac file được backup (manifest.json dinh kem trong tarball):
#   /opt/nas_api_server.py                          (NAS API server Python)
#   /opt/fan_custom.json                            (fan settings)
#   /etc/systemd/system/nas_api.service             (systemd unit)
#   /etc/systemd/system/fan.service                 (fan service neu co)
#   /etc/nas/auth.conf                              (WEBDAV creds — chmod 600)
#   /etc/nas/lan_whitelist.conf                     (LAN IP whitelist)
#   /etc/nas/install-pending-cookies.sh             (auto-install script)
#   /etc/nas/state/tiktok_live_watch.json           (watcher mirror state)
#   /etc/nginx/openmediavault-webgui.d/nas_api.conf       (nginx reverse proxy)
#   /etc/nginx/openmediavault-webgui.d/openmediavault-webdav.conf
#   /var/www/webdav/config/config.php               (OMV WebDAV publicDir)
#   <WEBDAV_ROOT>/cookies.txt                       (TikTok cookies — CHI duoc doc duoc neu nam trong WEBDAV_FILE_ROOT, duoc kiem toan qua _cookie_path_is_confined + _audit_cookie_access)
#   <WEBDAV_ROOT>/.nas_meta/tiktok_live_watch.json  (watcher state on HDD)
#   qBittorrent SID cookie lay tu localhost auth login (khong tu file cookie trinh duyet).
# ============================================================================
import tarfile

_BACKUP_DIR = "/etc/nas/backups"
_BACKUP_RESTORE_MAX_MEMBER_BYTES = 64 * 1024 * 1024
_BACKUP_RESTORE_COPY_CHUNK = 1024 * 1024
_BACKUP_FILES = [
    # (source_path, relative_path_in_tar, critical)
    # critical = True -> bao lỗi neu thieu khi restore
    ("/opt/nas_api_server.py",                              "opt/nas_api_server.py",                              True),
    ("/opt/fan_custom.json",                                "opt/fan_custom.json",                                False),
    ("/etc/systemd/system/nas_api.service",                 "etc/systemd/system/nas_api.service",                 True),
    ("/etc/systemd/system/fan.service",                     "etc/systemd/system/fan.service",                     False),
    ("/etc/nas/auth.conf",                                  "etc/nas/auth.conf",                                  True),
    ("/etc/nas/lan_whitelist.conf",                         "etc/nas/lan_whitelist.conf",                         False),
    ("/etc/nas/install-pending-cookies.sh",                 "etc/nas/install-pending-cookies.sh",                 False),
    ("/etc/nas/state/tiktok_live_watch.json",               "etc/nas/state/tiktok_live_watch.json",               False),
    ("/etc/nginx/openmediavault-webgui.d/nas_api.conf",     "etc/nginx/openmediavault-webgui.d/nas_api.conf",     False),
    ("/etc/nginx/openmediavault-webgui.d/openmediavault-webdav.conf", "etc/nginx/openmediavault-webgui.d/openmediavault-webdav.conf", False),
    ("/var/www/webdav/config/config.php",                   "var/www/webdav/config/config.php",                   False),
]


def _backup_dynamic_files():
    """Cac path phu thuoc WEBDAV_FILE_ROOT (HDD). L?y vao runtime."""
    return [
        (os.path.join(WEBDAV_FILE_ROOT, "cookies.txt"),                     "webdav_root/cookies.txt",                     False),
        (os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "tiktok_live_watch.json"), "webdav_root/.nas_meta/tiktok_live_watch.json", False),
    ]


def _ensure_backup_dir():
    import stat
    try:
        if os.path.realpath(_BACKUP_DIR) != os.path.abspath(_BACKUP_DIR):
            raise ValueError('Backup directory must not use symlinks')
        parent = os.stat(os.path.dirname(_BACKUP_DIR))
        if parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
            raise ValueError('Backup parent is not private to its owner')
        os.makedirs(_BACKUP_DIR, mode=0o700, exist_ok=True)
        fd = os.open(_BACKUP_DIR, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            if os.fstat(fd).st_uid != os.geteuid():
                raise ValueError('Unexpected backup directory owner')
            os.fchmod(fd, 0o700)
            for name in os.listdir(_BACKUP_DIR):
                if not name.startswith('Backup_NAS') or not name.endswith('.tar.gz'):
                    continue
                archive_fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
                try:
                    info = os.fstat(archive_fd)
                    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1:
                        raise ValueError('Unsafe backup archive')
                    os.fchmod(archive_fd, 0o600)
                finally:
                    os.close(archive_fd)
        finally:
            os.close(fd)
        return True
    except Exception as error:
        log.error('[Backup] Private backup directory unavailable: %s', error)
        return False


def _open_private_backup_tar(path):
    import contextlib
    @contextlib.contextmanager
    def create():
        if not _ensure_backup_dir():
            raise IOError('Private backup directory unavailable')
        name = os.path.basename(path)
        if os.path.abspath(os.path.dirname(path)) != os.path.realpath(_BACKUP_DIR) or not name.startswith('Backup_NAS') or not name.endswith('.tar.gz'):
            raise ValueError('Invalid backup archive path')
        directory_fd = os.open(_BACKUP_DIR, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory_fd)
            with os.fdopen(fd, 'wb') as raw:
                with tarfile.open(fileobj=raw, mode='w:gz') as archive:
                    yield archive
                raw.flush()
                os.fsync(raw.fileno())
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    return create()


def _backup_file_path(filename):
    filename = (filename or "").strip()
    if not filename:
        return None
    if os.path.basename(filename) != filename:
        return None
    if not filename.startswith("Backup_NAS") or not filename.endswith(".tar.gz"):
        return None
    backup_root = os.path.realpath(_BACKUP_DIR)
    full = os.path.realpath(os.path.join(backup_root, filename))
    expected = os.path.join(backup_root, filename)
    if full != expected:
        return None
    if not (full == backup_root or full.startswith(backup_root + os.sep)):
        return None
    return full


@app.route('/api/backup/create', methods=['POST'])
@requires_admin
def api_backup_create():
    """Tao 1 backup .tar.gz ch?a moi config/state hien tai."""
    if not _ensure_backup_dir():
        return jsonify({"error": "Không tạo được thư mục backup"}), 500
    try:
        timestamp = datetime.datetime.now().strftime("%d%m%Y %H%M%S")
        filename = "Backup_NAS %s.tar.gz" % timestamp
        full_path = os.path.join(_BACKUP_DIR, filename)
        manifest = {
            "created_at": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
            "hostname": os.uname()[1] if hasattr(os, "uname") else "unknown",
            "webdav_root": WEBDAV_FILE_ROOT,
            "files": [],
        }
        included = 0
        skipped = []
        all_files = list(_BACKUP_FILES) + _backup_dynamic_files()
        with _open_private_backup_tar(full_path) as tar:
            for src, arcname, critical in all_files:
                if not os.path.exists(src):
                    skipped.append({"path": src, "reason": "không tồn tại"})
                    continue
                try:
                    tar.add(src, arcname=arcname)
                    sz = 0
                    try: sz = os.path.getsize(src)
                    except Exception as e: log.debug("[M4] Ignored exception: %s", e)
                    manifest["files"].append({
                        "src": src,
                        "archive_path": arcname,
                        "size": sz,
                    })
                    included += 1
                except Exception as e:
                    skipped.append({"path": src, "reason": str(e)[:100]})
                    if critical:
                        log.warning("[Backup] File critical bị lỗi: %s -> %s", src, e)
            # Th?m manifest vao tarball cuoi cung
            manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8")
            info = tarfile.TarInfo(name="manifest.json")
            info.size = len(manifest_bytes)
            info.mtime = int(time.time())
            try:
                import io as _io
                tar.addfile(info, _io.BytesIO(manifest_bytes))
            except Exception as e:
                log.warning("[Backup] Không add được manifest: %s", e)
        size = os.path.getsize(full_path)
        # CC-005 P1/P2: _BACKUP_FILES gom auth.conf (chua credential) — backup
        # download da requires_admin; chi log canh bao ten file, KHONG ghi noi
        # dung credential ra log.
        try:
            _cred_hit = [a for _s, a, _c in all_files if "auth.conf" in str(a)]
            if _cred_hit:
                log.warning("[Backup] Backup %s chua file credential (%s) — bao ve file, khong share",
                            sanitize_log_input(filename), ",".join(_cred_hit))
        except Exception:
            pass
        log.info("[Backup] Tao xong %s (%d files, %d bytes)", filename, included, size)
        return jsonify({
            "filename": filename,
            "size": size,
            "size_human": format_bytes(size),
            "created_at": manifest["created_at"],
            "included_count": included,
            "skipped": skipped,
            "download_url": "/api/backup/download?filename=" + urllib.parse.quote(filename),
        })
    except Exception as e:
        log.error("[Backup] Tạo backup lỗi: %s", e)
        return jsonify({"error": "Không tạo được backup: %s" % str(e)[:200]}), 500


@app.route('/api/backup/list', methods=['GET'])
@requires_admin
def api_backup_list():
    """List mới backup co san trong /etc/nas/backups."""
    if not _ensure_backup_dir():
        return jsonify({"backups": []})
    items = []
    try:
        for name in os.listdir(_BACKUP_DIR):
            if not name.startswith("Backup_NAS"):
                continue
            full = os.path.join(_BACKUP_DIR, name)
            try:
                st = os.stat(full)
                items.append({
                    "filename": name,
                    "size": st.st_size,
                    "size_human": format_bytes(st.st_size),
                    "mtime": st.st_mtime,
                    "created_at": datetime.datetime.fromtimestamp(st.st_mtime).strftime("%d/%m/%Y %H:%M:%S"),
                    "download_url": "/api/backup/download?filename=" + urllib.parse.quote(name),
                })
            except Exception:
                continue
        items.sort(key=lambda x: x["mtime"], reverse=True)
    except Exception as e:
        log.warning("[Backup] List lỗi: %s", e)
    return jsonify({"backups": items, "backup_dir": _BACKUP_DIR})


@app.route('/api/backup/download', methods=['GET'])
@requires_admin
def api_backup_download():
    """Stream 1 file backup ve client."""
    filename = request.args.get("filename", "").strip()
    full = _backup_file_path(filename)
    if not full:
        return jsonify({"error": "Filename khong hop le"}), 400
    if not os.path.exists(full):
        return jsonify({"error": "File khong ton tai"}), 404
    try:
        from flask import send_file
        return send_file(full, mimetype="application/gzip",
                         as_attachment=True, attachment_filename=filename)
    except TypeError:
        from flask import send_file
        return send_file(full, mimetype="application/gzip", as_attachment=True)

@app.route('/api/backup/delete', methods=['POST'])
@requires_admin
def api_backup_delete():
    body = request.get_json(force=True) or {}
    filename = (body.get("filename") or "").strip()
    full = _backup_file_path(filename)
    if not full:
        return jsonify({"error": "Filename khong hop le"}), 400
    if not os.path.exists(full):
        return jsonify({"error": "File khong ton tai"}), 404
    try:
        os.remove(full)
        log.info("[Backup] Deleted %s", filename)
        return jsonify({"status": "deleted", "filename": filename})
    except Exception as e:
        return jsonify({"error": "Khong xoa duoc: %s" % str(e)[:200]}), 500

@app.route('/api/backup/restore', methods=['POST'])
@requires_admin
def api_backup_restore():
    """Khoi phuc tu mot backup file co san hoac uploaded.
    Body:
      - filename: ten file backup trong _BACKUP_DIR (uu tien)
      - file: multipart upload (neu khong co filename)
    """
    # Xac dinh source
    src_tar = None
    cleanup_after = False
    try:
        if request.files and "file" in request.files:
            up = request.files["file"]
            # CC-005 P1: cap upload 200MB + verify gzip magic (decompression bomb)
            try:
                up.stream.seek(0, 2)
                _rsz = up.stream.tell()
                up.stream.seek(0)
            except Exception:
                _rsz = 0
            if _rsz > 200 * 1024 * 1024:
                return jsonify({"error": "File backup vuot gioi han 200MB"}), 413
            tmp_dir = _get_hdd_tmp_root()
            tmp_file = tempfile.NamedTemporaryFile(prefix="nas_restore_", suffix=".tar.gz", dir=tmp_dir, delete=False)
            tmp = tmp_file.name
            tmp_file.close()
            up.save(tmp)
            try:
                if os.path.getsize(tmp) > 200 * 1024 * 1024:
                    raise ValueError("too large")
                with open(tmp, "rb") as _rf:
                    _rm = _rf.read(2)
                if _rm != b"\x1f\x8b":
                    raise ValueError("khong phai gzip hop le")
            except Exception as _re:
                try: os.remove(tmp)
                except Exception: pass
                return jsonify({"error": "File backup khong hop le: %s" % _re}), 400
            src_tar = tmp
            cleanup_after = True
        else:
            body = {}
            try: body = request.get_json(silent=True) or {}
            except Exception: body = {}
            filename = (body.get("filename") or "").strip()
            src_tar = _backup_file_path(filename)
        if not src_tar or not os.path.exists(src_tar):
            return jsonify({"error": "Không tìm thấy file backup để khôi phục"}), 400

        restored = []
        errors = []
        # CC-005 P1: cap tong uncompressed 500MB (decompression bomb: moi member
        # <=64MB nhung 10000 member x 64MB van tran HDD 1TB rk3328)
        _total_uncomp = [0]
        # FIX-REVIEW-193369e-#2: backup RIENG moi transaction + ghi ro dest nao
        # da thay trong transaction nay. Ban cu dung ten .pre-restore co dinh:
        # restore lan1 thanh cong de lai backup, lan2 staging loi van thay file
        # hien hanh bang backup cua LAN1 (hoi quy). Khong suy rollback tu ton
        # tai ten co dinh.
        import uuid as _tx_uuid
        _tx_tag = "pre-restore.%s" % _tx_uuid.uuid4().hex
        attempted = []
        replaced_in_tx = []
        manifest = None
        with tarfile.open(src_tar, "r:gz") as tar:
            # Đọc manifest truoc
            try:
                m_member = tar.getmember("manifest.json")
                if m_member.size > _BACKUP_RESTORE_MAX_MEMBER_BYTES:
                    raise ValueError("manifest too large")
                m_file = tar.extractfile(m_member)
                if m_file:
                    manifest = json.loads(m_file.read().decode("utf-8"))
            except Exception as e:
                log.warning("[Backup] Không Đọc được manifest: %s", e)

            # Build map archive_path -> real_dest
            file_map = {}
            for src, arcname, _crit in _BACKUP_FILES:
                file_map[arcname] = src
            for src, arcname, _crit in _backup_dynamic_files():
                file_map[arcname] = src

            for member in tar.getmembers():
                if len(restored) + len(errors) > 10000:
                    errors.append({"file": member.name, "reason": "vượt quá giới hạn 10000 file trong backup"})
                    break
                if member.name == "manifest.json" or not member.isfile():
                    continue
                dest = file_map.get(member.name)
                if not dest:
                    errors.append({"file": member.name, "reason": "không xác định được đường dẫn đích"})
                    continue
                if cleanup_after and dest in (
                    "/opt/nas_api_server.py",
                    "/etc/systemd/system/nas_api.service",
                    "/etc/systemd/system/fan.service",
                    "/etc/nas/install-pending-cookies.sh",
                ):
                    errors.append({"file": member.name, "reason": "không cho phép ghi đè file thực thi từ bản backup tải lên"})
                    continue
                if member.size > _BACKUP_RESTORE_MAX_MEMBER_BYTES:
                    errors.append({"file": member.name, "reason": "file backup vuot gioi han restore"})
                    continue
                _total_uncomp[0] += (member.size or 0)
                if _total_uncomp[0] > 500 * 1024 * 1024:
                    errors.append({"file": member.name, "reason": "tong backup vuot 500MB"})
                    break
                try:
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                except Exception as e:
                    errors.append({"file": dest, "reason": "mkdir lỗi: %s" % e})
                    continue
                try:
                    f = tar.extractfile(member)
                    if f is None:
                        errors.append({"file": dest, "reason": "tar không Đọc được"})
                        attempted.append(dest)
                        continue
                    # FIX-REVIEW-24/09-#1: STAGE DAY DU + VALIDATE TRUOC KHI DONG
                    # VAO BAN GOC. Ban cu rename goc -> .pre-restore TRUOC roi
                    # moi copy: ENOSPC/open/read loi giua chung khien duong dan
                    # chinh MAT ma file do khong vao `restored` nen rollback bo
                    # sot. Quy trinh moi: copy ra .restore-tmp -> fsync ->
                    # validate size -> backup goc -> replace nguyen tu. Moi dest
                    # duoc thu deu ghi vao `attempted` de rollback dung file loi.
                    attempted.append(dest)
                    tmp = dest + ".restore-tmp." + _tx_tag
                    with f, open(tmp, "wb") as w:
                        shutil.copyfileobj(f, w, length=_BACKUP_RESTORE_COPY_CHUNK)
                        w.flush()
                        os.fsync(w.fileno())
                    if os.path.getsize(tmp) != member.size:
                        raise IOError("staged file size does not match archive")
                    # Backup file dich hien tai truoc khi ghi de (rollback neu can).
                    # FIX-REVIEW-193369e-#2: backup theo TRANSACTION (_tx_tag);
                    # backup goc that bai -> FAIL NGAY, khong replace. Chi dest
                    # nao thay that trong transaction nay moi duoc rollback.
                    if os.path.exists(dest):
                        try:
                            os.replace(dest, dest + "." + _tx_tag)
                        except Exception as _be:
                            raise IOError("khong backup duoc ban goc: %s" % _be)
                    # Journal before install: even a failed install needs rollback
                    # after the original has moved. A missing backup denotes a new file.
                    replaced_in_tx.append(dest)
                    os.replace(tmp, dest)
                    # Phuc hoi quyen co ban: auth.conf phai chmod 600
                    if dest.endswith("auth.conf"):
                        try: os.chmod(dest, 0o600)
                        except Exception as e: log.debug("[M4] Ignored exception: %s", e)
                    if dest.endswith(".sh"):
                        try: os.chmod(dest, 0o755)
                        except Exception as e: log.debug("[M4] Ignored exception: %s", e)
                    restored.append(dest)
                except Exception as e:
                    errors.append({"file": dest, "reason": str(e)[:120]})

        # Invalidate index for any restored files that live under WEBDAV root.
        try:
            _restored_dirs = set()
            for dest in restored:
                try:
                    _d = os.path.dirname(os.path.realpath(dest))
                    if _d:
                        _restored_dirs.add(_d)
                except Exception:
                    pass
            if _restored_dirs:
                _index_mutated_directories(_restored_dirs)
        except NameError:
            # Isolated test seam may not define _index_mutated_directories.
            pass

        # FIX-REVIEW-193369e-#2: rollback CHI dest da thay trong transaction nay
        # (replaced_in_tx + _tx_tag), khong phai moi attempted. Co critical ->
        # KHONG restart, tra 500.
        for dest in attempted:
            _stage = dest + ".restore-tmp." + _tx_tag
            try:
                if os.path.exists(_stage):
                    os.remove(_stage)
            except OSError:
                pass
        _critical = [e for e in errors if isinstance(e, dict)]
        services_restarted = []
        if _critical:
            _rolled_back = []
            for dest in list(replaced_in_tx)[::-1]:
                _pre = dest + "." + _tx_tag
                try:
                    if os.path.exists(_pre):
                        os.replace(_pre, dest)
                        _rolled_back.append(dest)
                    elif os.path.exists(dest):
                        os.remove(dest)
                        _rolled_back.append(dest)
                except Exception as _rb:
                    errors.append({"file": dest, "reason": "rollback failed: %s" % _rb})
                    log.error("[Backup] Rollback that bai %s: %s", dest, _rb)
            if _rolled_back:
                log.warning("[Backup] Da rollback %d file ve .pre-restore do loi critical.", len(_rolled_back))
            try:
                _add_system_log("ERROR", "Backup",
                                "Restore THAT BAI: %d loi, da rollback %d file, KHONG restart service." % (
                                    len(_critical), len(_rolled_back)))
            except Exception:
                pass
            return jsonify({
                "status": "failed",
                "restored_count": len(restored),
                "restored": restored,
                "errors": errors,
                "services_restarted": services_restarted,
                "manifest": manifest,
            }), 500
        # FIX-REVIEW-24/09-R2: activation SAU khi response da flush.
        # Ban cu: restart nginx/fan TRUOC khi tra JSON + delayed exec 1s cho
        # nas_api — nginx restart co the cat response giua chung, sleep 1s khong
        # bao dam response hoan tat. Quy trinh moi: build response -> dang ky
        # call_on_close (chay SAU khi response flush xong) de restart nginx/fan
        # roi self-restart nas_api qua exec co drain. Caller luon nhan du JSON.
        services_restarted.append("nas_api (scheduled after response)")
        _result = jsonify({
            "status": "restored",
            "restored_count": len(restored),
            "restored": restored,
            "errors": errors,
            "services_restarted": services_restarted,
            "manifest": manifest,
        })
        # Luu ket qua ben vung truoc khi self-restart (R2).
        try:
            _add_system_log("SUCCESS", "Backup",
                            "Restore hoan tat: %d file, services se kich hoat sau response." % len(restored))
        except Exception:
            pass
        try:
            @_result.call_on_close
            def _activate_after_response():
                try:
                    for svc in ("nginx", "fan"):
                        try:
                            subprocess.run(["systemctl", "daemon-reload"], timeout=10)
                            r = subprocess.run(["systemctl", "restart", svc], timeout=15,
                                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                        except Exception:
                            pass
                    _restart_nas_api("restore cau hinh xong — kich hoat cau hinh moi")
                except Exception as _ae:
                    log.error("[Backup] Activation sau response loi: %s", _ae)
        except Exception:
            pass
        return _result
    except Exception as e:
        log.error("[Backup] Restore lỗi: %s", e)
        return jsonify({"error": "Không khôi phục được: %s" % str(e)[:200]}), 500
    finally:
        if cleanup_after and src_tar:
            try: os.remove(src_tar)
            except Exception as e: log.debug("[M4] Ignored exception: %s", e)


@app.route('/api/fan/control', methods=['POST'])
@requires_auth
def api_fan_control():
    try:
        data = request.json or {}
        settings = _load_fan_settings()
        mode = data.get('mode', settings.get('mode', 'auto'))
        
        # FIX: STATUS_CACHE -> _status_cache (ten dung cua bien global).
        # Truoc day moi POST /api/fan/control ne ra "name 'STATUS_CACHE' is not defined"
        # khien API tra HTTP 500 du hardware da chuyen mode dung. /api/status van
        # tra mode cu vi cache không được cap nhat ngay.
        if mode == 'auto':
            if settings.get('mode') != 'auto':
                # A read already in progress belongs to the previous mode.
                with _HDD_TEMP_REFRESH_LOCK:
                    _HDD_TEMP_CACHE.update(ts=None, attempt=None, paused=True, cool=0)
            settings['mode'] = 'auto'
            settings['on_temp'] = float(data.get('on_temp', FAN_DEFAULT_ON_TEMP) or FAN_DEFAULT_ON_TEMP)
            settings['off_temp'] = float(data.get('off_temp', FAN_DEFAULT_OFF_TEMP) or FAN_DEFAULT_OFF_TEMP)
            if settings['off_temp'] >= settings['on_temp']:
                settings['off_temp'] = max(28.0, settings['on_temp'] - 3.0)
            _save_fan_settings(settings)
            run_cmd(["systemctl", "stop", "fan.service"])
            with _cache_lock:
                _status_cache['fan_mode'] = 'auto'
                _status_cache['fan_on_temp'] = settings['on_temp']
                _status_cache['fan_off_temp'] = settings['off_temp']
            return jsonify({"status": "success", "mode": "auto", "on_temp": settings["on_temp"], "off_temp": settings["off_temp"]})

        elif mode == 'custom':
            settings['mode'] = 'custom'
            settings['on_temp'] = float(data.get('on_temp', settings.get('on_temp', FAN_DEFAULT_ON_TEMP)))
            settings['off_temp'] = float(data.get('off_temp', settings.get('off_temp', FAN_DEFAULT_OFF_TEMP)))
            if settings['off_temp'] >= settings['on_temp']:
                settings['off_temp'] = max(28.0, settings['on_temp'] - 3.0)
            _save_fan_settings(settings)
            run_cmd(["systemctl", "stop", "fan.service"])
            
            cpu_t = _fan_temp_value(get_cpu_temp())
            hdd_t = _fan_temp_value(get_hdd_temp())
            ctrl_t = hdd_t if hdd_t > 0 else cpu_t
            
            if ctrl_t >= settings['on_temp']:
                _pwm_apply_on(duty=10000, period=10000)
                pct, rpm, st = 100, _fan_rpm_for_percent(100), 'Đang chạy 100%'
            elif ctrl_t <= settings['off_temp']:
                _pwm_apply_off()
                pct, rpm, st = 0, 0, 'Dừng'
            else:
                pct = int(_status_cache.get('fan_percent', 0) or 0)
                if pct > 0:
                    rpm, st = _fan_rpm_for_percent(pct), _fan_status_for_percent(pct)
                else:
                    _pwm_apply_off()
                    pct, rpm, st = 0, 0, 'Dừng'
                
            with _cache_lock:
                _status_cache['fan_mode'] = 'custom'
                _status_cache['fan_on_temp'] = settings['on_temp']
                _status_cache['fan_off_temp'] = settings['off_temp']
                _status_cache['fan_percent'] = pct
                _status_cache['fan_rpm'] = rpm
                _status_cache['fan_status'] = st
            return jsonify({"status": "success", "mode": "custom", "on_temp": settings['on_temp'], "off_temp": settings['off_temp'], "fan_percent": pct, "fan_rpm": rpm, "fan_status": st})

        elif mode == 'off':
            settings['mode'] = 'off'
            _save_fan_settings(settings)
            run_cmd(["systemctl", "stop", "fan.service"])
            # FIX: ngat hen 5V tai chan ra PWM — KHONG chi set duty=0 (PWM
            # peripheral van hoat dong, mot so phan cung van giu 5V o ngo ra
            # quat). Phai disable hoan toan PWM channel.
            _pwm_apply_off()
            with _cache_lock:
                _status_cache['fan_mode'] = 'off'
                _status_cache['fan_percent'] = 0
                _status_cache['fan_rpm'] = 0
                _status_cache['fan_status'] = 'Dừng'
            return jsonify({"status": "success", "mode": "off"})

        elif mode == 'on':
            settings['mode'] = 'on'
            _save_fan_settings(settings)
            run_cmd(["systemctl", "stop", "fan.service"])
            # FIX: bắt PWM tu trạng thái disabled (lan tat truoc) -> phai dam bao
            # enable=1 sau khi set duty. Helper xu ly thu tu period/duty/enable.
            _pwm_apply_on(duty=10000, period=10000)
            with _cache_lock:
                _status_cache['fan_mode'] = 'on'
                _status_cache['fan_percent'] = 100
                _status_cache['fan_rpm'] = _fan_rpm_for_percent(100)
                _status_cache['fan_status'] = 'Đang chạy 100%'
            return jsonify({"status": "success", "mode": "on"})

        # Cap nhat lai fan info ngay cho cache
        try:
            cur_fan = get_fan_info()
            with _cache_lock:
                _status_cache['fan_rpm'] = cur_fan.get('rpm')
                _status_cache['fan_percent'] = cur_fan.get('percent')
                _status_cache['fan_status'] = cur_fan.get('status', '--')
        except Exception:
            pass
            
        return jsonify({"error": "Chế độ không hợp lệ"}), 400
    except Exception as e:
        return jsonify({"error": str(e)}), 500

# ============ TORRENT (qBittorrent) ============

@app.route("/api/torrent/control", methods=["POST"])
@requires_auth
def api_torrent_control():
    try:
        import urllib.request, urllib.parse
        data = request.get_json(force=True) or {}
        action = data.get("action", "")
        torrent_hash = data.get("hash", "")

        if not torrent_hash:
            return jsonify({"error": "Thiếu hash"}), 400

        qbt_base = "http://127.0.0.1:8080/api/v2"
        try:
            qbt_user, qbt_pass = _get_qbt_credentials()
        except ValueError as ve:
            return jsonify({"error": str(ve)}), 500

        # Step 1: Login to qBittorrent to get SID cookie
        login_data = urllib.parse.urlencode({"username": qbt_user, "password": qbt_pass}).encode("utf-8")
        login_req = urllib.request.Request("%s/auth/login" % qbt_base, data=login_data)
        login_resp = urllib.request.urlopen(login_req, timeout=5)
        sid_cookie = ""
        for header in login_resp.info().get_all("Set-Cookie") or []:
            if "SID=" in header:
                sid_cookie = header.split("SID=")[1].split(";")[0]
                break

        # Stệp 2: Map Android actions to qBittorrent v5 API endpoints
        # qBt v5.x renamed: pause -> stop, resume -> start
        if action == "pause":
            url = "%s/torrents/stop" % qbt_base
        elif action == "resume":
            url = "%s/torrents/start" % qbt_base
        elif action == "delete":
            url = "%s/torrents/delete" % qbt_base
        else:
            return jsonify({"error": "Hành động không xác định"}), 400

        # Stệp 3: Send POST with form-encoded body + SID cookie
        # (SID cookie chi duoc lay tu qBittorrent localhost auth login, khong tu file cookie trinh duyet.)
        _audit_cookie_access("qbt_sid", qbt_base, bool(sid_cookie), "qbittorrent_local_auth" if sid_cookie else "no_sid_received")
        post_data = {"hashes": torrent_hash}
        if action == "delete":
            post_data["deleteFiles"] = "false"
        encoded = urllib.parse.urlencode(post_data).encode("utf-8")
        req = urllib.request.Request(url, data=encoded, headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Cookie": "SID=%s" % sid_cookie
        })
        with urllib.request.urlopen(req, timeout=5) as resp:
            pass
        return jsonify({"result": "ok"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500



# ============ TAI XUONG TU XA ============

@app.route("/api/download", methods=["POST"])
@requires_auth
def api_download():
    try:
        import urllib.request
        import urllib.parse
        data = request.get_json(force=True) or {}
        link = data.get("url", "")
        if not link:
            return jsonify({"error": "Thiếu URL"}), 400
        # CC-005 P1: validate URL (magnet/http(s) + social allowlist cho http)
        # — truoc day url bat ky forward sang qBT: SSRF qua qBT fetch URL
        # noi bo + 500 lo str(e) stack/path noi bo.
        try:
            _dl = (link or "").strip()
            if _dl.startswith("magnet:"):
                pass
            elif not _social_validate_url(_dl):
                return jsonify({"error": "URL khong duoc ho tro hoac khong an toan"}), 400
        except Exception:
            return jsonify({"error": "URL khong duoc ho tro hoac khong an toan"}), 400

        qbt_url = "http://127.0.0.1:8080/api/v2/torrents/add"
        post_data = urllib.parse.urlencode({"urls": link}).encode()
        req = urllib.request.Request(qbt_url, data=post_data)
        urllib.request.urlopen(req, timeout=10)
        return jsonify({"result": "ok"})
    except Exception as e:
        log.warning("[Download] qBT forward loi: %s", e)
        return jsonify({"error": "Khong gui duoc xuong qBittorrent"}), 502



def _social_worker(job_id, url, folder):
    """
    Download one public URL via yt-dlp and move the result into WebDAV.
    """
    import subprocess
    import time as _time
    import queue as _queue
    import re as _social_re
    import signal as _signal
    from collections import deque as _deque

    proc = None

    def _terminate_and_reap(child):
        """End a managed process group and always collect the child status."""
        if child is None:
            return
        try:
            running = child.poll() is None
        except Exception:
            running = True
        if running:
            try:
                os.killpg(child.pid, _signal.SIGTERM)
            except Exception:
                try:
                    child.terminate()
                except Exception:
                    pass
            try:
                child.wait(timeout=5)
                return
            except subprocess.TimeoutExpired:
                pass
            except Exception:
                pass
            try:
                os.killpg(child.pid, _signal.SIGKILL)
            except Exception:
                try:
                    child.kill()
                except Exception:
                    pass
        try:
            child.wait()
        except Exception:
            pass

    def _set_error(msg):
        _social_update_job(
            job_id,
            status='error',
            error_reason=msg[:200],
            finished_at=_time.time(),
        )

    tmp_dir = None
    try:
        if not _social_validate_url(url):
            _set_error('URL khong con tro toi dia chi public hop le.')
            return

        tmp_dir = os.path.join(_SOCIAL_TMP_ROOT, job_id)
        _social_mkdirp(tmp_dir)

        ytdlp_bin = _find_ytdlp_bin()
        if not ytdlp_bin:
            _set_error('yt-dlp khong tim thay tren NAS.')
            return

        platform = _detect_platform(url)
        _social_update_job(job_id, platform=platform)

        # Fallback to RapidAPI (third-party) for Facebook if no Facebook cookies are present
        # This helps bypass Facebook's strict bot protection and Cobalt's shutdown
        cookies_path = os.path.join(WEBDAV_FILE_ROOT, "cookies.txt")
        has_fb_cookies = False
        if os.path.exists(cookies_path) and os.access(cookies_path, os.R_OK):
            try:
                _ensure_cookie_guard()
                ok = _cookie_path_is_confined(cookies_path)
                _audit_cookie_access("read", cookies_path, ok, "check_facebook_cookies" if ok else "path_outside_allowed_dirs")
                if not ok:
                    has_fb_cookies = False
                else:
                    with open(cookies_path, 'r', encoding='utf-8', errors='ignore') as f:
                        if '.facebook.com' in f.read():
                            has_fb_cookies = True
            except Exception:
                pass

        if platform == "facebook" and not has_fb_cookies:
            rapidapi_key = os.environ.get('RAPIDAPI_KEY')
            if rapidapi_key:
                import urllib.request, urllib.parse, json
                rapidapi_host = os.environ.get('RAPIDAPI_HOST', 'facebook-video-downloader-api.p.rapidapi.com')
                rapidapi_endpoint = os.environ.get('RAPIDAPI_ENDPOINT', 'https://' + rapidapi_host + '/fb/video/')
                try:
                    # Xây dựng HTTP Request chuẩn cho RapidAPI
                    api_url = rapidapi_endpoint + "?url=" + urllib.parse.quote(url)
                    req = urllib.request.Request(
                        api_url,
                        headers={
                            'X-RapidAPI-Key': rapidapi_key,
                            'X-RapidAPI-Host': rapidapi_host,
                            'User-Agent': 'Mozilla/5.0'
                        }
                    )
                    with urllib.request.urlopen(req, timeout=15) as response:
                        if response.status == 200:
                            resp_data = json.loads(response.read().decode('utf-8'))
                            # Lấy URL trực tiếp tùy theo cấu trúc JSON trả về của từng API
                            _rapid_url = None
                            if 'url' in resp_data:
                                _rapid_url = resp_data['url']
                            elif 'hd' in resp_data:
                                _rapid_url = resp_data['hd']
                            elif 'sd' in resp_data:
                                _rapid_url = resp_data['sd']
                            # CC-005 P1: re-validate URL tu RapidAPI (third-party JSON
                            # co the tra file:// hoac internal IP -> SSRF/HDD fill)
                            if _rapid_url and _social_validate_url(str(_rapid_url)):
                                url = _rapid_url
                            elif _rapid_url:
                                log.warning('[Social] RapidAPI tra URL khong an toan, giu URL goc')
                except Exception as e:
                    log.error('[Social] RapidAPI fallback failed: %s', e)

        output_template = os.path.join(tmp_dir, '%(title)s.%(ext)s')
        try:
            _social_filesize_cap = _social_effective_max_filesize()
        except Exception:
            _social_filesize_cap = "2G"
        cmd = [
            ytdlp_bin,
            '-o', output_template,
            '--no-playlist',
            '--newline',
            '--socket-timeout', '60',
            '--retries', '3',
            '--max-filesize', _social_filesize_cap,
            # Prefer pre-merged single-file formats so we do not require ffmpeg.
            # `b` = best single file with audio+video already muxed; we steer
            # toward mp4/webm containers that ship as one file. If only split
            # streams exist, yt-dlp falls back to bv* which may require ffmpeg
            # to merge — on NAS without ffmpeg the merge step fails. The spec
            # §3.3 explicitly accepts video-only results, so this fallback is
            # acceptable.
            '-f', 'b[ext=mp4]/b[ext=webm]/b/bv*',
            '--ignore-errors',
            '--no-warnings',
        ]

        cookies_path = os.path.join(WEBDAV_FILE_ROOT, "cookies.txt")
        _ensure_cookie_guard()
        _cookie_ok = _cookie_path_is_confined(cookies_path)
        _audit_cookie_access("read", cookies_path, _cookie_ok, "pass_to_ytdlp" if _cookie_ok else "path_outside_allowed_dirs")
        if _cookie_ok and os.path.exists(cookies_path) and os.access(cookies_path, os.R_OK):
            cmd.extend(["--cookies", cookies_path])

        if platform == "tiktok":
            cmd.extend([
                "--user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "--add-header", "Referer: https://www.tiktok.com/"
            ])
        elif platform == "facebook":
            cmd.extend([
                "--user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            ])
        cmd.extend(["--", url])

        _social_update_job(job_id, status='downloading')

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            close_fds=True,
            start_new_session=True,
        )
        stdout_lines = _deque(maxlen=200)
        output_queue = _queue.Queue()

        def _record_output(output_line):
            stdout_lines.append(output_line)
            try:
                decoded = output_line.decode('utf-8', errors='replace')
                match = _social_re.search(r'\[download\]\s+(\d+(?:\.\d+)?)%', decoded)
                if match:
                    progress = max(0, min(99, int(float(match.group(1)))))
                    _social_update_job(job_id, progress=progress)
            except Exception:
                pass

        def _read_output():
            try:
                for output_line in iter(proc.stdout.readline, b''):
                    output_queue.put(output_line)
            except Exception:
                pass

        reader = threading.Thread(target=_read_output, name='social_out_%s' % job_id)
        reader.daemon = True
        reader.start()

        started_at = _time.monotonic()
        timed_out = False
        try:
            _social_timeout_cap = _social_effective_ytdlp_timeout_s()
        except Exception:
            _social_timeout_cap = 300
        while proc.poll() is None:
            while True:
                try:
                    _record_output(output_queue.get_nowait())
                except _queue.Empty:
                    break
            if _time.monotonic() - started_at >= _social_timeout_cap:
                timed_out = True
                _terminate_and_reap(proc)
                break
            _time.sleep(0.05)

        reader.join(timeout=1)
        while True:
            try:
                _record_output(output_queue.get_nowait())
            except _queue.Empty:
                break

        if timed_out:
            _set_error('yt-dlp timeout sau %d giay.' % _social_timeout_cap)
            return

        proc.wait()
        yt_exit = proc.returncode

        if yt_exit != 0:
            output_all = b''.join(stdout_lines).decode('utf-8', errors='replace')
            _set_error('yt-dlp exit %d: %s' % (yt_exit, output_all[-300:]))
            log.warning('[Social] yt-dlp failed job=%s exit=%d', job_id, yt_exit)
            return

        downloaded_files = []
        try:
            for fname in os.listdir(tmp_dir):
                fpath = os.path.join(tmp_dir, fname)
                if os.path.isfile(fpath):
                    downloaded_files.append((fname, os.path.getsize(fpath)))
        except OSError:
            pass

        if not downloaded_files:
            _set_error('yt-dlp chay thanh cong nhung khong tim thay file.')
            return

        webdav_dest_base = _social_destination_dir(folder)
        if webdav_dest_base is None:
            _set_error('Thu muc dich nam ngoai WebDAV root.')
            return
        if not _social_mkdirp(webdav_dest_base):
            _set_error('Khong tao duoc thu muc dich trong WebDAV.')
            return
        webdav_dest_base = _social_destination_dir(folder)
        if webdav_dest_base is None:
            _set_error('Thu muc dich khong con nam trong WebDAV root.')
            return

        saved_files = []
        total_size = 0
        move_errors = []
        changed_dirs = set()
        for fname, fsize in downloaded_files:
            src_path = os.path.join(tmp_dir, fname)
            dst_path = _social_destination_path(webdav_dest_base, fname, job_id)
            try:
                shutil.move(src_path, dst_path)
                changed_dirs.add(os.path.dirname(dst_path))
                saved_files.append(os.path.basename(dst_path))
                total_size += fsize
                log.info('[Social] Saved %s (%d bytes)', dst_path, fsize)
            except OSError as mv_err:
                log.warning('[Social] Move failed: %s', mv_err)
                move_errors.append('%s: %s' % (fname, str(mv_err)))

        _index_mutated_directories(changed_dirs)
        if move_errors:
            _set_error('Khong luu duoc tep vao WebDAV: %s' % '; '.join(move_errors))
            return

        _social_update_job(
            job_id,
            status='completed',
            filename=', '.join(saved_files),
            size=total_size,
            progress=100,
            finished_at=_time.time(),
        )
        log.info('[Social] Job=%s completed: %s', job_id, saved_files)

    except Exception as e:
        log.error('[Social] Worker exception job=%s: %s', job_id, e)
        _set_error('Worker exception: %s' % str(e)[:200])
    finally:
        _terminate_and_reap(proc)
        if tmp_dir:
            try:
                shutil.rmtree(tmp_dir)
            except OSError:
                pass


@app.route("/api/social/download", methods=["POST"])
@requires_auth
def api_social_download():
    """
    Nhan URL, tao async job, tra ve job_id ngay.
    Android poll /api/social/status/<job_id> de theo doi tien trinh.
    """
    try:
        data = request.get_json(force=True) or {}
        url = (data.get('url') or '').strip()
        folder = (data.get('folder') or '').strip()

        if not url:
            return jsonify({'error': 'Thieu URL.'}), 400

        job_id, err = _social_create_job(url, folder)
        if err:
            if 'Max concurrent' in err:
                # SPEC_LIVESTREAM_LIMITS §3.3: 429 co cau truc (client cu van doc error).
                try:
                    _soc_cap = _social_effective_max_concurrent()
                except Exception:
                    _soc_cap = 2
                try:
                    _soc_active = sum(1 for _j in _social_download_jobs.values()
                                      if _j.get("status") in ("queued", "downloading"))
                except Exception:
                    _soc_active = _soc_cap
                return jsonify({'error': err, 'reason': 'max_concurrent',
                                'code': 'MAX_DOWNLOADS', 'cap': _soc_cap,
                                'active': _soc_active, 'retry_after_s': 60}), 429
            return jsonify({'error': err}), 400

        safe_folder = _social_get_job(job_id)['folder']

        t = threading.Thread(
            target=_social_worker,
            args=(job_id, url, safe_folder),
            name='social_dl_%s' % job_id,
            daemon=True,
        )
        try:
            t.start()
        except Exception as start_err:
            _social_update_job(
                job_id,
                status='error',
                error_reason='Khong khoi dong duoc worker: %s' % str(start_err)[:160],
                finished_at=time.time(),
            )
            raise

        return jsonify({
            'result': 'ok',
            'job_id': job_id,
            'status': 'queued',
            'url': url,
        }), 202

    except Exception as e:
        log.error('[Social] download endpoint error: %s', e)
        return jsonify({'error': str(e)}), 500


@app.route("/api/social/status/<job_id>", methods=["GET"])
@requires_auth
def api_social_status(job_id):
    """Tra ve trang thai job theo job_id."""
    job = _social_get_job(job_id)
    if job is None:
        return jsonify({'error': 'Job not found.'}), 404

    return jsonify({
        'job_id': job['job_id'],
        'status': job['status'],
        'progress': job.get('progress', 0),
        'filename': job.get('filename'),
        'size': job.get('size', 0),
        'platform': job.get('platform', 'other'),
        'error_reason': job.get('error_reason'),
        'started_at': job.get('started_at'),
        'finished_at': job.get('finished_at'),
    }), 200

@app.route("/api/torrent/add_file", methods=["POST"])
@requires_auth
def api_torrent_add_file():
    """Upload mot file .torrent va forward sang qBittorrent."""
    try:
        if "file" not in request.files:
            return jsonify({"error": "Không có file .torrent trong request"}), 400
        f = request.files["file"]
        fname = (f.filename or "").strip()
        if not fname:
            return jsonify({"error": "File không có tên"}), 400
        if not fname.lower().endswith(".torrent"):
            return jsonify({"error": "File phai co duoi .torrent"}), 400
        # CC-005 P1: sanitize filename (header injection CRLF/quote sang qBT)
        # — truoc day ghep tho fname vao multipart header. Chi giu ten base.
        import re as _re_tfn
        fname = os.path.basename(fname)
        fname = _re_tfn.sub(r"[^A-Za-z0-9_.\-]", "_", fname)[:128]
        if not fname.lower().endswith(".torrent") or not fname:
            return jsonify({"error": "Ten file khong hop le"}), 400
        content = f.read(50 * 1024 * 1024 + 1)
        if len(content) > 50 * 1024 * 1024:
            return jsonify({"error": "File torrent vượt quá giới hạn 50MB"}), 413
        if not content or len(content) < 64:
            return jsonify({"error": "File torrent rong hoac qua nho"}), 400
        # qBittorrent magic: torrent file bat dau bang 'd' (bencode dict)
        if content[0:1] != b"d":
            return jsonify({"error": "File không phải bencode torrent hợp lệ"}), 400

        # Build multipart de forward sang qBittorrent
        import urllib.request as _urlreq
        boundary = "----nas_api_torrent_boundary_%d" % int(time.time())
        body = b""
        body += ("--%s\r\n" % boundary).encode()
        body += ('Content-Disposition: form-data; name="torrents"; filename="%s"\r\n' % fname).encode()
        body += b"Content-Type: application/x-bittorrent\r\n\r\n"
        body += content
        body += ("\r\n--%s--\r\n" % boundary).encode()

        req_obj = _urlreq.Request(
            "http://127.0.0.1:8080/api/v2/torrents/add",
            data=body,
            headers={
                "Content-Type": "multipart/form-data; boundary=%s" % boundary,
                "Content-Length": str(len(body)),
            }
        )
        try:
            resp = _urlreq.urlopen(req_obj, timeout=30)
            qbt_response = resp.read().decode("utf-8", errors="ignore")
            resp.close()
            # qBittorrent tra "Ok." khi thảnh cầng, "Fails." khi lỗi
            if "Ok" in qbt_response or resp.getcode() == 200:
                return jsonify({"result": "ok", "filename": fname, "size": len(content)})
            return jsonify({"error": "qBittorrent tu choi: %s" % qbt_response[:200]}), 502
        except urllib.error.HTTPError as he:
            return jsonify({"error": "qBittorrent HTTP %d" % he.code}), 502
    except Exception as e:
        log.warning("[Torrent] add_file lỗi: %s", e)
        return jsonify({"error": str(e)[:200]}), 500


# ============ GIAI NEN FILE ============

@app.route("/api/file/unzip", methods=["POST"])
@requires_auth
def api_unzip():
    try:
        data = request.get_json(force=True) or {}
        raw_path = data.get("path", "") or data.get("file_path", "")
        if not raw_path:
            return jsonify({"error": "Khong tim thay tep"}), 404
        # P2-9: thong nhat hop dong path — Android gui relative WebDAV path
        # ("/Documents/a.zip", sau /webdav), khong phai filesystem path. Anh xa
        # ve WEBDAV_ROOT + kiem tra containment (chong traversal); giu tuong
        # thich absolute cu (validate truc tiep).
        file_path = _resolve_webdav_request_path(raw_path)
        if not file_path:
            if os.path.isabs(raw_path) and _validate_file_path(raw_path) and os.path.exists(raw_path):
                file_path = os.path.realpath(raw_path)
            else:
                return jsonify({"error": "Khong tim thay tep"}), 404
        if not _validate_file_path(file_path):
            return jsonify({"error": "Duong dan tep khong hop le"}), 403

        dest_dir = os.path.realpath(os.path.dirname(file_path))
        ext = file_path.lower()
        extracted = 0
        if ext.endswith(".zip"):
            extracted = _extract_zip_safe(file_path, dest_dir)
        elif ext.endswith((".tar.gz", ".tgz", ".tar", ".tar.bz2", ".tbz2", ".txz")):
            extracted = _extract_tar_safe(file_path, dest_dir)
        elif ext.endswith(".rar"):
            tool_path = shutil.which("7z") or shutil.which("7zz")
            if not tool_path:
                return jsonify({"error": "Missing 7z for safe RAR extraction"}), 503
            extracted = _extract_external_archive_safe(file_path, dest_dir, tool_path)
        elif ext.endswith(".7z"):
            tool_path = shutil.which("7z") or shutil.which("7zz")
            if not tool_path:
                return jsonify({"error": "Missing 7z for safe 7z extraction"}), 503
            extracted = _extract_external_archive_safe(file_path, dest_dir, tool_path)
        else:
            return jsonify({"error": "Dinh dang khong duoc ho tro"}), 400

        return jsonify({"result": "ok", "extracted": extracted})
    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        return jsonify({"error": str(e)}), 500

# ============ BAO MAT ============

@app.route("/api/auth/approve_ip", methods=["POST"])
@requires_admin
def api_approve_ip():
    try:
        data = request.get_json(force=True) or {}
        ip = str(data.get("ip", "") or "").strip()
        approved = data.get("approved", True)
        if not ip or not _validate_ip(ip):
            return jsonify({"error": "IP không hợp lệ"}), 400
        if ip:
            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
            cur = conn.cursor()
            if approved:
                _remember_authorized_ip(ip)
                cur.execute('INSERT OR REPLACE INTO authorized_ips VALUES (?, ?)', (ip, datetime.datetime.now()))
                cur.execute('DELETE FROM auth_attempts WHERE ip=?', (ip,))
                try:
                    subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'DROP'], stderr=subprocess.DEVNULL)
                except Exception:
                    pass
                cur.execute('DELETE FROM banned_ips WHERE ip=?', (ip,))
                if ip not in _lan_whitelist:
                    _lan_whitelist.add(ip)
                    _save_lan_whitelist()
                now = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                           ("SUCCESS", "Security", "[{}] Admin da CAP QUYEN cho IP: {} va them vao whitelist.".format(now, ip)))
            else:
                ban_ip_permanently(ip)
                now = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                cur.execute('INSERT OR REPLACE INTO banned_ips VALUES (?, ?, ?)', (ip, "Admin denied", now))
                cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                           ("ERROR", "Security", "[{}] Admin da CHAN VINH VIEN IP: {} bang iptables.".format(now, ip)))
            conn.commit()
            conn.close()
        return jsonify({"result": "ok", "status": "Approved" if approved else "Banned"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

# (DA XOA: Endpoint /api/lan/whitelist trung lap - da gop vao phien ban tach GET/POST/DELETE rieng biet phia duoi)

# ============ BAO CAO TUAN ============

@app.route("/api/system/weekly_report")
@requires_auth
def api_weekly_report():
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        cur = conn.cursor()
        cur.execute("SELECT COUNT(*) FROM banned_ips WHERE banned_at >= date('now', '-7 days')")
        row = cur.fetchone()
        banned = row[0] if row else 0
        cur.execute("SELECT message FROM system_logs WHERE module='DuplicateScan' AND type='SUCCESS' AND timestamp >= date('now', '-7 days')")
        freed_mb = sum([int(m[0].split("xóa ")[1].split("MB")[0]) for m in cur.fetchall() if "MB" in m[0] and "xóa " in m[0]])
        conn.close()
        return jsonify({"banned_count": banned, "freed_space": "{} MB".format(freed_mb) if freed_mb < 1024 else "{:.1f} GB".format(freed_mb/1024)})
    except Exception:
        return jsonify({"banned_count": 0, "freed_space": "0 MB"})

# ============ CANH BAO CHU DONG (PROACTIVE ALERTS) ============

@app.route("/api/alerts/poll")
@requires_auth
def api_alerts_poll():
    """
    Android WorkManager goi endpoint nay dinh ky (moi 15 phut).
    Tr? v? danh sách cầnh b?o moi ch?a Đọc + trạng thái h? thỏng hien tai.
    """
    since_ts = request.args.get("since", "")  # L?y cầnh b?o ke tu timestamp nay
    with _alert_state_lock:
        alerts = list(_alert_states["last_alerts"])
        ai_running = _alert_states["ai_scan_running"]

    # L?c cầnh b?o theo timestamp neu co tham so 'since'
    if since_ts:
        try:
            cutoff = datetime.datetime.strptime(since_ts, "%d/%m/%Y %H:%M:%S")
            filtered = []
            for a in alerts:
                try:
                    at = datetime.datetime.strptime(a["timestamp"], "%d/%m/%Y %H:%M:%S")
                    if at > cutoff:
                        filtered.append(a)
                except Exception:
                    filtered.append(a)
            alerts = filtered
        except Exception:
            pass

    # L?y trạng thái HDD hien tai (tu cache, không wake HDD)
    with _cache_lock:
        hdd_temp = _status_cache.get("temperature", "--°C")
        nas_status = _status_cache.get("status", "Online")

    return jsonify({
        "alerts": alerts,
        "hdd_temp": hdd_temp,
        "nas_online": True,
        "ai_scanning": ai_running,
        "server_time": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
    })


@app.route("/api/alerts/clear", methods=["POST"])
@requires_auth
def api_alerts_clear():
    """Xo? hang doi cầnh b?o sau khi Android da xu ly."""
    with _alert_state_lock:
        _alert_states["last_alerts"] = []
    return jsonify({"result": "ok"})


@app.route("/api/cron/status")
@requires_auth
def api_cron_status():
    """Trạng thái cua cron worker: luc don rac gần nh?t, AI quet lan cuoi."""
    with _alert_state_lock:
        last_clean = _alert_states["trash_last_clean"]
        last_ai = _alert_states["ai_last_scan"]
        ai_running = _alert_states["ai_scan_running"]

    def fmt_ts(ts):
        if ts == 0:
            return "Chưa chạy"
        return datetime.datetime.fromtimestamp(ts).strftime("%d/%m/%Y %H:%M:%S")

    return jsonify({
        "trash_last_clean": fmt_ts(last_clean),
        "ai_last_scan": fmt_ts(last_ai),
        "ai_running": ai_running,
    })


@app.route('/api/system/temperature_history', methods=['GET'])
@requires_auth
def api_temperature_history():
    """L?y lich su nhi?t để (120 diem gần nh?t tuong duong khoang 2 tieng)."""
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        cur = conn.cursor()
        cur.execute("SELECT strftime('%H:%M', timestamp), cpu_temp, hdd_temp FROM system_temperature_history ORDER BY id DESC LIMIT 120")
        rows = cur.fetchall()
        conn.close()
        
        # Rows dang DESC, reverse thảnh ASC de ve bieu do dien tien xuoi
        rows.reverse()
        history = [{"time": r[0], "cpu": round(r[1], 1), "hdd": round(r[2], 1)} for r in rows]
        return jsonify({"history": history})
    except Exception as e:
        return jsonify({"history": [], "error": "Không tải được lịch sử nhiệt độ: %s" % normalize_vietnamese_message(str(e))})

@app.route("/api/cron/trash/clean", methods=["POST"])
@requires_admin
def api_cron_trash_clean():
    """Kich hoat thu cong don dep Thung rac ngay lap tuc (không cần doi cron)."""
    try:
        data = request.get_json(force=True) or {}
        max_days = int(data.get("max_age_days", 30))
        deleted = _clean_trash(WEBDAV_FILE_ROOT, max_age_days=max_days)
        msg = "Đã xóa %d tệp trong Thùng rác (quá %d ngày)." % (deleted, max_days)
        _push_alert("TRASH_CLEANED", msg, "INFO")
        return jsonify({"result": "ok", "deleted": deleted, "message": msg})
    except Exception as e:
        return jsonify({"result": "error", "message": "Không dọn được Thùng rác: %s" % normalize_vietnamese_message(str(e))}), 500

@app.route("/api/system_logs", methods=["GET"])
@requires_auth
def api_system_logs():
    """Tr? v? danh sách nhat ky h? thỏng (AccessLog, DuplicateScan...) tu NAS."""
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        try:
            cur = conn.cursor()
            cur.execute("SELECT id, type, module, message, timestamp FROM system_logs ORDER BY id DESC LIMIT 50")
            logs = [{"id": r[0], "type": r[1], "module": r[2], "message": normalize_vietnamese_message(r[3]), "timestamp": str(r[4])[:19]} for r in cur.fetchall()]
        finally:
            conn.close()
        return jsonify({"status": "success", "logs": logs})
    except Exception as e:
        return jsonify({"status": "error", "message": "Không tải được nhật ký hệ thống: %s" % normalize_vietnamese_message(str(e))}), 500

@app.route("/api/system_logs/clear", methods=["POST"])
@requires_admin
def api_system_logs_clear():
    """Xoa toan bo nhat ky he thong tren NAS."""
    try:
        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
        try:
            cur = conn.cursor()
            cur.execute("DELETE FROM system_logs")
            conn.commit()
        finally:
            conn.close()
        return jsonify({"status": "success", "message": "Đã dọn sạch nhật ký trên NAS."})
    except Exception as e:
        return jsonify({"status": "error", "message": "Lỗi xóa nhật ký trên NAS: %s" % normalize_vietnamese_message(str(e))}), 500


# ============ SMART PHOTOS (GALLERY KHAM PHA) ============
# Phan loai ảnh nh? theo cau truc thư mục — Python 3.5, KHÔNG cần Docker/TFLite

@app.route("/api/ai/tags")
@requires_auth
def api_ai_tags():
    """Tra ve phan loai anh. Doc tu bang ai_image_tags trong nas_index.db (catalogue)
    thay boi doc file ai_tags.json. Neu DB chua co du lieu, fallback ve file JSON cu."""
    try:
        root = _get_webdav_public_dir()
        if not root:
            return jsonify({
                "categories": {},
                "total": 0,
                "status": "WebDAV root chua san sang",
                "ai_running": _alert_states.get("ai_scan_running", False)
            })
        root_real = os.path.realpath(root)
        conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
        try:
            state = conn.execute("SELECT root,generation,ready,catalogue_revision,scanned_at FROM index_state WHERE id=1").fetchone()
            catalogue_revision = state[3] if state else 0
            rows = conn.execute(
                "SELECT path,category FROM ai_image_tags WHERE catalogue_revision=? ORDER BY category,path",
                (catalogue_revision,)).fetchall()
        finally:
            conn.close()
        categories = {}
        total = 0
        for fpath, cat in rows:
            categories.setdefault(cat, []).append(fpath)
            total += 1
        if categories:
            return jsonify({
                "categories": categories,
                "total": total,
                "status": "ok",
                "catalogue_revision": catalogue_revision,
                "last_scan": _alert_states.get("ai_last_scan", 0) and
                             datetime.datetime.fromtimestamp(_alert_states.get("ai_last_scan", 0)).strftime("%d/%m/%Y %H:%M") or "",
                "ai_running": _alert_states.get("ai_scan_running", False),
                "source": "file_index_db",
            })
        # Fallback ve file JSON cu (client cu hoac giai cap phat neu DB chua du lieu).
        if os.path.exists(AI_TAGS_PATH):
            try:
                mtime = os.path.getmtime(AI_TAGS_PATH)
                with open(AI_TAGS_PATH, "r") as f:
                    tags_data = json.load(f)
                return jsonify({
                    "categories": tags_data.get("categories", {}),
                    "total": tags_data.get("total", 0),
                    "status": "ok",
                    "last_scan": datetime.datetime.fromtimestamp(mtime).strftime("%d/%m/%Y %H:%M"),
                    "ai_running": _alert_states.get("ai_scan_running", False),
                    "source": "ai_tags_json_fallback",
                })
            except Exception:
                pass
        return jsonify({
            "categories": {},
            "total": 0,
            "status": "Chua co du lieu. Nhan nut Quet de phan loai anh.",
            "ai_running": _alert_states.get("ai_scan_running", False),
        })
    except Exception as e:
        return jsonify({"categories": {}, "total": 0, "status": "Loi: %s" % normalize_vietnamese_message(str(e))}), 500


@app.route("/api/ai/trigger", methods=["POST"])
@requires_auth
def api_ai_trigger():
    """Kich hoat quet phan loai ảnh tren NAS (chay nen, Python 3.5 thuan)."""
    with _alert_state_lock:
        running = _alert_states["ai_scan_running"]
    if running:
        return jsonify({"result": "already_running", "message": "Đang quét ảnh, vui lòng chờ."})
    # Chay scan tren thread rieng de khong block API response
    def _bg_scan():
        ok = _scan_photos_lightweight()
        if ok:
            _push_alert("AI_SCAN_DONE", "Smart Gallery: Đã phân loại xong ảnh theo thư mục.", "SUCCESS")
    threading.Thread(target=_bg_scan, daemon=True).start()
    return jsonify({"result": "ok", "message": "Đang quét và phân loại ảnh. Kết quả sẽ có trong vài phút."})


@app.route("/api/ai/status")
@requires_auth
def api_ai_status():
    """Trạng thái quet phan loai ảnh."""
    with _alert_state_lock:
        running = _alert_states["ai_scan_running"]
        last_scan = _alert_states["ai_last_scan"]
    last_scan_str = "Chưa quét" if last_scan == 0 else \
        datetime.datetime.fromtimestamp(last_scan).strftime("%d/%m/%Y %H:%M:%S")
    tags_exist = os.path.exists(AI_TAGS_PATH)
    return jsonify({
        "running": running,
        "last_scan": last_scan_str,
        "has_data": tags_exist
    })


def _resolve_webdav_request_path(webdav_path):
    """Map WebDAV URL path → filesystem path.

    FIX-SMB-PLAYBACK: Thử cả WebDAV root lẫn SMB share root.
    Vì sao: WebDAV root = /srv/.../New folder, SMB share = /srv/...
    File upload qua SMB sẽ ở /srv/.../<path> nhưng get_webdav_root() trỏ vào
    /srv/.../New folder/<path> → /api/media 404 → video "không phát".
    Fallback: nếu WebDAV root không có file, thử resolve dưới các SMB share roots.
    """
    if not webdav_path:
        return None
    decoded = urllib.parse.unquote(webdav_path)
    if decoded.startswith("/webdav"):
        decoded = decoded[len("/webdav"):]
    decoded = decoded.lstrip("/")

    def _safe(base_dir, rel):
        if not base_dir:
            return None
        real = os.path.realpath(os.path.join(base_dir, rel))
        if real != base_dir and not real.startswith(base_dir + os.sep):
            return None
        return real

    # 1. Thử WebDAV root trước
    real_path = _safe(os.path.realpath(get_webdav_root()), decoded)
    if real_path and os.path.isfile(real_path):
        return real_path

    # 2. FIX-SMB-PLAYBACK: fallback sang SMB share roots
    smb_roots = ["/srv/dev-disk-by-label-data", "/sharedfolders/Data", "/var/www/webdav/public"]
    for root in smb_roots:
        if not os.path.isdir(root):
            continue
        cand = _safe(os.path.realpath(root), decoded)
        if cand and os.path.isfile(cand):
            return cand

    return real_path  # trả về None hoặc path WebDAV gốc (để caller trả 404)


def _media_cache_headers(real_path, file_size, mime_type):
    try:
        st = os.stat(real_path)
        mtime = int(st.st_mtime)
        ino = int(getattr(st, "st_ino", 0))
    except Exception:
        mtime = int(time.time())
        ino = 0
    # CC-004 P1-4: ETag them inode (size-mtime trung khi copy/replace cung
    # kich thuoc trong cung giay) + bo 'immutable' cho file <24h (livestream
    # dang ghi/moov dang hoan thien) de ExoPlayer/VLC khong cache ban hong.
    etag = '"%x-%x-%x"' % (file_size, mtime, ino & 0xFFFFFFFF)
    try:
        age_s = time.time() - mtime
    except Exception:
        age_s = 0
    if age_s < 86400:
        cache_ctl = "public, max-age=60"
    else:
        cache_ctl = "public, max-age=604800, immutable"
    return {
        "Accept-Ranges": "bytes",
        "Cache-Control": cache_ctl,
        "ETag": etag,
        "Last-Modified": datetime.datetime.utcfromtimestamp(mtime).strftime("%a, %d %b %Y %H:%M:%S GMT"),
        "Content-Type": mime_type,
        "X-Content-Type-Options": "nosniff",
    }


def _iter_file_range(real_path, start, end, chunk_size=4 * 1024 * 1024):
    try:
        f = open(real_path, "rb")
    except Exception:
        return
    try:
        f.seek(start)
        remaining = end - start + 1
        while remaining > 0:
            chunk = f.read(min(chunk_size, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk
    finally:
        f.close()


@app.route("/api/media", methods=["GET", "HEAD"])
@requires_auth
def api_media_fast():
    """LAN-optimized media endpoint: Range/HEAD/ETag, no JSON wrapping, 1MB chunks."""
    real_path = _resolve_webdav_request_path(request.args.get("path", ""))
    if not real_path or not os.path.exists(real_path) or not os.path.isfile(real_path):
        return jsonify({"error": "Tệp không tồn tại"}), 404
    try:
        file_size = os.path.getsize(real_path)
    except Exception:
        return jsonify({"error": "Không đọc được kích thước tệp"}), 500

    mime_type = mimetypes.guess_type(real_path)[0] or "application/octet-stream"
    headers = _media_cache_headers(real_path, file_size, mime_type)
    # CC-004 P1-4b: ho tro If-Modified-Since (ExoPlayer/VLC gui kem) + If-None-Match
    # yeu (so sanh sau khi strip W/). Thieu: client cu phai tai lai full du lieu.
    try:
        inm = _re_module.sub(r'^W/\s*', '', (request.headers.get("If-None-Match") or "").strip()).strip()
        if inm and inm == headers["ETag"]:
            return Response(status=304, headers=headers)
        ims = (request.headers.get("If-Modified-Since") or "").strip()
        if ims and not inm and ims == headers.get("Last-Modified"):
            return Response(status=304, headers=headers)
    except Exception:
        pass

    range_header = request.headers.get("Range", "")
    start = 0
    end = max(0, file_size - 1)
    status = 200
    if range_header.startswith("bytes="):
        spec = range_header[6:].split(",", 1)[0].strip()
        try:
            left, right = spec.split("-", 1)
            if left == "":
                suffix = int(right)
                if suffix <= 0:
                    raise ValueError("bad suffix")
                start = max(0, file_size - suffix)
            else:
                start = int(left)
                if right:
                    end = min(end, int(right))
            if start < 0 or start >= file_size or end < start:
                h = dict(headers)
                h["Content-Range"] = "bytes */%d" % file_size
                return Response(status=416, headers=h)
            status = 206
        except Exception:
            h = dict(headers)
            h["Content-Range"] = "bytes */%d" % file_size
            return Response(status=416, headers=h)

    content_length = 0 if file_size == 0 else end - start + 1
    headers["Content-Length"] = str(content_length)
    if status == 206:
        headers["Content-Range"] = "bytes %d-%d/%d" % (start, end, file_size)
    if request.method == "HEAD":
        return Response(status=status, headers=headers)
    return Response(_iter_file_range(real_path, start, end), status=status, headers=headers, direct_passthrough=True)


_SCREEN_RECORD_ROOT = os.path.join(WEBDAV_FILE_ROOT, "ScreenRecord")
_SCREEN_RECORD_MAX_SESSIONS = 3
_SCREEN_RECORD_SEGMENT_MAX_BYTES = 64 * 1024 * 1024
_screen_record_locks = {}
_screen_record_global_lock = threading.Lock()


def _safe_screen_session_id(raw):
    cleaned = _re_module.sub(r"[^A-Za-z0-9_.-]+", "_", str(raw or ""))
    return cleaned[:80] or ("screen_%s" % datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))


def _screen_record_session_dir(session_id):
    sid = _safe_screen_session_id(session_id)
    root = os.path.realpath(_SCREEN_RECORD_ROOT)
    path = os.path.realpath(os.path.join(root, sid))
    if path != root and path.startswith(root + os.sep):
        return path
    return None


def _screen_record_lock(session_id):
    sid = _safe_screen_session_id(session_id)
    with _screen_record_global_lock:
        lock = _screen_record_locks.get(sid)
        if lock is None:
            lock = threading.Lock()
            _screen_record_locks[sid] = lock
        return lock


def _screen_record_drop_lock(session_id):
    sid = _safe_screen_session_id(session_id)
    with _screen_record_global_lock:
        _screen_record_locks.pop(sid, None)


def _screen_manifest_path(session_dir):
    return os.path.join(session_dir, "manifest.json")


def _read_screen_manifest(session_dir):
    path = _screen_manifest_path(session_dir)
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _write_screen_manifest(session_dir, manifest):
    os.makedirs(session_dir, exist_ok=True)
    tmp = _screen_manifest_path(session_dir) + ".tmp"
    manifest["updated_at"] = int(time.time())
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2, sort_keys=True)
    os.replace(tmp, _screen_manifest_path(session_dir))


def _active_screen_record_count():
    try:
        if not os.path.isdir(_SCREEN_RECORD_ROOT):
            return 0
        count = 0
        now = int(time.time())
        for name in os.listdir(_SCREEN_RECORD_ROOT):
            m = _read_screen_manifest(os.path.join(_SCREEN_RECORD_ROOT, name))
            if m.get("status") in ("recording", "finishing"):
                # Bỏ qua các session đã quá 5 phút không có cập nhật để tránh kẹt slot quay
                updated_at = int(m.get("updated_at") or m.get("created_at") or 0)
                if now - updated_at < 300:
                    count += 1
        return count
    except Exception:
        return 0


@app.route("/api/screen_record/start", methods=["POST"])
@requires_auth
def api_screen_record_start():
    try:
        body = request.get_json(silent=True) or {}
        if _active_screen_record_count() >= _SCREEN_RECORD_MAX_SESSIONS:
            return jsonify({"ok": False, "error": "NAS đang nhận tối đa phiên quay màn hình."}), 429
        sid = _safe_screen_session_id(body.get("session_id") or ("screen_%s" % datetime.datetime.now().strftime("%Y%m%d_%H%M%S")))
        session_dir = _screen_record_session_dir(sid)
        if not session_dir:
            return jsonify({"ok": False, "error": "Session không hợp lệ"}), 400
        with _screen_record_lock(sid):
            segments_dir = os.path.join(session_dir, "segments")
            os.makedirs(segments_dir, exist_ok=True)
            manifest = _read_screen_manifest(session_dir)
            if manifest.get("status") in ("recording", "finishing"):
                return jsonify({"ok": True, "session_id": sid, "resumed": True, "manifest": manifest})
            manifest = {
                "session_id": sid,
                "status": "recording",
                "created_at": int(time.time()),
                "segment_duration_ms": int(body.get("segment_duration_ms") or 5000),
                "width": int(body.get("width") or 0),
                "height": int(body.get("height") or 0),
                "bitrate": int(body.get("bitrate") or 0),
                "format": "mpeg2ts",
                "uploaded": [],
                "failed": [],
                "total_segments": 0,
                "final_ts": None,
                "final_mp4": None,
            }
            _write_screen_manifest(session_dir, manifest)
        # R6: block thumbnail co TTL (2h) thay vi vo han. Phien quay gui segment
        # moi vai giay -> heartbeat refresh TTL o endpoint segment. Neu app chet
        # / service loi khong finish/cancel, block tu het han thay vi ket vo han.
        _set_thumbnail_auto_block("screen_record", True, ttl_seconds=7200)
        return jsonify({"ok": True, "session_id": sid, "path": "ScreenRecord/%s" % sid})
    except Exception as e:
        log.warning("[ScreenRecord] start lỗi: %s", e)
        return jsonify({"ok": False, "error": str(e)[:160]}), 500


@app.route("/api/screen_record/segment", methods=["POST"])
@requires_auth
def api_screen_record_segment():
    sid = _safe_screen_session_id(request.args.get("session_id", ""))
    try:
        idx = int(request.args.get("index", "-1"))
    except Exception:
        idx = -1
    if not sid or idx < 0:
        return jsonify({"ok": False, "error": "Thiếu session_id hoặc index"}), 400
    session_dir = _screen_record_session_dir(sid)
    if not session_dir:
        return jsonify({"ok": False, "error": "Session không hợp lệ"}), 400
    sha_expected = request.args.get("sha256", "").strip().lower()
    duration_ms = int(request.args.get("duration_ms", "0") or 0)
    with _screen_record_lock(sid):
        manifest = _read_screen_manifest(session_dir)
        if manifest.get("status") not in ("recording", "finishing"):
            return jsonify({"ok": False, "error": "Phiên chưa bắt đầu hoặc đã kết thúc"}), 409
        segments_dir = os.path.join(session_dir, "segments")
        os.makedirs(segments_dir, exist_ok=True)
        final_path = os.path.join(segments_dir, "part_%06d.ts" % idx)
        tmp_path = final_path + ".part"
        hasher = hashlib.sha256()
        written = 0
        try:
            with open(tmp_path, "wb") as f:
                while True:
                    chunk = request.stream.read(1024 * 1024)
                    if not chunk:
                        break
                    written += len(chunk)
                    if written > _SCREEN_RECORD_SEGMENT_MAX_BYTES:
                        raise ValueError("Segment quá lớn")
                    hasher.update(chunk)
                    f.write(chunk)
            sha_actual = hasher.hexdigest()
            if sha_expected and sha_actual != sha_expected:
                try:
                    os.remove(tmp_path)
                except Exception:
                    pass
                return jsonify({"ok": False, "error": "Sai checksum", "sha256": sha_actual}), 400
            os.replace(tmp_path, final_path)
        except Exception as e:
            try:
                if os.path.exists(tmp_path):
                    os.remove(tmp_path)
            except Exception:
                pass
            return jsonify({"ok": False, "error": str(e)[:160]}), 500

        uploaded = manifest.get("uploaded") or []
        uploaded = [x for x in uploaded if int(x.get("index", -1)) != idx]
        uploaded.append({"index": idx, "bytes": written, "sha256": hasher.hexdigest(), "duration_ms": duration_ms})
        uploaded.sort(key=lambda x: int(x.get("index", 0)))
        manifest["uploaded"] = uploaded
        manifest["total_segments"] = max(int(manifest.get("total_segments") or 0), idx + 1)
        manifest["status"] = "recording"
        _write_screen_manifest(session_dir, manifest)
        # R6: heartbeat — phien van quay thi refresh TTL block thumbnail.
        _set_thumbnail_auto_block("screen_record", True, ttl_seconds=7200)
    return jsonify({"ok": True, "session_id": sid, "index": idx, "bytes": written})


def _screen_record_remux_worker(session_dir, sid, final_ts):
    import subprocess
    try:
        # Chờ hệ thống rảnh bớt nếu đang có livestream hoặc tác vụ nặng khác
        retry_count = 0
        while retry_count < 12:  # Thử trong 3 phút (12 * 15 giây)
            if _background_heavy_work_allowed(ignore_user_idle=True):
                break
            log.info("[ScreenRecord] Hệ thống đang bận/RAM cao, tạm hoãn remux phiên %s, thử lại sau 15 giây", sid)
            time.sleep(15)
            retry_count += 1

        final_mp4 = os.path.join(session_dir, "%s.mp4" % sid)
        cmd = ["/usr/bin/ffmpeg", "-y", "-i", final_ts, "-c", "copy", "-movflags", "+faststart", final_mp4]
        
        # Nếu hệ thống có lệnh "nice", chạy ffmpeg với nice -n 19 để giảm độ ưu tiên CPU cực đại, tránh lag/OOM
        nice_path = shutil.which("nice")
        if nice_path:
            cmd = [nice_path, "-n", "19"] + cmd

        log.info("[ScreenRecord] Bắt đầu remux phiên %s sang MP4: %s", sid, cmd)
        proc = subprocess.run(
            cmd,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=900
        )
        with _screen_record_lock(sid):
            manifest = _read_screen_manifest(session_dir)
            if proc.returncode == 0 and os.path.exists(final_mp4) and os.path.getsize(final_mp4) > 0:
                _index_mutated_directories((session_dir,))
                manifest["final_mp4"] = os.path.relpath(final_mp4, WEBDAV_FILE_ROOT).replace(os.sep, "/")
                manifest["remux_status"] = "done"
                log.info("[ScreenRecord] Remux thành công phiên %s sang MP4", sid)
            else:
                manifest["remux_status"] = "failed"
                log.warning("[ScreenRecord] Remux thất bại phiên %s, mã trả về: %s", sid, proc.returncode)
            _write_screen_manifest(session_dir, manifest)
    except Exception as e:
        log.warning("[ScreenRecord] remux lỗi: %s", e)
    finally:
        # Bắt buộc gỡ block thumbnail tại đây để đảm bảo tài nguyên được giải phóng hoàn toàn
        try:
            _set_thumbnail_auto_block("screen_record", False)
        except Exception as e:
            log.warning("[ScreenRecord] Lỗi unblock thumbnail: %s", e)


        _screen_record_drop_lock(sid)


def _screen_record_finish_worker(session_dir, sid, total):
    final_ts = os.path.join(session_dir, "%s.ts" % sid)
    tmp_ts = final_ts + ".part"
    segments_dir = os.path.join(session_dir, "segments")
    try:
        with open(tmp_ts, "wb") as out:
            for i in range(total):
                part = os.path.join(segments_dir, "part_%06d.ts" % i)
                with open(part, "rb") as f:
                    shutil.copyfileobj(f, out, 1024 * 1024)
        os.replace(tmp_ts, final_ts)

        try:
            shutil.rmtree(segments_dir)
            log.info("[ScreenRecord] Da don dep thu muc segments tam thoi: %s", segments_dir)
        except Exception as e:
            log.warning("[ScreenRecord] Khong the xoa thu muc segments tam thoi: %s", e)

        with _screen_record_lock(sid):
            manifest = _read_screen_manifest(session_dir)
            manifest["status"] = "done"
            manifest["final_ts"] = os.path.relpath(final_ts, WEBDAV_FILE_ROOT).replace(os.sep, "/")
            manifest["missing"] = []
            manifest["completed_at"] = int(time.time())
            _write_screen_manifest(session_dir, manifest)
        _index_mutated_directories((session_dir,))
        threading.Thread(target=_screen_record_remux_worker, args=(session_dir, sid, final_ts), daemon=True).start()
    except Exception as e:
        log.warning("[ScreenRecord] finish worker loi: %s", e)
        try:
            if os.path.exists(tmp_ts):
                os.remove(tmp_ts)
        except Exception:
            pass
        with _screen_record_lock(sid):
            manifest = _read_screen_manifest(session_dir)
            manifest["status"] = "error"
            manifest["error"] = str(e)[:160]
            manifest["finished_at"] = int(time.time())
            _write_screen_manifest(session_dir, manifest)
        try:
            _set_thumbnail_auto_block("screen_record", False)
        except Exception:
            pass
        _screen_record_drop_lock(sid)


@app.route("/api/screen_record/finish", methods=["POST"])
@requires_auth
def api_screen_record_finish():
    body = request.get_json(silent=True) or {}
    sid = _safe_screen_session_id(body.get("session_id") or request.args.get("session_id", ""))
    session_dir = _screen_record_session_dir(sid)
    if not session_dir:
        return jsonify({"ok": False, "error": "Session không hợp lệ"}), 400
    with _screen_record_lock(sid):
        manifest = _read_screen_manifest(session_dir)
        total = int(body.get("total_segments") or manifest.get("total_segments") or 0)
        uploaded_idx = {int(x.get("index", -1)) for x in (manifest.get("uploaded") or [])}
        missing = [i for i in range(total) if i not in uploaded_idx]
        if missing:
            manifest["status"] = "recording"
            manifest["missing"] = missing[:5000]
            _write_screen_manifest(session_dir, manifest)
            return jsonify({"ok": False, "missing": missing, "uploaded": sorted(uploaded_idx)}), 409

        manifest["status"] = "finishing"
        _write_screen_manifest(session_dir, manifest)
    threading.Thread(target=_screen_record_finish_worker, args=(session_dir, sid, total), daemon=True).start()
    return jsonify({"ok": True, "session_id": sid, "status": "processing", "segments": total}), 202
    if False:
        segments_dir = os.path.join(session_dir, "segments")
        final_ts = os.path.join(session_dir, "%s.ts" % sid)
        tmp_ts = final_ts + ".part"
        with open(tmp_ts, "wb") as out:
            for i in range(total):
                part = os.path.join(segments_dir, "part_%06d.ts" % i)
                with open(part, "rb") as f:
                    shutil.copyfileobj(f, out, 1024 * 1024)
        os.replace(tmp_ts, final_ts)
        
        # Xóa các segment riêng lẻ để giải phóng bộ nhớ đĩa ngay lập tức
        try:
            shutil.rmtree(segments_dir)
            log.info("[ScreenRecord] Đã dọn dẹp thư mục segments tạm thời: %s", segments_dir)
        except Exception as e:
            log.warning("[ScreenRecord] Không thể xóa thư mục segments tạm thời: %s", e)

        manifest["status"] = "done"
        manifest["final_ts"] = os.path.relpath(final_ts, WEBDAV_FILE_ROOT).replace(os.sep, "/")
        manifest["missing"] = []
        manifest["completed_at"] = int(time.time())
        _write_screen_manifest(session_dir, manifest)
    # KHÔNG giải phóng block thumbnail ở đây, remux_worker sẽ giải phóng trong khối finally khi xong
    threading.Thread(target=_screen_record_remux_worker, args=(session_dir, sid, final_ts), daemon=True).start()
    return jsonify({"ok": True, "session_id": sid, "final_ts": manifest["final_ts"], "segments": total})


@app.route("/api/screen_record/status", methods=["GET"])
@requires_auth
def api_screen_record_status():
    raw_sid = request.args.get("session_id", "").strip()
    if raw_sid:
        sid = _safe_screen_session_id(raw_sid)
        session_dir = _screen_record_session_dir(sid)
        if not session_dir or not os.path.exists(session_dir):
            return jsonify({"ok": False, "error": "Không tìm thấy phiên"}), 404
        return jsonify({"ok": True, "manifest": _read_screen_manifest(session_dir)})
    sessions = []
    try:
        if os.path.isdir(_SCREEN_RECORD_ROOT):
            for name in sorted(os.listdir(_SCREEN_RECORD_ROOT), reverse=True)[:50]:
                m = _read_screen_manifest(os.path.join(_SCREEN_RECORD_ROOT, name))
                if m:
                    sessions.append(m)
    except Exception:
        pass
    return jsonify({"ok": True, "sessions": sessions})


@app.route("/api/screen_record/cancel", methods=["POST"])
@requires_auth
def api_screen_record_cancel():
    body = request.get_json(silent=True) or {}
    sid = _safe_screen_session_id(body.get("session_id") or request.args.get("session_id", ""))
    session_dir = _screen_record_session_dir(sid)
    if not session_dir:
        return jsonify({"ok": False, "error": "Session không hợp lệ"}), 400
    with _screen_record_lock(sid):
        manifest = _read_screen_manifest(session_dir)
        manifest["status"] = "cancelled"
        manifest["cancelled_at"] = int(time.time())
        _write_screen_manifest(session_dir, manifest)
        
        # Xóa các segment đã ghi khi huỷ phiên để giải phóng dung lượng đĩa HDD
        segments_dir = os.path.join(session_dir, "segments")
        if os.path.exists(segments_dir):
            try:
                shutil.rmtree(segments_dir)
                log.info("[ScreenRecord] Đã xóa thư mục segments khi hủy phiên: %s", segments_dir)
            except Exception as e:
                log.warning("[ScreenRecord] Không thể xóa thư mục segments khi hủy phiên: %s", e)

    _set_thumbnail_auto_block("screen_record", False)
    _screen_record_drop_lock(sid)
    return jsonify({"ok": True, "session_id": sid})


# ============ VIDEO STREAM TRANSCODE ============
# Transcode video sang MP4 (H.264 + AAC) on-the-fly bang FFmpeg
# ExoPlayer tren Android không gi?i mở được MPEG-2 (.mpg) tren nhieu thiet bi


_transcode_sessions = {}  # session_id -> { "file_path": ..., "duration": ..., "process": Popen, "lock": Lock }
_transcode_sessions_lock = threading.Lock()
_transcode_sessions_ip = {}  # CC-005 P1: dem session theo IP de cap per-client
_transcode_sessions_ip_lock = threading.Lock()
_TRANSCODE_SESSION_TTL_SEC = 2 * 3600
# HW-BUDGET (rk3328 4xA53/1GB): mỗi session giữ 1 ffmpeg HLS — cap 2.
_TRANSCODE_SESSION_MAX = 2
_TRANSCODE_SESSION_PER_IP = 1
try:
    _TRANSCODE_SALT = open("/etc/nas/transcode_salt", "rb").read(16).hex()
except Exception:
    import uuid as _uuid_salt
    _TRANSCODE_SALT = _uuid_salt.uuid4().hex


def _cleanup_transcode_sessions(force_limit=False):
    now = time.time()
    stale = []
    procs_to_kill = []
    with _transcode_sessions_lock:
        for sid, session in list(_transcode_sessions.items()):
            created_at = float(session.get("created_at", 0) or 0)
            proc = session.get("process")
            if created_at and now - created_at > _TRANSCODE_SESSION_TTL_SEC:
                stale.append(sid)
            elif proc is not None and proc.poll() is not None:
                session["process"] = None
        if force_limit and len(_transcode_sessions) - len(stale) > _TRANSCODE_SESSION_MAX:
            ordered = sorted(
                _transcode_sessions.items(),
                key=lambda item: float(item[1].get("last_access", item[1].get("created_at", 0)) or 0)
            )
            stale.extend([sid for sid, _session in ordered[:max(0, len(_transcode_sessions) - _TRANSCODE_SESSION_MAX)]])
        for sid in set(stale):
            session = _transcode_sessions.pop(sid, None)
            if not session:
                continue
            proc = session.get("process")
            if proc is not None and proc.poll() is None:
                procs_to_kill.append(proc)
    for proc in procs_to_kill:
        if proc is not None and proc.poll() is None:
            try:
                proc.kill()
            except Exception:
                pass

def _find_source_file(relative_path):
    """Tim file goc tren NAS tu duong dan WebDAV tuong doi.
    CC-004 P0-4: dung chung _resolve_webdav_request_path (containment realpath
    + SMB fallback co kiem soat). Bo candidate 'relative_path' tho (traversal
    ngoai root) va hard-code '/srv/dev-disk-by-label-data' rieng le."""
    try:
        resolved = _resolve_webdav_request_path(relative_path)
    except Exception:
        resolved = None
    if resolved and os.path.isfile(resolved) and _validate_file_path(resolved):
        return resolved
    return None

def _get_video_duration(file_path):
    """L?y thoi luồng video (giay) — ưu tiên Rust MP4 atom parser, fallback ffprobe."""
    if file_path and str(file_path).lower().endswith((".mp4", ".m4v", ".mov")):
        bridge = globals().get("rust_bridge")
        if bridge and hasattr(bridge, "parse_mp4_header"):
            try:
                info = bridge.parse_mp4_header(file_path)
                if info and info.get("valid") and float(info.get("duration_secs", 0) or 0) > 0:
                    return float(info["duration_secs"])
            except Exception:
                pass
    try:
        cmd = ["/usr/bin/ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file_path]
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        return float(result.stdout.decode('utf-8', errors='ignore').strip())
    except Exception as e:
        log.warning("[HLS] Lỗi đọc thời lượng bằng ffprobe: %s", e)
        return 7200.0  # Fallback 2 tieng neu lỗi

@app.route("/api/stream/transcode")
@requires_auth
def api_stream_transcode():
    """Khoi tao session JIT HLS"""
    import hashlib
    _cleanup_transcode_sessions(force_limit=True)
    relative_path = request.args.get("path", "")
    if not relative_path:
        return jsonify({"error": "Thiếu tham số 'path'"}), 400

    file_path = _find_source_file(relative_path)
    if not file_path:
        return jsonify({"error": "Không tìm thấy tệp"}), 404

    # CC-005 P1: session_id = md5(salt + path + mtime + size) — het predict;
    # cap 2 global + 1/IP (ffmpeg HLS tren rk3328 1GB).
    try:
        _stx = os.stat(file_path)
        _sid_src = "%s|%s|%d|%d" % (_TRANSCODE_SALT, file_path, _stx.st_size, int(_stx.st_mtime))
    except Exception:
        _sid_src = "%s|%s" % (_TRANSCODE_SALT, file_path)
    session_id = hashlib.md5(_sid_src.encode()).hexdigest()[:12]
    _cip = _request_client_ip()
    with _transcode_sessions_lock:
        if session_id not in _transcode_sessions and len(_transcode_sessions) >= _TRANSCODE_SESSION_MAX:
            return jsonify({"error": "NAS dang transcode toi da, thu lai sau",
                            "reason": "MAX_TRANSCODE", "cap": _TRANSCODE_SESSION_MAX,
                            "retry_after_s": 30}), 429
    if _cip:
        with _transcode_sessions_ip_lock:
            _now_ip = time.time()
            _lst = [t for t in _transcode_sessions_ip.get(_cip, []) if _now_ip - t < 7200]
            if session_id not in _transcode_sessions and len(_lst) >= _TRANSCODE_SESSION_PER_IP:
                return jsonify({"error": "Moi IP 1 session transcode/lan",
                                "reason": "MAX_TRANSCODE_IP", "retry_after_s": 30}), 429
            _lst.append(_now_ip)
            _transcode_sessions_ip[_cip] = _lst[-4:]
    hls_dir = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "nas_transcode", session_id)

    # Tu dong don dep rac HLS cu (>2h)
    try:
        base = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "nas_transcode")
        if os.path.exists(base):
            for d in os.listdir(base):
                dp = os.path.join(base, d)
                if os.path.isdir(dp) and (time.time() - os.path.getmtime(dp)) > 7200:
                    shutil.rmtree(dp, ignore_errors=True)
    except Exception:
        pass

    os.makedirs(hls_dir, exist_ok=True)

    # L?y tong thoi gian cua video
    duration = _get_video_duration(file_path)
    
    with _transcode_sessions_lock:
        session = _transcode_sessions.get(session_id)
        if session:
            session["file_path"] = file_path
            session["duration"] = duration
            session["last_access"] = time.time()
            session.setdefault("lock", threading.Lock())
        else:
            _transcode_sessions[session_id] = {
                "file_path": file_path,
                "duration": duration,
                "process": None,
                "created_at": time.time(),
                "last_access": time.time(),
                "lock": threading.Lock(),
            }
    log.info("[JIT HLS] Khởi tạo: %s (Duration: %.1fs)", file_path, duration)

    # Redirect den file m3u8 — ExoPlayer se call tiep vao /api/stream/hls/
    from flask import redirect
    return redirect("/api/stream/hls/%s/playlist.m3u8" % session_id)


@app.route("/api/stream/hls/<session_id>/<filename>")
@requires_auth
def api_stream_hls_file(session_id, filename):
    # CC-005 P0: validate session_id dung dinh dang md5[:12] cua
    # api_stream_transcode — truoc day session_id tu URL khong sanitize,
    # '../' join ra ngoai nas_transcode dir -> doc file .ts tuy y.
    import re as _re_hls
    if not _re_hls.match(r"^[a-f0-9]{12}$", session_id or ""):
        return "", 404
    with _transcode_sessions_lock:
        session = _transcode_sessions.get(session_id)
        if not session:
            return "", 404
        session["last_access"] = time.time()
        session_lock = session.get("lock")
        if session_lock is None:
            session_lock = threading.Lock()
            session["lock"] = session_lock
        duration = session.get("duration", 0)
    hls_dir = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "nas_transcode", session_id)

    if filename == "playlist.m3u8":
        lines = [
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:4",
            "#EXT-X-MEDIA-SEQUENCE:0",
            "#EXT-X-PLAYLIST-TYPE:VOD"
        ]
        seg_duration = 4.0
        total_segs = int(float(duration or 0) / seg_duration)
        for i in range(total_segs):
            lines.append("#EXTINF:%.6f," % seg_duration)
            lines.append("seg%05d.ts" % i)
        rem = float(duration or 0) - (total_segs * seg_duration)
        if rem > 0:
            lines.append("#EXTINF:%.6f," % rem)
            lines.append("seg%05d.ts" % total_segs)
        lines.append("#EXT-X-ENDLIST")

        response = Response("\n".join(lines))
        response.headers["Content-Type"] = "application/vnd.apple.mpegurl"
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        return response

    if not filename.endswith(".ts"):
        return "", 404

    import re
    # CC-005 P0: fullmatch + bound + realpath containment. Ban cu dung re.search
    # nen "../seg00001.ts" van match -> join thoat nas_transcode dir (traversal).
    match = re.match(r"^seg(\d{5})\.ts$", filename or "")
    if not match:
        return "", 404

    seg_idx = int(match.group(1))
    try:
        _total_segs = int(float(duration or 0) / 4.0) + 1
    except Exception:
        _total_segs = 10 ** 6
    if seg_idx < 0 or seg_idx > _total_segs + 2:
        return "", 404
    file_path = os.path.realpath(os.path.join(hls_dir, filename))
    if not file_path.startswith(os.path.realpath(hls_dir) + os.sep):
        return "", 404

    with session_lock:
        if not os.path.exists(file_path):
            start_time = seg_idx * 4.0
            proc = session.get("process")
            if proc is not None and proc.poll() is None:
                try:
                    proc.kill()
                except Exception:
                    pass
                session["process"] = None

            cmd = [
                "/usr/bin/ffmpeg",
                "-ss", str(start_time),
                "-i", session["file_path"],
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-f", "hls",
                "-hls_time", "4",
                "-hls_list_size", "0",
                "-start_number", str(seg_idx),
                "-hls_segment_filename", os.path.join(hls_dir, "seg%05d.ts"),
                "-y",
                os.path.join(hls_dir, "dummy.m3u8")
            ]
            log.info("[JIT HLS] %s | Bat dau transcode tu giay %ds...", filename, start_time)
            session["process"] = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            next_seg = os.path.join(hls_dir, "seg%05d.ts" % (seg_idx + 1))
            wait_count = 0
            while wait_count < 60:
                proc = session.get("process")
                if proc is None or proc.poll() is not None:
                    break
                if os.path.exists(next_seg):
                    break
                time.sleep(0.5)
                wait_count += 1

            if not os.path.exists(file_path):
                proc = session.get("process")
                if proc is not None and proc.poll() is None:
                    try:
                        proc.kill()
                    except Exception:
                        pass
                session["process"] = None
                log.error("[JIT HLS] Khong the tao %s", filename)
                return "", 500

    from flask import send_file as flask_send_file
    response = flask_send_file(file_path, mimetype="video/mp2t")
    response.headers["Cache-Control"] = "public, max-age=31536000"
    return response


@app.route("/api/tools/organize_legacy_videos", methods=["POST"])
@requires_auth
def api_organize_legacy_videos():
    """
    Quet toan bo WEBDAV_FILE_ROOT, di chuyen cac video không ph?i mp4 vao /Other Video/<ext>/
    """
    
    # Danh sách giay phep (chi video, không ph?i mp4)
    target_exts = {".mpg", ".mpeg", ".avi", ".wmv", ".flv", ".mkv", ".mov", ".ts", ".m4v", ".3gp"}
    
    other_video_dir = os.path.join(WEBDAV_FILE_ROOT, "Other Video")
    changed_dirs = set()
    
    moved_count = 0
    errors = []
    # CC-005 P1: cap 20000 file + deadline 120s (walk full disk treo HDD)
    _ORG_MAX_FILES = 20000
    _ORG_DEADLINE = time.time() + 120
    _org_scanned = [0]
    _org_truncated = [False]
    
    # Quet tất c? thư mục trong WEBDAV_FILE_ROOT
    for root, dirs, files in os.walk(WEBDAV_FILE_ROOT):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d != '#recycle']
        if time.time() > _ORG_DEADLINE or _org_scanned[0] > _ORG_MAX_FILES:
            _org_truncated[0] = True
            break
        # B? qua thư mục Other Video de không di chuy?n vong lap
        if os.path.abspath(root).startswith(os.path.abspath(other_video_dir)):
            continue
            
        for file in files:
            _org_scanned[0] += 1
            if _org_scanned[0] > _ORG_MAX_FILES or time.time() > _ORG_DEADLINE:
                _org_truncated[0] = True
                break
            if len(errors) > 100:
                break
            ext = os.path.splitext(file)[1].lower()
            if ext in target_exts:
                old_path = os.path.join(root, file)
                
                # T?o thư mục Other Video/<ext>
                ext_name = ext.lstrip(".")
                target_dir = os.path.join(other_video_dir, ext_name)
                os.makedirs(target_dir, exist_ok=True)
                
                # Trảnh trung ten file
                new_path = os.path.join(target_dir, file)
                if os.path.exists(new_path):
                    base, ex = os.path.splitext(file)
                    new_path = os.path.join(target_dir, "%s_%d%s" % (base, int(time.time()), ex))
                    
                try:
                    shutil.move(old_path, new_path)
                    changed_dirs.update((os.path.dirname(old_path), os.path.dirname(new_path)))
                    moved_count += 1
                    log.info("[Organize] Moved: %s -> %s", file, target_dir)
                except Exception as e:
                    errors.append(str(e))
                    
    # Fix quyen truy cap cho thư mục WebDAV vi script nay chay duoi quyen root
    # OpenMediaVault yeu cau ACL can ban (getfacl) va SGID (2775) de WebDAV nhin thay được
    if moved_count > 0:
        _index_mutated_directories(changed_dirs)
        try:
            _run_acl_copy(WEBDAV_FILE_ROOT, other_video_dir)
            subprocess.run(["chown", "-R", "daica:webdav-users", other_video_dir])
            subprocess.run(["chmod", "-R", "2775", other_video_dir])
        except Exception as e:
            log.warning("[Organize] Không thể sửa quyền ACL: %s", e)
            
    return jsonify({
        "success": True,
        "moved_count": moved_count,
        "errors": errors[:100],
        "truncated": _org_truncated[0],
        "scanned": _org_scanned[0]
    })


# ============ SMART ORGANIZER (Sap xep file theo Nam/Thang) ============

_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".gif"}
_VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".mpg", ".mpeg", ".wmv", ".flv", ".ts", ".m4v", ".3gp"}
_ALL_MEDIA_EXTS = _IMAGE_EXTS | _VIDEO_EXTS

def _smart_organize_candidates(base_dir, allowed_exts):
    """Read bounded batches from one catalogue revision without holding a read lock during moves."""
    if not base_dir:
        raise ValueError("catalogue_root_unavailable")
    root = os.path.realpath(base_dir)
    predicate = " OR ".join(["lower(name) LIKE ?"] * len(allowed_exts))
    extensions = ["%" + ext for ext in sorted(allowed_exts)]
    after = ""
    snapshot = None
    while True:
        conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
        try:
            conn.execute("BEGIN")
            state = conn.execute("SELECT root,generation,catalogue_revision,ready,scanned_at FROM index_state WHERE id=1").fetchone()
            if not state or state[0] != root or not state[3] or (snapshot is None and time.time() - state[4] > FILE_INDEX_FRESHNESS_SECONDS):
                _refresh_file_index_for_search()
                raise ValueError("catalogue_refresh_pending")
            if snapshot is not None and state[:3] != snapshot:
                raise ValueError("catalogue_changed")
            snapshot = state[:3]
            rows = conn.execute("SELECT path,size,mtime FROM file_index WHERE is_dir=0 AND path>? AND (" +
                                predicate + ") ORDER BY path LIMIT 512", [after] + extensions).fetchall()
        finally:
            conn.close()
        for rel, size, mtime in rows:
            parts = rel.split("/")
            if os.path.isabs(rel) or "\\" in rel or any(part in ("", ".", "..") for part in parts):
                raise ValueError("Invalid indexed path")
            label = datetime.datetime.fromtimestamp(mtime / 1000.0).strftime("%Y/%m")
            if "/".join(parts[:-1]) == label or "/".join(parts[:-1]).endswith("/" + label):
                continue
            yield rel, size, mtime, label
        if len(rows) < 512:
            return
        after = rows[-1][0]


@app.route("/api/tools/smart_organize/scan", methods=["POST"])
@requires_auth
def api_smart_organize_scan():
    """
    Quet thư mục WebDAV, phan nhom file theo Nam/Thang (mtime).
    Input JSON: { "path": "/webdav/", "filter": "all|image|video" }
    Tr? v?: { "total": N, "groups": [ { "label": "2024/03", "count": X, "size": Y } ] }
    """
    data = request.get_json(force=True) or {}
    scan_filter = data.get("filter", "all")

    base_dir = _get_webdav_public_dir()

    if scan_filter == "image":
        allowed_exts = _IMAGE_EXTS
    elif scan_filter == "video":
        allowed_exts = _VIDEO_EXTS
    else:
        allowed_exts = _ALL_MEDIA_EXTS

    groups_map = {}
    total = 0
    now = time.time()
    try:
        for rel, size, mtime, label in _smart_organize_candidates(base_dir, allowed_exts):
            if now - mtime / 1000.0 < 86400:
                continue
            group = groups_map.setdefault(label, {"count": 0, "size": 0, "sample_files": []})
            group["count"] += 1
            group["size"] += size
            total += 1
            if len(group["sample_files"]) < 5:
                group["sample_files"].append({"name": rel.rsplit("/", 1)[-1], "path": "/" + rel,
                                              "size": size, "mtime": mtime})
    except ValueError as error:
        return jsonify({"success": False, "error": str(error)}), 409

    # Sap xep theo thoi gian giam dan (mới nh?t truoc)
    sorted_labels = sorted(groups_map.keys(), reverse=True)
    groups = []
    for label in sorted_labels:
        g = groups_map[label]
        groups.append({
            "label": label,
            "count": g["count"],
            "size": g["size"],
            "sample_files": g["sample_files"]
        })

    return jsonify({
        "success": True,
        "total": total,
        "group_count": len(groups),
        "groups": groups
    })


_smart_organize_jobs = {}
_smart_organize_jobs_lock = threading.Lock()


def _smart_organize_job_snapshot(job_id):
    with _smart_organize_jobs_lock:
        job = _smart_organize_jobs.get(job_id)
        return dict(job) if job else None


def _smart_organize_job_update(job_id, **fields):
    with _smart_organize_jobs_lock:
        job = _smart_organize_jobs.setdefault(job_id, {"job_id": job_id})
        job.update(fields)
        job["updated_at"] = time.time()
        # P1-6: Evict completed jobs older than 2 hours to prevent unbounded growth
        _now = time.time()
        stale = [jid for jid, j in _smart_organize_jobs.items()
                 if j.get("status") in ("done", "error") and
                 _now - float(j.get("updated_at") or 0) > 7200]
        for _jid in stale:
            del _smart_organize_jobs[_jid]
        return dict(job)


def _smart_organize_worker(job_id, base_dir, allowed_exts, scan_filter, dry_run=False):
    # CC-005 P1/P2: dry_run (chỉ scan không move), manifest JSON old->new để undo,
    # skip file mtime<24h (tránh move file đang ghi), journal giữ 7 ngày trong .nas_meta.

    import stat
    moved_count = 0
    errors = []
    affected_dirs = set()
    changed_dirs = set()
    scanned_entries = 0
    max_entries = 50000
    deadline = time.time() + 1800
    _moves = []  # manifest entries {"old": rel_old, "new": rel_new}
    _skip_fresh = 0

    try:
        _smart_organize_job_update(
            job_id,
            status="running",
            started_at=time.time(),
            base_dir=base_dir,
            filter=scan_filter,
            allowed_exts=sorted(allowed_exts),
            dry_run=bool(dry_run),
            moved_count=0,
            scanned=0,
            error_count=0,
            errors=[],
        )

        base_dir = os.path.realpath(base_dir)
        for rel, size, indexed_mtime, label in _smart_organize_candidates(base_dir, allowed_exts):
            if scanned_entries >= max_entries or time.time() >= deadline:
                raise TimeoutError("Smart organize limit reached")
            if scanned_entries % 120 == 0 and not _background_heavy_work_allowed(ignore_user_idle=True):
                raise RuntimeError("Background heavy work is not allowed right now")

            scanned_entries += 1
            name = rel.rsplit("/", 1)[-1]
            full_path = os.path.join(base_dir, *rel.split("/"))
            try:
                if os.path.islink(full_path) or not os.path.realpath(full_path).startswith(base_dir + os.sep):
                    raise ValueError("Source is outside public root or a symbolic link")
                st = os.stat(full_path)
                if not stat.S_ISREG(st.st_mode) or st.st_size != size or int(st.st_mtime * 1000) != indexed_mtime:
                    raise ValueError("Source changed since catalogue scan")
                mtime = st.st_mtime
                # CC-005 P2: skip file mtime<24h (tránh move file đang ghi/upload dở).
                if time.time() - mtime < 86400:
                    _skip_fresh += 1
                    continue
                target_dir = os.path.join(base_dir, label)
                if not os.path.realpath(target_dir).startswith(base_dir + os.sep):
                    raise ValueError("Destination outside public root")

                new_path = os.path.join(target_dir, name)
                if os.path.lexists(new_path):
                    base_name, ex = os.path.splitext(name)
                    _ts_suffix = int(time.time())
                    _collision_idx = 0
                    while os.path.lexists(new_path):
                        if _collision_idx == 0:
                            new_path = os.path.join(target_dir, "%s_%d%s" % (base_name, _ts_suffix, ex))
                        else:
                            new_path = os.path.join(target_dir, "%s_%d_%d%s" % (base_name, _ts_suffix, _collision_idx, ex))
                        _collision_idx += 1

                # CC-005 P2: dry_run chi scan khong move (API cu default dry_run=0).
                _rel_old = full_path[len(base_dir):]
                if not _rel_old.startswith("/"):
                    _rel_old = "/" + _rel_old
                _rel_new = new_path[len(base_dir):]
                if not _rel_new.startswith("/"):
                    _rel_new = "/" + _rel_new
                if dry_run:
                    _moves.append({"old": _rel_old, "new": _rel_new, "dry_run": True})
                    moved_count += 1
                    continue
                if not dry_run:
                    os.makedirs(target_dir, exist_ok=True)
                    affected_dirs.add(target_dir)
                shutil.move(full_path, new_path)
                changed_dirs.update((os.path.dirname(full_path), os.path.dirname(new_path)))
                _moves.append({"old": _rel_old, "new": _rel_new})
                moved_count += 1
                if moved_count % 100 == 0:
                    log.info("[SmartOrganize] moved %d files...", moved_count)
                    _smart_organize_job_update(
                        job_id,
                        moved_count=moved_count,
                        scanned=scanned_entries,
                        error_count=len(errors),
                    )
            except Exception as e:
                errors.append(str(e))
                if len(errors) <= 20:
                    _smart_organize_job_update(
                        job_id,
                        moved_count=moved_count,
                        scanned=scanned_entries,
                        error_count=len(errors),
                        errors=errors[:20],
                        last_error=str(e),
                    )

        if moved_count > 0:
            for d in affected_dirs:
                try:
                    _run_acl_copy(base_dir, d)
                    subprocess.run(["chown", "-R", "daica:webdav-users", d])
                    subprocess.run(["chmod", "-R", "2775", d])
                except Exception as e:
                    log.warning("[SmartOrganize] ACL error: %s", e)

            _push_alert(
                "SMART_ORGANIZE",
                "Smart Organizer: Da sap xep %d tap vao thu muc theo Nam/Thang." % moved_count,
                "SUCCESS"
            )

        _manifest_path = ""
        if _moves:
            try:
                # CC-005 P2: journal/undo — manifest JSON old->new trong .nas_meta,
                # giữ 7 ngày (86400*7) để undo; prune manifest quá hạn mỗi job.
                _meta_dir = os.path.join(base_dir, ".nas_meta")
                try:
                    os.makedirs(_meta_dir, exist_ok=True)
                except Exception:
                    pass
                _manifest_path = os.path.join(
                    _meta_dir, "smart_organize_%s.json" % job_id)
                with open(_manifest_path, "w", encoding="utf-8") as _mf:
                    json.dump({
                        "job_id": job_id,
                        "created_at": int(time.time()),
                        "dry_run": bool(dry_run),
                        "filter": scan_filter,
                        "moves": _moves,
                    }, _mf, ensure_ascii=False)
                try:
                    _now_m = time.time()
                    for _fn in os.listdir(_meta_dir):
                        if _fn.startswith("smart_organize_") and _fn.endswith(".json"):
                            _fp = os.path.join(_meta_dir, _fn)
                            try:
                                if _now_m - os.path.getmtime(_fp) > 7 * 86400:
                                    os.remove(_fp)
                            except Exception:
                                pass
                except Exception:
                    pass
            except Exception as _me:
                log.warning("[SmartOrganize] Khong ghi duoc manifest: %s", _me)
                _manifest_path = ""

        final_status = "finished_with_errors" if errors else "finished"
        _smart_organize_job_update(
            job_id,
            status=final_status,
            moved_count=moved_count,
            scanned=scanned_entries,
            skipped_fresh=_skip_fresh,
            manifest_path=_manifest_path,
            dry_run=bool(dry_run),
            error_count=len(errors),
            errors=errors[:20],
            finished_at=time.time(),
        )
        log.info("[SmartOrganize] Done: %d files moved, %d errors.", moved_count, len(errors))
    except Exception as e:
        _manifest_path = ""
        if _moves:
            try:
                _meta_dir = os.path.join(base_dir, ".nas_meta")
                os.makedirs(_meta_dir, exist_ok=True)
                _manifest_path = os.path.join(_meta_dir, "smart_organize_%s.json" % job_id)
                with open(_manifest_path, "w", encoding="utf-8") as _mf:
                    json.dump({
                        "job_id": job_id,
                        "created_at": int(time.time()),
                        "dry_run": bool(dry_run),
                        "filter": scan_filter,
                        "moves": _moves,
                    }, _mf, ensure_ascii=False)
            except Exception as _me:
                log.warning("[SmartOrganize] Khong ghi duoc manifest khi abort: %s", _me)
                _manifest_path = ""
        failed_status = "aborted" if isinstance(e, RuntimeError) else "failed"
        _smart_organize_job_update(
            job_id,
            status=failed_status,
            error=str(e),
            moved_count=moved_count,
            scanned=scanned_entries,
            manifest_path=_manifest_path,
            error_count=len(errors) + 1,
            errors=(errors[:20] + [str(e)])[:20],
            finished_at=time.time(),
        )
        log.warning("[SmartOrganize] Job %s stopped: %s", job_id, e)
    finally:
        _index_mutated_directories(changed_dirs)


@app.route("/api/tools/smart_organize/execute", methods=["POST"])
@requires_auth
def api_smart_organize_execute():
    """
    Queue smart organize as a background job.
    Input JSON: { "filter": "all|image|video", "dry_run": 0|1 }
    CC-005 P2: dry_run=1 chỉ scan không move (mặc định 0 giữ API cũ).
    """
    data = request.get_json(force=True) or {}
    scan_filter = data.get("filter", "all")
    try:
        dry_run = bool(int(data.get("dry_run", 0)))
    except Exception:
        dry_run = bool(data.get("dry_run", False))

    base_dir = _get_webdav_public_dir()
    if not base_dir:
        return jsonify({"success": False, "error": "catalogue_root_unavailable"}), 503
    if scan_filter == "image":
        allowed_exts = _IMAGE_EXTS
    elif scan_filter == "video":
        allowed_exts = _VIDEO_EXTS
    else:
        allowed_exts = _ALL_MEDIA_EXTS

    if not _background_heavy_work_allowed(ignore_user_idle=True):
        return jsonify({
            "success": False,
            "queued": False,
            "error": "Background heavy work is not allowed right now",
        }), 429

    job_id = "smartorg_%d_%s" % (int(time.time() * 1000), uuid.uuid4().hex[:6])
    _smart_organize_job_update(
        job_id,
        status="queued",
        base_dir=base_dir,
        filter=scan_filter,
        allowed_exts=sorted(allowed_exts),
        dry_run=dry_run,
        moved_count=0,
        scanned=0,
        error_count=0,
        errors=[],
        queued_at=time.time(),
    )
    threading.Thread(
        target=_smart_organize_worker,
        args=(job_id, base_dir, allowed_exts, scan_filter, dry_run),
        daemon=True,
        name="SmartOrganizeWorker",
    ).start()

    return jsonify({
        "success": True,
        "queued": True,
        "job_id": job_id,
        "status": "running",
        "dry_run": dry_run,
    }), 202


@app.route("/api/tools/smart_organize/status/<job_id>", methods=["GET"])
@requires_auth
def api_smart_organize_status(job_id):
    job = _smart_organize_job_snapshot(job_id)
    if not job:
        return jsonify({"success": False, "error": "job_not_found", "job_id": job_id}), 404

    status = job.get("status", "queued")
    terminal = status in ("finished", "finished_with_errors")
    return jsonify({
        "success": terminal or status in ("queued", "running"),
        "job_id": job_id,
        "status": status,
        "filter": job.get("filter", "all"),
        "dry_run": bool(job.get("dry_run", False)),
        "moved_count": int(job.get("moved_count", 0) or 0),
        "scanned": int(job.get("scanned", 0) or 0),
        "skipped_fresh": int(job.get("skipped_fresh", 0) or 0),
        "manifest_path": job.get("manifest_path", ""),
        "error_count": int(job.get("error_count", 0) or 0),
        "errors": job.get("errors", [])[:20],
        "error": job.get("error", ""),
        "started_at": job.get("started_at", 0),
        "finished_at": job.get("finished_at", 0),
        "updated_at": job.get("updated_at", 0),
    })


@app.route("/api/tools/smart_organize/undo", methods=["POST"])
@requires_auth
def api_smart_organize_undo():
    """CC-005 P2: undo don gian tu manifest journal (.nas_meta/smart_organize_<job>.json).
    Input: { "job_id": "smartorg_..." }. Move nguoc new->old, bo qua entry dry_run.
    """
    data = request.get_json(force=True) or {}
    job_id = str(data.get("job_id", "") or "").strip()
    if not job_id or "/" in job_id or "\\" in job_id or ".." in job_id:
        return jsonify({"success": False, "error": "job_id khong hop le"}), 400
    base_dir = _get_webdav_public_dir()
    if not base_dir:
        return jsonify({"success": False, "error": "catalogue_root_unavailable"}), 503
    manifest_path = os.path.join(base_dir, ".nas_meta", "smart_organize_%s.json" % job_id)
    base_real = os.path.realpath(base_dir)
    mani_real = os.path.realpath(manifest_path)
    if mani_real != os.path.join(base_real, ".nas_meta", "smart_organize_%s.json" % job_id):
        return jsonify({"success": False, "error": "manifest ngoai pham vi"}), 400
    if not os.path.isfile(mani_real):
        return jsonify({"success": False, "error": "khong tim thay manifest"}), 404
    try:
        with open(mani_real, "r", encoding="utf-8") as _f:
            mani = json.load(_f)
    except Exception as e:
        return jsonify({"success": False, "error": "manifest loi: %s" % str(e)[:100]}), 500
    moves = (mani or {}).get("moves", []) if isinstance(mani, dict) else []
    restored = 0
    changed_dirs = set()
    skipped = 0
    errs = []
    for m in moves:
        try:
            if not isinstance(m, dict) or m.get("dry_run"):
                skipped += 1
                continue
            old_rel = str(m.get("old", "") or "").lstrip("/")
            new_rel = str(m.get("new", "") or "").lstrip("/")
            if not old_rel or not new_rel:
                skipped += 1
                continue
            old_abs = os.path.realpath(os.path.join(base_real, old_rel))
            new_abs = os.path.realpath(os.path.join(base_real, new_rel))
            if not (new_abs == base_real or new_abs.startswith(base_real + os.sep)):
                skipped += 1
                continue
            if not (old_abs == base_real or old_abs.startswith(base_real + os.sep)):
                skipped += 1
                continue
            if not os.path.exists(new_abs):
                skipped += 1
                continue
            try:
                os.makedirs(os.path.dirname(old_abs), exist_ok=True)
            except Exception:
                pass
            if os.path.exists(old_abs):
                skipped += 1
                continue
            shutil.move(new_abs, old_abs)
            changed_dirs.update((os.path.dirname(new_abs), os.path.dirname(old_abs)))
            restored += 1
        except Exception as e:
            errs.append(str(e)[:120])
            if len(errs) >= 20:
                break
    _index_mutated_directories(changed_dirs)
    return jsonify({"success": True, "job_id": job_id, "restored": restored,
                    "skipped": skipped, "errors": errs[:20]})


# ============ INDEX ENGINE & HASH DELEGATION ============

_cached_webdav_root = None

def get_webdav_root():
    """Tu dong do tim duong dan thư mục goc cua WebDAV tren NAS (Có bộ đệm RAM giảm tải HDD).
    CC-004 P1-1: uu tien _get_webdav_public_dir() (doc config.php $publicDir +
    validate exists) de 3 helper root (public_dir/_get_webdav_root/get_webdav_root)
    khong lech nhau gay /api/media 404 trong khi WebDAV PROPFIND van thay file."""
    global _cached_webdav_root
    if _cached_webdav_root is not None:
        return _cached_webdav_root
    try:
        pub = _get_webdav_public_dir()
        if pub:
            _cached_webdav_root = pub.rstrip('/')
            return _cached_webdav_root
    except Exception:
        pass

    import glob, re
    best_total = 0
    best_path = "/sharedfolders/Data" # Default OMV fallback
    try:
        import psutil
        for p in psutil.disk_partitions(all=False):
            if "/srv/dev-disk" in p.mountpoint or "/mnt/" in p.mountpoint or "/sharedfolders" in p.mountpoint:
                try:
                    u = psutil.disk_usage(p.mountpoint)
                    if u.total > best_total:
                        best_total = u.total
                        best_path = p.mountpoint
                        # HOTFIX: Bắt buộc đính kèm New folder - OMV WebDAV thực tế trên máy người dùng
                        if os.path.exists(os.path.join(p.mountpoint, "New folder")):
                            best_path = os.path.join(p.mountpoint, "New folder")
                except Exception as e: log.debug("[M4] Ignored exception: %s", e)
    except Exception as e: log.debug("[M4] Ignored exception: %s", e)

    _cached_webdav_root = best_path.rstrip('/')
    return _cached_webdav_root

def generate_fast_index(force=False):
    """Stream one complete public-root SQL snapshot; never walk or cache JSON."""
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=5)
    try:
        conn.execute('BEGIN')
        state = conn.execute('SELECT root,generation,ready,scanned_at,catalogue_revision FROM index_state WHERE id=1').fetchone()
        root = _get_webdav_public_dir()
        if not root or not state or state[0] != os.path.realpath(root):
            _refresh_file_index_for_search()
            raise ValueError('catalogue_root_unavailable')
        if not state[2] or _index_scan_lock.locked():
            _refresh_file_index_for_search()
            raise ValueError('catalogue_refresh_pending')
        # Allow retry/backoff to consume a completed refresh instead of starting
        # another scan on every request after the search five-second interval.
        if time.time() - state[3] > 60:
            _refresh_file_index_for_search()
            raise ValueError('catalogue_refresh_pending')

        # Ưu tiên streaming trực tiếp bằng Rust nas_indexer (5x throughput)
        bridge = globals().get('rust_bridge')
        if bridge and hasattr(bridge, 'stream_fast_index'):
            rust_stream = bridge.stream_fast_index(INDEX_DB_PATH)
            if rust_stream is not None:
                try:
                    conn.close()
                    conn = None
                    for chunk in rust_stream:
                        yield chunk
                    return
                finally:
                    if rust_stream is not None and hasattr(rust_stream, 'close'):
                        rust_stream.close()

        total = conn.execute('SELECT COUNT(*) FROM file_index WHERE is_dir=0').fetchone()[0]
        metadata = {'catalogue_protocol': 1, 'source': 'file_index', 'index_ready': True,
                    'index_generation': state[1], 'catalogue_updated_at': state[3],
                    'catalogue_revision': state[4],
                    'truncated': False, 'total': total}
        yield json.dumps(metadata, ensure_ascii=False)[:-1] + ',"files":['
        cursor = conn.execute('SELECT path,name,size,mtime,mime FROM file_index NOT INDEXED WHERE is_dir=0')
        first = True
        while True:
            rows = cursor.fetchmany(512)
            if not rows:
                break
            chunks = []
            for path, name, size, mtime, mime in rows:
                if os.path.isabs(path) or '\\' in path or any(part in ('', '.', '..') for part in path.split('/')):
                    raise ValueError('Invalid indexed path')
                chunks.append(('' if first else ',') + json.dumps({'name': name,
                    'path': '/webdav/' + path, 'size': size, 'mtime': mtime, 'mime': mime}, ensure_ascii=False))
                first = False
            yield ''.join(chunks)
        yield ']}'
    finally:
        if conn is not None:
            conn.close()

@app.route("/api/disk/trash_batch", methods=["POST"])
@requires_auth
def api_disk_trash_batch():
    """Di chuyển hàng loạt tệp vào thùng rác (.trash) cục bộ để tránh sập NAS."""
    data = request.json or {}
    files = data.get("files", [])
    if not isinstance(files, list):
        return jsonify({"error": "files must be a list"}), 400
    # CC-005 P1: cap 500 file/lan — truoc day khong cap, JSON lon OOM +
    # rename dong bo giu worker hang phut, HDD spin-up.
    if len(files) > 500:
        return jsonify({"error": "Batch qua lon (toi da 500 file/lan)"}), 400
    
    webdav_root = _get_webdav_public_dir()
    if not webdav_root:
        return jsonify({"error": "Configured WebDAV root is unavailable"}), 503
        
    success = 0
    errors = []
    removed_paths = []
    
    for webdav_path in files:
        if not webdav_path.startswith("/webdav/"):
            continue
        rel_path = webdav_path[8:]
        if rel_path.startswith("/"):
            rel_path = rel_path[1:]
            
        local_path = os.path.realpath(os.path.join(webdav_root, rel_path))
        root_real = os.path.realpath(webdav_root)
        if not os.path.exists(local_path):
            errors.append({"path": webdav_path, "error": "Not found"})
            continue
            
        if not local_path.startswith(root_real + os.sep):
            errors.append({"path": webdav_path, "error": "Path traversal"})
            continue

        trash_dir = _trash_dir_for_relative_path(webdav_root, rel_path)
        if not trash_dir:
            errors.append({"path": webdav_path, "error": "Cannot resolve per-drive trash"})
            continue
        try:
            if not os.path.exists(trash_dir):
                os.makedirs(trash_dir)
        except Exception as e:
            errors.append({"path": webdav_path, "error": "Cannot create per-drive .trash: " + str(e)})
            continue
            
        filename = os.path.basename(local_path)
        dest_path = os.path.join(trash_dir, filename)
        
        base, ext = os.path.splitext(filename)
        counter = 1
        while os.path.exists(dest_path):
            dest_path = os.path.join(trash_dir, "%s_%d%s" % (base, counter, ext))
            counter += 1
            
        try:
            was_directory = os.path.isdir(local_path)
            os.rename(local_path, dest_path)
            success += 1
            removed_paths.append((rel_path.rstrip("/") + ("/" if was_directory else ""), local_path))
        except Exception as e:
            errors.append({"path": webdav_path, "error": str(e)})
            
    if removed_paths:
        conn = None
        try:
            conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
            tables = set(row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'"))
            for rel_path, physical_path in removed_paths:
                relative_root = rel_path.rstrip("/")
                prefix = relative_root + "/"
                upper = relative_root + "0"
                conn.execute("DELETE FROM file_index WHERE path=? OR (path>=? AND path<?)",
                             (rel_path, prefix, upper))
                conn.execute("DELETE FROM index_directories WHERE path=? OR (path>=? AND path<?)",
                             (relative_root, prefix, upper))
                webdav_root = "/webdav/" + relative_root
                webdav_prefix = webdav_root + "/"
                webdav_upper = webdav_root + "0"
                for table in ("file_hashes", "verified_file_hashes"):
                    if table in tables:
                        conn.execute("DELETE FROM " + table + " WHERE path IN (?,?) OR (path>=? AND path<?) OR (path>=? AND path<?)",
                                     (relative_root, webdav_root, prefix, upper, webdav_prefix, webdav_upper))
                if "thumb_neg_cache" in tables:
                    physical_root = physical_path.rstrip(os.sep)
                    physical_prefix = physical_root + os.sep
                    conn.execute("DELETE FROM thumb_neg_cache WHERE path=? OR (path>=? AND path<?)",
                                 (physical_root, physical_prefix, physical_root + "0"))
            nas_work_coordinator.bump_catalogue_revision(conn)
            conn.execute("UPDATE index_state SET ready=0,incomplete_reason='file_mutation_pending',scanned_at=0 WHERE id=1")
            conn.commit()
        except sqlite3.Error as error:
            if conn is not None:
                conn.rollback()
            log.warning("[Trash] Shared file index reconciliation deferred: %s", error)
        finally:
            if conn is not None:
                conn.close()
            
    return jsonify({
        "success_count": success,
        "errors": errors
    })

@app.route("/api/disk/fast_index")
@requires_auth
def api_fast_index():
    chunks = generate_fast_index()
    try:
        first = next(chunks)
    except (ValueError, sqlite3.Error, OSError):
        chunks.close()
        return jsonify({'catalogue_protocol': 1, 'index_ready': False,
                        'error': 'catalogue_refresh_pending'}), 503, {'Retry-After': '2'}

    def stream():
        try:
            yield first
            for chunk in chunks:
                yield chunk
        finally:
            chunks.close()
    response = Response(stream(), mimetype='application/json')
    response.headers['Cache-Control'] = 'no-store'
    response.call_on_close(chunks.close)
    return response

@app.route("/api/disk/hash_batch", methods=["POST"])
@requires_auth
def api_hash_batch():
    """Uy quyen NAS tinh Partial Hash (1MB dau tien) — Song song hoa de tang toc."""
    data = request.json
    if not data or "files" not in data:
        return jsonify({"error": "Yêu cầu không hợp lệ"}), 400

    result = {}
    items = data.get("files", [])
    # CC-005 P0: cap batch (DoS/CPU) + resolve containment thay vi noi chuoi
    # base_dir + local_path (traversal doc file tuy y truoc day).
    if not isinstance(items, list):
        return jsonify({"error": "Yêu cầu không hợp lệ"}), 400
    if len(items) > 200:
        return jsonify({"error": "Batch qua lon (toi da 200 file/lan)"}), 400

    def _hash_one(item):
        try:
            url_path = item.get("path")
            local_path = item.get("local_path")
            if not url_path or not local_path:
                return None
            # CC-005 P0: resolve qua containment (realpath) — chan '..',
            # absolute path, symlink thoat root. Tra None thay vi throw.
            real_path = _resolve_webdav_request_path(local_path)
            if not real_path or not os.path.isfile(real_path):
                return None
            if rust_bridge and rust_bridge.is_rust_accelerated():
                try:
                    return (url_path, rust_bridge.compute_fast_md5_1mb(real_path))
                except Exception:
                    pass
            with open(real_path, "rb") as f:
                chunk = f.read(1048576)
                return (url_path, hashlib.md5(chunk).hexdigest())
        except Exception:
            return None
    
    # Adaptive workers: Đọc dia IO-bound, 2-4 luồng tuy tai nguyen
    try:
        mem = psutil.virtual_memory().percent
        workers = 2 if mem > 75 else (3 if mem > 60 else 4) 
    except Exception:
        workers = 2

    # Fast path: Rust rayon multi-threaded batch (zero GIL, 100% containment validated)
    bridge = globals().get("rust_bridge")
    if bridge and hasattr(bridge, "compute_batch_md5_1mb") and bridge.is_rust_accelerated():
        valid_pairs = []
        for item in items:
            try:
                url_path = item.get("path")
                local_path = item.get("local_path")
                if not url_path or not local_path:
                    continue
                real_path = _resolve_webdav_request_path(local_path)
                if real_path and os.path.isfile(real_path):
                    valid_pairs.append((url_path, real_path))
            except Exception:
                continue
        rust_res = bridge.compute_batch_md5_1mb(valid_pairs, workers=workers)
        if rust_res is not None:
            return jsonify(rust_res)

    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for r in pool.map(_hash_one, items):
            if r: result[r[0]] = r[1]

    return jsonify(result)


@app.route("/api/disk/scan_duplicates", methods=["POST"])
@requires_auth
def api_disk_scan_duplicates():
    """Quét tệp trùng lặp 3 tầng đa luồng bằng Rust rayon (Size -> MD5 1MB -> SHA256)."""
    data = request.json or {}
    rel_dir = str(data.get("path", "") or "").strip()
    if rel_dir:
        target_dir = _resolve_webdav_request_path(rel_dir)
    else:
        target_dir = _get_webdav_public_dir()
    if not target_dir or not os.path.isdir(target_dir):
        return jsonify({"error": "Thư mục mục tiêu không hợp lệ hoặc không tồn tại"}), 400
    try:
        min_size = max(0, int(data.get("min_size", 1024) or 1024))
        limit = max(1, min(500, int(data.get("limit", 500) or 500)))
    except Exception:
        return jsonify({"error": "Tham số min_size/limit không hợp lệ"}), 400
    try:
        mem = psutil.virtual_memory().percent
        workers = 2 if mem > 75 else (3 if mem > 60 else 4)
    except Exception:
        workers = 2

    bridge = globals().get("rust_bridge")
    if bridge and hasattr(bridge, "find_duplicates"):
        report = bridge.find_duplicates(target_dir, min_size=min_size, workers=workers, limit=limit)
        if report is not None:
            report["engine"] = "rust_rayon"
            return jsonify(report)
    return jsonify({"error": "Rust duplicate_finder chưa sẵn sàng trên hệ thống"}), 503

# ============ FILE HASH STORE — cùng DB với file_index và work queue =========
def _hash_db():
    """Open the shared index database and migrate the legacy hash table once."""
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
    try:
        conn.execute("""CREATE TABLE IF NOT EXISTS file_hashes (
            path TEXT PRIMARY KEY, size INTEGER NOT NULL DEFAULT 0,
            mtime INTEGER NOT NULL DEFAULT 0, sha256 TEXT NOT NULL DEFAULT '',
            partial TEXT NOT NULL DEFAULT '', scanned_at INTEGER NOT NULL DEFAULT 0)""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_hashes_sha ON file_hashes(sha256)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_hashes_size ON file_hashes(size)")
        conn.execute("CREATE TABLE IF NOT EXISTS file_hash_migrations (name TEXT PRIMARY KEY, migrated_at REAL NOT NULL)")
        conn.commit()
        migrated = conn.execute("SELECT 1 FROM file_hash_migrations WHERE name='legacy_file_hashes'").fetchone()
        if not migrated:
            legacy_root = _get_webdav_public_dir()
            legacy_path = os.path.join(legacy_root, ".nas_meta", "file_hashes.db") if legacy_root else ""
            if legacy_path and os.path.isfile(legacy_path) and os.path.realpath(legacy_path) != os.path.realpath(INDEX_DB_PATH):
                conn.execute("ATTACH DATABASE ? AS legacy_hashes", (legacy_path,))
                try:
                    legacy_table = conn.execute(
                        "SELECT 1 FROM legacy_hashes.sqlite_master WHERE type='table' AND name='file_hashes'").fetchone()
                    if legacy_table:
                        columns = set(row[1] for row in conn.execute("PRAGMA legacy_hashes.table_info(file_hashes)"))
                        if {"path", "size", "mtime", "sha256", "partial", "scanned_at"}.issubset(columns):
                            conn.execute("""INSERT OR IGNORE INTO file_hashes(path,size,mtime,sha256,partial,scanned_at)
                                SELECT CASE WHEN h.path LIKE '/webdav/%' THEN substr(h.path,9) ELSE trim(h.path,'/') END,
                                       h.size,h.mtime,lower(h.sha256),h.partial,h.scanned_at
                                FROM legacy_hashes.file_hashes h
                                WHERE length(h.sha256)=64 AND h.sha256 NOT GLOB '*[^0-9a-fA-F]*'
                                  AND h.path NOT LIKE '%..%' AND h.path NOT LIKE '%\\%'""")
                            conn.commit()
                finally:
                    conn.execute("DETACH DATABASE legacy_hashes")
            conn.execute("INSERT OR REPLACE INTO file_hash_migrations(name,migrated_at) VALUES('legacy_file_hashes',?)", (time.time(),))
        conn.commit()
    except Exception:
        conn.rollback()
        log.debug("[HashStore] Shared hash database migration deferred", exc_info=True)
    return conn


@app.route("/api/hash/save", methods=["POST"])
@requires_auth
def api_hash_save():
    """Luu batch hash do client tinh (PC/app render + hash, NAS chi luu).
    Body: {"items": [{"path": "/webdav/a.jpg", "size": 123, "mtime": 456,
    "sha256": "abc...", "partial": "md5-1MB", "source_version": "..."}]} — toi da 500 items/lan.
    source_version duoc dong bo bang vao bang verified_file_hashes (DB duy nhat nas_index.db)."""
    try:
        data = request.get_json(force=True, silent=True) or {}
        items = data.get("items", [])
        if not isinstance(items, list) or len(items) > 500:
            return jsonify({"error": "items phai la list <= 500"}), 400
        now = int(time.time())
        saved = 0
        conn = _hash_db()
        try:
            import re as _re_hash
            for it in items:
                try:
                    p = str(it.get("path", "") or "")
                    s = str(it.get("sha256", "") or "").lower()
                    # CC-005 P1: validate hex + normalize path (chong path spoof/DB pollute)
                    if not p or not _re_hash.match(r"^[a-f0-9]{64}$", s or ""):
                        continue
                    if ".." in p or "\\" in p:
                        continue
                    real = _resolve_webdav_request_path(p)
                    if not real:
                        continue
                    part = str(it.get("partial", "") or "")
                    if part and not _re_hash.match(r"^[a-f0-9]{1,64}$", part.lower()):
                        part = ""
                    try:
                        sz = int(it.get("size", 0) or 0)
                        mt = int(it.get("mtime", 0) or 0)
                    except Exception:
                        continue
                    if sz < 0 or sz > 10995116277760 or mt < 0:
                        continue
                    source_version = str(it.get("source_version", "") or "")[:128]
                    conn.execute(
                        "INSERT OR REPLACE INTO file_hashes "
                        "(path, size, mtime, sha256, partial, scanned_at) "
                        "VALUES (?, ?, ?, ?, ?, ?)",
                        (p, sz, mt, s, part, now))
                    saved += 1
                    # Dong bo sang bang verified_file_hashes (DB duy nhat) neu co source_version
                    if source_version:
                        try:
                            info = _duplicate_file_info(p)
                            if info['version'] == source_version:
                                conn.execute(
                                    "INSERT OR REPLACE INTO verified_file_hashes"
                                    "(file_id,version,size,sha256,verified_at,path)"
                                    " VALUES(?,?,?,?,?,?)",
                                    (info['file_id'], source_version, sz, s, now, p))
                        except (OSError, ValueError):
                            # File chua index hoac khong xac nhan duoc thong tin — chi luu file_hashes
                            pass
                except Exception:
                    continue
            conn.commit()
        finally:
            conn.close()
        return jsonify({"result": "ok", "saved": saved})
    except Exception as e:
        log.error("[API] Error in api_hash_save: %s", e)
        return jsonify({"error": str(e)}), 500


@app.route("/api/hash/duplicates", methods=["GET"])
@requires_auth
def api_hash_duplicates():
    """Nhom file trung lap theo sha256 — LOP 4: re-stat + ETag truoc khi tra.
    Moi file trong nhom duoc stat lai tren disk: size/mtime khac luc luu hash
    -> stale (tu refresh row, loai khoi nhom lan nay). ETag hien tai
    ("size-mtime") dinh kem tung file de app dung If-Match khi MOVE.
    Nhom con < 2 file sau loc -> bo. Query: ?min_size= (mac dinh 1KB)."""
    try:
        min_size = int(request.args.get("min_size", "1024") or 1024)
        conn = _hash_db()
        try:
            rows = conn.execute(
                "SELECT sha256, GROUP_CONCAT(path, char(31)), "
                "SUM(size), COUNT(*) FROM file_hashes "
                "WHERE sha256 != '' AND size >= ? "
                "GROUP BY sha256 HAVING COUNT(*) > 1 "
                "ORDER BY SUM(size) DESC LIMIT 500",
                (min_size,)).fetchall()
        finally:
            conn.close()
        groups = []
        stale_paths = []
        for sha, paths_blob, _total, _count in rows:
            paths = (paths_blob or "").split(chr(31))
            verified = []
            for p in paths:
                try:
                    info = _verify_hash_row(p, sha)
                except Exception:
                    continue
                if info is None:
                    continue  # file mat hoac doc loi — bo qua lan nay
                if info.get("stale"):
                    stale_paths.append(p)
                    continue  # da doi noi dung — khong bao trung
                verified.append(info)
            if len(verified) < 2:
                continue
            total = sum(v["size"] for v in verified)
            groups.append({"sha256": sha, "files": [v["path"] for v in verified],
                           "etags": {v["path"]: v["etag"] for v in verified},
                           "count": len(verified), "wasted_bytes": total})
        # Refresh async cac row stale (khong chan response).
        if stale_paths:
            try:
                _refresh_stale_rows(stale_paths)
            except Exception as e:
                log.warning("[Hash] Refresh stale loi: %s", e)
        return jsonify({"groups": groups, "total_groups": len(groups),
                        "stale_refreshed": len(stale_paths)})
    except Exception as e:
        log.error("[API] Error in api_hash_duplicates: %s", e)
        return jsonify({"error": str(e)}), 500


def _verify_hash_row(webdav_path, expected_sha):
    """Re-stat file tren disk, so size/mtime voi kho hash.
    Tra None neu file mat/doc loi; {"stale": True} neu da doi noi dung;
    dict day du kem etag hien tai neu khop."""
    real = _resolve_webdav_request_path(webdav_path)
    if not real or not os.path.isfile(real):
        return None
    try:
        st = os.stat(real)
    except Exception:
        return None
    size = st.st_size
    mtime = int(st.st_mtime)
    conn = _hash_db()
    try:
        row = conn.execute(
            "SELECT size, mtime FROM file_hashes WHERE path = ?",
            (webdav_path,)).fetchone()
    finally:
        if conn is not None:
            conn.close()
    if not row:
        return None
    if int(row[0] or 0) != size or int(row[1] or 0) != mtime:
        return {"stale": True, "path": webdav_path}
    # CC-005 P1: ETag them inode (copy/replace trung size-mtime khong sai cache)
    etag = '"%x-%x-%x"' % (getattr(st, "st_ino", 0), size, mtime)
    return {"path": webdav_path, "size": size, "mtime": mtime,
            "etag": etag, "sha256": expected_sha}


def _refresh_stale_rows(paths):
    """Xoa row stale de lan quet sau tinh lai (khong giu hash cu gay bao sai)."""
    if not paths:
        return
    conn = _hash_db()
    try:
        conn.executemany("DELETE FROM file_hashes WHERE path = ?",
                         [(p,) for p in paths])
        conn.commit()
    finally:
        if conn is not None:
            conn.close()


@app.route("/api/hash/lookup", methods=["GET"])
@requires_auth
def api_hash_lookup():
    """Tra cuu hash cua 1 file: ?path=/webdav/a.jpg -> row da luu (neu co)."""
    try:
        p = (request.args.get("path", "") or "").strip()
        if not p:
            return jsonify({"error": "Thieu path"}), 400
        conn = _hash_db()
        try:
            row = conn.execute(
                "SELECT path, size, mtime, sha256, partial, scanned_at "
                "FROM file_hashes WHERE path = ?", (p,)).fetchone()
        finally:
            conn.close()
        if not row:
            return jsonify({"found": False})
        return jsonify({"found": True, "path": row[0], "size": row[1],
                        "mtime": row[2], "sha256": row[3], "partial": row[4],
                        "scanned_at": row[5]})
    except Exception as e:
        log.error("[API] Error in api_hash_lookup: %s", e)
        return jsonify({"error": str(e)}), 500

# ============ THUMBNAIL GENERATOR (Synology-style, TURBO MODE) ============
# 4 worker song song, smart frame (chon frame sang nhat), khong throttle CPU

THUMB_DIR_NAME = ".thumbs"
THUMB_MAX_SIZE = 320
THUMB_QUALITY = 80
MEDIA_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".gif"}
MEDIA_VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".mpg", ".mpeg", ".wmv", ".flv", ".ts", ".m4v"}
MEDIA_ALL_EXTS = MEDIA_IMAGE_EXTS | MEDIA_VIDEO_EXTS
# Debian 9/Python 3.5 NAS images do not have a HEIC decoder. Keep HEIC in
# media discovery/status, but never enqueue it for a thumbnail retry loop.
THUMB_UNSUPPORTED_EXTS = {".heic", ".heif"}

_thumb_stats = {"generated": 0, "total_media": 0, "running": False, "last_file": "", "errors": 0, "paused": False, "block_reasons": []}
_thumb_stats_lock = threading.Lock()
_thumb_paused = threading.Event()  # Set = dang chay, Clear = t?m dùng
_thumb_paused.set()  # Mac dinh: CHAY
_thumb_gate_lock = threading.Lock()
_thumb_manual_paused = False
# CLIENT-FIRST: may tu render + upload thumb, daemon khong quet dinh ky nua.
# Quet chi chay khi: (1) app yeu cau tay qua /api/thumb/control action=scan,
# (2) du 24h ke tu lan quet truoc VA NAS that su idle. Khong con quet 30ph.
_thumb_scan_requested = False
_THUMB_DAILY_INTERVAL_S = 86400  # 24h giua cac lan quet tu dong
_thumb_auto_block_reasons = set()
_thumb_block_timestamps = {}             # {reason: time_when_blocked} — để auto-expire stale activity blocks
_THUMB_ACTIVITY_BLOCK_MAX_AGE_S = 600    # 10 phút: block từ /api/thumb/activity phải heartbeat mỗi 3 phút

def _apply_thumbnail_gate_locked():
    """?p dùng trạng thái pause/resume tu manual pause + cac tac vu nen n?ng.
    FIX: auto-expire activity blocks (from /api/thumb/activity) whose TTL has
    elapsed if the sending app died without sending active=false."""
    now = time.time()
    stale = [
        reason for reason, expires in _thumb_block_timestamps.items()
        if reason in _thumb_auto_block_reasons and now >= expires
    ]
    for reason in stale:
        _thumb_auto_block_reasons.discard(reason)
        _thumb_block_timestamps.pop(reason, None)
        log.warning("[Thumbnail] Auto-cleared stale activity gate: %s", reason)
    should_pause = _thumb_manual_paused or bool(_thumb_auto_block_reasons)
    if should_pause:
        _thumb_paused.clear()
    else:
        _thumb_paused.set()
    with _thumb_stats_lock:
        _thumb_stats["paused"] = should_pause
        _thumb_stats["block_reasons"] = sorted(_thumb_auto_block_reasons)
        if should_pause:
            _thumb_stats["running"] = False
            if _thumb_auto_block_reasons:
                _thumb_stats["last_file"] = "Tạm dừng: " + ", ".join(sorted(_thumb_auto_block_reasons))

def _set_thumbnail_auto_block(reason, active, ttl_seconds=None):
    """Chan thumbnail khi livestream/ytdlp/sync dang chay; bo chan khi da xong.
    ttl_seconds: if set, block auto-expires after this many seconds.
    Internal callers (livestream, USB, screen record) pass ttl=None (no expiry).
    External callers (/api/thumb/activity) pass ttl_seconds=600 (10 min) so that
    if the sending app is killed, the block auto-clears."""
    reason = str(reason or "").strip()
    if not reason:
        return
    with _thumb_gate_lock:
        changed = False
        if active and reason not in _thumb_auto_block_reasons:
            _thumb_auto_block_reasons.add(reason)
            if ttl_seconds is not None:
                _thumb_block_timestamps[reason] = time.time() + ttl_seconds
            changed = True
        elif active and reason in _thumb_auto_block_reasons:
            # Heartbeat: refresh TTL for activity blocks
            if ttl_seconds is not None:
                _thumb_block_timestamps[reason] = time.time() + ttl_seconds
        elif (not active) and reason in _thumb_auto_block_reasons:
            _thumb_auto_block_reasons.discard(reason)
            _thumb_block_timestamps.pop(reason, None)
            changed = True
        _apply_thumbnail_gate_locked()
    if changed:
        log.info("[Thumbnail] Gate %s: %s", "BLOCK" if active else "UNBLOCK", reason)

def _get_thumb_path(base_dir, file_path):
    # FIX-SMB-THUMB: Thumb cache LUÔN nằm trong WebDAV root (.thumbnails/),
    # bất kể file upload qua SMB hay WebDAV — tập trung ở 1 chỗ, không scatter.
    base_dir_real = os.path.realpath(base_dir)
    file_path_real = os.path.realpath(file_path)
    if file_path_real.startswith(base_dir_real + os.sep):
        rel = os.path.relpath(file_path_real, base_dir_real)
        # BẮT BUỘC: Khôi phục lại cấu trúc Hash cũ (ứng với thư mục .thumbs 3.3 GB gốc)
        # Vì file cũ được hash với chuỗi "New folder/..." do thư mục gốc trước đây là /srv/...
        hash_str = "New folder/" + rel if "New folder" in base_dir_real else rel
    else:
        # FIX-SMB-THUMB: file nằm ngoài WebDAV root (upload qua SMB) → hash từ đường dẫn tuyệt đối
        # để thumb luôn tìm được dù file ở đâu trong SMB share.
        hash_str = file_path_real
    safe_hash = hashlib.md5(hash_str.encode('utf-8')).hexdigest()
    return os.path.join(base_dir_real, THUMB_DIR_NAME, safe_hash + ".jpg")

def _thumb_current(source, target, minimum_size=0):
    try:
        src = os.stat(source)
        cached = os.stat(target)
        try:
            version = os.getxattr(target, "user.nas_source_version")
        except (AttributeError, OSError):
            version = None
        if version is not None:
            return cached.st_size > 0 and version == _duplicate_stat_version(src).encode("ascii")
        return (cached.st_size > minimum_size and
                cached.st_mtime_ns >= max(src.st_mtime_ns, src.st_ctime_ns))
    except OSError:
        return False


def _publish_thumb(source, target, write_jpeg, expected_version=None):
    """Stage the JPEG beside its destination and publish only for the source we read."""
    src = os.stat(source)
    version = _duplicate_stat_version(src)
    if expected_version is not None and expected_version != version:
        raise ValueError("source_version_changed")
    fd, staged = tempfile.mkstemp(prefix=".thumb-", suffix=".jpg", dir=os.path.dirname(target))
    os.close(fd)
    try:
        if not write_jpeg(staged):
            return False
        if os.path.getsize(staged) == 0:
            return False
        try:
            os.setxattr(staged, "user.nas_source_version", version.encode("ascii"))
        except (AttributeError, OSError):
            pass  # Filesystems without xattrs use the JPEG timestamp.
        if _duplicate_stat_version(os.stat(source)) != version:
            raise ValueError("source_version_changed")
        os.replace(staged, target)
        return True
    finally:
        if os.path.exists(staged):
            os.remove(staged)


def _generate_image_thumb(src_path, dst_path):
    """Generate JPEG thumbnail for images. Handles HEIC/HEIF detection and skips
    unsupported formats early to avoid retry storms on NAS without libheif."""
    try:
        src_sz = os.path.getsize(src_path) if os.path.exists(src_path) else 0
    except OSError:
        src_sz = 0

    # FIX-THUMB-HEIF: detect HEIF/HEIC by magic bytes, not extension.
    # Samsung/Facebook apps rename HEIC to .png — extension check misses these.
    # Magic: ftyp mif1, ftyp msf1, ftyp heic, ftyp heix, ftyp hevc.
    if src_sz >= 12:
        try:
            with open(src_path, "rb") as _hdr:
                _magic = _hdr.read(12)
            if _magic[4:8] in (b"ftyp",) and _magic[8:12] in (
                b"mif1", b"msf1", b"heic", b"heix", b"hevc", b"hevx",
                b"heim", b"heis", b"hevm", b"hevs", b"mif2",
            ):
                # NAS PIL 4.0.0 + ffmpeg 3.2 cannot decode HEIF — mark neg-cache so daemon skips
                try:
                    _thumb_neg_mark(src_path)
                except Exception:
                    pass
                return False
        except Exception:
            pass

    # Skip zero-byte or trivially small files (truncated upload, corrupt header).
    if src_sz < 200:
        return False

    # Extension check for well-named HEIC/HEIF (belt-and-suspenders).
    if os.path.splitext(src_path)[1].lower() in ('.heic', '.heif'):
        return False

    pil_version = "unavailable"
    try:
        from PIL import Image
        pil_version = getattr(Image, "__version__", "unknown")
        os.makedirs(os.path.dirname(dst_path), exist_ok=True)
        # FIX-RETRY-STORM: remove any stale output so the success check below
        # can't accidentally pass on a leftover file from a previous attempt.
        if os.path.exists(dst_path):
            try:
                os.remove(dst_path)
            except Exception:
                pass
        # Fast path: Rust ARM64 NEON SIMD (fast_image_resize + zune-jpeg)
        _thumb_bridge = globals().get("rust_bridge")
        if _thumb_bridge and hasattr(_thumb_bridge, "generate_image_thumbnail"):
            try:
                if (_thumb_bridge.generate_image_thumbnail(src_path, dst_path, THUMB_MAX_SIZE, THUMB_QUALITY)
                        and os.path.exists(dst_path) and os.path.getsize(dst_path) > 100):
                    return True
            except Exception:
                pass
        with Image.open(src_path) as opened:
            img = opened.convert("RGB") if opened.mode != "RGB" else opened.copy()
        img.thumbnail((THUMB_MAX_SIZE, THUMB_MAX_SIZE), Image.LANCZOS)
        try:
            img.save(dst_path, "JPEG", quality=THUMB_QUALITY)
        finally:
            img.close()
        return os.path.exists(dst_path) and os.path.getsize(dst_path) > 100
    except Exception as e:
        log.warning("[ThumbImg] Failed %s: %s (size=%d, PIL=%s)",
                    os.path.basename(src_path), e, src_sz, pil_version)
        # FIX-TRUNCATED-STORM: file truncated/corrupt khong bao gio decode duoc —
        # mark neg-cache de daemon bo qua 7 ngay (nhu AV1 video). Log 2026-09-19:
        # 1085 warning/ngay toan "image file is truncated" tu FB_IMG tai do.
        try:
            _thumb_neg_mark(src_path)
        except Exception:
            pass
        return False

def _scrub_stale_zero_byte_files(roots, max_seconds=10, min_age_seconds=600):
    """Remove settled 0-byte TEMP files from NAS roots without racing active uploads.

    P1-5: ban cu xoa MOI file 0-byte (ke ca file user co y de trong). Quy tac
    moi: chi xoa file khop pattern tam app (_is_app_temp_zero_byte) VA du tuoi.
    """
    excluded = {THUMB_DIR_NAME, ".thumbs", ".thumbnails", ".trash", ".nas_meta", ".git", ".recycle", "@eaDir", "#recycle"}
    deadline = time.time() + max_seconds
    removed = 0
    scanned = 0
    seen = set()
    for root in roots:
        if time.time() >= deadline or not root or not os.path.isdir(root):
            break
        try:
            real_root = os.path.realpath(root)
            if real_root in seen:
                continue
            seen.add(real_root)
            for dirpath, dirnames, filenames in os.walk(real_root, topdown=True):
                if time.time() >= deadline:
                    break
                dirnames[:] = [d for d in dirnames if d not in excluded and not d.startswith(".")]
                for fname in filenames:
                    scanned += 1
                    if time.time() >= deadline:
                        break
                    path = os.path.join(dirpath, fname)
                    try:
                        stat = os.stat(path)
                        if stat.st_size == 0 and _is_app_temp_zero_byte(fname) and time.time() - stat.st_mtime >= min_age_seconds:
                            os.remove(path)
                            removed += 1
                    except (FileNotFoundError, PermissionError, OSError):
                        continue
        except (PermissionError, OSError):
            continue
    if removed:
        log.info("[NASCleanup] Removed %d stale zero-byte files (scanned=%d)", removed, scanned)
    return removed


def _frame_brightness(jpg_path):
    """Do sang trung binh cua JPEG (0-255). Frame den = 0-15."""
    bridge = globals().get("rust_bridge")
    if bridge and hasattr(bridge, "compute_frame_brightness"):
        try:
            val = bridge.compute_frame_brightness(jpg_path)
            if val is not None:
                return val
        except Exception:
            pass
    try:
        from PIL import Image
        img = Image.open(jpg_path).convert('L')
        pixels = list(img.getdata())
        return sum(pixels) / max(len(pixels), 1)
    except Exception:
        return 0

# BO KHOA BAO VE RAM: Toi da 2 luồng FFmpeg
import threading
_ffmpeg_semaphore = threading.Semaphore(1)  # REVERT: 2 -> 1 de trảnh I/O burst lam SATA timeout

def _probe_video_codec(src_path):
    """Return the first video codec, or a stable diagnostic label."""
    if src_path and str(src_path).lower().endswith((".mp4", ".m4v", ".mov")):
        bridge = globals().get("rust_bridge")
        if bridge and hasattr(bridge, "parse_mp4_header"):
            try:
                info = bridge.parse_mp4_header(src_path)
                if info and info.get("valid") and info.get("video_codec"):
                    return str(info["video_codec"])
            except Exception:
                pass
    try:
        probe = subprocess.run(
            ["/usr/bin/ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=codec_name", "-of", "default=nw=1:nk=1", src_path],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5,
        )
        return (probe.stdout or b"").decode("utf-8", errors="ignore").strip() or "unknown"
    except Exception as e:
        return "probe-error:%s" % type(e).__name__

def _generate_video_thumb(src_path, dst_path):
    """FIX: chien luoc seek nhieu nac de tang ti le thumbnail thảnh cầng.

    Lỗi cu:
    - Seek 00:00:03 -> video < 3s thi ffmpeg fail im lang
    - Timeout 10s -> video lon hoac codec phuc tap (H.265/HEVC/AV1 tren rk3328
      ARM 1-2GB RAM khong co hwacc) thi ffmpeg bi kill truoc khi extract frame
    - Khong probe duration -> không bi?t co the seek bao nhieu
    - Fallback -ss 0 sau khi seek 3s lỗi: kernel da cache file -> 2nd run nhanh hon
      nhung van lỗi neu codec không decode được bang ffmpeg 3.2
    - Tao placeholder + return True che dau lỗi -> client không bi?t re-request

    Logic moi:
    1. Probe duration nhanh (ffprobe 3s) de chon seek time hop ly.
    2. Seek tai 10% duration (max 3s, min 0.5s) — trảnh frame den dau video.
    3. Timeout dong theo size: 15s cho file <100MB, 25s cho file <1GB, 40s cho >1GB.
    4. Fallback seek 0 neu seek 10% lỗi (file hong header time index).
    5. Chi tao placeholder khi MOI nhanh deu fail. Return False de retry sau (chu
       khong return True che dau).
    """
    try:
        import os, subprocess
        os.makedirs(os.path.dirname(dst_path), exist_ok=True)

        with _ffmpeg_semaphore:
            # Buoc 1 + 1.5 fast path: Rust MP4 atom parser (<0.1ms, loai bo ca 2 lan fork ffprobe)
            duration = 0.0
            codec = "unknown"
            _rust_probed = False
            if src_path and str(src_path).lower().endswith((".mp4", ".m4v", ".mov")):
                _mp4_bridge = globals().get("rust_bridge")
                if _mp4_bridge and hasattr(_mp4_bridge, "parse_mp4_header"):
                    try:
                        _mp4_info = _mp4_bridge.parse_mp4_header(src_path)
                        if _mp4_info and _mp4_info.get("valid") and _mp4_info.get("video_codec"):
                            duration = float(_mp4_info.get("duration_secs", 0) or 0)
                            codec = str(_mp4_info.get("video_codec"))
                            _rust_probed = True
                    except Exception:
                        pass

            # Fallback ffprobe cho non-MP4 hoac file loi header
            if not _rust_probed:
                try:
                    probe = subprocess.run(
                        ["/usr/bin/ffprobe", "-v", "error",
                         "-show_entries", "format=duration",
                         "-of", "default=noprint_wrappers=1:nokey=1",
                         src_path],
                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5
                    )
                    duration = float((probe.stdout or b"").decode("utf-8", errors="ignore").strip() or "0")
                except Exception:
                    duration = 0.0

                if os.path.exists(src_path):
                    try:
                        probe2 = subprocess.run(
                            ["/usr/bin/ffprobe", "-v", "error",
                             "-select_streams", "v:0",
                             "-show_entries", "stream=codec_name",
                             "-of", "default=nw=1:nk=1",
                             src_path],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5,
                        )
                        codec = (probe2.stdout or b"").decode("utf-8", errors="ignore").strip() or "unknown"
                        probe_stderr = (probe2.stderr or b"").decode("utf-8", errors="ignore").lower()
                        if any(tok in probe_stderr for tok in (
                            "no decoder for stream", "invalid data found",
                            "moov atom not found", "could not find codec",
                        )):
                            log.warning("[ThumbVideo] skip corrupt/unsupported %s (codec=%s, sz=%d)",
                                        os.path.basename(src_path), codec,
                                        os.path.getsize(src_path))
                            _thumb_neg_mark(src_path)
                            return False
                    except Exception:
                        codec = "probe-error"
            # ffmpeg 3.2 trên NAS có decoder software cho VP9/HEVC; chỉ AV1
            # chắc chắn không có decoder (không có libaom/av1).
            if codec and any(c in codec.lower() for c in ("av1", "av01")):
                log.warning("[ThumbVideo] skip unsupported codec=%s %s (sz=%d)",
                            codec, os.path.basename(src_path),
                            os.path.getsize(src_path))
                _thumb_neg_mark(src_path)
                return False

            # Buoc 2: chon seek time hop ly
            if duration > 0:
                # 10% duration, cap 0.5s..3s. Video 2s -> seek 0.2s? Khong, min 0s.
                seek_s = max(0.5, min(3.0, duration * 0.10)) if duration >= 1.0 else 0.0
            else:
                # Không probe được -> th? 3s nhu cu (will fallback to 0 if fail)
                seek_s = 3.0

            # Buoc 3: timeout dua theo size
            try:
                sz = os.path.getsize(src_path)
            except Exception:
                sz = 0
            if sz > 1024 * 1024 * 1024:    # >1GB
                ffmpeg_timeout = 40
            elif sz > 100 * 1024 * 1024:   # >100MB
                ffmpeg_timeout = 25
            else:
                ffmpeg_timeout = 15

            def _run_ffmpeg(ss_arg):
                cmd = ["/usr/bin/ffmpeg", "-y"]
                if ss_arg is not None and ss_arg > 0:
                    cmd += ["-ss", "%.2f" % ss_arg]
                cmd += [
                    "-i", src_path,
                    "-vframes", "1",
                    "-vf", "scale=%d:-1" % THUMB_MAX_SIZE,
                    "-q:v", "5",
                    "-an",  # bo audio cho nhanh
                    dst_path,
                ]
                # FIX-RETRY-STORM: xoa file output cu de khong bi stale file gia
                # thanh cong khi ffmpeg that ra fail (returncode != 0).
                try:
                    if os.path.exists(dst_path):
                        os.remove(dst_path)
                except Exception:
                    pass
                try:
                    proc = subprocess.run(
                        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        timeout=ffmpeg_timeout
                    )
                    if proc.returncode != 0:
                        stderr_text = (proc.stderr or b"").decode("utf-8", errors="ignore").lower()
                        stderr_tail = (proc.stderr or b"")[-400:].decode("utf-8", errors="ignore").strip()
                        log.warning(
                            "[Thumb] ffmpeg rc=%d ss=%s %s | %s",
                            proc.returncode, ss_arg, os.path.basename(src_path), stderr_tail
                        )
                        # FIX-CORRUPT-SKIP: file MP4 bi truncated tai moov atom thi
                        # retry vô ich — ffmpeg se luon fail. Fail-fast de giam log spam.
                        if any(token in stderr_text for token in (
                            "no decoder for stream",  # AV1 file
                            "invalid data found",      # moov corrupt / truncated
                            "moov atom not found",     # progressive download chưa hoàn tất
                            "could not find codec",    # file không phải video
                        )):
                            return "no_decoder"
                except subprocess.TimeoutExpired:
                    log.warning("[Thumb] ffmpeg timeout (%ds) ss=%s: %s", ffmpeg_timeout, ss_arg, os.path.basename(src_path))
                except Exception as e:
                    log.warning("[Thumb] ffmpeg lỗi ss=%s: %s — %s", ss_arg, os.path.basename(src_path), e)
                return "ok"

            # Try 1: seek tinh toan
            r1 = _run_ffmpeg(seek_s)
            if r1 == "no_decoder":
                log.warning("[ThumbVideo] skip — no decoder for %s (sz=%d)",
                            os.path.basename(src_path),
                            os.path.getsize(src_path) if os.path.exists(src_path) else -1)
                _thumb_neg_mark(src_path)
                return False
            if os.path.exists(dst_path) and os.path.getsize(dst_path) > 100:
                return True

            # Try 2: seek 0 (frame dau tien — co the den den cho video TikTok co intro den)
            if seek_s > 0:
                r2 = _run_ffmpeg(0)
                if r2 == "no_decoder":
                    _thumb_neg_mark(src_path)
                    return False
                if os.path.exists(dst_path) and os.path.getsize(dst_path) > 100:
                    return True

            # Try 3: seek giua video (50%) — cuu cầnh khi frame dau bi hong
            if duration > 2.0:
                r3 = _run_ffmpeg(duration / 2.0)
                if r3 == "no_decoder":
                    _thumb_neg_mark(src_path)
                    return False
                if os.path.exists(dst_path) and os.path.getsize(dst_path) > 100:
                    return True

        # FIX-THUMB-DEBUG: When all ffmpeg attempts fail on a file, log a single
        # WARNING with codec hint. Returns False below; the outer _process_one_thumb
        # writes the dedup'd "Không thể tạo ảnh thu nhỏ" log entry. This explicit line
        # lets grep /var/log/nasapi.log | grep ThumbVideo correlate codec failures.
        log.warning(
            "[ThumbVideo] Failed all attempts %s (sz=%d, duration=%.1fs, codec=%s). "
            "ffmpeg 3.2 trên RK3328 không có libaom/HEVC HW accel — AV1/HEVC từ Facebook/TikTok fail.",
            os.path.basename(src_path),
            os.path.getsize(src_path) if os.path.exists(src_path) else -1,
            duration,
            _probe_video_codec(src_path) if os.path.exists(src_path) else "missing",
        )
    except Exception as e:
        log.warning("[Thumb] unexpected error %s: %s", os.path.basename(src_path), e)

    # FIX-RETRY-STORM: Generator KHONG ghi placeholder. Caller quyet dinh:
    #  - Background daemon: khong ghi -> _needs_thumb() giu True, retry o scan sau.
    #  - /api/thumb on-demand (line 11045-11055): ghi placeholder de UI co feedback.
    return False

def _create_placeholder_thumb(dst_path):
    """Tạo ảnh placeholder báo lỗi (ERROR) cho video/ảnh không decode được."""
    try:
        from PIL import Image, ImageDraw
        img = Image.new('RGB', (THUMB_MAX_SIZE, int(THUMB_MAX_SIZE * 9 / 16)), (45, 45, 48))
        draw = ImageDraw.Draw(img)
        
        # Vẽ một dấu X màu đỏ ở giữa để báo lỗi
        w, h = img.size
        cw, ch = w // 2, h // 2
        size = 30
        draw.line((cw - size, ch - size, cw + size, ch + size), fill=(255, 50, 50), width=6)
        draw.line((cw + size, ch - size, cw - size, ch + size), fill=(255, 50, 50), width=6)
        
        img.save(dst_path, 'JPEG', quality=60) 
    except Exception:
        # Fallback: tao ảnh 1x1 pixel màu đen nếu không có thư viện PIL
        try:
            with open(dst_path, "wb") as f:
                f.write(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x01\x00H\x00H\x00\x00\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t\x08\n\x0c\x14\r\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a\x1f\x1e\x1d\x1a\x1c\x1c $.\' \",#\x1c\x1c(7),01444\x1f\'9=82<.342\xff\xdb\x00C\x01\t\t\t\x0c\x0b\x0c\x18\r\r\x182!\x1c!22222222222222222222222222222222222222222222222222\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff\xc4\x00\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\xff\xc4\x00\xb5\x10\x00\x02\x01\x03\x03\x02\x04\x03\x05\x05\x04\x04\x00\x00\x01}\x01\x02\x03\x00\x04\x11\x05\x12!1A\x06\x13Qa\x07\"q\x142\x81\x91\xa1\x08#B\xb1\xc1\x15R\xd1\xf0$3br\x82\t\n\x16\x17\x18\x19\x1a%&\'()*456789:CDEFGHIJSTUVWXYZcdefghijstuvwxyz\x83\x84\x85\x86\x87\x88\x89\x8a\x92\x93\x94\x95\x96\x97\x98\x99\x9a\xa2\xa3\xa4\xa5\xa6\xa7\xa8\xa9\xaa\xb2\xb3\xb4\xb5\xb6\xb7\xb8\xb9\xba\xc2\xc3\xc4\xc5\xc6\xc7\xc8\xc9\xca\xd2\xd3\xd4\xd5\xd6\xd7\xd8\xd9\xda\xe1\xe2\xe3\xe4\xe5\xe6\xe7\xe8\xe9\xea\xf1\xf2\xf3\xf4\xf5\xf6\xf7\xf8\xf9\xfa\xff\xc4\x00\x1f\x01\x00\x03\x01\x01\x01\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\xff\xc4\x00\xb5\x11\x00\x02\x01\x02\x04\x04\x03\x04\x07\x05\x04\x04\x00\x01\x02\x77\x00\x01\x02\x03\x11\x04\x05!1\x06\x12AQ\x07aq\x13\"2\x81\x08\x14B\x91\xa1\xb1\xc1\t#3R\xf0\x15br\xd1\n\x16$4\xe1%\xf1\x17\x18\x19\x1a&\'()*56789:CDEFGHIJSTUVWXYZcdefghijstuvwxyz\x82\x83\x84\x85\x86\x87\x88\x89\x8a\x92\x93\x94\x95\x96\x97\x98\x99\x9a\xa2\xa3\xa4\xa5\xa6\xa7\xa8\xa9\xaa\xb2\xb3\xb4\xb5\xb6\xb7\xb8\xb9\xba\xc2\xc3\xc4\xc5\xc6\xc7\xc8\xc9\xca\xd2\xd3\xd4\xd5\xd6\xd7\xd8\xd9\xda\xe2\xe3\xe4\xe5\xe6\xe7\xe8\xe9\xea\xf2\xf3\xf4\xf5\xf6\xf7\xf8\xf9\xfa\xff\xda\x00\x0c\x03\x01\x00\x02\x11\x03\x11\x00?\x00\xfd\xfc\xa8\xff\xd9")
        except Exception:
            pass

# FIX-NEG-THUMB-CACHE: negative-cache cho file unsupported (AV1, corrupt).
# Daemon rescan toàn disk mỗi vòng; không cache thì mỗi vòng probe + spawn
# ffmpeg lại cho cùng file — tốn CPU rk3328, spam log. Cache key = realpath,
# value = timestamp fail. TTL 7 ngày để ffmpeg update vẫn retry được.
_THUMB_NEG_CACHE = {}
_THUMB_NEG_CACHE_LOCK = threading.Lock()
_THUMB_NEG_CACHE_TTL = 7 * 24 * 3600
# FIX-PERSIST-NEG: cache memory-only mat khi restart -> rescan dau spam lai.
# Persist xuong SQLite (DB_PATH), load luc start, prune TTL.
_THUMB_NEG_CACHE_LOADED = False

def _thumb_neg_db():
    conn = None
    try:
        conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
        cur = conn.cursor()
        cur.execute("CREATE TABLE IF NOT EXISTS thumb_neg_cache (path TEXT PRIMARY KEY, ts REAL)")
        cur.execute("CREATE TABLE IF NOT EXISTS thumbnail_cache_migrations (name TEXT PRIMARY KEY, migrated_at REAL NOT NULL)")
        conn.commit()
        migrated = cur.execute("SELECT 1 FROM thumbnail_cache_migrations WHERE name='legacy_thumb_neg_cache'").fetchone()
        if not migrated:
            legacy_path = DB_PATH
            if legacy_path and os.path.isfile(legacy_path) and os.path.realpath(legacy_path) != os.path.realpath(INDEX_DB_PATH):
                conn.execute("ATTACH DATABASE ? AS legacy_thumbnail", (legacy_path,))
                try:
                    legacy_table = conn.execute(
                        "SELECT 1 FROM legacy_thumbnail.sqlite_master WHERE type='table' AND name='thumb_neg_cache'").fetchone()
                    if legacy_table:
                        conn.execute("INSERT OR IGNORE INTO thumb_neg_cache(path,ts) SELECT path,ts FROM legacy_thumbnail.thumb_neg_cache")
                        conn.commit()
                finally:
                    conn.execute("DETACH DATABASE legacy_thumbnail")
            cur.execute("INSERT OR REPLACE INTO thumbnail_cache_migrations(name,migrated_at) VALUES('legacy_thumb_neg_cache',?)", (time.time(),))
            conn.commit()
        return conn
    except Exception:
        if conn is not None:
            conn.close()
        return None

def _thumb_neg_load():
    global _THUMB_NEG_CACHE_LOADED
    if _THUMB_NEG_CACHE_LOADED:
        return
    _THUMB_NEG_CACHE_LOADED = True
    try:
        conn = _thumb_neg_db()
        if not conn:
            return
        cur = conn.cursor()
        now = time.time()
        cur.execute("SELECT path, ts FROM thumb_neg_cache")
        rows = cur.fetchall()
        with _THUMB_NEG_CACHE_LOCK:
            for path, ts in rows:
                try:
                    if now - float(ts) <= _THUMB_NEG_CACHE_TTL:
                        if len(_THUMB_NEG_CACHE) < _THUMB_NEG_CACHE_MAX:
                            _THUMB_NEG_CACHE[path] = float(ts)
                    else:
                        cur.execute("DELETE FROM thumb_neg_cache WHERE path=?", (path,))
                except Exception:
                    continue
        conn.commit()
        conn.close()
    except Exception:
        pass
_THUMB_NEG_CACHE_MAX = 5000

def _thumb_neg_cached(real_path):
    """True nếu file đã fail gần đây — daemon bỏ qua không probe lại."""
    _thumb_neg_load()
    try:
        with _THUMB_NEG_CACHE_LOCK:
            ts = _THUMB_NEG_CACHE.get(real_path)
            if ts is None:
                return False
            src = os.stat(real_path)
            if time.time() - ts > _THUMB_NEG_CACHE_TTL or max(src.st_mtime, src.st_ctime) > ts:
                _THUMB_NEG_CACHE.pop(real_path, None)
                return False
            return True
    except Exception:
        return False

def _thumb_neg_mark(real_path):
    """Đánh dấu file fail unsupported, kèm evict khi đầy."""
    try:
        with _THUMB_NEG_CACHE_LOCK:
            if len(_THUMB_NEG_CACHE) >= _THUMB_NEG_CACHE_MAX:
                oldest = sorted(_THUMB_NEG_CACHE.items(), key=lambda kv: kv[1])[:500]
                for k, _ in oldest:
                    _THUMB_NEG_CACHE.pop(k, None)
            _THUMB_NEG_CACHE[real_path] = time.time()
            try:
                conn = _thumb_neg_db()
                if conn:
                    conn.execute("INSERT OR REPLACE INTO thumb_neg_cache (path, ts) VALUES (?, ?)",
                                 (real_path, time.time()))
                    conn.commit()
                    conn.close()
            except Exception:
                pass
    except Exception:
        pass

def _process_one_thumb(args):
    full_path, thumb_path, ext = args[:3]
    # FIX-HEIC-PRESERVE: do not create a placeholder for HEIC/HEIF — the NAS has
    # no HEIC decoder today; if we wrote a placeholder JPG here, _needs_thumb()
    # would mark the file done forever and the daemon would never retry when the
    # decoder is eventually available.
    if ext in ('.heic', '.heif'):
        try:
            _thumb_neg_mark(full_path)
        except Exception:
            pass
        return False
    try:
        if ext in MEDIA_IMAGE_EXTS:
            if _publish_thumb(full_path, thumb_path, lambda staged: _generate_image_thumb(full_path, staged)):
                return True
        elif ext in MEDIA_VIDEO_EXTS:
            if _publish_thumb(full_path, thumb_path, lambda staged: _generate_video_thumb(full_path, staged)):
                return True
    except Exception as e:
        log.warning("[Thumb] Lỗi xử lý thumbnail cho %s: %s", os.path.basename(full_path), e)
        # FIX-DASH-SPAM: chi ghi log he thong khi Exception that su xay ra (unexpected error).
        # Cac truong hop known-unsupported (AV1, corrupt, HEIF magic-bytes) return False
        # ong ngay lap tuc — khong can thong bao len dashboard, chi log warning la du.
        _add_system_log_once(
            "thumb_err:%s" % thumb_path,
            "ERROR",
            "Thumbnail",
            "Không thể tạo ảnh thu nhỏ cho file: %s" % os.path.basename(full_path),
            86400
        )

    # FIX-RETRY-STORM: Background daemon KHONG tao placeholder khi generation fail.
    # _needs_thumb() kiem tra "exists && size>0" => placeholder bi coi la DONE vinh vien,
    # nen ffmpeg/Pillow sua loi (codec update, libheif, ...) se khong bao gio retry.
    # Placeholder chi nen ghi khi nguoi dung goi /api/thumb on-demand, khong phai tu daemon.
    return False


def _thumbnail_catalogue_page(after_path="", limit=512):
    """Read one bounded page from the ready canonical file_index snapshot."""
    if not isinstance(after_path, str) or len(after_path) > 2048:
        raise ValueError("Invalid thumbnail catalogue cursor")
    if after_path and (os.path.isabs(after_path) or "\\" in after_path or
                       any(part in ("", ".", "..") for part in after_path.split("/"))):
        raise ValueError("Invalid thumbnail catalogue cursor")
    if not isinstance(limit, int) or limit < 1 or limit > 1024:
        raise ValueError("Invalid thumbnail catalogue page size")
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        conn.execute("BEGIN")
        state = conn.execute("SELECT root,ready,catalogue_revision FROM index_state WHERE id=1").fetchone()
        if not state or not state[0] or not state[1]:
            return {"ready": False, "root": state[0] if state else "", "revision": state[2] if state else 0, "rows": []}
        rows = conn.execute(
            "SELECT path,name FROM file_index WHERE is_dir=0 AND path>? ORDER BY path LIMIT ?",
            (after_path, limit)).fetchall()
        for rel_path, _name in rows:
            if (os.path.isabs(rel_path) or "\\" in rel_path or
                    any(part in ("", ".", "..") for part in rel_path.split("/"))):
                raise ValueError("Invalid indexed thumbnail path")
        return {"ready": True, "root": state[0], "revision": int(state[2] or 0), "rows": rows}
    finally:
        if conn is not None:
            conn.close()


def _thumbnail_generator():
    """Background daemon: 24/7 bulk thumbnail generator for NAS.

    Owner: NAS (RK3328) runs 24/7, so it should eat the CPU work, not the phone.
    Scans WEBDAV_FILE_ROOT + SMB share roots, generates missing thumbnails for
    both images and videos, respects pause/block gates, and reports progress
    via _thumb_stats so the Android client can poll /api/thumb/status.

    Concurrency: single daemon thread (already spawned at line 14512). ffmpeg
    is capped to 1 via _ffmpeg_semaphore inside _generate_video_thumb — we
    additionally sleep 1s between items to avoid starving the API server.

    Gates honored:
      - _thumb_paused Event (set by _apply_thumbnail_gate_locked)
      - _background_heavy_work_allowed() (livestream, USB import, RAM>78%, load>2.5,
        user dang duyet <90s)
      - CLIENT-FIRST: quet dinh ky TAT. Chi quet khi app yeu cau tay
        (action=scan) hoac du 24h ke tu lan truoc. May tu render + upload,
        daemon chi lap cho trong luc that su idle.

    Two-pass scan:
      1. First scan: walks all roots, counts media, sets total_media.
      2. Forward sweep: skips files whose .thumbnails/<hash>.jpg already exists.
      3. Ngu day du 24h (thay vi 30 phut) truoc khi xet quet tiep.
    """
    global _thumb_stats, _thumb_scan_requested

    # FIX-AV1-RETRY-STORM: daemon quét toàn disk mỗi vòng; file AV1/corrupt
    # (ffmpeg 3.2 không decoder) bị probe + spawn ffmpeg lại vô ích mỗi vòng,
    # ~2300 warning/ngày trong log 2026-09-15. Giãn rescan 300s -> 1800s.
    # CLIENT-FIRST: tat han quet dinh ky 30ph. Daemon chi quet khi:
    #  (1) app yeu cau tay (action=scan), (2) du 24h + that su idle.
    _RESCAN_INTERVAL_S = 86400  # 24h giua cac lan quet tu dong

    def _scan_roots(after_path=""):
        """Read media candidates from the canonical file_index in bounded pages."""
        cursor_path = after_path
        while True:
            page = _thumbnail_catalogue_page(cursor_path, 512)
            if not page["ready"]:
                yield (None, None, None)
                break
            if page["root"] != os.path.realpath(base_dir):
                raise RuntimeError("catalogue_changed")
            rows = page["rows"]
            if not rows:
                break
            for rel_path, name in rows:
                cursor_path = rel_path
                if any(part.startswith(".") or part in ("_broken_quarantine",) for part in rel_path.split("/")):
                    yield ("", "", rel_path)
                    continue
                ext = os.path.splitext(name)[1].lower()
                yield (os.path.join(base_dir, *rel_path.split("/")), ext, rel_path)

    # FIX-PLACEHOLDER-STALE: real thumbnails are >= 2KB; old error placeholders
    # created by _create_placeholder_thumb() are ~700 bytes (320x180 JPEG, quality 60,
    # solid color + red X). Treat anything <= 1200 bytes as stale placeholder and retry.
    _PLACEHOLDER_MAX_SIZE = 1200

    def _needs_thumb(real_path, base=None):
        """Return True if the thumbnail is missing, zero-byte, or a stale placeholder."""
        try:
            base = base or get_webdav_root()
            tp = _get_thumb_path(base, real_path)
            return not _thumb_current(real_path, tp, _PLACEHOLDER_MAX_SIZE)
        except Exception:
            return True

    log.info("[Thumbnail] Background daemon starting (24/7 idle worker).")

    # FIX-THUMB-INIT: first scan runs IMMEDIATELY (last_full_scan = far in past).
    # The old code set last_full_scan = 0, then checked now - 0 < 300 (True)
    # which caused5 minutes of dead sleep before the first scan could run.
    last_full_scan = 0.0
    _last_gate_closed_log_sig = None
    while True:
        thumb_lease = None
        thumb_checkpoint_cursor = ""
        thumb_completed = 0
        thumb_total = 0
        try:
            now = time.time()
            # Re-evaluate TTL even while paused. Without this, a crashed app's
            # stale activity block would never expire because the worker only slept.
            with _thumb_gate_lock:
                _apply_thumbnail_gate_locked()
                requested_in_memory = bool(globals().get("_thumb_scan_requested", False))
            saved_jobs = nas_work_coordinator.get_status(INDEX_DB_PATH, "thumbnail")
            saved_job = saved_jobs[0] if saved_jobs else None
            expired_run = bool(saved_job and saved_job["state"] == "running" and not saved_job.get("owner"))
            manual_scan = requested_in_memory or bool(saved_job and saved_job["state"] in ("queued", "paused", "retry")) or expired_run
            due_daily = (not saved_job or saved_job["state"] == "failed" or
                         (saved_job["state"] == "complete" and
                          now - float(saved_job["updated_at"]) >= _RESCAN_INTERVAL_S))
            # Gate check FIRST (so pause takes effect immediately, not 5 min later).
            _heavy_ok = _background_heavy_work_allowed()
            if not _thumb_paused.is_set() or not _heavy_ok:
                with _thumb_stats_lock:
                    _thumb_stats["running"] = False
                    _thumb_stats["paused"] = True
                if manual_scan or due_daily:
                    _gate_sig = (_thumb_paused.is_set(), _heavy_ok, tuple(sorted(_thumb_auto_block_reasons)))
                    if _gate_sig != _last_gate_closed_log_sig:
                        _last_gate_closed_log_sig = _gate_sig
                        log.info("[Thumbnail] Gate closed — paused_event=%s heavy_ok=%s block_reasons=%s — sleeping 5s.",
                                 _thumb_paused.is_set(), _heavy_ok, sorted(_thumb_auto_block_reasons))
                time.sleep(5)
                continue
            _last_gate_closed_log_sig = None

            # CLIENT-FIRST: khong quet dinh ky nua. Chi quet khi app yeu cau tay
            # (dat _thumb_scan_requested) hoac du 24h ke tu lan truoc.
            # May tu render + upload thumb, daemon chi lap cho trong luc idle.
            if not manual_scan and not due_daily:
                time.sleep(5)
                continue
            root = _get_webdav_public_dir()
            if not root:
                time.sleep(30)
                continue
            nas_work_coordinator.request_job(INDEX_DB_PATH, "thumbnail", os.path.realpath(root), priority=30)
            thumb_lease = nas_work_coordinator.claim_next(
                INDEX_DB_PATH, "nas:thumbnail", True, lease_seconds=3600, job_name="thumbnail")
            if not thumb_lease:
                time.sleep(5)
                continue
            if manual_scan:
                with _thumb_gate_lock:
                    globals()["_thumb_scan_requested"] = False
            last_full_scan = now
            log.info("[Thumbnail] Starting scan pass (manual=%s) ...", manual_scan)

            base_dir = thumb_lease["root"]
            conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
            try:
                conn.execute("BEGIN")
                state = conn.execute("SELECT root,ready,catalogue_revision FROM index_state WHERE id=1").fetchone()
                if not state or state[0] != os.path.realpath(base_dir) or not state[1]:
                    nas_work_coordinator.checkpoint(
                        INDEX_DB_PATH, "thumbnail", thumb_lease["owner"], thumb_lease["token"], "",
                        0, 0, state="paused", error="catalogue_not_ready")
                    thumb_lease = None
                    time.sleep(5)
                    continue
                catalogue_revision = int(state[2] or 0)
                ext_predicate = " OR ".join(["lower(name) LIKE ?"] * len(MEDIA_ALL_EXTS))
                media_total = conn.execute(
                    "SELECT COUNT(*) FROM file_index WHERE is_dir=0 AND (" + ext_predicate + ")",
                    tuple("%%%s" % ext for ext in sorted(MEDIA_ALL_EXTS))).fetchone()[0]
            finally:
                conn.close()
            saved_cursor = thumb_lease.get("cursor", "")
            saved_revision, separator, after_path = saved_cursor.partition("\n")
            if separator:
                thumb_checkpoint_cursor = str(catalogue_revision) + "\n" + after_path
                thumb_completed = min(thumb_lease.get("completed", 0), media_total)
            elif saved_cursor:
                after_path = saved_cursor
                thumb_checkpoint_cursor = str(catalogue_revision) + "\n" + after_path
                thumb_completed = min(thumb_lease.get("completed", 0), media_total)
            else:
                after_path = ""
                thumb_completed = 0
            total = thumb_completed
            thumb_total = media_total
            thumb_dir = os.path.join(base_dir, THUMB_DIR_NAME)
            try:
                os.makedirs(thumb_dir, exist_ok=True)
            except Exception:
                pass

            # One bounded file_index pass replaces separate WebDAV and SMB tree walks.
            generated_count = 0
            error_count = 0
            interrupted = False
            with _thumb_stats_lock:
                _thumb_stats["generated"] = thumb_completed
                _thumb_stats["errors"] = 0
                _thumb_stats["running"] = True
                _thumb_stats["paused"] = False
                _thumb_stats["start_time"] = now
                _thumb_stats["last_file"] = ""
                _thumb_stats["total_media"] = media_total

            for real_path, ext, rel_path in _scan_roots(after_path):
                if real_path is None:
                    with _thumb_stats_lock:
                        _thumb_stats["running"] = False
                        _thumb_stats["paused"] = True
                    interrupted = True
                    break
                # Re-check gates each iteration — pause may flip mid-scan
                if not _thumb_paused.is_set() or not _background_heavy_work_allowed():
                    with _thumb_stats_lock:
                        _thumb_stats["running"] = False
                        _thumb_stats["paused"] = True
                    log.info("[Thumbnail] Gate closed mid-scan — processed %d/%d so far.", total, _thumb_stats["total_media"])
                    interrupted = True
                    break
                total += 1
                thumb_checkpoint_cursor = str(catalogue_revision) + "\n" + rel_path
                thumb_completed = total
                if total % 25 == 0:
                    if not nas_work_coordinator.checkpoint(
                        INDEX_DB_PATH, "thumbnail", thumb_lease["owner"], thumb_lease["token"],
                        thumb_checkpoint_cursor, min(total, media_total), media_total, state="running"):
                        raise RuntimeError("thumbnail_work_lease_lost")
                if not real_path or ext not in MEDIA_ALL_EXTS:
                    continue

                # Skip formats the NAS cannot decode — never retry, never placeholder.
                if ext in THUMB_UNSUPPORTED_EXTS:
                    continue

                # FIX-NEG-THUMB-CACHE: file đã fail unsupported (AV1/corrupt)
                # trong 7 ngày qua — bỏ qua probe + ffmpeg, không spam log.
                if _thumb_neg_cached(real_path):
                    continue

                if not _needs_thumb(real_path, base_dir):
                    generated_count += 1
                    continue

                try:
                    thumb_path = _get_thumb_path(base_dir, real_path)
                    ok = _process_one_thumb((real_path, thumb_path, ext))
                    generated_count += 1
                    with _thumb_stats_lock:
                        _thumb_stats["generated"] = generated_count
                        _thumb_stats["last_file"] = os.path.basename(real_path)
                    if not ok:
                        error_count += 1
                        with _thumb_stats_lock:
                            _thumb_stats["errors"] = error_count
                except (FileNotFoundError, PermissionError, OSError):
                    pass
                except Exception as e:
                    log.warning("[Thumbnail] Error on %s: %s", real_path, e)
                    error_count += 1
                    with _thumb_stats_lock:
                        _thumb_stats["errors"] = error_count
                time.sleep(0.1)

            # Walk finished — set final total
            with _thumb_stats_lock:
                _thumb_stats["total_media"] = total
                _thumb_stats["running"] = False
            nas_work_coordinator.checkpoint(
                INDEX_DB_PATH, "thumbnail", thumb_lease["owner"], thumb_lease["token"], thumb_checkpoint_cursor,
                min(total, media_total), media_total, state="paused" if interrupted else "complete",
                error="nas_busy" if interrupted else "")
            thumb_lease = None
            log.info("[Thumbnail] Scan pass done: generated=%d errors=%d total=%d",
                     generated_count, error_count, total)
        except KeyboardInterrupt:
            log.info("[Thumbnail] Daemon interrupted, restarting loop.")
            time.sleep(2)
        except Exception as e:
            if thumb_lease:
                try:
                    nas_work_coordinator.checkpoint(
                        INDEX_DB_PATH, "thumbnail", thumb_lease["owner"], thumb_lease["token"],
                        thumb_checkpoint_cursor, min(thumb_completed, thumb_total), thumb_total,
                        state="retry", error=str(e))
                except Exception:
                    pass
            log.error("[Thumbnail] Daemon loop error: %s — restarting in 10s", e)
            time.sleep(10)


@app.route("/api/thumb")
@requires_auth
def api_thumb():
    """Tr? v? thumbnail. On-demand neu ch?a co."""
    import urllib.parse
    webdav_path = request.args.get("path", "")
    # HOTFIX: Android Kotlin `java.net.URL.path` pass raw %20, and `URLEncoder` double encodes to %2520.
    # Flask auto-decodes once back to %20. We must unquote a second time to resolve pure UTF-8 Ext4 strings.
    webdav_path = urllib.parse.unquote(webdav_path)
    
    if not webdav_path:
        return jsonify({"error": "Thiếu đường dẫn"}), 400

    # FIX-SMB-THUMB: dùng _resolve_webdav_request_path để fallback SMB share root,
    # giống hệt /api/media — nếu không, file SMB-uploaded sẽ 404.
    real_path = _resolve_webdav_request_path(webdav_path)
    if not real_path:
        return jsonify({"error": "Đường dẫn không hợp lệ"}), 400
    base_dir = get_webdav_root()
    
    if not os.path.exists(real_path):
        return jsonify({"error": "Tệp không tồn tại"}), 404
    
    thumb_path = _get_thumb_path(base_dir, real_path)
    
    if not _thumb_current(real_path, thumb_path):
        ext = os.path.splitext(real_path)[1].lower()
        if ext in THUMB_UNSUPPORTED_EXTS:
            return jsonify({"error": "NAS không hỗ trợ giải mã HEIC/HEIF"}), 415
        if ext not in MEDIA_IMAGE_EXTS and ext not in MEDIA_VIDEO_EXTS:
            return jsonify({"error": "Không hỗ trợ"}), 415
        # CLIENT-FIRST: on-demand user request KHONG duoc chan boi gate livestream/USB/screen_record.
        # Gate chi chan background worker. Yeu cua nguoi dung mo thu muc duoc render luon neu co tren dia.
        root = _get_webdav_public_dir()
        if not root:
            return jsonify({"error": "Thư mục WebDAV chưa sẵn sàng"}), 503
        nas_work_coordinator.request_job(INDEX_DB_PATH, "thumbnail_on_demand", os.path.realpath(root), priority=50)
        lease = nas_work_coordinator.claim_next(
            INDEX_DB_PATH, "nas:thumb-on-demand", True, lease_seconds=300,
            job_name="thumbnail_on_demand")
        if not lease:
            return jsonify({"error": "NAS đang xử lý tác vụ nền khác", "retry_later": True}), 503
        thumb_dir = os.path.join(base_dir, THUMB_DIR_NAME)
        os.makedirs(thumb_dir, exist_ok=True)
        try:
            ready = _process_one_thumb((real_path, thumb_path, ext))
            nas_work_coordinator.checkpoint(
                INDEX_DB_PATH, "thumbnail_on_demand", lease["owner"], lease["token"],
                "", 1 if ready else 0, 1,
                state="complete" if ready else "failed", error="" if ready else "generation_failed")
        except Exception as error:
            nas_work_coordinator.checkpoint(
                INDEX_DB_PATH, "thumbnail_on_demand", lease["owner"], lease["token"],
                "", 0, 1, state="failed", error=str(error))
            raise
    
    if _thumb_current(real_path, thumb_path):
        # CC-005 P1: send_file + ETag/304 + cap 5MB (truoc day .read() full -> OOM)
        try:
            _tst = os.stat(thumb_path)
            if _tst.st_size > 5 * 1024 * 1024:
                return jsonify({"error": "Thumbnail loi (>5MB)"}), 500
            _tetag = '"%x-%x-%x"' % (getattr(_tst, "st_ino", 0), _tst.st_size, int(_tst.st_mtime))
            _inm = request.headers.get("If-None-Match", "")
            if _inm and _re_module.sub(r'^W/\s*', '', _inm.strip()).strip() == _tetag:
                return Response(status=304)
            from flask import send_file as _send_thumb
            try:
                _resp = _send_thumb(thumb_path, mimetype="image/jpeg", cache_timeout=86400)
            except TypeError:
                _resp = _send_thumb(thumb_path, mimetype="image/jpeg")
            _resp.headers["ETag"] = _tetag
            _resp.headers["Cache-Control"] = "private, no-cache"
            return _resp
        except Exception as e:
            log.warning("[Thumbnail] Serve loi: %s", e)
            return jsonify({"error": "Khong doc duoc thumbnail"}), 500
    return jsonify({"error": "Không tạo được"}), 500

@app.route("/api/thumb/upload", methods=["POST"])
@requires_auth
def api_thumb_upload():
    """Accept a pre-generated thumbnail from Android client.
    POST multipart/form-data:
      - path: WebDAV path of the source media file
      - thumb: JPEG thumbnail image
    Writes to .thumbs/<hash>.jpg so the daemon skips it in future scans.
    """
    import shutil
    webdav_path = request.form.get("path", "")
    if not webdav_path:
        return jsonify({"error": "Thiếu tham số 'path'"}), 400
    thumb_file = request.files.get("thumb")
    if thumb_file is None or not getattr(thumb_file, "filename", None):
        return jsonify({"error": "Thiếu file thumbnail"}), 400
    content_type = thumb_file.content_type or ""
    if not content_type.startswith("image/"):
        return jsonify({"error": "Chỉ chấp nhận file image"}), 415
    # CC-005 P1: cap 5MB + verify magic JPEG (SOI) — truoc day chi check
    # content_type (spoof duoc), khong cap size -> lap HDD/eMMC + OOM khi GET.
    try:
        thumb_file.stream.seek(0, 2)
        _tu_size = thumb_file.stream.tell()
        thumb_file.stream.seek(0)
    except Exception:
        _tu_size = 0
    if _tu_size <= 0 or _tu_size > 5 * 1024 * 1024:
        return jsonify({"error": "Thumbnail vuot gioi han 5MB"}), 413
    try:
        _tu_magic = thumb_file.stream.read(3)
        thumb_file.stream.seek(0)
        if _tu_magic[:2] != b"\xff\xd8":
            return jsonify({"error": "Thumbnail khong phai JPEG hop le"}), 415
    except Exception:
        return jsonify({"error": "Khong doc duoc thumbnail"}), 400
    # Reuse the same path resolution as /api/thumb GET.
    real_path = _resolve_webdav_request_path(webdav_path)
    if not real_path:
        return jsonify({"error": "Đường dẫn không hợp lệ"}), 400
    base_dir = get_webdav_root()
    thumb_path = _get_thumb_path(base_dir, real_path)
    thumb_dir = os.path.dirname(thumb_path)
    try:
        os.makedirs(thumb_dir, exist_ok=True)
    except Exception:
        pass
    try:
        def write_upload(staged):
            with open(staged, "wb") as out:
                shutil.copyfileobj(thumb_file, out, length=4 * 1024 * 1024)
            return os.path.getsize(staged) == _tu_size
        if not _publish_thumb(real_path, thumb_path, write_upload, request.form.get("source_version")):
            return jsonify({"error": "incomplete_thumbnail"}), 400
        return jsonify({"ok": True, "thumb_path": thumb_path})
    except (ValueError, FileNotFoundError):
        return jsonify({"error": "source_version_changed"}), 409
    except Exception as e:
        log.warning("[ThumbUpload] Lỗi ghi thumbnail: %s — %s", os.path.basename(real_path), e)
        return jsonify({"error": "Lỗi ghi file"}), 500


@app.route("/api/thumb/exists", methods=["POST"])
@requires_auth
def api_thumb_exists():
    """Check cached thumbnail outputs for indexed files without generating them."""
    body = request.get_json(force=True, silent=True) or {}
    if not isinstance(body, dict):
        return jsonify({"error": "Expected an object"}), 400
    paths = body.get("paths", [])
    if not isinstance(paths, list) or len(paths) > 512:
        return jsonify({"error": "paths must be a list of at most 512 items"}), 400
    root = _get_webdav_public_dir()
    if not root:
        return jsonify({"error": "Configured WebDAV root is unavailable"}), 503
    rel_paths = []
    for path in paths:
        if not isinstance(path, str) or not path.startswith("/webdav/"):
            return jsonify({"error": "Invalid WebDAV path"}), 400
        rel = path[8:]
        if not rel or "\\" in rel or any(part in ("", ".", "..") for part in rel.split("/")):
            return jsonify({"error": "Invalid WebDAV path"}), 400
        rel_paths.append(rel)
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        indexed = set(row[0] for row in conn.execute(
            "SELECT path FROM file_index WHERE is_dir=0 AND path IN (" +
            ",".join("?" for _ in rel_paths) + ")", tuple(rel_paths))) if rel_paths else set()
    finally:
        if conn is not None:
            conn.close()
    missing = []
    versions = {}
    root = os.path.realpath(root)
    for path, rel in zip(paths, rel_paths):
        if rel not in indexed:
            missing.append(path)
            continue
        full = os.path.join(root, *rel.split("/"))
        thumb = _get_thumb_path(root, full)
        if not _thumb_current(full, thumb, 1200):
            missing.append(path)
            try:
                versions[path] = _duplicate_stat_version(os.stat(full))
            except OSError:
                pass
    return jsonify({"missing": missing, "source_versions": versions})


@app.route("/api/thumb/status")
@requires_auth
def api_thumb_status():
    durable_jobs = nas_work_coordinator.get_status(INDEX_DB_PATH, "thumbnail")
    with _thumb_stats_lock:
        data = dict(_thumb_stats)
        st = data.get("start_time", 0)
        if data["running"] and st > 0:
            elapsed = int(time.time() - st)
            data["elapsed_seconds"] = elapsed
            base = data.get("_base_done", data["generated"])
            done = data["generated"] - base
            pending = data["total_media"] - data["generated"]

            if done > 0 and elapsed > 0:
                rate = done / elapsed
                data["eta_seconds"] = int(pending / rate) if rate > 0 else -1
            else:
                data["eta_seconds"] = -1
        else:
            data["elapsed_seconds"] = 0
            data["eta_seconds"] = -1

        data.pop("start_time", None)
        data.pop("_base_done", None)
        if durable_jobs:
            job = durable_jobs[0]
            data["work"] = {key: job[key] for key in (
                "state", "completed", "total", "updated_at", "error", "owner", "lease_expires_at")}

        # --- actual_status: tieng viet ro rang, phan biet scan_cursor/scan_total ---
        # Luu luon so lieu gan nhat (khong tra total_media=0, generated=0 khi paused).
        conn = None
        state_row = None
        scan_total = int(data.get("total_media") or 0)
        try:
            conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
            state_row = conn.execute(
                "SELECT root,ready,incomplete_reason,catalogue_revision,scanned_at,built FROM index_state WHERE id=1"
            ).fetchone()
            cnt = conn.execute("SELECT count(*) FROM file_index WHERE is_dir=0").fetchone()
            if cnt is not None:
                scan_total = int(cnt[0] or 0)
        except sqlite3.Error:
            pass
        finally:
            if conn is not None:
                conn.close()

        scan_cursor = ""

        if state_row:
            # state_row[5] = built (so nguyen), khong phai con trow file.
            # Con trow quet thuc su lay tu _thumb_stats last_file (file cuoi cung da xu ly).
            scan_cursor = data.get("last_file", "") or ""

        # Xac dinh trang thai gate/thuc te.
        paused = bool(data.get("paused"))
        block_reasons = sorted(data.get("block_reasons", []))
        running = bool(data.get("running"))
        job_state = ""
        if durable_jobs:
            job_state = durable_jobs[0].get("state", "")

        if not state_row or not state_row[1]:
            actual_status = "index_not_ready"
        elif paused and block_reasons:
            actual_status = "paused:" + ",".join(block_reasons)
        elif paused:
            actual_status = "paused"
        elif running and job_state == "running":
            actual_status = "scanning"
        elif job_state == "complete":
            actual_status = "idle"
        else:
            actual_status = "idle"

        data["actual_status"] = actual_status
        data["scan_cursor"] = scan_cursor
        data["scan_total"] = scan_total

        # Thong tin thuc te: so thumbnail thuc co tren dia (khong lay tu con tro quet).
        # work.completed/con tro quet la so file da duyet qua, KHONG PHAI so thumbnail da tao.
        work_job = durable_jobs[0] if durable_jobs else {}
        actual_generated = int(data.get("generated", 0) or 0)
        actual_total = int(data.get("total_media", 0) or 0)
        if actual_total == 0:
            actual_total = int(work_job.get("total", 0) or 0)
        actual_missing = actual_total - actual_generated
        if actual_missing < 0:
            actual_missing = 0
        data["actual_generated"] = actual_generated
        data["actual_total"] = actual_total
        data["actual_missing"] = actual_missing

        # Format thoi gian thah "hh:mm"
        el = data.get("elapsed_seconds", 0)
        data["elapsed_fmt"] = "%02d:%02d" % (el // 3600, (el % 3600) // 60)
        et = data.get("eta_seconds", -1)
        data["eta_fmt"] = "%02d:%02d" % (et // 3600, (et % 3600) // 60) if et > 0 else "--:--"

        return jsonify(data)

@app.route("/api/work/status")
@requires_auth
def api_nas_work_status():
    """Durable NAS maintenance state stored beside the canonical file index."""
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        state = conn.execute("SELECT root,generation,ready,catalogue_revision,scanned_at FROM index_state WHERE id=1").fetchone()
    finally:
        if conn is not None:
            conn.close()
    root = _get_webdav_public_dir()
    index_ready = bool(state and root and state[0] == os.path.realpath(root) and state[2] and not _index_scan_lock.locked())
    jobs = []
    for job in nas_work_coordinator.get_status(INDEX_DB_PATH):
        jobs.append({key: job[key] for key in (
            "name", "state", "completed", "total", "revision", "attempt", "updated_at",
            "error", "owner", "lease_expires_at")})
    return jsonify({"source": "file_index", "index_ready": index_ready,
                    "index_generation": state[1] if state else 0,
                    "catalogue_revision": state[3] if state else 0,
                    "catalogue_updated_at": state[4] if state else 0, "jobs": jobs})


@app.route("/api/work/request", methods=["POST"])
@requires_auth
def api_nas_work_request():
    body = request.get_json(force=True, silent=True) or {}
    if not isinstance(body, dict):
        return jsonify({"error": "Expected an object"}), 400
    name = str(body.get("name", "")).strip().lower()
    if name not in ("thumbnail", "duplicates", "fingerprint", "hash"):
        return jsonify({"error": "Unsupported NAS maintenance job"}), 400
    root = _get_webdav_public_dir()
    if not root:
        return jsonify({"error": "Configured WebDAV root is unavailable"}), 503
    priority = {"thumbnail": 20, "duplicates": 10, "fingerprint": 5, "hash": 5}[name]
    try:
        status = nas_work_coordinator.request_job(
            INDEX_DB_PATH, name, os.path.realpath(root), priority=priority)
    except ValueError as error:
        return jsonify({"error": str(error)}), 409
    return jsonify({"source": "file_index", "job": status[0] if status else None})


@app.route("/api/work/claim", methods=["POST"])
@requires_auth
def api_nas_work_claim():
    body = request.get_json(force=True, silent=True) or {}
    if not isinstance(body, dict):
        return jsonify({"error": "Expected an object"}), 400
    name = str(body.get("name", "")).strip().lower()
    owner = str(body.get("owner", "")).strip()
    if name not in ("thumbnail", "duplicates", "fingerprint", "hash"):
        return jsonify({"error": "Unsupported NAS maintenance job"}), 400
    if not owner or len(owner) > 112 or _re_module.search(r"[^A-Za-z0-9_.:-]", owner):
        return jsonify({"error": "Invalid worker identity"}), 400
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        state = conn.execute("SELECT root,ready,scanned_at,catalogue_revision FROM index_state WHERE id=1").fetchone()
    finally:
        if conn is not None:
            conn.close()
    root = _get_webdav_public_dir()
    stale = bool(state and state[1] and time.time() - float(state[2] or 0) > FILE_INDEX_FRESHNESS_SECONDS)
    if not state or not root or state[0] != os.path.realpath(root) or not state[1] or stale or _index_scan_lock.locked():
        _refresh_file_index_for_search()
        reason = "catalogue_stale" if stale else "catalogue_not_ready"
        return jsonify({"claimed": False, "retry_after": 30, "reason": reason}), 423
    lease = nas_work_coordinator.claim_next(
        INDEX_DB_PATH, "client:" + owner, _background_heavy_work_allowed(),
        lease_seconds=300, job_name=name)
    if not lease:
        return jsonify({"claimed": False, "retry_after": 30, "reason": "nas_busy_or_job_unavailable"}), 423
    return jsonify({key: lease[key] for key in ("name", "cursor", "completed", "total", "revision", "catalogue_revision", "attempt", "owner", "token", "expires_at")})


@app.route("/api/work/checkpoint", methods=["POST"])
@requires_auth
def api_nas_work_checkpoint():
    body = request.get_json(force=True, silent=True) or {}
    if not isinstance(body, dict):
        return jsonify({"error": "Expected an object"}), 400
    name = str(body.get("name", "")).strip().lower()
    owner = str(body.get("owner", "")).strip()
    token = str(body.get("token", "")).strip()
    if name not in ("thumbnail", "duplicates", "fingerprint", "hash"):
        return jsonify({"error": "Unsupported NAS maintenance job"}), 400
    if not owner or len(owner) > 128 or _re_module.search(r"[^A-Za-z0-9_.:-]", owner) or not token or len(token) > 128:
        return jsonify({"error": "Invalid worker lease"}), 400
    if not nas_work_coordinator.owns_lease(INDEX_DB_PATH, name, owner, token):
        return jsonify({"error": "Worker lease expired or is no longer owned"}), 409
    progress_state = body.get("state", "running")
    if progress_state == "running" and not _background_heavy_work_allowed():
        try:
            accepted = nas_work_coordinator.checkpoint(
                INDEX_DB_PATH, name, owner, token, body.get("cursor", ""),
                body.get("completed", -1), body.get("total", -1),
                state="paused", error="nas_busy")
        except ValueError as error:
            return jsonify({"error": str(error)}), 400
        if not accepted:
            return jsonify({"error": "Worker lease expired or is no longer owned"}), 409
        return jsonify({"error": "NAS maintenance paused because NAS is busy", "reason": "nas_busy"}), 423
    try:
        accepted = nas_work_coordinator.checkpoint(
            INDEX_DB_PATH, name, owner, token, body.get("cursor", ""),
            body.get("completed", -1), body.get("total", -1),
            state=progress_state, error=str(body.get("error", "")))
    except ValueError as error:
        return jsonify({"error": str(error)}), 400
    if not accepted:
        return jsonify({"error": "Worker lease expired or is no longer owned"}), 409
    return jsonify({"accepted": True})


@app.route("/api/work/files", methods=["POST"])
@requires_auth
def api_nas_work_files():
    """Return a bounded catalogue page only to the current maintenance lease owner."""
    body = request.get_json(force=True, silent=True) or {}
    if not isinstance(body, dict):
        return jsonify({"error": "Expected an object"}), 400
    name = str(body.get("name", "")).strip().lower()
    owner = str(body.get("owner", "")).strip()
    token = str(body.get("token", "")).strip()
    cursor = body.get("cursor", "")
    limit = body.get("limit", 256)
    if name not in ("thumbnail", "duplicates", "fingerprint", "hash"):
        return jsonify({"error": "Unsupported NAS maintenance job"}), 400
    if not nas_work_coordinator.owns_lease(INDEX_DB_PATH, name, owner, token):
        return jsonify({"error": "Worker lease expired or is no longer owned"}), 409
    if not isinstance(cursor, str) or len(cursor) > 2048 or type(limit) is not int or limit < 1 or limit > 512:
        return jsonify({"error": "Invalid page cursor or limit"}), 400
    if cursor and (os.path.isabs(cursor) or "\\" in cursor or
                   any(part in ("", ".", "..") for part in cursor.split("/"))):
        return jsonify({"error": "Invalid page cursor"}), 400
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        conn.execute("BEGIN")
        state = conn.execute("SELECT root,generation,ready,catalogue_revision,scanned_at FROM index_state WHERE id=1").fetchone()
        root = _get_webdav_public_dir()
        if (not state or not root or state[0] != os.path.realpath(root) or not state[2] or
                time.time() - float(state[4] or 0) > FILE_INDEX_FRESHNESS_SECONDS or _index_scan_lock.locked()):
            _refresh_file_index_for_search()
            return jsonify({"error": "catalogue_not_ready", "retry_after": 30}), 409
        expected_revision = body.get("catalogue_revision")
        if expected_revision is not None and expected_revision != state[3]:
            return jsonify({"error": "catalogue_changed", "catalogue_revision": state[3]}), 409
        predicate = ""
        params = []
        if name == "thumbnail":
            predicate = " AND (" + " OR ".join(["lower(name) LIKE ?"] * len(MEDIA_ALL_EXTS)) + ")"
            params.extend("%%%s" % ext for ext in sorted(MEDIA_ALL_EXTS))
        elif name == "duplicates":
            predicate = (" AND EXISTS (SELECT 1 FROM file_index AS other WHERE other.is_dir=0"
                         " AND other.size=file_index.size AND other.path<>file_index.path)")
        total = None
        if body.get("include_total") is not False:
            total = conn.execute("SELECT COUNT(*) FROM file_index WHERE is_dir=0" + predicate, tuple(params)).fetchone()[0]
        rows = conn.execute(
            "SELECT path,name,size,mtime,mime FROM file_index WHERE is_dir=0 AND path>?" + predicate +
            " ORDER BY path LIMIT ?", tuple([cursor] + params + [limit])).fetchall()
        files = [{"path": "/webdav/" + row[0], "name": row[1], "size": row[2],
                  "mtime": row[3], "mime": row[4]} for row in rows]
        return jsonify({"source": "file_index", "catalogue_revision": state[3],
                        "index_generation": state[1], "total": total, "files": files,
                        "next_cursor": rows[-1][0] if rows else cursor,
                        "eof": len(rows) < limit})
    finally:
        if conn is not None:
            conn.close()


# ============================================================================
# FINGERPRINT (Moc 1) — Chu ky anh aHash chung cho tat ca client.
# Android FingerprintWorker (Room) khong con tinh toan aHash lap lai tung may.
# Thay do: client goi /api/fingerprint/pending de lay danh sach anh chua co aHash,
# tinh aHash tren may client (tu first 512KB qua WebDAV Range), POST ve
# /api/fingerprint/save. NAS luu vao bang image_fingerprints trong nas_index.db.
# Client khac (Web, PC worker, Android khac) goi /api/fingerprint/lookup de tai
# aHash da co thay tinh toan lai.
# ============================================================================


def _fingerprint_db():
    """Mo shared index DB va co ban table image_fingerprints (da duoc tao trong _init_db)."""
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_10", 10.0))
    try:
        conn.execute("""CREATE TABLE IF NOT EXISTS image_fingerprints (
            path TEXT PRIMARY KEY,
            source_version TEXT NOT NULL DEFAULT '',
            ahash TEXT NOT NULL DEFAULT '',
            size INTEGER NOT NULL DEFAULT 0,
            mtime INTEGER NOT NULL DEFAULT 0,
            updated_at REAL NOT NULL DEFAULT 0
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_image_fps_ahash ON image_fingerprints(ahash)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_image_fps_version ON image_fingerprints(source_version)")
        conn.commit()
    except Exception:
        conn.rollback()
        log.debug("[Fingerprint] image_fingerprints table deferred", exc_info=True)
    return conn


def _image_fingerprint_is_stale(path, size, mtime):
    """Tra ve True neu file da co aHash nhung kich thuoc/mtime da doi (can tinh lai)."""
    conn = _fingerprint_db()
    try:
        row = conn.execute("SELECT size,mtime FROM image_fingerprints WHERE path=?", (path,)).fetchone()
    finally:
        if conn is not None:
            conn.close()
    if not row:
        return True  # chua co -> can tinh
    return int(row[0]) != int(size or 0) or int(row[1]) != int(mtime or 0)


@app.route("/api/fingerprint/pending", methods=["GET"])
@requires_auth
def api_fingerprint_pending():
    """Tra ve danh sach anh chua co aHash (hoac aHash bi stale) de client tinh toan.
    Query: ?limit= (mac dinh 512, toi da 2048) & ?source_version= (phan biet phien ban client)."""
    try:
        limit = int(request.args.get("limit", "512") or 512)
    except Exception:
        limit = 512
    if limit < 1 or limit > 2048:
        limit = 512
    source_version = (request.args.get("source_version") or "").strip()[:64]
    root = _get_webdav_public_dir()
    if not root:
        return jsonify({"error": "WebDAV root unavailable"}), 503
    root_real = os.path.realpath(root)
    conn = sqlite3.connect(INDEX_DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_5", 5.0))
    try:
        conn.execute("BEGIN")
        state = conn.execute("SELECT root,generation,ready,catalogue_revision,scanned_at FROM index_state WHERE id=1").fetchone()
        if (not state or not root or state[0] != root_real or not state[2] or
                time.time() - float(state[4] or 0) > FILE_INDEX_FRESHNESS_SECONDS or _index_scan_lock.locked()):
            _refresh_file_index_for_search()
            return jsonify({"error": "catalogue_not_ready", "retry_after": 30}), 409
        # Lay anh co du lieu trong file_index, bo qua nhung da co aHash hop le
        # (khong stale). JOIN voi image_fingerprints bo loc nhanh.
        rows = conn.execute("""
            SELECT f.path, f.name, f.size, f.mtime
            FROM file_index f
            LEFT JOIN image_fingerprints fp ON fp.path = f.path
            WHERE f.is_dir=0
              AND (fp.path IS NULL OR fp.size<>f.size OR fp.mtime<>f.mtime OR fp.ahash='' OR fp.ahash IS NULL)
              AND lower(f.mime) LIKE 'image/%'
            ORDER BY f.path
            LIMIT ?
        """, (limit,)).fetchall()
        files = [{"path": "/webdav/" + row[0], "name": row[1], "size": row[2], "mtime": row[3]} for row in rows]
        return jsonify({"source": "file_index", "catalogue_revision": state[3],
                        "source_version": source_version, "total": len(files), "files": files})
    finally:
        if conn is not None:
            conn.close()


@app.route("/api/fingerprint/save", methods=["POST"])
@requires_auth
def api_fingerprint_save():
    """Nhan batch aHash tu client. Body: {"items": [{"path": "/webdav/a.jpg",
    "source_version": "android-1.2", "ahash": "abcd1234...", "size": 123, "mtime": 456}], toi da 500/lan."""
    body = request.get_json(force=True, silent=True) or {}
    items = body.get("items", [])
    if not isinstance(items, list) or len(items) > 500:
        return jsonify({"error": "items phai la list <= 500"}), 400
    source_version = str(body.get("source_version") or "").strip()[:64]
    now = time.time()
    saved = 0
    conn = _fingerprint_db()
    try:
        import re as _re_hash
        for it in items:
            try:
                p = str(it.get("path", "") or "")
                if not p or ".." in p or "\\" in p:
                    continue
                # Chuan hoa path: loai bo /webdav/ prefix neu co
                if p.startswith("/webdav/"):
                    p = p[len("/webdav/"):]
                real = _resolve_webdav_request_path(p)
                if not real:
                    continue
                ahash = str(it.get("ahash", "") or "").lower()
                if not _re_hash.match(r"^[0-9a-f]{1,64}$", ahash or ""):
                    continue
                try:
                    sz = int(it.get("size", 0) or 0)
                    mt = int(it.get("mtime", 0) or 0)
                except Exception:
                    continue
                if sz < 0 or mt < 0:
                    continue
                sv = str(it.get("source_version") or source_version or "unknown")[:64]
                conn.execute(
                    "INSERT OR REPLACE INTO image_fingerprints(path,source_version,ahash,size,mtime,updated_at) "
                    "VALUES(?,?,?,?,?,?)",
                    (p, sv, ahash, sz, mt, now))
                saved += 1
            except Exception:
                continue
        conn.commit()
    except Exception as e:
        conn.rollback()
        log.error("[Fingerprint] save error: %s", e)
        return jsonify({"error": str(e)}), 500
    finally:
        if conn is not None:
            conn.close()
    return jsonify({"result": "ok", "saved": saved})


@app.route("/api/fingerprint/lookup", methods=["POST"])
@requires_auth
def api_fingerprint_lookup():
    """Tra cuu aHash cho mot danh sach path. Body: {"paths": ["/webdav/a.jpg", ...]}.
    Tra ve {"results": {"/webdav/a.jpg": {"ahash": "...", "source_version": "..."}}."""
    body = request.get_json(force=True, silent=True) or {}
    paths = body.get("paths", [])
    if not isinstance(paths, list) or len(paths) > 500:
        return jsonify({"error": "paths phai la list <= 500"}), 400
    conn = _fingerprint_db()
    try:
        results = {}
        for p in paths:
            try:
                if not isinstance(p, str) or ".." in p or "\\" in p:
                    continue
                if p.startswith("/webdav/"):
                    p = p[len("/webdav/"):]
                row = conn.execute("SELECT ahash,source_version,size,mtime FROM image_fingerprints WHERE path=?", (p,)).fetchone()
                if row and row[0]:
                    results["/webdav/" + p] = {"ahash": row[0], "source_version": row[1] or "",
                                               "size": row[2], "mtime": row[3]}
            except Exception:
                continue
    finally:
        if conn is not None:
            conn.close()
    return jsonify({"result": "ok", "results": results})


@app.route("/api/fingerprint/compute", methods=["POST"])
@requires_auth
def api_fingerprint_compute():
    """Tu dong tinh aHash bang Rust tren NAS cho mot danh sach path hoac quet batch anh chua co hash.
    Body JSON hoac Query params:
      - paths: list duong dan cu the can tinh (toi da 500)
      - limit: so luong anh chua co hash can tinh tu dong tu file_index (mac dinh 100, toi da 500)
    """
    body = request.get_json(force=True, silent=True) or {}
    paths = body.get("paths")
    try:
        limit = int(body.get("limit") or request.args.get("limit") or 100)
    except Exception:
        limit = 100
    limit = max(1, min(500, limit))

    root = _get_webdav_public_dir()
    if not root:
        return jsonify({"error": "WebDAV root unavailable"}), 503
    root_real = os.path.realpath(root)

    candidates = []
    conn = _fingerprint_db()
    try:
        if isinstance(paths, list) and paths:
            for p in paths[:500]:
                if not isinstance(p, str) or ".." in p or "\\" in p:
                    continue
                clean_p = p[len("/webdav/"):] if p.startswith("/webdav/") else p
                full = _resolve_webdav_request_path(clean_p)
                if full and os.path.isfile(full):
                    try:
                        st = os.stat(full)
                        candidates.append((clean_p, full, st.st_size, int(st.st_mtime * 1000)))
                    except Exception:
                        pass
        else:
            rows = conn.execute(
                "SELECT f.path, f.size, f.mtime FROM file_index f "
                "LEFT JOIN image_fingerprints fp ON fp.path = f.path "
                "WHERE f.is_dir=0 "
                "AND (fp.path IS NULL OR fp.size<>f.size OR fp.mtime<>f.mtime OR fp.ahash='' OR fp.ahash IS NULL) "
                "AND lower(f.mime) LIKE 'image/%' "
                "ORDER BY f.path LIMIT ?", (limit,)).fetchall()
            for rel_p, sz, mt in rows:
                full = os.path.join(root_real, *rel_p.split("/"))
                if os.path.isfile(full):
                    candidates.append((rel_p, full, sz, mt))

        if not candidates:
            return jsonify({"result": "ok", "computed": 0, "remaining_pending": 0, "results": {}})

        full_map = {}
        for rel_p, full, sz, mt in candidates:
            full_map[full] = (rel_p, sz, mt)

        bridge = globals().get("rust_bridge")
        batch_hashes = {}
        if bridge and hasattr(bridge, "compute_batch_ahash"):
            batch_hashes = bridge.compute_batch_ahash(list(full_map.keys()), workers=4)
        else:
            for full in full_map:
                if bridge and hasattr(bridge, "compute_image_ahash"):
                    batch_hashes[full] = bridge.compute_image_ahash(full)

        now = time.time()
        saved = 0
        results = {}
        for full, h in batch_hashes.items():
            if not h or len(h) != 16:
                continue
            meta = full_map.get(full)
            if not meta:
                continue
            rel_p, sz, mt = meta
            conn.execute(
                "INSERT OR REPLACE INTO image_fingerprints(path,source_version,ahash,size,mtime,updated_at) "
                "VALUES(?,?,?,?,?,?)",
                (rel_p, "nas-rust-1.0", h, sz, mt, now))
            saved += 1
            results["/webdav/" + rel_p] = h
        conn.commit()

        pending_count = conn.execute(
            "SELECT COUNT(*) FROM file_index f "
            "LEFT JOIN image_fingerprints fp ON fp.path = f.path "
            "WHERE f.is_dir=0 "
            "AND (fp.path IS NULL OR fp.size<>f.size OR fp.mtime<>f.mtime OR fp.ahash='' OR fp.ahash IS NULL) "
            "AND lower(f.mime) LIKE 'image/%'").fetchone()[0]

        return jsonify({
            "result": "ok",
            "computed": saved,
            "remaining_pending": pending_count,
            "results": results
        })
    except Exception as e:
        if conn is not None:
            conn.rollback()
        log.error("[Fingerprint] compute error: %s", e)
        return jsonify({"error": str(e)}), 500
    finally:
        if conn is not None:
            conn.close()


@app.route("/api/fingerprint/similar", methods=["GET"])
@requires_auth
def api_fingerprint_similar():
    """Tim cac anh tuong dong voi anh chi dinh dua vao khoang cach Hamming (Hamming distance <= threshold).
    Query params:
      - path: duong dan anh can so sanh (vi du: /webdav/DCIM/photo.jpg)
      - threshold: nguong khac biet bit (mac dinh 5, toi da 15, cang nho cang giong)
      - limit: so ket qua tra ve toi da (mac dinh 50, toi da 200)
    """
    raw_p = (request.args.get("path") or "").strip()
    if not raw_p or ".." in raw_p or "\\" in raw_p:
        return jsonify({"error": "Invalid path"}), 400
    clean_p = raw_p[len("/webdav/"):] if raw_p.startswith("/webdav/") else raw_p

    try:
        threshold = int(request.args.get("threshold", "5") or 5)
    except Exception:
        threshold = 5
    threshold = max(0, min(20, threshold))

    try:
        limit = int(request.args.get("limit", "50") or 50)
    except Exception:
        limit = 50
    limit = max(1, min(200, limit))

    conn = _fingerprint_db()
    try:
        row = conn.execute("SELECT ahash FROM image_fingerprints WHERE path=?", (clean_p,)).fetchone()
        target_hash = row[0] if row else None
        if not target_hash:
            full = _resolve_webdav_request_path(clean_p)
            bridge = globals().get("rust_bridge")
            if full and os.path.isfile(full) and bridge and hasattr(bridge, "compute_image_ahash"):
                target_hash = bridge.compute_image_ahash(full)
                if target_hash:
                    st = os.stat(full)
                    conn.execute(
                        "INSERT OR REPLACE INTO image_fingerprints(path,source_version,ahash,size,mtime,updated_at) VALUES(?,?,?,?,?,?)",
                        (clean_p, "nas-rust-1.0", target_hash, st.st_size, int(st.st_mtime * 1000), time.time()))
                    conn.commit()

        if not target_hash or len(target_hash) != 16:
            return jsonify({"error": "Target image ahash unavailable"}), 404

        target_int = int(target_hash, 16)
        rows = conn.execute("SELECT path, ahash, size, mtime FROM image_fingerprints WHERE path<>? AND ahash<>''", (clean_p,)).fetchall()
        matches = []
        for p, h, sz, mt in rows:
            if len(h) != 16:
                continue
            try:
                diff = bin(target_int ^ int(h, 16)).count("1")
                if diff <= threshold:
                    matches.append({
                        "path": "/webdav/" + p,
                        "ahash": h,
                        "hamming_distance": diff,
                        "size": sz,
                        "mtime": mt
                    })
            except Exception:
                continue

        matches.sort(key=lambda x: x["hamming_distance"])
        return jsonify({
            "target": "/webdav/" + clean_p,
            "target_ahash": target_hash,
            "threshold": threshold,
            "total_matches": len(matches),
            "matches": matches[:limit]
        })
    finally:
        if conn is not None:
            conn.close()


@app.route("/api/process_state", methods=["GET"])
@requires_auth
def api_process_state():
    """Return persisted progress/cursors for background workers."""
    data = _load_process_state()
    data["_meta"] = {
        "file": _PROCESS_STATE_FILE,
        "schema": 1,
        "namespaces": sorted([k for k in data.keys() if not str(k).startswith("_")]),
        "updated_at": int(time.time()),
    }
    return jsonify(data)

@app.route("/api/thumb/control", methods=["POST"])
@requires_auth
def api_thumb_control():
    """Dieu khien Thumbnail Generator: pause / resume / scan.
    Body: {"action": "pause"|"resume"|"scan"} — scan = yeu cau quet 1 vong ngay
    (daemon van ton trong gate: pause/user-active/livestream/RAM/load)."""
    data = request.get_json(force=True) or {}
    action = data.get("action", "").strip().lower()

    global _thumb_manual_paused, _thumb_scan_requested
    if action == "pause":
        with _thumb_gate_lock:
            _thumb_manual_paused = True
            _apply_thumbnail_gate_locked()
        return jsonify({"result": "ok", "paused": True, "block_reasons": sorted(_thumb_auto_block_reasons)})
    elif action == "resume":
        with _thumb_gate_lock:
            _thumb_manual_paused = False
            _apply_thumbnail_gate_locked()
            paused = not _thumb_paused.is_set()
        return jsonify({"result": "ok", "paused": paused, "block_reasons": sorted(_thumb_auto_block_reasons)})
    elif action == "scan":
        root = _get_webdav_public_dir()
        if not root:
            return jsonify({"error": "Không tìm thấy thư mục WebDAV đã cấu hình"}), 503
        nas_work_coordinator.request_job(INDEX_DB_PATH, "thumbnail", os.path.realpath(root), priority=30)
        with _thumb_gate_lock:
            _thumb_scan_requested = True
        return jsonify({"result": "ok", "scan_requested": True})
    else:
        return jsonify({"error": "Hành động phải là 'pause', 'resume' hoặc 'scan'"}), 400

@app.route("/api/thumb/activity", methods=["POST"])
@requires_auth
def api_thumb_activity():
    """App/worker bao cho NAS biet tac vu n?ng dang chay de t?m dùng thumbnail.
    Body: {"source": "sync", "active": true|false}"""
    body = request.get_json(force=True, silent=True) or {}
    source = _re_module.sub(r"[^a-zA-Z0-9_.-]+", "_", str(body.get("source", "sync"))).strip("_") or "sync"
    active = bool(body.get("active", False))
    if len(source) > 48:
        source = source[:48]
    # CC-005 P1: whitelist source + TTL 180s (truoc day bat ky string nao cung
    # block + TTL 600s — app chet giua chung giu NAS pause thumb lau)
    if source not in ("sync", "backup", "upload", "social", "livestream", "scan"):
        source = "sync"
    _set_thumbnail_auto_block(source, active, ttl_seconds=180)
    return jsonify({
        "result": "ok",
        "paused": not _thumb_paused.is_set(),
        "block_reasons": sorted(_thumb_auto_block_reasons)
    })

# ============ DOCKER POWER CONTROL ============

@app.route("/api/docker/power", methods=["GET"])
@requires_auth
def api_docker_power_get():
    """Kiểm tra Docker dang chay hay khong."""
    try:
        r = subprocess.run(["systemctl", "is-active", "docker"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        running = r.stdout.decode().strip() == "active"
        return jsonify({"running": running, "effective_running": running})
    except Exception as e:
        return jsonify({"running": False, "error": str(e)})

@app.route("/api/docker/power", methods=["POST"])
@requires_admin
def api_docker_power_post():
    """Bat/tat Docker service. Body: {"action": "start"} hoac {"action": "stop"}"""
    data = request.json or {}
    action = data.get("action", "").strip().lower()
    
    if action == "start":
        subprocess.run(["systemctl", "start", "containerd"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        subprocess.run(["systemctl", "start", "docker"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        time.sleep(2)
        # Tu dong start tất c? container da co (an toan: 2 buoc thay vi shell expansion)
        _all_ids = run_cmd(["docker", "ps", "-aq"])
        if _all_ids:
            subprocess.run(["docker", "start"] + _all_ids.split(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        try:
            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
            conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                ("INFO", "Docker", "Đã bật Docker + qBittorrent"))
            conn.commit()
            conn.close()
        except Exception as e: log.debug("[M4] Ignored exception: %s", e)
        r = subprocess.run(["systemctl", "is-active", "docker"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        running = r.stdout.decode().strip() == "active"
        return jsonify({"result": "ok", "action": "started", "running": running, "effective_running": running})
    elif action == "stop":
        _running_ids = run_cmd(["docker", "ps", "-q"])
        if _running_ids:
            subprocess.run(["docker", "stop"] + _running_ids.split(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        subprocess.run(["systemctl", "stop", "docker"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        subprocess.run(["systemctl", "stop", "containerd"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        try:
            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
            conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                ("INFO", "Docker", "Đã tắt Docker để tiết kiệm RAM"))
            conn.commit()
            conn.close()
        except Exception as e: log.debug("[M4] Ignored exception: %s", e)
        r = subprocess.run(["systemctl", "is-active", "docker"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        running = r.stdout.decode().strip() == "active"
        return jsonify({"result": "ok", "action": "stopped", "running": running, "effective_running": running})
    else:
        return jsonify({"error": "Hành động phải là 'start' hoặc 'stop'"}), 400


# ============ LAN WHITELIST API ============

@app.route("/api/lan/whitelist", methods=["GET"])
@requires_auth
def api_lan_whitelist_get():
    """L?y danh sách IP/subnet trong LAN whitelist."""
    return jsonify({
        "ips": sorted(list(_lan_whitelist)),
        "subnets": sorted(_lan_subnets)
    })

@app.route("/api/lan/whitelist", methods=["POST"])
@requires_admin
def api_lan_whitelist_add():
    """Th?m IP hoac subnet vao LAN whitelist + tu dong mo iptables.
    Body: {"ip": "192.168.1.100"} hoac {"subnet": "192.168.1.0/24"}"""
    data = request.json or {}
    ip = data.get("ip", "").strip()
    subnet = data.get("subnet", "").strip()

    # FIX-AUDIT-BUG2: validate IP/CIDR truoc khi persist + ap iptables.
    # Ban cu nhan gia tri thoa mai tu JSON -> ghi rác vao _lan_whitelist /
    # _lan_subnets va ap iptables rule sai dinh dang. Dung validator co san
    # (_RE_SAFE_IP / _RE_SAFE_CIDR) de reject input khong hop le.
    if subnet:
        if not _validate_cidr(subnet):
            return jsonify({"error": "Subnet không hợp lệ (mong đợi CIDR, vd 192.168.1.0/24)"}), 400

        if subnet not in _lan_subnets:
            _lan_subnets.append(subnet)
            _save_lan_whitelist()
        # ?p dùng iptables ACCEPT ngay lap tuc cho subnet
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', subnet, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', subnet, '-j', 'ACCEPT'])
        except Exception as e: log.debug("[M4] Ignored exception: %s", e)
        return jsonify({"result": "ok", "added_subnet": subnet})
    elif ip:
        if not _validate_ip(ip):
            return jsonify({"error": "IP không hợp lệ (mong đợi IPv4, vd 192.168.1.100)"}), 400
        _lan_whitelist.add(ip)
        _save_lan_whitelist()
        # ?p dùng iptables ACCEPT ngay lap tuc cho IP
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', ip, '-j', 'ACCEPT'])
        except Exception as e: log.debug("[M4] Ignored exception: %s", e)
        return jsonify({"result": "ok", "added_ip": ip})
    else:
        return jsonify({"error": "Thiếu IP hoặc subnet"}), 400

@app.route("/api/lan/whitelist", methods=["DELETE"])
@requires_admin
def api_lan_whitelist_remove():
    """Xo? IP hoac subnet khoi LAN whitelist + go iptables rule tuong ung.
    Body: {"ip": "..."} hoac {"subnet": "..."}"""
    data = request.json or {}
    ip = data.get("ip", "").strip()
    subnet = data.get("subnet", "").strip()
    
    if subnet and subnet in _lan_subnets:
        _lan_subnets.remove(subnet)
        _save_lan_whitelist()
        # Go iptables rule cua subnet
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', subnet, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
        except Exception as e: log.debug("[M4] Ignored exception: %s", e)
        return jsonify({"result": "ok", "removed_subnet": subnet})
    elif ip and ip in _lan_whitelist:
        _lan_whitelist.discard(ip)
        _save_lan_whitelist()
        # Go iptables rule cua IP
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
        except Exception as e: log.debug("[M4] Ignored exception: %s", e)
        return jsonify({"result": "ok", "removed_ip": ip})
    else:
        return jsonify({"error": "IP/subnet không tồn tại trong whitelist"}), 404

# ============ TAILSCALE WATCHDOG ============
_tailscale_restart_count = 0
_tailscale_last_restart = None

def _system_health_watchdog():
    """Daemon thread: Giám sát toàn bộ Sức khỏe Hệ Thống (Tailscale, Nginx, HDD, LAN IP).
    Tự động restart service hoặc Reboot NAS nếú mất mạng/mất ổ cứng."""
    global _tailscale_restart_count, _tailscale_last_restart
    time.sleep(30)  # Chờ NAS khởi động xong hoàn toàn (30s)
    
    # File chống boot-loop: Cấm reboot liên tục dưới 15 phút
    REBOOT_LOCK_FILE = "/etc/nas/last_watchdog_reboot"
    
    def _trigger_hard_reboot(reason):
        try:
            if os.path.exists(REBOOT_LOCK_FILE):
                last_time = os.path.getmtime(REBOOT_LOCK_FILE)
                if time.time() - last_time < 900:  # 15 phút
                    log.warning("[Watchdog] Phát hiện %s, nhưng đã khóa reboot vì NAS vừa reboot gần đây.", reason)
                    return
            
            log.critical("[Watchdog] Thực thi reboot NAS: %s", reason)
            # Ghi log DB truoc khi ngat
            try:
                conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
                conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                    ("CRITICAL", "Hardware", "Tự động reboot NAS do: %s" % reason))
                conn.commit()
                conn.close()
            except Exception as e: log.debug("[M4] Ignored exception: %s", e)
            
            # Tao file lock de chong bootloop
            os.makedirs(os.path.dirname(REBOOT_LOCK_FILE), exist_ok=True)
            with open(REBOOT_LOCK_FILE, "w") as f:
                f.write(str(time.time()))
                
            subprocess.run(["reboot"])
        except Exception as e:
            log.error("[Watchdog] Reboot thất bại: %s", e)
            
    # Biến đếm thời gian lỗi (Cần lỗi liên tục 2 lần mới Action để tránh chập chờn)
    hdd_error_cycles = 0
    lan_error_cycles = 0

    # Không ép `hdparm -S 0` lúc khởi động. Lệnh đó tắt standby HDD và đi ngược
    # mục tiêu bảo vệ ổ trên NAS gia đình. Spindown/standby chỉ do sleep schedule
    # hoặc cấu hình hệ thống quyết định.

    while True:
        try:
            # 1. Kiểm tra tailscaled process
            result = subprocess.run(["pgrep", "-x", "tailscaled"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            if result.returncode != 0:
                log.warning("[Watchdog] tailscaled đã tắt, đang khởi động lại...")
                restart_result = subprocess.run(["systemctl", "restart", "tailscaled"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                _tailscale_restart_count += 1
                _tailscale_last_restart = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                if restart_result.returncode == 0:
                    time.sleep(3)
                    subprocess.run(["tailscale", "up"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
                    try:
                        conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
                        conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                            ("WARNING", "Network", "Tailscale bị ngắt, hệ thống RK3328 đã tự động khởi động lại lần %d" % _tailscale_restart_count))
                        conn.commit()
                        conn.close()
                    except Exception as e: log.debug("[M4] Ignored exception: %s", e)
            
            # 2. Kiểm tra nginx process (Dam bao WebDAV an toan, khong bi OMV chet tren boot)
            nginx_res = subprocess.run(["systemctl", "is-active", "nginx"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            if nginx_res.stdout.decode().strip() != "active":
                log.warning("[Watchdog] Nginx (WebDAV) đã tắt hoặc lỗi, đang khởi động lại...")
                subprocess.run(["systemctl", "start", "nginx"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                
            global _system_alert
            # 3. Kiểm tra HDD (Mount point co ban)
            hdd_path = "/srv/dev-disk-by-label-data"
            if not os.path.exists(hdd_path) or not os.path.ismount(hdd_path):
                hdd_error_cycles += 1
                log.warning("[Watchdog] Không tìm thấy ổ cứng hoặc ổ cứng chưa mount (lần %d)", hdd_error_cycles)
                _system_alert = "⚠️ Lỗi Ổ cứng (Tự động Reboot sau %ds)" % ((6 - hdd_error_cycles) * 10)
                if hdd_error_cycles >= 6:  # Mat HDD lien cuc trong 1 phut (6 * 10s)
                    _trigger_hard_reboot("Mất kết nối ổ cứng (SATA/USB bị ngắt)")
                    hdd_error_cycles = 0
            else:
                hdd_error_cycles = 0
                
            # 4. Kiểm tra LAN IP (eth0)
            ip_res = subprocess.run(["ip", "-4", "addr", "show", "eth0"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            if "inet " not in ip_res.stdout.decode() and hdd_error_cycles == 0:
                lan_error_cycles += 1
                log.warning("[Watchdog] Mất kết nối IP LAN eth0 (lần %d)", lan_error_cycles)
                _system_alert = "⚠️ Mất mạng LAN (Tự động Reboot sau %ds)" % ((6 - lan_error_cycles) * 10)
                if lan_error_cycles >= 6:  # Mat mang lien tuc trong 1 phut
                    _trigger_hard_reboot("Mất kết nối mạng LAN (eth0 không có IP)")
                    lan_error_cycles = 0
            elif hdd_error_cycles == 0:
                lan_error_cycles = 0
                _system_alert = ""
                
        except Exception as e:
            log.error("[Watchdog] Lỗi giám sát: %s", e)
            
        time.sleep(30)
            
# End Watchdog


@app.route("/api/tailscale/status", methods=["GET"])
@requires_auth
def api_tailscale_status():
    """Tr? v? trạng thái Tailscale: IP, status, so lan restart."""
    try:
        # Kiểm tra process
        proc = subprocess.run(["pgrep", "-x", "tailscaled"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        is_running = proc.returncode == 0
        
        # L?y IP Tailscale
        tailscale_ip = ""
        if is_running:
            try:
                ip_result = subprocess.run(
                    ["tailscale", "ip", "-4"],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5
                )
                tailscale_ip = ip_result.stdout.decode('utf-8', errors='ignore').strip() if ip_result.returncode == 0 else ""
            except Exception:
                pass
        
        # L?y trạng thái ket noi
        status_text = "stopped"
        if is_running:
            try:
                st = subprocess.run(
                    ["tailscale", "status", "--json"],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5
                )
                if st.returncode == 0:
                    st_json = json.loads(st.stdout.decode('utf-8', errors='ignore'))
                    backend_state = st_json.get("BackendState", "Unknown")
                    status_text = backend_state  # "Running", "NeedsLogin", "Stopped"
                else:
                    status_text = "running"
            except Exception:
                status_text = "running"
        
        return jsonify({
            "running": is_running,
            "status": status_text,
            "ip": tailscale_ip,
            "restart_count": _tailscale_restart_count,
            "last_restart": _tailscale_last_restart
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ============ GUEST PASS (FTP TAM THOI) ============
# Endpoint: POST /api/guest/create  { duration_minutes: 60 }
# Endpoint: POST /api/guest/revoke  { username: "nasguest_xxxx" }
#
# Cach hoat dong:
#  1. Tao user Linux ngau nhien (nasguest_xxxx) voi shell /bin/false (khoa SSH)
#  2. C?u hảnh vsftpd cho phep user nay: Read-Only vao thư mục Media/
#  3. Dat timeout: cron-like background thread se xoá user sau so phut da dat
#
# CANH BAO: Can chay voi quyen root (hoac sudo) de tao user h? thỏng.

import random
import string

# L?u trạng thái Guest Pass (dang hoat dong)
_guest_passes = {}  # {username: {"password": ..., "expires_at": epoch}}
_guest_lock = threading.Lock()

VSFTPD_USER_DIR = _cfg_str("vsftpd_user_dir", "/etc/vsftpd/userconf")  # CC-005 P1/P2: ENV NAS_VSFTPD_USER_DIR (default giữ nguyên)
GUEST_FTP_ROOT  = _cfg_str("guest_ftp_root", "/srv/dev-disk-by-label-data")  # CC-005 P1/P2: ENV NAS_GUEST_FTP_ROOT (default giữ nguyên)

def _generate_guest_name():
    suffix = ''.join(random.choice(string.ascii_lowercase + string.digits) for _ in range(6))
    return "nasguest_%s" % suffix

def _generate_guest_password(length=10):
    # CC-005 P1: random.SystemRandom().choice (os.urandom CSPRNG, Python 3.5.3 native) + length 12
    _choice_fn = random.SystemRandom().choice
    chars = string.ascii_letters + string.digits
    length = 12
    return "".join(_choice_fn(chars) for _ in range(length))

def _create_linux_user(username, password):
    """Tao user Linux khoa SSH, pha shell /sbin/nologin."""
    # CC-005 P2: username whitelist ^nasguest_[a-z0-9]{6}$ — chan command injection
    # qua useradd/chpasswd (username do _generate_guest_name sinh ra).
    import re as _re_guest
    if not _re_guest.match(r'^nasguest_[a-z0-9]{6}$', username or ""):
        log.warning("[GuestPass] Tu choi username khong hop le: %s", sanitize_log_input(username))
        return False
    try:
        # Tao user h? thỏng (khong home, khong login)
        subprocess.check_call([
            "useradd", "-M", "-s", "/sbin/nologin",
            "-G", "ftp", username
        ])
        # Dat mat khau qua echo "user:pass" | chpasswd
        proc = subprocess.Popen(
            ["chpasswd"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        proc.communicate(input=("%s:%s" % (username, password)).encode())
        return True
    except Exception as e:
        log.error("Lỗi tạo user %s: %s", username, e)
        return False

def _create_vsftpd_user_config(username):
    """Tao file per-user vsftpd config cho read-only access."""
    # CC-005 P2: whitelist username + realpath verify (GUEST_FTP_ROOT /
    # VSFTPD_USER_DIR resolve realpath, config_path containment).
    import re as _re_guest2
    if not _re_guest2.match(r'^nasguest_[a-z0-9]{6}$', username or ""):
        log.warning("[GuestPass] Tu choi username khong hop le")
        return False
    try:
        _user_dir_real = os.path.realpath(_cfg_str("vsftpd_user_dir", VSFTPD_USER_DIR))
        _ftp_root_real = os.path.realpath(_cfg_str("guest_ftp_root", GUEST_FTP_ROOT))
        if not os.path.isdir(_ftp_root_real):
            log.warning("[GuestPass] GUEST_FTP_ROOT khong ton tai")
            return False
        os.makedirs(_user_dir_real, exist_ok=True)
        config_path = os.path.realpath(os.path.join(_user_dir_real, username))
        if config_path != os.path.join(_user_dir_real, username):
            log.warning("[GuestPass] config_path thoat VSFTPD_USER_DIR")
            return False
        with open(config_path, 'w') as f:
            f.write("local_root=%s\n" % _ftp_root_real)
            f.write("write_enable=NO\n")
            f.write("anon_world_readable_only=YES\n")
            # FIX SANDBOX: Nhot user vao GUEST_FTP_ROOT, ngan Đọc thư mục cha (VD: /etc, /root)
            f.write("chroot_local_user=YES\n")
            f.write("allow_writeable_chroot=YES\n")
        return True
    except Exception as e:
        log.error("Lỗi tạo cấu hình vsftpd cho %s: %s", username, e)
        return False

def _delete_linux_user(username):
    """Xo? user Linux va file cau hinh vsftpd."""
    # CC-005 P2: whitelist + containment nhu _create_vsftpd_user_config.
    import re as _re_guest3
    if not _re_guest3.match(r'^nasguest_[a-z0-9]{6}$', username or ""):
        log.warning("[GuestPass] Tu choi xoa username khong hop le")
        return
    try:
        subprocess.run(["userdel", username], stderr=subprocess.DEVNULL)
        _user_dir_real = os.path.realpath(_cfg_str("vsftpd_user_dir", VSFTPD_USER_DIR))
        config_path = os.path.realpath(os.path.join(_user_dir_real, username))
        if config_path != os.path.join(_user_dir_real, username):
            log.warning("[GuestPass] config_path thoat VSFTPD_USER_DIR")
            return
        if os.path.exists(config_path):
            os.remove(config_path)
    except Exception as e:
        log.error("Lỗi xóa user %s: %s", username, e)

def _get_hdd_config_dir():
    """Lấy thư mục lưu cấu hình trên ổ cứng HDD để TRÁNH GHI LÊN eMMC hệ thống (GEMINI §6)."""
    if os.path.exists("/srv"):
        for item in os.listdir("/srv"):
            if item.startswith("dev-disk-by-"):
                disk_path = os.path.join("/srv", item)
                if os.path.isdir(disk_path):
                    config_dir = os.path.join(disk_path, ".naswebdav")
                    try:
                        os.makedirs(config_dir, mode=0o700, exist_ok=True)
                        try:
                            os.chmod(config_dir, 0o700)
                        except Exception:
                            pass
                        return config_dir
                    except Exception:
                        pass
    return None

def _get_guest_passes_file():
    hdd_dir = _get_hdd_config_dir()
    if hdd_dir:
        return os.path.join(hdd_dir, "guest_passes.json")
    return None

def _load_guest_passes():
    global _guest_passes
    file_path = _get_guest_passes_file()
    if file_path and os.path.exists(file_path):
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
                now = time.time()
                valid = {}
                for username, info in loaded.items():
                    if info.get("expires_at", 0) > now:
                        valid[username] = info
                    else:
                        _delete_linux_user(username)
                with _guest_lock:
                    _guest_passes = valid
        except Exception as e:
            log.warning("[GuestPass] Error loading guest passes: %s", e)

def _save_guest_passes():
    file_path = _get_guest_passes_file()
    if not file_path:
        log.warning("[GuestPass] Skip saving guest passes: No HDD partition mounted to prevent eMMC wear.")
        return
    try:
        with _guest_lock:
            data = dict(_guest_passes)
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(data, f)
        os.chmod(file_path, 0o600)
    except Exception as e:
        log.warning("[GuestPass] Error saving guest passes: %s", e)

_load_guest_passes()

def _guest_expiry_watcher():
    """Background thread: quet va xoá Guest Pass da het han (moi 30 giay)."""
    while True:
        try:
            now = time.time()
            with _guest_lock:
                expired = [u for u, v in _guest_passes.items() if v["expires_at"] <= now]
            if expired:
                for username in expired:
                    _delete_linux_user(username)
                    with _guest_lock:
                        _guest_passes.pop(username, None)
                    log.info("[GuestPass] Đã thu hồi user hết hạn: %s", username)
                _save_guest_passes()
        except Exception as e:
            log.error("[GuestPass] Lỗi watcher: %s", e)
        time.sleep(30)

# Background thread quan ly het han Guest Pass
threading.Thread(target=_guest_expiry_watcher, daemon=True).start()

@app.route("/api/guest/create", methods=["POST"])
# CC-005 P1/P2: endpoint nhay cam (tao user Linux + vsftpd config) bat buoc
# requires_admin — chan LAN-whitelist bypass cua requires_auth. Endpoint chi doc
# (status, storage, smart, omv overview) giu requires_auth.
@requires_admin
def api_guest_create():
    """Tao FTP user tam thoi (Read-Only) voi thoi gian ton tai gioi han."""
    try:
        body = request.get_json(force=True) or {}
        duration_minutes = int(body.get("duration_minutes", 60))
        duration_minutes = max(5, min(duration_minutes, 1440))  # 5ph - 24h
        # CC-005 P1: cap 10 guest dong thoi (chong useradd spam day /etc/passwd)
        with _guest_lock:
            _now_g = time.time()
            _active_g = sum(1 for v in _guest_passes.values() if v.get("expires_at", 0) > _now_g)
            if _active_g >= 10:
                return jsonify({"error": "Da dat toi da 10 guest, thu hoi bot truoc"}), 429

        username = _generate_guest_name()
        password = _generate_guest_password()
        expires_at = time.time() + duration_minutes * 60

        # Tao user Linux + cau hinh FTP
        ok_user = _create_linux_user(username, password)
        ok_ftp  = _create_vsftpd_user_config(username)

        if not ok_user:
            return jsonify({"error": "Không thể tạo user Linux. Kiểm tra quyền root."}), 500

        with _guest_lock:
            _guest_passes[username] = {"password": password, "expires_at": expires_at}
        _save_guest_passes()

        # L?y IP LAN cua NAS (vi Android can dia chi FTP)
        try:
            import socket as _socket_mod
            nas_host = _socket_mod.gethostbyname(_socket_mod.gethostname())
        except Exception:
            nas_host = request.host.split(":")[0]

        return jsonify({
            "username":       username,
            "password":       password,
            "host":           nas_host,
            "ftp_port":       21,
            "expires_at_unix": int(expires_at),
            "duration_minutes": duration_minutes,
            "message":        "Đã tạo Guest FTP '%s' (tồn tại %d phút)" % (username, duration_minutes)
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/guest/revoke", methods=["POST"])
# CC-005 P1/P2: nhay cam (userdel + xoa file config) -> requires_admin.
@requires_admin
def api_guest_revoke():
    """Thu hoi (xoa) FTP user tam thoi ngay lap tuc."""
    try:
        body = request.get_json(force=True) or {}
        username = body.get("username", "")
        import re as _re_revoke
        if not _re_revoke.match(r'^nasguest_[a-z0-9]{6}$', username or ""):
            return jsonify({"error": "Username không hợp lệ"}), 400

        _delete_linux_user(username)
        with _guest_lock:
            _guest_passes.pop(username, None)
        _save_guest_passes()

        return jsonify({"message": "Đã thu hồi Guest FTP user '%s' thành công." % username})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ============ TEMPORARY FILE SHARE LINKS ============
_share_links = {}  # {token: {"path": real_path, "expires_at": epoch}}
_share_lock = threading.Lock()


def _resolve_share_target(raw_path):
    root = _get_webdav_public_dir()
    if not root:
        return None
    root_real = os.path.realpath(root)
    rel = str(raw_path or "").strip()
    if rel.startswith("/webdav/"):
        rel = rel[len("/webdav/"):]
    elif rel == "/webdav":
        rel = ""
    rel = rel.lstrip("/")
    if not rel:
        return None
    candidate = os.path.realpath(os.path.join(root_real, rel))
    if candidate != root_real and not candidate.startswith(root_real + os.sep):
        return None
    if not os.path.isfile(candidate):
        return None
    return candidate


@app.route("/api/share/create", methods=["POST"])
@requires_auth
def api_share_create():
    import binascii
    try:
        body = request.get_json(force=True) or {}
        raw_path = body.get("path", "")
        hours = int(body.get("hours", 24) or 24)
        hours = max(1, min(168, hours))
        target = _resolve_share_target(raw_path)
        if not target:
            return jsonify({"error": "Tệp không tồn tại hoặc đường dẫn không hợp lệ"}), 404
        token = binascii.hexlify(os.urandom(12)).decode("ascii")
        now = time.time()
        exp = now + (hours * 3600)
        with _share_lock:
            expired = [k for k, v in _share_links.items() if v.get("expires_at", 0) <= now]
            for k in expired:
                _share_links.pop(k, None)
            if len(_share_links) >= 200:
                oldest = sorted(_share_links.items(), key=lambda kv: kv[1].get("expires_at", 0))[:20]
                for k, _ in oldest:
                    _share_links.pop(k, None)
            _share_links[token] = {"path": target, "expires_at": exp}
        return jsonify({
            "token": token,
            "url": "/api/share/dl/%s" % token,
            "expires_at": int(exp)
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/share/dl/<token>", methods=["GET"])
def api_share_download(token):
    import re as _re_tok
    if not _re_tok.match(r'^[a-f0-9]{24}$', token or ""):
        return jsonify({"error": "Token không hợp lệ"}), 404
    now = time.time()
    with _share_lock:
        entry = _share_links.get(token)
        if entry and entry.get("expires_at", 0) <= now:
            _share_links.pop(token, None)
            entry = None
    if not entry:
        return jsonify({"error": "Liên kết chia sẻ đã hết hạn hoặc không tồn tại"}), 404
    target = entry.get("path", "")
    root = _get_webdav_public_dir()
    if not root or not target:
        return jsonify({"error": "Không tìm thấy tệp"}), 404
    root_real = os.path.realpath(root)
    real_target = os.path.realpath(target)
    if real_target != root_real and not real_target.startswith(root_real + os.sep):
        return jsonify({"error": "Truy cập bị từ chối"}), 403
    if not os.path.isfile(real_target):
        return jsonify({"error": "Tệp không còn tồn tại"}), 404
    from flask import send_file
    return send_file(real_target, as_attachment=True, attachment_filename=os.path.basename(real_target))


# ============ LIVESTREAM RECORDER (TikTok / Facebook / YouTube Live) ============
# Ghi hinh livestream theo thoi gian thuc xuong HDD.
# S? dùng yt-dlp voi --live-from-start de capture HLS stream.
# CPU chi ~2-5% (chi copy segment, KHONG re-encode).
#
# Endpoint:
#   POST /api/livestream/record   { url, quality }
#   GET  /api/livestream/status
#   POST /api/livestream/stop     { job_id }

_livestream_jobs = {}  # {job_id: {url, platform, pid, output_file, started_at, status}}
_livestream_lock = threading.Lock()
_livestream_starting_claims = {}
# FIX-REVIEW-24/09-#4: co drain — record tu choi start moi trong luc drain.
_livestream_draining = False
# FIX-REVIEW-193369e-#4: tracker finalizer (remux) co ownership/liveness rieng.
# {job_id: {"thread": Thread, "done": Event, "result": str, "started": float}}
_livestream_finalizers = {}
# FIX-AUDIT-L5: snapshot registry truoc restart de reconcile sau exec.
_LIVESTREAM_REGISTRY_SNAPSHOT = "/var/tmp/nas_api_livestream_registry.json"


def _livestream_snapshot_registry():
    """Ghi snapshot nhe (khong Popen) cua registry ra file truoc restart."""
    try:
        snap = {}
        with _livestream_lock:
            for jid, info in list(_livestream_jobs.items()):
                snap[jid] = {k: v for k, v in info.items()
                             if not k.startswith("_proc") and k != "_proc"}
        with open(_LIVESTREAM_REGISTRY_SNAPSHOT, "w") as f:
            json.dump({"ts": time.time(), "jobs": snap}, f)
    except Exception as e:
        log.warning("[Livestream] Snapshot registry that bai: %s", e)


def _livestream_reconcile_after_restart():
    """Sau exec (registry rong), doc snapshot cu: job nao dang recording thi
    danh dau interrupted (khong mat han), de watcher/UI biet ma xu ly lai."""
    try:
        if not os.path.exists(_LIVESTREAM_REGISTRY_SNAPSHOT):
            return
        with open(_LIVESTREAM_REGISTRY_SNAPSHOT) as f:
            snap = json.load(f)
        jobs = (snap or {}).get("jobs", {})
        if not jobs:
            return
        with _livestream_lock:
            for jid, old in jobs.items():
                if jid in _livestream_jobs:
                    continue
                if old.get("status") in ("recording", "starting", "stopping", "timeout"):
                    _livestream_jobs[jid] = {
                        "url": old.get("url", ""),
                        "platform": old.get("platform", ""),
                        "recording_key": old.get("recording_key", ""),
                        "status": "error",
                        "error_reason": "Service restart giua chung ghi — can kiem tra file output.",
                        "output_file": old.get("output_file", ""),
                        "output_dir": old.get("output_dir", _LIVESTREAM_DIR),
                        "file_stem": old.get("file_stem", ""),
                        "direct_output_path": old.get("direct_output_path", ""),
                        "log_file": old.get("log_file", ""),
                        "started_ts": old.get("started_ts", 0),
                        "interrupted_by_restart": True,
                    }
        log.info("[Livestream] Reconcile sau restart: %d job cu danh dau interrupted.", len(jobs))
    except Exception as e:
        log.warning("[Livestream] Reconcile registry that bai: %s", e)
_livestream_recent_error_cooldown_sec = 300
# FIX-STALL-BACKOFF: stall kill dat cooldown dai rieng (2h). Cooldown chung 5p
# khien watcher ghi lai ngay stream dang dung -> vong lap kill-ghi-kill spam
# log + ton CPU + rac file ca dem (my.coffee79 2026-09-21).
_LIVESTREAM_STALL_COOLDOWN_SEC = 2 * 3600
_LIVESTREAM_DIR = os.path.join(WEBDAV_FILE_ROOT, "Livestream")
_LIVESTREAM_MAX_HOURS = 12  # Timeout tu dong sau 12 gio
# SPEC_LIVESTREAM_LIMITS §3.1: runtime doc qua _get_* (env > conf > default).
# Giu ten bien de khong vo test/AST; code chay phai qua helper effective_*.


def _livestream_effective_max_hours():
    try:
        _fn = globals().get("_get_livestream_max_hours")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    try:
        return int(_LIVESTREAM_MAX_HOURS)
    except Exception:
        return 12


def _livestream_effective_err_cooldown_s():
    try:
        _fn = globals().get("_get_live_err_cooldown_s")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    try:
        return int(_livestream_recent_error_cooldown_sec)
    except Exception:
        return 300


def _livestream_effective_stall_cooldown_s():
    try:
        _fn = globals().get("_get_live_stall_cooldown_s")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    try:
        return int(_LIVESTREAM_STALL_COOLDOWN_SEC)
    except Exception:
        return 7200


def _tiktok_effective_html_cap():
    try:
        _fn = globals().get("_get_tiktok_html_cap")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    return 786432


def _tiktok_effective_html_timeout():
    try:
        _fn = globals().get("_get_tiktok_html_timeout")
        if callable(_fn):
            return int(_fn())
    except Exception:
        pass
    return 25


def _livestream_kill_pid(pid, jid=""):
    """SIGTERM process livestream treo; best-effort, khong throw."""
    try:
        if not pid:
            return
        try:
            os.killpg(int(pid), signal.SIGTERM)
        except Exception:
            try:
                os.kill(int(pid), signal.SIGTERM)
            except Exception:
                pass
    except Exception:
        pass


def _livestream_job_cmdline_markers(info):
    """Cac chuoi nhan dang tien trinh thuoc ve job nay (de verify ownership)."""
    markers = []
    for key in ("file_stem", "tmp_dir", "wrapper_dir", "direct_output_path", "log_file"):
        val = info.get(key, "")
        if val:
            markers.append(os.path.basename(str(val)))
    stem = info.get("file_stem", "")
    if stem:
        markers.append(stem)
    return [m for m in markers if m]


def _livestream_pids_in_group(pgid):
    """Liet ke PID con song thuoc process group (parse /proc/*/stat dung,
    chiu duoc comm co khoang trang). Tra ve list [(pid, ppid)].

    FIX-REVIEW-24/09-#2: BAO GOM leader/direct child (PPID == API). Ban cu loai
    member co PPID==API, nhung recorder direct tao bang Popen tu API co PPID
    chinh la API -> direct ffmpeg khong con bi coi la group rong du con chay,
    dan toi stop/watchdog bo qua recorder con song."""
    out = []
    try:
        me = os.getpid()
        for entry in os.listdir("/proc"):
            if not entry.isdigit():
                continue
            pid_int = int(entry)
            if pid_int == me:
                continue
            try:
                with open("/proc/%s/stat" % entry) as sf:
                    raw = sf.read()
                rparen = raw.rfind(")")
                if rparen < 0:
                    continue
                fields = raw[rparen + 2:].split()
                # sau comm: state(0) ppid(1) pgrp(2) ...
                if len(fields) < 3:
                    continue
                if int(fields[2]) == pgid:
                    out.append((pid_int, int(fields[1])))
            except Exception:
                continue
    except Exception:
        pass
    return out


def _livestream_group_owned_by_job(pgid, info):
    """Kiem tra process group co that su thuoc ve job nay khong (chong PID reuse).
    owned = co it nhat 1 tien trinh trong group ma cmdline chua marker cua job
    (file_stem / tmp_dir / log file) hoac la yt-dlp/ffmpeg. Group rong -> (False, True)
    de caller biet da chet han. Khong match -> (False, False): KHONG kill.

    FIX-REVIEW-24/09-#2: uu tien identity/handle so huu thuc (_proc). Neu Popen
    handle con song (poll() is None) va proc.pid == pgid thi owned chac chan —
    khong phu thuoc cmdline marker (direct ffmpeg co the khong mang marker)."""
    # 1) Handle so huu thuc: Popen con song + pid khop pgid -> owned.
    try:
        _proc = info.get("_proc")
        if _proc is not None:
            try:
                _alive = _proc.poll() is None
            except Exception:
                _alive = False
            try:
                _pid_match = int(getattr(_proc, "pid", 0) or 0) == int(pgid)
            except Exception:
                _pid_match = False
            if _alive and _pid_match:
                return True, False
    except Exception:
        pass
    members = _livestream_pids_in_group(pgid)
    if not members:
        return False, True
    markers = _livestream_job_cmdline_markers(info)
    try:
        for pid_int, _ in members:
            try:
                with open("/proc/%d/cmdline" % pid_int, "rb") as cf:
                    cmdline = cf.read().replace(b"\x00", b" ").decode("utf-8", "ignore")
            except Exception:
                continue
            low = cmdline.lower()
            if "yt-dlp" in low or "ffmpeg" in low or "ffprobe" in low:
                if not markers:
                    return True, False
                for mk in markers:
                    if mk and mk in cmdline:
                        return True, False
                # yt-dlp/ffmpeg nhung khong phai marker cua job -> nghi PID reuse
                continue
            for mk in markers:
                if mk and mk in cmdline:
                    return True, False
    except Exception:
        pass
    return False, False


def _livestream_orphan_group_cleanup(jid, info, pid):
    """FIX-AUDIT-L2/L3: cha da chet nhung process group con song.
    - Graceful: lan dau gui SIGTERM + stamp, vong watchdog sau (60s) moi
      SIGKILL neu van con song (de ffmpeg flush/finalize file).
    - Ownership: chi signal khi group that su thuoc ve job (verify cmdline
      marker), chong PID/PGID reuse tac dong tien trinh khong lien quan.
    Tra ve True neu group da sach (chet han), False neu van con xu ly."""
    try:
        pgid = int(pid)
    except Exception:
        return True
    owned, empty = _livestream_group_owned_by_job(pgid, info)
    if empty:
        info.pop("_orphan_term_ts", None)
        return True
    if not owned:
        log.warning("[Livestream] Job %s: group %d co tien trinh nhung khong khop job (nghi PID reuse) — KHONG kill.",
                    jid, pgid)
        try:
            info.pop("_orphan_term_ts", None)
        except Exception:
            pass
        # Group khong thuoc ve job -> tien trinh cua job da chet that.
        # Tra ve True de caller tiep tuc finalize job, khong bi tien trinh
        # khong lien quan chan.
        return True
    now = time.time()
    first_seen = info.get("_orphan_term_ts", 0) or 0
    if not first_seen:
        log.warning("[Livestream] Job %s: cha da chet nhung group %d van con tien trinh — SIGTERM graceful, cho 60s flush.",
                    jid, pgid)
        try:
            os.killpg(pgid, signal.SIGTERM)
        except Exception:
            try:
                os.kill(pgid, signal.SIGTERM)
            except Exception:
                pass
        info["_orphan_term_ts"] = now
        return False
    if now - float(first_seen) > 60:
        log.warning("[Livestream] Job %s: group %d van song sau SIGTERM 60s — SIGKILL.", jid, pgid)
        try:
            os.killpg(pgid, signal.SIGKILL)
        except Exception:
            pass
        try:
            info.pop("_orphan_term_ts", None)
        except Exception:
            pass
        return False
    return False


def _livestream_current_output_size(info):
    """Doc dung luong thuc te cua file dang ghi tu dia (KHONG dung info['file_size']).

    info['file_size'] chi duoc cap nhat o nhanh xu ly process da ket thuc, nen
    trong luc dang ghi luon bang 0. Watchdog phai doc truc tiep tu dia de danh
    gia tien trinh ghi — neu khong se ket luan sai la stream dung tien trien
    va kill nham mot phien dang ghi khoe.

    Quan trong: nhanh yt-dlp thong thuong khong luu ten file cu the vao
    info['output_file']/info['direct_output_path'] (de trong) luc dang ghi —
    no chi ghi qua output_template "%(ext)s" nen ten file thuc te chua
    file_stem cua job. Vi vay ngoai 2 duong dan da biet, helper con quet
    thu muc output_dir de tim file BAT DAU BANG file_stem cua job nay.

    FIX-P2-1: dung file_stem (prefix duy nhat: platform[_stable_id]_timestamp)
    thay vi substring timestamp_str. timestamp_str chi den GIÂY nen 2 job cua 2
    user khac nhau khoi cung giay se co chung substring -> quet theo 'in' lay
    ca file cua job khac (va lay max size) -> watchdog do sai size, hoac khong
    bao gio kill duoc job treo, hoac kill nham job khoe. file_stem gom ca
    stable_id nen phan biet duoc 2 user cung giay.
    """
    out_dir = info.get("output_dir", _LIVESTREAM_DIR)
    out_file = info.get("output_file", "")
    direct_path = info.get("direct_output_path", "")
    stem = info.get("file_stem", "")
    best = 0

    def _consider(cand):
        nonlocal best
        if not cand or not os.path.isfile(cand):
            return
        try:
            sz = os.path.getsize(cand)
            if sz > best:
                best = sz
        except Exception:
            pass

    # 1) Duong dan file da biet (nhanh TikTok FLV / file da remux xong)
    _consider(direct_path)
    _consider(os.path.join(out_dir, out_file) if out_file else "")
    # 2) Nhanh yt-dlp / file thuc te: match chinh xac file cua job nay bang
    #    file_stem (startswith), KHONG dung substring timestamp_str.
    if stem and os.path.isdir(out_dir):
        try:
            for fn in os.listdir(out_dir):
                if fn.startswith(stem) and os.path.isfile(os.path.join(out_dir, fn)):
                    _consider(os.path.join(out_dir, fn))
        except Exception:
            pass
    return best

def _detect_platform(url):
    """Nhan dien nen tang tu URL."""
    if not url:
        return "other"
    url_lower = url.lower()
    if "tiktok.com" in url_lower:
        return "tiktok"
    elif "facebook.com" in url_lower or "fb.watch" in url_lower:
        return "facebook"
    elif "youtube.com" in url_lower or "youtu.be" in url_lower:
        return "youtube"
    elif "instagram.com" in url_lower:
        return "instagram"
    elif "douyin.com" in url_lower:
        return "douyin"
    elif "twitter.com" in url_lower or "x.com" in url_lower:
        return "twitter"
    elif "reddit.com" in url_lower:
        return "reddit"
    elif "shopee" in url_lower:
        return "shopee"
    return "other"

def _livestream_recording_key(platform, url, watch_username=""):
    """Stable key used to prevent duplicate recording sessions."""
    platform = (platform or _detect_platform(url or "") or "other").lower()
    username = _normalize_tiktok_username(watch_username or "")
    if not username and "tiktok" in (url or "").lower():
        try:
            m = _re_module.search(r"tiktok\.com/@([\w.\-]+)", url or "")
            if m:
                username = _normalize_tiktok_username(m.group(1))
        except Exception:
            username = ""
    if platform == "tiktok" and username:
        return "tiktok:%s" % username.lower()
    return "%s:%s" % (platform, (url or "").strip().rstrip("/").lower())

def _livestream_active_job_for_key_locked(recording_key):
    if not recording_key:
        return "", None
    tiktok_user = ""
    if recording_key.startswith("tiktok:"):
        tiktok_user = recording_key.split(":", 1)[1]
        target_url = "@%s/live" % tiktok_user
    else:
        target_url = ""
    for jid, info in list(_livestream_jobs.items()):
        is_match = info.get("recording_key", "") == recording_key
        if not is_match and tiktok_user:
            is_match = (info.get("watch_username", "").lower() == tiktok_user or
                        target_url in info.get("url", "").lower() or
                        target_url in info.get("original_url", "").lower())
        if not is_match:
            continue
        # FIX-REVIEW-24/09-#11: giu reservation den khi het writer/finalizing.
        # Ban cu chi dedup recording: job stopping/finalizing (nhom cu dang
        # flush) bi coi la trong -> start trung khi ngoai cooldown.
        if info.get("status") not in ("recording", "starting", "stopping", "timeout", "finalizing"):
            continue
        return jid, info
    return "", None

def _livestream_recent_job_for_key_locked(recording_key, max_age_sec=None):
    if not recording_key:
        return "", None
    if not max_age_sec:
        try:
            max_age_sec = _livestream_effective_err_cooldown_s()
        except Exception:
            max_age_sec = _livestream_recent_error_cooldown_sec
    now = time.time()
    best_job_id = ""
    best_info = None
    best_started = 0.0
    for jid, info in list(_livestream_jobs.items()):
        if info.get("recording_key", "") != recording_key:
            continue
        started_ts = float(info.get("started_ts", 0) or 0)
        if now - started_ts > max_age_sec:
            continue
        if started_ts >= best_started:
            best_job_id = jid
            best_info = info
            best_started = started_ts
    return best_job_id, best_info

_ytdlp_bin_cache = {"path": "", "checked_at": 0.0}

def _find_ytdlp_bin():
    """Tim yt-dlp binary tren h? thỏng."""
    now = time.time()
    cached = _ytdlp_bin_cache.get("path", "")
    if cached and now - float(_ytdlp_bin_cache.get("checked_at", 0.0) or 0.0) < 3600:
        return cached
    for candidate in ["/usr/local/bin/yt-dlp", "/usr/bin/yt-dlp", "yt-dlp", 
                       "/opt/yt-dlp", "/root/yt-dlp", "/usr/local/bin/yt-dlp_linux_aarch64"]:
        try:
            found = candidate
            if not os.path.isabs(candidate):
                found = shutil.which(candidate) or ""
            if found and os.path.exists(found) and os.access(found, os.X_OK):
                _ytdlp_bin_cache["path"] = found
                _ytdlp_bin_cache["checked_at"] = now
                return found
        except Exception:
            continue
    _ytdlp_bin_cache["path"] = ""
    _ytdlp_bin_cache["checked_at"] = now
    return None

def _direct_flv_has_remuxable_video(flv_url, cookies_path="", user_agent=""):
    """Kiểm tra nhanh ffmpeg tren NAS co nhan được codec video cua FLV CDN khong."""
    cmd = [
        "ffprobe", "-v", "error",
        "-analyzeduration", "3000000",
        "-probesize", "1048576",
        "-select_streams", "v:0",
        "-show_entries", "stream=codec_name",
        "-of", "default=noprint_wrappers=1:nokey=1",
    ]
    if user_agent:
        cmd.extend(["-user_agent", user_agent])
    # Signed media URLs must not receive browser cookies.
    cmd.extend(["-headers", "Referer: https://www.tiktok.com/\r\n"])
    cmd.append(flv_url)
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
        codec = (proc.stdout or b"").decode("utf-8", errors="ignore").strip().lower()
        if proc.returncode == 0 and codec and codec not in ("unknown", "none"):
            log.info("[Livestream] Direct FLV probe OK: codec=%s url=%s", codec, flv_url[:120])
            return True
        log.warning("[Livestream] Codec FLV trực tiếp không remux được qua ffprobe: codec=%s err=%s",
                    codec or "-", (proc.stderr or b"")[-160:])
    except Exception as e:
        log.warning("[Livestream] Lỗi kiểm tra codec FLV trực tiếp: %s", e)
    return False

def _livestream_video_is_playable(video_path):
    if not video_path or not os.path.exists(video_path) or os.path.getsize(video_path) <= 1024:
        return False
    try:
        proc = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-show_entries", "stream=codec_type:format=duration",
                "-of", "json",
                video_path,
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=15,
        )
        if proc.returncode != 0:
            err_tail = (proc.stderr or b"")[-200:].decode("utf-8", errors="ignore")
            log.warning("[Livestream] ffprobe không đọc được video %s: %s", os.path.basename(video_path), err_tail)
            return False
        data = json.loads((proc.stdout or b"{}").decode("utf-8", errors="ignore") or "{}")
        streams = data.get("streams") or []
        has_video = any((s or {}).get("codec_type") == "video" for s in streams if isinstance(s, dict))
        duration_text = str((data.get("format") or {}).get("duration") or "").strip()
        return bool(has_video) or bool(duration_text)
    except Exception as e:
        log.warning("[Livestream] Lỗi kiểm tra video %s: %s", os.path.basename(video_path), e)
        return False


def _quarantine_broken_livestream_file(file_path):
    if not file_path or not os.path.exists(file_path):
        return ""
    try:
        broken_path = file_path + ".broken"
        if os.path.exists(broken_path):
            broken_path = broken_path + "." + str(int(time.time()))
        os.rename(file_path, broken_path)
        _index_mutated_directories((os.path.dirname(file_path),))
        return broken_path
    except Exception:
        return ""


def _remux_flv_to_mp4(flv_path):
    """Remux file FLV thảnh MP4 bang ffmpeg -c copy (khong re-encode, ~0% CPU).
    Tr? v? duong dan file MP4 neu thảnh cầng, hoac chuoi rong neu thất bại."""
    if not flv_path or not os.path.exists(flv_path):
        return ""
    mp4_path = os.path.splitext(flv_path)[0] + ".mp4"
    try:
        # -c copy: chi doi container, khong giai ma -> CPU cuc thap.
        # -movflags +faststart: dat moov atom o dau file, cho phep stream/seek nhanh.
        # -fflags +genpts: regen PTS de trảnh lỗi "non-monotonic DTS".
        proc = subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            "-fflags", "+genpts",
            "-i", flv_path,
            "-c", "copy",
            "-movflags", "+faststart",
            "-bsf:a", "aac_adtstoasc",
            mp4_path,
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        if proc.returncode == 0 and _livestream_video_is_playable(mp4_path):
            try:
                os.remove(flv_path)
            except Exception:
                pass
            _index_mutated_directories((os.path.dirname(mp4_path),))
            return mp4_path
        if not os.path.exists(flv_path):
            log.warning("[Livestream] File nguon bien mat trong luc remux, bo qua quarantine: %s",
                        os.path.basename(flv_path))
            return ""
        # Fallback: th? lỗi không dùng aac_adtstoasc (mot so FLV co audio non-AAC)
        proc2 = subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            "-fflags", "+genpts",
            "-i", flv_path,
            "-c", "copy",
            "-movflags", "+faststart",
            mp4_path,
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        if proc2.returncode == 0 and _livestream_video_is_playable(mp4_path):
            try:
                os.remove(flv_path)
            except Exception:
                pass
            _index_mutated_directories((os.path.dirname(mp4_path),))
            return mp4_path
        if not os.path.exists(flv_path):
            log.warning("[Livestream] File nguon bien mat trong luc remux fallback, bo qua quarantine: %s",
                        os.path.basename(flv_path))
            return ""
        # FIX: Pass 3 — th?m h264_mp4toannexb video BSF. Mot so FLV/H264 thieu
        # NAL annexB delimiter -> mp4 muxer reject. BSF nay th?m lai delimiter.
        proc3 = subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            "-fflags", "+genpts",
            "-i", flv_path,
            "-c", "copy",
            "-bsf:v", "h264_mp4toannexb",
            "-movflags", "+faststart",
            mp4_path,
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        if proc3.returncode == 0 and _livestream_video_is_playable(mp4_path):
            try:
                os.remove(flv_path)
            except Exception:
                pass
            _index_mutated_directories((os.path.dirname(mp4_path),))
            return mp4_path
        if proc.returncode in (-15, -9) or proc2.returncode in (-15, -9) or proc3.returncode in (-15, -9):
            log.warning("[Livestream] Remux bị dừng do restart/cancel (rc=%d|%d|%d), giữ nguyên file gốc: %s",
                        proc.returncode, proc2.returncode, proc3.returncode, os.path.basename(flv_path))
            return ""
        # Het cach: log day du stderr + rename file .flv -> .broken.flv de user
        # biet file da bi hong/không pl?y được, KHÔNG xoá (de debug hoac thu
        # mo bang VLC tay).
        err_tail = (proc3.stderr or proc2.stderr or b"")[-240:]
        log.warning("[Livestream] Remux FLV sang MP4 thất bại sau cả 3 lượt thử (rc=%d|%d|%d): %s",
                    proc.returncode, proc2.returncode, proc3.returncode, err_tail)
        try:
            broken_path = _quarantine_broken_livestream_file(flv_path)
            log.info("[Livestream] FLV không mở được, đã đổi tên thành: %s", os.path.basename(broken_path))
        except Exception:
            pass
        return ""
    except Exception as e:
        log.error("[Livestream] Lỗi remux FLV sang MP4: %s", e)
        return ""

def _livestream_error_from_log(info):
    log_file = info.get("log_file", "")
    if not log_file or not os.path.exists(log_file):
        return ""
    try:
        with open(log_file, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()
        last_lines = lines[-30:]
        for line in reversed(last_lines):
            line_clean = line.strip()
            line_lo = line_clean.lower()
            if "not currently live" in line_lo:
                return "Kênh hiện không còn live."
            if "this live has ended" in line_lo or "live has ended" in line_lo:
                return "Livestream đã kết thúc."
            if "http error 403" in line_lo or "forbidden" in line_lo:
                return "Nguồn livestream bị từ chối hoặc URL stream đã hết hạn."
            if "http error 404" in line_lo or "not found" in line_lo:
                return "Không tìm thấy nguồn livestream."
            if "timed out" in line_lo or "timeout" in line_lo:
                return "Kết nối tới nguồn livestream bị quá hạn."
            if "offline" in line_lo:
                return "Kênh đang offline hoặc chưa phát livestream."
            if "error:" in line_lo or "failed" in line_lo or "http error" in line_lo:
                return "Không tải được nguồn livestream. Vui lòng kiểm tra kênh còn live và cookie TikTok."
        if last_lines:
            return "Không đọc được trạng thái livestream từ nhật ký yt-dlp."
    except Exception:
        pass
    return ""

def _livestream_watchdog():
    """Thread nen tu dong kill cac livestream job qua 12 gio hoac da chet.
    Cung cap nhat thumbnail gate khi livestream/ytdlp không cần chay."""
    while True:
        try:
            time.sleep(60)  # Kiểm tra mới ph?t
            ytdlp_active = False
            _cleanup_stale_job_tmp(max_age_hours=24)
            _cleanup_runtime_tmp_artifacts(max_age_minutes=30)
            _cleanup_livestream_junk()
            # Thu hồi RAM steady-state mỗi 60s: trả pages rảnh (thumbnail/livestream
            # đã xong) về OS. Rẻ (~vài µs khi không có gì để trim) trên NAS ~1GB.
            _release_memory_to_os()
            # Auto-purge job cũ để _livestream_jobs/_livestream_starting_claims
            # không phình vô hạn (RAM NAS chỉ ~1GB). Chỉ xóa job ĐÃ kết thúc
            # (không còn recording/starting) và quá 6 giờ — app đã đồng bộ trạng
            # thái cuối từ lâu; cooldown dedup chỉ 300s nên không ảnh hưởng.
            try:
                _purge_now = time.time()
                with _livestream_lock:
                    for _pjid in list(_livestream_jobs.keys()):
                        _pinfo = _livestream_jobs.get(_pjid) or {}
                        _pstatus = _pinfo.get("status", "")
                        _pstarted = float(_pinfo.get("started_ts", 0) or 0)
                        # FIX-SIGKILL: job dang trong qua trinh ket thuc (timeout/
                        # stopping + _kill_ts) CHUA chet han toan (process co the
                        # con song, dang cho SIGKILL) -> khong purge voi, de
                        # watchdog xac nhan process chet roi moi remove.
                        _terminating = _pstatus in ("timeout", "stopping") and _pinfo.get("_kill_ts")
                        if _terminating:
                            continue
                        if _pstatus in ("finished", "error", "stopped", "cancelled") and _purge_now - _pstarted > 21600:
                            _livestream_jobs.pop(_pjid, None)
            except Exception as _pe:
                log.warning("[Livestream] Auto-purge job lỗi: %s", _pe)
            # FIX-AUDIT-L4: reap co chu dich moi vong watchdog — poll Popen handle,
            # zombie thi wait() de thu hoi exit code ngay, khong doi subprocess tiep
            # theo moi reap. Giu _reaped_rc de nhanh finalize dung, khong kill lai.
            try:
                with _livestream_lock:
                    for _rjid, _rinfo in list(_livestream_jobs.items()):
                        _proc = _rinfo.get("_proc")
                        if _proc is None or _rinfo.get("_reaped_rc") is not None:
                            continue
                        try:
                            _rc = _proc.poll()
                        except Exception:
                            continue
                        if _rc is not None:
                            try:
                                _proc.wait(timeout=0)
                            except Exception:
                                pass
                            _rinfo["_reaped_rc"] = _rc
                            log.info("[Livestream] Job %s: reaped Popen rc=%s.", _rjid, _rc)
            except Exception as _re:
                log.warning("[Livestream] Reap Popen lỗi: %s", _re)
            # Reap ytdlp_jobs zombies in background so /api/ytdlp/download jobs
            # do not stay 'running' forever if client never polls /api/ytdlp/status.
            try:
                with _ytdlp_lock:
                    _ydl_changed = False
                    for _yjid, _yinfo in list(_ytdlp_jobs.items()):
                        _yproc = _yinfo.get("_proc")
                        _ypid = _yinfo.get("pid")
                        _ydead = False
                        if _yproc is not None:
                            try:
                                if _yproc.poll() is not None:
                                    try:
                                        _yproc.wait(timeout=0)
                                    except Exception:
                                        pass
                                    _ydead = True
                            except Exception:
                                pass
                        elif _ypid:
                            try:
                                os.kill(int(_ypid), 0)
                            except Exception:
                                _ydead = True
                        if _ydead:
                            _cleanup_job_tmp(_yinfo.get("tmp_dir", ""))
                            _ytdlp_jobs.pop(_yjid, None)
                            _ydl_changed = True
                    if _ydl_changed:
                        _set_thumbnail_auto_block("ytdlp", len(_ytdlp_jobs) > 0)
            except Exception as _ye:
                log.warning("[Livestream] Reap ytdlp jobs lỗi: %s", _ye)
            # FIX-AUDIT-L5: danh sach remux thu thap trong vong lock, xu ly NGOAI
            # lock o duoi (ffmpeg 600s khong giu lock).
            pending_remux = []
            finished_directories = set()
            with _livestream_lock:
                for jid, info in list(_livestream_jobs.items()):
                    # FIX-SIGKILL: job dang trong qua trinh ket thuc (timeout/
                    # stopping) KHONG duoc bo qua — phai di tiep den nhanh
                    # escalation de SIGKILL process con song. Truoc day "timeout"
                    # nam trong skip-set o day -> vong lap continue truoc khi
                    # toi nhanh SIGKILL o duoi, nen process bi treo kho khong
                    # bao gio bi kill cung.
                    _st = info.get("status", "")
                    if _st in ("finished", "error", "stopped", "cancelled", "finalizing"):
                        continue
                    if _livestream_draining:
                        continue
                    if _st in ("timeout", "stopping"):
                        # Job dang cho process chet: xu ly o nhanh escalation
                        # (SIGKILL neu qua 60s) DUOI cung, khong re va nhanh
                        # 12h SIGTERM o day.
                        pass
                    pid = info.get("pid")
                    # Kiểm tra process con song khong
                    is_running = False
                    try:
                        os.kill(pid, 0)
                        is_running = True
                    except Exception:
                        pass

                    if not is_running:
                        # FIX-AUDIT-L2/L3: cha chet nhung group con song -> graceful
                        # cleanup co verify ownership (chong PID reuse), cho 60s flush.
                        # Group sach hoac khong thuoc ve job -> finalize binh thuong.
                        # Group van con xu ly (cua job) -> bo qua finalize vong nay.
                        if not _livestream_orphan_group_cleanup(jid, info, pid):
                            continue
                        # Process da ket thuc tu nhien (stream het hoac lỗi)
                        try:
                            out_pattern = info.get("output_dir", "")
                            file_stem = info.get("file_stem", "")
                            # FIX-P2-1: match bang file_stem (prefix duy nhat) thay vi
                            # substring timestamp_str, tranh lay nham file cua job khac
                            # cung giay. Khong lay file moi nhat toan thu muc vi job
                            # fail/offline se bi gan nham MP4 cu va bao sai trang thai.
                            if os.path.isdir(out_pattern):
                                files = sorted(
                                    [os.path.join(out_pattern, f) for f in os.listdir(out_pattern)
                                     if (not file_stem or f.startswith(file_stem))
                                     and os.path.isfile(os.path.join(out_pattern, f))],
                                    key=os.path.getmtime, reverse=True
                                )
                                if files:
                                    info["output_file"] = os.path.basename(files[0])
                                    info["file_size"] = os.path.getsize(files[0])
                                    info["_latest_output_path"] = files[0]
                        except Exception:
                            pass

                        # FIX-AUDIT-L5: KHONG remux duoi lock (ffmpeg 600s giu lock,
                        # self-watchdog thay lock ket -> restart -> mat registry).
                        # Trong lock chi snapshot + danh dau; remux thuc hien NGOAI
                        # lock o cuoi vong watchdog, commit co kiem tra generation.
                        flv_path = ""
                        try:
                            latest_path = info.get("_latest_output_path", "")
                            direct_path = info.get("direct_output_path", "")
                            if direct_path and direct_path.lower().endswith((".flv", ".ts")) and os.path.exists(direct_path):
                                flv_path = direct_path
                            elif latest_path and latest_path.lower().endswith((".flv", ".ts")) and os.path.exists(latest_path):
                                flv_path = latest_path
                            if flv_path and os.path.getsize(flv_path) > 1024:
                                info["_remux_pending"] = flv_path
                                info["_remux_generation"] = info.get("_generation", 0)
                                try:
                                    pending_remux.append((jid, flv_path, info.get("_generation", 0)))
                                except NameError:
                                    pass
                        except Exception as e:
                            log.warning("[Livestream] Job %s: chuan bi remux that bai: %s", jid, e)

                        # Kiểm tra dung lượng file de xac dinh thảnh cầng hay thất bại
                        final_path = ""
                        try:
                            direct_path = info.get("direct_output_path", "")
                            latest_path = info.get("_latest_output_path", "")
                            output_file = info.get("output_file", "")
                            output_dir = info.get("output_dir", _LIVESTREAM_DIR)
                            for candidate in (direct_path, latest_path, os.path.join(output_dir, output_file) if output_file else ""):
                                if candidate and os.path.exists(candidate):
                                    final_path = candidate
                                    break
                            if final_path and final_path.lower().endswith(".mp4") and not _livestream_video_is_playable(final_path):
                                broken_path = _quarantine_broken_livestream_file(final_path)
                                info["output_file"] = os.path.basename(broken_path) if broken_path else os.path.basename(final_path) + ".broken"
                                info["direct_output_path"] = broken_path
                                info["file_size"] = 0
                                info["error_reason"] = "Tệp MP4 thiếu metadata moov atom hoặc ffprobe không đọc được."
                                log.error("[Livestream] Job %s: MP4 không hợp lệ, đã chuyển sang .broken: %s", jid, info["output_file"])
                        except Exception as e:
                            log.warning("[Livestream] Job %s: không kiểm tra được MP4 sau ghi: %s", jid, e)

                        if info.get("file_size", 0) < 1000:
                            info["status"] = "error"
                            info["error_reason"] = _livestream_error_from_log(info) or "Không tạo được tệp video hợp lệ."
                            log.error("[Livestream] Job %s (PID %d) đã kết thúc với lỗi (tệp < 1KB).", jid, pid)
                            reason_str = str(info.get("error_reason", ""))
                            if "Không tìm thấy nguồn" in reason_str:
                                log_type = "INFO"
                                log_msg = "Hiện không live."
                            else:
                                log_type = "ERROR"
                                log_msg = "Lỗi ghi: %s" % reason_str
                                
                            if info.get("logged_start"):
                                _log_livestream_event(
                                    log_type, jid, info, log_msg, "final_error"
                                )
                        # FIX-REVIEW-24/09-#10: finalizing den khi remux commit.
                        # Ban cu danh dau finished/log SUCCESS truoc remux ngoai
                        # lock; remux that bai (ke ca .broken) van giu finished.
                        # Quy trinh moi: con _remux_pending -> status=finalizing,
                        # bo qua finalize vong nay; commit remux ngoai lock se gan
                        # finished (SUCCESS) hoac error (that bai/.broken).
                        elif info.get("_remux_pending"):
                            info["status"] = "finalizing"
                            continue
                        else:
                            info["status"] = "finished"
                            if final_path:
                                finished_directories.add(os.path.dirname(final_path))
                            log.info("[Livestream] Job %s (PID %d) đã kết thúc tự nhiên.", jid, pid)
                            log_msg = "Đã lưu thành công (%s)." % format_bytes(int(info.get("file_size", 0) or 0))
                            _log_livestream_event(
                                "SUCCESS", jid, info, log_msg, "finished"
                            )

                        info["finished_at"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                        _cleanup_job_tmp(info.get("tmp_dir", ""))
                        # FIX-ENOSPC: dọn cả thư mục chứa wrapper loop_*.py trên HDD.
                        _cleanup_job_tmp(info.get("wrapper_dir", ""))
                        _cleanup_runtime_tmp_artifacts(max_age_minutes=30)
                        if info.get("status") != "finished" or info.get("file_size", 0) < 150 * 1024:
                            continue

                        # Ghi log
                        try:
                            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
                            cur = conn.cursor()
                            file_size_str = format_bytes(info.get("file_size", 0))
                            msg = "Livestream %s đã ghi xong: %s (%s)" % (
                                info.get("platform", "unknown"),
                                info.get("output_file", "?"),
                                file_size_str
                            )
                            cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                                        ("SUCCESS", "Livestream", msg))
                            conn.commit()
                            conn.close()
                        except Exception:
                            pass
                        continue

                    # FIX-SIGKILL (thu tu): phai kiem tra escalation (timeout/
                    # stopping + _kill_ts) TRUOC nhanh 12h SIGTERM. Neu de nhanh
                    # 12h o tren, job da vuot 12h van vao lai block do moi tick
                    # => SIGTERM + reset _kill_ts lien tuc => SIGKILL (o elif cu)
                    # khong bao gio tich luy du 60s. Dat escalation len dau tien.
                    if info.get("status") in ("timeout", "stopping") and info.get("_kill_ts"):
                        # Vong sau: process li sau SIGTERM -> SIGKILL.
                        try:
                            os.kill(pid, 0)
                            if time.time() - float(info.get("_kill_ts", 0)) > 60:
                                log.warning("[Livestream] Job %s li don sau SIGTERM, SIGKILL ca cay process.", jid)
                                try:
                                    # FIX-P2-2: kill ca process group (pid==PGID vi
                                    # Popen start_new_session=True) de diet ffmpeg con.
                                    os.killpg(int(pid), signal.SIGKILL)
                                except Exception:
                                    try:
                                        os.kill(int(pid), signal.SIGKILL)
                                    except Exception:
                                        pass
                        except Exception:
                            pass
                    else:
                        # Kiểm tra timeout (12 gio) — chi SIGTERM + dat _kill_ts,
                        # KHONG reset _kill_ts cua nhanh escalation o tren.
                        # FIX-HANG: started_ts thieu -> dung thoi diem watchdog.
                        # SPEC_LIVESTREAM_LIMITS §3.1: max_hours doc dong (default 12).
                        started = info.get("started_ts", 0) or info.get("_watch_first_seen", 0)
                        if not info.get("_watch_first_seen"):
                            info["_watch_first_seen"] = time.time()
                            started = started or time.time()
                        try:
                            _job_mh = int(info.get("max_hours", 0) or 0)
                            _live_max_h = _job_mh if (1 <= _job_mh <= 72) else _livestream_effective_max_hours()
                        except Exception:
                            _live_max_h = 12
                        # SPEC dynamic + COMPAT AST: giu literal goc trong bieu thuc
                        # (khi default 12 thi dung nhanh _LIVESTREAM_MAX_HOURS * 3600).
                        _live_max_s = (_live_max_h * 3600 if _live_max_h != 12 else _LIVESTREAM_MAX_HOURS * 3600)
                        if started > 0 and (time.time() - started) > _live_max_s:
                            log.warning("[Livestream] Job %s vượt quá %d giờ, tự động dừng.", jid, _live_max_h)
                            _log_livestream_event("WARNING", jid, info, "Tu dong dung vi vuot qua %d gio." % _live_max_h, "timeout")
                            _livestream_kill_pid(pid, jid)
                            if not info.get("_kill_ts"):
                                info["_kill_ts"] = time.time()
                            info["status"] = "timeout"
                        else:
                            # Stall detector: file output khong lon sau 20 phut -> ket luan treo.
                            # FIX-WATCHDOG: doc dung luong THUC te tu dia (file dang ghi),
                            # khong doc info['file_size'] vi truong nay chi cap nhat khi
                            # process da chet (luon = 0 luc dang ghi) -> watchdog ket luan
                            # sai stream dung tien trien va kill nham phien dang ghi khoe.
                            # FIX-AUDIT-L1: GET status (doc) danh dau stall_suspected khi
                            # thay size dung 4 phut. Watchdog doc co nay de xac nhan
                            # doc lap tu dia: neu dia tiep tuc dung -> kill ngay ma
                            # khong doi du 20 phut tu moc watchdog (dong nhat chinh
                            # sach stall, tranh mau thuan nguong 4p vs 20p).
                            try:
                                cur_size = _livestream_current_output_size(info)
                                last = float(info.get("_stall_check_size", -1))
                                last_ts = float(info.get("_stall_check_ts", 0))
                                now = time.time()
                                _get_suspect = bool(info.get("stall_suspected", False))
                                _get_since = float(info.get("stall_since", 0) or 0)
                                if last < 0:
                                    info["_stall_check_size"] = float(cur_size)
                                    info["_stall_check_ts"] = now
                                elif cur_size != last:
                                    info["_stall_check_size"] = float(cur_size)
                                    info["_stall_check_ts"] = now
                                    info["stall_suspected"] = False
                                    info["stall_since"] = 0
                                elif now - last_ts > 20 * 60 or (_get_suspect and _get_since and now - _get_since > 240 and now - last_ts > 240):
                                    _mins = int(max(now - last_ts, (now - _get_since) if _get_since else 0) / 60)
                                    log.warning("[Livestream] Job %s dung tien trien %d phut (size=%d), kill.",
                                                jid, _mins, cur_size)
                                    _log_livestream_event("WARNING", jid, info, "Tu dong dung vi stream dung tien trien.", "stalled")
                                    _livestream_kill_pid(pid, jid)
                                    info["status"] = "timeout"
                                    info["_kill_ts"] = now
                                    info["stall_suspected"] = False
                                    info["stall_since"] = 0
                                    # Backoff dai: watcher khong ghi lai user nay trong 2h.
                                    try:
                                        _stall_cd = _livestream_effective_stall_cooldown_s()
                                    except Exception:
                                        _stall_cd = 7200
                                    info["_stall_cooldown_until"] = now + _stall_cd
                            except Exception:
                                pass

                active = any(j.get("status") == "recording" for j in _livestream_jobs.values())
            _index_mutated_directories(finished_directories)
            # FIX-AUDIT-L5: remux NGOAI lock (ffmpeg toi 600s). Commit co kiem
            # tra generation: job bi stop/restart giua chung thi bo qua, khong
            # ghi de trang thai cua the he moi.
            try:
                for _rjid, _rflv, _rgen in list(pending_remux):
                    # FIX-REVIEW-193369e-#4: chay remux trong thread co tracker —
                    # drain/restart thay duoc finalizer dang song (thay vi mat
                    # hut khi recorder chet). Bo qua neu job da co finalizer.
                    try:
                        with _livestream_lock:
                            _fx = _livestream_finalizers.get(_rjid)
                            if _fx is not None and not _fx.get("done", threading.Event()).is_set():
                                continue
                    except Exception:
                        pass
                    _fx_done = threading.Event()
                    _fx_box = {"result": ""}

                    def _fx_run(_flv=_rflv, _box=_fx_box, _ev=_fx_done):
                        try:
                            _box["result"] = _remux_flv_to_mp4(_flv)
                        except Exception as _rx:
                            log.warning("[Livestream] Job remux ngoai lock that bai: %s", _rx)
                            _box["result"] = ""
                        finally:
                            _ev.set()

                    _fx_thread = threading.Thread(target=_fx_run, daemon=True)
                    try:
                        with _livestream_lock:
                            _livestream_finalizers[_rjid] = {
                                "thread": _fx_thread, "done": _fx_done,
                                "result": _fx_box, "started": time.time(),
                                "generation": _rgen,
                            }
                    except Exception:
                        pass
                    _fx_thread.start()
                    _fx_done.wait(timeout=600)
                    try:
                        _mp4 = _fx_box.get("result", "")
                    except Exception:
                        _mp4 = ""
                    try:
                        with _livestream_lock:
                            _ri = _livestream_jobs.get(_rjid)
                            if _ri is None:
                                continue
                            if _ri.get("_generation", 0) != _rgen:
                                continue
                            # FIX-REVIEW-24/09-#10: commit remux gan trang thai
                            # cuoi: thanh cong + playable -> finished/SUCCESS;
                            # that bai/.broken -> error (khong giu finished cu).
                            if _mp4:
                                try:
                                    _ok_play = _livestream_video_is_playable(_mp4)
                                except Exception:
                                    _ok_play = False
                                if _ok_play:
                                    _ri["output_file"] = os.path.basename(_mp4)
                                    try:
                                        _ri["file_size"] = os.path.getsize(_mp4)
                                    except Exception:
                                        pass
                                    _ri["direct_output_path"] = _mp4
                                    _ri["status"] = "finished"
                                    _ri["finished_at"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                                    log.info("[Livestream] Job %s: remux FLV -> MP4 OK (%s)",
                                             _rjid, os.path.basename(_mp4))
                                    _log_livestream_event(
                                        "SUCCESS", _rjid, _ri,
                                        "Đã lưu thành công (%s)." % format_bytes(int(_ri.get("file_size", 0) or 0)),
                                        "finished")
                                else:
                                    _ri["status"] = "error"
                                    _ri["error_reason"] = "Tep MP4 sau remux khong playable."
                                    _ri["finished_at"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                            else:
                                _broken = _rflv + ".broken"
                                if os.path.exists(_broken):
                                    _ri["output_file"] = os.path.basename(_broken)
                                    try:
                                        _ri["file_size"] = os.path.getsize(_broken)
                                    except Exception:
                                        pass
                                elif os.path.exists(_rflv):
                                    _ri["output_file"] = os.path.basename(_rflv)
                                    try:
                                        _ri["file_size"] = os.path.getsize(_rflv)
                                    except Exception:
                                        pass
                                    _ri["error_reason"] = "Chua chuyen duoc sang MP4, da giu nguyen tep goc."
                                else:
                                    _ri["error_reason"] = "Tep nguon da bien mat truoc khi remux hoan tat."
                                _ri["status"] = "error"
                                _ri["finished_at"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                            _ri.pop("_remux_pending", None)
                            _ri.pop("_remux_generation", None)
                    except Exception as _rc:
                        log.warning("[Livestream] Job %s: commit remux that bai: %s", _rjid, _rc)
            except Exception as _ro:
                log.warning("[Livestream] Xu ly remux ngoai lock loi: %s", _ro)
            try:
                with _ytdlp_lock:
                    _ydl_dirs = set()
                    for jid, info in list(_ytdlp_jobs.items()):
                        pid = info.get("pid")
                        is_running = False
                        try:
                            os.kill(pid, 0)
                            is_running = True
                        except Exception:
                            pass
                        if not is_running:
                            _cleanup_job_tmp(info.get("tmp_dir", ""))
                            _ydl_dirs.add(info.get("folder", ""))
                            del _ytdlp_jobs[jid]
                        else:
                            ytdlp_active = True
                    if _ydl_dirs:
                        _index_mutated_directories([d for d in _ydl_dirs if d])
            except NameError:
                ytdlp_active = False
            _set_thumbnail_auto_block("livestream", active)
            _set_thumbnail_auto_block("ytdlp", ytdlp_active)
        except Exception as e:
            log.error("[Livestream] Lỗi watchdog: %s", e)
            _add_system_log_once("livestream_watchdog_exception", "ERROR", "Livestream", "Watchdog livestream loi: %s" % normalize_vietnamese_message(str(e))[:240], 120)

def _fan_change_delay(target, applied):
    if target == 0 and applied:
        return 600
    return 1


def _fan_controller_watchdog():
    """Delay stopping the fan until the drive stays cool for ten minutes."""
    last_mode = None
    last_target_percent = None
    target_since_ts = 0.0
    last_applied_percent = None

    try:
        run_cmd(["systemctl", "stop", "fan.service"])
    except Exception:
        pass

    while True:
        try:
            settings = _load_fan_settings()
            mode = str(settings.get("mode", "auto") or "auto").strip().lower()
            now_ts = time.monotonic()

            if mode == "off":
                if last_mode != mode or last_applied_percent != 0:
                    _pwm_apply_off()
                    with _cache_lock:
                        _status_cache['fan_percent'] = 0
                        _status_cache['fan_rpm'] = 0
                        _status_cache['fan_status'] = "Dừng"
                last_mode = mode
                last_target_percent = None
                target_since_ts = 0.0
                last_applied_percent = 0
                time.sleep(1)
                continue

            if mode == "on":
                if last_mode != mode or last_applied_percent != 100:
                    _pwm_apply_on(duty=10000, period=10000)
                    pct_on = 100
                    rpm_on = _fan_rpm_for_percent(pct_on)
                    st_on = _fan_status_for_percent(pct_on)
                    with _cache_lock:
                        _status_cache['fan_percent'] = pct_on
                        _status_cache['fan_rpm'] = rpm_on
                        _status_cache['fan_status'] = st_on
                last_mode = mode
                last_target_percent = 100
                target_since_ts = now_ts
                last_applied_percent = 100
                time.sleep(1)
                continue

            on_temp = float(settings.get("on_temp", FAN_DEFAULT_ON_TEMP))
            off_temp = float(settings.get("off_temp", FAN_DEFAULT_OFF_TEMP))
            if off_temp >= on_temp:
                off_temp = max(28.0, on_temp - 3.0)

            cpu_temp = _fan_temp_value(get_cpu_temp())
            hdd_temp = _fan_temp_value(get_hdd_temp())
            
            # FIX NGUYÊN NHÂN BẬT QUẠT SỚM:
            # Dùng nhiệt độ HDD làm mốc điều khiển chính cho mốc người dùng tùy chỉnh.
            # Tránh việc CPU (thường cao hơn HDD 5-10 độ) làm quạt bật khi HDD mới 42°C.
            if hdd_temp > 0:
                control_temp = hdd_temp
            else:
                # Khi HDD tạm thời chưa có giá trị, không so sánh CPU (thường 45-50°C)
                # trực tiếp với on_temp của HDD (thường 42°C) để tránh nháy quạt giật cục.
                control_temp = max(0.0, cpu_temp - 15.0)

            force_hot = cpu_temp >= FAN_CPU_FORCE_ON_TEMP or (hdd_temp > 0 and hdd_temp >= FAN_HDD_FORCE_ON_TEMP)
            if force_hot:
                target_percent = 100
            elif mode == "custom":
                # Logic Chế độ Tùy Chỉnh (Custom Mode):
                # - Đến mốc trên (>= on_temp) mới BẬT quạt.
                # - Đến mốc dưới (<= off_temp) mới DỪNG quạt.
                # - Ở giữa khoảng (off_temp < temp < on_temp): nếu quạt đang tắt -> KHÔNG CHẠY (0%).
                if control_temp >= on_temp + 10.0:
                    target_percent = 100
                elif control_temp >= on_temp + 5.0:
                    target_percent = 60
                elif control_temp >= on_temp:
                    target_percent = 30
                elif control_temp <= off_temp:
                    target_percent = 0
                else:
                    if last_applied_percent in (0, None):
                        target_percent = 0  # Chưa chạm mốc trên -> KHÔNG CHẠY
                    else:
                        target_percent = last_applied_percent  # Đang chạy -> giữ nguyên cho tới khi <= off_temp
            else:
                # Logic Chế độ Tự động (Auto Mode)
                if control_temp >= on_temp + 10.0:
                    target_percent = 100
                elif control_temp >= on_temp + 5.0:
                    target_percent = 60
                elif control_temp >= on_temp:
                    target_percent = 30
                elif control_temp <= off_temp:
                    target_percent = 0
                else:
                    if last_applied_percent in (0, None):
                        target_percent = 0
                    else:
                        target_percent = min(last_applied_percent, 30)

            if mode != last_mode:
                last_target_percent = None
                target_since_ts = 0.0
                last_mode = mode

            if target_percent != last_target_percent:
                last_target_percent = target_percent
                target_since_ts = now_ts
                log.info("[FanWatchdog] Đổi quạt sang %s%% (mode=%s, MaxTemp=%.1fC [CPU %.1fC, HDD %.1fC], on=%.1f, off=%.1f)",
                         target_percent, mode, control_temp, cpu_temp, hdd_temp, on_temp, off_temp)

            if last_applied_percent is not None and target_percent != last_applied_percent and not force_hot:
                if now_ts - target_since_ts < _fan_change_delay(target_percent, last_applied_percent):
                    time.sleep(1)
                    continue

            if target_percent == last_applied_percent:
                time.sleep(1)
                continue

            _pwm_apply_level(target_percent)
            last_applied_percent = target_percent

            # RULE 11: Cập nhật _status_cache thời gian thực trong khối _cache_lock
            pct_snap = target_percent
            rpm_snap = _fan_rpm_for_percent(pct_snap)
            st_snap = _fan_status_for_percent(pct_snap)
            with _cache_lock:
                _status_cache['fan_percent'] = pct_snap
                _status_cache['fan_rpm'] = rpm_snap
                _status_cache['fan_status'] = st_snap
        except Exception as e:
            log.error("[FanWatchdog] Lỗi: %s", e)
        time.sleep(1)
# FIX: Khoi phuc trạng thái quat sau reboot. Kernel PWM driver mac dinh
# enable=1 -> 5V luon co o cong ra quat ngay khi NAS bat nguon. Đọc lai
# /opt/fan_custom.json, neu mode=off thi ngat PWM ngay tu dau de trảnh
# truong hop "vua bat nguon quat da chay du user da chon Tat tu lan truoc".
def _restore_fan_state_on_boot():
    try:
        settings = _load_fan_settings()
        mode = settings.get("mode", "auto")
        if mode == "off":
            run_cmd(["systemctl", "stop", "fan.service"])
            _pwm_apply_off()
            log.info("[Fan] Kh?i ph?c tr?ng th?i T?T (c?t 5V) t? /opt/fan_custom.json")
        elif mode == "on":
            run_cmd(["systemctl", "stop", "fan.service"])
            _pwm_apply_on(duty=10000, period=10000)
            log.info("[Fan] Kh?i ph?c tr?ng th?i B?T 100%% t? /opt/fan_custom.json")
        elif mode == "custom":
            run_cmd(["systemctl", "stop", "fan.service"])
            _pwm_apply_off()
            log.info("[Fan] Khởi phục trạng thái TÙY CHỈNH -> tắt quạt ban đầu, watchdog sẽ đo nhiệt độ để bật khi cần")
        else:
            run_cmd(["systemctl", "stop", "fan.service"])
            _pwm_apply_off()
            log.info("[Fan] Khởi phục trạng thái TỰ ĐỘNG -> tắt quạt ban đầu, watchdog sẽ đo nhiệt độ để bật khi cần")
    except Exception as e:
        log.warning("[Fan] Kh?ng kh?i ph?c ???c tr?ng th?i: %s", e)
if __name__ == "__main__":
    _restore_fan_state_on_boot()
    # Khoi dong watchdog thread
    threading.Thread(target=_fan_controller_watchdog, daemon=True).start()
    # FIX-AUDIT-L5: reconcile registry cu truoc khi watchdog nhan tac vu moi.
    _livestream_reconcile_after_restart()
    threading.Thread(target=_livestream_watchdog, daemon=True).start()

_TIKTOK_WATCH_FILE = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "tiktok_live_watch.json")
_tiktok_watch_lock = threading.Lock()
_tiktok_watch_state = {
    "users": [],
    "exclude_enabled": False,
    "exclude_start": "23:00",
    "exclude_end": "07:00",
    "poll_interval": 60
}
_tiktok_watch_wake = threading.Event()
_tiktok_watch_runtime = {
    "running": False,
    "started_at": "",
    "last_tick": "",
    "last_summary": "",
    "last_error": "",
    "loop_count": 0,
}

def _tiktok_watch_interval():
    # SPEC_LIVESTREAM_LIMITS §3.1: base default doc dong qua _get_tiktok_watch_s()
    # (env NAS_TIKTOK_WATCH_S > conf > 60), state poll_interval (user setting)
    # override neu co. Clamp 15-300 nhu cu.
    try:
        try:
            _dyn_default = int(globals().get("_get_tiktok_watch_s", lambda: 60)())
        except Exception:
            _dyn_default = 60
        _raw = _tiktok_watch_state.get("poll_interval", _dyn_default)
        return max(15, min(300, int(_raw)))
    except Exception:
        return 60

def _normalize_tiktok_watch_user_entry(user):
    if not isinstance(user, dict):
        user = {"username": str(user or "")}
    username = _normalize_tiktok_username(user.get("username", ""))
    return {
        "username": username,
        "status": user.get("status", "watching") or "watching",
        "last_check": user.get("last_check", "") or "",
        "last_live": user.get("last_live", "") or "",
        "last_live_verified": bool(user.get("last_live_verified", False)),
        "last_error": normalize_vietnamese_message(user.get("last_error", "") or ""),
        "job_id": user.get("job_id", "") or "",
        "live_session_recorded": bool(user.get("live_session_recorded", False)),
        "live_session_job_id": user.get("live_session_job_id", "") or "",
        "live_session_started": user.get("live_session_started", "") or "",
        "live_session_last_live": user.get("live_session_last_live", "") or "",
        "offline_confirm_count": int(user.get("offline_confirm_count", 0) or 0),
        "reconnect_count": int(user.get("reconnect_count", 0) or 0),
        "reconnect_attempt_ts": float(user.get("reconnect_attempt_ts", 0) or 0),
    }

def _load_tiktok_watch_state():
    global _tiktok_watch_state
    try:
        # FIX: Load tu file mới nh?t giua primary (HDD) va mirror (eMMC).
        # Neu HDD bi RO trong khi user thay doi state -> mirror moi hon ->
        # phai dùng mirror khi reboot, neu khong se mat thay doi.
        candidates = []
        for path in (_TIKTOK_WATCH_FILE, _TIKTOK_WATCH_MIRROR):
            try:
                if os.path.exists(path):
                    candidates.append((os.path.getmtime(path), path))
            except Exception:
                pass
        candidates.sort(reverse=True)  # newest first
        for _mtime, path in candidates:
            try:
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                if isinstance(data, dict):
                    _tiktok_watch_state.update(data)
                    log.info("[TikTokWatch] Đã tải trạng thái từ %s (mtime=%s)", path, _mtime)
                    break
            except Exception as e:
                log.warning("[TikTokWatch] Skip file %s: %s", path, e)
        users = []
        seen = set()
        for raw_user in _tiktok_watch_state.get("users", []):
            user = _normalize_tiktok_watch_user_entry(raw_user)
            username_lc = user.get("username", "").lower()
            if not username_lc or username_lc in seen:
                continue
            seen.add(username_lc)
            users.append(user)
        _tiktok_watch_state["users"] = users
        _tiktok_watch_state["poll_interval"] = _tiktok_watch_interval()
    except Exception as e:
        log.error("[TikTokWatch] Lỗi tải trạng thái: %s", e)

# FIX: Mirror state vao eMMC root FS de khong mat khi HDD bi RO/corrupt.
# Primary: WEBDAV_FILE_ROOT/.nas_meta/tiktok_live_watch.json (HDD)
# Mirror : /etc/nas/state/tiktok_live_watch.json              (eMMC, robust)
# Save tr? v? True neu CO IT NHAT 1 noi ghi thảnh cầng. Load l?y file moi
# nhat theo mtime giua hai noi.
_TIKTOK_WATCH_MIRROR = "/etc/nas/state/tiktok_live_watch.json"

def _save_tiktok_watch_state():
    primary_ok = False
    mirror_ok = False
    payload = json.dumps(_tiktok_watch_state, ensure_ascii=False)
    # Primary (HDD) disabled to reduce I/O
    primary_ok = True
    # Mirror (eMMC, luon ghi de state khong mat khi HDD chet)
    try:
        os.makedirs(os.path.dirname(_TIKTOK_WATCH_MIRROR), exist_ok=True)
        tmp = _TIKTOK_WATCH_MIRROR + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(payload)
        os.replace(tmp, _TIKTOK_WATCH_MIRROR)
        mirror_ok = True
    except Exception as e:
        log.error("[TikTokWatch] Không lưu được mirror (eMMC): %s", e)
    if not primary_ok and not mirror_ok:
        log.error("[TikTokWatch] LUU THAT BAI O CA HAI NOI — state se mat khi reboot")
    return primary_ok or mirror_ok

def _normalize_tiktok_username(username):
    username = (username or "").strip()
    if username.startswith("@"):
        username = username[1:]
    username = username.split("/")[0].split("?")[0].strip()
    return "".join(ch for ch in username if ch.isalnum() or ch in "._-")[:64]

# Cache trạng thái cookies TikTok de trảnh hit TikTok mới chu k? watchdog.
_tiktok_cookies_cache = {
    "status": "unknown",   # missing / expired / expiring_soon / revoked / valid / unknown
    "message": "",         # mo ta ng??i dùng doc
    "checked_at": 0.0,     # epoch lan check gần nh?t
    "file_mtime": 0.0,     # mtime cua cookies.txt luc check de phat hien file moi
    "expiry": 0,           # epoch het han sessionid (0 = khong ro)
    "expiry_str": "",      # ngay het han dang doc duoc
    "days_left": -1.0,     # so ngay con lai (-1 = khong ro)
}
_TIKTOK_COOKIES_CHECK_INTERVAL = 600  # 10 phut moi lan goi mang den TikTok
_TIKTOK_COOKIES_EXPIRY_WARN_SEC = 3 * 86400  # báo trước 3 ngày khi sắp hết hạn

_COOKIE_ACCESS_AUDIT_LOG = os.path.join(SYSTEM_DB_DIR, "cookie_access.audit.log")
_cookie_audit_lock = threading.Lock()

_COOKIE_ALLOWED_DIRS = None  # duoc cap nhat lan dau trong _ensure_cookie_guard()

def _ensure_cookie_guard():
    """Khoi tao danh sach cac thuoc duoc phep chua cookie (chi WEBDAV_FILE_ROOT va con cua no)."""
    global _COOKIE_ALLOWED_DIRS
    if _COOKIE_ALLOWED_DIRS is not None:
        return
    allowed = set()
    root = os.path.realpath(WEBDAV_FILE_ROOT or "")
    if root and os.path.isdir(root):
        allowed.add(root)
    # Them /etc/nas neu co (cai dat cookies boi admin qua install-pending-cookies.sh)
    etc_nas = "/etc/nas"
    if os.path.isdir(etc_nas):
        allowed.add(os.path.realpath(etc_nas))
    _COOKIE_ALLOWED_DIRS = allowed


def _cookie_path_is_confined(path):
    """Tra ve True neu `path` nam trong mot thuoc duoc phep va khong co path traversal/symlink thoat."""
    if not path or not isinstance(path, str):
        return False
    _ensure_cookie_guard()
    try:
        real = os.path.realpath(path)
    except Exception:
        return False
    for base in (_COOKIE_ALLOWED_DIRS or set()):
        base = os.path.realpath(base)
        if real == base or real.startswith(base + os.sep):
            return True
    return False


def _is_valid_netscape_cookies_file(path):
    """Kiem tra file cookies.txt co dang Netscape hop luon (co it nhat mot dong cookie hop le)."""
    if not _cookie_path_is_confined(path):
        return False
    try:
        if not os.path.isfile(path) or not os.access(path, os.R_OK):
            return False
        # Gioi han kich thuoc de chua doc file tro lon bi tao boi attacker
        if os.path.getsize(path) > 4 * 1024 * 1024:
            return False
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            valid_lines = 0
            for line in f:
                line = line.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) >= 7:
                    valid_lines += 1
                    if valid_lines >= 1:
                        return True
        return False
    except Exception:
        return False


def _audit_cookie_access(action, path, ok, reason=""):
    """Ghi nhat ky tien trinh truy cap cookie file de phat hien tam can (stealer-like behavior)."""
    try:
        log_dir = os.path.dirname(_COOKIE_ACCESS_AUDIT_LOG)
        with _cookie_audit_lock:
            if log_dir and not os.path.exists(log_dir):
                os.makedirs(log_dir, exist_ok=True)
            with open(_COOKIE_ACCESS_AUDIT_LOG, "a", encoding="utf-8") as f:
                f.write("%s\t%s\t%s\t%s\t%s\n" % (
                    time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
                    action,
                    path or "",
                    "OK" if ok else "DENY",
                    reason,
                ))
    except Exception:
        pass


def _tiktok_cookies_path():
    return os.path.join(WEBDAV_FILE_ROOT, "cookies.txt")

def _parse_cookies_sessionid_expiry(path):
    """Đọc cookies.txt (Netscape format), trả về (epoch_expiry, sessionid_value) cho sessionid TikTok.
    Chỉ đọc file trong phạm vi được phép (_cookie_path_is_confined) và ghi nhật ký kiểm toán."""
    _ensure_cookie_guard()
    ok = _cookie_path_is_confined(path)
    _audit_cookie_access("read", path, ok, "parse_sessionid_expiry" if ok else "path_outside_allowed_dirs")
    if not ok:
        return 0, ""
    expiry = 0
    sessionid = ""
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if line.startswith("#") or not line.strip():
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 7:
                    continue
                domain, _flag, _p, _secure, exp, name, value = parts[:7]
                if "tiktok.com" not in domain:
                    continue
                if name in ("sessionid", "sid_guard", "sid_tt"):
                    try:
                        e = int(exp)
                    except Exception:
                        e = 0
                    if e > expiry:
                        expiry = e
                    if name == "sessionid" and value:
                        sessionid = value
    except Exception:
        pass
    return expiry, sessionid

def _ping_tiktok_cookies(path):
    """Goi 1 endpoint can dang nhap; tr? v? (is_valid, detail)."""
    curl_cmd = [
        "curl", "-4", "-s", "-L",
        "--max-time", "12",
        "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "-H", "Referer: https://www.tiktok.com/",
        "-H", "Accept: application/json, text/plain, */*",
        "-b", path,
        "-w", "\n__HTTP__:%{http_code}",
        # passport_logged_out endpoint tr? v? cau truc { user: { uid, sec_uid }, ... } khi co session
        "https://www.tiktok.com/passport/web/account/info/?aid=1988",
    ]
    try:
        proc = subprocess.run(curl_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        raw = (proc.stdout or b"").decode("utf-8", errors="ignore")
        idx = raw.rfind("\n__HTTP__:")
        body = raw[:idx] if idx >= 0 else raw
        http_code = raw[idx + len("\n__HTTP__:") :].strip() if idx >= 0 else "?"
        if "\"uid\"" in body or "\"sec_uid\"" in body or "\"user_id\"" in body:
            return True, "HTTP %s OK" % http_code
        if "\"status_code\":8" in body or "not login" in body.lower() or "please log in" in body.lower():
            return False, "TikTok trả về trạng thái 'chưa đăng nhập' — cookies đã hết hạn"
        if http_code in ("401", "403"):
            return False, "TikTok từ chối (HTTP %s) — cookies đã bị thu hồi" % http_code
        # Không x?c ?ảnh được — coi nhu valid de khong false-alarm
        return True, "HTTP %s (không rõ)" % http_code
    except Exception as e:
        return True, "Không kiểm tra được (%s)" % str(e)[:80]

def _check_cookies_status(force=False):
    """Trả về dict { status, message, checked_at, expiry, expiry_str, days_left } của cookies TikTok.
    Sử dụng cache 10 phút trừ khi force=True hoặc file vừa thay đổi.
    Trạng thái: missing / expired / expiring_soon (còn <=3 ngày) / revoked / valid / unknown.
    Tự động ghi system_logs (cooldown) khi phát hiện missing/expired/expiring_soon/revoked
    và khi phục hồi về valid — để user biết ngay cả khi không mở app (xem /api/system_logs)."""
    path = _tiktok_cookies_path()
    now = time.time()
    if not os.path.exists(path):
        _tiktok_cookies_cache.update({"status": "missing", "message": "Chưa có cookies.txt ở thư mục gốc WebDAV", "checked_at": now, "file_mtime": 0, "expiry": 0, "expiry_str": "", "days_left": -1.0})
        _add_system_log_once("tiktok_cookies_missing", "ERROR", "TikTokWatch", "Chưa có cookies.txt ở thư mục gốc WebDAV — watcher không phát hiện được live.", 86400)
        return dict(_tiktok_cookies_cache)
    try:
        mtime = os.path.getmtime(path)
    except Exception:
        mtime = 0
    file_changed = mtime != _tiktok_cookies_cache.get("file_mtime", 0)
    if (not force) and (not file_changed) and (now - _tiktok_cookies_cache.get("checked_at", 0) < _TIKTOK_COOKIES_CHECK_INTERVAL):
        return dict(_tiktok_cookies_cache)
    expiry, sessionid = _parse_cookies_sessionid_expiry(path)
    expiry_str = ""
    days_left = -1.0
    if expiry:
        try:
            expiry_str = datetime.datetime.fromtimestamp(expiry).strftime("%d/%m/%Y %H:%M")
            days_left = round((expiry - now) / 86400.0, 1)
        except Exception:
            pass
    if not sessionid:
        _tiktok_cookies_cache.update({"status": "missing", "message": "cookies.txt thiếu sessionid TikTok", "checked_at": now, "file_mtime": mtime, "expiry": expiry or 0, "expiry_str": expiry_str, "days_left": days_left})
        _add_system_log_once("tiktok_cookies_no_sessionid", "ERROR", "TikTokWatch", "cookies.txt thiếu sessionid TikTok — xuất lại file đầy đủ.", 86400)
        return dict(_tiktok_cookies_cache)
    if expiry and expiry < now:
        _tiktok_cookies_cache.update({"status": "expired", "message": "Cookie sessionid hết hạn lúc %s. Vui lòng xuất lại cookies.txt." % (expiry_str or "?"), "checked_at": now, "file_mtime": mtime, "expiry": expiry, "expiry_str": expiry_str, "days_left": days_left})
        _add_system_log_once("tiktok_cookies_expired", "ERROR", "TikTokWatch", "Cookies TikTok HẾT HẠN lúc %s — watcher mù, không ghi được live. Xuất lại cookies.txt." % (expiry_str or "?"), 86400)
        return dict(_tiktok_cookies_cache)
    ok, detail = _ping_tiktok_cookies(path)
    if ok:
        if expiry and (expiry - now) <= _TIKTOK_COOKIES_EXPIRY_WARN_SEC:
            _tiktok_cookies_cache.update({"status": "expiring_soon", "message": "Cookies TikTok sắp hết hạn (%s, còn ~%s ngày). Hãy xuất lại cookies.txt trước khi hết." % (expiry_str or "?", days_left), "checked_at": now, "file_mtime": mtime, "expiry": expiry, "expiry_str": expiry_str, "days_left": days_left})
            _add_system_log_once("tiktok_cookies_expiring", "WARNING", "TikTokWatch", "Cookies TikTok SẮP HẾT HẠN %s (còn ~%s ngày) — xuất lại cookies.txt trước khi watcher mù." % (expiry_str or "?", days_left), 86400)
        else:
            prev = _tiktok_cookies_cache.get("status", "")
            _tiktok_cookies_cache.update({"status": "valid", "message": detail, "checked_at": now, "file_mtime": mtime, "expiry": expiry or 0, "expiry_str": expiry_str, "days_left": days_left})
            if prev in ("missing", "expired", "expiring_soon", "revoked"):
                _add_system_log_once("tiktok_cookies_recovered", "INFO", "TikTokWatch", "Cookies TikTok đã sống trở lại (%s)." % detail, 3600)
    else:
        _tiktok_cookies_cache.update({"status": "revoked", "message": detail, "checked_at": now, "file_mtime": mtime, "expiry": expiry or 0, "expiry_str": expiry_str, "days_left": days_left})
        _add_system_log_once("tiktok_cookies_revoked", "ERROR", "TikTokWatch", "Cookies TikTok BỊ THU HỒI / logout (%s) — watcher mù. Xuất lại cookies.txt." % detail, 86400)
    return dict(_tiktok_cookies_cache)

def _is_tiktok_watch_excluded(now_dt=None):
    now_dt = now_dt or datetime.datetime.now()
    if not _tiktok_watch_state.get("exclude_enabled", False):
        return False
    try:
        start_h, start_m = [int(x) for x in _tiktok_watch_state.get("exclude_start", "23:00").split(":")[:2]]
        end_h, end_m = [int(x) for x in _tiktok_watch_state.get("exclude_end", "07:00").split(":")[:2]]
        now_minutes = now_dt.hour * 60 + now_dt.minute
        start_minutes = start_h * 60 + start_m
        end_minutes = end_h * 60 + end_m
        if start_minutes <= end_minutes:
            return start_minutes <= now_minutes < end_minutes
        return now_minutes >= start_minutes or now_minutes < end_minutes
    except Exception:
        return False

def _tiktok_watch_mark_session_recorded(user, job_id, now_str):
    user["live_session_recorded"] = True
    user["live_session_job_id"] = job_id or user.get("live_session_job_id", "") or user.get("job_id", "") or ""
    user["live_session_started"] = user.get("live_session_started", "") or now_str
    user["live_session_last_live"] = now_str
    user["last_live"] = now_str
    user["last_live_verified"] = True
    user["offline_confirm_count"] = 0

def _tiktok_watch_clear_session(user):
    user["live_session_recorded"] = False
    user["live_session_job_id"] = ""
    user["live_session_started"] = ""
    user["live_session_last_live"] = ""
    user["job_id"] = ""
    user["offline_confirm_count"] = 0

def _tiktok_watch_user_has_recording(username):
    uname = username.lower()
    target_url = "@%s/live" % uname
    recording_key = _livestream_recording_key("tiktok", "", uname)
    now = time.time()
    with _livestream_lock:
        jid, _info = _livestream_active_job_for_key_locked(recording_key)
        if jid:
            return jid
        for jid, info in _livestream_jobs.items():
            is_match = (info.get("recording_key", "") == recording_key or
                        info.get("watch_username", "").lower() == uname or
                        target_url in info.get("url", "").lower())
            if not is_match:
                continue
            st = info.get("status", "")
            if st == "recording":
                alive = False
                try:
                    os.kill(info.get("pid"), 0)
                    alive = True
                except Exception:
                    pass
                if alive:
                    return jid
                continue
            if st == "finished":
                finished_str = info.get("finished_at", "")
                if finished_str:
                    try:
                        ft = datetime.datetime.strptime(finished_str, "%d/%m/%Y %H:%M:%S").timestamp()
                        info["_tiktok_finished_ts"] = ft
                    except Exception:
                        pass
    return ""


def _extract_tiktok_live_flv_urls(html):
    flv_urls = []
    for pat in (
        r'\\"flv\\":\\"(https://[^"\\]+)',
        r'\\"origin\\":\{[^}]*\\"flv\\":\\"(https://[^"\\]+)',
        r'"flv":"(https://[^"\\]+)',
        r'"origin":\{[^}]*"flv":"(https://[^"\\]+)',
        r'https:\\/\\/[^"\\]{1,2000}?\.flv[^"\\]{0,2000}',
        r'https://[^"\\<>\s]{1,2000}?\.flv[^"\\<>\s]{0,2000}',
    ):
        for u in _re_module.findall(pat, html or ""):
            u = u.replace("\\u0026", "&").replace("\\/", "/")
            if "only_audio=1" in u:
                continue
            if u not in flv_urls:
                flv_urls.append(u)
    def _flv_rank(u):
        if "_hd.flv" in u:
            return 0
        if "_ld.flv" in u:
            return 1
        if "_sd.flv" in u:
            return 2
        return 9
    return sorted(flv_urls, key=_flv_rank)

def _tiktok_sigi_object_slice(html):
    """Return the JSON object (balanced braces) starting right after a
    SIGI_STATE marker, or '' if no marker / no object found.

    FIX-P2-3: giới hạn fallback trong CHÍNH object JSON của phòng đích,
    không quét 50k ký tự raw hay toàn bộ HTML. Nếu không có marker (HTML
    lỗi/truncated) thì trả '' để không leak status của phòng khác nằm ngoài
    object — trước đây slice 50k từ marker hoặc quét toàn HTML có thể trả
    'live' từ một <script> khác chứa liveRoomStatus của phòng không liên quan.
    """
    if not html:
        return ""
    # TikTok nhúng state trong thẻ <script id="SIGI_STATE" type="application/json">
    # hoặc <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
    # Lưu ý: SIGI_STATE chứa LiveRoom và stream_data đầy đủ nên phải ưu tiên tìm trước.
    m = _re_module.search(r'<script[^>]*id=["\']SIGI_STATE["\'][^>]*>(.*?)</script>', html, _re_module.DOTALL)
    if m:
        content = m.group(1).strip()
        if content.startswith("{") and content.endswith("}"):
            return content

    m2 = _re_module.search(r'<script[^>]*id=["\']__UNIVERSAL_DATA_FOR_REHYDRATION__["\'][^>]*>(.*?)</script>', html, _re_module.DOTALL)
    if m2:
        content = m2.group(1).strip()
        if content.startswith("{") and content.endswith("}"):
            return content

    lowered = html.lower()
    # FIX-AUDIT-L6: SIGI_STATE=null; roi object script khac co the bi nhan lam
    # state (html.find('{',idx) nhay qua null). Duyet tung marker: neu giua
    # marker va '{' dau tien chi co khoang trang/null/;/= thi marker do rong —
    # bo qua, thu marker tiep theo. Khong nhay coc sang object khac.
    for marker in ("window.__SIGI_STATE__=", "window.SIGI_STATE=", "SIGI_STATE="):
        search_from = 0
        while True:
            idx = lowered.find(marker.lower(), search_from)
            if idx < 0:
                break
            start = html.find("{", idx)
            if start < 0:
                break
            gap = html[idx + len(marker):start].strip()
            if gap and gap not in ("=", ":"):
                # Co noi dung khac giua marker va '{' (vd null;) -> marker rong.
                search_from = start + 1
                continue
            depth = 0
            i = start
            n = len(html)
            while i < n:
                c = html[i]
                if c == '"':
                    i += 1
                    while i < n:
                        if html[i] == "\\":
                            i += 2
                            continue
                        if html[i] == '"':
                            i += 1
                            break
                        i += 1
                    continue
                if c == "'":
                    i += 1
                    while i < n and html[i] != "'":
                        i += 1
                    i += 1
                    continue
                if c == "{":
                    depth += 1
                elif c == "}":
                    depth -= 1
                    if depth == 0:
                        return html[start:i + 1]
                i += 1
            return ""
    return ""


def _parse_tiktok_live_room_state(html):
    """Extract live-room status from TikTok HTML embedded JSON state.

    TikTok no longer inlines raw .flv/.m3u8 URLs in the live page HTML; the
    authoritative signal lives in the embedded app state (SIGI_STATE / webapp
    sharing / __INITIAL_STATE__) as a LiveRoom object with liveRoomStatus
    (1=live, 2=live, 3=replay/paused, 4=ended) and roomId.
    Returns (is_live, room_id, status, detail).
    """
    if not html:
        return False, "", -1, "empty html"
    lowered = html.lower()
    try:
        # FIX-REVIEW-193369e-#12: CHI helper slice an toan — xoa loop cu.
        # Ban cu giu loop find('{') tu do sau helper: SIGI_STATE=null; roi
        # object script khac van bi lay nham. Mot parser cau truc duy nhat.
        _slice = _tiktok_sigi_object_slice(html)
        if _slice:
            try:
                state = json.loads(_slice)
                room = _dig_tiktok_room(state)
                if room is not None:
                    return _eval_tiktok_room(room)
                _lr = state.get("LiveRoom") if isinstance(state, dict) else None
                if isinstance(_lr, dict) and "liveRoomStatus" in _lr:
                    return _eval_tiktok_room(_lr)
            except Exception:
                pass
        # FIX-P2-3: fallback chi doc status cua DUNG phong dich (LiveRoom),
        # KHONG quet regex liveRoomStatus trong toan bo object (se lay status
        # cua room khac nhu extra.roomId=9999 dang live -> false-positive).
        # Chi parse slice thanh JSON va doc truc tiep LiveRoom.liveRoomStatus
        # / LiveRoom.roomId cua target room. Neu khong co -> khong leak.
        slice_txt = _tiktok_sigi_object_slice(html)
        if slice_txt:
            try:
                _st = json.loads(slice_txt)
                if isinstance(_st, dict):
                    _lr = _st.get("LiveRoom")
                    if isinstance(_lr, dict) and "liveRoomStatus" in _lr:
                        return _eval_tiktok_room(_lr)
            except Exception:
                pass
            # Cuoi cung: chi chap nhan liveRoomStatus nam NGAY SAU "LiveRoom":
            # de tranh lay status cua room khac (extra/ShareModule...).
            m = _re_module.search(r'"LiveRoom"\s*:\s*\{[^}]*"liveRoomStatus"\s*:\s*(\d+)', slice_txt)
            if m:
                status = int(m.group(1))
                rid = ""
                rm = _re_module.search(r'"LiveRoom"\s*:\s*\{[^}]*"roomId"\s*:\s*["\']?(\d+)', slice_txt)
                if rm:
                    rid = rm.group(1)
                return _eval_tiktok_status(status, rid)
        return False, "", -1, "no embedded live room state"
    except Exception as e:
        return False, "", -1, "parse error: %s" % str(e)[:120]


def _dig_tiktok_room(state):
    cand = state.get("LiveRoom") if isinstance(state, dict) else None
    if isinstance(cand, dict):
        if "liveRoom" in cand:
            return cand["liveRoom"]
        if "liveRoomUserInfo" in cand and isinstance(cand["liveRoomUserInfo"], dict):
            return cand["liveRoomUserInfo"].get("liveRoom")
    if isinstance(state, dict) and ("roomId" in state or "liveRoomStatus" in state or "status" in state):
        return state
    return None


def _eval_tiktok_room(room):
    status = -1
    try:
        status = int(room.get("liveRoomStatus", room.get("status", -1)))
    except Exception:
        status = -1
    rid = str(room.get("roomId", room.get("id", "")) or "")
    if not rid:
        try:
            sd = room.get("streamData", {}).get("pull_data", {}).get("stream_data", "")
            if sd and isinstance(sd, str):
                sd_obj = json.loads(sd)
                rid = str(sd_obj.get("common", {}).get("room_id", "") or "")
        except Exception:
            pass
    return _eval_tiktok_status(status, rid)


def _eval_tiktok_status(status, rid):
    # TikTok Webcast status: 1 = LIVE, 2 = LIVE, 3 = REPLAY/PAUSED, 4 = ENDED/OFFLINE
    if status in (1, 2):
        return True, rid, status, ""
    if status == 3:
        return False, rid, status, "replay"
    if status == 4:
        return False, rid, status, "offline"
    return False, rid, status, "unknown status %s" % status





def _extract_tiktok_live_media_urls(html):
    """Extract direct livestream media URLs from TikTok HTML without probing codec.

    Presence of these URLs is a stronger "user is live" signal than yt-dlp simulate,
    which often false-negatives on TikTok. Recording code will validate/remux later.
    """
    return _extract_tiktok_live_media_urls_scoped(html, "")


def _extract_tiktok_live_media_urls_scoped(html, room_id):
    """FIX-REVIEW-24/09-#9: room-bound extraction — chi lay media URL nam trong
    object JSON CAN BANG chua roomId cua phong dich, khong quet toan trang va
    khong slice tho theo ban kinh ky tu (2 phong gan nhau van lan). Voi moi lan
    xuat hien roomId: mo rong ra object JSON can bang nho nhat chua no (brace
    balance, bo qua trong chuoi), chi extract trong object do. Khong room_id:
    fallback toan trang (hanh vi cu) de khong break caller chua co room."""
    if not html:
        return []

    # 1. Trích xuất trực tiếp từ cấu trúc LiveRoom trong SIGI_STATE (chính xác 100%, đúng phòng đích)
    sigi_json = _tiktok_sigi_object_slice(html)
    if sigi_json:
        try:
            state = json.loads(sigi_json)
            lr = _dig_tiktok_room(state)
            if lr and isinstance(lr, dict):
                try:
                    _lr_st = int(lr.get("liveRoomStatus", lr.get("status", -1)))
                except Exception:
                    _lr_st = -1
                if _lr_st in (3, 4):
                    return []
                target_rid = str(lr.get("roomId", lr.get("id", "")) or "")
                sdata = lr.get("streamData", {})
                pdata = sdata.get("pull_data", {}) if isinstance(sdata, dict) else {}
                inner_sd = pdata.get("stream_data", "") if isinstance(pdata, dict) else ""
                urls_found = []
                if inner_sd and isinstance(inner_sd, str):
                    sd_obj = json.loads(inner_sd)
                    sd_rid = str(sd_obj.get("common", {}).get("room_id", "") or "")
                    if not target_rid:
                        target_rid = sd_rid
                    if not room_id or room_id in (target_rid, sd_rid):
                        d = sd_obj.get("data", {})
                        for qual in ("hd", "sd", "ld", "origin"):
                            if qual in d and isinstance(d[qual], dict):
                                flv = d[qual].get("main", {}).get("flv")
                                if flv and "only_audio=1" not in flv and flv not in urls_found:
                                    urls_found.append(flv)
                        for qual, qv in d.items():
                            if isinstance(qv, dict):
                                flv = qv.get("main", {}).get("flv")
                                if flv and "only_audio=1" not in flv and flv not in urls_found:
                                    urls_found.append(flv)
                                hls = qv.get("main", {}).get("hls")
                                if hls and hls not in urls_found:
                                    urls_found.append(hls)
                if not urls_found and (not room_id or room_id == target_rid):
                    _skip_keys = ("liveRoomList", "recommend", "recommendLive", "relatedLive", "otherRooms", "extra")
                    def _walk_lr(node):
                        if isinstance(node, dict):
                            for _k, _v in node.items():
                                if _k in _skip_keys:
                                    continue
                                if isinstance(_v, str) and _v.startswith("https://") and (".flv" in _v or ".m3u8" in _v):
                                    _u = _v.replace("\\u0026", "&").replace("\\/", "/")
                                    if "only_audio=1" not in _u and _u not in urls_found:
                                        urls_found.append(_u)
                                elif isinstance(_v, (dict, list)):
                                    _walk_lr(_v)
                        elif isinstance(node, list):
                            for _item in node:
                                _walk_lr(_item)
                    _walk_lr(lr)
                if urls_found:
                    def _flv_rank(u):
                        if "_hd.flv" in u:
                            return 0
                        if "_ld.flv" in u:
                            return 1
                        if ".m3u8" in u:
                            return 2
                        if "_sd.flv" in u:
                            return 3
                        return 9
                    return sorted(set(urls_found), key=_flv_rank)
        except Exception as e:
            log.warning("[Livestream] Lỗi trích xuất stream_data từ SIGI: %s", e)

    if ".flv" not in html and ".m3u8" not in html:
        return []
    # Khong co room_id -> KHONG scan toan trang (tranh lay media phong khac).
    # Chi scan room_id cu the moi co y nghia room-bound; thieu identity -> [].
    if not room_id:
        return []

    def _balanced_around(pos, text):
        # Tim object JSON BAO TRUM GAN NHAT (innermost) chua vi tri pos:
        # quet LUI tim '{' mo cua object chua pos (depth 0), roi tu do quet
        # TIEN tim '}' dong khop. Bo qua trong "..." (va escape \\). Tra ve
        # object nho nhat chua roomId -> loai nested recommendation/extra
        # cua phong khac.
        try:
            _n = len(text)
            # Lui: tim '{' mo cua object bao quanh pos. Quet trai, '{' la
            # mo object (depth 0 -> chinh la object dich), '}' la dong object
            # cua object con (depth tang). Break tai '{' dau tien o depth 0.
            _depth = 0
            _open = -1
            _j = pos
            while _j >= 0:
                _c = text[_j]
                if _c == '"':
                    _j -= 1
                    while _j >= 0:
                        if text[_j] == "\\":
                            _j -= 2
                            continue
                        if text[_j] == '"':
                            _j -= 1
                            break
                        _j -= 1
                    continue
                if _c == "}":
                    _depth += 1
                elif _c == "{":
                    if _depth == 0:
                        _open = _j
                        break
                    _depth -= 1
                _j -= 1
            if _open < 0:
                return ""
            # Tien: tu sau '{' (depth 1) quet tim '}' dong khop.
            _depth = 1
            _close = -1
            _i = _open + 1
            while _i < _n:
                _c = text[_i]
                if _c == '"':
                    _i += 1
                    while _i < _n:
                        if text[_i] == "\\":
                            _i += 2
                            continue
                        if text[_i] == '"':
                            _i += 1
                            break
                        _i += 1
                    continue
                if _c == "{":
                    _depth += 1
                elif _c == "}":
                    _depth -= 1
                    if _depth == 0:
                        _close = _i
                        break
                _i += 1
            if _close < 0:
                return ""
            return text[_open:_close + 1]
        except Exception:
            return ""

    def _harvest(text):
        _urls = []
        _n = len(text)
        _pos = 0
        while _pos < _n and len(_urls) < 80:
            _idx = text.find("https://", _pos)
            if _idx < 0:
                break
            _end = _idx
            while _end < _n and text[_end] not in ('"', "'", "\\", "<", ">", " ", "\n", "\r", "\t"):
                _end += 1
            _u = text[_idx:_end].replace("\\u0026", "&").replace("\\/", "/")
            if (".flv" in _u or ".m3u8" in _u) and "only_audio=1" not in _u and _u not in _urls:
                _urls.append(_u)
            _pos = max(_end + 1, _idx + 8)
        return _urls

    def _direct_values(obj_text):
        # Chi lay GIA TRI TRUC TIEP (top-level) cua object, bo qua nested dict
        # (recommendation/extra cua phong khac). Parse JSON neu duoc.
        try:
            _parsed = json.loads(obj_text)
            if isinstance(_parsed, dict):
                return " ".join(str(_v) for _v in _parsed.values()
                               if isinstance(_v, (str, int, float)))
        except Exception:
            pass
        return obj_text

    def _rank(u):
        if "_hd.flv" in u:
            return 0
        if "_ld.flv" in u:
            return 1
        if ".m3u8" in u:
            return 2
        if "_sd.flv" in u:
            return 3
        return 9

    texts = [(html or "")[:_tiktok_effective_html_cap()]]
    if room_id:
        try:
            # FIX-REVIEW-P1-#12: chuan hoa whitespace truoc tim kiem roomId.
            # TikTok HTML (va helper test page()) co the co khoang trang sau
            # dau ':' ("roomId": "7001"); thi chuoi needle khong khoang trang
            # se KHONG khop -> bo sot phong dich. Strip whitespace trong phan
            # tim kiem de ca 2 trinh bay deu khop, va object capture/parse
            # tren van ban da strip hoat dong dung.
            # SPEC_LIVESTREAM_LIMITS §3.1: cap HTML doc dong.
            try:
                _html_cap = _tiktok_effective_html_cap()
            except Exception:
                _html_cap = 786432
            _full = _re_module.sub(r"\s+", "", (html or "")[:_html_cap])
            _slices = []
            _start = 0
            # FIX-REVIEW-P1-#12: match room_id nhu mot GIA TRI roomId JSON, khong
            # substring tho. Ban cu _full.find("7001") khop trong "170019" cua
            # phong khac -> lay media phong sai. Tim "roomid" / "roomId" bao quanh
            # boi dau phan cach JSON chinh xac, roi moi lay object BAO TRUM
            # (enclosing) chua roomId o muc cao nhat. Chi lay GIA TRI TRUC TIEP
            # cua object do -> bo qua recommendation/extra nested. Khong object
            # khop -> [].
            _needles = ('"roomid":"%s"' % room_id, '"roomid":%s' % room_id,
                        '"roomId":"%s"' % room_id, '"roomId":%s' % room_id)
            while True:
                _idx = -1
                _matched_len = 0
                for _needle in _needles:
                    _pos = _full.find(_needle, _start)
                    if _pos >= 0 and (_idx < 0 or _pos < _idx):
                        _idx = _pos
                        _matched_len = len(_needle)
                if _idx < 0:
                    break
                # Quet tu SAU needle (tai gia tri roomId), khong tren dau ngoac
                # mo cua key -> tranh str-skip lam lech vi tri bat dau. Luu
                # _matched_len tai cho tim thay (khong dung _needle bi ro ri sau
                # vong for -> sai do dai, quet lech vao giua gia tri).
                _obj = _balanced_around(_idx + _matched_len, _full)
                if _obj:
                    _slices.append((_idx, _obj))
                _start = _idx + 1
                if len(_slices) >= 8:
                    break
            if _slices:
                # Object gan nhat voi roomId cua phong dich (idx nho nhat),
                # duyet tat ca slice khop roomId (bo qua slice da bao status 3/4).
                _slices.sort(key=lambda kv: kv[0])
                texts = [
                    _direct_values(_s[1]) for _s in _slices
                    if '"liveRoomStatus":4' not in _s[1]
                    and '"liveRoomStatus":3' not in _s[1]
                    and '"liveroomstatus":4' not in _s[1].lower()
                ]
            else:
                return []
        except Exception:
            # FIX-AUDIT-BUG1: fail-CLOSED. Ban cu fallback quet toan trang HTML
            # khi extractor throw -> ro URL media cua phong TikTok KHAC vao luong
            # record. Khong co media nao khop room -> tra rong, khong quet lai.
            return []
    urls = []
    for text in texts:
        urls.extend(_harvest(text))
    if urls:
        return sorted(set(urls), key=_rank)
    # FIX-REVIEW-193369e-#12: KHONG fallback toan HTML khi khong thay media
    # target. Ban cu: target live nhung khong co media + recommendation co FLV
    # => tra URL phong khac (ghi nham nguoi). Khong thay trong object phong
    # dich thi tra rong — caller (checker/retry) tu quyet dinh tiep theo bang
    # parser trang thai, khong doan URL.
    return []

def _tiktok_stream_url_seems_live(stream_url, cookies_path="", user_agent=""):
    """Kiểm tra URL stream TikTok con song bang HTTP nh?.

    Watcher không dùng ffprobe de quyet dinh user dang live vi ffmpeg/ffprobe
    3.2 tren NAS co the không Đọc được enhanced FLV/codec moi, gay false-negative.
    Viec co remux được sang MP4 hay khong van do /api/livestream/record xu ly.
    """
    if not stream_url:
        return False, "Thiếu URL stream"
    ua = user_agent or "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    stream_lower = stream_url.lower()
    common = [
        "--http1.1",
        "--max-time", "3",
        "--connect-timeout", "2",
        "-A", ua,
        "-H", "Referer: https://www.tiktok.com/",
    ]
    use_cookies = bool(cookies_path and os.path.exists(cookies_path) and "tiktokcdn" not in stream_lower)

    def _run_probe(extra_args):
        cmd = ["curl", "-4", "-s", "-L"] + common + extra_args + [
            "-o", "/dev/null",
            "-w", "%{http_code}|%{content_type}|%{size_download}",
        ]
        if use_cookies:
            cmd.extend(["-b", cookies_path])
        cmd.append(stream_url)
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        out = (proc.stdout or b"").decode("utf-8", errors="ignore").strip()
        parts = out.split("|")
        http_code = parts[0] if len(parts) > 0 else "0"
        content_type = (parts[1] if len(parts) > 1 else "").lower()
        try:
            size = int(float(parts[2])) if len(parts) > 2 and parts[2] else 0
        except Exception:
            size = 0
        return http_code, content_type, size

    def _content_type_ok(content_type):
        if not content_type:
            return True
        accepted = (
            "video/x-flv",
            "video/flv",
            "flv-application",
            "application/octet-stream",
            "video/mp4",
        )
        return any(token in content_type for token in accepted)

    try:
        http_code, content_type, _size = _run_probe(["-I"])
        if http_code.startswith(("2", "3")) and _content_type_ok(content_type):
            return True, ""
        if http_code in ("401", "403"):
            return False, "TikTok từ chối stream (HTTP %s), hãy kiểm tra cookies.txt" % http_code
        if http_code == "404":
            return False, "URL stream đã hết hạn hoặc live vừa kết thúc"
        if http_code == "429":
            return False, "TikTok giới hạn tốc độ, thử lại ở chu kỳ sau"

        # Mot so CDN khong tra HEAD tot. Tai thu toi da 8s vao /dev/null de xem
        # co byte video thuc su khong, khong ghi file tam xuong HDD.
        http_code, content_type, size = _run_probe([
            "--speed-time", "2",
            "--speed-limit", "128",
        ])
        if not http_code.startswith(("4", "5")) and size >= 256 and _content_type_ok(content_type):
            return True, ""
        if http_code in ("401", "403"):
            return False, "TikTok từ chối stream (HTTP %s), hãy kiểm tra cookies.txt" % http_code
        if http_code == "404":
            return False, "URL stream đã hết hạn hoặc live vừa kết thúc"
        if http_code == "429":
            return False, "TikTok giới hạn tốc độ, thử lại ở chu kỳ sau"
        return False, "URL stream đã hết hạn hoặc không kiểm tra được"
    except Exception as e:
        return False, "Không kiểm tra được URL stream: %s" % normalize_vietnamese_message(str(e))[:100]

def _check_tiktok_user_live(username):
    # yt-dlp --simulate hay bi TikTok bot-block tu IP datacenter/NAS nen tin hieu
    # khong dang tin cay. Dung cung phuong phap nhu /api/livestream/record:
    # curl trang HTML live cua user (vi browser UA + cookies neu co) roi tim
    # pattern FLV trong embedded JSON — neu co thi user dang live.
    live_url = "https://www.tiktok.com/@%s/live" % username
    cookies_path = os.path.join(WEBDAV_FILE_ROOT, "cookies.txt")
    tiktok_user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    # In HTTP status code o cuoi response qua --write-out de phan biet bi block (403/429)
    # voi "page tr? v? nhung khong co stream" (200 nhung empty / not-live).
    sentinel = "\n__HTTP_STATUS__:"
    curl_cmd = [
        "curl", "-4", "-s", "-L",
        "--max-time", "6",
        "--connect-timeout", "4",
        "-A", tiktok_user_agent,
        "-H", "Referer: https://www.tiktok.com/",
        "-H", "Accept-Language: en-US,en;q=0.9,vi;q=0.8",
        "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "-w", "%s%%{http_code}" % sentinel,
    ]
    if os.path.exists(cookies_path):
        curl_cmd.extend(["-b", cookies_path])
    curl_cmd.append(live_url)
    try:
        proc = subprocess.run(curl_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        raw = (proc.stdout or b"").decode("utf-8", errors="ignore")
        # Tach body va http_code
        idx = raw.rfind(sentinel)
        if idx >= 0:
            html = raw[:idx]
            http_code = raw[idx + len(sentinel):].strip()
        else:
            html = raw
            http_code = "?"
        try:
            _html_cap_chk = _tiktok_effective_html_cap()
        except Exception:
            _html_cap_chk = 786432
        if len(html) > _html_cap_chk:
            html = html[:_html_cap_chk]
        if not html.strip():
            # Trang trong thuong la TikTok bot-block tam thoi — không ph?i lỗi that su.
            # Coi la offline de watcher tiep tuc kiểm tra lan sau (không l?u last_error).
            return False, "unknown: TikTok trả trang rỗng hoặc challenge tạm thời"
        # NEW: parse embedded live-room JSON state (SIGI_STATE). TikTok no longer
        # inlines raw flv/m3u8 URLs, so the HTML-pattern check below false-negative.
        room_live, room_id, room_status, room_detail = _parse_tiktok_live_room_state(html)
        if room_live:
            return True, ""
        # FIX-REVIEW-24/09-#9: parser co tham quyen bao OFFLINE (status=4) thi
        # CHAN fallback media toan trang — URL do co the cua phong duoc goi y
        # khac con live. Chi extract trong slice cua phong dich (room-bound).
        if room_status == 4:
            return False, "offline"
        lowered = html.lower()
        media_urls = _extract_tiktok_live_media_urls_scoped(html, room_id)
        live_title = " is live" in lowered and "tiktok" in lowered
        live_room = ("\"room_id\"" in lowered or "room_id=" in lowered) and ("\"stream_data\"" in lowered or "flv" in lowered or "m3u8" in lowered)
        if media_urls:
            for stream_url in media_urls[:2]:
                ok, detail = _tiktok_stream_url_seems_live(stream_url, cookies_path, tiktok_user_agent)
                if ok:
                    return True, ""
            return False, "unknown: TikTok có URL stream nhưng CDN chưa xác nhận stream còn sống"
        if live_room or live_title:
            return False, "unknown: TikTok báo có phòng live nhưng chưa thấy URL stream sống"
        challenge_signals = (
            "captcha",
            "verify to continue",
            "security check",
            "challenge",
            "secsdk-captcha",
            "verifycenter",
        )
        for sig in challenge_signals:
            if sig in lowered and not media_urls:
                return False, "unknown: TikTok yêu cầu xác minh/captcha tạm thời"
        if http_code in ("401", "403", "429"):
            return False, "unknown: TikTok chặn tạm thời HTTP %s" % http_code
        offline_signals = (
            "live has ended",
            "this live has ended",
            "phòng trực tiếp đã kết thúc",
            "user does not exist",
            "page not available",
            "couldn\\'t find this account",
            "\"liveroomstatus\":4",  # 4 = ended
            "\"liveroom\":null",
        )
        for sig in offline_signals:
            if sig in lowered:
                return False, "offline"
        # Nếu không có dấu hiệu offline/ended mà HTML chứa media URL, coi là live.
        # Không probe codec ở watcher vì ffmpeg/yt-dlp trên NAS hay false-negative.
        # HTML tr? v? binh thuong nhung không tìm thấy FLV URL va khong match signal nao
        # → user khong dang live (TikTok khong show stream URL khi offline). KHONG phai lỗi.
        return False, "unknown: chưa thấy URL stream, chưa xác nhận user đã dừng live"
    except Exception as e:
        return False, "Không kiểm tra được livestream: %s" % normalize_vietnamese_message(str(e))[:120]

def _start_tiktok_watch_record(username):
    """Goi record trong cung process, tra (job_id, message, outcome).

    FIX-AUDIT-L8: outcome typed (accepted/already_running/cooldown/failed) xuyen
    suot backend-watcher. Record tra 2xx+job_id cu kem cooldown/duplicate ma
    khong tao process thi watcher KHONG duoc danh dau recording/session_recorded.
    """
    live_url = "https://www.tiktok.com/@%s/live" % username
    payload = {"url": live_url, "quality": "best", "watch_username": username}
    try:
        # Gọi trực tiếp handler trong cùng process NAS. Không phụ thuộc app Android,
        # không phụ thuộc WorkManager, và không tự gọi HTTP localhost gây nghẽn queue.
        view_func = getattr(api_livestream_record, "__wrapped__", api_livestream_record)
        with app.test_request_context(
            "/api/livestream/record",
            method="POST",
            data=json.dumps(payload),
            content_type="application/json",
        ):
            result = view_func()
        status_code = 200
        response_obj = result
        if isinstance(result, tuple):
            response_obj = result[0]
            if len(result) > 1 and isinstance(result[1], int):
                status_code = result[1]
        data = {}
        if hasattr(response_obj, "get_json"):
            data = response_obj.get_json(silent=True) or {}
        if 200 <= status_code < 300:
            jid = data.get("job_id", "")
            outcome = str(data.get("outcome", "") or "").lower()
            if not outcome:
                # Tuong thich nguoc: suy outcome tu body cu.
                if data.get("duplicate"):
                    reason = str(data.get("reason", "") or "")
                    outcome = "cooldown" if "cooldown" in reason else "already_running"
                else:
                    outcome = "accepted"
            # Chi tin job co process that su song (chong cooldown/job cu).
            if jid and outcome in ("accepted", "already_running"):
                try:
                    with _livestream_lock:
                        _inf = _livestream_jobs.get(jid) or {}
                        _pid = _inf.get("pid")
                        _st = _inf.get("status", "")
                    _alive = False
                    if _pid and _st in ("recording", "starting"):
                        try:
                            os.kill(int(_pid), 0)
                            _alive = True
                        except Exception:
                            _alive = False
                    if not _alive:
                        return jid, data.get("message", ""), "cooldown"
                except Exception:
                    pass
            return jid, data.get("message", ""), outcome
        return "", normalize_vietnamese_message(data.get("error") or data.get("detail") or ("HTTP %d" % status_code))[:200], "failed"
    except Exception as e:
        return "", "NAS không khởi tạo được tác vụ ghi livestream: %s" % normalize_vietnamese_message(str(e))[:120], "failed"

def _tiktok_live_watchdog():
    _load_tiktok_watch_state()
    _tiktok_watch_runtime.update({
        "running": True,
        "started_at": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
        "last_tick": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
        "last_heartbeat": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
        "last_error": "",
        "last_summary": "Đã khởi động watcher TikTok trên NAS.",
    })
    log.info("[TikTokWatch] Watcher TikTok chạy trên NAS, không phụ thuộc app Android.")
    while True:
        try:
            loop_started_ts = time.time()
            with _tiktok_watch_lock:
                users_snapshot = [dict(u) for u in _tiktok_watch_state.get("users", [])]
                excluded = _is_tiktok_watch_excluded()
            changed = False
            checked_count = 0
            started_count = 0
            recording_count = 0
            pending_checks = []
            for user in users_snapshot:
                _tiktok_watch_runtime["last_heartbeat"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                username = user.get("username", "")
                if not username:
                    continue
                now_str = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                if excluded:
                    user["status"] = "excluded"
                    user["last_check"] = now_str
                    changed = True
                    continue
                uname_lc = username.lower()
                existing_job = _tiktok_watch_user_has_recording(username)
                if existing_job:
                    _tiktok_watch_mark_session_recorded(user, existing_job, now_str)
                    user["status"] = "recording"
                    user["job_id"] = existing_job
                    user["last_error"] = ""
                    recording_count += 1
                    user["last_check"] = now_str
                    changed = True
                    continue
                pending_checks.append((user, username, now_str))

            # TikTok checks are network/HTML heavy on this ARM NAS. Keep concurrency low
            # so a 30+ user watch list cannot starve API/status requests.
            max_workers = 1 if pending_checks else 0
            check_results = {}
            if pending_checks:
                with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
                    future_map = {
                        executor.submit(_check_tiktok_user_live, username): (user, username, now_str)
                        for user, username, now_str in pending_checks
                    }
                    for future in concurrent.futures.as_completed(future_map):
                        _tiktok_watch_runtime["last_heartbeat"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                        user, username, now_str = future_map[future]
                        try:
                            check_results[username.lower()] = future.result()
                        except Exception as e:
                            check_results[username.lower()] = (False, "Không kiểm tra được livestream: %s" % normalize_vietnamese_message(str(e))[:120])

            for user, username, now_str in pending_checks:
                is_live, err = check_results.get(username.lower(), (False, "offline"))
                checked_count += 1
                user["last_check"] = now_str
                if is_live:
                    # SPEC_LIVESTREAM_LIMITS §3.1: watcher ton trong cap dong
                    # (env > conf > default 2, clamp 1-8) thay vi cung 2.
                    try:
                        _watch_cap = int(globals().get("_get_livestream_max_concurrent", lambda: 2)())
                    except Exception:
                        _watch_cap = 2
                    if _watch_cap < 1:
                        _watch_cap = 1
                    if _watch_cap > 8:
                        _watch_cap = 8
                    if started_count >= _watch_cap:
                        user["status"] = "queued"
                        user["last_error"] = "Dang xep hang, watcher se bat o vong ke tiep."
                        changed = True
                        continue
                    # FIX-AUDIT-L8: chi danh dau recording/session_recorded khi
                    # outcome la accepted/already_running CO process song.
                    # cooldown/failed -> giu watching + ly do, khong danh dau ghi.
                    job_id, msg, outcome = _start_tiktok_watch_record(username)
                    if outcome in ("accepted", "already_running") and job_id:
                        user["status"] = "recording"
                        user["job_id"] = job_id
                        user["last_error"] = ""
                        started_count += 1
                        recording_count += 1
                        _tiktok_watch_mark_session_recorded(user, job_id, now_str)
                        log.info("[TikTokWatch] @%s đang live, NAS đã tự bắt đầu ghi job %s.", username, job_id)
                    else:
                        log.warning("[TikTokWatch] @%s đang live nhưng không bắt đầu ghi được: %s", username, msg)
                        if "User đã kết thúc live" not in msg and "offline" not in msg.lower():
                            _add_system_log_once(
                                "tiktok_start_fail:%s" % username.lower(),
                                "ERROR",
                                "TikTokWatch",
                                "@%s dang live nhung khong bat dau ghi duoc: %s" % (username, normalize_vietnamese_message(msg)[:220]),
                                180
                            )
                else:
                    if user.get("live_session_recorded", False):
                        is_offline = err == "offline"
                        is_unknown = err.startswith("unknown:")
                        offline_count = int(user.get("offline_confirm_count", 0) or 0) + 1
                        user["offline_confirm_count"] = offline_count
                        max_confirm = 3 if is_offline else 5
                        if offline_count < max_confirm:
                            user["status"] = "rechecking" if is_offline else "reconnecting"
                            user["job_id"] = user.get("live_session_job_id", "")
                            can_reconnect = is_offline
                            if is_offline:
                                user["last_error"] = "Chờ xác nhận user đã dừng live (%d/%d)" % (offline_count, max_confirm)
                            else:
                                user["last_error"] = "Chưa xác nhận đã dừng live (%d/%d): %s" % (offline_count, max_confirm, normalize_vietnamese_message(err))
                            last_attempt = float(user.get("reconnect_attempt_ts", 0) or 0)
                            if can_reconnect and time.time() - last_attempt >= 45:
                                user["reconnect_attempt_ts"] = time.time()
                                # FIX-AUDIT-L8: nhu tren — chi mark khi outcome co process song.
                                job_id, msg, outcome = _start_tiktok_watch_record(username)
                                if outcome in ("accepted", "already_running") and job_id:
                                    user["status"] = "recording"
                                    user["job_id"] = job_id
                                    user["last_error"] = ""
                                    user["reconnect_count"] = int(user.get("reconnect_count", 0) or 0) + 1
                                    started_count += 1
                                    recording_count += 1
                                    _tiktok_watch_mark_session_recorded(user, job_id, now_str)
                                    log.info("[TikTokWatch] @%s nối lại ghi %s, job %s.", username, "trong lúc xác nhận offline" if is_offline else "sau lỗi tạm thời", job_id)
                                else:
                                    user["last_error"] = "Chưa nối lại được (%d/%d): %s" % (offline_count, max_confirm, normalize_vietnamese_message(msg))
                            changed = True
                            continue
                        log.info("[TikTokWatch] @%s đã ngoại tuyến sau %d lần xác nhận, mở khoá phiên live tiếp theo.", username, offline_count)
                        _tiktok_watch_clear_session(user)
                        user["status"] = "watching"
                        user["last_error"] = ""
                    else:
                        is_real_error = any(kw in err.lower() for kw in ("cookies", "rate", "limit", "403", "401", "429", "t\u1eeb ch\u1ed1i"))
                        user["status"] = "watching"
                        user["job_id"] = ""
                        user["last_error"] = normalize_vietnamese_message(err) if is_real_error else ""
                changed = True
                
                # Cập nhật last_tick ngay trong vòng lặp để UI không tưởng watchdog bị treo
                _tiktok_watch_runtime["last_tick"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")

            if changed:
                # FIX (race condition): KHONG ghi de full list users — neu user
                # qua app /add/remove trong luc watchdog quet (30-60s/lap), thay
                # doi do se bi xoá hen. Logic moi: merge per-username vao state
                # hien tai. Update theo username, gi giu user moi them, b? qua
                # user da bi remove.
                with _tiktok_watch_lock:
                    snapshot_by_name = {
                        (u.get("username") or "").lower(): u
                        for u in users_snapshot
                        if u.get("username")
                    }
                    current_users = _tiktok_watch_state.get("users", [])
                    for cur in current_users:
                        un = (cur.get("username") or "").lower()
                        upd = snapshot_by_name.get(un)
                        if upd:
                            # Apply moi field tu snapshot vao cur (preserve cur
                            # reference de khong dut tham chieu trong RAM khac).
                            cur.update(upd)
                    _save_tiktok_watch_state()
            try:
                with _livestream_lock:
                    active_jobs = [
                        dict(j)
                        for j in _livestream_jobs.values()
                        if j.get("status") == "recording"
                    ]
                recording_count = len(active_jobs)
                started_count = sum(
                    1 for j in active_jobs
                    if float(j.get("started_ts", 0) or 0) >= loop_started_ts
                )
            except Exception:
                pass

            _tiktok_watch_runtime.update({
                "running": True,
                "last_tick": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
                "last_heartbeat": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
                "last_error": "",
                "last_summary": "Đã kiểm tra %d user, %d đang ghi, %d vừa mới bắt đầu." % (checked_count, recording_count, started_count),
                "loop_count": int(_tiktok_watch_runtime.get("loop_count", 0)) + 1,
            })
        except Exception as e:
            _tiktok_watch_runtime.update({
                "running": False,
                "last_error": normalize_vietnamese_message(str(e))[:200],
                "last_tick": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
                "last_heartbeat": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
            })
            log.error("[TikTokWatch] Lỗi watchdog: %s", e)
            _add_system_log_once("tiktok_watchdog_exception", "ERROR", "TikTokWatch", "Watchdog TikTok loi: %s" % normalize_vietnamese_message(str(e))[:240], 120)
        wait_seconds = _tiktok_watch_interval()
        try:
            with _tiktok_watch_lock:
                if any(u.get("status") in ("rechecking", "reconnecting") for u in _tiktok_watch_state.get("users", [])):
                    wait_seconds = min(wait_seconds, 15)
        except Exception:
            pass
        _tiktok_watch_runtime["last_heartbeat"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
        _tiktok_watch_wake.wait(wait_seconds)
        _tiktok_watch_wake.clear()

def _nas_api_self_watchdog():
    """Watch critical background loops and restart this process if they stall."""
    time.sleep(180)
    stale_cycles = 0
    lock_fail_cycles = 0
    while True:
        try:
            now = time.time()
            last_tick = _tiktok_watch_runtime.get("last_tick", "")
            tick_age = 0
            if last_tick:
                try:
                    tick_age = now - datetime.datetime.strptime(last_tick, "%d/%m/%Y %H:%M:%S").timestamp()
                except Exception:
                    tick_age = 0
            if tick_age and tick_age > max(600, _tiktok_watch_interval() * 6):
                stale_cycles += 1
                _add_system_log_once(
                    "nasapi_tiktok_watch_stale",
                    "ERROR",
                    "NasAPI",
                    "TikTok watcher khong cap nhat %d giay; stale_cycles=%d" % (int(tick_age), stale_cycles),
                    120
                )
            else:
                stale_cycles = 0

            got_live_lock = _livestream_lock.acquire(timeout=5.0)
            if got_live_lock:
                try:
                    lock_fail_cycles = 0
                finally:
                    _livestream_lock.release()
            else:
                lock_fail_cycles += 1
                _add_system_log_once(
                    "nasapi_livestream_lock_stuck",
                    "ERROR",
                    "NasAPI",
                    "Livestream lock bi ket qua 5 giay; lock_fail_cycles=%d" % lock_fail_cycles,
                    120
                )

            if stale_cycles >= 3:
                _restart_nas_api("TikTok watcher bi ket, last_tick cach %d giay" % int(tick_age))
            # Nâng ngưỡng 3->5 chu kỳ (~5 phút) trước khi restart: restart sẽ giết
            # mọi livestream đang ghi nên chỉ restart khi CHẮC CHẮN lock deadlock
            # thật, tránh false-positive khi lock kẹt tạm thời.
            if lock_fail_cycles >= 5:
                _restart_nas_api("Livestream lock bi ket lien tiep %d chu ky (~5 phut)" % lock_fail_cycles)
        except Exception as e:
            _add_system_log_once("nasapi_self_watchdog_exception", "ERROR", "NasAPI", "Self-watchdog loi: %s" % normalize_vietnamese_message(str(e))[:240], 120)
        time.sleep(60)

@app.route("/api/tiktok/live_watch", methods=["GET"])
@requires_auth
def api_tiktok_live_watch_get():
    # Pre-fetch livestream info to avoid nested locks (deadlock prevention)
    active_livestreams = {}
    active_recording_count = 0
    with _livestream_lock:
        for k, v in _livestream_jobs.items():
            active_livestreams[k] = (v.get("status"), v.get("pid"))
            if v.get("status") == "recording":
                active_recording_count += 1

    with _tiktok_watch_lock:
        _load_tiktok_watch_state()
        changed = False
        for user in _tiktok_watch_state.get("users", []):
            if user.get("status") == "recording":
                jid = user.get("job_id", "")
                active = False
                if jid:
                    l_status, l_pid = active_livestreams.get(jid, (None, None))
                    if l_status == "recording" and l_pid is not None:
                        try:
                            os.kill(l_pid, 0)
                            active = True
                        except Exception:
                            active = False
                if not active:
                    if user.get("live_session_recorded", False):
                        user["status"] = "recorded"
                        user["job_id"] = user.get("live_session_job_id", "") or jid
                    else:
                        user["status"] = "watching"
                        user["job_id"] = ""
                    changed = True
            if user.get("last_live") and not user.get("last_live_verified", False):
                user["last_live"] = ""
                changed = True
        if changed:
            _save_tiktok_watch_state()
        resp = dict(_tiktok_watch_state)
    # Th?m trạng thái cookies de UI hien banner khi het han / bi thu hoi.
    # Them expiry/expiry_str/days_left de app push canh bao truoc 3 ngay.
    cookies = _check_cookies_status()
    resp["cookies_status"] = cookies.get("status", "unknown")
    resp["cookies_message"] = cookies.get("message", "")
    resp["cookies_expiry"] = cookies.get("expiry", 0) or 0
    resp["cookies_expiry_str"] = cookies.get("expiry_str", "") or ""
    resp["cookies_days_left"] = cookies.get("days_left", -1.0)
    daemon = dict(_tiktok_watch_runtime)
    try:
        heartbeat = daemon.get("last_heartbeat") or daemon.get("last_tick") or ""
        if heartbeat:
            heartbeat_age = time.time() - datetime.datetime.strptime(heartbeat, "%d/%m/%Y %H:%M:%S").timestamp()
            daemon["heartbeat_age_seconds"] = int(max(0, heartbeat_age))
            daemon["running"] = active_recording_count > 0 or heartbeat_age < max(600, _tiktok_watch_interval() * 3)
            daemon["active_recording_count"] = active_recording_count
    except Exception:
        pass
    resp["daemon"] = daemon
    resp["daemon_running"] = daemon.get("running", False)
    resp["last_tick"] = daemon.get("last_tick", "")
    resp["summary"] = daemon.get("summary", "")
    resp["poll_interval"] = _tiktok_watch_interval()
    return jsonify(resp)

@app.route("/api/tiktok/live_watch/add", methods=["POST"])
@requires_auth
def api_tiktok_live_watch_add():
    body = request.get_json(force=True) or {}
    username = _normalize_tiktok_username(body.get("username", ""))
    if not username:
        return jsonify({"error": "Username TikTok không hợp lệ"}), 400
    with _tiktok_watch_lock:
        users = _tiktok_watch_state.setdefault("users", [])
        if not any(u.get("username", "").lower() == username.lower() for u in users):
            users.append({
                "username": username,
                "status": "watching",
                "last_check": "",
                "last_live": "",
                "last_live_verified": False,
                "last_error": "",
                "job_id": "",
                "live_session_recorded": False,
                "live_session_job_id": "",
                "live_session_started": "",
                "live_session_last_live": ""
            })
        saved = _save_tiktok_watch_state()
        _tiktok_watch_wake.set()
        # FIX: bao lỗi RO ngay cho user neu CA HAI noi l?u deu fail
        if not saved:
            response = jsonify({
                "error": "Đã thêm trong RAM nhưng KHÔNG lưu được xuống đĩa. Sẽ mất khi reboot.",
                "persisted": False,
                "users": _tiktok_watch_state.get("users", []),
            })
            response.status_code = 503
            return response
        return jsonify(_tiktok_watch_state)

@app.route("/api/tiktok/live_watch/remove", methods=["POST"])
@requires_auth
def api_tiktok_live_watch_remove():
    body = request.get_json(force=True) or {}
    username = _normalize_tiktok_username(body.get("username", ""))
    with _tiktok_watch_lock:
        _tiktok_watch_state["users"] = [
            u for u in _tiktok_watch_state.get("users", [])
            if u.get("username", "").lower() != username.lower()
        ]
        saved = _save_tiktok_watch_state()
        _tiktok_watch_wake.set()
        if not saved:
            response = jsonify({
                "error": "Đã xoá trong RAM nhưng KHÔNG lưu được xuống đĩa. Sẽ trở lại khi reboot.",
                "persisted": False,
                "users": _tiktok_watch_state.get("users", []),
            })
            response.status_code = 503
            return response
        return jsonify(_tiktok_watch_state)

@app.route("/api/tiktok/live_watch/settings", methods=["POST"])
@requires_auth
def api_tiktok_live_watch_settings():
    body = request.get_json(force=True) or {}
    with _tiktok_watch_lock:
        # FIX-AUDIT-L7: Android gui enabled/start/end, backend cu chi doc
        # exclude_enabled/exclude_start/exclude_end -> bo qua, tra 200 nhung
        # khong luu. Chap nhan ca hai key + validate HH:MM.
        if "exclude_enabled" in body or "enabled" in body:
            _tiktok_watch_state["exclude_enabled"] = bool(
                body.get("exclude_enabled", body.get("enabled")))
        _raw_start = body.get("exclude_start", body.get("start", ""))
        _raw_end = body.get("exclude_end", body.get("end", ""))
        import re as _re_hhmm
        if _raw_start and _re_hhmm.match(r"^\d{1,2}:\d{2}$", str(_raw_start)):
            _tiktok_watch_state["exclude_start"] = str(_raw_start)[:5]
        if _raw_end and _re_hhmm.match(r"^\d{1,2}:\d{2}$", str(_raw_end)):
            _tiktok_watch_state["exclude_end"] = str(_raw_end)[:5]
        _save_tiktok_watch_state()
        _tiktok_watch_wake.set()
        return jsonify(_tiktok_watch_state)


@app.route("/api/livestream/record", methods=["POST"])
@requires_auth
def api_livestream_record():
    """Bắt đầu ghi hảnh livestream tu TikTok/Facebook/YouTube."""
    claimed_recording_key = ""
    try:
        body = request.get_json(force=True) or {}
        live_url = body.get("url", "").strip()
        quality = body.get("quality", "best").strip()
        referer = body.get("referer", "").strip()
        user_agent = body.get("user_agent", "").strip()
        # Watch_username: gan boi _start_tiktok_watch_record de dedup chinh xac
        # khi URL bi ghi de thảnh FLV CDN URL trong nhanh TikTok direct.
        watch_username = body.get("watch_username", "").strip()

        if not live_url:
            return jsonify({"error": "Thiếu URL livestream"}), 400
        try:
            if not _social_validate_url(live_url):
                return jsonify({"error": "URL livestream không hợp lệ hoặc không an toàn"}), 400
        except Exception:
            return jsonify({"error": "URL livestream không hợp lệ hoặc không an toàn"}), 400
        req_max_hours = 0
        if "max_hours" in body and body.get("max_hours") is not None:
            try:
                _mh = int(body.get("max_hours"))
                if 1 <= _mh <= 72:
                    req_max_hours = _mh
            except Exception:
                pass

        # Kiểm tra yt-dlp
        ytdlp_bin = _find_ytdlp_bin()
        if not ytdlp_bin:
            return jsonify({
                "error": "yt-dlp chưa được cài đặt trên NAS",
                "install_hint": "wget https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64 -O /usr/local/bin/yt-dlp && chmod +x /usr/local/bin/yt-dlp"
            }), 503

        # FIX: PRE-FLIGHT CHECK cho TikTok — kiểm tra nhanh user co dang live khong
        # truoc khi cham vao yt-dlp/ffmpeg (cham, ton tai nguyen). Tra error CU THE
        # de app khong hien "timeout" chung chung nua.
        # Chi check cho URL TikTok co @user/live; cac URL khac (FB/YT/Shopee) van di
        # qua flow cu vi format URL khac va probe nhanh hon.
        # Removed pre-flight check due to false negatives. Let yt-dlp try its best.
        # Không gi?i h?n so luồng ghi cung; thay vao do check phan cung de
        # bao ve NAS khoi tinh trang treo. Chap nhan luồng moi neu:
        #   - CPU dang dung < 85%
        #   - RAM con trong > 200MB
        #   - Load average 1-phut < so core * 1.5
        # Neu vuot bat ky nguong nao -> tu choi voi 429 + chi tiet so do.
        with _livestream_lock:
            active_count = sum(1 for j in _livestream_jobs.values() if j.get("status") == "recording")
        try:
            cpu_pct = psutil.cpu_percent(interval=0.4)
        except Exception:
            cpu_pct = 0.0
        try:
            mem = psutil.virtual_memory()
            mem_free_mb = mem.available / (1024 * 1024)
            mem_pct = mem.percent
        except Exception:
            mem_free_mb = 9999
            mem_pct = 0.0
        try:
            cores = max(1, psutil.cpu_count(logical=True) or 1)
            load1 = os.getloadavg()[0]
        except Exception:
            cores = 1
            load1 = 0.0
        hw_reason = ""
        # CC-004 P0-5: ap cap config (admin, clamp 1-8) TRUOC HW-gate. Ban cu
        # khong goi _get_livestream_max_concurrent() nen admin dat 2 van chay
        # toi 16 ffmpeg+yt-dlp giet rk3328 1GB. 16 giu lam hard-ceiling.
        try:
            _cap_fn = globals().get("_get_livestream_max_concurrent")
            cfg_cap = int(_cap_fn()) if callable(_cap_fn) else 2
        except Exception:
            cfg_cap = 2
        _is_cap_limit = False
        if active_count >= cfg_cap:
            hw_reason = "Da dat gioi han %d luong ghi dong thoi (admin config)" % cfg_cap
            _is_cap_limit = True
        elif cpu_pct > 85:
            hw_reason = "CPU %.0f%% quá cao" % cpu_pct
        elif mem_free_mb < 200:
            hw_reason = "RAM trống chỉ còn %.0f MB" % mem_free_mb
        elif mem_pct > 90:
            hw_reason = "RAM đang dùng %.0f%%" % mem_pct
        elif load1 > cores * 1.5:
            hw_reason = "Load average %.2f vượt %.1f (cores x 1.5)" % (load1, cores * 1.5)
        # Cap an toan tuyet doi: 16 luồng song song, trảnh truong hop psutil tra
        # so do sai khien NAS bi tham lam vo han.
        if active_count >= 16:
            hw_reason = "Đã có %d luồng ghi đồng thời (ngưỡng an toàn)" % active_count
            _is_cap_limit = True
        if hw_reason:
            _err_payload = {
                "error": "Không thể bắt đầu luồng mới: %s" % hw_reason,
                "active_count": active_count,
                "cpu_pct": round(cpu_pct, 1),
                "mem_free_mb": int(mem_free_mb),
                "load_avg_1min": round(load1, 2),
            }
            if _is_cap_limit:
                _err_payload["code"] = "MAX_STREAMS"
                _err_payload["reason"] = "max_concurrent"
                _err_payload["cap"] = cfg_cap
                _err_payload["active"] = active_count
                _err_payload["retry_after_s"] = 60
            return jsonify(_err_payload), 429

        # Kiểm tra dung lượng HDD con lai
        try:
            disk_usage = psutil.disk_usage(WEBDAV_FILE_ROOT)
            free_gb = disk_usage.free / (1024 ** 3)
            if free_gb < 2.0:
                return jsonify({
                    "error": "HDD còn quá ít dung lượng (%.1f GB). Cần ít nhất 2 GB để ghi livestream." % free_gb
                }), 507
        except Exception:
            pass

        # Auto-detect platform
        platform = _detect_platform(live_url)

        # T?o thư mục luu
        try:
            os.makedirs(_LIVESTREAM_DIR, exist_ok=True)
        except Exception:
            pass

        # Tao ten file output
        timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        # FIX-P2-2: job_id (ms + random) tinh som de dung lam thanh phan dinh
        # danh file. Duy nhat toan cuc, nen 2 URL khac nhau khoi cung giay
        # (hoac cung URL yeu cau 2 lan) se co file name khong trung nhau,
        # tranh ghi de/trung lap du lieu. Truoc day chi dung platform+giay,
        # nen 2 job cung giay de trung duong dan.
        job_id = "live_%d_%s" % (int(time.time() * 1000), uuid.uuid4().hex[:6])
        # FIX: KHÔNG dùng %(title) trong output_template — title cua TikTok live
        # co the thay doi giua chung (host doi caption, hoac yt-dlp re-resolve
        # metadata sau khi mat ket noi). Moi lan title doi -> yt-dlp dong file
        # cu va mo file moi -> 1 session bi ghi ra nhieu file .mp4.
        #
        # Thay vao do dùng stable_id deterministic:
        #   - Neu co watch_username (tu watcher) -> dung username
        #   - Neu URL TikTok co @user -> trich username tu URL
        #   - Fallback: chi platform + timestamp
        stable_id = ""
        if watch_username:
            stable_id = watch_username
        else:
            try:
                # Truoc tien thu match @user truc tiep tren URL
                m = _re_module.search(r"tiktok\.com/@([\w.\-]+)", live_url)
                if m:
                    stable_id = m.group(1)
                else:
                    # FIX: URL TikTok dang short (tiktok.com/t/<id>, vt.tiktok.com,
                    # vm.tiktok.com) khong co @user -> resolve redirect de tim @user
                    # cuoi cung. Neu không resolve được thi stable_id van rong va
                    # filename se la "tiktok_<ts>.mp4" (không cần "_tiktok" cung).
                    is_short = ("tiktok.com/t/" in live_url.lower()
                                or "vt.tiktok.com" in live_url.lower()
                                or "vm.tiktok.com" in live_url.lower())
                    if is_short:
                        try:
                            current = live_url
                            # Theo redirect toi 5 hop (TikTok thuong redirect 2-3 lan)
                            for _hop in range(5):
                                conn = urllib.request.Request(
                                    current,
                                    headers={
                                        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
                                        "Referer": "https://www.tiktok.com/",
                                    },
                                )
                                # Th? HEAD truoc, neu không được thi GET
                                resp = None
                                try:
                                    resp = urllib.request.urlopen(conn, timeout=5)
                                except Exception:
                                    break
                                next_url = resp.geturl() if resp else current
                                try: resp.close()
                                except Exception as e: log.debug("[M4] Ignored exception: %s", e)
                                if not next_url or next_url == current:
                                    break
                                current = next_url
                                if "@" in current:
                                    break
                            m2 = _re_module.search(r"tiktok\.com/@([\w.\-]+)", current)
                            if m2:
                                stable_id = m2.group(1)
                                log.info("[Livestream] Resolved short URL -> @%s", stable_id)
                        except Exception as _e:
                            log.warning("[Livestream] Không resolve được short URL: %s", _e)
            except Exception:
                pass
        if stable_id:
            # Sanitize de trảnh ky tu xau trong filename
            stable_id = _re_module.sub(r"[^\w.\-]", "_", stable_id)[:40]
            output_template = os.path.join(
                _LIVESTREAM_DIR,
                "%s_%s_%s_%s.%%(ext)s" % (platform, stable_id, timestamp_str, job_id)
            )
        else:
            output_template = os.path.join(
                _LIVESTREAM_DIR,
                "%s_%s_%s.%%(ext)s" % (platform, timestamp_str, job_id)
            )
        direct_tiktok_flv = False
        direct_output_file = ""
        _wrapper_dir = ""
        # FIX-P2-1: file_stem la prefix ten file THUC te cua job nay (khong
        # duoi, khong chua random cua job_id). Duy nhat theo gioi han cua
        # timestamp_str (giay) + stable_id. Dung de match chinh xac file cua
        # job nay khi quet output_dir, tranh lay nhầm file cua job khac cung
        # giay (timestamp_str chi den giay, 2 user khac nhau khoi cung giay se
        # co chung substring -> size helper lay max size la SAI).
        # FIX-P2-2: file_stem phai chua job_id (suffix) de khop voi ten file
        # moi va duy tri dinh danh duy nhat theo job. Van bat dau bang
        # platform[_stable_id]_timestamp de watchdog match dung file cua job
        # nay qua startswith (giu hanh vi FIX-P2-1).
        if stable_id:
            file_stem = "%s_%s_%s_%s" % (platform, stable_id, timestamp_str, job_id)
        else:
            file_stem = "%s_%s_%s" % (platform, timestamp_str, job_id)
        tiktok_user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        original_record_url = body.get("url", "").strip() or live_url

        # Xay dung lenh yt-dlp cho livestream
        format_str = "best"
        if quality == "720p":
            format_str = "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
        elif quality == "audio":
            format_str = "bestaudio[ext=m4a]/bestaudio"

        cmd = [
            ytdlp_bin,
            "--no-live-from-start",    # Ghi tu hien tai (TikTok/Facebook không hỗ trợ tu dau)
            "--no-part",
            "--no-playlist",
            "--no-warnings",
            "-f", format_str,
            "-o", output_template,
            "--socket-timeout", "60",
            "--retries", "infinite",
            "--fragment-retries", "infinite",
            "--hls-use-mpegts",         # Ghi tung doan .ts -> khong bi corrupt khi ngat
            "--downloader", "ffmpeg",   # Dung ffmpeg downloader -> on dinh hon voi live stream
            "--downloader-args", "ffmpeg:-loglevel warning",
            # FIX: ep yt-dlp remux fragment HLS thảnh MP4 container ch?an, khong
            # con l?u raw .ts mislabel ext .mp4 (player tu choi parse vi magic
            # bytes khong khop). --remux-video chi remux container, KHONG
            # re-encode -> nhanh, không gi?m ch?t lượng.
            "--remux-video", "mp4",
            # FIX: moov atom de o dau file de player pl?y được khi file con dang
            # ghi (progressive streaming). Khong co flag nay, moov nam o cuoi
            # va player phai download het roi moi seek được.
            "--postprocessor-args", "ffmpeg:-movflags +faststart",
        ]

        # Th?m cookies neu co file (ở thư mục gốc)
        cookies_path = os.path.join(WEBDAV_FILE_ROOT, "cookies.txt")
        if os.path.exists(cookies_path):
            cmd.extend(["--cookies", cookies_path])

        if referer:
            cmd.extend(["--referer", referer])
        if user_agent:
            cmd.extend(["--user-agent", user_agent])

        # --- TIKTOK HTML FLV FALLBACK -> MP4 ---
        # TikTok API metadata cua yt-dlp co the bao sai "not currently live",
        # trong khi trang HTML van co FLV stream dang chay. L?y cac FLV URL
        # tu HTML, chon bien the H264 ffmpeg 3.2 Đọc được (_hd/_ld), roi ghi
        # truc tiep thảnh MP4. Khong l?u FLV ra NAS.
        if "tiktok" in live_url.lower():
            curl_cmd = [
                "curl", "-4", "-s", "-L",
                "--max-time", "20",
                "-A", tiktok_user_agent,
                "-H", "Referer: https://www.tiktok.com/",
                "-H", "Accept-Language: en-US,en;q=0.9,vi;q=0.8",
            ]
            if os.path.exists(cookies_path):
                curl_cmd.extend(["-b", cookies_path])
            # FIX-AUDIT-L6: room-bound extraction — truoc khi lay media toan
            # trang, parse trang thai phong dich. Neu parser co tham quyen bao
            # OFFLINE (status=4) thi KHONG fallback lay media (URL do co the
            # cua phong duoc goi y khac con live) -> ve yt-dlp path binh thuong.
            curl_cmd.append(live_url)
            try:
                _html_timeout = _tiktok_effective_html_timeout()
            except Exception:
                _html_timeout = 25
            try:
                html = subprocess.check_output(curl_cmd, timeout=_html_timeout).decode("utf-8", errors="ignore")
                log.info("[Livestream] TikTok HTML fallback: nhận %d bytes HTML", len(html))
                # FIX-AUDIT-L6: kiem tra phong dich truoc khi lay media toan trang.
                try:
                    _rl, _rid, _rs, _rd = _parse_tiktok_live_room_state(html)
                except Exception:
                    _rl, _rid, _rs, _rd = False, "", -1, "parse error"
                if _rs == 4:
                    log.info("[Livestream] TikTok HTML fallback: phong dich OFFLINE (status=4, room=%s) — bo qua media toan trang, dung yt-dlp path.", _rid)
                    media_urls = []
                else:
                    # FIX-REVIEW-24/09-#9: room-bound — chi lay media cua phong dich.
                    media_urls = _extract_tiktok_live_media_urls_scoped(html, _rid)
                if not media_urls and _rs != 4:
                    log.warning("[Livestream] TikTok HTML fallback: HTML %d bytes nhưng không extract được URL media nào", len(html))
                    # Retry 1 lần với mobile UA
                    try:
                        import time as _time_mod
                        _time_mod.sleep(2)
                        retry_cmd = list(curl_cmd)
                        mobile_ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                        for _i, _v in enumerate(retry_cmd):
                            if _v == "-A" and _i + 1 < len(retry_cmd):
                                retry_cmd[_i + 1] = mobile_ua
                                break
                            if _v == "-H" and _i + 1 < len(retry_cmd) and retry_cmd[_i + 1].startswith("User-Agent:"):
                                retry_cmd[_i + 1] = "User-Agent: " + mobile_ua
                                break
                        html2 = subprocess.check_output(retry_cmd, timeout=_html_timeout).decode("utf-8", errors="ignore")
                        log.info("[Livestream] TikTok HTML retry: nhận %d bytes HTML", len(html2))
                        # FIX-REVIEW-24/09-#9: retry cung room-bound + kiem tra
                        # phong dich (khong extract toan trang mu).
                        try:
                            _rl2, _rid2, _rs2, _rd2 = _parse_tiktok_live_room_state(html2)
                        except Exception:
                            _rl2, _rid2, _rs2, _rd2 = False, "", -1, "parse error"
                        if _rs2 == 4:
                            media_urls = []
                        else:
                            media_urls = _extract_tiktok_live_media_urls_scoped(html2, _rid2 or _rid)
                        if media_urls:
                            html = html2
                            log.info("[Livestream] TikTok HTML retry: tìm được %d URL media", len(media_urls))
                    except Exception as _retry_err:
                        log.warning("[Livestream] TikTok HTML retry lỗi: %s", _retry_err)
                log.info("[Livestream] TikTok HTML fallback: phát hiện %d URL media ứng viên", len(media_urls))
                # FIX: HEAD-check từng candidate theo thứ tự ưu tiên (_hd > _ld > .m3u8 > _sd)
                # và chọn URL đầu tiên trả về 2xx/3xx. Trước đây luôn lấy candidate[0] (_hd)
                # rồi break ngay — nếu biến thể HD bị 404 thì job ghi fail (file 0 byte)
                # dù user vẫn đang live và còn URL chất lượng khác dùng được.
                # Giới hạn 6 lần probe để bảo vệ CPU ARM khi danh sách candidate dài.
                chosen_url = ""
                for candidate in media_urls[:6]:
                    # TikTok CDN FLV /stage/ KHONG tra loi HEAD (-I) -> luon 000.
                    # Dung ranged GET (-r 0-1) xin 1-2 byte dau de lay status that:
                    # 200/206 = stream song, 404 = bien the da chet/het han.
                    probe_cmd = [
                        "curl", "-4", "-s", "-L", "--http1.1",
                        "-r", "0-1",
                        "--max-time", "6", "--connect-timeout", "4",
                        "-A", tiktok_user_agent,
                        "-H", "Referer: https://www.tiktok.com/",
                        "-o", "/dev/null",
                        "-w", "%{http_code}",
                    ]
                    if os.path.exists(cookies_path) and "tiktokcdn" not in candidate.lower():
                        probe_cmd.extend(["-b", cookies_path])
                    probe_cmd.append(candidate)
                    probe_code = "0"
                    try:
                        probe_proc = subprocess.run(probe_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=8)
                        probe_code = (probe_proc.stdout or b"").decode("utf-8", errors="ignore").strip() or "0"
                    except Exception:
                        probe_code = "0"
                    log.info("[Livestream] TikTok HTML fallback: probe HTTP %s %s", probe_code, candidate[:160])
                    if probe_code.startswith(("2", "3")):
                        chosen_url = candidate
                        break
                # Nếu không URL nào pass HEAD, vẫn thử candidate đầu tiên để logic
                # HEAD/re-scrape phía dưới xử lý tiếp (giữ hành vi cũ làm lưới an toàn).
                if not chosen_url and media_urls:
                    log.warning("[Livestream] TikTok HTML fallback: không URL nào pass probe, bỏ direct FLV để tránh tạo file 0 byte")
                if chosen_url:
                    live_url = chosen_url
                    direct_tiktok_flv = True
                    # FIX: dùng stable_id (username) trong filename, không để "_tiktok"
                    # cung. Truoc day mới l?c dung direct FLV path, file deu co dang
                    # tiktok_<ts>_tiktok.mp4 -> mat thong tin user trong ten file.
                    if stable_id:
                        direct_output_file = os.path.join(
                            _LIVESTREAM_DIR,
                            "%s_%s_%s_%s.mp4" % (platform, stable_id, timestamp_str, job_id)
                        )
                    else:
                        direct_output_file = os.path.join(
                            _LIVESTREAM_DIR,
                            "%s_%s_%s.mp4" % (platform, timestamp_str, job_id)
                        )
                    log.info("[Livestream] TikTok HTML fallback: dùng direct media URL để ghi MP4")
            except Exception as e:
                log.warning("[Livestream] Lỗi TikTok HTML fallback: %s", e)
        # --------------------------------

        if direct_tiktok_flv:
            # FLV URL da được chon la H264 remuxable. Ghi thang MP4 bang
            # ffmpeg, không để lai file .flv.
            # Pre-check NHANH bang HEAD request (khong tai body) de tu choi som
            # neu URL FLV da 404/403/expired. HEAD chi ton ~1-3s nen khong gay
            # timeout 35s o local urlopen ben watcher.
            head_cmd = [
                "curl", "-4", "-s", "-L", "--http1.1",
                "-r", "0-1",
                "--max-time", "6",
                "--connect-timeout", "4",
                "-A", tiktok_user_agent,
                "-H", "Referer: https://www.tiktok.com/",
                "-o", "/dev/null",
                # FIX: log th?m content_type de validate FLV that su (trảnh
                # truong hop CDN tr? v? text/html, application/json hay
                # application/vnd.apple.mpegurl ma curl van ghi vao .flv).
                "-w", "%{http_code}|%{content_type}",
            ]
            if os.path.exists(cookies_path) and "tiktokcdn" not in live_url.lower():
                head_cmd.extend(["-b", cookies_path])
            head_cmd.append(live_url)
            content_type = ""
            try:
                head_proc = subprocess.run(head_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
                head_out = (head_proc.stdout or b"").decode("utf-8", errors="ignore").strip() or "0|"
                parts = head_out.split("|", 1)
                http_code = parts[0] or "0"
                content_type = (parts[1] if len(parts) > 1 else "").lower().strip()
            except Exception:
                http_code = "0"
                content_type = ""

            # Neu HEAD thất bại voi 4xx/5xx -> thu re-scrape 1 lan (FLV URL co the vua het han).
            if http_code.startswith(("4", "5")):
                original_user_url = body.get("url", "").strip()
                if original_user_url and "tiktok.com" in original_user_url and not original_user_url.startswith(live_url[:30]):
                    rescrape_cmd = [
                        "curl", "-4", "-s", "-L", "--max-time", "8",
                        "-A", tiktok_user_agent,
                        "-H", "Referer: https://www.tiktok.com/",
                    ]
                    if os.path.exists(cookies_path):
                        rescrape_cmd.extend(["-b", cookies_path])
                    rescrape_cmd.append(original_user_url)
                    try:
                        html2 = subprocess.check_output(rescrape_cmd, timeout=10).decode("utf-8", errors="ignore")
                        # FIX-REVIEW-24/09-#9: re-scrape cung room-bound.
                        try:
                            _rrl, _rrid, _rrs, _rrd = _parse_tiktok_live_room_state(html2)
                        except Exception:
                            _rrl, _rrid, _rrs, _rrd = False, "", -1, "parse error"
                        _rescrape_urls = [] if _rrs == 4 else _extract_tiktok_live_media_urls_scoped(html2, _rrid)
                        for new_flv in _rescrape_urls[:6]:
                            if new_flv != live_url:
                                live_url = new_flv
                                head_cmd[-1] = live_url
                                head_proc = subprocess.run(head_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
                                head_out = (head_proc.stdout or b"").decode("utf-8", errors="ignore").strip() or "0|"
                                parts = head_out.split("|", 1)
                                http_code = parts[0] or "0"
                                content_type = (parts[1] if len(parts) > 1 else "").lower().strip()
                                log.info("[Livestream] TikTok direct fallback: re-probe HTTP %s %s", http_code, live_url[:160])
                                if http_code.startswith(("2", "3")):
                                    break
                    except Exception:
                        pass

            if http_code.startswith(("4", "5")):
                # Message ngan gon, chuyen nghiep, ko log JSON tho.
                short_msg = {
                    "401": "Cookies TikTok không hợp lệ",
                    "403": "TikTok chặn vùng / cookies bị thu hồi",
                    "404": "User đã kết thúc live",
                    "429": "TikTok giới hạn tốc độ — thử lại sau ít phút",
                }.get(http_code, "TikTok CDN từ chối (HTTP %s)" % http_code)
                return jsonify({
                    "error": short_msg,
                    "http_code": http_code,
                }), 502

            # FIX: Validate Content-Type — neu CDN tr? v? text/html, JSON,
            # hay m3u8 manifest (cac dau hieu URL het han hoac sai) thi fallback
            # ve yt-dlp path (yt-dlp se tu re-scrape, demux HLS, remux thảnh
            # mp4 ch?an), thay vi l?u rac vao file .flv không pl?y được.
            is_hls_url = ".m3u8" in live_url.lower()
            is_flv_serve = any(s in content_type for s in ("video/x-flv", "video/flv", "flv-application", "application/octet-stream", "video/mp4", "mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl"))
            if content_type and not is_flv_serve:
                # CDN không serve FLV thuan — bo direct path, dung yt-dlp fallback
                direct_tiktok_flv = False
                direct_output_file = ""
            elif not _direct_flv_has_remuxable_video(live_url, cookies_path, user_agent):
                # TikTok FLV mới có thể dùng enhanced FLV/HEVC tag mà ffmpeg cũ
                # trên ARM NAS không remux được. Probe nhanh trước khi ghi raw.
                direct_tiktok_flv = False
                direct_output_file = ""

        if direct_tiktok_flv:
            loop_script = """import sys, time, subprocess, re, os, shutil
username = sys.argv[1]
out_file = sys.argv[2]
cookies = sys.argv[3]
ua = sys.argv[4]
initial_url = sys.argv[5] if len(sys.argv) > 5 else ""
tried_urls = set()

def extract_media_urls(html):
    urls = []
    text = (html or "")[:786432]
    for pat in (
        r'\\\\"flv\\\\":\\\\"(https://[^"\\\\\\\\]+)',
        r'\\"flv\\":\\"(https://[^"\\\\]+)',
        r'\\\\"hls_pull_url\\\\":\\\\"(https://[^"\\\\\\\\]+)',
        r'\\"hls_pull_url\\":\\"(https://[^"\\\\]+)',
        r'https:\\\\/\\\\/[^"\\\\]{1,2000}?\\.m3u8[^"\\\\]{0,2000}',
        r'https://[^"\\\\<>\\s]{1,2000}?\\.m3u8[^"\\\\<>\\s]{0,2000}',
        r'https:\\\\/\\\\/[^"\\\\]{1,2000}?\\.flv[^"\\\\]{0,2000}',
        r'https://[^"\\\\<>\\s]{1,2000}?\\.flv[^"\\\\<>\\s]{0,2000}',
    ):
        for u in re.findall(pat, text):
            u = u.replace("\\\\u0026", "&").replace("\\\\/", "/")
            if "only_audio=1" not in u and u not in urls:
                urls.append(u)
    def rank(u):
        ul = u.lower()
        if "_hd.flv" in ul: return 0
        if "_ld.flv" in ul: return 1
        if ".m3u8" in ul: return 2
        if "_sd.flv" in ul: return 3
        return 9
    return sorted(urls, key=rank)

def _room_object_text(html, uname):
    # Tra ve text object JSON chua uniqueId/username cua phong dich (room-bound),
    # lowercase + bo khoang trang. Neu khong co identity -> "" (khong scan phong
    # khac, khong ket luan offline tu du lieu phong khac).
    try:
        _u = (uname or "").lower()
        if not _u:
            return ""
        _flat = re.sub(r"\\s+", "", (html or "").lower())
        _best = -1
        _best_len = 0
        for _t in ('"uniqueid":"%s"' % _u, '"uniqueid":%s' % _u, '"@%s"' % _u):
            _p = _flat.find(_t)
            if _p >= 0 and (_best < 0 or _p < _best):
                _best = _p
                _best_len = len(_t)
        if _best < 0:
            return ""
        _n = len(_flat)
        _depth = 0
        _open = -1
        _j = _best + _best_len
        while _j >= 0:
            _c = _flat[_j]
            if _c == '"':
                _j -= 1
                while _j >= 0:
                    if _flat[_j] == '\\\\':
                        _j -= 2
                        continue
                    if _flat[_j] == '"':
                        _j -= 1
                        break
                    _j -= 1
                continue
            if _c == "}":
                _depth += 1
            elif _c == "{":
                if _depth == 0:
                    _open = _j
                    break
                _depth -= 1
            _j -= 1
        if _open < 0:
            return ""
        _depth = 1
        _close = -1
        _i = _open + 1
        while _i < _n:
            _c = _flat[_i]
            if _c == '"':
                _i += 1
                while _i < _n:
                    if _flat[_i] == '\\\\':
                        _i += 2
                        continue
                    if _flat[_i] == '"':
                        _i += 1
                        break
                    _i += 1
                continue
            if _c == "{":
                _depth += 1
            elif _c == "}":
                _depth -= 1
                if _depth == 0:
                    _close = _i
                    break
            _i += 1
        if _close < 0:
            return ""
        return _flat[_open:_close + 1]
    except Exception:
        return ""

def get_media_url():
    global initial_url
    if initial_url and initial_url not in tried_urls:
        url = initial_url
        initial_url = ""
        return url
    cmd = ["curl", "-4", "-s", "-L", "--max-time", "8", "-A", ua, "-H", "Referer: https://www.tiktok.com/"]
    if os.path.exists(cookies): cmd.extend(["-b", cookies])
    cmd.append("https://www.tiktok.com/@%s/live" % username)
    try:
        html = subprocess.check_output(cmd, timeout=15).decode('utf-8', errors='ignore')
        # FIX-REVIEW-P1-#12: room-bound reconnect. Chi xem xet object JSON cua
        # phong dich (uniqueId/username khop). Phong khac (offline hay co URL)
        # khong anh huong den quyet dinh cua phong nay.
        _scope = _room_object_text(html, username)
        if not _scope:
            return ""
        _has_off = ('"liveroomstatus":4' in _scope or '"liveroom":null' in _scope
                    or 'livehasended' in _scope or 'thislivehasended' in _scope)
        if _has_off:
            return ""
        urls = extract_media_urls(_scope)
        for url in urls[:6]:
            probe = ["curl", "-4", "-s", "-L", "--http1.1", "-r", "0-1", "--max-time", "6", "--connect-timeout", "4", "-A", ua, "-H", "Referer: https://www.tiktok.com/", "-o", "/dev/null", "-w", "%{http_code}"]
            if os.path.exists(cookies) and "tiktokcdn" not in url.lower():
                probe.extend(["-b", cookies])
            probe.append(url)
            try:
                code = subprocess.check_output(probe, timeout=8).decode("utf-8", errors="ignore").strip()
            except Exception:
                code = "0"
            if code.startswith(("2", "3")) and url not in tried_urls:
                return url
        preferred_unknown = []
        preferred_unknown.extend([u for u in urls[:6] if "_ld" in u.lower()])
        preferred_unknown.extend([u for u in urls[:6] if ".m3u8" in u.lower()])
        preferred_unknown.extend([u for u in urls[:6] if "_hd" not in u.lower()])
        preferred_unknown.extend(urls[:6])
        for url in preferred_unknown:
            if url not in tried_urls:
                return url
    except Exception as e:
        sys.stderr.write("[TikTok] get_media_url failed: %s\\n" % (e,))
    return ""

MAX_WAIT_NO_DATA = 45
START_TIME = time.time()

fail_count = 0
has_data = False
while True:
    if not has_data and (time.time() - START_TIME) > MAX_WAIT_NO_DATA:
        try:
            if os.path.exists(out_file) and os.path.getsize(out_file) == 0:
                os.remove(out_file)
        except Exception:
            pass
        break
    media_url = get_media_url()
    if not media_url:
        fail_count += 1
        if fail_count > 2: break
        time.sleep(10)
        continue
    tried_urls.add(media_url)
    fail_count = 0
    cmd = ["/usr/bin/ffmpeg", "-y", "-loglevel", "warning", "-rw_timeout", "20000000", "-user_agent", ua]
    crlf = chr(13) + chr(10)
    cmd.extend(["-headers", "Referer: https://www.tiktok.com/" + crlf])
    cmd.extend(["-i", media_url, "-c", "copy", "-bsf:a", "aac_adtstoasc", "-f", "mpegts", "pipe:1"])
    try:
        before_size = os.path.getsize(out_file) if os.path.exists(out_file) else 0
        with open(out_file, "ab") as f:
            proc = subprocess.run(cmd, stdout=f, stderr=sys.stderr)
        after_size = os.path.getsize(out_file) if os.path.exists(out_file) else 0
        if after_size > before_size:
            has_data = True
        else:
            fail_count += 1
            if (not has_data) and fail_count > 2:
                break
    finally:
        pass
    time.sleep(3)
"""
            # FIX-ENOSPC: ghi wrapper sang HDD tmp thay vì /tmp tmpfs (490M).
            # Log 2026-09-15: 30 lỗi [Errno 28] khi /tmp đầy — watcher retry
            # mỗi 5 phút nên spam. Ghi HDD + dọn wrapper khi job xong.
            _wrapper_dir = _make_hdd_tmp_dir("livestream_wrapper")
            if not _wrapper_dir:
                log.error("[Livestream] Không tạo được thư mục tạm cho wrapper, bỏ qua phiên ghi.")
                if claimed_recording_key:
                    with _livestream_lock:
                        _livestream_starting_claims.pop(claimed_recording_key, None)
                return jsonify({
                    "error": "Không tạo được thư mục tạm cho phiên ghi.",
                    "reason": "tmp_unavailable",
                }), 507
            wrapper_path = os.path.join(_wrapper_dir, "loop_%s.py" % timestamp_str)
            try:
                with open(wrapper_path, "w", encoding="utf-8") as f:
                    f.write(loop_script)
            except OSError as e:
                log.error("[Livestream] Không ghi được wrapper %s: %s", wrapper_path, e)
                _cleanup_job_tmp(_wrapper_dir)
                if claimed_recording_key:
                    with _livestream_lock:
                        _livestream_starting_claims.pop(claimed_recording_key, None)
                return jsonify({
                    "error": "Ổ đĩa tạm đầy, không bắt đầu ghi hình được.",
                    "reason": "no_space",
                }), 507

            direct_output_file = direct_output_file.replace(".mp4", ".ts")

            cmd = [
                "python3", wrapper_path,
                watch_username or original_record_url.split("@")[-1].split("/")[0],
                direct_output_file,
                cookies_path,
                tiktok_user_agent,
                live_url
            ]
        else:
            # Truong hop fallback: live_url co the la URL FLV CDN da scrape ra,
            # nhung CDN tra Content-Type không ph?i FLV. Reset ve URL goc cua
            # user de yt-dlp scrape lai theo cach cua no.
            original_user_url = body.get("url", "").strip()
            if original_user_url and "tiktok.com" in original_user_url:
                live_url = original_user_url
            cmd.append(live_url)

            # Pre-check: yt-dlp --dump-json để xác nhận user có đang live không
            if "tiktok.com" in live_url.lower():
                try:
                    precheck_cmd = [ytdlp_bin, "--dump-json", "--no-download", "--socket-timeout", "10", live_url]
                    if os.path.exists(cookies_path):
                        precheck_cmd[4:4] = ["--cookies", cookies_path]
                    precheck_proc = subprocess.run(precheck_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
                    if precheck_proc.returncode != 0:
                        precheck_err = (precheck_proc.stderr or b"").decode("utf-8", errors="ignore").lower()
                        if any(sig in precheck_err for sig in ("not currently live", "is offline", "room is currently not available", "this live has ended")):
                            return jsonify({
                                "error": "User hiện không đang live — TikTok xác nhận offline. Hãy kiểm tra lại link hoặc thử lại sau.",
                                "reason": "user_offline",
                            }), 404
                except subprocess.TimeoutExpired:
                    log.warning("[Livestream] yt-dlp pre-check timeout, bỏ qua và tiếp tục ghi")
                except Exception as e:
                    log.warning("[Livestream] yt-dlp pre-check lỗi: %s, bỏ qua", e)

        # Log file rieng cho debug
        log_dir = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "livestream_logs")
        try:
            os.makedirs(log_dir, exist_ok=True)
        except Exception:
            pass
        # FIX-AUDIT-L9: log mang job_id (da unique) thay vi chi timestamp giay.
        # Hai job cung giay truoc day chung 1 file log (open 'w' truncate lan
        # nhau, 2 writer tron noi dung) — doc loi co the nhan loi job khac.
        log_file = os.path.join(log_dir, "live_%s.log" % job_id)
        tmp_dir = _make_hdd_tmp_dir("livestream_%s" % job_id)
        if cmd and cmd[0] == ytdlp_bin:
            if tmp_dir:
                cmd[-1:-1] = ["--paths", "temp:%s" % tmp_dir]
            cmd[-1:-1] = ["--"]

        recording_key = _livestream_recording_key(platform, original_record_url, stable_id or watch_username)
        with _livestream_lock:
            # FIX-REVIEW-24/09-#4: tu choi start moi trong luc drain truoc restart.
            if _livestream_draining:
                return jsonify({
                    "error": "Service dang drain truoc restart, thu lai sau it phut.",
                    "reason": "draining",
                }), 503
            existing_job_id, existing_info = _livestream_active_job_for_key_locked(recording_key)
            if existing_job_id:
                return jsonify({
                    "job_id": existing_job_id,
                    "pid": existing_info.get("pid"),
                    "platform": existing_info.get("platform", platform),
                    "save_folder": "Livestream/",
                    "status": "recording",
                    "duplicate": True,
                    "outcome": "already_running",
                    "message": "Phiên ghi của user này đang chạy, không tạo phiên trùng."
                })
            # Stall backoff: user vua bi kill vi dung tien trien -> tu choi 2h.
            # FIX-DEADLOCK: bo 'with _livestream_lock' lồng — dang o trong khoá
            # ngoai (line tren) va threading.Lock khong reentrant => thread bi ke
            # tai lan lay khoa thu 2, ke ca watchdog/status cung bi chan. Duyet
            # truc tiep _livestream_jobs (da duoc bao ve boi khoa ngoai).
            for _oj, _oi in list(_livestream_jobs.items()):
                if _oi.get("recording_key", "") == recording_key:
                    _until = float(_oi.get("_stall_cooldown_until", 0) or 0)
                    if _until and time.time() < _until:
                        _left = int((_until - time.time()) / 60)
                        return jsonify({
                            "job_id": _oj,
                            "platform": platform,
                            "save_folder": "Livestream/",
                            "status": "cooldown",
                            "duplicate": True,
                            "outcome": "cooldown",
                            "reason": "stall_cooldown",
                            "message": "Stream dung tien trien, tam nghi %d phut truoc khi thu lai." % _left,
                        })
            recent_job_id, recent_info = _livestream_recent_job_for_key_locked(recording_key)
            if recent_job_id:
                recent_status = recent_info.get("status", "")
                recent_size = int(recent_info.get("file_size", 0) or 0)
                if recent_status in ("recording", "starting") or recent_size < 1000:
                    return jsonify({
                        "job_id": recent_job_id,
                        "pid": recent_info.get("pid"),
                        "platform": recent_info.get("platform", platform),
                        "save_folder": "Livestream/",
                        "status": recent_status or "cooldown",
                        "duplicate": True,
                        "outcome": "cooldown",
                        "reason": "same_live_session_cooldown",
                        "message": "Phiên live của user này vừa được xử lý, không tạo thêm phiên 0B trùng lặp. Watcher sẽ thử lại sau."
                    })
            claim = _livestream_starting_claims.get(recording_key)
            if claim and time.time() - float(claim.get("ts", 0) or 0) < 180:
                return jsonify({
                    "error": "Phiên ghi của user này đang được khởi tạo, vui lòng chờ trạng thái cập nhật.",
                    "reason": "recording_starting",
                }), 409
            # HW-BUDGET: cap tổng recording đồng thời (dedup trên chỉ chặn trùng
            # cùng user). Vượt cap → 429, watcher retry sau.
            # FIX-REVIEW-24/09-#11: tinh ca stopping/timeout/finalizing (con
            # writer) vao capacity — khong giai phong som khi nhom cu flush.
            # SPEC_LIVESTREAM_LIMITS §3.1+3.3: cap doc dong (env > conf > default 2).
            _live_active = sum(
                1 for _j in _livestream_jobs.values()
                if _j.get("status") in ("recording", "starting", "stopping", "timeout", "finalizing"))
            _live_starting = sum(
                1 for _c in _livestream_starting_claims.values()
                if time.time() - float(_c.get("ts", 0) or 0) < 180)
            try:
                _live_cap = int(globals().get("_get_livestream_max_concurrent", lambda: 2)())
            except Exception:
                _live_cap = 2
            try:
                if _live_cap < 1:
                    _live_cap = 1
                if _live_cap > 8:
                    _live_cap = 8
            except Exception:
                _live_cap = 2
            if _live_active + _live_starting >= _live_cap:
                log.warning("[Livestream] Từ chối phiên mới (%s): đã %d recording (cap %d)",
                            recording_key, _live_active, _live_cap)
                return jsonify({
                    "error": "NAS đang ghi tối đa %d luồng, thử lại sau." % _live_cap,
                    "reason": "max_concurrent",
                    "code": "MAX_STREAMS",
                    "cap": _live_cap,
                    "active": _live_active + _live_starting,
                    "retry_after_s": 60,
                }), 429
            _livestream_starting_claims[recording_key] = {"ts": time.time(), "url": original_record_url}
            claimed_recording_key = recording_key

        with open(log_file, "w") as lf:
            lf.write("CMD: %s\n\n" % " ".join(cmd))
            if tmp_dir:
                lf.write("TMPDIR: %s\n\n" % tmp_dir)
            # FIX-P2-2: start_new_session=True -> Popen tao process group rieng
            # (proc.pid == PGID). Khi do _livestream_kill_pid dung os.killpg()
            # moi thuc su ket ca cay (yt-dlp -> ffmpeg con). Neu khong, killpg
            # luon fail va chi kill duoc PID cha, ffmpeg con song sot giu file.
            proc = subprocess.Popen(
                cmd,
                stdout=lf, stderr=lf,
                close_fds=True,
                start_new_session=True,
                env=_job_env_with_tmp(tmp_dir)
            )

        # job_id da duoc tinh som o tren (FIX-P2-2) de dung lam dinh danh file;
        # tai day chi reuse, khong tinh lai (tranh 2 gia tri khac nhau).
        # job_id = "live_%d_%s" % (int(time.time() * 1000), uuid.uuid4().hex[:6])
        now_str = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")

        with _livestream_lock:
            # FIX-AUDIT-L4: giu Popen handle + generation trong registry, khong chi
            # so PID. Watchdog reap co chu dich qua poll()/wait (khong phu thuoc
            # lan tao subprocess tiep theo), kill(pid,0) khong phan biet zombie.
            # _proc khong JSON-serializable — snapshot dict(info) phai loai tru.
            _livestream_jobs[job_id] = {
                "url": live_url,
                "original_url": original_record_url,
                "recording_key": recording_key,
                "platform": platform,
                "pid": proc.pid,
                "_proc": proc,
                "_generation": int(time.time() * 1000),
                "status": "recording",
                "output_dir": _LIVESTREAM_DIR,
                "output_file": os.path.basename(direct_output_file) if direct_output_file else "",
                "file_size": 0,
                "started_at": now_str,
                "started_ts": time.time(),
                "quality": quality,
                "log_file": log_file,
                "timestamp_str": timestamp_str,
                "file_stem": file_stem,
                "direct_tiktok_flv": direct_tiktok_flv,
                "direct_output_path": direct_output_file,
                "watch_username": watch_username,
                "max_hours": req_max_hours,
                "tmp_dir": tmp_dir,
                "wrapper_dir": _wrapper_dir,
            }
            _livestream_starting_claims.pop(recording_key, None)
            claimed_recording_key = ""
            start_info = {k: v for k, v in _livestream_jobs[job_id].items() if not k.startswith("_")}

        # T?m dùng thumbnail generator de nhuong CPU/IO cho viec ghi livestream.
        # Watchdog se tu dong bo chan khi không cần luồng nao dang ghi.
        _set_thumbnail_auto_block("livestream", True)
        display_source = "@%s" % watch_username if watch_username else (stable_id or platform)
        # Khong log "Bat dau ghi..." ngay lap tuc de tranh spam neu file size = 0.
        # Viec log se duoc thuc hien khi file_size > 0.

        # Ghi log h? thỏng
        try:
            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
            cur = conn.cursor()
            display_source = "@%s" % watch_username if watch_username else (stable_id or platform)
            if watch_username:
                conn.close()
                raise RuntimeError("skip auto livestream start log")
            cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                        ("INFO", "Livestream",
                         "Bắt đầu ghi livestream %s cho %s (PID=%d)." % (platform, display_source, proc.pid)))
            conn.commit()
            conn.close()
        except Exception:
            pass

        log.info("[Livestream] Bắt đầu ghi %s (PID %d) → %s", platform, proc.pid, _LIVESTREAM_DIR)

        return jsonify({
            "job_id": job_id,
            "pid": proc.pid,
            "platform": platform,
            "save_folder": "Livestream/",
            "status": "recording",
            "outcome": "accepted",
            "message": "Đang ghi hình livestream %s. Video sẽ được lưu vào thư mục Livestream/." % platform.upper()
        })

    except Exception as e:
        if claimed_recording_key:
            try:
                with _livestream_lock:
                    _livestream_starting_claims.pop(claimed_recording_key, None)
            except Exception:
                pass
        log.error("[Livestream] Lỗi bắt đầu ghi hình: %s", e)
        _add_system_log("ERROR", "Livestream", "Khong bat dau duoc ghi livestream: %s" % normalize_vietnamese_message(str(e))[:240])
        return jsonify({"error": "Không bắt đầu được ghi livestream: %s" % normalize_vietnamese_message(str(e))}), 500


@app.route("/api/livestream/status", methods=["GET"])
@requires_auth
def api_livestream_status():
    """L?y trạng thái tất c? c?c livestream job."""
    jobs_snapshot = []
    with _livestream_lock:
        for jid, info in list(_livestream_jobs.items()):
            # FIX-AUDIT-L4: loai _proc/_generation khoi snapshot (Popen khong
            # JSON-serializable, generation chi dung noi bo).
            jobs_snapshot.append((jid, {k: v for k, v in info.items() if not k.startswith("_proc")}))

    result_jobs = []
    updates = {}
    directory_entries = {}
    
    for jid, info in jobs_snapshot:
        pid = info.get("pid")
        status = info.get("status", "unknown")

        # 1. Tim file output va l?y size truoc khi dảnh gia status
        file_size = int(info.get("file_size", 0) or 0)
        output_file = info.get("output_file", "")
        out_dir = info.get("output_dir", _LIVESTREAM_DIR)
        try:
            # Completed jobs already have metadata from the watchdog; polling
            # history must not occupy every API worker with disk scans.
            if status in ("recording", "stopping", "timeout", "finalizing"):
                file_size = 0
                known_paths = [info.get("direct_output_path", "")]
                if output_file:
                    known_paths.append(os.path.join(out_dir, output_file))
                latest = next((p for p in known_paths if p and os.path.isfile(p)), "")
                file_stem = info.get("file_stem", "")
                if not latest and file_stem:
                    if out_dir not in directory_entries:
                        directory_entries[out_dir] = []
                        if os.path.isdir(out_dir):
                            directory_entries[out_dir] = os.listdir(out_dir)
                    candidates = [os.path.join(out_dir, name)
                                  for name in directory_entries[out_dir]
                                  if name.startswith(file_stem) and not name.endswith(".log")]
                    candidates = [p for p in candidates if os.path.isfile(p)]
                    if candidates:
                        latest = max(candidates, key=os.path.getmtime)
                if latest:
                    output_file = os.path.basename(latest)
                    file_size = os.path.getsize(latest)
        except Exception:
            pass

        # 2. Kiểm tra process con chay khong va set status dua vao file_size
        if status == "recording":
            now_time = time.time()
            last_size = info.get("last_size", -1)
            last_size_time = info.get("last_size_time", 0)
            
            if jid not in updates:
                updates[jid] = {}
                
            if file_size > 0 and not info.get("logged_start"):
                updates[jid]["logged_start"] = True
                platform = info.get("platform", "")
                watch_username = info.get("watch_username", "")
                display_source = "@%s" % watch_username if watch_username else (info.get("stable_id") or platform)
                _log_livestream_event(
                    "INFO", jid, info,
                    "Bắt đầu ghi video %s cho %s (chất lượng: %s)" % (platform, display_source, info.get("quality", "best")),
                    "start",
                    timestamp=info.get("started_ts", 0)
                )
                
            if last_size_time == 0:
                updates[jid]["last_size"] = file_size
                updates[jid]["last_size_time"] = now_time
            elif file_size != last_size:
                updates[jid]["last_size"] = file_size
                updates[jid]["last_size_time"] = now_time
            elif now_time - last_size_time > 240:
                # FIX-AUDIT-L1: GET status chi doc snapshot, KHONG tu kill.
                # Quyet dinh stall/stop/finalize do watchdog tap trung xu ly.
                # Danh dau nghi ngo de watchdog/UI thay, khong gui signal o day.
                updates[jid]["stall_suspected"] = True
                updates[jid]["stall_since"] = last_size_time

            is_running = False
            try:
                os.kill(pid, 0)
                is_running = True
            except Exception:
                pass

            # FIX-REVIEW-193369e-#3: GET KHONG CAP NHAT TRANG THAI VONG DOI.
            # Ban cu: group sach -> gan finished/error + finished_at + log ket
            # thuc ngay tai GET; GET chay truoc watchdog co the thay FLV du
            # 150KB roi gan finished, watchdog bo qua nen khong remux/kiem
            # chung playable. Quy trinh moi: trinh bay snapshot (stopping khi
            # con writer, finalizing khi het writer cho controller xu ly);
            # CHI controller (watchdog finalize) duoc chot terminal sau xac
            # minh artifact. GET khong ghi updates status/finished/log ket thuc.
            if not is_running:
                _group_writers = []
                try:
                    _group_writers = _livestream_pids_in_group(int(pid or 0)) if pid else []
                except Exception:
                    _group_writers = []
                if _group_writers and status == "recording":
                    status = "stopping"
                elif not _group_writers:
                    # Het writer: cho controller finalize (remux/validate) roi
                    # chot terminal. GET bao finalizing, khong gan finished/error.
                    if status == "recording":
                        status = "finalizing"

        # Tinh duration
        started_ts = info.get("started_ts", 0)
        duration_sec = int(time.time() - started_ts) if started_ts > 0 else 0

        # Tinh toc do ghi trung binh
        avg_speed = ""
        if duration_sec > 0 and file_size > 0:
            avg_speed = format_bytes(int(file_size / duration_sec)) + "/s"

        error_reason = ""
        if status == "error":
            error_reason = _livestream_error_from_log(info)
            if not error_reason:
                error_reason = "Không thể phân tích luồng stream hoặc tệp bị hỏng."
            if jid not in updates: updates[jid] = {}
            updates[jid]["error_reason"] = error_reason

        result_jobs.append({
            "job_id": jid,
            "url": info.get("url", ""),
            "platform": info.get("platform", ""),
            "status": status,
            "pid": pid,
            "output_file": output_file,
            "file_size": format_bytes(file_size) if file_size > 0 else "0 B",
            "file_size_bytes": file_size,
            "duration_seconds": duration_sec,
            "duration_display": "%dh%02dm%02ds" % (duration_sec // 3600, (duration_sec % 3600) // 60, duration_sec % 60),
            "started_ts": started_ts,
            "avg_speed": avg_speed,
            "error_reason": error_reason,
            "watch_username": info.get("watch_username", ""),
            "recording_key": info.get("recording_key", ""),
            "stall_suspected": bool(info.get("stall_suspected", False)),
            "stall_since": info.get("stall_since", 0)
        })

    try:
        deduped_jobs = {}
        for job in result_jobs:
            username = (job.get("watch_username") or "").strip().lower()
            recording_key = (job.get("recording_key") or "").strip().lower()
            output_file = (job.get("output_file") or "").strip().lower()
            key = username or recording_key or output_file or job.get("job_id", "")
            size_bytes = int(job.get("file_size_bytes") or 0)
            rank = (
                1 if job.get("status") == "recording" else 0,
                1 if size_bytes > 0 else 0,
                size_bytes,
                int(job.get("duration_seconds") or 0),
                int(job.get("started_ts") or 0),
            )
            current = deduped_jobs.get(key)
            if current is None or rank > current[0]:
                deduped_jobs[key] = (rank, job)
        result_jobs = [item[1] for item in deduped_jobs.values()]
        result_jobs.sort(key=lambda j: int(j.get("started_ts") or 0), reverse=True)
    except Exception:
        pass

    try:
        live_count = sum(1 for j in result_jobs if j["status"] == "recording")
        total_users = len(_tiktok_watch_state.get("users", []))
        if total_users > 0:
            _tiktok_watch_runtime["last_summary"] = (
                "Đã kiểm tra %d user, %d đang ghi, 0 vừa mới bắt đầu." % (total_users, live_count)
            )
    except Exception:
        pass

    if updates:
        with _livestream_lock:
            for jid, up in updates.items():
                if jid in _livestream_jobs:
                    # FIX-REVIEW-193369e-#3: GET KHONG GHI BAT KY TRANG THAI
                    # VONG DOI NAO (status/finished_at). Chi apply quan sat
                    # (last_size/last_size_time/stall_*/logged_start/error_reason).
                    # Moi terminal transition do controller (watchdog finalize)
                    # so huu sau xac minh artifact.
                    _obs = {k: v for k, v in up.items()
                            if k in ("last_size", "last_size_time",
                                     "stall_suspected", "stall_since",
                                     "logged_start", "error_reason")}
                    _livestream_jobs[jid].update(_obs)

    return jsonify({"jobs": result_jobs})

@app.route("/api/livestream/stop", methods=["POST"])
@requires_auth
def api_livestream_stop():
    """Dung ghi hảnh livestream bang job_id."""
    try:
        body = request.get_json(force=True) or {}
        job_id = body.get("job_id", "").strip()

        if not job_id:
            # Dung tất c?
            with _livestream_lock:
                for jid, info in _livestream_jobs.items():
                    if info.get("status") == "recording":
                        try:
                            # FIX-AUDIT-L3: chi signal khi group that su thuoc ve
                            # job (chong PID reuse). Khong owned -> danh dau error,
                            # khong gui signal ra ngoai.
                            owned, _ = _livestream_group_owned_by_job(int(info.get("pid") or 0), info)
                            if owned:
                                _livestream_kill_pid(info["pid"], jid)
                                info["status"] = "stopping"
                                info["_kill_ts"] = time.time()
                            else:
                                info["status"] = "error"
                                info["error_reason"] = "Tien trinh khong con thuoc ve job (nghi PID reuse) — khong gui signal."
                        except Exception:
                            pass
            return jsonify({"message": "Đã gửi lệnh dừng tất cả livestream."})

        with _livestream_lock:
            info = _livestream_jobs.get(job_id)

        if not info:
            return jsonify({"error": "Không tìm thấy tác vụ: %s" % job_id}), 404

        # FIX-AUDIT-L3: tu choi stop job da terminal — khong signal PID cu
        # (PID co the da duoc tai su dung cho tien trinh khong lien quan).
        cur_status = info.get("status", "")
        if cur_status in ("finished", "error", "stopped", "cancelled", "timeout"):
            return jsonify({"job_id": job_id, "status": cur_status,
                            "message": "Tac vu da ket thuc, khong gui lenh dung."})

        pid = info.get("pid")
        try:
            # FIX-REVIEW-24/09-#2: _proc handle con song la bang chung so huu
            # manh nhat (direct recorder co PPID==API). Uu tien signal qua
            # handle/group ngay; chi 409 khi that su khong con gi de signal.
            try:
                _stop_proc = info.get("_proc")
                _stop_alive = _stop_proc is not None and _stop_proc.poll() is None
            except Exception:
                _stop_proc, _stop_alive = None, False
            try:
                owned, _ = _livestream_group_owned_by_job(int(pid or 0), info)
            except Exception:
                owned = False
            if not owned and not _stop_alive:
                info["status"] = "error"
                info["error_reason"] = "Tien trinh khong con thuoc ve job (nghi PID reuse) — khong gui signal."
                return jsonify({"job_id": job_id, "status": "error",
                                "message": "Tien trinh khong con thuoc ve job, khong gui lenh dung."}), 409
            # FIX-P2-1: dung _livestream_kill_pid de SIGTERM ca process group
            # (pid==PGID vi start_new_session=True), diet ca ffmpeg con, khong
            # chi kill PID cha nhu truoc. Giu status="stopping" + _kill_ts de
            # watchdog tiep tuc theo doi group den khi that su chet (60s sau
            # moi SIGKILL neu con song). Truoc day danh dau "stopped" ngay nen
            # watchdog bo qua job -> ffmpeg con tiep tuc ghi sau khi cha chet.
            _livestream_kill_pid(pid, job_id)
            info["status"] = "stopping"
            info["_kill_ts"] = time.time()
            info["finished_at"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
            log.info("[Livestream] Đã dừng tác vụ %s (PID %d) theo yêu cầu.", job_id, pid)

            # Ghi log
            try:
                conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
                cur = conn.cursor()
                cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                            ("INFO", "Livestream",
                             "Đã dừng ghi livestream %s (PID=%d) theo yêu cầu người dùng." % (info.get("platform", ""), pid)))
                conn.commit()
                conn.close()
            except Exception:
                pass

            return jsonify({
                "job_id": job_id,
                "status": "stopping",
                "message": "Đã gửi lệnh dừng. Tệp video sẽ được yt-dlp hoàn tất trong vài giây."
            })
        except ProcessLookupError:
            info["status"] = "finished"
            return jsonify({"job_id": job_id, "status": "finished", "message": "Tiến trình đã kết thúc trước đó."})
        except Exception as e:
            return jsonify({"error": "Không dừng được livestream: %s" % normalize_vietnamese_message(str(e))}), 500

    except Exception as e:
        return jsonify({"error": "Không xử lý được yêu cầu dừng livestream: %s" % normalize_vietnamese_message(str(e))}), 500


# ============ SOCIAL EXTRACTOR (YT-DLP) ============
# Endpoint: POST /api/ytdlp/download  { url, save_folder, quality }
#
# Cach hoat dong:
#  1. Nhan link tu Android
#  2. Chay yt-dlp ngam (Popen, khong cho doi) de khong block Flask
#  3. Tr? v? ngay lap tuc {"message": "Đã nh?n lảnh..."}
#  4. yt-dlp tu tai va l?u vao WEBDAV_FILE_ROOT/save_folder
#
# Yeu cau cai dat: pip3 install yt-dlp  (hoac pip install yt-dlp)
# Hoac: apt-get install yt-dlp  (Debian/OMV)

_ytdlp_jobs = {}  # {job_id: {url, status, pid}}
_ytdlp_lock = threading.Lock()

@app.route("/api/ytdlp/download", methods=["POST"])
@requires_auth
def api_ytdlp_download():
    """Nhan link video, chay yt-dlp ngam va l?u vao NAS."""
    try:
        body = request.get_json(force=True) or {}
        video_url = body.get("url", "").strip()
        # CC-005 P0: validate URL qua _social_validate_url (allowlist
        # youtube/tiktok/fb/instagram + DNS public IP) — truoc day url tho
        # append thang vao cmd yt-dlp: SSRF file://, IP noi bo, metadata
        # 169.254.x, quet LAN, lap HDD.
        try:
            if not video_url or not _social_validate_url(video_url):
                return jsonify({"error": "URL video khong duoc ho tro hoac khong an toan"}), 400
        except Exception:
            return jsonify({"error": "URL video khong duoc ho tro hoac khong an toan"}), 400
        save_folder_input = body.get("save_folder", "Downloads/social/")
        save_folder = _social_sanitize_folder(save_folder_input)
        dest_dir = _resolve_webdav_request_path(save_folder)
        if not dest_dir:
            return jsonify({"error": "Thư mục lưu không hợp lệ hoặc nằm ngoài phạm vi"}), 400
        try:
            os.makedirs(dest_dir, exist_ok=True)
        except Exception:
            pass

        # Format chat luồng: uu tien mp4 HD, fallback best
        quality = body.get("quality", "video").strip().lower()
        format_str = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
        if quality == "audio":
            format_str = "bestaudio[ext=m4a]/bestaudio"

        # Output template: ten file goc cua video, trong dest_dir
        output_template = os.path.join(dest_dir, "%(title).100s.%(ext)s")

        # CC-004 P2-5: gioi han yt-dlp chay dong thoi (mac dinh 2, toi da 4) —
        # truoc day khong cap, bam tai hang loat -> N tien trinh yt-dlp + ffmpeg
        # merge giet RK3328 1GB (giong loi livestream cap 16 cu).
        try:
            _ydl_cap = int(_get_ytdlp_max_concurrent())
        except Exception:
            _ydl_cap = 2
        try:
            with _ytdlp_lock:
                _ydl_running = sum(
                    1 for _ji in _ytdlp_jobs.values()
                    if str(_ji.get("status", "running")) in ("running", "downloading", "queued")
                )
            if _ydl_running >= _ydl_cap:
                return jsonify({
                    "error": "NAS dang tai toi da %d video cung luc, vui long doi xong roi tai tiep." % _ydl_cap,
                    "running": _ydl_running,
                    "cap": _ydl_cap,
                }), 429
        except Exception:
            pass

        # P1-8: ytdlp_bin CHUA duoc dinh nghia trong scope nay (NameError khi
        # dung lenh). Cac endpoint khac (social/livestream) goi _find_ytdlp_bin()
        # dung cach. Tra 503 ro rang neu chua cai dat.
        ytdlp_bin = _find_ytdlp_bin()
        if not ytdlp_bin:
            return jsonify({
                "error": "yt-dlp chưa được cài đặt trên NAS",
                "install_hint": "wget https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64 -O /usr/local/bin/yt-dlp && chmod +x /usr/local/bin/yt-dlp"
            }), 503

        # Khoi dong yt-dlp ngam (KHONG cho doi - tr? v? ngay cho Android)
        cmd = [
            ytdlp_bin,
            "--no-playlist",
            "--no-warnings",
            "--quiet",
            "-f", format_str,
            "-o", output_template,
            "--socket-timeout", "30",
            "--retries", "3",
        ]

        # Inject cookies.txt + browser User-Agent de qua mat bot protection
        # (chu yeu can cho TikTok, dong thoi vo hai voi cac platform khac)
        cookies_path = os.path.join(WEBDAV_FILE_ROOT, "cookies.txt")
        if os.path.exists(cookies_path):
            cmd.extend(["--cookies", cookies_path])
        cmd.extend([
            "--user-agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        ])
        if "tiktok" in video_url.lower():
            cmd.extend(["--add-header", "Referer: https://www.tiktok.com/"])

        cmd.append(video_url)

        # Ghi log yt-dlp ra file rieng de debug
        log_dir = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "ytdlp_logs")
        try:
            os.makedirs(log_dir, exist_ok=True)
        except Exception:
            pass
        log_file = os.path.join(log_dir, "ytdlp_%s.log" % int(time.time()))
        tmp_dir = _make_hdd_tmp_dir("ytdlp_%s" % int(time.time()))
        if tmp_dir and cmd:
            cmd[-1:-1] = ["--paths", "temp:%s" % tmp_dir]
        if cmd:
            cmd[-1:-1] = ["--"]

        with open(log_file, "w") as lf:
            if tmp_dir:
                lf.write("TMPDIR: %s\n\n" % tmp_dir)
            proc = subprocess.Popen(
                cmd,
                stdout=lf, stderr=lf,
                close_fds=True,
                env=_job_env_with_tmp(tmp_dir)
            )

        job_id = "ytdlp_%d_%s" % (int(time.time() * 1000), uuid.uuid4().hex[:8])
        with _ytdlp_lock:
            _ytdlp_jobs[job_id] = {
                "url": video_url,
                "folder": dest_dir,
                "pid": proc.pid,
                "_proc": proc,
                "started_at": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
                "log_file": log_file,
                "tmp_dir": tmp_dir
            }
        _set_thumbnail_auto_block("ytdlp", True)

        # Ghi log h? thỏng
        try:
            conn = sqlite3.connect(DB_PATH, timeout=_cfg_sqlite_timeout("sqlite_timeout_20", 20.0))
            cur = conn.cursor()
            cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                        ("INFO", "SocialExtract",
                         "Đã nhận lệnh yt-dlp PID=%d cho URL: %s" % (proc.pid, video_url[:100])))
            conn.commit()
            conn.close()
        except Exception:
            pass

        return jsonify({
            "job_id": job_id,
            "pid": proc.pid,
            "save_folder": dest_dir,
            "message": "NAS đã nhận lệnh tải video. Video sẽ xuất hiện trong %s sau vài phút." % save_folder
        })

    except Exception as e:
        return jsonify({"error": "Không bắt đầu được tải video: %s" % normalize_vietnamese_message(str(e))}), 500


@app.route("/api/ytdlp/status", methods=["GET"])
@requires_auth
def api_ytdlp_status():
    """Kiểm tra trạng thái cac job yt-dlp dang chay."""
    with _ytdlp_lock:
        active = {}
        for jid, info in list(_ytdlp_jobs.items()):
            pid = info.get("pid")
            _proc = info.get("_proc")
            is_running = False
            if _proc is not None:
                try:
                    if _proc.poll() is None:
                        is_running = True
                    else:
                        try:
                            _proc.wait(timeout=0)
                        except Exception:
                            pass
                except Exception:
                    pass
            else:
                try:
                    os.kill(pid, 0)  # Signal 0 = check ton tai
                    is_running = True
                except Exception:
                    pass
            if not is_running:
                reason = ""
                log_file = info.get("log_file", "")
                if log_file and os.path.exists(log_file):
                    try:
                        with open(log_file, "r", encoding="utf-8", errors="ignore") as f:
                            reason = "".join(f.readlines()[-8:])[-500:]
                    except Exception:
                        reason = ""
                _add_system_log_once(
                    "ytdlp_done:%s" % jid,
                    "INFO" if not reason else "WARNING",
                    "SocialExtract",
                    "Tac vu yt-dlp ket thuc pid=%s url=%s folder=%s%s" % (
                        pid,
                        info.get("url", "")[:120],
                        info.get("folder", ""),
                        ("; log tail=%s" % reason.strip()) if reason else ""
                    ),
                    600
                )
                _cleanup_job_tmp(info.get("tmp_dir", ""))
                _cleanup_runtime_tmp_artifacts(max_age_minutes=30)
                del _ytdlp_jobs[jid]
            else:
                active[jid] = {k: v for k, v in info.items() if not str(k).startswith("_")}
        _set_thumbnail_auto_block("ytdlp", len(active) > 0)
    return jsonify({"active_jobs": len(active), "jobs": list(active.values())})


# ============ KHOI CHAY HELPER FUNCTIONS (TOP-LEVEL) ============
def _graceful_shutdown(signum, frame):
    """Dung server sach, không để lai zombie."""
    log.info("[Shutdown] Nhận tín hiệu %s, đang dọn dẹp...", signum)
    try:
        parent = psutil.Process(os.getpid())
        children = parent.children(recursive=True)
        for child in children:
            try:
                child.terminate()
            except Exception:
                pass
        _, alive = psutil.wait_procs(children, timeout=3)
        for child in alive:
            try:
                child.kill()
            except Exception:
                pass
    except Exception as e:
        log.warning("[Shutdown] Khong don duoc child processes: %s", e)
    try:
        os.remove(PID_FILE)
    except Exception:
        pass
    try:
        for info in list(_livestream_jobs.values()) + list(_ytdlp_jobs.values()):
            _cleanup_job_tmp(info.get("tmp_dir", ""))
        _cleanup_runtime_tmp_artifacts(max_age_minutes=30)
    except Exception:
        pass
    try:
        sys.stdout.flush()
        sys.stderr.flush()
    except Exception:
        pass
    os._exit(128 + signum)


def _cleanup_pid():
    try:
        os.remove(PID_FILE)
    except Exception:
        pass


def _force_free_port(port):
    """Terminate only our own stale NAS API listener on port before bind."""
    import socket as _socket
    try:
        test_sock = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
        test_sock.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
        test_sock.bind(('0.0.0.0', port))
        test_sock.close()
        return
    except OSError:
        pass
    log.warning("[Port %d] Dang bi chiem, kiem tra listener an toan...", port)
    targets = []
    seen_pids = set()
    try:
        for conn in psutil.net_connections(kind="inet"):
            if conn.status != psutil.CONN_LISTEN or not conn.laddr or conn.laddr.port != port or not conn.pid:
                continue
            if conn.pid in seen_pids:
                continue
            try:
                proc = psutil.Process(conn.pid)
                name = (proc.name() or "").lower()
                cmdline = " ".join(proc.cmdline()).lower()
                exe_name = ""
                try:
                    exe_name = os.path.basename(os.readlink("/proc/%s/exe" % proc.pid)).lower()
                except OSError:
                    pass
                if "nas_api_server.py" not in cmdline and "nas_api_server.py" not in exe_name:
                    log.warning("[Port %d] Skip PID %d (%s) - khong phai NAS API.", port, proc.pid, name)
                    continue
                targets.append(proc)
                seen_pids.add(conn.pid)
            except psutil.NoSuchProcess:
                continue
    except (psutil.Error, OSError) as e:
        log.warning("[Port %d] Khong doc duoc listener list: %s", port, e)
        return
    if not targets:
        log.warning("[Port %d] Khong tim thay NAS API listener nao, bo qua.", port)
        return
    for proc in targets:
        try:
            log.warning("[Port %d] Dang terminate PID %d (%s)...", port, proc.pid, proc.name())
            proc.terminate()
        except psutil.NoSuchProcess:
            continue
        except Exception as e:
            log.warning("[Port %d] Khong terminate duoc PID %d: %s", port, getattr(proc, "pid", -1), e)
    _, alive = psutil.wait_procs(targets, timeout=5)
    for proc in alive:
        try:
            log.warning("[Port %d] PID %d chua dung, kill...", port, proc.pid)
            proc.kill()
        except psutil.NoSuchProcess:
            continue
        except Exception as e:
            log.warning("[Port %d] Khong kill duoc PID %d: %s", port, getattr(proc, "pid", -1), e)
    if alive:
        psutil.wait_procs(alive, timeout=5)
    log.info("[Port %d] Da giai phong.", port)


def _auto_resource_reclaimer_thread():
    """TRÌNH TỰ ĐỘNG GIẢI PHÓNG CPU/RAM VÀ DIỆT TIẾN TRÌNH THỪA"""
    log.info("[ResourceReclaimer] Trình tự động giải phóng RAM/CPU đã khởi động.")
    while True:
        try:
            time.sleep(180)
            try:
                gc.collect()
                try:
                    libc = ctypes.CDLL('libc.so.6')
                    libc.malloc_trim(0)
                except Exception:
                    pass
            except Exception:
                pass

            try:
                mem = psutil.virtual_memory()
                if getattr(mem, 'available', mem.free) < 250 * 1024 * 1024:
                    with open("/proc/sys/vm/drop_caches", "w") as f:
                        f.write("3\n")
                    log.info("[ResourceReclaimer] Đã tự động giải phóng Kernel Page Cache VFS.")
            except Exception:
                pass

            now = time.time()
            current_pid = os.getpid()
            for proc in psutil.process_iter():
                try:
                    pid = proc.pid
                    if pid == current_pid or pid <= 100:
                        continue
                    
                    name = (proc.name() or "").lower()
                    cmdline = " ".join(proc.cmdline() or []).lower()
                    status = str(proc.status())

                    if any(protected in name or protected in cmdline for protected in ('ffmpeg', 'yt-dlp', 'ytdlp', 'aria2c', 'rsync', 'rclone')):
                        continue
                    
                    if status in ('zombie', 'dead') or status == getattr(psutil, 'STATUS_ZOMBIE', 'zombie'):
                        try:
                            proc.kill()
                            log.info("[ResourceReclaimer] Đã dọn tiến trình Zombie PID=%d (%s)" % (pid, name))
                        except Exception:
                            pass
                        continue

                    if name in ('curl', 'wget') and (now - proc.create_time() > 600):
                        try:
                            proc.kill()
                            log.info("[ResourceReclaimer] Đã tiêu diệt curl/wget treo > 10 phút PID=%d" % pid)
                        except Exception:
                            pass
                        continue

                    if name == 'containerd':
                        try:
                            proc.kill()
                            log.info("[ResourceReclaimer] Đã tiêu diệt containerd mồ côi PID=%d" % pid)
                        except Exception:
                            pass
                        continue

                    if "python" in name and ("/tmp/loop_" in cmdline or "-c import" in cmdline) and (now - proc.create_time() > 1200):
                        try:
                            proc.kill()
                            log.info("[ResourceReclaimer] Đã tiêu diệt Python test script mồ côi PID=%d" % pid)
                        except Exception:
                            pass
                        continue

                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue

            try:
                if os.path.exists("/tmp"):
                    for f in os.listdir("/tmp"):
                        if f.startswith("loop_") or f.startswith("ffmpeg_") or f.startswith("thumb_tmp_") or f.startswith("test_ytdlp_") or f.startswith("nas_fast_index") or f.endswith(".mp4") or f.endswith(".webm") or f.endswith(".ts"):
                            fpath = os.path.join("/tmp", f)
                            try:
                                if os.path.isfile(fpath) and (now - os.path.getmtime(fpath) > 1800):
                                    os.remove(fpath)
                                    log.info("[ResourceReclaimer] Đã xóa file đệm rác RAM-disk /tmp: %s" % f)
                            except Exception:
                                pass
            except Exception:
                pass

        except Exception as e:
            log.warning("[ResourceReclaimer] Lỗi: %s" % str(e))


def _get_ssl_config():
    """P1-1: Resolves SSL certificate and key paths for HTTPS/WSS if present."""
    cert = os.environ.get("NAS_SSL_CERT", "")
    key = os.environ.get("NAS_SSL_KEY", "")
    if cert and key and os.path.exists(cert) and os.path.exists(key):
        return cert, key

    default_certs = [
        ("/etc/ssl/certs/nas_api.crt", "/etc/ssl/private/nas_api.key"),
        ("/opt/nas_api.crt", "/opt/nas_api.key"),
        ("/var/www/webdav/nas_api.crt", "/var/www/webdav/nas_api.key")
    ]
    for c, k in default_certs:
        if os.path.exists(c) and os.path.exists(k):
            return c, k
    return None, None


def _make_ssl_context(cert, key):
    """P1-1: Creates an SSLContext supporting TLS auto-negotiation (TLS 1.2 / TLS 1.3)."""
    if not cert or not key or ssl is None:
        return None
    try:
        if hasattr(ssl, "create_default_context"):
            ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        else:
            protocol = getattr(ssl, "PROTOCOL_TLS", getattr(ssl, "PROTOCOL_SSLv23", getattr(ssl, "PROTOCOL_TLSv1_2", None)))
            if protocol is None:
                return None
            ctx = ssl.SSLContext(protocol)
        ctx.load_cert_chain(certfile=cert, keyfile=key)
        return ctx
    except Exception as e:
        safe_cert_name = os.path.basename(cert) if cert else "unknown"
        log.warning("[HTTPS] Lỗi tạo SSL context (%s: %s)" % (safe_cert_name, str(e)))
        return None


def run_flask():
    cert, key = _get_ssl_config()
    ssl_ctx = _make_ssl_context(cert, key)
    if ssl_ctx is not None:
        try:
            import tornado.wsgi
            import tornado.httpserver
            import tornado.ioloop

            container = tornado.wsgi.WSGIContainer(app)
            http_server = tornado.httpserver.HTTPServer(container, ssl_options=ssl_ctx)
            http_server.listen(5050)
            log.info("[HTTPS] Flask API Server running with SSL/TLS (Tornado WSGI) on port 5050")
            tornado.ioloop.IOLoop.current().start()
            return
        except Exception as e:
            log.warning("[HTTPS] SSL setup for Flask failed (%s), falling back to Waitress HTTP" % str(e))

    try:
        from waitress import serve
        serve(app, host="0.0.0.0", port=5050, threads=6, connection_limit=50)
    except ImportError:
        log.warning("Thiếu thư viện Waitress. Vui lòng chạy: pip3 install waitress")
        app.run(host="0.0.0.0", port=5050, debug=False, threaded=True)



# ============ KHOI CHAY MAIN ============
if __name__ == "__main__":
    main_loop = tornado.ioloop.IOLoop.current()
    _cleanup_stale_job_tmp(max_age_hours=1)
    _cleanup_runtime_tmp_artifacts(max_age_minutes=30)
    
    signal.signal(signal.SIGTERM, _graceful_shutdown)
    signal.signal(signal.SIGINT, _graceful_shutdown)
    atexit.register(_cleanup_pid)

    try:
        with open(PID_FILE, 'w') as f:
            f.write(str(os.getpid()))
    except Exception:
        pass
    
    bind_host = "0.0.0.0"

    _force_free_port(5050)
    _force_free_port(5051)

    log.info("=" * 50)
    log.info("NAS API Server - Chainedbox L1 Pro")
    log.info("Port: 5050 (API) | 5051 (WebSocket)")
    log.info("Bind: %s (hỗ trợ LAN và Tailscale)", bind_host)
    log.info("User: %s", WEBDAV_USER if WEBDAV_USER else "(chưa cấu hình)")
    log.info("PID: %d (file: %s)", os.getpid(), PID_FILE)
    if _lan_whitelist or _lan_subnets:
        log.info("LAN Whitelist: %d IP, %d subnet", len(_lan_whitelist), len(_lan_subnets))
    else:
        log.info("LAN Whitelist: (trống - chỉ truy cập qua Tailscale hoặc đăng nhập)")
    log.info("=" * 50)
    _ensure_trash_directories()
    _init_index_db()

    threading.Thread(target=monitor_scanners, daemon=True).start()
    threading.Thread(target=monitor_journalctl, daemon=True).start()
    threading.Thread(target=_update_status_cache, daemon=True).start()
    threading.Thread(target=_thumbnail_generator, daemon=True).start()
    log.info("[Thumbnail] Trình tạo ảnh thu nhỏ nền đã khởi động.")
    
    threading.Thread(target=_system_health_watchdog, daemon=True).start()
    log.info("[Watchdog] Trình giám sát sức khỏe hệ thống đã khởi động.")
    threading.Thread(target=_social_jobs_cleanup_daemon, daemon=True).start()
    log.info("[Social] Background cleanup daemon started (purge expired jobs every 5m).")

    threading.Thread(target=_disk_health_watchdog, daemon=True).start()
    log.info("[DiskHealth] Trình theo dõi sức khỏe HDD đã khởi động.")
    threading.Thread(target=_scheduled_backup_worker, daemon=True).start()
    log.info("[BackupSchedule] Trình lên lịch backup tự động đã khởi động.")
    threading.Thread(target=_usb_import_watchdog, daemon=True, name="USBImportWatchdog").start()
    log.info("[USBImport] Trình tự động phát hiện và copy USB đã khởi động.")
    threading.Thread(target=_sleep_schedule_worker, daemon=True).start()
    log.info("[SleepSchedule] Trình lên lịch HDD spindown đã khởi động.")

    threading.Thread(target=_cron_worker, daemon=True).start()
    log.info("[Cron] Tác vụ tự động dọn dẹp và cảnh báo chủ động đã khởi động.")

    threading.Thread(target=_tiktok_live_watchdog, daemon=True, name="TikTokLiveWatchdog").start()
    log.info("[TikTokWatch] Watcher TikTok live đã khởi động trên NAS.")
    threading.Thread(target=_auto_resource_reclaimer_thread, daemon=True, name="ResourceReclaimer").start()
    log.info("[ResourceReclaimer] Watcher giải phóng tài nguyên ngầm đã khởi động.")
    threading.Thread(target=_file_indexer_watchdog, daemon=True, name="FileIndexWatchdog").start()
    log.info("[FileIndex] Watcher lập chỉ mục SQLite siêu tốc đã được khởi động.")
    threading.Thread(target=_nas_api_self_watchdog, daemon=True, name="NasApiSelfWatchdog").start()
    log.info("[NasAPI] Self-watchdog tự khởi động lại đã được kích hoạt.")
    
    threading.Thread(target=run_flask, daemon=True).start()

    # Chay Tornado WebSocket tren port 5051 (main thread) dung de ban thong bao (Alerts)
    ws_app = tornado.web.Application([
        (r"/ws/alerts", AlertWebSocket),
    ])
    # Manual socket voi SO_REUSEADDR de trảnh lỗi Address already in use (TIME_WAIT)
    _ws_sock = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
    _ws_sock.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
    _ws_sock.bind((bind_host, 5051))
    _ws_sock.listen(128)
    _ws_sock.setblocking(False)

    cert, key = _get_ssl_config()
    ws_ssl_opts = _make_ssl_context(cert, key)
    if ws_ssl_opts is not None:
        log.info("[HTTPS] WebSocket Alert Server running with WSS (TLS) on port 5051")

    ws_server = tornado.httpserver.HTTPServer(ws_app, ssl_options=ws_ssl_opts)
    ws_server.add_socket(_ws_sock)
    log.info("Server đã khởi động thành công!")
    main_loop.start()


