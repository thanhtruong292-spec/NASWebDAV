#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NAS API Server cho Chainedbox L1 Pro (rk3328)
Phuc vu du lieu he thong real-time cho ung dung Android NAS WebDAV.

Cai dat: pip3 install flask psutil tornado
Chay:    python3 nas_api_server.py
Tu dong: Them vao /etc/rc.local hoac tao systemd service

Port: 5000 (HTTP)
"""

import os
import sys
# THÊM DÒNG NÀY ĐỂ TRỊ BỆNH 1.5GB RAM ẢO CỦA LINUX GLIBC
os.environ["MALLOC_ARENA_MAX"] = "2"
import json
import time
import uuid
import subprocess
import threading
import logging
import re as _re_module
import shutil
from functools import wraps
import sqlite3
import base64
import urllib.request
import urllib.error
import urllib.parse

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
import atexit
import shutil

# ============ LOGGING TIEU CHUAN ============
# Ghi log ra file /var/log/nas_api.log + console, co rotation
_log_formatter = logging.Formatter(
    "%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
_log_handler_console = logging.StreamHandler(sys.stdout)
_log_handler_console.setFormatter(_log_formatter)
try:
    from logging.handlers import RotatingFileHandler
    _log_handler_file = RotatingFileHandler(
        "/var/log/nas_api.log", maxBytes=5*1024*1024, backupCount=3, encoding="utf-8"
    )
    _log_handler_file.setFormatter(_log_formatter)
except Exception:
    _log_handler_file = None

log = logging.getLogger("NasAPI")
log.setLevel(logging.INFO)
log.addHandler(_log_handler_console)
if _log_handler_file:
    log.addHandler(_log_handler_file)

import tornado.ioloop
import tornado.web
import tornado.websocket

main_loop = None # Gắn với main thread để các background thread gọi callback



from flask import Flask, request, jsonify, Response

try:
    import psutil
except ImportError:
    print("Thiếu thư viện psutil. Cài đặt: pip3 install psutil")
    sys.exit(1)

app = Flask(__name__)

def _get_webdav_root():
    """Doc WebDAV root tu config OMV (/var/www/webdav/config/config.php)"""
    config_file = "/var/www/webdav/config/config.php"
    try:
        if os.path.exists(config_file):
            with open(config_file, "r") as f:
                for line in f:
                    # Tim dong: $publicDir = '/path/to/dir';
                    if "$publicDir" in line and "=" in line:
                        path = line.split("'")[1] if "'" in line else line.split('"')[1]
                        return path.rstrip("/")
    except Exception:
        pass
    return "/srv/dev-disk-by-label-data"  # Fallback mac dinh

WEBDAV_FILE_ROOT = _get_webdav_root()

DB_PATH = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "nas_index.db")
PID_FILE = "/var/run/nas_api_server.pid"
LAN_WHITELIST_PATH = "/etc/nas/lan_whitelist.conf"
WEBDAV_LOG = "/var/log/nginx/openmediavault-webgui_access.log"
AI_TAGS_PATH = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "ai_tags.json")
NAS_TMP_ROOT = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "tmp")

def _make_hdd_tmp_dir(prefix):
    """Tao thu muc tmp rieng tren HDD de tranh lam day /tmp tmpfs."""
    safe_prefix = _re_module.sub(r"[^A-Za-z0-9_.-]+", "_", str(prefix or "job")).strip("_") or "job"
    try:
        os.makedirs(NAS_TMP_ROOT, exist_ok=True)
        tmp_dir = os.path.join(
            NAS_TMP_ROOT,
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

def _cleanup_runtime_tmp_artifacts(max_age_minutes=30):
    """Don rac tmp do PyInstaller/ffmpeg de lai, khong dung vao socket he thong."""
    deleted = 0
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
    """FIX: KHONG duoc crash server khi HDD bi I/O error.

    Truoc day: init_db chay ngay khi import module va goi sqlite3.connect tren
    DB_PATH (nam tren HDD). Neu HDD bi loi (filesystem ro/inode hong) thi
    OperationalError -> module import fail -> systemd restart loop vinh vien.

    Logic moi: bat het exception, log warning, return False. Server van len
    duoc, cac endpoint dung @requires_auth se fallback DB-less va van dang
    nhap duoc bang Basic Auth.
    """
    try:
        try:
            os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
        except OSError as e:
            log.warning("[init_db] Khong tao duoc thu muc (HDD co the loi): %s", e)
            # Khong return — thu tiep connect xem co the DB file van con OK
        conn = sqlite3.connect(DB_PATH, timeout=20.0)
        cur = conn.cursor()
        cur.execute('CREATE TABLE IF NOT EXISTS banned_ips (ip TEXT PRIMARY KEY, reason TEXT, banned_at DATETIME)')
        cur.execute('CREATE TABLE IF NOT EXISTS auth_attempts (ip TEXT PRIMARY KEY, count INTEGER)')
        cur.execute('CREATE TABLE IF NOT EXISTS authorized_ips (ip TEXT PRIMARY KEY, added_at DATETIME)')
        cur.execute('CREATE TABLE IF NOT EXISTS system_logs (id INTEGER PRIMARY KEY, type TEXT, module TEXT, message TEXT, timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)')
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
        conn.commit()
        conn.close()
        log.info("[init_db] DB san sang.")
    except Exception as e:
        # HDD/DB khong dung duoc -> server van phai len. Login se fallback
        # DB-less mode (chi Basic Auth, khong remember IP).
        log.error("[init_db] DB KHONG mo duoc — server chay che do DB-less: %s", e)

    # DỌN DẸP RÁC RAM (TMPFS) LỊCH SỬ KHI KHỞI ĐỘNG CỦA LỖI OOM
    import shutil
    try:
        shutil.rmtree("/tmp/nas_transcode", ignore_errors=True)
    except Exception:
        pass

init_db()

# ============ LAN IP WHITELIST ============
# Doc danh sach IP LAN duoc phep truy cap (khong can Tailscale)
# File /etc/nas/lan_whitelist.conf, moi dong 1 IP hoac CIDR (vd: 192.168.1.0/24)
_lan_whitelist = set()
_lan_subnets = []

def _load_lan_whitelist():
    """Doc file lan_whitelist.conf va cap nhat danh sach IP/subnet."""
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
                        _lan_subnets.append(line)
                    else:
                        _lan_whitelist.add(line)
    except Exception as e:
        log.error("Lỗi đọc danh sách LAN whitelist: %s", e)

def _ip_in_whitelist(ip):
    """Kiem tra IP co trong whitelist (exact match hoac subnet match)."""
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
    """Ghi danh sach IP/subnet ra file."""
    try:
        os.makedirs(os.path.dirname(LAN_WHITELIST_PATH), exist_ok=True)
        with open(LAN_WHITELIST_PATH, 'w') as f:
            f.write('# Danh sach IP/subnet LAN duoc truy cap NAS API (khong can Tailscale)\n')
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
    """Ap dung iptables ACCEPT cho tat ca IP/subnet trong whitelist.
    Goi khi startup va khi them/xoa IP de dam bao firewall dong bo voi file config."""
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

_apply_iptables_for_whitelist()

clients = set()
def broadcast(data):
    for c in list(clients):
        try: c.write_message(json.dumps(data))
        except Exception: clients.remove(c)

class AlertWebSocket(tornado.websocket.WebSocketHandler):
    def check_origin(self, origin): return True
    def open(self): clients.add(self)
    def on_close(self):
        if self in clients: clients.remove(self)

def get_ip_geo(ip):
    if ip.startswith(("192.168.", "10.", "172.", "127.")): return "LOCAL", "LAN"
    try:
        import urllib.request
        resp = urllib.request.urlopen("http://ip-api.com/json/{}".format(ip), timeout=2)
        res = json.loads(resp.read().decode("utf-8"))
        return res.get("countryCode", "UN"), res.get("country", "Unknown")
    except Exception: return "UN", "Unknown"

def ban_ip_permanently(ip):
    # Co che fail2ban da bi VO HIEU HOA theo yeu cau nguoi dung.
    # Khong con goi iptables DROP de tranh chan nham IP Tailscale/LAN cua chinh chu.
    # Chi ghi log de admin biet co request ban (visibility) nhung khong thuc thi.
    try:
        now = datetime.datetime.now().strftime("%d/%m/%y %H:%M:%S")
        conn = sqlite3.connect(DB_PATH, timeout=20.0)
        cur = conn.cursor()
        safe_msg = sanitize_log_input("[{}] [DISABLED] Đã bỏ qua yêu cầu chặn IP {} vì fail2ban đang tắt.".format(now, ip))
        cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                   ("WARNING", "Firewall", safe_msg))
        conn.commit()
        conn.close()
    except Exception as e:
        log.error("[Firewall] Lỗi ghi log yêu cầu chặn IP %s: %s", ip, e)

def handle_auth_failure(ip):
    # Co che fail2ban da bi VO HIEU HOA theo yeu cau nguoi dung.
    # Chi ghi nhan so lan that bai vao auth_attempts de admin theo doi,
    # KHONG con tu dong them banned_ips/iptables DROP nua.
    conn = sqlite3.connect(DB_PATH, timeout=20.0)
    cur = conn.cursor()
    cur.execute('INSERT OR IGNORE INTO auth_attempts VALUES (?, 0)', (ip,))
    cur.execute('UPDATE auth_attempts SET count = count + 1 WHERE ip=?', (ip,))
    conn.commit()
    conn.close()

recent_auth_ips = {}

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
                        
                        # A. Lấy thông tin MAC Address bằng lệnh arp
                        mac_address = "Không rõ"
                        try:
                            arp_out = subprocess.check_output(["arp", "-n", ip], stderr=subprocess.DEVNULL).decode('utf-8')
                            match = _re_module.search(r'([0-9a-fA-F]{2}[:-]){5}([0-9a-fA-F]{2})', arp_out)
                            if match:
                                mac_address = match.group(0).upper()
                        except Exception:
                            pass
                            
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
                                conn = sqlite3.connect(DB_PATH)
                                cur = conn.cursor()
                                cur.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("INFO", "AccessLog", m_msg))
                                conn.commit()
                                conn.close()
                            except Exception:
                                pass
                            # (2) Phát sự kiện WebSocket cho Android App
                            try:
                                broadcast({"type": "ACCESS_LOG", "message": m_msg})
                            except Exception:
                                pass
                                

def monitor_journalctl():
    """Lang nghe he thong theo thoi gian thuc tu journalctl (sshd, kernel, smartd)"""
    cmd = ["journalctl", "-f", "-q", "-n", "0"]
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
                        conn = sqlite3.connect(DB_PATH)
                        conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("ERROR", "Security", m_msg))
                        conn.commit()
                        conn.close()
                        broadcast({"type": "ACCESS_LOG", "message": m_msg})
                    except: pass
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
                        conn = sqlite3.connect(DB_PATH)
                        conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("INFO", "AccessLog", m_msg))
                        conn.commit()
                        conn.close()
                        broadcast({"type": "ACCESS_LOG", "message": m_msg})
                    except: pass
                if main_loop: main_loop.add_callback(_commit_ssh_acc, log_json)
                continue
            
            # 3. Kernel CPU Nhiệt độ
            if 'kernel:' in line and 'temperature above threshold' in line:
                log_json = json.dumps({"event": "CPU_TEMP_WARN"}, ensure_ascii=False)
                def _commit_cpu_warn(m_msg):
                    try:
                        conn = sqlite3.connect(DB_PATH)
                        conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("WARNING", "Hardware", m_msg))
                        conn.commit()
                        conn.close()
                        broadcast({"type": "ACCESS_LOG", "message": m_msg})
                    except: pass
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
                        conn = sqlite3.connect(DB_PATH)
                        conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)", ("WARNING", "Hardware", m_msg))
                        conn.commit()
                        conn.close()
                        broadcast({"type": "ACCESS_LOG", "message": m_msg})
                    except: pass
                if main_loop: main_loop.add_callback(_commit_smart_warn, log_json)
                continue

    except Exception as e:
        log.error("Trình giám sát journalctl đã dừng: %s", e)


# ============ CAU HINH ============
# Doc thong tin xac thuc tu file bao mat /etc/nas/auth.conf (chmod 600)
# Format file auth.conf:
#   WEBDAV_USER=daica
#   WEBDAV_PASS=your_password_here
AUTH_CONFIG_PATH = "/etc/nas/auth.conf"

# Danh sach o cung de kiem tra S.M.A.R.T (Tu dong quet)
SMART_DISKS = ["/dev/sdb", "/dev/sda", "/dev/hda", "/dev/vda"]

# File tam de do toc do o cung
SPEED_TEST_FILE = "/tmp/nas_speed_test.bin"


def _load_credentials():
    """Doc user/pass tu file cau hinh bao mat, fallback sang bien moi truong."""
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
        log.warning("Chưa cấu hình WEBDAV_USER/WEBDAV_PASS. Hãy tạo file %s với WEBDAV_USER=... WEBDAV_PASS=...", AUTH_CONFIG_PATH)
    return user, passwd


WEBDAV_USER, WEBDAV_PASS = _load_credentials()


# ============ XAC THUC ============
def check_auth(username, password):
    return username == WEBDAV_USER and password == WEBDAV_PASS

def requires_auth(f):
    """FIX: Robust voi loi disk/DB. Truoc day moi request deu mo sqlite3.connect(DB_PATH)
    de check authorized_ips. Neu HDD bi I/O error (filesystem ro hoac inode hong)
    thi sqlite3 throw OperationalError -> Flask tra HTTP 500 cho moi endpoint —
    ke ca /api/ping. Hau qua: app khong dang nhap duoc khi HDD co loi (du Basic
    Auth co the lam viec doc lap voi DB).

    Logic moi:
    - LAN whitelist check khong can DB -> kiem truoc.
    - Co gang mo DB; neu fail vi disk loi -> fallback "DB-less mode": khong
      remember trusted IP nua, moi request phai Basic Auth, nhung khong reject.
    - Basic Auth check chi can WEBDAV_USER/WEBDAV_PASS (load tu /opt/nas_api.conf
      o RAM khi start) -> hoat dong binh thuong khi HDD chet.
    """
    @wraps(f)
    def decorated(*args, **kwargs):
        ip = request.remote_addr

        # Kiem tra IP trong LAN whitelist (bypass auth) — KHONG can DB
        if _ip_in_whitelist(ip):
            return f(*args, **kwargs)

        # Mo DB. Neu disk hong (I/O error, filesystem ro) -> fallback DB-less.
        conn = None
        cur = None
        db_ok = False
        try:
            conn = sqlite3.connect(DB_PATH, timeout=5.0)
            cur = conn.cursor()
            db_ok = True
        except Exception as db_err:
            # Disk/DB khong dung duoc -> moi request se yeu cau Basic Auth nhung
            # khong lam app sap luong dang nhap.
            try:
                log.warning("[Auth] DB khong mo duoc, fallback DB-less: %s", db_err)
            except Exception:
                pass

        # Kiem tra IP da duoc tin cay (tu dang nhap truoc do) — neu DB available
        if db_ok:
            try:
                cur.execute('SELECT 1 FROM authorized_ips WHERE ip=?', (ip,))
                is_trusted = cur.fetchone()
                if is_trusted:
                    try: conn.close()
                    except Exception: pass
                    return f(*args, **kwargs)
            except Exception:
                # Neu query loi (table hong) -> coi nhu chua trusted, di tiep
                pass

        # IP chua tin cay: Yeu cau xac thuc Basic Auth
        auth = request.authorization
        if not auth:
            if conn:
                try: conn.close()
                except Exception: pass
            return jsonify({"detail": "Chưa xác thực"}), 401

        if check_auth(auth.username, auth.password):
            # Dang nhap dung: Tu dong tin cay IP nay (chi khi DB ok)
            if db_ok:
                try:
                    cur.execute('INSERT OR REPLACE INTO authorized_ips VALUES (?, ?)', (ip, datetime.datetime.now()))
                    conn.commit()
                except Exception:
                    # Khong persist duoc trusted IP — khong sao, request tiep theo
                    # se Basic Auth lai. App van dang nhap duoc.
                    pass
            if conn:
                try: conn.close()
                except Exception: pass
            return f(*args, **kwargs)
        else:
            if conn:
                try: conn.close()
                except Exception: pass
            return jsonify({"detail": "Sai mật khẩu"}), 401

    return decorated


# ============ TIEN ICH ============

# Regex validate input de chong Command Injection
_RE_SAFE_CONTAINER = _re_module.compile(r'^[a-zA-Z0-9][a-zA-Z0-9_.\-]{0,127}$')
_RE_SAFE_IP = _re_module.compile(r'^(\d{1,3}\.){3}\d{1,3}$')
_RE_SAFE_CIDR = _re_module.compile(r'^(\d{1,3}\.){3}\d{1,3}/\d{1,2}$')
_RE_SAFE_DISK = _re_module.compile(r'^/dev/[a-z]{2,4}[0-9]?$')

def _validate_ip(ip):
    """Validate IP address format. Return True if valid."""
    return bool(ip and _RE_SAFE_IP.match(ip))

def _validate_cidr(cidr):
    """Validate CIDR subnet format. Return True if valid."""
    return bool(cidr and _RE_SAFE_CIDR.match(cidr))

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
    return real.startswith(os.path.realpath(WEBDAV_FILE_ROOT))

def run_cmd(cmd_list, timeout=10, merge_stderr=False):
    """Chay lenh AN TOAN bang list args (KHONG dung shell=True).
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
    allowed_flags = {"-A", "-d", "-n", "-o", "-o+", "-y", "-C", "-q", "-aq", "-s", "-j", "-D", "-I", "1", "sat", "input", "output", "eth0", "TCP", "-x", "-m", "tcp", "--dport", "-R", "--set-file=-"}
    for idx, arg in enumerate(cmd_list):
        str_arg = str(arg)
        if not str_arg or not str_arg.strip():
            raise ValueError("Tham số lệnh không hợp lệ: %s" % arg)
        # Bao mat Argument Injection: Chan cac tham so bat dau bang '-' neu khong nam trong hardcode whitelist
        if idx > 0 and str_arg.startswith("-") and str_arg not in allowed_flags:
             # Dac biet bo qua truong hop chuoi IP thong thuong (cuc ky hiem nhung van co kha nang bi cham vao) tuc la filter
             raise ValueError("Cảnh báo bảo mật: tham số chứa flag không được phép (%s)" % str_arg)
    return run_cmd(cmd_list, timeout, merge_stderr)

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
    """Lay nhiet do CPU tu thermal zone (Chainedbox rk3328)."""
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
        # Fallback: lay bat ky sensor nao co gia tri hop ly (20-120 do)
        for name, entries in temps.items():
            # Bo qua sensor o cung
            if "drive" in name.lower() or "hdd" in name.lower():
                continue
            for entry in entries:
                if 20 < entry.current < 120:
                    return "%d\u00b0C" % int(entry.current)
    except Exception:
        pass
    # Phuong phap 2: Doc truc tiep tu sysfs (Chainedbox rk3328)
    # Quet tat ca thermal zone de tim zone cua CPU
    try:
        import glob
        thermal_zones = sorted(glob.glob("/sys/class/thermal/thermal_zone*/"))
        for zone_dir in thermal_zones:
            try:
                # Kiem tra type cua thermal zone
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
        # Fallback: doc zone0 (thuong la CPU tren ARM SoC)
        with open("/sys/class/thermal/thermal_zone0/temp") as f:
            temp_milli = int(f.read().strip())
            if temp_milli > 1000:
                return "%d\u00b0C" % (temp_milli // 1000)
            elif 0 < temp_milli < 150:
                return "%d\u00b0C" % temp_milli
    except Exception:
        pass
    return "--\u00b0C"


def get_hdd_temp():
    """Lay nhiet do o cung — uu tien OMV RPC, fallback smartctl/sysfs."""
    import re
    # Phuong phap 0 (uu tien): Lay tu OMV Smart enumerateDevices
    try:
        omv_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "Smart", "enumerateDevices", "{}"], timeout=10)
        if omv_out and omv_out.strip().startswith("{"):
            devs = json.loads(omv_out)
            for key in devs:
                dev = devs[key]
                if "mmc" in dev.get("devicename", ""):
                    continue
                temp_str = dev.get("temperature", "")
                if temp_str and temp_str != "--\u00b0C":
                    # OMV tra ve "31°C" hoac "31"
                    temp_str = str(temp_str).replace("\u00b0C", "").strip()
                    if temp_str.isdigit() and 10 < int(temp_str) < 100:
                        return "%s\u00b0C" % temp_str
    except Exception:
        pass
    # Phuong phap 1: smartctl voi regex chinh xac
    for disk_path in SMART_DISKS:
        try:
            # Dung 2>&1 de lay ca stdout va stderr
            if not _validate_disk_path(disk_path):
                continue
            output = run_cmd(["sudo", "smartctl", "-A", disk_path, "-d", "sat"], merge_stderr=True)
            if not output or "open device" in output.lower():
                output = run_cmd(["sudo", "smartctl", "-A", disk_path], merge_stderr=True)
            if output and "temperature" in output.lower():
                for line in output.split("\n"):
                    line_lower = line.lower()
                    if "temperature" not in line_lower:
                        continue
                    # Format: "194 Temperature_Celsius ... - 33 (0 8 0 0 0)"
                    # Lay so dau tien sau dau '-' hoac sau cot cuoi (truoc dau ngoac)
                    match = re.search(r'-\s+(\d+)(?:\s*\(|$)', line)
                    if match:
                        temp_val = int(match.group(1))
                        if 10 < temp_val < 100:
                            return "%d\u00b0C" % temp_val
                    # Fallback: lay so hop le cuoi cung trong dong (truoc ngoac don)
                    line_before_paren = line.split("(")[0]
                    nums = re.findall(r'\b(\d{2})\b', line_before_paren)
                    for n in reversed(nums):
                        if 10 < int(n) < 100:
                            return "%d\u00b0C" % int(n)
        except Exception:
            pass
        # Phuong phap 2: hddtemp
        try:
            output = run_cmd(["sudo", "hddtemp", "-n", disk_path], merge_stderr=True)
            if output and output.strip().replace("-", "").isdigit():
                temp_val = int(output.strip())
                if 10 < temp_val < 100:
                    return "%d\u00b0C" % temp_val
        except Exception:
            pass
    # Xong buoc lap qua cac disk
    # Phuong phap 3: drivetemp kernel module (psutil)
    try:
        temps = psutil.sensors_temperatures()
        if "drivetemp" in temps:
            for entry in temps["drivetemp"]:
                if entry.current > 0:
                    return "%d\u00b0C" % int(entry.current)
    except Exception:
        pass
    # Phuong phap 4: Doc truc tiep tu sysfs hwmon (khong can smartctl)
    try:
        import glob
        # Tim hwmon cua o cung /dev/sda
        hwmon_paths = glob.glob("/sys/block/sda/device/hwmon/hwmon*/temp1_input")
        if not hwmon_paths:
            hwmon_paths = glob.glob("/sys/block/sda/device/hwmon/*/temp1_input")
        for hp in hwmon_paths:
            with open(hp) as f:
                temp_milli = int(f.read().strip())
                if temp_milli > 1000:
                    temp_c = temp_milli // 1000
                else:
                    temp_c = temp_milli
                if 10 < temp_c < 100:
                    return "%d\u00b0C" % temp_c
    except Exception:
        pass
    # Phuong phap 5: Quet tat ca hwmon devices tim drivetemp
    try:
        import glob
        for hwmon_dir in glob.glob("/sys/class/hwmon/hwmon*/"):
            try:
                name_file = os.path.join(hwmon_dir, "name")
                if os.path.exists(name_file):
                    with open(name_file) as f:
                        name = f.read().strip().lower()
                    if "drivetemp" in name or "hdd" in name:
                        temp_file = os.path.join(hwmon_dir, "temp1_input")
                        if os.path.exists(temp_file):
                            with open(temp_file) as f:
                                temp_milli = int(f.read().strip())
                                temp_c = temp_milli // 1000 if temp_milli > 1000 else temp_milli
                                if 10 < temp_c < 100:
                                    return "%d\u00b0C" % temp_c
            except Exception:
                continue
    except Exception:
        pass
    # Phuong phap 6: Thu load kernel module drivetemp
    try:
        run_cmd(["modprobe", "drivetemp"])
        import time
        time.sleep(0.5)
        temps = psutil.sensors_temperatures()
        if "drivetemp" in temps:
            for entry in temps["drivetemp"]:
                if entry.current > 0:
                    return "%d\u00b0C" % int(entry.current)
    except Exception:
        pass
    return "--\u00b0C"


def get_uptime():
    """Format uptime thanh dang de doc."""
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
    """Format bytes thanh don vi de doc."""
    if b < 1024:
        return "%d B" % b
    elif b < 1024 ** 2:
        return "%.1f KB" % (b / 1024.0)
    elif b < 1024 ** 3:
        return "%.1f MB" % (b / (1024.0 ** 2))
    else:
        return "%.2f GB" % (b / (1024.0 ** 3))


def format_speed(bps):
    """Format bytes/sec thanh toc do."""
    if bps < 1024:
        return "%.0f B/s" % bps
    elif bps < 1024 ** 2:
        return "%.1f KB/s" % (bps / 1024.0)
    else:
        return "%.1f MB/s" % (bps / (1024.0 ** 2))


# Luu tru bang thong mang cho tinh toan delta
_last_net = {"rx": 0, "tx": 0, "time": 0}


