# NASWebDAV — Danh sách lỗi cần sửa & hướng dẫn

> Ngày review: 2026-06-03 · Phạm vi: `nas_api_server.py` (12,732 dòng) + 26 file `*.kt`
> Mức hoàn thiện: Server ~95% · App Kotlin ~92%. Logic chức năng cơ bản đã hoàn thiện, còn vài lỗi logic ẩn cần vá.

Quy ước ưu tiên: 🔴 CRITICAL (sửa ngay) · 🟠 HIGH · 🟡 MEDIUM.

---

## 🔴 CRITICAL

### C1 — `safe_run_cmd` không thực thi lệnh, luôn trả về `None`
- **File:** `nas_api_server.py`
- **Hàm:** `safe_run_cmd` (dòng ~1074)
- **Triệu chứng:** Hàm chỉ chạy vòng lặp validate args (dòng 1080–1088) rồi **rơi ra ngoài hàm không có `subprocess.run` và không `return`** → trả về `None`.
  Lưu ý: `subprocess.run` ở dòng 1060–1066 thuộc *hàm phía trên*, KHÔNG thuộc `safe_run_cmd`.
- **Hậu quả:** Mọi caller bị crash `AttributeError: 'NoneType' has no attribute 'strip'`:
  - dòng ~1588 (`get_fan_info`)
  - dòng ~10378 (`_fan_controller_watchdog`)
  - dòng ~4079/4082 (`_read_dmesg_recent`)
  → Trạng thái quạt + đọc dmesg/disk-health **chết** trên thực tế.
- **Hướng dẫn sửa:** Thêm phần thực thi sau vòng `for` validate:
  ```python
  def safe_run_cmd(cmd_list, timeout=10, merge_stderr=False):
      allowed_flags = {...}
      for idx, arg in enumerate(cmd_list):
          str_arg = str(arg)
          if not str_arg or not str_arg.strip():
              log.warning("safe_run_cmd: tham so lenh khong hop le: %s", arg)
              return ""
          if idx > 0 and str_arg.startswith("-") and str_arg not in allowed_flags:
              log.warning("safe_run_cmd: flag khong duoc phep (%s)", str_arg)
              return ""
      # >>> THÊM PHẦN NÀY <<<
      try:
          stderr_dest = subprocess.STDOUT if merge_stderr else subprocess.PIPE
          result = subprocess.run(
              cmd_list, shell=False,
              stdout=subprocess.PIPE, stderr=stderr_dest,
              timeout=timeout,
          )
          return result.stdout.decode("utf-8", errors="replace").strip()
      except subprocess.TimeoutExpired:
          log.warning("safe_run_cmd: qua han (%ds): %s", timeout, cmd_list[:3])
          return ""
      except Exception as e:
          log.error("safe_run_cmd: that bai: %s — %s", cmd_list[:3], e)
          return ""
  ```
- **Kiểm thử:** Gọi `safe_run_cmd(["echo", "ok"])` phải trả `"ok"`; mở dashboard và xác nhận trạng thái quạt + disk-health hiển thị, không còn traceback `'NoneType'`.

### C2 — `_cleanup_runtime_tmp_artifacts` dùng biến `now` chưa định nghĩa
- **File:** `nas_api_server.py`
- **Hàm:** `_cleanup_runtime_tmp_artifacts` (dòng ~352), lỗi tại dòng 366.
- **Triệu chứng:** `if (now - os.path.getmtime(path)) < max_age:` — `now` không tồn tại trong hàm này (chỉ định nghĩa cục bộ ở hàm `_cleanup_stale_job_tmp` khác). Raise `NameError`, bị `except: continue` nuốt → **không xóa gì cả**.
- **Hậu quả:** Rác `_MEI*` (PyInstaller), `ffmpeg*`, `flv_repair*` trong `/tmp` (tmpfs/RAM) tích tụ trên thiết bị 1–2 GB → nguy cơ OOM. Hàm được gọi lúc khởi động (~12580), sau mỗi job yt-dlp (~12567), và lúc shutdown.
- **Hướng dẫn sửa:** Thêm `now = time.time()` đầu hàm:
  ```python
  def _cleanup_runtime_tmp_artifacts(max_age_minutes=30):
      deleted = 0
      now = time.time()          # <<< THÊM
      max_age = max_age_minutes * 60
      ...
  ```
- **Kiểm thử:** Tạo file `/tmp/ffmpeg_test` cũ >30 phút (chỉnh mtime), gọi hàm, file phải bị xóa và `deleted` tăng.

---

## 🟠 HIGH

