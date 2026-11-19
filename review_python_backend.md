# Python Backend Review

**Dự án:** NASWebDAV — Backend Python (`backend/nas_api_server.py`, `backend/rust_bridge.py`)  
**Môi trường thực thi mục tiêu:** Chainedbox L1 Pro (RK3328 ARM64, Debian 9 Stretch, Python 3.5.3, Flask 1.1.4, Pillow 4.0.0, ZRAM 49MB `/var/log`)  
**Quy chuẩn áp dụng:** `AGENTS.md` (NAS Hardware Invariants), Ponytail (Lazy Senior Dev Mode), silent-failure-hunter, security-audit.  
**Kết quả test suite:** 277 passed, 6 skipped (`python -m pytest tests/ -v`).

---

## 🔴 Must Fix (Lỗi cú pháp Python 3.5, lỗ hổng bảo mật, crash)

### 1. Lỗi thụt lề phá vỡ Lock trong `_init_rust_bridge` gây Race Condition đa luồng
* **Vị trí:** [`backend/rust_bridge.py:32-46`](file:///d:/Android/NASWebDAV/backend/rust_bridge.py#L32-L46)
* **Hiện trạng:**
  ```python
  def _init_rust_bridge():
      global _RUST_LIB, _RUST_AVAILABLE
      if _RUST_LIB is not None:
          return _RUST_AVAILABLE

      with _RUST_INIT_LOCK:
          if _RUST_LIB is not None:
              return _RUST_AVAILABLE

          # Các đường dẫn tìm kiếm thư viện
      candidates = [
          ...
      ]
      for p in candidates:
          ...
  ```
* **Phân tích rủi ro:**
  Dòng 36 là comment thụt lề 8 space, nhưng dòng 37 `candidates = [` bị thụt lề về 4 space. Do đó, khối `with _RUST_INIT_LOCK:` kết thúc ngay lập tức sau dòng 34! Toàn bộ vòng lặp tìm file, gọi `ctypes.CDLL(p)` và gán các `argtypes`/`restype` cho C-ABI (từ dòng 37 đến 80) chạy **hoàn toàn nằm ngoài lock**.
  Khi nhiều luồng API (hoặc luồng băm nền) cùng lúc gọi các hàm `compute_fast_sha256`, `compute_fast_md5_1mb`, hoặc `compute_batch_md5_1mb` lúc server vừa khởi động, nhiều luồng sẽ cùng nạp và ghi đè `_RUST_LIB`, dẫn tới race condition hoặc crash process C-ABI.
* **Giải pháp:**
  Thụt lề toàn bộ khối từ `candidates = [` đến hết hàm vào bên trong `with _RUST_INIT_LOCK:`.

---

### 2. Lỗ hổng Logic Xoá Nhầm Thư Mục Gốc trong `api_disk_trash_batch`
* **Vị trí:** [`backend/nas_api_server.py:13505-13531`](file:///d:/Android/NASWebDAV/backend/nas_api_server.py#L13505-L13531)
* **Hiện trạng:**
  ```python
  local_path = os.path.realpath(os.path.join(webdav_root, rel_path))
  root_real = os.path.realpath(webdav_root)
  ...
  if local_path != root_real and not local_path.startswith(root_real + os.sep):
      errors.append({"path": webdav_path, "error": "Path traversal"})
      continue
  ...
  filename = os.path.basename(local_path)
  dest_path = os.path.join(trash_dir, filename)
  ...
  os.rename(local_path, dest_path)
  ```
* **Phân tích rủi ro:**
  Điều kiện `if local_path != root_real and not local_path.startswith(root_real + os.sep):` cho phép trường hợp `local_path == root_real` lọt qua (vì `local_path != root_real` là `False`).
  Nếu client gửi `webdav_path = "/webdav/"` hoặc `"/webdav/."`, `rel_path` sẽ rỗng, `local_path` trỏ thẳng vào chính thư mục gốc `webdav_root`. Khi đó, `os.rename(local_path, dest_path)` sẽ cố gắng đổi tên/di chuyển **toàn bộ thư mục gốc WebDAV** vào `.trash`!
* **Giải pháp:**
  Đối với thao tác xoá/di chuyển, đối tượng bắt buộc phải là con nằm trong root:
  ```python
  if not local_path.startswith(root_real + os.sep):
      errors.append({"path": webdav_path, "error": "Invalid target or path traversal"})
      continue
  ```

---

### 3. Nguy cơ Deadlock vĩnh viễn trong `schedule_fts_rebuild`
* **Vị trí:** [`backend/rust_bridge.py:377-390`](file:///d:/Android/NASWebDAV/backend/rust_bridge.py#L377-L390)
* **Hiện trạng:**
  ```python
  if not _FTS_REBUILD_LOCK.acquire(False):
      return False

  def _worker():
      try:
          cnt = build_fts_index(db_path)
          ...
      finally:
          _FTS_REBUILD_LOCK.release()

  t = threading.Thread(target=_worker, daemon=True)
  t.start()
  return True
  ```
* **Phân tích rủi ro:**
  `_FTS_REBUILD_LOCK` được acquire non-blocking trước khi khởi tạo luồng. Nếu `t.start()` ném ngoại lệ (ví dụ `RuntimeError: can't start new thread` do chạm giới hạn luồng của OS hoặc cạn bộ nhớ trên NAS 1GB RAM), luồng `_worker` sẽ không bao giờ được chạy. Khối `finally` bên trong `_worker` không bao giờ được gọi, khiến `_FTS_REBUILD_LOCK` bị khoá vĩnh viễn. Mọi lệnh rebuild FTS sau đó đều bị bỏ qua.
* **Giải pháp:**
  Bọc `t.start()` trong `try...except`, nếu thất bại phải gọi `_FTS_REBUILD_LOCK.release()`:
  ```python
  try:
      t = threading.Thread(target=_worker, daemon=True)
      t.start()
      return True
  except Exception as e:
      _FTS_REBUILD_LOCK.release()
      log.warning("Khong khoi tao duoc luong FTS rebuild: %s", e)
      return False
  ```

---

## 🟡 Should Fix (Silent failures, thiếu lock/race condition, code smell)

### 1. Thiếu cờ Cache Negative Lookup trong `_init_rust_bridge` gây I/O dư thừa
* **Vị trí:** [`backend/rust_bridge.py:27-80`](file:///d:/Android/NASWebDAV/backend/rust_bridge.py#L27-L80)
* **Hiện trạng:**
  Trong `_init_indexer_lib()` (dòng 246), có biến cờ `_INDEXER_LIB_CHECKED = True` để nhớ rằng đã kiểm tra thư viện trên đĩa rồi (dù có hay không). Tuy nhiên, `_init_rust_bridge()` không có cờ tương tự.
* **Ảnh hưởng:**
  Nếu hệ thống chưa cài đặt thư viện Rust `nas_hasher`, `_RUST_LIB` sẽ giữ giá trị `None`. Mỗi khi `compute_fast_sha256` hoặc `compute_fast_md5_1mb` được gọi, hàm lại kiểm tra 4 đường dẫn bằng `os.path.isfile(p)` trên ổ đĩa. Khi ứng dụng quét/băm hàng nghìn tệp, việc này lặp lại hàng nghìn syscall kiểm tra tệp không cần thiết.
* **Khắc phục:**
  Thêm cờ toàn cục `_RUST_CHECKED = False` và đặt thành `True` sau lượt quét đầu tiên.

---

### 2. Nguy cơ Zombie Process (`<defunct>`) trong `_RustIndexerStream.close()` & `stream_fast_index()`
* **Vị trí:** [`backend/rust_bridge.py:191-196, 226-230`](file:///d:/Android/NASWebDAV/backend/rust_bridge.py#L191-L196)
* **Hiện trạng:**
  ```python
  if self._proc.poll() is None:
      try:
          self._proc.terminate()
      except Exception:
          pass
  ```
* **Ảnh hưởng:**
  Trên Linux (Debian 9 Stretch), khi tiến trình con bị gửi tín hiệu SIGTERM (`terminate()`) nhưng tiến trình cha không gọi `.wait(timeout=...)` hoặc `.poll()`, tiến trình con chuyển sang trạng thái Zombie (`defunct`) và giữ lại slot trong PID table của kernel cho đến khi tiến trình cha kết thúc.
* **Khắc phục:**
  Thêm `self._proc.wait(timeout=0.5)` sau khi `terminate()`.

---

### 3. Mutate `services_restarted` sau `jsonify()` không có tác dụng trong `api_backup_restore`
* **Vị trí:** [`backend/nas_api_server.py:10919-10950`](file:///d:/Android/NASWebDAV/backend/nas_api_server.py#L10919-L10950)
* **Hiện trạng:**
  ```python
  _result = jsonify({
      "status": "restored",
      ...
      "services_restarted": services_restarted,
      ...
  })
  ...
  services_restarted.append("nas_api (scheduled after response)")
  ```
* **Ảnh hưởng:**
  `_result` được serialize ngay lập tức khi gọi `jsonify(...)`. Việc `append` vào mảng `services_restarted` sau đó hoàn toàn không cập nhật vào `_result`. JSON trả về cho client luôn có `"services_restarted": []`.
* **Khắc phục:**
  Append các service dự kiến restart vào `services_restarted` TRƯỚC KHI gọi `jsonify(...)`.

---

### 4. Crash `AttributeError` khi nhận Request rỗng trong `api_cron_trash_clean`
* **Vị trí:** [`backend/nas_api_server.py:11833-11834`](file:///d:/Android/NASWebDAV/backend/nas_api_server.py#L11833-L11834)
* **Hiện trạng:**
  ```python
  data = request.get_json(force=True)
  max_days = int(data.get("max_age_days", 30))
  ```
* **Ảnh hưởng:**
  Nếu client gửi request không có JSON body hoặc body rỗng, `data` là `None`. Dòng tiếp theo `data.get(...)` sẽ ném ngoại lệ `AttributeError: 'NoneType' object has no attribute 'get'` dẫn đến HTTP 500 lỗi.
* **Khắc phục:**
  Đổi thành: `data = request.get_json(force=True) or {}`.

---

### 5. Khối `except ... pass` nuốt lỗi ngầm trong các tác vụ nền
* **Vị trí:** 307 khối `except: pass` / `except Exception: pass` trong `nas_api_server.py` (tập trung tại `_livestream_watchdog`: 16 khối, `_social_worker`: 10 khối, `_auto_resource_reclaimer_thread`: 9 khối).
* **Ảnh hưởng:**
  Ngoại lệ bị nuốt hoàn toàn mà không có log debug, gây khó khăn cho việc chẩn đoán lỗi khi tiến trình nền dừng đột ngột hoặc stream bị drop trên thực tế.
* **Khắc phục:**
  Với các khối nền quan trọng, tối thiểu nên ghi `log.debug(...)` hoặc log có context ngắn gọn.

---

## 🟢 Nice to Have (Refactor, cleanup)

1. **Bổ sung `rust_bridge.py` vào kiểm thử tự động Python 3.5.3:**  
   Trong [`tests/test_audit_20261026_fixes.py`](file:///d:/Android/NASWebDAV/tests/test_audit_20261026_fixes.py#L153), danh sách `targets` hiện chỉ có `SERVER_PATH`, `check_log.py` và `scripts/*.py`. Nên thêm `backend/rust_bridge.py` và `backend/nas_work_coordinator.py` để đảm bảo CI tự động bảo vệ toàn bộ backend.
2. **Loại bỏ Import Fallback `urlparse` cũ:**  
   Dòng 1161-1164 còn khối fallback `from urlparse import urlparse` của Python 2. Do backend cố định chạy Python 3.5.3+, có thể đơn giản hóa dùng trực tiếp `from urllib.parse import urlparse`.
3. **Cấu trúc File Quá Lớn:**  
   `nas_api_server.py` có 20.989 dòng code. Về lâu dài, có thể cân nhắc chia nhỏ thành các Flask Blueprints chuyên biệt (`auth_routes.py`, `disk_routes.py`, `livestream_routes.py`, `media_routes.py`) để dễ bảo trì, nhưng cần cẩn trọng để không phá vỡ các global state của Python 3.5.

---

## ✅ Tốt (Các điểm đã tuân thủ tốt)

1. **Tuyệt đối tuân thủ Python 3.5.3 Syntax & Stdlib (Bất biến phần cứng):**
   * Hoàn toàn không có f-strings (`f"..."`), không có type annotations, không có walrus operator (`:=`), không có numeric underscores (`1_000_000`).
   * Toàn bộ 108 vị trí gọi `subprocess` đều tuân thủ nghiêm ngặt: không dùng `capture_output=True`, `text=True`, `encoding=...`, `errors=...`. Tất cả đều sử dụng `stdout=subprocess.PIPE, stderr=subprocess.PIPE` và `.decode("utf-8", errors="ignore")`.
   * Hoàn toàn không sử dụng `with os.scandir(...)` hay `.close()` trên `os.scandir`.
   * Không import bất kỳ module nào thuộc Python 3.6+ (`secrets`, `dataclasses`, `contextvars`, `zoneinfo`, `tomllib`).
   * 100% regex Match object sử dụng `.group(...)`, không dùng subscripting `m[0]`.
   * Các hàm `send_file` đều tuân thủ Flask 1.1.4 (`attachment_filename=`, `as_attachment=`, `cache_timeout=`).
2. **Kiểm soát Log & ZRAM 49MB an toàn:**
   * `log.propagate = False` được thiết lập triệt để ở đầu server.
   * `RotatingFileHandler` áp dụng đúng chuẩn giới hạn 1MB x 2 file backup (tối đa 3MB) trên phân vùng zram 49MB `/var/log`.
3. **Bảo mật Endpoint & Chống Giả mạo IP:**
   * 135/137 route được bảo vệ chặt chẽ bằng `@requires_auth` hoặc `@requires_admin`. Endpoint `/api/search` được bọc auth qua runtime patching `app.view_functions['api_fast_search'] = requires_auth(api_fast_search)`.
   * Endpoint duy nhất mở không auth là `/api/share/dl/<token>` (phục vụ chia sẻ file tạm có token ngẫu nhiên 24 hex), có validate regex và boundary path traversal chặt chẽ.
   * Cơ chế `_request_client_ip()` chỉ đọc `X-Forwarded-For` và `X-Real-IP` khi kết nối trực tiếp đến từ Trusted Proxy, chống tuyệt đối tấn công giả mạo Header để vượt firewall.
4. **Bảo mật Cơ sở dữ liệu (Zero SQL Injection):**
   * 100% câu truy vấn SQL đều dùng parameterized query `?`.
   * Các bảng động đều được kiểm tra chặt chẽ qua whitelist `_SQLITE_STATE_TABLES`.
5. **Độ ổn định của Test Suite:**
   * 277/277 unit tests và regression tests vượt qua 100% (`277 passed, 6 skipped`).