def get_network_speed():
    """Tinh toc do mang tu delta giua 2 lan goi."""
    global _last_net
    try:
        counters = psutil.net_io_counters()
        now = time.time()
        rx_speed = 0
        tx_speed = 0
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
    """Lay danh sach torrent tu qBittorrent Web API (neu co)."""
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
    """Tim phan vung du lieu chinh (lon nhat) va tra ve % su dung.
    Thay vi doc '/' (root eMMC nho), tim phan vung data HDD that su."""
    try:
        best_usage = None
        best_total = 0
        for partition in psutil.disk_partitions(all=False):
            # Bo qua loi do Docker overlay hoac cac FS ao gay fluctuation RAM/ROM
            if partition.fstype in ["overlay", "squashfs", "tmpfs", "devtmpfs"]:
                continue
            if partition.mountpoint.startswith(("/var/lib/docker", "/snap")):
                continue
            try:
                usage = psutil.disk_usage(partition.mountpoint)
                # Chon phan vung co tong dung luong lon nhat (= o cung data)
                if usage.total > best_total:
                    best_total = usage.total
                    best_usage = usage
            except (PermissionError, OSError):
                continue
        if best_usage and best_total > 0:
            return "%.1f%%|%s / %s" % (best_usage.percent, format_bytes(best_usage.used), format_bytes(best_usage.total))
    except Exception:
        pass
    # Fallback ve root neu khong tim thay
    try:
        disk = psutil.disk_usage("/")
        return "%.1f%%|%s / %s" % (disk.percent, format_bytes(disk.used), format_bytes(disk.total))
    except Exception:
        return "--%|"


def get_disk_partitions():
    """Lay thong tin phan vung o dia (loc bo trung lap va nho)."""
    parts = []
    # Cac thu muc can bo qua (log, ram, overlay, bind mount)
    SKIP_PREFIXES = ("/var/log", "/run/", "/dev/", "/proc/", "/sys/", "/tmp/")
    seen_sizes = {}  # Track duplicate (total_bytes, percent) de loc trung lap
    try:
        for partition in psutil.disk_partitions(all=False):
            mnt = partition.mountpoint
            # Bo qua cac thu muc he thong va log nho
            skip = False
            for prefix in SKIP_PREFIXES:
                if mnt.startswith(prefix):
                    skip = True
                    break
            if skip:
                continue
            try:
                usage = psutil.disk_usage(mnt)
                # Bo qua phan vung qua nho (duoi 500MB)
                if usage.total < 500 * 1024 * 1024:
                    continue
                # Bo qua phan vung trung lap (cung dung luong va % voi phan vung da co)
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



def get_fan_info():
    """Lay thong tin quat lam mat - Chainedbox rk3328 dung PWM pwmchip0."""
    PWM_DIR = "/sys/class/pwm/pwmchip0/pwm0"
    try:
        duty_path = os.path.join(PWM_DIR, "duty_cycle")
        period_path = os.path.join(PWM_DIR, "period")
        
        # Doc setting tu JSON
        settings = {"mode": "auto", "on_temp": 65, "off_temp": 55}
        try:
            if os.path.exists("/opt/fan_custom.json"):
                with open("/opt/fan_custom.json", "r") as f:
                    settings.update(json.load(f))
        except Exception:
            pass
            
        mode = settings.get("mode", "auto")
        
        # Kiem tra thuc te
        out = safe_run_cmd(["systemctl", "is-active", "fan.service"]).strip()
        if out == "active":
            mode = "auto"

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
            percent = int((duty * 100.0) / period)

            # FIX: doc them enable de bao cao "Tat" chinh xac khi PWM da bi cat hen
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

            payload = {
                "rpm": None,
                "percent": percent if enable_val == 1 else 0,
                "mode": mode,
                "on_temp": settings.get("on_temp", 65),
                "off_temp": settings.get("off_temp", 55)
            }
            if duty == 0 or enable_val == 0:
                payload["status"] = "Dừng"
            else:
                payload["status"] = "Đang chạy %d%%" % percent
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



def get_top_processes(n=3):
    """Lay top N tien trinh tieu hao CPU nhieu nhat (Python 3.5)."""
    procs = []
    try:
        num_cores = psutil.cpu_count() or 1
        active_procs = []

        # Pass 1: Tao baseline hieu nang cho tung tien trinh
        for proc in psutil.process_iter():
            try:
                proc.cpu_percent()
                active_procs.append(proc)
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
                
        # Ngu 0.1 giay de psutil tinh toan delta giua 2 lan goi
        time.sleep(0.1)

        # Pass 2: Lay so lieu % CPU chinh xac tuyet doi thuoc ve thoi gian thuc
        for proc in active_procs:
            try:
                cpu = proc.cpu_percent() / num_cores
                name = proc.name()
                procs.append({"name": name, "cpu": cpu})
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
        procs.sort(key=lambda x: x["cpu"], reverse=True)
        # Gom nhom tien trinh cung ten
        grouped = {}
        for p in procs:
            key = p["name"]
            if key in grouped:
                grouped[key]["cpu"] = min(grouped[key]["cpu"] + p["cpu"], 100.0)
            else:
                p["cpu"] = min(p["cpu"], 100.0)
                grouped[key] = dict(p)
        result = sorted(grouped.values(), key=lambda x: x["cpu"], reverse=True)
        return result[:n]
    except Exception:
        return []


# ============ BACKGROUND CACHE (Phan hoi API tuc thi) ============
_status_cache = {"status": "Đang khởi động..."}
_cache_lock = threading.Lock()

def _update_status_cache():
    """Background thread: cap nhat du lieu he thong moi 2 giay."""
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

            # Cap nhat thong tin Fan & Torrents (moi 10 giay)
            if loop_count % 5 == 0:
                cached_top = get_top_processes(3)
                cached_fan = get_fan_info()
                cached_torrents = get_torrents()
            
            # Cap nhat thong tin O cung (moi 60 giay) - TRANG HDD SPIN-UP!
            if loop_count % 30 == 0:
                cached_disk_parts = get_disk_partitions()
                cached_disk = get_main_disk_usage()
                cached_hdd_temp = get_hdd_temp()

            data = {
                "temperature": cached_hdd_temp,
                "cpu": "%.1f%%" % cpu_percent,
                "cpu_temp": get_cpu_temp(),
                "ram": "%s / %s" % (ram_used, ram_total),
                "ram_used": ram_used,
                "ram_total": ram_total,
                "ram_percent": str(int(round(mem.percent))),
                "mem_used": ram_used,
                "mem_total": ram_total,
                "mem_percent": str(int(round(mem.percent))),
                "disk": cached_disk,
                "net_rx": net_rx,
                "net_tx": net_tx,
                "uptime": get_uptime(),
                "status": "Online",
                "fan_rpm": cached_fan.get("rpm"),
                "fan_status": cached_fan.get("status", "--"),
                "fan_mode": cached_fan.get("mode", "auto"),
                "fan_on_temp": cached_fan.get("on_temp", 65),
                "fan_off_temp": cached_fan.get("off_temp", 55),
                "top_processes": cached_top,
                "torrents": cached_torrents,
                "disk_parts": cached_disk_parts
            }
            with _cache_lock:
                _status_cache = data
            
            loop_count = (loop_count + 1) % 30
        except Exception as e:
            with _cache_lock:
                _status_cache = {"status": "Lỗi: %s" % str(e)}
        time.sleep(2)


# ============ CRON WORKER (TU DONG HOA) ============
# Chay nen moi 1 gio: Don dep Thung rac, kick AI scan luc 2h sang
# TUAN THU hardware constraints RK3328: Khong poll CPU qua 10s, I/O nhe nhan

# Bien toan cuc luu trang thai canh bao (cho alert polling cua Android)
_alert_state_lock = threading.Lock()
_alert_states = {
    "hdd_temp_alerted": False,      # Da gui canh bao nhiet do chua?
    "offline_alerted": False,        # Da gui canh bao offline chua?
    "last_torrent_states": {},       # {hash: progress} - so sanh phat hien hoan thanh
    "last_alerts": [],               # Danh sach canh bao moi (Android poll)
    "ai_scan_running": False,        # Dang quet anh hay khong
    "ai_last_scan": 0,               # Thoi gian lan quet AI cuoi cung (epoch)
    "trash_last_clean": 0,           # Thoi gian lan don rac cuoi cung (epoch)
    "empty_last_clean": 0,           # Thoi gian lan don file/folder rong cuoi cung (epoch)
}

def _push_alert(alert_type, message, severity="INFO"):
    """Them canh bao vao hang doi de Android lay qua /api/alerts/poll"""
    message = normalize_vietnamese_message(sanitize_log_input(message))
    alert_type = sanitize_log_input(alert_type)
    with _alert_state_lock:
        # Giu toi da 50 canh bao gan nhat
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
        conn = sqlite3.connect(DB_PATH, timeout=20.0)
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
    """Xoa cac file trong thu muc .trash/ qua N ngay. Khong wake spin-up HDD khong can thiet."""
    try:
        trash_dir = os.path.join(webdav_root, ".trash")
        if not os.path.exists(trash_dir):
            return 0
        now = time.time()
        max_age_sec = max_age_days * 86400
        deleted = 0
        for fname in os.listdir(trash_dir):
            fpath = os.path.join(trash_dir, fname)
            try:
                age = now - os.path.getmtime(fpath)
                if age > max_age_sec:
                    if os.path.isdir(fpath):
                        import shutil
                        shutil.rmtree(fpath, ignore_errors=True)
                    else:
                        os.remove(fpath)
                    deleted += 1
            except Exception:
                continue
        return deleted
    except Exception:
        return 0




# Map ten thu muc -> danh muc gallery (Python 3.5 compat, KHONG can Docker/TFLite)
_FOLDER_CATEGORY_MAP = {
    "Khuon Mat":           ["selfie", "portrait", "face", "avatar"],
    "Mang Xa Hoi":         ["facebook", "tiktok", "telegram", "instagram", "zalo", "messenger"],
    "Thien Nhien / Bien":  ["beach", "sea", "ocean", "nature", "mountain", "sunset", "sky"],
    "Video":               ["video", "movie", "film", "clip"],
    "Tai Lieu":            ["document", "doc", "scan", "notes", "samsung notes", "pdf"],
    "Camera / Giam Sat":   ["surveillance", "camera", "cctv", "security"],
    "Anh Tai Ve":          ["download", "downloads", "saved"],
    "Album Dien Thoai":    ["dcim", "camera", "screenshot", "album"],
}
_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic"}


def _scan_photos_lightweight():
    """
    Quet va phan loai anh theo ten thu muc — Python 3.5 thuan, KHONG can Docker.
    Chay truc tiep tren NAS, chi dung os.walk() va string matching.
    Ghi ket qua ra AI_TAGS_PATH de Android doc qua /api/ai/tags.
    """
    try:
        with _alert_state_lock:
            if _alert_states["ai_scan_running"]:
                return False  # Dang chay roi
            _alert_states["ai_scan_running"] = True

        root_dir = WEBDAV_FILE_ROOT
        categories = {}  # {"Mang Xa Hoi": ["Facebook/img1.jpg", ...], ...}
        total = 0

        for dirpath, dirnames, filenames in os.walk(root_dir):
            # Bo qua thu muc an (.trash, .thumbnails...)
            dirnames[:] = [d for d in dirnames if not d.startswith(".")]

            # Lay ten thu muc hien tai va cha
            rel_dir = dirpath[len(root_dir):].strip("/").strip("\\")
            folder_parts = rel_dir.lower().replace("\\", "/").split("/") if rel_dir else []

            for fname in filenames:
                ext = os.path.splitext(fname)[1].lower()
                if ext not in _IMAGE_EXTS:
                    continue
                total += 1
                rel_path = os.path.join(rel_dir, fname).replace("\\", "/")

                # Phan loai dua tren ten thu muc
                matched = False
                for cat_name, keywords in _FOLDER_CATEGORY_MAP.items():
                    for kw in keywords:
                        for part in folder_parts:
                            if kw in part:
                                if cat_name not in categories:
                                    categories[cat_name] = []
                                categories[cat_name].append(rel_path)
                                matched = True
                                break
                        if matched:
                            break
                    if matched:
                        break
                # Khong khop thu muc nao -> xep vao "Khac"
                if not matched:
                    if "Khac" not in categories:
                        categories["Khac"] = []
                    categories["Khac"].append(rel_path)

        # Ghi ra JSON (atomic write: tmp -> rename)
        ai_data = {
            "categories": categories,
            "total": total,
            "generated_at": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        }
        tmp_path = AI_TAGS_PATH + ".tmp"
        try:
            parent_dir = os.path.dirname(AI_TAGS_PATH)
            if not os.path.exists(parent_dir):
                os.makedirs(parent_dir)
        except Exception:
            pass
        with open(tmp_path, "w") as f:
            json.dump(ai_data, f)
        os.rename(tmp_path, AI_TAGS_PATH)

        with _alert_state_lock:
            _alert_states["ai_scan_running"] = False
            _alert_states["ai_last_scan"] = time.time()

        return True
    except Exception as e:
        with _alert_state_lock:
            _alert_states["ai_scan_running"] = False
        log.error("[AI Scan] Lỗi: %s", e)
        return False


def _clean_empty_files_and_dirs(root_dir, exclude_dirs=None):
    """Don dep tu dong file rong (0-byte), FLV hong cu va thu muc rong duoi root_dir.
    Bo qua cac thu muc he thong: .trash, .nas_meta, .thumbnails, .git, .recycle.

    Tra ve tuple (so file rong da xoa, so thu muc da xoa, so FLV hong da xoa).
    """
    if exclude_dirs is None:
        exclude_dirs = {".trash", ".nas_meta", ".thumbnails", ".git", ".recycle", "@eaDir"}
    if not os.path.isdir(root_dir):
        return (0, 0, 0)
    deleted_files = 0
    deleted_dirs = 0
    deleted_broken_flv = 0
    # Walk bottom-up de xoa thu muc tu trong ra ngoai
    for dirpath, dirnames, filenames in os.walk(root_dir, topdown=False):
        # Bo qua cac thu muc system
        rel = os.path.relpath(dirpath, root_dir)
        parts = rel.split(os.sep)
        if any(p in exclude_dirs for p in parts):
            continue
        # 1) Xoa file 0-byte
        for fname in filenames:
            if fname.startswith("."):
                continue  # bo qua dotfile (.DS_Store, .gitkeep, etc.)
            fpath = os.path.join(dirpath, fname)
            try:
                if not os.path.isfile(fpath):
                    continue
                lower_name = fname.lower()
                is_broken_flv = lower_name.endswith(".broken.flv") or ".flv.broken" in lower_name
                if is_broken_flv:
                    os.remove(fpath)
                    deleted_broken_flv += 1
                elif os.path.getsize(fpath) == 0:
                    os.remove(fpath)
                    deleted_files += 1
            except Exception:
                pass
        # 2) Xoa thu muc rong (sau khi xoa file ben trong o vong tren)
        try:
            if dirpath == root_dir:
                continue  # khong xoa root
            if not os.listdir(dirpath):
                os.rmdir(dirpath)
                deleted_dirs += 1
        except Exception:
            pass
    return (deleted_files, deleted_dirs, deleted_broken_flv)


def _check_torrent_completion():
    """Phat hien torrent vua hoan thanh (progress 1.0) so voi lan poll truoc."""
    new_completed = []
    try:
        current = get_torrents()  # Lay trang thai moi nhat
        with _alert_state_lock:
            prev_states = dict(_alert_states["last_torrent_states"])
            # Cap nhat trang thai hien tai
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
            # Chi bao khi chuyen tu <1.0 sang >=1.0 (vua hoan thanh)
            if prev_prog >= 0 and prev_prog < 1.0 and curr_prog >= 1.0:
                name = t.get("name", "Unknown")
                new_completed.append(name)
    except Exception:
        pass
    return new_completed


def _check_hdd_temp_alert(threshold=60):
    """Kiem tra nhiet do HDD co vuot nguong canh bao khong."""
    try:
        temp_str = get_hdd_temp()
        if temp_str and temp_str != "--\u00b0C":
            val = int(temp_str.replace("\u00b0C", "").strip())
            return val, val >= threshold
        return 0, False
    except Exception:
        return 0, False


def _cron_worker():
    """
    Background cron thread chay moi 60 giay.
    Dam nhiem cac viec: don rac, phat hien canh bao, kick AI 2h sang.
    Tuan thu STRICT: khong poll HDD/proc qua thuong, interval >= 60s
    """
    # Doi 60 giay sau khi server khoi dong de tranh tranh tai I/O luc boot
    time.sleep(60)
    check_interval = 0  # Dem so vong de don rac (moi 3600s / 60 = 60 vong)
    while True:
        try:
            now_ts = time.time()
            now_dt = datetime.datetime.now()

            # --- 1. Kiem tra torrent hoan thanh (moi 60 giay) ---
            completed = _check_torrent_completion()
            for name in completed:
                msg = "Torrent đã tải xong: %s" % name
                _push_alert("TORRENT_DONE", msg, "SUCCESS")

            # --- 2. Kiem tra nhiet do HDD (moi 60 giay) ---
            # NOTE: get_hdd_temp() co cache 60s rieng, khong wake HDD them lan nua
            temp_val, is_hot = _check_hdd_temp_alert(threshold=60)
            with _alert_state_lock:
                prev_alerted = _alert_states["hdd_temp_alerted"]
            if is_hot and not prev_alerted:
                msg = "CẢNH BÁO: Nhiệt độ HDD đang cao: %d\u00b0C (> 60\u00b0C)!" % temp_val
                _push_alert("HDD_TEMP_HIGH", msg, "ERROR")
                with _alert_state_lock:
                    _alert_states["hdd_temp_alerted"] = True
            elif not is_hot:
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
                conn = sqlite3.connect(DB_PATH, timeout=10.0)
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
            except Exception:
                pass

            # --- 4. Don dep Thung rac (moi 24h) ---
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

            # --- 4b. Don dep file rong (0-byte), FLV hong cu + thu muc rong (moi 24h) ---
            # Quet WEBDAV_FILE_ROOT, bo qua .trash/.nas_meta/.thumbnails va dotfile.
            # File 0-byte thuong la rac tu download fail / FLV stream rong, thu muc
            # rong sau khi xoa file lai cung khong dung gi -> don sach.
            with _alert_state_lock:
                last_empty = _alert_states["empty_last_clean"]
            if (now_ts - last_empty) > 86400:  # 24h
                try:
                    df, dd, db = _clean_empty_files_and_dirs(WEBDAV_FILE_ROOT)
                    if df + dd + db > 0:
                        msg = "Tự động dọn dẹp: Đã xóa %d tệp rỗng và %d thư mục rỗng." % (df, dd)
                        if db > 0:
                            msg = msg + " FLV hỏng cũ: %d." % db
                        _push_alert("EMPTY_CLEANED", msg, "INFO")
                except Exception as e:
                    log.warning("[Cron] Lỗi dọn tệp rỗng/thư mục rỗng: %s", e)
                with _alert_state_lock:
                    _alert_states["empty_last_clean"] = now_ts

            # --- 5. Quet phan loai anh nhe luc 3h sang ---
            with _alert_state_lock:
                last_ai = _alert_states["ai_last_scan"]
            is_3am = (now_dt.hour == 3 and now_dt.minute < 5)
            if is_3am and (now_ts - last_ai) > 82800:
                started = _scan_photos_lightweight()
                if started:
                    _push_alert("AI_SCAN_STARTED", "Smart Gallery: Đã phân loại ảnh theo thư mục.", "INFO")

            # --- 6. Tạo Báo Cáo Hàng Ngày lúc 6h sáng ---
            is_6am = (now_dt.hour == 6 and now_dt.minute < 2)
            if is_6am:
                yesterday = (now_dt - datetime.timedelta(days=1)).strftime("%Y-%m-%d")
                try:
                    conn = sqlite3.connect(DB_PATH, timeout=10.0)
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
        conn = sqlite3.connect(DB_PATH, timeout=20.0)
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

        conn = sqlite3.connect(DB_PATH, timeout=20.0)
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
        conn = sqlite3.connect(DB_PATH, timeout=10.0)
        cur = conn.cursor()
        # QUAN TRONG: Khong dung % operator vi conflict voi %Y, %m... trong strftime SQLite
        # Tinh san WHERE time filter bang Python roi truyen vao query
        hours_filter = "-%d hours" % hours
        cur.execute(
            "SELECT strftime('%Y-%m-%dT%H:%M:%S', timestamp),"
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
        conn = sqlite3.connect(DB_PATH, timeout=10.0)
        cur = conn.cursor()
        cur.execute("SELECT report_json, generated_at FROM daily_reports WHERE report_date = ?", (date_str,))
        row = cur.fetchone()
        conn.close()
        if row:
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
    """Trang thai he thong - tra ve cache tuc thi (kem canh bao neu co)."""
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
        
    return jsonify(data)


@app.route("/api/ping", methods=["GET", "HEAD"])
@requires_auth
def api_ping():
    """Lightweight authenticated ping endpoint for LAN/Tailscale latency checks."""
    if request.method == "HEAD":
        return ("", 204)
    return jsonify({"ok": True, "ts": time.time()})


@app.route("/api/system/idle")
@requires_auth
def api_system_idle():
    """Tra ve trang thai 'ranh' cua NAS de quyet dinh co nen chay tac vu nang
    (vi du quet trung lap) hay khong.

    Idle = TRUE khi:
      - CPU usage < 50%
      - Load average 1 phut < cores * 0.7
      - RAM free > 150 MB
      - Khong co livestream nao dang recording
      - Khong co backup/restore/torrent dang chay nang
    """
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


@app.route("/api/disk/smart")
@requires_auth
def api_smart():
    """Thong tin S.M.A.R.T o cung — uu tien lay tu OMV, fallback smartctl."""
    raw_log = ""
    status = "Unknown"
    temperature = "--\u00b0C"

    # === Phuong phap 1: Lay tu OMV RPC (chinh xac nhat) ===
    try:
        omv_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "Smart", "enumerateDevices", "{}"], timeout=15)
        if omv_out and omv_out.strip():
            raw_parsed = json.loads(omv_out)
            # OMV co the tra ve object {"1": {...}} hoac array [{...}]
            if isinstance(raw_parsed, dict):
                devices = list(raw_parsed.values())
            else:
                devices = raw_parsed
            # Tim o cung that (khong phai eMMC/mmcblk)
            real_devs = [d for d in devices if isinstance(d, dict) and "mmc" not in d.get("devicename", "")]
            if real_devs:
                dev = real_devs[0]
                overall = dev.get("overallstatus", "")
                if overall.upper() == "GOOD":
                    status = "PASSED"
                elif overall.upper() == "BAD":
                    status = "FAILED"
                else:
                    status = overall if overall else "Unknown"
                # Nhiet do tu OMV (co the la "31°C" hoac "31")
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
                raw_log = "Thiet bi: /dev/%s\nModel: %s\nSerial: %s\nTrang thai OMV: %s\nNhiet do: %s" % (
                    devname, full_model, serial, overall, temperature
                )
                # Lay SMART attributes tu OMV
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
                # Bo sung nhiet do fallback
                if temperature == "--\u00b0C":
                    try:
                        temperature = get_hdd_temp()
                    except Exception:
                        pass
                return jsonify({"status": status, "temperature": temperature, "raw_log": raw_log})
            else:
                return jsonify({"status": "eMMC Only", "temperature": "--\u00b0C", "raw_log": "OMV chỉ phát hiện eMMC. Không có HDD/SSD."})
    except Exception:
        pass

    # === Phuong phap 2: Fallback smartctl truc tiep ===
    has_real_hdd = False
    real_disks = [d for d in SMART_DISKS if "mmc" not in d]
    for disk_path in real_disks:
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

    return jsonify({"status": status, "temperature": temperature, "raw_log": raw_log})


@app.route("/api/omv/overview")
@requires_auth
def api_omv_overview():
    """Tong hop thong tin tu OMV RPC: he thong, dich vu, mang, filesystem, o cung."""
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
                services.append({
                    "name": s.get("name", ""),
                    "title": s.get("title", ""),
                    "enabled": s.get("enabled", False),
                    "running": s.get("running", False)
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
        fs_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "FileSystemMgmt", "enumerateFilesystems", "{}"], timeout=10)
        if fs_out:
            fs_data = json.loads(fs_out)
            filesystems = []
            for fs in fs_data:
                # Bo qua zram (log2ram) va phan vung < 500MB
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
        disk_out = run_cmd(["sudo", "omv-rpc", "-u", "admin", "DiskMgmt", "enumerateDevices", "{}"], timeout=10)
        if disk_out:
            disk_data = json.loads(disk_out)
            disks = []
            for d in disk_data:
                vendor = d.get("vendor", "")
                model = d.get("model", "")
                disks.append({
                    "name": d.get("devicename", ""),
                    "device": d.get("devicefile", ""),
                    "model": ("%s %s" % (vendor, model)).strip(),
                    "serial": d.get("serialnumber", ""),
                    "size": d.get("size", "0"),
                    "description": d.get("description", ""),
                    "is_root": d.get("isroot", False)
                })
            result["disks"] = disks
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

    return jsonify(result)

@app.route("/api/processes", methods=["GET"])
@requires_auth
def api_processes():
    """Lay danh sach 100 tien trinh hang dau, sap xep theo CPU hoac RAM."""
    try:
        sort_by = request.args.get("sort", "cpu")
        limit = int(request.args.get("limit", 100))
        num_cores = psutil.cpu_count() or 1
        
        active_procs = []
        for p in psutil.process_iter(['pid', 'name', 'username', 'status', 'memory_percent']):
            try:
                p.cpu_percent()
                active_procs.append(p)
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
                
        time.sleep(0.1)
        
        procs = []
        for p in active_procs:
            try:
                info = p.info
                cpu = p.cpu_percent() / num_cores
                # Handle status string
                st = str(info.get('status', ''))
                
                procs.append({
                    "pid": info.get('pid', 0),
                    "name": info.get('name', 'unknown'),
                    "user": info.get('username', 'root') or "root",
                    "status": st,
                    "cpu": round(cpu, 1),
                    "mem": round(info.get('memory_percent', 0.0) or 0.0, 1)
                })
            except (psutil.NoSuchProcess, psutil.AccessDenied, KeyError):
                continue
                
        if sort_by == "mem":
            procs.sort(key=lambda x: x["mem"], reverse=True)
        else:
            procs.sort(key=lambda x: x["cpu"], reverse=True)
            
        return jsonify({"status": "success", "data": procs[:limit]})
    except Exception as e:
        log.error("Lỗi API danh sách tiến trình: %s", e)
        return jsonify({"error": str(e)}), 500


