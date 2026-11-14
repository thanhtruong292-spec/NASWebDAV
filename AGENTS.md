# AGENTS.md — NASWebDAV Agent Instructions & Invariants

> **ĐỌC KỸ TRƯỚC KHI SỬA BẤT KỲ FILE PYTHON NÀO TRONG REPO NÀY!**

## 1. Ràng buộc phần cứng & môi trường chạy NAS (BẤT BIẾN)

Backend [`backend/nas_api_server.py`](backend/nas_api_server.py) và các script quản trị NAS chạy trực tiếp trên **Chainedbox L1 Pro (RK3328 ARM64, Debian 9 Stretch, 1GB RAM, `/var/log` zram 49MB)**:
- **Python**: **3.5.3** (`/usr/bin/python3`)
- **Flask**: **1.1.4** (`Werkzeug 1.0.1`)
- **Pillow (PIL)**: **4.0.0**
- **Địa chỉ NAS**: LAN `192.168.100.254` / Tailscale `100.90.135.102` (`/opt/nas_api_server.py`, systemd unit `nas_api.service`)

### Danh sách tính năng Python 3.6+ / Flask 2.0+ CẤM DÙNG trong `backend/nas_api_server.py`:
1. **CẤM f-strings (`f"..."`)**, **CẤM variable type annotations (`x: int = 1`)**, **CẤM walrus (`:=`)**, **CẤM dấu gạch dưới trong số (`1_000_000`)**.
2. **CẤM `capture_output=True`, `text=True`, `encoding=...`, `errors=...` trong `subprocess.run` / `Popen` / `check_output`**. Luôn dùng `stdout=subprocess.PIPE, stderr=subprocess.PIPE` và `.decode("utf-8", errors="ignore")`.
3. **CẤM truyền `bytes` vào `json.loads(...)`** — trong Python 3.5.3, `json.loads()` chỉ nhận `str`! Luôn `.decode("utf-8", errors="ignore")` trước.
4. **CẤM `with os.scandir(...)` hoặc `.close()` trên `os.scandir`** — trong Python 3.5.3 `os.scandir` không phải context manager. Hãy dùng `os.listdir(...)`.
5. **CẤM `import secrets`, `dataclasses`, `contextvars`, `zoneinfo`, `tomllib`**. Dùng `random.SystemRandom().choice` hoặc `os.urandom`.
6. **CẤM `m[0]`, `m[1]` trên `re` Match object** — trong Python 3.5.3 `SRE_Match` không subscriptable; bắt buộc dùng `m.group(0)`, `m.group(1)`.
7. **CẤM `fromisoformat`, `isoformat(timespec=...)`, `monotonic_ns`, `time_ns`, `removeprefix`, `removesuffix`, `isascii`, `random.choices`, `shutil.copytree(..., dirs_exist_ok=True)`**.
8. **CẤM tham số Flask 2.0+ trong `send_file`**: không dùng `max_age=`, `download_name=`, `etag=`. Trong Flask 1.1.4 phải dùng `cache_timeout=`, `attachment_filename=`, `add_etags=` hoặc gán trực tiếp header `Cache-Control`.
9. **CẤM ghi spam log tần suất cao vào `/var/log/nas_api.log`** vì `/var/log` nằm trên phân vùng zram chỉ 49MB và luôn giữ `log.propagate = False`.

## 2. Kiểm tra bắt buộc trước khi kết thúc task

- Chạy `python -m pytest tests/ -v` (đảm bảo toàn bộ bài test trong `tests/test_audit_20261026_fixes.py` PASS 100%).
- Xem thêm chi tiết về quy chuẩn Android Kotlin/Compose/WorkManager tại [`DEVELOPMENT_GUIDELINES.md`](DEVELOPMENT_GUIDELINES.md) và [`CLAUDE.md`](CLAUDE.md).