### H1 — Bypass auth qua `request.remote_addr` giả mạo
- **File:** `nas_api_server.py` · **Hàm:** `requires_auth` (dòng ~956–960), `_ip_in_whitelist`.
- **Vấn đề:** Sau nginx/proxy, `remote_addr` là IP proxy chứ không phải client; không validate `X-Forwarded-For` với trusted-proxy. Nếu IP proxy/container nằm trong subnet whitelist → bypass toàn bộ auth. Server bind `0.0.0.0:5050`, an ninh phụ thuộc hoàn toàn iptables (best-effort).
- **Hướng dẫn sửa:** Chỉ tin `X-Forwarded-For` khi request đến từ proxy tin cậy đã cấu hình; nếu không có proxy, dùng `remote_addr` trực tiếp và KHÔNG whitelist dải địa chỉ của proxy. Ghi rõ danh sách trusted-proxy trong config.

### H2 — So sánh credential plaintext, không constant-time
- **File:** `nas_api_server.py` · **Hàm:** `check_auth` (dòng ~937–938).
- **Vấn đề:** `username == WEBDAV_USER and password == WEBDAV_PASS` — lộ timing side-channel; credential lưu plaintext trong `/opt/nas_api.conf`.
- **Hướng dẫn sửa:**
  ```python
  import hmac
  ok = hmac.compare_digest(username, WEBDAV_USER) & hmac.compare_digest(password, WEBDAV_PASS)
  ```
  (so sánh cả 2 để tránh short-circuit timing).

### H3 — Zip-slip / archive traversal ở `/api/file/unzip`
- **File:** `nas_api_server.py` · dòng ~7317–7344.
- **Vấn đề:** Đường dẫn archive được validate, nhưng giải nén (`unzip -o`, `unrar x`, `tar xf`, `7z x`) ghi vào `dest_dir` mà KHÔNG kiểm tra entry chứa `../` → ghi ra ngoài WebDAV root.
- **Hướng dẫn sửa:** Liệt kê entry trước khi giải nén, từ chối nếu `os.path.realpath(os.path.join(dest_dir, member))` không nằm dưới `dest_dir`; hoặc giải nén vào thư mục tạm rồi move từng entry đã validate.

### H4 — `requires_auth` mở SQLite mới mỗi request (HDD wear)
- **File:** `nas_api_server.py` · dòng ~967–981.
- **Vấn đề:** Mỗi request authenticated `sqlite3.connect(DB_PATH)` + `SELECT FROM authorized_ips`; `DB_PATH` nằm trên HDD. Với poller ~4–5s, đây là đọc/ghi liên tục lên HDD đang quay, đi ngược mục tiêu spindown/giảm wear.
- **Hướng dẫn sửa:** Cache danh sách IP tin cậy trong RAM (dùng pattern `runtime_state` đã có), refresh định kỳ / khi có thay đổi thay vì query mỗi request.

### H5 — `_validate_file_path` dùng `startswith` không có separator
- **File:** `nas_api_server.py` · dòng ~1046–1051.
- **Vấn đề:** `return real.startswith(os.path.realpath(WEBDAV_FILE_ROOT))` — `/srv/...-data-evil` lọt qua root `/srv/...-data`.
- **Hướng dẫn sửa:** Dùng logic đúng đã có sẵn ở `_resolve_webdav_request_path` (dòng ~7620):
  ```python
  base = os.path.realpath(WEBDAV_FILE_ROOT)
  return real == base or real.startswith(base + os.sep)
  ```

### H6 — ViewModel tạo bằng constructor thô (mất state khi config change)
- **File:** `app/src/main/java/com/nas/naswebdav/MainActivity.kt:179`
- **Vấn đề:** `viewModel = WebDavViewModel(...)` không qua `ViewModelProvider`/`by viewModels()`. Mỗi config change tạo VM mới → mất hết in-flight state (upload, scan, StateFlow, polling), orphan `viewModelScope`. Chỉ an toàn nếu khóa orientation (phụ thuộc ngầm).
- **Hướng dẫn sửa:** Dùng `by viewModels { factory }` với `ViewModelProvider.Factory` truyền dependency; hoặc nếu cố tình khóa orientation thì ghi rõ ràng trong manifest + comment.

### H7 — `WebDavManager` là object global, race credential
- **File:** `app/src/main/java/com/nas/naswebdav/WebDavManager.kt` (lines ~77–81, `connect()` ~203–213).
- **Vấn đề:** `currentUser/currentPass/currentBaseUrl` là state global mutable, được interceptor preemptive-auth đọc lúc request. Nhiều Worker + `NasDocumentProvider` + UI gọi `connect()` đồng thời → `connect()` tới host B giữa chừng có thể chèn nhầm credential vào request host A đang bay.
- **Hướng dẫn sửa:** Chuyển sang per-session/instance scope, hoặc truyền credential theo từng call (OkHttp `Request.tag`/authenticator riêng) thay vì global mutable.