@app.route("/api/disk/speedtest", methods=["POST"])
@requires_auth
def api_speedtest():
    """Do toc do doc/ghi bang pure Python, an toan va doc lap voi he dieu hanh."""
    try:
        import time, os
        # Tim duong dan o cung that de do (Uu tien /srv/dev-disk vi /sharedfolders hay bi loi IO Errno 5 tren OMV)
        test_file = SPEED_TEST_FILE
        best_total = 0
        for p in psutil.disk_partitions(all=False):
            try:
                u = psutil.disk_usage(p.mountpoint)
                # Uu tien thu muc srv/dev-disk cua OMV
                if u.total > best_total and ("/srv/dev-disk" in p.mountpoint or "/mnt/" in p.mountpoint):
                    best_total = u.total
                    test_file = os.path.join(p.mountpoint, "nas_speed_test.bin")
            except (PermissionError, OSError):
                continue
        # Neu khong tim duoc srv, thu fallback
        if best_total == 0:
            if os.path.exists("/srv/dev-disk-by-label-data"):
                test_file = "/srv/dev-disk-by-label-data/nas_speed_test.bin"
            elif os.path.exists("/sharedfolders/Data"):
                test_file = "/sharedfolders/Data/nas_speed_test.bin"
            
        test_size_mb = 50
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
@requires_auth
def api_reboot():
    threading.Timer(2.0, lambda: subprocess.run(["reboot"])).start()
    return jsonify({"result": "ok"})

@app.route("/api/power/suspend", methods=["POST"])
@requires_auth
def api_suspend():
    return _schedule_suspend_response()

@app.route("/api/power/shutdown", methods=["POST"])
@requires_auth
def api_shutdown():
    return _schedule_suspend_response(legacy_endpoint="shutdown")


