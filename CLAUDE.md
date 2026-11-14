# CLAUDE.md — NASWebDAV Project Rules & Hard Constraints

## 🚨 CRITICAL HARDWARE & RUNTIME CONSTRAINT: NAS RUNS PYTHON 3.5.3 + FLASK 1.1.4

Toàn bộ code backend chạy trên thiết bị **Chainedbox L1 Pro (RK3328 ARM64, Debian 9 Stretch, 1GB RAM, 49MB zram)**:
- **File chính**: [`backend/nas_api_server.py`](backend/nas_api_server.py) (đồng bộ lên `/opt/nas_api_server.py` tại `192.168.100.254` / Tailscale `100.90.135.102`)
- **Python**: **Python 3.5.3** (`/usr/bin/python3`)
- **Flask**: **Flask 1.1.4** + **Werkzeug 1.0.1**
- **Pillow (PIL)**: **4.0.0**
- **psutil**: **5.9.5**

### ❌ CẤM TUYỆT ĐỐI (Sẽ gây `SyntaxError`, `ImportError`, `TypeError`, `AttributeError` trên NAS Python 3.5.3):

| Tính năng bị cấm (Python 3.6+ / Flask 2.0+) | Phiên bản mới có | BẮT BUỘC DÙNG THAY THẾ (Python 3.5.3 + Flask 1.1.4) |
|---|---|---|
| f-strings: `f"hello {x}"` (`JoinedStr`) | Python 3.6+ | `"hello %s" % (x,)` hoặc `"hello {0}".format(x)` |
| Variable type annotations: `x: int = 1` (`AnnAssign`) | Python 3.6+ | `x = 1` (không dùng type annotation cho biến) |
| Builtin generic types: `list[str]`, `dict[str, int]`, `X \| Y` | Python 3.9+ / 3.10+ | Không dùng hoặc chỉ viết trong comment |
| Numeric literal underscores: `1_000_000` | Python 3.6+ | `1000000` |
| Walrus operator: `if (m := pat.search(s)):` (`NamedExpr`) | Python 3.8+ | Gán biến ở dòng riêng `m = pat.search(s)` |
| `import secrets` / `secrets.choice` / `secrets.token_hex` | Python 3.6+ | `random.SystemRandom().choice(...)` hoặc `binascii.hexlify(os.urandom(n)).decode('ascii')` |
| `subprocess.run(..., capture_output=True)` | Python 3.7+ | `subprocess.run(..., stdout=subprocess.PIPE, stderr=subprocess.PIPE)` |
| `subprocess.run(..., text=True)` | Python 3.7+ | `(proc.stdout or b"").decode("utf-8", errors="ignore")` |
| `subprocess.run/Popen/check_output(..., encoding=..., errors=...)` | Python 3.6+ | Đọc raw `bytes` rồi `.decode("utf-8", errors="ignore")` |
| `json.loads(bytes_obj)` (truyền `bytes` trực tiếp vào `json.loads`) | Python 3.6+ | Luôn `.decode("utf-8", errors="ignore")` thành `str` trước khi gọi `json.loads(...)` |
| `with os.scandir(path) as it:` hoặc `it.close()` | Python 3.6+ | Dùng `os.listdir(path)` (trong Python 3.5 `os.scandir` không hỗ trợ `with` và không có `.close()`) |
| Truyền `pathlib.Path` vào `open()`, `os.*`, `os.path.*`, `shutil.*` | Python 3.6+ (PEP 519) | Luôn dùng `str` path (`os.path.join`) |
| `m[0]`, `m[1]` trên `re` Match object (`Match.__getitem__`) | Python 3.6+ | `m.group(0)`, `m.group(1)` |
| `datetime.fromisoformat(...)`, `.isoformat(timespec=...)` | Python 3.7+ / 3.6+ | `datetime.strptime(...)`, `.isoformat()` (không truyền `timespec`) |
| `time.time_ns()`, `time.monotonic_ns()`, `time.perf_counter_ns()` | Python 3.7+ | `int(time.time() * 1000)`, `time.monotonic()` |
| `str.removeprefix()`, `str.removesuffix()`, `str.isascii()` | Python 3.9+ / 3.7+ | `s[len(p):] if s.startswith(p) else s` |
| `random.choices(...)`, `random.randbytes(...)` | Python 3.6+ / 3.9+ | `[random.choice(seq) for _ in range(k)]`, `os.urandom(n)` |
| `hashlib.blake2b`, `hashlib.blake2s`, `hashlib.sha3_*` | Python 3.6+ | `hashlib.sha256()`, `hashlib.md5()` |
| `ssl.PROTOCOL_TLS`, `ssl.PROTOCOL_TLS_SERVER`, `ssl.TLSVersion` | Python 3.6+ / 3.7+ | `ssl.PROTOCOL_SSLv23` / `ssl.PROTOCOL_TLSv1_2` |
| `shutil.copytree(..., dirs_exist_ok=True)` | Python 3.8+ | Tự duyệt `os.walk` + `os.makedirs(..., exist_ok=True)` + `shutil.copy2` |
| `sqlite3.Connection.backup(...)` | Python 3.7+ | SQLite online backup hoặc copy file khi giữ lock |
| Flask `send_file(..., max_age=..., download_name=..., etag=...)` | Flask 2.0+ | Dùng `cache_timeout=...`, `attachment_filename=...`, `add_etags=...` hoặc gán `_resp.headers["Cache-Control"]` |
| Flask `@app.get(...)`, `@app.post(...)` | Flask 2.0+ | `@app.route(..., methods=["GET"])`, `@app.route(..., methods=["POST"])` |

---

## Quy trình bắt buộc sau mỗi lần sửa `backend/nas_api_server.py`

1. **Line endings**: Giữ chuẩn LF (`\n`), UTF-8 không BOM (`U+FEFF`).
2. **Chạy toàn bộ bộ test**:
   ```bash
   python -m pytest tests/ -v
   ```
   (Trong đó [`tests/test_audit_20261026_fixes.py`](tests/test_audit_20261026_fixes.py) tự động quét AST, token, stdlib kwargs, Flask 1.1.4 kwargs và scope `NameError` của toàn bộ `backend/nas_api_server.py` và `loop_script` nhúng bên trong).
3. **Đồng bộ và kiểm tra trực tiếp trên Python 3.5.3 của NAS**:
   - Copy `backend/nas_api_server.py` lên `/opt/nas_api_server.py` trên NAS (`192.168.100.254` / `100.90.135.102`).
   - Chạy `/usr/bin/python3 -m py_compile /opt/nas_api_server.py` trên NAS.
   - Restart `systemctl restart nas_api.service` và kiểm tra `/var/log/nas_api.log`.