### H8 — `createDocument` luôn trả success path
- **File:** `app/src/main/java/com/nas/naswebdav/NasDocumentProvider.kt:314-327`
- **Vấn đề:** Khi WebDAV timeout/fail trong `runBlocking`, exception bị nuốt nhưng `newId` vẫn trả về → DocumentsUI tưởng tạo file/folder thành công.
- **Hướng dẫn sửa:** Bắt lỗi và `throw FileNotFoundException`/`null` đúng hợp đồng `DocumentsProvider.createDocument` khi thao tác thất bại.

---

## 🟡 MEDIUM

### Python (`nas_api_server.py`)
- **M1 — `api_smart_organize_execute` (dòng ~8403–8443):** `os.walk` toàn HDD + `shutil.move` đồng bộ trong request, không có `_background_heavy_work_allowed()` gate → block worker (chỉ 6 thread) nhiều phút, hammer HDD. → Đẩy sang background job có gate + giới hạn thời gian/số lượng.
- **M2 — `broadcast()` (dòng ~581–584):** set `clients` không có lock; 2 thread broadcast đồng thời + `clients.remove(c)` → `KeyError`. → Thêm lock hoặc dùng `discard()`.
- **M3 — `_iter_file_range` (dòng ~7641–7650):** giữ file handle mở qua generator; client ngắt giữa chừng → rò FD. → Bọc `try/finally` đóng handle theo vòng đời response.
- **M4 — `except: pass` rải rác** (293, 309, 328, 519, 987–989, 1040–1047...): nuốt lỗi DB/disk. → Log lỗi ở mức cần thiết.
- **M5 — Shutdown:** `_force_free_port` dùng `fuser -k` có thể giết nhầm process; `os._exit(0)` bỏ qua atexit/cleanup. → Cân nhắc shutdown an toàn hơn.

### Kotlin
- **M6 — Polling chồng nhau:** `listenToLocalNasApi` (`/api/status` ~5s) + `launchDashboardRealtimeScheduler` (VM:~3787) + WebSocket cùng chạy trên pool 4 kết nối ("NAS yếu") → bão hòa khi mở dashboard. → Gộp sau một scheduler tier điều phối chung.
- **M7 — `LocalVideoProxy.kt:63-82`:** tự đóng sau 60s idle + accept-loop thoát ở exception đầu tiên → seek sau pause dài fail. `nasConnection.disconnect()` chỉ chạy ở path GET-2xx (193–195), HEAD/error rò connection. → Giữ proxy sống theo phiên phát, đóng connection trong `finally`.
- **M8 — Deprecated API:** `getParcelableExtra`/`getParcelableArrayListExtra` không-typed (MainActivity.kt:238,242, deprecated API 33+); `enterPictureInPictureMode` ở `MainActivity.onUserLeaveHint:101` thiếu try/catch + check `FEATURE_PICTURE_IN_PICTURE` (VideoPlayerActivity:80-92 làm đúng). → Migrate sang overload typed + guard PiP.
- **M9 — Offline-Sync chết:** `sync_queue` + `SyncAction` + `SyncActionDao` (Database.kt:298-319) khai báo nhưng không Worker nào dùng. → Hoàn thiện hoặc xóa.

---

## Maintainability (không phải bug, nên cải thiện dần)
- `nas_api_server.py`: 1 module 12.7k dòng, ~250 hàm, ~120 route, ~12 thread nền, side-effect lúc import (`init_db()`, `_apply_iptables_for_whitelist()`) → khó test. **Đề xuất:** tách package `auth / hardware / media / storage / daemons`, bỏ side-effect import-time.
- Kotlin: business-logic (OkHttp + parse JSON) nhúng thẳng trong Composable ở các file UI 1.8k–5k dòng (BrowserScreen, MainMenuScreen). → Đẩy về ViewModel.
- Mojibake: comment/string tiếng Việt trong server bị double-encode (UTF-8 đọc như Latin-1) → khó đọc.

---

## Thứ tự ưu tiên đề xuất
1. C1, C2 — fix nhỏ, an toàn, chặn crash + leak RAM.
2. H1/H4 — cứng hóa auth + bỏ SQLite-mỗi-request.
3. H3 + H5 — vá path traversal.
4. H6, H8 — Kotlin lifecycle/contract.
5. Dọn M9 (code chết) + gộp polling M6.