@app.route("/api/auth/authorize", methods=["POST"])
@requires_auth
def api_auth_authorize():
    """Xac thuc va cap phep IP ket noi (Android handshake)."""
    ip = request.remote_addr
    
    # 1. MỞ KHÓA FIREWALL (iptables) LẬP TỨC CHO IP NÀY (Bypass mọi RULE chặn WebDAV LAN)
    try:
        # Xóa rule ACCEPT cũ nếu có để tránh trùng lặp
        subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
        # Thêm rule ACCEPT ưu tiên cao nhất
        subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', ip, '-j', 'ACCEPT'], check=True)
        # Gỡ bỏ rule DROP mặt định nếu trước đó lỡ quẹt trúng
        subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'DROP'], stderr=subprocess.DEVNULL)
    except Exception as e:
        log.warning("Lỗi mở khóa iptables cho IP %s: %s", ip, e)

    # 2. Thêm vào lan_whitelist để bền vững
    if ip not in _lan_whitelist:
        _lan_whitelist.add(ip)
        _save_lan_whitelist()
        # Reload nginx nếu NAS dùng Nginx đọc whitelist
        try: subprocess.run(["sudo", "systemctl", "reload", "nginx"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        except Exception as e: log.warning("Reload Nginx thất bại: %s", e)

    # 3. Ghi log vào Database
    conn = sqlite3.connect(DB_PATH, timeout=20.0)
    cur = conn.cursor()
    cur.execute('INSERT OR REPLACE INTO authorized_ips VALUES (?, ?)', (ip, datetime.datetime.now()))
    cur.execute('DELETE FROM banned_ips WHERE ip=?', (ip,))
    cur.execute('DELETE FROM auth_attempts WHERE ip=?', (ip,))
    conn.commit()
    conn.close()
    
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
@requires_auth
def api_docker_control():
    try:
        data = request.get_json(force=True)
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
    except Exception: pass
    return {"mode": "auto", "on_temp": 65, "off_temp": 55}

def _save_fan_settings(settings):
    try:
        with open(FAN_SETTINGS_FILE, "w") as f:
            json.dump(settings, f)
    except Exception: pass


# ============================================================================
# Fan PWM low-level helpers (FIX: nut Tat trong app phai cat hen 5V chu khong
# chi set duty=0 — kernel PWM peripheral khi enable=1 + duty=0 van co the giu
# transistor o trang thai khong xac dinh tuy phan cung Chainedbox).
# ============================================================================
PWM_PATH = "/sys/class/pwm/pwmchip0/pwm0"


def _pwm_write(node, value):
    """Ghi gia tri vao 1 sysfs node PWM. Im lang neu khong ton tai."""
    path = os.path.join(PWM_PATH, node)
    try:
        if not os.path.exists(path):
            return False
        # subprocess voi sh -c de chac chan kernel nhin thay file write
        # (mot so kernel khong cho python ghi truc tiep voi PermissionDenied).
        subprocess.run(["sh", "-c", "echo %s > %s" % (value, path)], check=False)
        return True
    except Exception as e:
        log.warning("[Fan] PWM write %s=%s loi: %s", node, value, e)
        return False


def _pwm_export_if_needed():
    """Mot so kernel can echo 0 > pwmchip0/export truoc khi /pwm0 ton tai."""
    try:
        if not os.path.isdir(PWM_PATH):
            export_path = "/sys/class/pwm/pwmchip0/export"
            if os.path.exists(export_path):
                subprocess.run(["sh", "-c", "echo 0 > %s" % export_path], check=False)
    except Exception:
        pass


def _pwm_apply_off():
    """Tat hoan toan PWM: duty=0 truoc, enable=0 sau de pin ve LOW va peripheral
    ngung output. Tren rk3328 Chainedbox phai ca hai buoc nay 5V moi ngat tai
    chan ra quat."""
    _pwm_export_if_needed()
    _pwm_write("duty_cycle", 0)
    _pwm_write("enable", 0)


def _pwm_apply_on(duty=10000, period=10000):
    """Bat PWM: period -> duty -> enable. Kernel yeu cau duty <= period nen phai
    cap nhat period truoc neu can tang duty. enable=1 cuoi cung."""
    _pwm_export_if_needed()
    # Doc period hien tai; chi ghi neu nho hon duty mong muon (tranh ghi -EINVAL).
    try:
        with open(os.path.join(PWM_PATH, "period")) as f:
            cur_period = int(f.read().strip() or "0")
    except Exception:
        cur_period = 0
    if cur_period < duty:
        _pwm_write("period", period)
    _pwm_write("duty_cycle", duty)
    _pwm_write("enable", 1)


# ============================================================================
# PHOTO TIMELINE — Group anh theo Year/Month/Day cho UI Google-Photos-style
# ============================================================================
# Endpoint nhe — chi liet ke path + mtime, KHONG mo tung file de doc EXIF
# (tranh stress disk). Client tu group theo mtime client-side.
# ============================================================================
@app.route('/api/photos/timeline', methods=['GET'])
@requires_auth
def api_photos_timeline():
    """List anh trong WEBDAV_FILE_ROOT, sort by mtime desc, group-ready.
    Query params:
      - month: "YYYY-MM" -> chi tra anh trong thang do
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

    target_year = None
    target_month = None
    if month_filter:
        try:
            parts = month_filter.split("-")
            target_year = int(parts[0])
            target_month = int(parts[1])
        except Exception:
            pass

    items = []
    try:
        for root, dirs, files in os.walk(WEBDAV_FILE_ROOT):
            # Skip hidden + thumb dirs (tranh stress disk)
            dirs[:] = [d for d in dirs if not d.startswith('.') and d != THUMB_DIR_NAME and d != '#recycle']
            for name in files:
                if name.startswith('.'): continue
                ext = os.path.splitext(name)[1].lower()
                if ext not in _IMAGE_EXTS: continue
                full = os.path.join(root, name)
                try:
                    st = os.stat(full)
                    mtime = int(st.st_mtime)
                    if target_year is not None:
                        dt = datetime.datetime.fromtimestamp(mtime)
                        if dt.year != target_year or dt.month != target_month:
                            continue
                    rel = os.path.relpath(full, WEBDAV_FILE_ROOT).replace("\\", "/")
                    items.append({
                        "path": rel,
                        "mtime": mtime,
                        "size": st.st_size,
                    })
                except Exception:
                    continue
    except Exception as e:
        log.warning("[PhotoTimeline] Walk loi: %s", e)
        return jsonify({"error": str(e)[:200]}), 500

    # Sort desc, apply pagination
    items.sort(key=lambda x: x["mtime"], reverse=True)
    total = len(items)
    page = items[offset:offset + limit]
    return jsonify({
        "total": total,
        "offset": offset,
        "limit": limit,
        "items": page,
        "filter_month": month_filter or None,
    })


# ============================================================================
# DISK HEALTH MONITOR — Theo doi suc khoe HDD truoc khi qua muon
# ============================================================================
# Sample SMART + dmesg + io stats moi 5 phut, ghi append vao .jsonl tren eMMC.
# Auto-alert qua system_logs khi vuot threshold. UI app doc /api/disk/health
# (snapshot hien tai) hoac /api/disk/health/history?days=N (time series).
# ============================================================================
_DISK_HEALTH_HISTORY_FILE = "/etc/nas/state/disk_health_history.jsonl"
_DISK_HEALTH_SAMPLE_INTERVAL_SEC = 300  # 5 phut
_DISK_HEALTH_RETENTION_DAYS = 30
_disk_health_last_sample = {}   # giu sample gan nhat trong RAM cho /api/disk/health
_disk_health_lock = threading.Lock()


def _read_dmesg_recent(seconds=300):
    """Dem so EXT4 error va SATA reset trong dmesg trong N giay gan day."""
    try:
        out = safe_run_cmd(["dmesg", "--time-format=raw"], timeout=8)
        if not out:
            # Khong co --time-format raw -> fallback parse [seconds] o dau dong
            out = safe_run_cmd(["dmesg"], timeout=8)
    except Exception:
        return {"ext4_errors": 0, "sata_resets": 0, "io_errors": 0}
    ext4 = 0
    sata = 0
    ioerr = 0
    # Doc tu duoi len, dem den khi vuot ngoai window
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
        if "i/o error" in low:
            ioerr += 1
    return {"ext4_errors": ext4, "sata_resets": sata, "io_errors": ioerr}


def _read_io_stats(devname="sda"):
    """Doc /sys/class/block/<dev>/stat: io wait time, sectors r/w."""
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
    """Lay 3 metric quan trong tu smartctl: Reallocated, Pending, UDMA CRC, temp."""
    result = {
        "smart_status": "Unknown",
        "temp_c": None,
        "reallocated_sectors": None,
        "pending_sectors": None,
        "udma_crc_err": None,
        "power_on_hours": None,
    }
    # Phat hien thiet bi HDD (skip mmcblk)
    dev = None
    try:
        for cand in ("sda", "sdb"):
            if os.path.exists("/dev/" + cand):
                dev = "/dev/" + cand
                break
    except Exception:
        pass
    if not dev:
        return result
    # FIX: safe_run_cmd block '-H' flag (security whitelist). Goi subprocess
    # truc tiep voi danh sach args co dinh (khong co user input) -> an toan.
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
        log.warning("[DiskHealth] smartctl loi: %s", e)
    return result


def _compute_health_score(sample):
    """Tinh diem suc khoe 0-100 + nhan canh bao."""
    score = 100
    warnings = []
    if sample.get("smart_status") == "FAILED":
        score -= 50; warnings.append("SMART overall-health: FAILED")
    elif sample.get("smart_status") not in ("PASSED",):
        score -= 5
    realloc = sample.get("reallocated_sectors") or 0
    if realloc > 0:
        score -= min(20, realloc)
        warnings.append("%d sector da realloc" % realloc)
    pending = sample.get("pending_sectors") or 0
    if pending > 0:
        score -= min(30, pending * 2)
        warnings.append("%d sector dang cho realloc (pending) — DAU HIEU O CUNG SAP HONG" % pending)
    offline_unc = sample.get("offline_uncorrectable") or 0
    if offline_unc > 0:
        score -= min(20, offline_unc * 2)
        warnings.append("%d offline uncorrectable sector" % offline_unc)
    cmd_to = sample.get("command_timeout") or 0
    if cmd_to > 10000:
        score -= 10
        warnings.append("%d command timeout — SATA link khong on dinh" % cmd_to)
    crc = sample.get("udma_crc_err") or 0
    if crc > 0:
        score -= min(10, crc)
        warnings.append("%d UDMA CRC error (cap SATA can kiem tra)" % crc)
    temp = sample.get("temp_c") or 0
    if temp > 60:
        score -= 20; warnings.append("Nhiet do %d°C qua nong" % temp)
    elif temp > 50:
        score -= 8; warnings.append("Nhiet do %d°C cao" % temp)
    ext4 = sample.get("ext4_errors_recent") or 0
    if ext4 > 0:
        score -= min(30, ext4 * 5)
        warnings.append("%d EXT4-fs error trong 5 phut gan day" % ext4)
    sata = sample.get("sata_resets_recent") or 0
    if sata > 0:
        score -= min(40, sata * 15)
        warnings.append("%d SATA reset/exception trong 5 phut — co the dat cap loi" % sata)
    ioerr = sample.get("io_errors_recent") or 0
    if ioerr > 0:
        score -= min(30, ioerr * 10)
        warnings.append("%d I/O error trong 5 phut" % ioerr)
    return max(0, score), warnings


def _disk_health_sample_once():
    """Sample 1 lan, append vao .jsonl, update RAM cache, alert neu can."""
    global _disk_health_last_sample
    try:
        smart = _parse_smart_attributes()
        dmesg = _read_dmesg_recent(seconds=_DISK_HEALTH_SAMPLE_INTERVAL_SEC)
        io = _read_io_stats("sda")
        sample = {
            "ts": int(time.time()),
            "datetime": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        }
        sample.update(smart)
        sample["ext4_errors_recent"] = dmesg.get("ext4_errors", 0)
        sample["sata_resets_recent"] = dmesg.get("sata_resets", 0)
        sample["io_errors_recent"] = dmesg.get("io_errors", 0)
        sample["io_stats"] = io
        score, warnings = _compute_health_score(sample)
        sample["score"] = score
        sample["warnings"] = warnings

        with _disk_health_lock:
            _disk_health_last_sample = sample

        # Append vao .jsonl tren eMMC
        try:
            os.makedirs(os.path.dirname(_DISK_HEALTH_HISTORY_FILE), exist_ok=True)
            with open(_DISK_HEALTH_HISTORY_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(sample, ensure_ascii=False) + "\n")
        except Exception as e:
            log.warning("[DiskHealth] Khong ghi history: %s", e)

        # Alert qua system_logs neu score xuong duoi nguong hoac co warning critical
        if score < 60:
            try:
                _add_system_log("WARNING", "DiskHealth",
                    "Diem suc khoe HDD: %d/100. Canh bao: %s" % (score, "; ".join(warnings[:3])))
            except Exception:
                pass
        if dmesg.get("sata_resets", 0) > 0 or dmesg.get("io_errors", 0) > 0:
            try:
                _add_system_log("ERROR", "DiskHealth",
                    "Phat hien SATA/I/O error: %d SATA reset, %d I/O error trong 5 phut. Kiem tra cap SATA/nguon ngay." % (
                        dmesg.get("sata_resets", 0), dmesg.get("io_errors", 0)))
            except Exception:
                pass
    except Exception as e:
        log.error("[DiskHealth] Sample loi: %s", e)


def _disk_health_prune_old_records():
    """Xoa cac dong .jsonl cu hon retention."""
    try:
        if not os.path.exists(_DISK_HEALTH_HISTORY_FILE): return
        cutoff = int(time.time()) - (_DISK_HEALTH_RETENTION_DAYS * 86400)
        kept = []
        with open(_DISK_HEALTH_HISTORY_FILE, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    obj = json.loads(line)
                    if obj.get("ts", 0) >= cutoff:
                        kept.append(line.rstrip("\n"))
                except Exception:
                    continue
        # Chi rewrite neu thuc su co prune
        if kept and len(kept) < sum(1 for _ in open(_DISK_HEALTH_HISTORY_FILE)):
            tmp = _DISK_HEALTH_HISTORY_FILE + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                f.write("\n".join(kept) + "\n")
            os.replace(tmp, _DISK_HEALTH_HISTORY_FILE)
    except Exception as e:
        log.warning("[DiskHealth] Prune loi: %s", e)


def _disk_health_watchdog():
    """Background daemon: sample moi 5 phut, prune moi 1 gio."""
    time.sleep(30)  # cho server on dinh
    prune_counter = 0
    while True:
        _disk_health_sample_once()
        prune_counter += 1
        if prune_counter >= 12:  # ~1 gio
            _disk_health_prune_old_records()
            prune_counter = 0
        time.sleep(_DISK_HEALTH_SAMPLE_INTERVAL_SEC)


def _add_system_log(level, module, message):
    """Helper: them log vao bang system_logs neu DB available."""
    try:
        conn = sqlite3.connect(DB_PATH, timeout=3.0)
        cur = conn.cursor()
        cur.execute(
            "INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
            (level, module, message)
        )
        conn.commit()
        conn.close()
    except Exception:
        pass


@app.route('/api/disk/health', methods=['GET'])
@requires_auth
def api_disk_health():
    """Snapshot suc khoe HDD hien tai (sample gan nhat trong RAM)."""
    with _disk_health_lock:
        sample = dict(_disk_health_last_sample) if _disk_health_last_sample else None
    if sample is None:
        # Sample on-demand neu chua co
        _disk_health_sample_once()
        with _disk_health_lock:
            sample = dict(_disk_health_last_sample) if _disk_health_last_sample else {}
    return jsonify({
        "current": sample,
        "sample_interval_sec": _DISK_HEALTH_SAMPLE_INTERVAL_SEC,
    })


@app.route('/api/disk/health/history', methods=['GET'])
@requires_auth
def api_disk_health_history():
    """Time-series suc khoe HDD trong N ngay gan day (default 7)."""
    try:
        days = int(request.args.get("days", "7"))
    except Exception:
        days = 7
    days = max(1, min(30, days))
    cutoff = int(time.time()) - (days * 86400)
    items = []
    try:
        if os.path.exists(_DISK_HEALTH_HISTORY_FILE):
            with open(_DISK_HEALTH_HISTORY_FILE, "r", encoding="utf-8") as f:
                for line in f:
                    try:
                        obj = json.loads(line)
                        if obj.get("ts", 0) >= cutoff:
                            items.append(obj)
                    except Exception:
                        continue
    except Exception as e:
        log.warning("[DiskHealth] Read history loi: %s", e)
    return jsonify({
        "days": days,
        "count": len(items),
        "samples": items,
    })


# ============================================================================
# NAS SLEEP SCHEDULE — HDD spindown / full suspend theo lich
# ============================================================================
# Muc dich: giam hao mon HDD (ich biet voi o cu nhieu pending sectors) bang
# cach tu dong spindown ngoai gio dung. Khong tat NAS hoan toan (van ping duoc),
# chi parking head + ngung quay platter.
# ============================================================================
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
        log.warning("[SleepSchedule] Load loi: %s", e)
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
        log.error("[SleepSchedule] Save loi: %s", e)
        return False


def _is_in_sleep_window(sched, now=None):
    """Tra ve True neu thoi diem hien tai nam trong khung gio sleep."""
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
    # Khong co recording
    try:
        with _livestream_lock:
            for j in _livestream_jobs.values():
                if j.get("status") == "recording":
                    return False
    except Exception:
        pass
    return True


def _hdd_spindown():
    """Spindown /dev/sda bang hdparm -y. Tra (ok, msg)."""
    try:
        r = subprocess.run(["hdparm", "-y", "/dev/sda"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        if r.returncode == 0:
            return True, "spundown OK"
        return False, (r.stderr or b"").decode("utf-8", errors="ignore")[:200]
    except Exception as e:
        return False, str(e)[:200]


def _hdd_get_power_state():
    """Doc hdparm -C /dev/sda -> 'active/idle', 'standby', 'sleeping'."""
    try:
        r = subprocess.run(["hdparm", "-C", "/dev/sda"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        if r.returncode == 0:
            out = (r.stdout or b"").decode("utf-8", errors="ignore")
            for line in out.splitlines():
                if "drive state is:" in line:
                    return line.split(":", 1)[1].strip()
    except Exception:
        pass
    return "unknown"


def _sleep_schedule_worker():
    """Daemon: kiem tra moi 5 phut, trigger sleep neu dieu kien dat."""
    time.sleep(120)  # cho server on dinh
    while True:
        try:
            sched = _load_sleep_schedule()
            if not sched.get("enabled"):
                time.sleep(300)
                continue
            in_window = _is_in_sleep_window(sched)
            if not in_window:
                time.sleep(300)
                continue
            # In window — kiem tra dieu kien idle
            if sched.get("idle_only", True) and not _system_is_idle():
                # Busy — bo qua tick nay, check lai sau 5 phut
                time.sleep(300)
                continue
            # Trigger sleep action
            mode = sched.get("mode", "spindown")
            if mode == "spindown":
                # Chi spindown neu HDD dang quay
                state = _hdd_get_power_state()
                if "standby" in state or "sleeping" in state:
                    # Da spindown roi, skip
                    time.sleep(300)
                    continue
                ok, msg = _hdd_spindown()
                sched["last_action_ts"] = int(time.time())
                sched["last_action_state"] = "spindown_active" if ok else "spindown_failed: " + msg
                _save_sleep_schedule(sched)
                if ok:
                    _add_system_log("INFO", "SleepSchedule", "HDD spindown thanh cong (gio %d-%d)" % (sched.get("start_hour"), sched.get("end_hour")))
                else:
                    _add_system_log("WARNING", "SleepSchedule", "HDD spindown loi: %s" % msg)
            elif mode == "suspend":
                # Full suspend - dung systemctl
                sched["last_action_ts"] = int(time.time())
                sched["last_action_state"] = "suspend_initiated"
                _save_sleep_schedule(sched)
                _add_system_log("INFO", "SleepSchedule", "NAS suspend (full) gio %d-%d. Wake bang WoL." % (sched.get("start_hour"), sched.get("end_hour")))
                # Don't actually suspend — too aggressive; user must opt in via explicit endpoint
                # subprocess.run(["systemctl", "suspend"])
            time.sleep(300)
        except Exception as e:
            log.error("[SleepSchedule] Worker loi: %s", e)
            time.sleep(300)


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
    "retention_count": 7,    # giu N backup gan nhat
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
        log.warning("[BackupSchedule] Load loi: %s", e)
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
        log.error("[BackupSchedule] Save loi: %s", e)
        return False


def _create_backup_tarball():
    """Tao 1 backup tar.gz, return path. Tach ra de scheduled job dung lai."""
    if not _ensure_backup_dir():
        raise IOError("Khong tao duoc thu muc backup")
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
    with tarfile.open(full_path, "w:gz") as tar:
        for src, arcname, _crit in all_files:
            if not os.path.exists(src): continue
            try:
                tar.add(src, arcname=arcname)
                sz = 0
                try: sz = os.path.getsize(src)
                except Exception: pass
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
    """Xoa cac backup cu, chi giu N file gan nhat."""
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
                log.info("[BackupSchedule] Retention: xoa %s", os.path.basename(path))
            except Exception as e:
                log.warning("[BackupSchedule] Khong xoa duoc %s: %s", path, e)
    except Exception as e:
        log.warning("[BackupSchedule] Retention loi: %s", e)


def _rclone_upload_backup(local_path, remote, remote_path):
    """Upload 1 backup file len rclone remote. Tra (ok, msg)."""
    if not remote:
        return True, "skip — chua cau hinh rclone remote"
    rclone_bin = "/usr/bin/rclone"
    if not os.path.exists(rclone_bin):
        return False, "rclone khong duoc cai"
    try:
        full_remote = remote.rstrip(":") + ":" + remote_path.lstrip("/")
        r = subprocess.run(
            [rclone_bin, "copy", local_path, full_remote, "--quiet", "--timeout=300s"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600
        )
        if r.returncode == 0:
            return True, "uploaded to " + full_remote
        return False, (r.stderr or b"").decode("utf-8", errors="ignore")[:200]
    except Exception as e:
        return False, str(e)[:200]


def _scheduled_backup_worker():
    """Daemon kiem tra lich moi 5 phut, chay backup dung gio."""
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
                # Da toi gio chay va chua chay hom nay
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
                        "Backup theo lich loi: %s" % str(e)[:200])
                sched["last_run_ts"] = int(time.time())
                _save_backup_schedule(sched)
            time.sleep(300)  # check moi 5 phut
        except Exception as e:
            log.error("[BackupSchedule] Worker loi: %s", e)
            time.sleep(300)


@app.route('/api/backup/schedule', methods=['GET'])
@requires_auth
def api_backup_schedule_get():
    return jsonify(_load_backup_schedule())


@app.route('/api/backup/schedule', methods=['POST'])
@requires_auth
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
    ok = _save_backup_schedule(sched)
    return jsonify({"saved": ok, "schedule": sched})


# ============================================================================
# BACKUP / RESTORE — Sao luu va khoi phuc cau hinh NAS
# ============================================================================
# Backup tarball chua moi config/state cua NAS API + WebDAV + fan + watcher.
# Luu vao /etc/nas/backups (eMMC, an toan khi HDD chet).
# Filename: "Backup_NAS DDMMYYYY HHMMSS.tar.gz"
#
# Cac file duoc backup (manifest.json dinh kem trong tarball):
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
#   <WEBDAV_ROOT>/cookies.txt                       (TikTok cookies — neu accessible)
#   <WEBDAV_ROOT>/.nas_meta/tiktok_live_watch.json  (watcher state on HDD)
# ============================================================================
import tarfile

_BACKUP_DIR = "/etc/nas/backups"
_BACKUP_FILES = [
    # (source_path, relative_path_in_tar, critical)
    # critical = True -> bao loi neu thieu khi restore
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
    """Cac path phu thuoc WEBDAV_FILE_ROOT (HDD). Lay vao runtime."""
    return [
        (os.path.join(WEBDAV_FILE_ROOT, "cookies.txt"),                     "webdav_root/cookies.txt",                     False),
        (os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "tiktok_live_watch.json"), "webdav_root/.nas_meta/tiktok_live_watch.json", False),
    ]


def _ensure_backup_dir():
    try:
        os.makedirs(_BACKUP_DIR, exist_ok=True)
        return True
    except Exception as e:
        log.error("[Backup] Khong tao duoc thu muc: %s", e)
        return False


@app.route('/api/backup/create', methods=['POST'])
@requires_auth
def api_backup_create():
    """Tao 1 backup .tar.gz chua moi config/state hien tai."""
    if not _ensure_backup_dir():
        return jsonify({"error": "Khong tao duoc thu muc backup"}), 500
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
        with tarfile.open(full_path, "w:gz") as tar:
            for src, arcname, critical in all_files:
                if not os.path.exists(src):
                    skipped.append({"path": src, "reason": "khong ton tai"})
                    continue
                try:
                    tar.add(src, arcname=arcname)
                    sz = 0
                    try: sz = os.path.getsize(src)
                    except Exception: pass
                    manifest["files"].append({
                        "src": src,
                        "archive_path": arcname,
                        "size": sz,
                    })
                    included += 1
                except Exception as e:
                    skipped.append({"path": src, "reason": str(e)[:100]})
                    if critical:
                        log.warning("[Backup] File critical bi loi: %s -> %s", src, e)
            # Them manifest vao tarball cuoi cung
            manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8")
            info = tarfile.TarInfo(name="manifest.json")
            info.size = len(manifest_bytes)
            info.mtime = int(time.time())
            try:
                import io as _io
                tar.addfile(info, _io.BytesIO(manifest_bytes))
            except Exception as e:
                log.warning("[Backup] Khong add duoc manifest: %s", e)
        size = os.path.getsize(full_path)
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
        log.error("[Backup] Tao backup loi: %s", e)
        return jsonify({"error": "Khong tao duoc backup: %s" % str(e)[:200]}), 500


@app.route('/api/backup/list', methods=['GET'])
@requires_auth
def api_backup_list():
    """List moi backup co san trong /etc/nas/backups."""
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
        log.warning("[Backup] List loi: %s", e)
    return jsonify({"backups": items, "backup_dir": _BACKUP_DIR})


@app.route('/api/backup/download', methods=['GET'])
@requires_auth
def api_backup_download():
    """Stream 1 file backup ve client."""
    filename = request.args.get("filename", "").strip()
    if not filename or "/" in filename or ".." in filename:
        return jsonify({"error": "Filename khong hop le"}), 400
    full = os.path.join(_BACKUP_DIR, filename)
    if not os.path.exists(full):
        return jsonify({"error": "File khong ton tai"}), 404
    try:
        from flask import send_file
        return send_file(full, mimetype="application/gzip",
                         as_attachment=True, attachment_filename=filename)
    except TypeError:
        # Flask cu khong co attachment_filename keyword
        from flask import send_file
        return send_file(full, mimetype="application/gzip", as_attachment=True)


@app.route('/api/backup/delete', methods=['POST'])
@requires_auth
def api_backup_delete():
    body = request.get_json(force=True) or {}
    filename = (body.get("filename") or "").strip()
    if not filename or "/" in filename or ".." in filename:
        return jsonify({"error": "Filename khong hop le"}), 400
    full = os.path.join(_BACKUP_DIR, filename)
    if not os.path.exists(full):
        return jsonify({"error": "File khong ton tai"}), 404
    try:
        os.remove(full)
        log.info("[Backup] Da xoa %s", filename)
        return jsonify({"status": "deleted", "filename": filename})
    except Exception as e:
        return jsonify({"error": "Khong xoa duoc: %s" % str(e)[:200]}), 500


@app.route('/api/backup/restore', methods=['POST'])
@requires_auth
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
            tmp = os.path.join("/tmp", "restore_upload_%d.tar.gz" % int(time.time()))
            up.save(tmp)
            src_tar = tmp
            cleanup_after = True
        else:
            body = {}
            try: body = request.get_json(silent=True) or {}
            except Exception: body = {}
            filename = (body.get("filename") or "").strip()
            if filename and "/" not in filename and ".." not in filename:
                src_tar = os.path.join(_BACKUP_DIR, filename)
        if not src_tar or not os.path.exists(src_tar):
            return jsonify({"error": "Khong tim thay file backup de khoi phuc"}), 400

        restored = []
        errors = []
        manifest = None
        with tarfile.open(src_tar, "r:gz") as tar:
            # Doc manifest truoc
            try:
                m_member = tar.getmember("manifest.json")
                m_file = tar.extractfile(m_member)
                if m_file:
                    manifest = json.loads(m_file.read().decode("utf-8"))
            except Exception as e:
                log.warning("[Backup] Khong doc duoc manifest: %s", e)

            # Build map archive_path -> real_dest
            file_map = {}
            for src, arcname, _crit in _BACKUP_FILES:
                file_map[arcname] = src
            for src, arcname, _crit in _backup_dynamic_files():
                file_map[arcname] = src

            for member in tar.getmembers():
                if member.name == "manifest.json" or not member.isfile():
                    continue
                dest = file_map.get(member.name)
                if not dest:
                    errors.append({"file": member.name, "reason": "khong xac dinh duoc duong dan dich"})
                    continue
                try:
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                except Exception as e:
                    errors.append({"file": dest, "reason": "mkdir loi: %s" % e})
                    continue
                try:
                    f = tar.extractfile(member)
                    if f is None:
                        errors.append({"file": dest, "reason": "tar khong doc duoc"})
                        continue
                    data = f.read()
                    # Backup file dich hien tai truoc khi ghi de (rollback neu can)
                    if os.path.exists(dest):
                        try: os.replace(dest, dest + ".pre-restore")
                        except Exception: pass
                    tmp = dest + ".restore-tmp"
                    with open(tmp, "wb") as w:
                        w.write(data)
                    os.replace(tmp, dest)
                    # Phuc hoi quyen co ban: auth.conf phai chmod 600
                    if dest.endswith("auth.conf"):
                        try: os.chmod(dest, 0o600)
                        except Exception: pass
                    if dest.endswith(".sh"):
                        try: os.chmod(dest, 0o755)
                        except Exception: pass
                    restored.append(dest)
                except Exception as e:
                    errors.append({"file": dest, "reason": str(e)[:120]})

        # Khoi dong lai services chinh
        services_restarted = []
        for svc in ("nas_api", "nginx", "fan"):
            try:
                subprocess.run(["systemctl", "daemon-reload"], timeout=10)
                r = subprocess.run(["systemctl", "restart", svc], timeout=15, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if r.returncode == 0:
                    services_restarted.append(svc)
            except Exception:
                pass

        return jsonify({
            "status": "restored",
            "restored_count": len(restored),
            "restored": restored,
            "errors": errors,
            "services_restarted": services_restarted,
            "manifest": manifest,
        })
    except Exception as e:
        log.error("[Backup] Restore loi: %s", e)
        return jsonify({"error": "Khong khoi phuc duoc: %s" % str(e)[:200]}), 500
    finally:
        if cleanup_after and src_tar:
            try: os.remove(src_tar)
            except Exception: pass


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
        # tra mode cu vi cache khong duoc cap nhat ngay.
        if mode == 'auto':
            settings['mode'] = 'auto'
            _save_fan_settings(settings)
            # fan.service se tu set duty va enable theo nhiet do. Phai bao dam
            # enable=1 truoc khi start service de service khong gap PWM da bi
            # disable boi lan "off" truoc do.
            _pwm_write("enable", 1)
            run_cmd(["systemctl", "start", "fan.service"])
            with _cache_lock:
                _status_cache['fan_mode'] = 'auto'
            return jsonify({"status": "success", "mode": "auto"})

        elif mode == 'custom':
            settings['mode'] = 'custom'
            settings['on_temp'] = data.get('on_temp', settings.get('on_temp', 65))
            settings['off_temp'] = data.get('off_temp', settings.get('off_temp', 55))
            _save_fan_settings(settings)
            run_cmd(["systemctl", "stop", "fan.service"])
            # Watchdog se quyet dinh duty 0/10000 theo hysteresis. Cho phep
            # PWM peripheral chay san de watchdog ghi duty co tac dung.
            _pwm_write("enable", 1)
            with _cache_lock:
                _status_cache['fan_mode'] = 'custom'
                _status_cache['fan_on_temp'] = settings['on_temp']
                _status_cache['fan_off_temp'] = settings['off_temp']
            return jsonify({"status": "success", "mode": "custom", "on_temp": settings['on_temp'], "off_temp": settings['off_temp']})

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
                _status_cache['fan_status'] = 'Dừng'
            return jsonify({"status": "success", "mode": "off"})

        elif mode == 'on':
            settings['mode'] = 'on'
            _save_fan_settings(settings)
            run_cmd(["systemctl", "stop", "fan.service"])
            # FIX: bat PWM tu trang thai disabled (lan tat truoc) -> phai dam bao
            # enable=1 sau khi set duty. Helper xu ly thu tu period/duty/enable.
            _pwm_apply_on(duty=10000, period=10000)
            with _cache_lock:
                _status_cache['fan_mode'] = 'on'
                _status_cache['fan_status'] = 'Đang chạy 100%'
            return jsonify({"status": "success", "mode": "on"})
            
        return jsonify({"error": "Chế độ không hợp lệ"}), 400
    except Exception as e:
        return jsonify({"error": str(e)}), 500

# ============ TORRENT (qBittorrent) ============

@app.route("/api/torrent/control", methods=["POST"])
@requires_auth
def api_torrent_control():
    try:
        import urllib.request, urllib.parse
        data = request.get_json(force=True)
        action = data.get("action", "")
        torrent_hash = data.get("hash", "")

        if not torrent_hash:
            return jsonify({"error": "Thiếu hash"}), 400

        qbt_base = "http://127.0.0.1:8080/api/v2"

        # Step 1: Login to qBittorrent to get SID cookie
        login_data = urllib.parse.urlencode({"username": "admin", "password": "adminadmin"}).encode("utf-8")
        login_req = urllib.request.Request("%s/auth/login" % qbt_base, data=login_data)
        login_resp = urllib.request.urlopen(login_req, timeout=5)
        sid_cookie = ""
        for header in login_resp.info().get_all("Set-Cookie") or []:
            if "SID=" in header:
                sid_cookie = header.split("SID=")[1].split(";")[0]
                break

        # Step 2: Map Android actions to qBittorrent v5 API endpoints
        # qBt v5.x renamed: pause -> stop, resume -> start
        if action == "pause":
            url = "%s/torrents/stop" % qbt_base
        elif action == "resume":
            url = "%s/torrents/start" % qbt_base
        elif action == "delete":
            url = "%s/torrents/delete" % qbt_base
        else:
            return jsonify({"error": "Hành động không xác định"}), 400

        # Step 3: Send POST with form-encoded body + SID cookie
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
        data = request.get_json(force=True)
        link = data.get("url", "")
        if not link:
            return jsonify({"error": "Thiếu URL"}), 400

        qbt_url = "http://127.0.0.1:8080/api/v2/torrents/add"
        post_data = urllib.parse.urlencode({"urls": link}).encode()
        req = urllib.request.Request(qbt_url, data=post_data)
        urllib.request.urlopen(req, timeout=10)
        return jsonify({"result": "ok"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/torrent/add_file", methods=["POST"])
@requires_auth
def api_torrent_add_file():
    """Upload mot file .torrent va forward sang qBittorrent."""
    try:
        if "file" not in request.files:
            return jsonify({"error": "Khong co file .torrent trong request"}), 400
        f = request.files["file"]
        fname = (f.filename or "").strip()
        if not fname:
            return jsonify({"error": "File khong co ten"}), 400
        if not fname.lower().endswith(".torrent"):
            return jsonify({"error": "File phai co duoi .torrent"}), 400
        content = f.read()
        if not content or len(content) < 64:
            return jsonify({"error": "File torrent rong hoac qua nho"}), 400
        # qBittorrent magic: torrent file bat dau bang 'd' (bencode dict)
        if content[0:1] != b"d":
            return jsonify({"error": "File khong phai bencode torrent hop le"}), 400

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
            # qBittorrent tra "Ok." khi thanh cong, "Fails." khi loi
            if "Ok" in qbt_response or resp.getcode() == 200:
                return jsonify({"result": "ok", "filename": fname, "size": len(content)})
            return jsonify({"error": "qBittorrent tu choi: %s" % qbt_response[:200]}), 502
        except urllib.error.HTTPError as he:
            return jsonify({"error": "qBittorrent HTTP %d" % he.code}), 502
    except Exception as e:
        log.warning("[Torrent] add_file loi: %s", e)
        return jsonify({"error": str(e)[:200]}), 500


# ============ GIAI NEN FILE ============

@app.route("/api/file/unzip", methods=["POST"])
@requires_auth
def api_unzip():
    try:
        data = request.get_json(force=True)
        file_path = data.get("path", "") or data.get("file_path", "")
        if not file_path or not os.path.exists(file_path):
            return jsonify({"error": "Không tìm thấy tệp"}), 404
        # SECURITY: Validate file path to prevent path traversal
        if not _validate_file_path(file_path):
            return jsonify({"error": "Đường dẫn tệp không hợp lệ"}), 403

        dest_dir = os.path.dirname(file_path)
        ext = file_path.lower()
        if ext.endswith(".zip"):
            run_cmd(["unzip", "-o", file_path, "-d", dest_dir], timeout=300)
        elif ext.endswith(".rar"):
            run_cmd(["unrar", "x", "-o+", file_path, dest_dir + "/"], timeout=300)
        elif ext.endswith((".tar.gz", ".tgz")):
            run_cmd(["tar", "xzf", file_path, "-C", dest_dir], timeout=300)
        elif ext.endswith((".tar", ".tar.bz2")):
            run_cmd(["tar", "xf", file_path, "-C", dest_dir], timeout=300)
        elif ext.endswith(".7z"):
            run_cmd(["7z", "x", file_path, "-o" + dest_dir, "-y"], timeout=300)
        else:
            return jsonify({"error": "Định dạng không được hỗ trợ"}), 400

        return jsonify({"result": "ok"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ============ BAO MAT ============

@app.route("/api/auth/approve_ip", methods=["POST"])
@requires_auth
def api_approve_ip():
    try:
        data = request.get_json(force=True)
        ip = data.get("ip", "")
        approved = data.get("approved", True)
        if ip:
            conn = sqlite3.connect(DB_PATH, timeout=20.0)
            cur = conn.cursor()
            if approved:
                # Thêm vào authorized_ips DB
                cur.execute('INSERT OR REPLACE INTO authorized_ips VALUES (?, ?)', (ip, datetime.datetime.now()))
                cur.execute('DELETE FROM auth_attempts WHERE ip=?', (ip,))
                # Gỡ ban iptables nếu bị chặn trước đó
                try: subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'DROP'], stderr=subprocess.DEVNULL)
                except Exception: pass
                cur.execute('DELETE FROM banned_ips WHERE ip=?', (ip,))
                # Thêm vào LAN whitelist file để bền vững qua restart
                if ip not in _lan_whitelist:
                    _lan_whitelist.add(ip)
                    _save_lan_whitelist()
                now = datetime.datetime.now().strftime("%d/%m/%y %H:%M:%S")
                cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                           ("SUCCESS", "Security", "[{}] Admin đã CẤP QUYỀN cho IP: {} và thêm vào whitelist.".format(now, ip)))
            else:
                # Ban IP vĩnh viễn
                ban_ip_permanently(ip)
                now = datetime.datetime.now().strftime("%d/%m/%y %H:%M:%S")
                cur.execute('INSERT OR REPLACE INTO banned_ips VALUES (?, ?, ?)', (ip, "Admin denied", now))
                cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                           ("ERROR", "Security", "[{}] Admin đã CHẶN VĨNH VIỄN IP: {} bằng iptables.".format(now, ip)))
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
        conn = sqlite3.connect(DB_PATH, timeout=20.0)
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
    Tra ve danh sach canh bao moi chua doc + trang thai he thong hien tai.
    """
    since_ts = request.args.get("since", "")  # Lay canh bao ke tu timestamp nay
    with _alert_state_lock:
        alerts = list(_alert_states["last_alerts"])
        ai_running = _alert_states["ai_scan_running"]

    # Loc canh bao theo timestamp neu co tham so 'since'
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

    # Lay trang thai HDD hien tai (tu cache, khong wake HDD)
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
    """Xoa hang doi canh bao sau khi Android da xu ly."""
    with _alert_state_lock:
        _alert_states["last_alerts"] = []
    return jsonify({"result": "ok"})


@app.route("/api/cron/status")
@requires_auth
def api_cron_status():
    """Trang thai cua cron worker: luc don rac gan nhat, AI quet lan cuoi."""
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
    """Lay lich su nhiet do (120 diem gan nhat tuong duong khoang 2 tieng)."""
    try:
        conn = sqlite3.connect(DB_PATH, timeout=20.0)
        cur = conn.cursor()
        cur.execute("SELECT strftime('%H:%M', timestamp), cpu_temp, hdd_temp FROM system_temperature_history ORDER BY id DESC LIMIT 120")
        rows = cur.fetchall()
        conn.close()
        
        # Rows dang DESC, reverse thanh ASC de ve bieu do dien tien xuoi
        rows.reverse()
        history = [{"time": r[0], "cpu": round(r[1], 1), "hdd": round(r[2], 1)} for r in rows]
        return jsonify({"history": history})
    except Exception as e:
        return jsonify({"history": [], "error": "Không tải được lịch sử nhiệt độ: %s" % normalize_vietnamese_message(str(e))})

@app.route("/api/cron/trash/clean", methods=["POST"])
@requires_auth
def api_cron_trash_clean():
    """Kich hoat thu cong don dep Thung rac ngay lap tuc (khong can doi cron)."""
    try:
        data = request.get_json(force=True)
        max_days = int(data.get("max_age_days", 30))
        deleted = _clean_trash(WEBDAV_FILE_ROOT, max_age_days=max_days)
        msg = "Đã xóa %d tệp trong Thùng rác (quá %d ngày)." % (deleted, max_days)
        _push_alert("TRASH_CLEANED", msg, "INFO")
        return jsonify({"result": "ok", "deleted": deleted, "message": msg})
    except Exception as e:
        return jsonify({"result": "error", "message": "Không dọn được Thùng rác: %s" % normalize_vietnamese_message(str(e))}), 500

@app.route("/api/system_logs", methods=["GET"])
def api_system_logs():
    """Tra ve danh sach nhat ky he thong (AccessLog, DuplicateScan...) tu NAS."""
    try:
        conn = sqlite3.connect(DB_PATH)
        cur = conn.cursor()
        cur.execute("SELECT id, type, module, message, timestamp FROM system_logs ORDER BY id DESC LIMIT 50")
        logs = [{"id": r[0], "type": r[1], "module": r[2], "message": normalize_vietnamese_message(r[3]), "timestamp": r[4]} for r in cur.fetchall()]
        conn.close()
        return jsonify({"status": "success", "logs": logs})
    except Exception as e:
        return jsonify({"status": "error", "message": "Không tải được nhật ký hệ thống: %s" % normalize_vietnamese_message(str(e))}), 500


# ============ SMART PHOTOS (GALLERY KHAM PHA) ============
# Phan loai anh nhe theo cau truc thu muc — Python 3.5, KHONG can Docker/TFLite

@app.route("/api/ai/tags")
@requires_auth
def api_ai_tags():
    """Tra ve phan loai anh theo thu muc. Quet truc tiep tren NAS."""
    try:
        if not os.path.exists(AI_TAGS_PATH):
            return jsonify({
                "categories": {},
                "total": 0,
                "status": "Chưa có dữ liệu. Nhấn nút Quét để phân loại ảnh.",
                "ai_running": _alert_states.get("ai_scan_running", False)
            })
        mtime = os.path.getmtime(AI_TAGS_PATH)
        with open(AI_TAGS_PATH, "r") as f:
            tags_data = json.load(f)

        return jsonify({
            "categories": tags_data.get("categories", {}),
            "total": tags_data.get("total", 0),
            "status": "ok",
            "last_scan": datetime.datetime.fromtimestamp(mtime).strftime("%d/%m/%Y %H:%M"),
            "ai_running": _alert_states.get("ai_scan_running", False)
        })
    except Exception as e:
        return jsonify({"categories": {}, "total": 0, "status": "Lỗi: %s" % normalize_vietnamese_message(str(e))}), 500


@app.route("/api/ai/trigger", methods=["POST"])
@requires_auth
def api_ai_trigger():
    """Kich hoat quet phan loai anh tren NAS (chay nen, Python 3.5 thuan)."""
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
    """Trang thai quet phan loai anh."""
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


# ============ VIDEO STREAM TRANSCODE ============
# Transcode video sang MP4 (H.264 + AAC) on-the-fly bang FFmpeg
# ExoPlayer tren Android khong giai ma duoc MPEG-2 (.mpg) tren nhieu thiet bi


_transcode_sessions = {}  # session_id -> { "file_path": ..., "duration": ..., "process": Popen }

def _find_source_file(relative_path):
    """Tim file goc tren NAS tu duong dan WebDAV tuong doi.
    FIX SECURITY: Validate path traversal truoc khi tra ve."""
    from urllib.parse import unquote
    relative_path = unquote(relative_path)
    candidates = [
        os.path.join(WEBDAV_FILE_ROOT, relative_path.lstrip("/")),
        relative_path,
        os.path.join("/srv/dev-disk-by-label-data", relative_path.lstrip("/")),
    ]
    for c in candidates:
        if os.path.exists(c) and _validate_file_path(c):
            return c
    return None

def _get_video_duration(file_path):
    """Lay thoi luong video (giay) bang ffprobe"""
    try:
        cmd = ["/usr/bin/ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file_path]
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        return float(result.stdout.decode('utf-8', errors='ignore').strip())
    except Exception as e:
        log.warning("[HLS] Lỗi đọc thời lượng bằng ffprobe: %s", e)
        return 7200.0  # Fallback 2 tieng neu loi

@app.route("/api/stream/transcode")
@requires_auth
def api_stream_transcode():
    """Khoi tao session JIT HLS"""
    import hashlib
    relative_path = request.args.get("path", "")
    if not relative_path:
        return jsonify({"error": "Thiếu tham số 'path'"}), 400

    file_path = _find_source_file(relative_path)
    if not file_path:
        return jsonify({"error": "Không tìm thấy tệp"}), 404

    session_id = hashlib.md5(file_path.encode()).hexdigest()[:12]
    hls_dir = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "nas_transcode", session_id)

    # Tu dong don dep rac HLS cu (>2h)
    try:
        base = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "nas_transcode")
        if os.path.exists(base):
            for d in os.listdir(base):
                dp = os.path.join(base, d)
                if os.path.isdir(dp) and (time.time() - os.path.getmtime(dp)) > 7200:
                    import shutil
                    shutil.rmtree(dp, ignore_errors=True)
    except Exception:
        pass

    os.makedirs(hls_dir, exist_ok=True)

    # Lay tong thoi gian cua video
    duration = _get_video_duration(file_path)
    
    _transcode_sessions[session_id] = {
        "file_path": file_path,
        "duration": duration,
        "process": None
    }
    log.info("[JIT HLS] Khoi tao: %s (Duration: %.1fs)", file_path, duration)

    # Redirect den file m3u8 — ExoPlayer se call tiep vao /api/stream/hls/
    from flask import redirect
    return redirect("/api/stream/hls/%s/playlist.m3u8" % session_id)


@app.route("/api/stream/hls/<session_id>/<filename>")
@requires_auth
def api_stream_hls_file(session_id, filename):
    """
    Just-in-Time HLS Server.
    - Neu request playlist.m3u8: tra ve file VOD fake day du tat ca cac segment.
    - Neu request seg00100.ts: kiem tra neu co, gui ve. Neu chua co, chay FFmpeg tu -ss 400.
    """
    if session_id not in _transcode_sessions:
        return "", 404
        
    session = _transcode_sessions[session_id]
    hls_dir = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "nas_transcode", session_id)

    if filename == "playlist.m3u8":
        # Tao playlist M3U8 kieu VOD co day du tat ca segments
        duration = session["duration"]
        lines = [
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:4",
            "#EXT-X-MEDIA-SEQUENCE:0",
            "#EXT-X-PLAYLIST-TYPE:VOD"
        ]
        
        seg_duration = 4.0
        total_segs = int(duration / seg_duration)
        for i in range(total_segs):
            lines.append("#EXTINF:%.6f," % seg_duration)
            lines.append("seg%05d.ts" % i)
            
        rem = duration - (total_segs * seg_duration)
        if rem > 0:
            lines.append("#EXTINF:%.6f," % rem)
            lines.append("seg%05d.ts" % total_segs)
            
        lines.append("#EXT-X-ENDLIST")
        playlist_text = "\n".join(lines)
        
        from flask import make_response
        response = make_response(playlist_text)
        response.headers["Content-Type"] = "application/vnd.apple.mpegurl"
        # Chong cache de tranh loi
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        return response

    if filename.endswith(".ts"):
        # Lay so index xuong doan ts (vi du seg00100.ts -> 100)
        import re
        match = re.search(r"seg(\d+)\.ts", filename)
        if not match:
            return "", 404
            
        seg_idx = int(match.group(1))
        file_path = os.path.join(hls_dir, filename)

        # Neu file da duoc transcode roi thi gui luon
        if not os.path.exists(file_path):
            # Tinh gio bat dau
            start_time = seg_idx * 4.0
            
            # Kill tien trinh FFmpeg cu neu co (do nguoi dung vua tua)
            if session["process"] is not None:
                try:
                    session["process"].kill()
                    session["process"] = None
                except Exception:
                    pass
            
            # Chay FFmpeg tu diem start_time
            cmd = [
                "/usr/bin/ffmpeg",
                "-ss", str(start_time),     # Tua file goc den dung vi tri can transcode (fast seek)
                "-i", session["file_path"],
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-f", "hls",
                "-hls_time", "4",
                "-hls_list_size", "0",
                "-start_number", str(seg_idx), # De file ra dung ten seg%05d.ts tuong ung
                "-hls_segment_filename", os.path.join(hls_dir, "seg%05d.ts"),
                "-y",
                os.path.join(hls_dir, "dummy.m3u8")
            ]
            
            log.info("[JIT HLS] %s | Bat dau transcode tu giay %ds...", filename, start_time)
            session["process"] = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            
            # Cho den khi FFmpeg ghi xong file TS do:
            # Vi FFmpeg 3.2 khong ho tro temp_file, no ghi truc tiep vao segXXXXX.ts
            # Nen ta biet no ghi xong khi file KẾ TIẾP (segXXXXX+1.ts) xuat hien, hoac FFmpeg thoat
            next_seg = os.path.join(hls_dir, "seg%05d.ts" % (seg_idx + 1))
            wait_count = 0
            while wait_count < 60:
                if session["process"].poll() is not None:
                    # Tien trinh FFmpeg da thoat (co the do xong file hoac loi)
                    break 
                if os.path.exists(next_seg):
                    # File tiep theo da ton tai -> file hien tai chac chan da ghi xong 100%
                    break
                time.sleep(0.5)
                wait_count += 1
                
            if not os.path.exists(file_path):
                log.error("[JIT HLS] Lỗi FFmpeg, không thể tạo %s", filename)
                return "", 500

        # Tra file ts ve
        from flask import send_file as flask_send_file
        response = make_response(flask_send_file(file_path, mimetype="video/mp2t"))
        response.headers["Cache-Control"] = "public, max-age=31536000" # Cache file video mai mai
        return response

    return "", 404


@app.route("/api/tools/organize_legacy_videos", methods=["POST"])
@requires_auth
def api_organize_legacy_videos():
    """
    Quet toan bo WEBDAV_FILE_ROOT, di chuyen cac video khong phai mp4 vao /Other Video/<ext>/
    """
    import shutil
    
    # Danh sach giay phep (chi video, khong phai mp4)
    target_exts = {".mpg", ".mpeg", ".avi", ".wmv", ".flv", ".mkv", ".mov", ".ts", ".m4v", ".3gp"}
    
    other_video_dir = os.path.join(WEBDAV_FILE_ROOT, "Other Video")
    
    moved_count = 0
    errors = []
    
    # Quet tat ca thu muc trong WEBDAV_FILE_ROOT
    for root, dirs, files in os.walk(WEBDAV_FILE_ROOT):
        # Bo qua thu muc Other Video de khong di chuyen vong lap
        if os.path.abspath(root).startswith(os.path.abspath(other_video_dir)):
            continue
            
        for file in files:
            ext = os.path.splitext(file)[1].lower()
            if ext in target_exts:
                old_path = os.path.join(root, file)
                
                # Tao thu muc Other Video/<ext>
                ext_name = ext.lstrip(".")
                target_dir = os.path.join(other_video_dir, ext_name)
                os.makedirs(target_dir, exist_ok=True)
                
                # Tranh trung ten file
                new_path = os.path.join(target_dir, file)
                if os.path.exists(new_path):
                    base, ex = os.path.splitext(file)
                    new_path = os.path.join(target_dir, "%s_%d%s" % (base, int(time.time()), ex))
                    
                try:
                    shutil.move(old_path, new_path)
                    moved_count += 1
                    log.info("[Organize] Moved: %s -> %s", file, target_dir)
                except Exception as e:
                    errors.append(str(e))
                    
    # Fix quyen truy cap cho thu muc WebDAV vi script nay chay duoi quyen root
    # OpenMediaVault yeu cau ACL can ban (getfacl) va SGID (2775) de WebDAV nhin thay duoc
    if moved_count > 0:
        try:
            _run_acl_copy(WEBDAV_FILE_ROOT, other_video_dir)
            subprocess.run(["chown", "-R", "daica:webdav-users", other_video_dir])
            subprocess.run(["chmod", "-R", "2775", other_video_dir])
        except Exception as e:
            log.warning("[Organize] Không thể sửa quyền ACL: %s", e)
            
    return jsonify({
        "success": True,
        "moved_count": moved_count,
        "errors": errors
    })


# ============ SMART ORGANIZER (Sap xep file theo Nam/Thang) ============

_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".gif"}
_VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".mpg", ".mpeg", ".wmv", ".flv", ".ts", ".m4v", ".3gp"}
_ALL_MEDIA_EXTS = _IMAGE_EXTS | _VIDEO_EXTS

@app.route("/api/tools/smart_organize/scan", methods=["POST"])
@requires_auth
def api_smart_organize_scan():
    """
    Quet thu muc WebDAV, phan nhom file theo Nam/Thang (mtime).
    Input JSON: { "path": "/webdav/", "filter": "all|image|video" }
    Tra ve: { "total": N, "groups": [ { "label": "2024/03", "count": X, "size": Y } ] }
    """
    data = request.get_json(force=True) or {}
    scan_filter = data.get("filter", "all")

    base_dir = get_webdav_root()

    if scan_filter == "image":
        allowed_exts = _IMAGE_EXTS
    elif scan_filter == "video":
        allowed_exts = _VIDEO_EXTS
    else:
        allowed_exts = _ALL_MEDIA_EXTS

    # Thu thap file, nhom theo YYYY/MM
    groups_map = {}  # key = "2024/03", value = { "count": N, "size": S, "files": [...] }
    total = 0

    for root, dirs, files in os.walk(base_dir):
        # Bo qua thu muc an va hệ thong
        dirs[:] = [d for d in dirs if not d.startswith('.') and d != '#recycle']
        for name in files:
            if name.startswith('.'):
                continue
            ext = os.path.splitext(name)[1].lower()
            if ext not in allowed_exts:
                continue

            full_path = os.path.join(root, name)
            try:
                st = os.stat(full_path)
                mtime = st.st_mtime
                size = st.st_size
                dt = datetime.datetime.fromtimestamp(mtime)
                label = "%04d/%02d" % (dt.year, dt.month)

                rel_path = full_path[len(base_dir):]
                if not rel_path.startswith("/"):
                    rel_path = "/" + rel_path

                # Kiem tra file da nam trong thu muc YYYY/MM chua (bo qua neu da dung cho)
                parent_dir = os.path.dirname(rel_path).strip("/")
                if parent_dir == label or parent_dir.endswith("/" + label):
                    continue

                if label not in groups_map:
                    groups_map[label] = {"count": 0, "size": 0, "sample_files": []}

                groups_map[label]["count"] += 1
                groups_map[label]["size"] += size
                total += 1

                # Giu toi da 5 file mau de hien thi preview
                if len(groups_map[label]["sample_files"]) < 5:
                    groups_map[label]["sample_files"].append({
                        "name": name,
                        "path": rel_path,
                        "size": size,
                        "mtime": int(mtime * 1000)
                    })
            except Exception:
                pass

    # Sap xep theo thoi gian giam dan (moi nhat truoc)
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


@app.route("/api/tools/smart_organize/execute", methods=["POST"])
@requires_auth
def api_smart_organize_execute():
    """
    Thuc thi sap xep: Di chuyen file vao thu muc YYYY/MM.
    Input JSON: { "filter": "all|image|video" }
    """
    import shutil

    data = request.get_json(force=True) or {}
    scan_filter = data.get("filter", "all")

    base_dir = get_webdav_root()

    if scan_filter == "image":
        allowed_exts = _IMAGE_EXTS
    elif scan_filter == "video":
        allowed_exts = _VIDEO_EXTS
    else:
        allowed_exts = _ALL_MEDIA_EXTS

    moved_count = 0
    errors = []
    affected_dirs = set()

    for root, dirs, files in os.walk(base_dir):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d != '#recycle']
        for name in files:
            if name.startswith('.'):
                continue
            ext = os.path.splitext(name)[1].lower()
            if ext not in allowed_exts:
                continue

            full_path = os.path.join(root, name)
            try:
                st = os.stat(full_path)
                mtime = st.st_mtime
                dt = datetime.datetime.fromtimestamp(mtime)
                label = "%04d/%02d" % (dt.year, dt.month)

                rel_path = full_path[len(base_dir):]
                if not rel_path.startswith("/"):
                    rel_path = "/" + rel_path

                # Bo qua file da nam dung thu muc
                parent_dir = os.path.dirname(rel_path).strip("/")
                if parent_dir == label or parent_dir.endswith("/" + label):
                    continue

                target_dir = os.path.join(base_dir, label)
                os.makedirs(target_dir, exist_ok=True)
                affected_dirs.add(target_dir)

                new_path = os.path.join(target_dir, name)
                # Tranh trung ten
                if os.path.exists(new_path):
                    base_name, ex = os.path.splitext(name)
                    new_path = os.path.join(target_dir, "%s_%d%s" % (base_name, int(time.time()), ex))

                shutil.move(full_path, new_path)
                moved_count += 1
                if moved_count % 100 == 0:
                    log.info("[SmartOrganize] Đã di chuyển %d tệp...", moved_count)
            except Exception as e:
                errors.append(str(e))

    # Fix quyen ACL cho WebDAV (giong organize_legacy_videos)
    if moved_count > 0:
        for d in affected_dirs:
            try:
                _run_acl_copy(base_dir, d)
                subprocess.run(["chown", "-R", "daica:webdav-users", d])
                subprocess.run(["chmod", "-R", "2775", d])
            except Exception as e:
                log.warning("[SmartOrganize] Lỗi ACL: %s", e)

        _push_alert(
            "SMART_ORGANIZE",
            "Smart Organizer: Đã sắp xếp %d tệp vào thư mục theo Năm/Tháng." % moved_count,
            "SUCCESS"
        )

    log.info("[SmartOrganize] Hoàn tất: %d tệp đã di chuyển, %d lỗi.", moved_count, len(errors))
    return jsonify({
        "success": True,
        "moved_count": moved_count,
        "errors": errors[:20]  # Gioi han 20 loi dau tien
    })


# ============ INDEX ENGINE & HASH DELEGATION ============

_cached_webdav_root = None

def get_webdav_root():
    """Tu dong do tim duong dan thu muc goc cua WebDAV tren NAS (Có bộ đệm RAM giảm tải HDD)."""
    global _cached_webdav_root
    if _cached_webdav_root is not None:
        return _cached_webdav_root

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
                except Exception: pass
    except Exception: pass

    _cached_webdav_root = best_path.rstrip('/')
    return _cached_webdav_root

def generate_fast_index():
    """Single-pass os.walk: Thu thap + stream trong 1 lan duyet duy nhat.
    Dung buffer de ghi total chinh xac vao header JSON."""
    base_dir = get_webdav_root()
    media_exts = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".mp4", ".mkv", ".mov", ".avi"}
    base_len = len(base_dir)

    # Single-pass: Thu thap tat ca entries trong 1 lan walk
    entries = []
    for root, dirs, files in os.walk(base_dir):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d != '#recycle']
        for name in files:
            if name.startswith('.'): continue
            ext = os.path.splitext(name)[1].lower()
            if ext not in media_exts: continue
            full_path = os.path.join(root, name)
            try:
                st = os.stat(full_path)
                rel_path = full_path[base_len:]
                if not rel_path.startswith("/"): rel_path = "/" + rel_path
                entries.append((name, "/webdav" + rel_path, st.st_size, int(st.st_mtime * 1000)))
            except Exception: pass

    yield '{"total": %d, "files":[' % len(entries)
    for i, (name, webdav_path, size, mtime) in enumerate(entries):
        if i > 0: yield ','
        yield json.dumps({"name": name, "path": webdav_path, "size": size, "mtime": mtime})
    yield ']}'

@app.route("/api/disk/fast_index")
@requires_auth
def api_fast_index():
    """Quet thu muc toc do cao bang OS thuan, stream JSON generator de khong tran RAM."""
    return Response(generate_fast_index(), mimetype='application/json')

@app.route("/api/disk/hash_batch", methods=["POST"])
@requires_auth
def api_hash_batch():
    """Uy quyen NAS tinh Partial Hash (1MB dau tien) — Song song hoa de tang toc."""
    data = request.json
    if not data or "files" not in data:
        return jsonify({"error": "Yêu cầu không hợp lệ"}), 400
    
    result = {}
    base_dir = get_webdav_root()
    items = data.get("files", [])
    
    def _hash_one(item):
        url_path = item.get("path")
        local_path = item.get("local_path")
        if not url_path or not local_path: return None
        real_path = base_dir + local_path
        if not os.path.exists(real_path): return None
        try:
            with open(real_path, "rb") as f:
                chunk = f.read(1048576)
                return (url_path, hashlib.md5(chunk).hexdigest()) 
        except Exception: return None
    
    # Adaptive workers: doc dia IO-bound, 2-4 luong tuy tai nguyen
    try:
        mem = psutil.virtual_memory().percent
        workers = 2 if mem > 75 else (3 if mem > 60 else 4) 
    except Exception:
        workers = 2
    
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for r in pool.map(_hash_one, items):
            if r: result[r[0]] = r[1]
            
    return jsonify(result)

# ============ THUMBNAIL GENERATOR (Synology-style, TURBO MODE) ============
# 4 worker song song, smart frame (chon frame sang nhat), khong throttle CPU

THUMB_DIR_NAME = ".thumbs"
THUMB_MAX_SIZE = 320
THUMB_QUALITY = 80
MEDIA_IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".gif"}
MEDIA_VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".mpg", ".mpeg", ".wmv", ".flv", ".ts", ".m4v"}
MEDIA_ALL_EXTS = MEDIA_IMAGE_EXTS | MEDIA_VIDEO_EXTS

_thumb_stats = {"generated": 0, "total_media": 0, "running": False, "last_file": "", "errors": 0, "paused": False, "block_reasons": []}
_thumb_stats_lock = threading.Lock()
_thumb_paused = threading.Event()  # Set = dang chay, Clear = tam dung
_thumb_paused.set()  # Mac dinh: CHAY
_thumb_gate_lock = threading.Lock()
_thumb_manual_paused = False
_thumb_auto_block_reasons = set()

def _apply_thumbnail_gate_locked():
    """Ap dung trang thai pause/resume tu manual pause + cac tac vu nen nang."""
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

def _set_thumbnail_auto_block(reason, active):
    """Chan thumbnail khi livestream/ytdlp/sync dang chay; bo chan khi da xong."""
    reason = str(reason or "").strip()
    if not reason:
        return
    with _thumb_gate_lock:
        changed = False
        if active and reason not in _thumb_auto_block_reasons:
            _thumb_auto_block_reasons.add(reason)
            changed = True
        elif (not active) and reason in _thumb_auto_block_reasons:
            _thumb_auto_block_reasons.discard(reason)
            changed = True
        _apply_thumbnail_gate_locked()
    if changed:
        log.info("[Thumbnail] Gate %s: %s", "BLOCK" if active else "UNBLOCK", reason)

def _get_thumb_path(base_dir, file_path):
    rel = os.path.relpath(file_path, base_dir)
    # BẮT BUỘC: Khôi phục lại cấu trúc Hash cũ (ứng với thư mục .thumbs 3.3 GB gốc)
    # Vì file cũ được hash với chuỗi "New folder/..." do thư mục gốc trước đây là /srv/...
    hash_str = "New folder/" + rel if "New folder" in base_dir else rel
    safe_hash = hashlib.md5(hash_str.encode('utf-8')).hexdigest()
    return os.path.join(base_dir, THUMB_DIR_NAME, safe_hash + ".jpg")

def _generate_image_thumb(src_path, dst_path):
    try:
        from PIL import Image
        os.makedirs(os.path.dirname(dst_path), exist_ok=True)
        img = Image.open(src_path)
        img.thumbnail((THUMB_MAX_SIZE, THUMB_MAX_SIZE), Image.LANCZOS)
        if img.mode in ('RGBA', 'P', 'LA'):
            img = img.convert('RGB')
        img.save(dst_path, 'JPEG', quality=THUMB_QUALITY)
        return True
    except Exception:
        # File corrupt hoac khong phai anh that -> tao placeholder
        try:
            _create_placeholder_thumb(dst_path)
            return True
        except Exception:
            return False

def _frame_brightness(jpg_path):
    """Do sang trung binh cua JPEG (0-255). Frame den = 0-15."""
    try:
        from PIL import Image
        img = Image.open(jpg_path).convert('L')
        pixels = list(img.getdata())
        return sum(pixels) / max(len(pixels), 1)
    except Exception:
        return 0

# BO KHOA BAO VE RAM: Toi da 2 luong FFmpeg
import threading
_ffmpeg_semaphore = threading.Semaphore(1)  # REVERT: 2 -> 1 de tranh I/O burst lam SATA timeout

def _generate_video_thumb(src_path, dst_path):
    """FIX: chien luoc seek nhieu nac de tang ti le thumbnail thanh cong.

    Loi cu:
    - Seek 00:00:03 -> video < 3s thi ffmpeg fail im lang
    - Timeout 10s -> video lon hoac codec phuc tap (H.265/HEVC/AV1 tren rk3328
      ARM 1-2GB RAM khong co hwacc) thi ffmpeg bi kill truoc khi extract frame
    - Khong probe duration -> khong biet co the seek bao nhieu
    - Fallback -ss 0 sau khi seek 3s loi: kernel da cache file -> 2nd run nhanh hon
      nhung van loi neu codec khong decode duoc bang ffmpeg 3.2
    - Tao placeholder + return True che dau loi -> client khong biet re-request

    Logic moi:
    1. Probe duration nhanh (ffprobe 3s) de chon seek time hop ly.
    2. Seek tai 10% duration (max 3s, min 0.5s) — tranh frame den dau video.
    3. Timeout dong theo size: 15s cho file <100MB, 25s cho file <1GB, 40s cho >1GB.
    4. Fallback seek 0 neu seek 10% loi (file hong header time index).
    5. Chi tao placeholder khi MOI nhanh deu fail. Return False de retry sau (chu
       khong return True che dau).
    """
    try:
        import os, subprocess
        os.makedirs(os.path.dirname(dst_path), exist_ok=True)

        with _ffmpeg_semaphore:
            # Buoc 1: probe duration nhanh
            duration = 0.0
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

            # Buoc 2: chon seek time hop ly
            if duration > 0:
                # 10% duration, cap 0.5s..3s. Video 2s -> seek 0.2s? Khong, min 0s.
                seek_s = max(0.5, min(3.0, duration * 0.10)) if duration >= 1.0 else 0.0
            else:
                # Khong probe duoc -> thu 3s nhu cu (will fallback to 0 if fail)
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
                try:
                    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=ffmpeg_timeout)
                except subprocess.TimeoutExpired:
                    log.warning("[Thumb] ffmpeg timeout (%ds) ss=%s: %s", ffmpeg_timeout, ss_arg, os.path.basename(src_path))
                except Exception as e:
                    log.warning("[Thumb] ffmpeg loi ss=%s: %s — %s", ss_arg, os.path.basename(src_path), e)

            # Try 1: seek tinh toan
            _run_ffmpeg(seek_s)
            if os.path.exists(dst_path) and os.path.getsize(dst_path) > 100:
                return True

            # Try 2: seek 0 (frame dau tien — co the den den cho video TikTok co intro den)
            if seek_s > 0:
                _run_ffmpeg(0)
                if os.path.exists(dst_path) and os.path.getsize(dst_path) > 100:
                    return True

            # Try 3: seek giua video (50%) — cuu canh khi frame dau bi hong
            if duration > 2.0:
                _run_ffmpeg(duration / 2.0)
                if os.path.exists(dst_path) and os.path.getsize(dst_path) > 100:
                    return True
    except Exception as e:
        log.warning("[Thumb] unexpected error %s: %s", os.path.basename(src_path), e)

    # Het cach -> tao placeholder de UI khong trong tron, nhung tra ve False
    # de _thumb_stats track that bai va co the retry o vong sau.
    try:
        _create_placeholder_thumb(dst_path)
    except Exception:
        pass
    return False

def _create_placeholder_thumb(dst_path):
    """Tao anh placeholder nho cho video khong decode duoc (AV1, VP9...)."""
    try:
        from PIL import Image, ImageDraw
        img = Image.new('RGB', (THUMB_MAX_SIZE, int(THUMB_MAX_SIZE * 9 / 16)), (45, 45, 48))
        draw = ImageDraw.Draw(img)
        # Ve icon play tam gia
        cx, cy = THUMB_MAX_SIZE // 2, int(THUMB_MAX_SIZE * 9 / 32)
        s = 30
        draw.polygon([(cx - s, cy - s), (cx - s, cy + s), (cx + s, cy)], fill=(180, 180, 180))
        img.save(dst_path, 'JPEG', quality=60) 
    except Exception:
        # Fallback: tao 1x1 pixel JPEG
        from PIL import Image
        img = Image.new('RGB', (1, 1), (45, 45, 48))
        img.save(dst_path, 'JPEG')

def _process_one_thumb(args):
    full_path, thumb_path, ext = args
    try:
        if ext in MEDIA_IMAGE_EXTS:
            return _generate_image_thumb(full_path, thumb_path)
        elif ext in MEDIA_VIDEO_EXTS:
            return _generate_video_thumb(full_path, thumb_path)
    except Exception:
        pass
    return False

def _thumbnail_generator():
    """Background daemon: XU LY AN TOAN - TUAN TU, kiem tra CPU/RAM truoc moi file.
    Tranh lam sap NAS ARM yeu (rk3328, 1-2GB RAM)."""
    global _thumb_stats
    
    time.sleep(30)  # Cho server va o cung khoi dong on dinh
    
    while True:
        try:
            if not _thumb_paused.is_set():
                with _thumb_stats_lock:
                    _thumb_stats["running"] = False
                    _thumb_stats["paused"] = True
                _thumb_paused.wait()
            base_dir = get_webdav_root()
            thumb_dir = os.path.join(base_dir, THUMB_DIR_NAME)
            os.makedirs(thumb_dir, exist_ok=True)
            
            with _thumb_stats_lock:
                _thumb_stats["running"] = True
                _thumb_stats["errors"] = 0
            
            # PASS 1: Thu thap file can xu ly (gioi han 500 file moi lan quet)
            pending = []
            total = 0
            already_done = 0
            MAX_BATCH = 5000  # Batch lon hon vi cpu/ram con nhieu
            
            for root, dirs, files in os.walk(base_dir):
                dirs[:] = [d for d in dirs if not d.startswith('.') and d != THUMB_DIR_NAME and d != '#recycle']
                for name in files:
                    if name.startswith('.'): continue
                    ext = os.path.splitext(name)[1].lower()
                    if ext not in MEDIA_ALL_EXTS: continue
                    total += 1
                    full_path = os.path.join(root, name)
                    thumb_path = _get_thumb_path(base_dir, full_path)
                    if os.path.exists(thumb_path) and os.path.getsize(thumb_path) > 0:
                        # REVERT: KHONG retry placeholder nua. Threshold 2200 truoc
                        # day khien daemon kick ffmpeg cho 3400+ file moi vong quet
                        # -> I/O burst lien tuc -> SATA timeout -> corrupt FS.
                        # Logic seek thong minh trong _generate_video_thumb VAN giu
                        # cho file MOI; nhung khong dung de spam retry file cu.
                        # Khi nao disk on dinh thi user co the xoa .thumbs/ thu cong
                        # de retry toan bo.
                        already_done += 1
                        continue
                    if len(pending) < MAX_BATCH:
                        pending.append((full_path, thumb_path, ext))
            
            with _thumb_stats_lock:
                _thumb_stats["total_media"] = total
                _thumb_stats["generated"] = already_done
                _thumb_stats["_base_done"] = already_done
                _thumb_stats["start_time"] = time.time()
                
            # SMART SLEEP (Ngu dong): Chi chay neu co Media moi, hoac CPU ranh, hoac 3:00 AM
            if len(pending) == 0:
                import datetime
                with _thumb_stats_lock:
                    _thumb_stats["running"] = False
                    _thumb_stats["last_file"] = "Ngủ đông: Chờ 3:00 AM hoặc Rảnh"
                    
                while True:
                    time.sleep(60)
                    if not _thumb_paused.is_set():
                        _thumb_paused.wait()
                    now = datetime.datetime.now()
                    
                    # 1. Hẹn giờ ban đêm: Bắt buộc quét toàn bộ rác định kỳ lúc 3:00 - 3:05 Sáng
                    if now.hour == 3 and now.minute < 5:
                        break
                        
                    # 2. Xử lý tải nhẹ (Rảnh): Mỗi 30 phút một lần, nếu NAS cực rảnh -> Thức dậy làm bù
                    # Điều kiện CPU < 15.0 giúp giảm nguy cơ giật lác hệ thống
                    if now.minute % 30 == 0:
                        cpu = psutil.cpu_percent(interval=1)
                        if cpu < 15.0:
                            break
                            
                continue # Pha vỡ Ngủ Đông, chạy Pass 1 lại từ đầu
            
            # PASS 2: ADAPTIVE TURBO — Toi uu toc do toi da cho Chainedbox L1 Pro (RK3328 quad-core, 2GB RAM)
            # Chien luoc: Song song khi ranh, tuan tu khi ban, dung khi nguy hiem
            _counters = {"generated": already_done, "errors": 0, "batch": 0}
            _counter_lock = threading.Lock()
            abort_batch = False
            
            # Tach rieng anh (nhe, chay song song PIL) va video (nang, gioi han FFmpeg)
            image_pending = [p for p in pending if p[2] in MEDIA_IMAGE_EXTS]
            video_pending = [p for p in pending if p[2] in MEDIA_VIDEO_EXTS]
            
            def _adaptive_workers():
                """Tinh so luong worker toi uu dua tren tai nguyen thuc te."""
                try:
                    cpu = psutil.cpu_percent(interval=0.3)
                    mem = psutil.virtual_memory().percent
                except Exception:
                    return 1
                if mem > 82 or cpu > 85:
                    return 1  # An toan: tuan tu
                elif mem > 70 or cpu > 65:
                    return 2  # Trung binh: 2 luong
                else:
                    return 3  # Ranh: 3 luong (de lai 1 core cho OS + API server)
            
            def _check_resources_and_throttle():
                """Kiem tra tai nguyen, tra ve True neu can dung khan cap."""
                nonlocal abort_batch
                try:
                    mem = psutil.virtual_memory()
                    if mem.percent > 88:
                        with _thumb_stats_lock:
                            _thumb_stats["last_file"] = "Dừng khẩn cấp: RAM %d%%" % int(mem.percent)
                        abort_batch = True
                        return True
                    if mem.percent > 80:
                        time.sleep(3)  # Giam toc de RAM giai phong
                    elif mem.percent > 70:
                        time.sleep(0.5)
                    else:
                        time.sleep(0.05)  # TOI UU I/O CAO CHO ST4000VX (Hoãn 50ms chống quá tải cơ học đĩa cứng)
                except Exception:
                    pass
                return False
            
            def _process_with_pause_gate(item):
                """Xu ly 1 file voi ho tro pause/resume. Thread-safe qua _counter_lock."""
                nonlocal abort_batch
                if abort_batch:
                    return
                # === PAUSE/RESUME GATE ===
                if not _thumb_paused.is_set():
                    with _thumb_stats_lock:
                        _thumb_stats["paused"] = True
                        _thumb_stats["running"] = False
                        _thumb_stats["last_file"] = "Tạm dừng bởi người dùng"
                    _thumb_paused.wait()
                    with _thumb_stats_lock:
                        _thumb_stats["paused"] = False
                        _thumb_stats["running"] = True
                        _thumb_stats["start_time"] = time.time() - (_counters["generated"] - already_done) * 0.5
                
                full_path, thumb_path, ext = item
                name = os.path.basename(full_path)
                
                with _thumb_stats_lock:
                    _thumb_stats["last_file"] = name
                
                try:
                    success = _process_one_thumb(item)
                    ok = bool(success)
                except Exception:
                    ok = False
                
                with _counter_lock:
                    if ok:
                        _counters["generated"] += 1
                    else:
                        _counters["errors"] += 1
                    _counters["batch"] += 1
                    bc = _counters["batch"]
                
                # Cap nhat stats THOI GIAN THUC moi file (lock contention nhe vi critical section nho)
                with _thumb_stats_lock:
                    _thumb_stats["generated"] = _counters["generated"]
                    _thumb_stats["errors"] = _counters["errors"]
                # Kiem tra tai nguyen moi 20 file
                if bc % 20 == 0:
                    _check_resources_and_throttle()
            
            # === XU LY ANH: Song song voi ThreadPoolExecutor ===
            if image_pending and not abort_batch:
                from concurrent.futures import ThreadPoolExecutor, as_completed
                workers = _adaptive_workers()
                with _thumb_stats_lock:
                    _thumb_stats["last_file"] = "Ảnh: %d tệp, %d luồng" % (len(image_pending), workers)
                
                # Chia thanh cac micro-batch (100 file) de re-evaluate workers giua chung
                for chunk_start in range(0, len(image_pending), 100):
                    if abort_batch:
                        break
                    chunk = image_pending[chunk_start:chunk_start + 100]
                    workers = _adaptive_workers()  # Re-evaluate moi 100 file
                    
                    with ThreadPoolExecutor(max_workers=workers) as pool:
                        futures = [pool.submit(_process_with_pause_gate, item) for item in chunk]
                        for f in as_completed(futures):
                            try:
                                f.result()
                            except Exception:
                                pass
                            if abort_batch:
                                break
            
            # === XU LY VIDEO: Tuan tu (FFmpeg nang, da co _ffmpeg_semaphore gioi han 2) ===
            if video_pending and not abort_batch:
                with _thumb_stats_lock:
                    _thumb_stats["last_file"] = "Video: %d tệp (tuần tự)" % len(video_pending)
                for item in video_pending:
                    if abort_batch:
                        break
                    _process_with_pause_gate(item)
            
            generated = _counters["generated"]
            errors = _counters["errors"]
            
            with _thumb_stats_lock:
                _thumb_stats["generated"] = generated
                _thumb_stats["total_media"] = total
                _thumb_stats["running"] = False
                _thumb_stats["last_file"] = "Hoàn tất! %d/%d (lỗi: %d)" % (generated, total, errors)
            
            try:
                conn = sqlite3.connect(DB_PATH, timeout=20.0)
                cur = conn.cursor()
                cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                    ("INFO", "Thumbnail", "Đã tạo %d/%d ảnh thu nhỏ (lỗi: %d)." % (generated, total, errors)))
                conn.commit()
                conn.close()
            except Exception: pass
            
        except Exception as e:
            with _thumb_stats_lock:
                _thumb_stats["running"] = False
                _thumb_stats["last_file"] = "Lỗi: %s" % str(e)
        
        time.sleep(30)  # Quet lai sau 30 giay


@app.route("/api/thumb")
@requires_auth
def api_thumb():
    """Tra ve thumbnail. On-demand neu chua co."""
    import urllib.parse
    webdav_path = request.args.get("path", "")
    # HOTFIX: Android Kotlin `java.net.URL.path` pass raw %20, and `URLEncoder` double encodes to %2520.
    # Flask auto-decodes once back to %20. We must unquote a second time to resolve pure UTF-8 Ext4 strings.
    webdav_path = urllib.parse.unquote(webdav_path)
    
    if not webdav_path:
        return jsonify({"error": "Thiếu đường dẫn"}), 400
    
    base_dir = get_webdav_root()
    local_rel = webdav_path.replace("/webdav", "", 1)
    real_path = base_dir + local_rel
    
    if not os.path.exists(real_path):
        return jsonify({"error": "Tệp không tồn tại"}), 404
    
    thumb_path = _get_thumb_path(base_dir, real_path)
    
    if not os.path.exists(thumb_path) or os.path.getsize(thumb_path) == 0:
        thumb_dir = os.path.join(base_dir, THUMB_DIR_NAME)
        os.makedirs(thumb_dir, exist_ok=True)
        ext = os.path.splitext(real_path)[1].lower()
        if ext in MEDIA_IMAGE_EXTS:
            _generate_image_thumb(real_path, thumb_path)
        elif ext in MEDIA_VIDEO_EXTS:
            _generate_video_thumb(real_path, thumb_path)
        else:
            return jsonify({"error": "Không hỗ trợ"}), 415
    
    if os.path.exists(thumb_path) and os.path.getsize(thumb_path) > 0:
        return Response(
            open(thumb_path, 'rb').read(),
            mimetype='image/jpeg',
            headers={'Cache-Control': 'public, max-age=86400'}
        )
    return jsonify({"error": "Không tạo được"}), 500


@app.route("/api/thumb/status")
@requires_auth
def api_thumb_status():
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
        
        # Format thoi gian thanh "hh:mm"
        el = data.get("elapsed_seconds", 0)
        data["elapsed_fmt"] = "%02d:%02d" % (el // 3600, (el % 3600) // 60)
        et = data.get("eta_seconds", -1)
        data["eta_fmt"] = "%02d:%02d" % (et // 3600, (et % 3600) // 60) if et > 0 else "--:--"
        
        return jsonify(data)

@app.route("/api/thumb/control", methods=["POST"])
@requires_auth
def api_thumb_control():
    """Dieu khien Thumbnail Generator: pause / resume.
    Body: {"action": "pause"} hoac {"action": "resume"}"""
    data = request.get_json(force=True) or {}
    action = data.get("action", "").strip().lower()

    global _thumb_manual_paused
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
    else:
        return jsonify({"error": "Hành động phải là 'pause' hoặc 'resume'"}), 400

@app.route("/api/thumb/activity", methods=["POST"])
@requires_auth
def api_thumb_activity():
    """App/worker bao cho NAS biet tac vu nang dang chay de tam dung thumbnail.
    Body: {"source": "sync", "active": true|false}"""
    body = request.get_json(force=True, silent=True) or {}
    source = _re_module.sub(r"[^a-zA-Z0-9_.-]+", "_", str(body.get("source", "sync"))).strip("_") or "sync"
    active = bool(body.get("active", False))
    if len(source) > 48:
        source = source[:48]
    _set_thumbnail_auto_block(source, active)
    return jsonify({
        "result": "ok",
        "paused": not _thumb_paused.is_set(),
        "block_reasons": sorted(_thumb_auto_block_reasons)
    })

# ============ DOCKER POWER CONTROL ============

@app.route("/api/docker/power", methods=["GET"])
@requires_auth
def api_docker_power_get():
    """Kiem tra Docker dang chay hay khong."""
    try:
        r = subprocess.run(["systemctl", "is-active", "docker"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        running = r.stdout.decode().strip() == "active"
        return jsonify({"running": running})
    except Exception as e:
        return jsonify({"running": False, "error": str(e)})

@app.route("/api/docker/power", methods=["POST"])
@requires_auth
def api_docker_power_post():
    """Bat/tat Docker service. Body: {"action": "start"} hoac {"action": "stop"}"""
    data = request.json or {}
    action = data.get("action", "").strip().lower()
    
    if action == "start":
        subprocess.run(["systemctl", "start", "containerd"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        subprocess.run(["systemctl", "start", "docker"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        time.sleep(2)
        # Tu dong start tat ca container da co (an toan: 2 buoc thay vi shell expansion)
        _all_ids = run_cmd(["docker", "ps", "-aq"])
        if _all_ids:
            subprocess.run(["docker", "start"] + _all_ids.split(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        try:
            conn = sqlite3.connect(DB_PATH, timeout=20.0)
            conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                ("INFO", "Docker", "Đã bật Docker + qBittorrent"))
            conn.commit()
            conn.close()
        except Exception: pass
        return jsonify({"result": "ok", "action": "started"})
    elif action == "stop":
        _running_ids = run_cmd(["docker", "ps", "-q"])
        if _running_ids:
            subprocess.run(["docker", "stop"] + _running_ids.split(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        subprocess.run(["systemctl", "stop", "docker"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        subprocess.run(["systemctl", "stop", "containerd"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        try:
            conn = sqlite3.connect(DB_PATH, timeout=20.0)
            conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                ("INFO", "Docker", "Đã tắt Docker để tiết kiệm RAM"))
            conn.commit()
            conn.close()
        except Exception: pass
        return jsonify({"result": "ok", "action": "stopped"})
    else:
        return jsonify({"error": "Hành động phải là 'start' hoặc 'stop'"}), 400


# ============ LAN WHITELIST API ============

@app.route("/api/lan/whitelist", methods=["GET"])
@requires_auth
def api_lan_whitelist_get():
    """Lay danh sach IP/subnet trong LAN whitelist."""
    return jsonify({
        "ips": sorted(list(_lan_whitelist)),
        "subnets": sorted(_lan_subnets)
    })

@app.route("/api/lan/whitelist", methods=["POST"])
@requires_auth
def api_lan_whitelist_add():
    """Them IP hoac subnet vao LAN whitelist + tu dong mo iptables.
    Body: {"ip": "192.168.1.100"} hoac {"subnet": "192.168.1.0/24"}"""
    data = request.json or {}
    ip = data.get("ip", "").strip()
    subnet = data.get("subnet", "").strip()
    
    if subnet:
        if subnet not in _lan_subnets:
            _lan_subnets.append(subnet)
            _save_lan_whitelist()
        # Ap dung iptables ACCEPT ngay lap tuc cho subnet
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', subnet, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', subnet, '-j', 'ACCEPT'])
        except Exception: pass
        return jsonify({"result": "ok", "added_subnet": subnet})
    elif ip:
        _lan_whitelist.add(ip)
        _save_lan_whitelist()
        # Ap dung iptables ACCEPT ngay lap tuc cho IP
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
            subprocess.run(['iptables', '-I', 'INPUT', '1', '-s', ip, '-j', 'ACCEPT'])
        except Exception: pass
        return jsonify({"result": "ok", "added_ip": ip})
    else:
        return jsonify({"error": "Thiếu IP hoặc subnet"}), 400

@app.route("/api/lan/whitelist", methods=["DELETE"])
@requires_auth
def api_lan_whitelist_remove():
    """Xoa IP hoac subnet khoi LAN whitelist + go iptables rule tuong ung.
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
        except Exception: pass
        return jsonify({"result": "ok", "removed_subnet": subnet})
    elif ip and ip in _lan_whitelist:
        _lan_whitelist.discard(ip)
        _save_lan_whitelist()
        # Go iptables rule cua IP
        try:
            subprocess.run(['iptables', '-D', 'INPUT', '-s', ip, '-j', 'ACCEPT'], stderr=subprocess.DEVNULL)
        except Exception: pass
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
                conn = sqlite3.connect(DB_PATH, timeout=20.0)
                conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                    ("CRITICAL", "Hardware", "Tự động reboot NAS do: %s" % reason))
                conn.commit()
                conn.close()
            except Exception: pass
            
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

    # THIẾT LẬP TỐI ƯU CƠ HỌC CHO Ổ SEAGATE SKYHAWK ST4000VX (SURVEILLANCE): 
    # CẤM APM VÀ CẤM STANDBY CHỐNG HAO MÒN KHỞI ĐỘNG MOTOR (SPIN-DOWN)
    try:
        run_cmd(["sudo", "hdparm", "-B", "254", "-S", "0", "/dev/sda"], merge_stderr=True)
    except Exception:
        pass

    while True:
        try:
            # 1. Kiem tra tailscaled process
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
                        conn = sqlite3.connect(DB_PATH, timeout=20.0)
                        conn.execute("INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)",
                            ("WARNING", "Network", "Tailscale bị ngắt, hệ thống RK3328 đã tự động khởi động lại lần %d" % _tailscale_restart_count))
                        conn.commit()
                        conn.close()
                    except Exception: pass
            
            # 2. Kiem tra nginx process (Dam bao WebDAV an toan, khong bi OMV chet tren boot)
            nginx_res = subprocess.run(["systemctl", "is-active", "nginx"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            if nginx_res.stdout.decode().strip() != "active":
                log.warning("[Watchdog] Nginx (WebDAV) đã tắt hoặc lỗi, đang khởi động lại...")
                subprocess.run(["systemctl", "start", "nginx"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                
            global _system_alert
            # 3. Kiem tra HDD (Mount point co ban)
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
                
            # 4. Kiem tra LAN IP (eth0)
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
            
        time.sleep(10)
            
# End Watchdog


@app.route("/api/tailscale/status", methods=["GET"])
@requires_auth
def api_tailscale_status():
    """Tra ve trang thai Tailscale: IP, status, so lan restart."""
    try:
        # Kiem tra process
        proc = subprocess.run(["pgrep", "-x", "tailscaled"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        is_running = proc.returncode == 0
        
        # Lay IP Tailscale
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
        
        # Lay trang thai ket noi
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
#  2. Cu hinh vsftpd cho phep user nay: Read-Only vao thu muc Media/
#  3. Dat timeout: cron-like background thread se xoa user sau so phut da dat
#
# CANH BAO: Can chay voi quyen root (hoac sudo) de tao user he thong.

import random
import string

# Luu trang thai Guest Pass (dang hoat dong)
_guest_passes = {}  # {username: {"password": ..., "expires_at": epoch}}
_guest_lock = threading.Lock()

VSFTPD_USER_DIR = "/etc/vsftpd/userconf"   # Thu muc cau hinh per-user vsftpd
GUEST_FTP_ROOT  = "/srv/dev-disk-by-label-data"  # Thu muc FTP se thay the qua chrootdir

def _generate_guest_name():
    suffix = ''.join(random.choice(string.ascii_lowercase + string.digits) for _ in range(6))
    return "nasguest_%s" % suffix

def _generate_guest_password(length=10):
    chars = string.ascii_letters + string.digits
    return ''.join(random.choice(chars) for _ in range(length))

def _create_linux_user(username, password):
    """Tao user Linux khoa SSH, pha shell /sbin/nologin."""
    try:
        # Tao user he thong (khong home, khong login)
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
    try:
        os.makedirs(VSFTPD_USER_DIR, exist_ok=True)
        config_path = os.path.join(VSFTPD_USER_DIR, username)
        with open(config_path, 'w') as f:
            f.write("local_root=%s\n" % GUEST_FTP_ROOT)
            f.write("write_enable=NO\n")
            f.write("anon_world_readable_only=YES\n")
            # FIX SANDBOX: Nhot user vao GUEST_FTP_ROOT, ngan doc thu muc cha (VD: /etc, /root)
            f.write("chroot_local_user=YES\n")
            f.write("allow_writeable_chroot=YES\n")
        return True
    except Exception as e:
        log.error("Lỗi tạo cấu hình vsftpd cho %s: %s", username, e)
        return False

def _delete_linux_user(username):
    """Xoa user Linux va file cau hinh vsftpd."""
    try:
        subprocess.run(["userdel", username], stderr=subprocess.DEVNULL)
        config_path = os.path.join(VSFTPD_USER_DIR, username)
        if os.path.exists(config_path):
            os.remove(config_path)
    except Exception as e:
        log.error("Lỗi xóa user %s: %s", username, e)

def _guest_expiry_watcher():
    """Background thread: quet va xoa Guest Pass da het han (moi 30 giay)."""
    while True:
        try:
            now = time.time()
            with _guest_lock:
                expired = [u for u, v in _guest_passes.items() if v["expires_at"] <= now]
            for username in expired:
                _delete_linux_user(username)
                with _guest_lock:
                    _guest_passes.pop(username, None)
                log.info("[GuestPass] Đã thu hồi user hết hạn: %s", username)
        except Exception as e:
            log.error("[GuestPass] Lỗi watcher: %s", e)
        time.sleep(30)

# Background thread quan ly het han Guest Pass
threading.Thread(target=_guest_expiry_watcher, daemon=True).start()

@app.route("/api/guest/create", methods=["POST"])
@requires_auth
def api_guest_create():
    """Tao FTP user tam thoi (Read-Only) voi thoi gian ton tai gioi han."""
    try:
        body = request.get_json(force=True) or {}
        duration_minutes = int(body.get("duration_minutes", 60))
        duration_minutes = max(5, min(duration_minutes, 1440))  # 5ph - 24h

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

        # Lay IP LAN cua NAS (vi Android can dia chi FTP)
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
@requires_auth
def api_guest_revoke():
    """Thu hoi (xoa) FTP user tam thoi ngay lap tuc."""
    try:
        body = request.get_json(force=True) or {}
        username = body.get("username", "")
        if not username or not username.startswith("nasguest_"):
            return jsonify({"error": "Username không hợp lệ"}), 400

        _delete_linux_user(username)
        with _guest_lock:
            _guest_passes.pop(username, None)

        return jsonify({"message": "Đã thu hồi Guest FTP user '%s' thành công." % username})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ============ LIVESTREAM RECORDER (TikTok / Facebook / YouTube Live) ============
# Ghi hinh livestream theo thoi gian thuc xuong HDD.
# Su dung yt-dlp voi --live-from-start de capture HLS stream.
# CPU chi ~2-5% (chi copy segment, KHONG re-encode).
#
# Endpoint:
#   POST /api/livestream/record   { url, quality }
#   GET  /api/livestream/status
#   POST /api/livestream/stop     { job_id }

_livestream_jobs = {}  # {job_id: {url, platform, pid, output_file, started_at, status}}
_livestream_lock = threading.Lock()
_LIVESTREAM_DIR = os.path.join(WEBDAV_FILE_ROOT, "Livestream")
_LIVESTREAM_MAX_HOURS = 12  # Timeout tu dong sau 12 gio

def _detect_platform(url):
    """Nhan dien nen tang tu URL."""
    url_lower = url.lower()
    if "tiktok.com" in url_lower:
        return "tiktok"
    elif "facebook.com" in url_lower or "fb.watch" in url_lower:
        return "facebook"
    elif "youtube.com" in url_lower or "youtu.be" in url_lower:
        return "youtube"
    elif "shopee" in url_lower:
        return "shopee"
    return "other"

def _find_ytdlp_bin():
    """Tim yt-dlp binary tren he thong."""
    for candidate in ["/usr/local/bin/yt-dlp", "/usr/bin/yt-dlp", "yt-dlp", 
                       "/opt/yt-dlp", "/root/yt-dlp", "/usr/local/bin/yt-dlp_linux_aarch64"]:
        try:
            result = subprocess.run([candidate, "--version"],
                                     stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE)
            if result.returncode == 0:
                return candidate
        except Exception:
            continue
    return None

def _direct_flv_has_remuxable_video(flv_url, cookies_path="", user_agent=""):
    """Kiem tra nhanh ffmpeg tren NAS co nhan duoc codec video cua FLV CDN khong."""
    flv_url_lower = (flv_url or "").lower()
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
    # TikTok CDN URLs da co token xac thuc trong query string. Gui lai cookie
    # web TikTok vao CDN co the lam probe tra ket qua sai, roi fallback ve
    # yt-dlp va bao nham "not currently live".
    if "tiktokcdn" in flv_url_lower:
        cmd.extend(["-headers", "Referer: https://www.tiktok.com/\r\n"])
    elif cookies_path and os.path.exists(cookies_path):
        try:
            cookie_header = ""
            with open(cookies_path, "r") as f:
                pairs = []
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#"):
                        continue
                    cols = line.split("\t")
                    if len(cols) >= 7:
                        pairs.append("%s=%s" % (cols[5], cols[6]))
                cookie_header = "; ".join(pairs)
            if cookie_header:
                cmd.extend(["-headers", "Cookie: %s\r\nReferer: https://www.tiktok.com/\r\n" % cookie_header])
        except Exception:
            pass
    else:
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

def _remux_flv_to_mp4(flv_path):
    """Remux file FLV thanh MP4 bang ffmpeg -c copy (khong re-encode, ~0% CPU).
    Tra ve duong dan file MP4 neu thanh cong, hoac chuoi rong neu that bai."""
    if not flv_path or not os.path.exists(flv_path):
        return ""
    mp4_path = os.path.splitext(flv_path)[0] + ".mp4"
    try:
        # -c copy: chi doi container, khong giai ma -> CPU cuc thap.
        # -movflags +faststart: dat moov atom o dau file, cho phep stream/seek nhanh.
        # -fflags +genpts: regen PTS de tranh loi "non-monotonic DTS".
        proc = subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            "-fflags", "+genpts",
            "-i", flv_path,
            "-c", "copy",
            "-movflags", "+faststart",
            "-bsf:a", "aac_adtstoasc",
            mp4_path,
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        if proc.returncode == 0 and os.path.exists(mp4_path) and os.path.getsize(mp4_path) > 1024:
            try:
                os.remove(flv_path)
            except Exception:
                pass
            return mp4_path
        # Fallback: thu lai khong dung aac_adtstoasc (mot so FLV co audio non-AAC)
        proc2 = subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            "-fflags", "+genpts",
            "-i", flv_path,
            "-c", "copy",
            "-movflags", "+faststart",
            mp4_path,
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        if proc2.returncode == 0 and os.path.exists(mp4_path) and os.path.getsize(mp4_path) > 1024:
            try:
                os.remove(flv_path)
            except Exception:
                pass
            return mp4_path
        # FIX: Pass 3 — them h264_mp4toannexb video BSF. Mot so FLV/H264 thieu
        # NAL annexB delimiter -> mp4 muxer reject. BSF nay them lai delimiter.
        proc3 = subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            "-fflags", "+genpts",
            "-i", flv_path,
            "-c", "copy",
            "-bsf:v", "h264_mp4toannexb",
            "-movflags", "+faststart",
            mp4_path,
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
        if proc3.returncode == 0 and os.path.exists(mp4_path) and os.path.getsize(mp4_path) > 1024:
            try:
                os.remove(flv_path)
            except Exception:
                pass
            return mp4_path
        # Het cach: log day du stderr + rename file .flv -> .broken.flv de user
        # biet file da bi hong/khong play duoc, KHONG xoa (de debug hoac thu
        # mo bang VLC tay).
        err_tail = (proc3.stderr or proc2.stderr or b"")[-240:]
        log.warning("[Livestream] Remux FLV sang MP4 thất bại sau cả 3 lượt thử (rc=%d|%d|%d): %s",
                    proc.returncode, proc2.returncode, proc3.returncode, err_tail)
        try:
            broken_path = flv_path + ".broken"  # vd: foo.flv.broken
            if os.path.exists(broken_path):
                broken_path = broken_path + "." + str(int(time.time()))
            os.rename(flv_path, broken_path)
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
    Cung cap nhat thumbnail gate khi livestream/ytdlp khong con chay."""
    while True:
        try:
            time.sleep(60)  # Kiem tra moi phut
            ytdlp_active = False
            _cleanup_stale_job_tmp(max_age_hours=24)
            _cleanup_runtime_tmp_artifacts(max_age_minutes=30)
            with _livestream_lock:
                for jid, info in list(_livestream_jobs.items()):
                    pid = info.get("pid")
                    # Kiem tra process con song khong
                    is_running = False
                    try:
                        os.kill(pid, 0)
                        is_running = True
                    except Exception:
                        pass

                    if not is_running:
                        # Process da ket thuc tu nhien (stream het hoac loi)
                        try:
                            out_pattern = info.get("output_dir", "")
                            timestamp_str = info.get("timestamp_str", "")
                            # Tim dung file cua job nay. Khong lay file moi nhat toan thu muc,
                            # vi job fail/offline se bi gan nham MP4 cu va bao sai trang thai.
                            if os.path.isdir(out_pattern):
                                files = sorted(
                                    [os.path.join(out_pattern, f) for f in os.listdir(out_pattern)
                                     if os.path.isfile(os.path.join(out_pattern, f))
                                     and (not timestamp_str or timestamp_str in f)],
                                    key=os.path.getmtime, reverse=True
                                )
                                if files:
                                    info["output_file"] = os.path.basename(files[0])
                                    info["file_size"] = os.path.getsize(files[0])
                                    info["_latest_output_path"] = files[0]
                        except Exception:
                            pass

                        # Bat buoc output livestream la MP4. Neu yt-dlp/downloader
                        # con de lai FLV thi remux ngay; fail thi job fail, khong
                        # bao thanh cong voi file .flv khong mo duoc.
                        flv_path = ""
                        try:
                            latest_path = info.get("_latest_output_path", "")
                            direct_path = info.get("direct_output_path", "")
                            if direct_path and direct_path.lower().endswith(".flv") and os.path.exists(direct_path):
                                flv_path = direct_path
                            elif latest_path and latest_path.lower().endswith(".flv") and os.path.exists(latest_path):
                                flv_path = latest_path
                            if flv_path and os.path.getsize(flv_path) > 1024:
                                mp4_path = _remux_flv_to_mp4(flv_path)
                                if mp4_path:
                                    info["output_file"] = os.path.basename(mp4_path)
                                    info["file_size"] = os.path.getsize(mp4_path)
                                    info["direct_output_path"] = mp4_path
                                    log.info("[Livestream] Job %s: remux FLV -> MP4 OK (%s)", jid, os.path.basename(mp4_path))
                                else:
                                    info["output_file"] = os.path.basename(flv_path) + ".broken"
                                    info["file_size"] = 0
                        except Exception as e:
                            if flv_path:
                                info["output_file"] = os.path.basename(flv_path) + ".broken"
                                info["file_size"] = 0
                            log.warning("[Livestream] Job %s: remux thất bại: %s", jid, e)

                        # Kiem tra dung luong file de xac dinh thanh cong hay that bai
                        if info.get("file_size", 0) < 1000:
                            info["status"] = "error"
                            info["error_reason"] = _livestream_error_from_log(info) or "Không tạo được tệp video hợp lệ."
                            log.error("[Livestream] Job %s (PID %d) đã kết thúc với lỗi (tệp < 1KB).", jid, pid)
                        else:
                            info["status"] = "finished"
                            log.info("[Livestream] Job %s (PID %d) đã kết thúc tự nhiên.", jid, pid)

                        info["finished_at"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
                        _cleanup_job_tmp(info.get("tmp_dir", ""))
                        _cleanup_runtime_tmp_artifacts(max_age_minutes=30)

                        # Ghi log
                        try:
                            conn = sqlite3.connect(DB_PATH, timeout=20.0)
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

                    # Kiem tra timeout (12 gio)
                    started = info.get("started_ts", 0)
                    if started > 0 and (time.time() - started) > _LIVESTREAM_MAX_HOURS * 3600:
                        log.warning("[Livestream] Job %s vượt quá %d giờ, tự động dừng.", jid, _LIVESTREAM_MAX_HOURS)
                        try:
                            os.kill(pid, signal.SIGTERM)
                        except Exception:
                            pass
                        info["status"] = "timeout"

                active = any(j.get("status") == "recording" for j in _livestream_jobs.values())
            try:
                with _ytdlp_lock:
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
                            del _ytdlp_jobs[jid]
                        else:
                            ytdlp_active = True
            except NameError:
                ytdlp_active = False
            _set_thumbnail_auto_block("livestream", active)
            _set_thumbnail_auto_block("ytdlp", ytdlp_active)
        except Exception as e:
            log.error("[Livestream] Lỗi watchdog: %s", e)

def _fan_controller_watchdog():
    """Tien trinh ngam dieu khien quat theo che do tuy chinh (Hysteresis)"""
    while True:
        try:
            settings = _load_fan_settings()
            if settings.get("mode") == "custom":
                on_temp = float(settings.get("on_temp", 65))
                off_temp = float(settings.get("off_temp", 55))
                # get_cpu_temp() tra ve chuoi co hau to °C (vd "57°C") nen phai strip truoc khi convert.
                raw_temp = str(get_cpu_temp()).strip()
                current_temp = float(_re_module.sub(r"[^0-9.\-]", "", raw_temp) or "0")
                
                # Dam bao OS daemon da duoc tat
                out = safe_run_cmd(["systemctl", "is-active", "fan.service"]).strip()
                if out == "active":
                    subprocess.run(["systemctl", "stop", "fan.service"])
                    
                # Binary Hysteresis Logic (ON/OFF)
                if current_temp >= on_temp:
                    duty = 10000
                elif current_temp <= off_temp:
                    duty = 0
                else:
                    # Giữ nguyên trạng thái hiện tại (Đang chạy thì chạy tiếp, đang dừng thì dừng tiếp)
                    try:
                        with open('/sys/class/pwm/pwmchip0/pwm0/duty_cycle', 'r') as f:
                            duty = int(f.read().strip())
                    except Exception:
                        duty = 0

                # FIX: enable=1 truoc khi ghi duty trong custom mode — neu user
                # chuyen tu OFF (enable=0) sang CUSTOM ma watchdog ghi duty truoc
                # khi enable thi kernel se tra ve EINVAL va quat khong chay.
                _pwm_write("enable", 1)
                subprocess.run(["sh", "-c", "echo %s > /sys/class/pwm/pwmchip0/pwm0/duty_cycle" % duty])
                
        except Exception as e:
            log.error("[FanWatchdog] Lỗi: %s", e)
        time.sleep(10)

# FIX: Khoi phuc trang thai quat sau reboot. Kernel PWM driver mac dinh
# enable=1 -> 5V luon co o cong ra quat ngay khi NAS bat nguon. Doc lai
# /opt/fan_custom.json, neu mode=off thi ngat PWM ngay tu dau de tranh
# truong hop "vua bat nguon quat da chay du user da chon Tat tu lan truoc".
def _restore_fan_state_on_boot():
    try:
        settings = _load_fan_settings()
        mode = settings.get("mode", "auto")
        if mode == "off":
            # User da chon Tat -> ngat hen PWM ngay khi service len.
            run_cmd(["systemctl", "stop", "fan.service"])
            _pwm_apply_off()
            log.info("[Fan] Khoi phuc trang thai TAT (cat 5V) tu /opt/fan_custom.json")
        elif mode == "on":
            run_cmd(["systemctl", "stop", "fan.service"])
            _pwm_apply_on(duty=10000, period=10000)
            log.info("[Fan] Khoi phuc trang thai BAT 100%% tu /opt/fan_custom.json")
        elif mode == "custom":
            # Watchdog se dieu khien duty, nhung enable=1 phai san sang
            _pwm_write("enable", 1)
            log.info("[Fan] Khoi phuc trang thai TUY CHINH — watchdog se quyet dinh")
        # mode="auto" -> fan.service tu lo, khong can lam gi
    except Exception as e:
        log.warning("[Fan] Khong khoi phuc duoc trang thai: %s", e)


_restore_fan_state_on_boot()

# Khoi dong watchdog thread
threading.Thread(target=_fan_controller_watchdog, daemon=True).start()
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
    try:
        return max(30, min(300, int(_tiktok_watch_state.get("poll_interval", 60))))
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
    }

def _load_tiktok_watch_state():
    global _tiktok_watch_state
    try:
        # FIX: Load tu file moi nhat giua primary (HDD) va mirror (eMMC).
        # Neu HDD bi RO trong khi user thay doi state -> mirror moi hon ->
        # phai dung mirror khi reboot, neu khong se mat thay doi.
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
                    log.info("[TikTokWatch] Loaded state tu %s (mtime=%s)", path, _mtime)
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
# Save tra ve True neu CO IT NHAT 1 noi ghi thanh cong. Load lay file moi
# nhat theo mtime giua hai noi.
_TIKTOK_WATCH_MIRROR = "/etc/nas/state/tiktok_live_watch.json"

def _save_tiktok_watch_state():
    primary_ok = False
    mirror_ok = False
    payload = json.dumps(_tiktok_watch_state, ensure_ascii=False)
    # Primary (HDD)
    try:
        os.makedirs(os.path.dirname(_TIKTOK_WATCH_FILE), exist_ok=True)
        tmp = _TIKTOK_WATCH_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(payload)
        os.replace(tmp, _TIKTOK_WATCH_FILE)
        primary_ok = True
    except Exception as e:
        log.warning("[TikTokWatch] Khong luu duoc primary (HDD): %s", e)
    # Mirror (eMMC, luon ghi de state khong mat khi HDD chet)
    try:
        os.makedirs(os.path.dirname(_TIKTOK_WATCH_MIRROR), exist_ok=True)
        tmp = _TIKTOK_WATCH_MIRROR + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(payload)
        os.replace(tmp, _TIKTOK_WATCH_MIRROR)
        mirror_ok = True
    except Exception as e:
        log.error("[TikTokWatch] Khong luu duoc mirror (eMMC): %s", e)
    if not primary_ok and not mirror_ok:
        log.error("[TikTokWatch] LUU THAT BAI O CA HAI NOI — state se mat khi reboot")
    return primary_ok or mirror_ok

def _normalize_tiktok_username(username):
    username = (username or "").strip()
    if username.startswith("@"):
        username = username[1:]
    username = username.split("/")[0].split("?")[0].strip()
    return "".join(ch for ch in username if ch.isalnum() or ch in "._-")[:64]

# Cache trang thai cookies TikTok de tranh hit TikTok moi chu ky watchdog.
_tiktok_cookies_cache = {
    "status": "unknown",   # missing / expired / revoked / valid / unknown
    "message": "",         # mo ta nguoi dung doc
    "checked_at": 0.0,     # epoch lan check gan nhat
    "file_mtime": 0.0,     # mtime cua cookies.txt luc check de phat hien file moi
}
_TIKTOK_COOKIES_CHECK_INTERVAL = 600  # 10 phut moi lan goi mang den TikTok

def _tiktok_cookies_path():
    return os.path.join(WEBDAV_FILE_ROOT, "cookies.txt")

def _parse_cookies_sessionid_expiry(path):
    """Doc cookies.txt (Netscape format), tra ve (epoch_expiry, sessionid_value) cho sessionid TikTok."""
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
    """Goi 1 endpoint can dang nhap; tra ve (is_valid, detail)."""
    curl_cmd = [
        "curl", "-s", "-L",
        "--max-time", "12",
        "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "-H", "Referer: https://www.tiktok.com/",
        "-H", "Accept: application/json, text/plain, */*",
        "-b", path,
        "-w", "\n__HTTP__:%{http_code}",
        # passport_logged_out endpoint tra ve cau truc { user: { uid, sec_uid }, ... } khi co session
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
        # Khong xac dinh duoc — coi nhu valid de khong false-alarm
        return True, "HTTP %s (không rõ)" % http_code
    except Exception as e:
        return True, "Không kiểm tra được (%s)" % str(e)[:80]

def _check_cookies_status(force=False):
    """Tra ve dict { status, message, checked_at } cua cookies TikTok.
    Su dung cache 10 phut tru khi force=True hoac file vua thay doi."""
    path = _tiktok_cookies_path()
    now = time.time()
    if not os.path.exists(path):
        _tiktok_cookies_cache.update({"status": "missing", "message": "Chưa có cookies.txt ở thư mục gốc WebDAV", "checked_at": now, "file_mtime": 0})
        return dict(_tiktok_cookies_cache)
    try:
        mtime = os.path.getmtime(path)
    except Exception:
        mtime = 0
    file_changed = mtime != _tiktok_cookies_cache.get("file_mtime", 0)
    if (not force) and (not file_changed) and (now - _tiktok_cookies_cache.get("checked_at", 0) < _TIKTOK_COOKIES_CHECK_INTERVAL):
        return dict(_tiktok_cookies_cache)
    expiry, sessionid = _parse_cookies_sessionid_expiry(path)
    if not sessionid:
        _tiktok_cookies_cache.update({"status": "missing", "message": "cookies.txt thiếu sessionid TikTok", "checked_at": now, "file_mtime": mtime})
        return dict(_tiktok_cookies_cache)
    if expiry and expiry < now:
        _tiktok_cookies_cache.update({"status": "expired", "message": "Cookie sessionid hết hạn lúc %s. Vui lòng xuất lại cookies.txt." % datetime.datetime.fromtimestamp(expiry).strftime("%d/%m/%Y %H:%M"), "checked_at": now, "file_mtime": mtime})
        return dict(_tiktok_cookies_cache)
    ok, detail = _ping_tiktok_cookies(path)
    if ok:
        _tiktok_cookies_cache.update({"status": "valid", "message": detail, "checked_at": now, "file_mtime": mtime})
    else:
        _tiktok_cookies_cache.update({"status": "revoked", "message": detail, "checked_at": now, "file_mtime": mtime})
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

def _tiktok_watch_clear_session(user):
    user["live_session_recorded"] = False
    user["live_session_job_id"] = ""
    user["live_session_started"] = ""
    user["live_session_last_live"] = ""
    user["job_id"] = ""

def _tiktok_watch_user_has_recording(username):
    uname = username.lower()
    target_url = "@%s/live" % uname
    now = time.time()
    with _livestream_lock:
        for jid, info in _livestream_jobs.items():
            is_match = (info.get("watch_username", "").lower() == uname or
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
        r'https:\\/\\/[^"\\]+?\.flv[^"\\]*',
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

def _tiktok_stream_url_seems_live(stream_url, cookies_path="", user_agent=""):
    """Kiem tra URL stream TikTok con song bang HTTP nhe.

    Watcher khong dung ffprobe de quyet dinh user dang live vi ffmpeg/ffprobe
    3.2 tren NAS co the khong doc duoc enhanced FLV/codec moi, gay false-negative.
    Viec co remux duoc sang MP4 hay khong van do /api/livestream/record xu ly.
    """
    if not stream_url:
        return False, "Thiếu URL stream"
    ua = user_agent or "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    stream_lower = stream_url.lower()
    common = [
        "--http1.1",
        "--max-time", "8",
        "--connect-timeout", "5",
        "-A", ua,
        "-H", "Referer: https://www.tiktok.com/",
    ]
    use_cookies = bool(cookies_path and os.path.exists(cookies_path) and "tiktokcdn" not in stream_lower)

    def _run_probe(extra_args):
        cmd = ["curl", "-s", "-L"] + common + extra_args + [
            "-o", "/dev/null",
            "-w", "%{http_code}|%{content_type}|%{size_download}",
        ]
        if use_cookies:
            cmd.extend(["-b", cookies_path])
        cmd.append(stream_url)
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
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
            "--speed-time", "5",
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
    # In HTTP status code o cuoi response qua --write-out de phan biet bi block (403/429)
    # voi "page tra ve nhung khong co stream" (200 nhung empty / not-live).
    sentinel = "\n__HTTP_STATUS__:"
    curl_cmd = [
        "curl", "-s", "-L",
        "--max-time", "20",
        "--retry", "2",
        "--retry-delay", "1",
        "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "-H", "Referer: https://www.tiktok.com/",
        "-H", "Accept-Language: en-US,en;q=0.9,vi;q=0.8",
        "-H", "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "-w", "%s%%{http_code}" % sentinel,
    ]
    if os.path.exists(cookies_path):
        curl_cmd.extend(["-b", cookies_path])
    curl_cmd.append(live_url)
    try:
        proc = subprocess.run(curl_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        raw = (proc.stdout or b"").decode("utf-8", errors="ignore")
        # Tach body va http_code
        idx = raw.rfind(sentinel)
        if idx >= 0:
            html = raw[:idx]
            http_code = raw[idx + len(sentinel):].strip()
        else:
            html = raw
            http_code = "?"
        if not html.strip():
            return False, "TikTok trả về trang rỗng (HTTP %s)" % http_code
        # Chi coi la live khi URL stream con phan hoi thuc su. Khong bat ffprobe
        # doc duoc codec tai day vi watcher co the false-negative voi TikTok FLV moi.
        flv_urls = _extract_tiktok_live_flv_urls(html)
        detail = ""
        last_http_block = False  # True khi gap HTTP 401/403/429 — la loi that su (cookies/rate-limit)
        for candidate in flv_urls[:3]:
            ok, detail = _tiktok_stream_url_seems_live(
                candidate,
                cookies_path,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            if ok:
                return True, ""
            # Chi coi la "loi that su" khi cookies/rate-limit; cac truong hop khac la user offline
            if detail and ("từ chối" in detail or "giới hạn tốc độ" in detail or "cookies.txt" in detail):
                last_http_block = True
        if flv_urls:
            # FLV URL co trong HTML nhung khong probe duoc:
            #  - Neu vi cookies/rate-limit (401/403/429) → ERROR that, giu detail
            #  - Neu chi vi 404 / size 0 / content-type sai → user vua offline, URL stale → coi nhu OFFLINE
            if last_http_block:
                return False, normalize_vietnamese_message(detail or "URL stream đã hết hạn hoặc không kiểm tra được")
            return False, "offline"
        lowered = html.lower()
        offline_signals = (
            "live has ended",
            "this live has ended",
            "phòng trực tiếp đã kết thúc",
            "user does not exist",
            "page not available",
            "couldn\\'t find this account",
            "\"liveroomstatus\":4",  # 4 = ended
            "\"liveroomstatus\":2",  # 2 = preparing
            "\"liveroom\":null",
        )
        for sig in offline_signals:
            if sig in lowered:
                return False, "offline"
        # HTML tra ve binh thuong nhung khong tim thay FLV URL va khong match signal nao
        # → user khong dang live (TikTok khong show stream URL khi offline). KHONG phai loi.
        return False, "offline"
    except Exception as e:
        return False, "Không kiểm tra được livestream: %s" % normalize_vietnamese_message(str(e))[:120]

def _start_tiktok_watch_record(username):
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
            return data.get("job_id", ""), data.get("message", "")
        return "", normalize_vietnamese_message(data.get("error") or data.get("detail") or ("HTTP %d" % status_code))[:200]
    except Exception as e:
        return "", "NAS không khởi tạo được tác vụ ghi livestream: %s" % normalize_vietnamese_message(str(e))[:120]

def _tiktok_live_watchdog():
    _load_tiktok_watch_state()
    _tiktok_watch_runtime.update({
        "running": True,
        "started_at": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
        "last_error": "",
        "last_summary": "Đã khởi động watcher TikTok trên NAS.",
    })
    log.info("[TikTokWatch] Watcher TikTok chạy trên NAS, không phụ thuộc app Android.")
    while True:
        try:
            with _tiktok_watch_lock:
                users_snapshot = [dict(u) for u in _tiktok_watch_state.get("users", [])]
                excluded = _is_tiktok_watch_excluded()
            changed = False
            checked_count = 0
            started_count = 0
            recording_count = 0
            for user in users_snapshot:
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
                is_live, err = _check_tiktok_user_live(username)
                checked_count += 1
                user["last_check"] = now_str
                if is_live:
                    if user.get("live_session_recorded", False):
                        _tiktok_watch_mark_session_recorded(
                            user,
                            user.get("live_session_job_id", "") or user.get("job_id", ""),
                            now_str,
                        )
                        user["status"] = "recorded"
                        user["job_id"] = user.get("live_session_job_id", "")
                        user["last_error"] = "Da ghi phien live nay; khong tao file thu hai cho toi khi user offline."
                        changed = True
                        continue
                    job_id, msg = _start_tiktok_watch_record(username)
                    user["status"] = "recording" if job_id else "error"
                    user["job_id"] = job_id
                    user["last_error"] = "" if job_id else msg
                    if job_id:
                        started_count += 1
                        recording_count += 1
                        _tiktok_watch_mark_session_recorded(user, job_id, now_str)
                        log.info("[TikTokWatch] @%s đang live, NAS đã tự bắt đầu ghi job %s.", username, job_id)
                    else:
                        log.warning("[TikTokWatch] @%s đang live nhưng không bắt đầu ghi được: %s", username, msg)
                else:
                    if err == "offline":
                        if user.get("live_session_recorded", False):
                            log.info("[TikTokWatch] @%s da offline, mo khoa phien live tiep theo.", username)
                        _tiktok_watch_clear_session(user)
                        user["status"] = "watching"
                        user["last_error"] = ""
                    elif user.get("live_session_recorded", False):
                        user["status"] = "recorded"
                        user["job_id"] = user.get("live_session_job_id", "")
                        user["last_error"] = "Chua xac nhan offline: %s" % normalize_vietnamese_message(err)
                    else:
                        user["status"] = "watching"
                        user["job_id"] = ""
                        user["last_error"] = normalize_vietnamese_message(err)
                changed = True
            if changed:
                # FIX (race condition): KHONG ghi de full list users — neu user
                # qua app /add/remove trong luc watchdog quet (30-60s/lap), thay
                # doi do se bi xoa hen. Logic moi: merge per-username vao state
                # hien tai. Update theo username, gi giu user moi them, bo qua
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
            _tiktok_watch_runtime.update({
                "running": True,
                "last_tick": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
                "last_error": "",
                "last_summary": "Đã kiểm tra %d user, %d đang ghi, %d vừa bắt đầu." % (checked_count, recording_count, started_count),
                "loop_count": int(_tiktok_watch_runtime.get("loop_count", 0)) + 1,
            })
        except Exception as e:
            _tiktok_watch_runtime.update({
                "running": False,
                "last_error": normalize_vietnamese_message(str(e))[:200],
                "last_tick": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
            })
            log.error("[TikTokWatch] Lỗi watchdog: %s", e)
        wait_seconds = _tiktok_watch_interval()
        _tiktok_watch_wake.wait(wait_seconds)
        _tiktok_watch_wake.clear()

@app.route("/api/tiktok/live_watch", methods=["GET"])
@requires_auth
def api_tiktok_live_watch_get():
    with _tiktok_watch_lock:
        _load_tiktok_watch_state()
        changed = False
        for user in _tiktok_watch_state.get("users", []):
            if user.get("status") == "recording":
                jid = user.get("job_id", "")
                active = False
                if jid:
                    with _livestream_lock:
                        info = _livestream_jobs.get(jid)
                        if info and info.get("status") == "recording":
                            try:
                                os.kill(info.get("pid"), 0)
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
    # Them trang thai cookies de UI hien banner khi het han / bi thu hoi.
    cookies = _check_cookies_status()
    resp["cookies_status"] = cookies.get("status", "unknown")
    resp["cookies_message"] = cookies.get("message", "")
    resp["daemon"] = dict(_tiktok_watch_runtime)
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
        # FIX: bao loi RO ngay cho user neu CA HAI noi luu deu fail
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
        if "exclude_enabled" in body:
            _tiktok_watch_state["exclude_enabled"] = bool(body.get("exclude_enabled"))
        if body.get("exclude_start"):
            _tiktok_watch_state["exclude_start"] = str(body.get("exclude_start"))[:5]
        if body.get("exclude_end"):
            _tiktok_watch_state["exclude_end"] = str(body.get("exclude_end"))[:5]
        _save_tiktok_watch_state()
        _tiktok_watch_wake.set()
        return jsonify(_tiktok_watch_state)


@app.route("/api/livestream/record", methods=["POST"])
@requires_auth
def api_livestream_record():
    """Bat dau ghi hinh livestream tu TikTok/Facebook/YouTube."""
    try:
        body = request.get_json(force=True) or {}
        live_url = body.get("url", "").strip()
        quality = body.get("quality", "best").strip()
        referer = body.get("referer", "").strip()
        user_agent = body.get("user_agent", "").strip()
        # Watch_username: gan boi _start_tiktok_watch_record de dedup chinh xac
        # khi URL bi ghi de thanh FLV CDN URL trong nhanh TikTok direct.
        watch_username = body.get("watch_username", "").strip()

        if not live_url:
            return jsonify({"error": "Thiếu URL livestream"}), 400

        # Kiem tra yt-dlp
        ytdlp_bin = _find_ytdlp_bin()
        if not ytdlp_bin:
            return jsonify({
                "error": "yt-dlp chưa được cài đặt trên NAS",
                "install_hint": "wget https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64 -O /usr/local/bin/yt-dlp && chmod +x /usr/local/bin/yt-dlp"
            }), 503

        # FIX: PRE-FLIGHT CHECK cho TikTok — kiem tra nhanh user co dang live khong
        # truoc khi cham vao yt-dlp/ffmpeg (cham, ton tai nguyen). Tra error CU THE
        # de app khong hien "timeout" chung chung nua.
        # Chi check cho URL TikTok co @user/live; cac URL khac (FB/YT/Shopee) van di
        # qua flow cu vi format URL khac va probe nhanh hon.
        try:
            tt_match = _re_module.search(r"tiktok\.com/@([\w.\-]+)", live_url)
            if tt_match and "/live" in live_url.lower():
                preflight_user = tt_match.group(1)
                # Skip preflight neu duoc goi tu watcher (da check live roi)
                if not watch_username:
                    is_live, detail = _check_tiktok_user_live(preflight_user)
                    if not is_live:
                        # Xac dinh ly do cu the cho user
                        if detail == "offline" or detail == "":
                            return jsonify({
                                "error": "@%s hiện không live (đã offline hoặc chưa bật stream)" % preflight_user,
                                "reason": "offline",
                            }), 404
                        elif "cookies" in detail.lower() or "cookie" in detail.lower():
                            return jsonify({
                                "error": "Cookies TikTok không hợp lệ: %s" % detail,
                                "reason": "cookies_invalid",
                            }), 401
                        elif "giới hạn tốc độ" in detail or "429" in detail:
                            return jsonify({
                                "error": "TikTok giới hạn tốc độ — thử lại sau vài phút",
                                "reason": "rate_limited",
                            }), 429
                        elif "từ chối" in detail or "chặn" in detail or "403" in detail:
                            return jsonify({
                                "error": "TikTok chặn IP/vùng: %s" % detail,
                                "reason": "blocked",
                            }), 403
                        else:
                            return jsonify({
                                "error": "Không thể bắt đầu ghi: %s" % detail,
                                "reason": "unknown_preflight",
                            }), 502
        except Exception as _e:
            # Preflight loi -> di tiep voi flow cu (yt-dlp/ffmpeg se tra error)
            log.warning("[Livestream] Preflight check ngoai mong doi: %s", _e)

        # Khong gioi han so luong ghi cung; thay vao do check phan cung de
        # bao ve NAS khoi tinh trang treo. Chap nhan luong moi neu:
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
        if cpu_pct > 85:
            hw_reason = "CPU %.0f%% quá cao" % cpu_pct
        elif mem_free_mb < 200:
            hw_reason = "RAM trống chỉ còn %.0f MB" % mem_free_mb
        elif mem_pct > 90:
            hw_reason = "RAM đang dùng %.0f%%" % mem_pct
        elif load1 > cores * 1.5:
            hw_reason = "Load average %.2f vượt %.1f (cores x 1.5)" % (load1, cores * 1.5)
        # Cap an toan tuyet doi: 16 luong song song, tranh truong hop psutil tra
        # so do sai khien NAS bi tham lam vo han.
        if active_count >= 16:
            hw_reason = "Đã có %d luồng ghi đồng thời (ngưỡng an toàn)" % active_count
        if hw_reason:
            return jsonify({
                "error": "Không thể bắt đầu luồng mới: %s" % hw_reason,
                "active_count": active_count,
                "cpu_pct": round(cpu_pct, 1),
                "mem_free_mb": int(mem_free_mb),
                "load_avg_1min": round(load1, 2),
            }), 429

        # Kiem tra dung luong HDD con lai
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

        # Tao thu muc luu
        try:
            os.makedirs(_LIVESTREAM_DIR, exist_ok=True)
        except Exception:
            pass

        # Tao ten file output
        timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        # FIX: KHONG dung %(title) trong output_template — title cua TikTok live
        # co the thay doi giua chung (host doi caption, hoac yt-dlp re-resolve
        # metadata sau khi mat ket noi). Moi lan title doi -> yt-dlp dong file
        # cu va mo file moi -> 1 session bi ghi ra nhieu file .mp4.
        #
        # Thay vao do dung stable_id deterministic:
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
                    # cuoi cung. Neu khong resolve duoc thi stable_id van rong va
                    # filename se la "tiktok_<ts>.mp4" (khong con "_tiktok" cung).
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
                                # Thu HEAD truoc, neu khong duoc thi GET
                                resp = None
                                try:
                                    resp = urllib.request.urlopen(conn, timeout=5)
                                except Exception:
                                    break
                                next_url = resp.geturl() if resp else current
                                try: resp.close()
                                except Exception: pass
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
                            log.warning("[Livestream] Khong resolve duoc short URL: %s", _e)
            except Exception:
                pass
        if stable_id:
            # Sanitize de tranh ky tu xau trong filename
            stable_id = _re_module.sub(r"[^\w.\-]", "_", stable_id)[:40]
            output_template = os.path.join(
                _LIVESTREAM_DIR,
                "%s_%s_%s.%%(ext)s" % (platform, stable_id, timestamp_str)
            )
        else:
            output_template = os.path.join(
                _LIVESTREAM_DIR,
                "%s_%s.%%(ext)s" % (platform, timestamp_str)
            )
        direct_tiktok_flv = False
        direct_output_file = ""
        tiktok_user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        # Xay dung lenh yt-dlp cho livestream
        format_str = "best"
        if quality == "720p":
            format_str = "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
        elif quality == "audio":
            format_str = "bestaudio[ext=m4a]/bestaudio"

        cmd = [
            ytdlp_bin,
            "--no-live-from-start",    # Ghi tu hien tai (TikTok/Facebook khong ho tro tu dau)
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
            # FIX: ep yt-dlp remux fragment HLS thanh MP4 container chuan, khong
            # con luu raw .ts mislabel ext .mp4 (player tu choi parse vi magic
            # bytes khong khop). --remux-video chi remux container, KHONG
            # re-encode -> nhanh, khong giam chat luong.
            "--remux-video", "mp4",
            # FIX: moov atom de o dau file de player play duoc khi file con dang
            # ghi (progressive streaming). Khong co flag nay, moov nam o cuoi
            # va player phai download het roi moi seek duoc.
            "--postprocessor-args", "ffmpeg:-movflags +faststart",
        ]

        # Them cookies neu co file (ở thư mục gốc)
        cookies_path = os.path.join(WEBDAV_FILE_ROOT, "cookies.txt")
        if os.path.exists(cookies_path):
            cmd.extend(["--cookies", cookies_path])

        if referer:
            cmd.extend(["--referer", referer])
        if user_agent:
            cmd.extend(["--user-agent", user_agent])

        # --- TIKTOK HTML FLV FALLBACK -> MP4 ---
        # TikTok API metadata cua yt-dlp co the bao sai "not currently live",
        # trong khi trang HTML van co FLV stream dang chay. Lay cac FLV URL
        # tu HTML, chon bien the H264 ffmpeg 3.2 doc duoc (_hd/_ld), roi ghi
        # truc tiep thanh MP4. Khong luu FLV ra NAS.
        if "tiktok" in live_url.lower():
            curl_cmd = [
                "curl", "-s", "-L",
                "--max-time", "20",
                "-A", tiktok_user_agent,
                "-H", "Referer: https://www.tiktok.com/",
                "-H", "Accept-Language: en-US,en;q=0.9,vi;q=0.8",
            ]
            if os.path.exists(cookies_path):
                curl_cmd.extend(["-b", cookies_path])
            curl_cmd.append(live_url)
            
            try:
                html = subprocess.check_output(curl_cmd, timeout=25).decode("utf-8", errors="ignore")
                flv_urls = []
                for pat in (
                    r'\\"flv\\":\\"(https://[^"\\]+)',
                    r'\\"origin\\":\{[^}]*\\"flv\\":\\"(https://[^"\\]+)',
                    r'"flv":"(https://[^"\\]+)',
                    r'"origin":\{[^}]*"flv":"(https://[^"\\]+)',
                    r'https:\\/\\/[^"\\]+?\.flv[^"\\]*',
                ):
                    for u in _re_module.findall(pat, html):
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
                log.info("[Livestream] TikTok HTML fallback: phát hiện %d URL FLV ứng viên", len(flv_urls))
                for candidate in sorted(flv_urls, key=_flv_rank):
                    log.info("[Livestream] TikTok HTML fallback: probe candidate %s", candidate[:180])
                    if _direct_flv_has_remuxable_video(candidate, cookies_path, tiktok_user_agent):
                        live_url = candidate
                        direct_tiktok_flv = True
                        # FIX: dung stable_id (username) trong filename, khong de "_tiktok"
                        # cung. Truoc day moi luc dung direct FLV path, file deu co dang
                        # tiktok_<ts>_tiktok.mp4 -> mat thong tin user trong ten file.
                        if stable_id:
                            direct_output_file = os.path.join(
                                _LIVESTREAM_DIR,
                                "%s_%s_%s.mp4" % (platform, stable_id, timestamp_str)
                            )
                        else:
                            direct_output_file = os.path.join(
                                _LIVESTREAM_DIR,
                                "%s_%s.mp4" % (platform, timestamp_str)
                            )
                        log.info("[Livestream] TikTok HTML fallback: dùng FLV H264 có thể remux sang MP4")
                        break
                if not direct_tiktok_flv and flv_urls:
                    log.warning("[Livestream] TikTok HTML fallback: có URL FLV nhưng không kiểm tra được codec có thể remux")
            except Exception as e:
                log.warning("[Livestream] Lỗi TikTok HTML fallback: %s", e)
        # --------------------------------

        if direct_tiktok_flv:
            # FLV URL da duoc chon la H264 remuxable. Ghi thang MP4 bang
            # ffmpeg, khong de lai file .flv.
            # Pre-check NHANH bang HEAD request (khong tai body) de tu choi som
            # neu URL FLV da 404/403/expired. HEAD chi ton ~1-3s nen khong gay
            # timeout 35s o local urlopen ben watcher.
            head_cmd = [
                "curl", "-s", "-I", "-L", "--http1.1",
                "--max-time", "6",
                "--connect-timeout", "4",
                "-A", tiktok_user_agent,
                "-H", "Referer: https://www.tiktok.com/",
                "-o", "/dev/null",
                # FIX: log them content_type de validate FLV that su (tranh
                # truong hop CDN tra ve text/html, application/json hay
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

            # Neu HEAD that bai voi 4xx/5xx -> thu re-scrape 1 lan (FLV URL co the vua het han).
            if http_code.startswith(("4", "5")):
                original_user_url = body.get("url", "").strip()
                if original_user_url and "tiktok.com" in original_user_url and not original_user_url.startswith(live_url[:30]):
                    rescrape_cmd = [
                        "curl", "-s", "-L", "--max-time", "8",
                        "-A", tiktok_user_agent,
                        "-H", "Referer: https://www.tiktok.com/",
                    ]
                    if os.path.exists(cookies_path):
                        rescrape_cmd.extend(["-b", cookies_path])
                    rescrape_cmd.append(original_user_url)
                    try:
                        html2 = subprocess.check_output(rescrape_cmd, timeout=10).decode("utf-8", errors="ignore")
                        m2 = re.search(r'\\"origin\\":\{[^}]*\\"flv\\":\\"(https://[^"\\]+)', html2) or re.search(r'\\"flv\\":\\"(https://[^"\\]+)', html2)
                        if m2:
                            new_flv = m2.group(1).replace("\\u0026", "&")
                            if new_flv != live_url:
                                live_url = new_flv
                                head_cmd[-1] = live_url
                                head_proc = subprocess.run(head_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
                                head_out = (head_proc.stdout or b"").decode("utf-8", errors="ignore").strip() or "0|"
                                parts = head_out.split("|", 1)
                                http_code = parts[0] or "0"
                                content_type = (parts[1] if len(parts) > 1 else "").lower().strip()
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

            # FIX: Validate Content-Type — neu CDN tra ve text/html, JSON,
            # hay m3u8 manifest (cac dau hieu URL het han hoac sai) thi fallback
            # ve yt-dlp path (yt-dlp se tu re-scrape, demux HLS, remux thanh
            # mp4 chuan), thay vi luu rac vao file .flv khong play duoc.
            is_flv_serve = any(s in content_type for s in ("video/x-flv", "video/flv", "flv-application", "application/octet-stream", "video/mp4"))
            if content_type and not is_flv_serve:
                # CDN khong serve FLV thuan — bo direct path, dung yt-dlp fallback
                direct_tiktok_flv = False
                direct_output_file = ""
            elif not _direct_flv_has_remuxable_video(live_url, cookies_path, tiktok_user_agent):
                # TikTok FLV moi co the dung enhanced FLV/HEVC tag ma ffmpeg 3.2
                # tren NAS doc thanh codec unknown. Neu cu curl raw se tao file
                # lon nhung khong remux/mo duoc, nen fallback ve yt-dlp.
                direct_tiktok_flv = False
                direct_output_file = ""

        if direct_tiktok_flv:
            cmd = [
                "/usr/bin/ffmpeg", "-y",
                "-loglevel", "warning",
                "-rw_timeout", "60000000",
                "-user_agent", tiktok_user_agent,
                "-headers", "Referer: https://www.tiktok.com/\r\n",
                "-i", live_url,
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                "-movflags", "+faststart",
                direct_output_file,
            ]
        else:
            # Truong hop fallback: live_url co the la URL FLV CDN da scrape ra,
            # nhung CDN tra Content-Type khong phai FLV. Reset ve URL goc cua
            # user de yt-dlp scrape lai theo cach cua no.
            original_user_url = body.get("url", "").strip()
            if original_user_url and "tiktok.com" in original_user_url:
                live_url = original_user_url
            cmd.append(live_url)

        # Log file rieng cho debug
        log_dir = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "livestream_logs")
        try:
            os.makedirs(log_dir, exist_ok=True)
        except Exception:
            pass
        log_file = os.path.join(log_dir, "live_%s.log" % timestamp_str)
        tmp_dir = _make_hdd_tmp_dir("livestream_%s" % timestamp_str)
        if tmp_dir and cmd and cmd[0] == ytdlp_bin:
            cmd[-1:-1] = ["--paths", "temp:%s" % tmp_dir]

        with open(log_file, "w") as lf:
            lf.write("CMD: %s\n\n" % " ".join(cmd))
            if tmp_dir:
                lf.write("TMPDIR: %s\n\n" % tmp_dir)
            proc = subprocess.Popen(
                cmd,
                stdout=lf, stderr=lf,
                close_fds=True,
                env=_job_env_with_tmp(tmp_dir)
            )

        # job_id voi millisecond + random suffix de tranh trung khoa khi 2 job
        # khoi cung giay (truong hop nhieu user TikTok cung len live gan nhau).
        job_id = "live_%d_%s" % (int(time.time() * 1000), uuid.uuid4().hex[:6])
        now_str = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")

        with _livestream_lock:
            _livestream_jobs[job_id] = {
                "url": live_url,
                "platform": platform,
                "pid": proc.pid,
                "status": "recording",
                "output_dir": _LIVESTREAM_DIR,
                "output_file": os.path.basename(direct_output_file) if direct_output_file else "",
                "file_size": 0,
                "started_at": now_str,
                "started_ts": time.time(),
                "quality": quality,
                "log_file": log_file,
                "timestamp_str": timestamp_str,
                "direct_tiktok_flv": direct_tiktok_flv,
                "direct_output_path": direct_output_file,
                "watch_username": watch_username,
                "tmp_dir": tmp_dir,
            }

        # Tam dung thumbnail generator de nhuong CPU/IO cho viec ghi livestream.
        # Watchdog se tu dong bo chan khi khong con luong nao dang ghi.
        _set_thumbnail_auto_block("livestream", True)

        # Ghi log he thong
        try:
            conn = sqlite3.connect(DB_PATH, timeout=20.0)
            cur = conn.cursor()
            cur.execute('INSERT INTO system_logs (type, module, message) VALUES (?, ?, ?)',
                        ("INFO", "Livestream",
                         "Bắt đầu ghi livestream %s (PID=%d): %s" % (platform, proc.pid, live_url[:120])))
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
            "message": "Đang ghi hình livestream %s. Video sẽ được lưu vào thư mục Livestream/." % platform.upper()
        })

    except Exception as e:
        log.error("[Livestream] Lỗi bắt đầu ghi hình: %s", e)
        return jsonify({"error": "Không bắt đầu được ghi livestream: %s" % normalize_vietnamese_message(str(e))}), 500


@app.route("/api/livestream/status", methods=["GET"])
@requires_auth
def api_livestream_status():
    """Lay trang thai tat ca cac livestream job."""
    result_jobs = []
    with _livestream_lock:
        for jid, info in list(_livestream_jobs.items()):
            pid = info.get("pid")
            status = info.get("status", "unknown")

            # 1. Tim file output va lay size truoc khi danh gia status
            file_size = 0
            output_file = info.get("output_file", "")
            out_dir = info.get("output_dir", _LIVESTREAM_DIR)
            try:
                if os.path.isdir(out_dir):
                    # Tim file moi nhat trong thu muc Livestream cua luong nay
                    platform = info.get("platform", "")
                    timestamp_str = info.get("timestamp_str", "")
                    all_files = []
                    for f in os.listdir(out_dir):
                        fp = os.path.join(out_dir, f)
                        if os.path.isfile(fp) and not f.endswith(".log"):
                            if timestamp_str and timestamp_str not in f:
                                continue
                            all_files.append(fp)
                    if all_files:
                        latest = max(all_files, key=os.path.getmtime)
                        output_file = os.path.basename(latest)
                        file_size = os.path.getsize(latest)
            except Exception:
                pass

            # 2. Kiem tra process con chay khong va set status dua vao file_size
            if status == "recording":
                is_running = False
                try:
                    os.kill(pid, 0)
                    is_running = True
                except Exception:
                    pass
                    
                if not is_running:
                    # Neu file be hon 150KB (thuong la cac trang bao loi HTML do CDN gui hoac file video bi hong)
                    if file_size < 150 * 1024:
                        status = "error"
                        info["status"] = "error"
                    else:
                        status = "finished"
                        info["status"] = "finished"
                    info["finished_at"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")

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
            info["error_reason"] = error_reason

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
                "error_reason": info.get("error_reason", ""),
                "quality": info.get("quality", "best"),
                "started_at": info.get("started_at", ""),
                "finished_at": info.get("finished_at", ""),
                # FIX: expose watch_username de UI hien "@user" thay vi jobid/filename
                "watch_username": info.get("watch_username", "")
            })

    # Don dep job cu qua 24 gio
    with _livestream_lock:
        for jid, info in list(_livestream_jobs.items()):
            if info.get("status") in ("finished", "stopped", "timeout", "error"):
                started_ts = info.get("started_ts", 0)
                if started_ts > 0 and (time.time() - started_ts) > 86400:
                    del _livestream_jobs[jid]

    active_count = sum(1 for j in result_jobs if j.get("status") == "recording")
    return jsonify({
        "active_streams": active_count,
        "total_jobs": len(result_jobs),
        "jobs": result_jobs
    })


@app.route("/api/livestream/stop", methods=["POST"])
@requires_auth
def api_livestream_stop():
    """Dung ghi hinh livestream bang job_id."""
    try:
        body = request.get_json(force=True) or {}
        job_id = body.get("job_id", "").strip()

        if not job_id:
            # Dung tat ca
            with _livestream_lock:
                for jid, info in _livestream_jobs.items():
                    if info.get("status") == "recording":
                        try:
                            os.kill(info["pid"], signal.SIGTERM)
                            info["status"] = "stopped"
                        except Exception:
                            pass
            return jsonify({"message": "Đã gửi lệnh dừng tất cả livestream."})

        with _livestream_lock:
            info = _livestream_jobs.get(job_id)

        if not info:
            return jsonify({"error": "Không tìm thấy tác vụ: %s" % job_id}), 404

        pid = info.get("pid")
        try:
            os.kill(pid, signal.SIGTERM)  # SIGTERM de yt-dlp finalize file
            info["status"] = "stopped"
            info["finished_at"] = datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S")
            log.info("[Livestream] Đã dừng tác vụ %s (PID %d) theo yêu cầu.", job_id, pid)

            # Ghi log
            try:
                conn = sqlite3.connect(DB_PATH, timeout=20.0)
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
                "status": "stopped",
                "message": "Đã dừng ghi hình. Tệp video sẽ được yt-dlp hoàn tất trong vài giây."
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
#  3. Tra ve ngay lap tuc {"message": "Da nhan lenh..."}
#  4. yt-dlp tu tai va luu vao WEBDAV_FILE_ROOT/save_folder
#
# Yeu cau cai dat: pip3 install yt-dlp  (hoac pip install yt-dlp)
# Hoac: apt-get install yt-dlp  (Debian/OMV)

_ytdlp_jobs = {}  # {job_id: {url, status, pid}}
_ytdlp_lock = threading.Lock()

@app.route("/api/ytdlp/download", methods=["POST"])
@requires_auth
def api_ytdlp_download():
    """Nhan link video, chay yt-dlp ngam va luu vao NAS."""
    try:
        body = request.get_json(force=True) or {}
        video_url = body.get("url", "").strip()
        save_folder = body.get("save_folder", "Downloads/social/").strip("/")
        quality = body.get("quality", "best")

        if not video_url:
            return jsonify({"error": "Thiếu URL video"}), 400

        # Kiem tra yt-dlp co san khong
        ytdlp_bin = None
        for candidate in ["yt-dlp", "/usr/local/bin/yt-dlp", "/usr/bin/yt-dlp"]:
            try:
                if subprocess.run([candidate, "--version"],
                                   stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL).returncode == 0:
                    ytdlp_bin = candidate
                    break
            except Exception:
                continue

        if not ytdlp_bin:
            return jsonify({
                "error": "yt-dlp chưa được cài đặt. Chạy: pip3 install yt-dlp",
                "install_hint": "sudo pip3 install yt-dlp"
            }), 503

        # Xay duong dan luu (tuyet doi)
        dest_dir = os.path.join(WEBDAV_FILE_ROOT, save_folder)
        try:
            os.makedirs(dest_dir, exist_ok=True)
        except Exception:
            pass

        # Format chat luong: uu tien mp4 HD, fallback best
        format_str = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
        if quality == "audio":
            format_str = "bestaudio[ext=m4a]/bestaudio"

        # Output template: ten file goc cua video, trong dest_dir
        output_template = os.path.join(dest_dir, "%(title).100s.%(ext)s")

        # Khoi dong yt-dlp ngam (KHONG cho doi - tra ve ngay cho Android)
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

        with open(log_file, "w") as lf:
            if tmp_dir:
                lf.write("TMPDIR: %s\n\n" % tmp_dir)
            proc = subprocess.Popen(
                cmd,
                stdout=lf, stderr=lf,
                close_fds=True,
                env=_job_env_with_tmp(tmp_dir)
            )

        job_id = str(int(time.time()))
        with _ytdlp_lock:
            _ytdlp_jobs[job_id] = {
                "url": video_url,
                "folder": dest_dir,
                "pid": proc.pid,
                "started_at": datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
                "tmp_dir": tmp_dir
            }
        _set_thumbnail_auto_block("ytdlp", True)

        # Ghi log he thong
        try:
            conn = sqlite3.connect(DB_PATH, timeout=20.0)
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
    """Kiem tra trang thai cac job yt-dlp dang chay."""
    with _ytdlp_lock:
        active = {}
        for jid, info in list(_ytdlp_jobs.items()):
            pid = info.get("pid")
            is_running = False
            try:
                os.kill(pid, 0)  # Signal 0 = check ton tai
                is_running = True
            except Exception:
                pass
            if not is_running:
                _cleanup_job_tmp(info.get("tmp_dir", ""))
                _cleanup_runtime_tmp_artifacts(max_age_minutes=30)
                del _ytdlp_jobs[jid]
            else:
                active[jid] = info
        _set_thumbnail_auto_block("ytdlp", len(active) > 0)
    return jsonify({"active_jobs": len(active), "jobs": list(active.values())})


# ============ KHOI CHAY ============
if __name__ == "__main__":
    # Initialize main IO loop here so it's bound to the main thread
    main_loop = tornado.ioloop.IOLoop.current()
    _cleanup_stale_job_tmp(max_age_hours=1)
    _cleanup_runtime_tmp_artifacts(max_age_minutes=30)
    
    # SIGTERM/SIGINT: Graceful shutdown - kill tat ca child processes truoc khi thoat
    def _graceful_shutdown(signum, frame):
        """Dung server sach, khong de lai zombie."""
        log.info("[Shutdown] Nhận tín hiệu %s, đang dọn dẹp...", signum)
        # Kill tat ca child process cua nhom tien trinh nay
        try:
            import os as _os
            pgid = _os.getpgrp()
            _os.killpg(pgid, signal.SIGTERM)
        except Exception:
            pass
        # Xoa PID file
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
        sys.exit(0)
    signal.signal(signal.SIGTERM, _graceful_shutdown)
    signal.signal(signal.SIGINT, _graceful_shutdown)

    # atexit: Don dep PID file khi thoat binh thuong
    def _cleanup_pid():
        try:
            os.remove(PID_FILE)
        except Exception:
            pass
    atexit.register(_cleanup_pid)

    # Ghi PID file de restart an toan
    try:
        with open(PID_FILE, 'w') as f:
            f.write(str(os.getpid()))
    except Exception:
        pass
    
    bind_host = "0.0.0.0"

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
    
    # Thread giam sat log WebDAV de phat hien scan password
    threading.Thread(target=monitor_scanners, daemon=True).start()
    threading.Thread(target=monitor_journalctl, daemon=True).start()
    
    # Thread cache du lieu he thong (cap nhat moi 2 giay) → API phan hoi tuc thi
    threading.Thread(target=_update_status_cache, daemon=True).start()
    
    # Thread tao thumbnail tu dong (Synology-style)
    threading.Thread(target=_thumbnail_generator, daemon=True).start()
    log.info("[Thumbnail] Trình tạo ảnh thu nhỏ nền đã khởi động.")
    
    # Thread giam sat Hanh vi He thong Toan Dien (Mat HDD, Mat LAN IP, Chet Service)
    threading.Thread(target=_system_health_watchdog, daemon=True).start()
    log.info("[Watchdog] Trình giám sát sức khỏe hệ thống đã khởi động (tự động xử lý lỗi mạng/ổ cứng).")

    # FEATURE: Disk health time-series daemon + scheduled backup daemon
    threading.Thread(target=_disk_health_watchdog, daemon=True).start()
    log.info("[DiskHealth] Trình theo dõi sức khỏe HDD đã khởi động (sample mỗi 5 phút).")
    threading.Thread(target=_scheduled_backup_worker, daemon=True).start()
    log.info("[BackupSchedule] Trình lên lịch backup tự động đã khởi động.")
    threading.Thread(target=_sleep_schedule_worker, daemon=True).start()
    log.info("[SleepSchedule] Trình lên lịch HDD spindown đã khởi động.")

    # Thread cron don dep Thung rac + phat hien canh bao + kick AI ban dem
    threading.Thread(target=_cron_worker, daemon=True).start()
    log.info("[Cron] Tác vụ tự động dọn dẹp và cảnh báo chủ động đã khởi động.")

    # Thread dò TikTok live chạy hoàn toàn trên NAS. App Android chỉ cấu hình và
    # hiển thị trạng thái; việc phát hiện live + ghi hình không phụ thuộc app.
    threading.Thread(target=_tiktok_live_watchdog, daemon=True, name="TikTokLiveWatchdog").start()
    log.info("[TikTokWatch] Watcher TikTok live đã khởi động trên NAS.")
    # ============ DON PORT TRUOC KHI BIND (FIX ZOMBIE PROCESS GIU PORT) ============
    import socket as _socket
    def _force_free_port(port):
        """Kill bat ky process/thread nao dang giu port nay (bao gom zombie threads)."""
        try:
            test_sock = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
            test_sock.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
            test_sock.bind(('0.0.0.0', port))
            test_sock.close()
        except OSError:
            log.warning("[Port %d] Đang bị chiếm, thử giải phóng...", port)
            try:
                subprocess.run(['fuser', '-k', '%d/tcp' % port], stderr=subprocess.DEVNULL)
                time.sleep(2)
            except Exception: pass
            try:
                result = subprocess.run(['fuser', '%d/tcp' % port], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                pids = result.stdout.decode('utf-8', errors='ignore').strip().split()
                for pid in pids:
                    pid = pid.strip()
                    if pid.isdigit():
                        subprocess.run(['kill', '-9', pid], stderr=subprocess.DEVNULL)
                time.sleep(1)
            except Exception: pass
            log.info("[Port %d] Đã giải phóng.", port)

    _force_free_port(5050)
    _force_free_port(5051)

    # ============ TOI UU HOA CUC DAI: WAITRESS MULTI-THREAD ============
    def run_flask():
        try:
            from waitress import serve
            serve(app, host=bind_host, port=5050, threads=4, connection_limit=50)
        except ImportError:
            log.warning("Thiếu thư viện Waitress. Vui lòng chạy: pip3 install waitress")
            app.run(host=bind_host, port=5050, debug=False, threaded=True)
            
    threading.Thread(target=run_flask, daemon=True).start()

    # Chay Tornado WebSocket tren port 5051 (main thread) dung de ban thong bao (Alerts)
    ws_app = tornado.web.Application([
        (r"/ws/alerts", AlertWebSocket),
    ])
    # Manual socket voi SO_REUSEADDR de tranh loi Address already in use (TIME_WAIT)
    _ws_sock = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
    _ws_sock.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
    _ws_sock.bind((bind_host, 5051))
    _ws_sock.listen(128)
    _ws_sock.setblocking(False)
    ws_server = tornado.httpserver.HTTPServer(ws_app)
    ws_server.add_socket(_ws_sock)
    log.info("Server đã khởi động thành công!")
    main_loop.start()
