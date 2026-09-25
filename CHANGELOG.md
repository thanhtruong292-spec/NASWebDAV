# CHANGELOG — NASWebDAV Project

> Mọi thay đổi code đều phải được ghi vào đây. Format: `[YYYY-MM-DD] <Tóm tắt>`.
> Model đang dùng ghi ở mỗi entry. Xem hardrules.md §RULE-1 để biết quy tắc.

---

## [2026-09-25] Review độc lập P1 + R-hồi quy + P2 — sửa nhánh mất dữ liệu (main-fresh)

Review độc lập đối chiếu SPEC_PRODUCTION.md phát hiện 8 P1 mất dữ liệu, 6 hồi quy/thiếu sót sau sửa, 9 P2 chức năng. Sửa theo đúng thứ tự báo cáo, mỗi mục commit riêng.

### Đợt P1 — nhánh mất dữ liệu (8/8)
- **P1-1** (`5e1c2c1`): `WebDavManager.verifyBackupContent()` mới — HEAD size + strong ETag → GET `If-Match` toàn nội dung → hash-stream so với nguồn → HEAD recheck ETag. `AutoBackupWorker` dùng thay logic 1MB/size+ETag cũ. File nén (gzip đổi byte) luôn giữ nguồn.
- **P1-2** (`ecfb86d`): `getFullSha256PhoneStream` chỉ HTTP 200, đúng byte-count, từ chối 206; survivor dọn trùng so full-hash thật với hash đã verify (thay `!= null`).
- **P1-3** (`3459082`): bỏ fallback DELETE vĩnh viễn khi MOVE trash fail ở `FileBrowserViewModel`, `BatchOperationWorker`, `SmartToolsViewModel` — giữ file + log.
- **P1-4** (`a6f4a6f`): `renameFile`/`copyFile` mặc định `Overwrite: F`; batch COPY/MOVE xung đột 412 → tên duy nhất + timestamp.
- **P1-5** (`cdbc244`): backend chỉ dọn file 0-byte khớp pattern tạm app + đủ tuổi; thư mục rỗng tạm/cũ; giữ file user (`.nomedia`, `empty.txt`...).
- **P1-6** (`04c13f5`): trash retention dùng `max(mtime, ctime)` thay vì `mtime` (rename vào trash cập nhật ctime).
- **P1-7** (`c3bacf9`): giữ phiên screen-record khi lỗi (timeout/finish-fail/failRecording), chỉ hủy khi user chủ động.
- **P1-8** (`aea7298` + `d24e4e9`): SMB publish nguyên tử bằng `rename(..., false)`, giữ staged khi lỗi; `api_ytdlp_download` gọi `_find_ytdlp_bin()` + 503 rõ.

### Đợt R — hồi quy + thiếu sót sau sửa (6/6)
- **R1** (`4ba63d2`): `retainSpoolOnDestroy` — `onDestroy` không xóa spool khi giữ phiên lỗi; thêm `discardSession()` cho user chủ động bỏ.
- **R2** (`9596065`): pattern temp chặt (suffix/prefix/delimited — `chapter.part1.txt` không còn khớp); thư mục chỉ xóa khi tên temp app-managed (bỏ nhánh "rỗng >24h").
- **R3** (`c5cc886`): victim yêu cầu ETag strong + MOVE gửi `If-Match` + verify full-hash trash đích sau MOVE.
- **R4** (`4065f11`): tách `deletePermanently()` khỏi `deleteFile()`; UI trong trash gọi đúng hàm (hết MOVE-về-chính-nó).
- **R5** (`ec712b4`): tên trash chứa hash parent (`A/photo.jpg` ≠ `B/photo.jpg`); batch trash 412 → retry tên duy nhất + metadata đúng.
- **R6** (`9596065`): `screen_record` block thumbnail có TTL 2h + heartbeat mỗi segment; app chết thì tự mở lại.

### Đợt P2 — chức năng tồn đọng (9/9)
- **P2-1+P2-2** (`312727f`): skip backup yêu cầu đúng đích `__id` + HEAD size + 1MB-hash khớp; 412 phân biệt không-đổi (success) / đã-sửa (phiên bản `__v<ts>` mới).
- **P2-3→P2-7** (`6cbff82`): DocumentsProvider gắn `Authorization` trực tiếp; queue luôn continuation khi còn pending; `createFolder` đúng thư mục duyệt + refresh; restore thiếu metadata báo lỗi không MOVE; background refresh cập nhật `fileList`.
- **P2-8** (`108b623` + `64b8641` + `11b8137`): `files_cache.accountKey` (DB v18) + scope toàn bộ query browser/search/photos/duplicate + xóa cache đổi phiên (drain đồng bộ, không coroutine lẻ gây flaky test).
- **P2-9** (`986e1e8`): `api_unzip` ánh xạ relative WebDAV path + containment, giữ tương thích absolute.

### Fix bảo mật kèm theo
- `a8c7210` + `ec39df2`: `/api/search` thiếu `@requires_auth` (lộ cấu trúc file NAS cho LAN không auth) — bọc sau định nghĩa + patch `app.view_functions` (decorator trực tiếp gây `NameError` crash-loop, đã cứu). Verify live: no-auth → 401.
- `ab5b770`: validate whitelist IP/CIDR khi load từ disk.

### Verify
- Backend pytest: 115 passed / 10 skipped. Android unit: DatabaseDao 3/3, BackupContent 8/8, FullContent 5/5, SMB 2/2 — 0 failure.
- `compileDebugKotlin` + `assembleDebug` BUILD SUCCESSFUL (`app-debug.apk` ~38MB).
- NAS deploy qua Tailscale (LAN vật lý 192.168.100.254 không thông): service `active`, 0 traceback.
- Chưa nghiệm thu production-ready: cần cron 1–2 chu kỳ + test APK thực tế.
- **Model**: agentgw-gpt-5.6-sol

---

## [2026-09-23] Livestream / Log-Guard — đóng 4 lỗi P2 còn tồn tại sau 7fb3373

### backend/nas_api_server.py (deploy `/opt/nas_api_server.py`)
- **P2-1 (process-group gap on stop):** nút dừng + "dừng tất cả" giờ dùng
  `_livestream_kill_pid` (killpg whole process group do `start_new_session=True`)
  và giữ `status="stopping"` + `_kill_ts` để watchdog tiếp tục theo dõi group đến
  khi chết. Thêm orphaned-child sweep khi cha chết nhưng group vẫn còn tiến trình.
  - **Guard bổ sung:** sweep chỉ chạy khi `_pgid == int(pid)` (chính PGID của job),
    tránh killpg nhầm process group khác (tái sử dụng PGID số) → tự sát service.
- **P2-2 (định danh file):** `job_id` (ms + random) tính sớm và nhúng vào
  `output_template`, `file_stem`, `direct_output_file` → 2 job cùng giây (URL khác
  nhau) có tên file không trùng.
- **P2-3 (parser TikTok):** fallback chỉ đọc `LiveRoom` của target room (parse JSON,
  không regex quét toàn SIGI_STATE object) → không leak status phòng khác
  (`extra.roomId=9999` đang live không làm báo nhầm live).

### scripts/nas_log_guard.sh (deploy `/usr/local/bin/nas_log_guard.sh` + cài `/etc/cron.hourly/nas_log_guard`)
- **P2-4 (backup trước truncate):** copy log sang HDD và verify size > 0 **trước**
  khi truncate (trước đây truncate trước nên backup luôn rỗng 0–160 byte). Backup
  mới ~13.7 MB. Giải phóng zram `/var/log` từ 100% → ~39%.

### Tests
- Thêm 7 regression test (P2-2/P2-3 + strengthen fallback). Toàn bộ suite: 99 passed.
- **Model**: agentgw-gpt-5.6-sol

## [2026-04-20] Fix Memory Leak — LeakCanary "1 leaks at AndroidComposeView.legacyTextIn..."

### Nguyên nhân
`TextInputServiceAndroid` (Compose internal) giữ reference đến `CursorAnchorInfoController` → chain qua `LayoutNode` → `PopupLayout` (đã `onDetachedFromWindow`) → **leak 224 kB / 5130 objects**.

Xảy ra khi bất kỳ `ModalBottomSheet` bị dismiss trong khi có `TextField` đang active (IME focus).

### Fix — CommonStates.kt: `NasModalBottomSheet` wrapper
- Tạo `NasModalBottomSheet` composable tập trung trong `ui/components/CommonStates.kt`
- Tự động gọi `focusManager.clearFocus(force = true)` TRƯỚC mọi `onDismissRequest`
- Support đầy đủ: `sheetState`, `containerColor`, `scrimColor`, `dragHandle`, `content`

### Áp dụng cho tất cả 14 ModalBottomSheet trong app
- `Dialogs.kt`: **5** sheets migrated
- `BrowserScreen.kt`: **5** sheets migrated
- `MainMenuScreen.kt`: **4** sheets migrated

- **Model**: Gemini 2.5 Pro

## [2026-04-20] Fix ping loop — timeout 2s + exponential backoff

### WebDavManager.kt
- TCP ping timeout: **5000ms → 2000ms** (LAN chỉ cần ~1-5ms, fail nhanh hơn)
- Log FAIL: `Log.e` (kèm full stacktrace) → `Log.w` (chỉ message ngắn) — ping fail là bình thường khi WiFi reconnect, không phải crash

### WebDavViewModel.kt (ping loop)
- Thêm `consecutiveFails` counter + exponential backoff:
  - Thành công: ping mỗi **3s** (không đổi)
  - Fail 1-2 lần: chờ **3s** (short retry)
  - Fail 3-5 lần: chờ **10s** (WiFi đang reconnect)
  - Fail 6+ lần: chờ **30s** (NAS offline, tránh spam)
- Trước đây: fail 5s timeout + retry ngay sau 3s = log spam mỗi 8s

**Nguyên nhân log**: WiFi phone reconnect sau sleep → route chưa ổn định → TCP timeout. Firewall và nginx hoàn toàn bình thường.

- **Model**: Gemini 2.5 Pro

## [2026-04-20] Dedup Nhật ký hệ thống + fix Memory Leak PopupLayout

### SystemLogger Dedup (Utils.kt)
- Thêm cơ chế dedup: cùng `module|message` trong vòng **5 phút** → tăng `repeatCount` thay vì insert entry mới
- Cache in-memory `ConcurrentHashMap<String, Pair<Long, Long>>` (key = `module|message`)
- UI hiển thị badge **×N** màu tương ứng type (xanh/cam/đỏ) khi `repeatCount > 1`

### Database.kt (v12 → v13)
- `SystemLog` thêm field `repeatCount: Int = 1`
- `LogDao` thêm `insertLogReturnId()` trả `Long` và `incrementRepeatCount(id, now)`
- Migration `MIGRATION_12_13`: `ALTER TABLE system_logs ADD COLUMN repeatCount INTEGER NOT NULL DEFAULT 1`

### DuplicateScanWorker.kt
- Xóa `db.logDao().insertLog()` thừa trong `AutoDuplicateScanWorker` — trước đây ghi **2 log cùng lúc** cho mỗi lần dọn rác định kỳ

### Dialogs.kt — Memory Leak Fix
- `SystemLogDialog`: thêm `val focusManager = LocalFocusManager.current`
- `onDismissRequest`: gọi `focusManager.clearFocus(force = true)` trước khi `onDismiss()`
- Giảm `PopupLayout` leak do `TextInputServiceAndroid` không được cleanup khi dismiss `ModalBottomSheet`

- **Model**: Gemini 2.5 Pro

## [2026-04-20] Thêm /api/ping endpoint vào nas_api_server.py

- **File**: `/opt/nas_api_server.py` (NAS, trước @app.route("/api/status"))
- **Thay đổi**: Thêm lightweight `GET /api/ping` → trả `{"status":"ok","ts":<unix_ts>}`, **không cần auth**
- **Lý do**: `/api/ping` bị 500 vì endpoint chưa tồn tại. Dùng để test connectivity nhanh
- **Nguyên nhân log cũ**: `SocketTimeoutException` lúc 00:30 là APK cũ (HTTP OPTIONS 5s timeout) chưa update, không phải iptables
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Color-coded Daily Report — ngưỡng màu cho CPU/RAM/Temp

- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MonitoringChartsScreen.kt` (lines 562–592)
- **Thay đổi**: Thêm `c1` (cột trái) màu động + nâng cấp `c2` cột phải với ngưỡng 3 mức
  - **CPU/RAM**: 🟢 xanh <70% / 🟡 vàng 70–89% / 🔴 đỏ ≥90%
  - **CPU Temp**: 🟢 xanh <65°C / 🟡 vàng 65–74°C / 🔴 đỏ ≥75°C
  - **HDD Temp**: 🟢 xanh <40°C / 🟡 vàng 40–49°C / 🔴 đỏ ≥50°C
- **Lý do**: Trước đây chỉ `c2` có màu và nhiều thông số dùng màu trắng mặc định
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Fix °C thiếu trong Daily Report (CPU/HDD temp)

- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MonitoringChartsScreen.kt` (lines 566–569)
- **Thay đổi**: `"%.1f"` → `"%.1f°C"` ở 4 field: `cpuTempAvg`, `cpuTempPeak`, `hddTempAvg`, `hddTempPeak`
- **Lý do**: Giá trị nhiệt độ hiển thị số không có đơn vị (ví dụ `41,2` thay vì `41,2°C`)
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Fix Ping — TCP Socket thay vì HTTP OPTIONS

- **File**: `app/src/main/java/com/nas/naswebdav/WebDavManager.kt` (lines 280–306)
- **Thay đổi**: `checkPingServer()` dùng `java.net.Socket.connect()` thay vì HTTP OPTIONS đến WebDAV
- **Lý do**: OPTIONS request gồm TCP + nginx xử lý + auth → ~300ms. TCP socket chỉ đo RTT thật → <10ms trên LAN
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Fix SMART — Thêm °C cho nhiệt độ HDD

- **File**: `app/src/main/java/com/nas/naswebdav/ui/dialogs/Dialogs.kt` (line 215)
- **Thay đổi**: Guard UI: nếu giá trị `temperature` có chữ số nhưng không có `°` → thêm `°C`
- **Lý do**: Server trả về số nguyên `38` thay vì `38°C`, UI không có fallback
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Khôi phục font Samsung One — NasTheme

- **File**: `app/src/main/java/com/nas/naswebdav/MainActivity.kt` (lines 50, 313)
- **Thay đổi**: Thay `MaterialTheme {}` bằng `NasTheme {}` trong `setContent`
- **Lý do**: `AppTypography` đã có `SamsungOneFontFamily` nhưng không được áp dụng vì `MaterialTheme` không nhận `typography = AppTypography`. `NasTheme` wrap đúng cả `colorScheme` + `typography`
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Setup Neural Memory & Hardrules

- **File**: `.agents/hardrules.md` [NEW]
- **File**: `CHANGELOG.md` [NEW]
- **Thay đổi**: Tạo hệ thống hard rules và neural-memory MCP cho dự án
- **Lý do**: Đảm bảo AI nhớ ngữ cảnh giữa các phiên, ghi lại phát hiện quan trọng
- **Model**: Gemini 2.5 Pro

---

## [2026-04-20] Fix iptables NAS — Restore Whitelist Policy

- **File**: NAS `/etc/iptables/rules.v4`
- **Thay đổi**: Xóa 2 DROP rules thừa cho `192.168.100.93`, rebuild iptables whitelist-only
- **Lý do**: Phone bị block hoàn toàn vì DROP rules xuất hiện trước ACCEPT rules
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Fix IndexOutOfBoundsException — ImageViewerScreen

- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MediaScreens.kt` (line 222)
- **Thay đổi**: `pagerState.currentPage` → `.coerceIn(0, imageFiles.size - 1)` trước khi truy cập list
- **Lý do**: Crash khi list ảnh thay đổi giữa recomposition (19 phần tử nhưng index = 19)
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Expose Ping Error — Hiện thị lỗi đăng nhập thực

- **File**: `app/src/main/java/com/nas/naswebdav/WebDavManager.kt` (lines 275–302)
- **File**: `app/src/main/java/com/nas/naswebdav/WebDavViewModel.kt` (line ~1460)
- **Thay đổi**: `checkPingServer()` lưu exception message vào `lastPingError`, hiển thị thay vì hardcode
- **Lý do**: Không biết lý do thực sự của login failure (SocketTimeoutException vs ConnectException)
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Fix Build — Nhiều Unresolved References

- **File**: `app/src/main/java/com/nas/naswebdav/ui/dialogs/Dialogs.kt`
  - `activeLivestreams` type inference → explicit `List<LivestreamJob>` + map nested class
- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MainMenuScreen.kt`
  - `activeStreams` type inference → explicit + map
  - `cancelConnect()` stub added
- **File**: `app/src/main/java/com/nas/naswebdav/WebDavViewModel.kt`
  - `FolderStats` data class + `folderStats`/`isFolderStatsLoading` properties
  - `fetchFolderStats()` stub
  - `cancelConnect()` function
- **Lý do**: Build failures từ unresolved references sau refactor
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Fix Login — pingClient 10s Timeout

- **File**: `app/src/main/java/com/nas/naswebdav/WebDavManager.kt` (lines 289–292)
- **Thay đổi**: `checkPingServer` dùng `pingClient` (10s) thay vì `optimizedClient` (60s)
- **Lý do**: Login mất 60 giây mới fail, UX rất kém
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Fix Literal \\n — Error Dialog Newline

- **File**: `app/src/main/java/com/nas/naswebdav/WebDavViewModel.kt` (line ~1460)
- **Thay đổi**: `"\\n"` → `"\n"` trong error message
- **Lý do**: Hiện thị `\n` thay vì xuống dòng trong dialog lỗi
- **Model**: Gemini 2.5 Pro

---

## [2026-04-18] Fix network_security_config.xml Missing

- **File**: `app/src/main/res/xml/network_security_config.xml` [NEW]
- **Thay đổi**: Tạo file bị thiếu gây AAPT build failure
- **Lý do**: AndroidManifest.xml reference file không tồn tại
- **Model**: Gemini 2.5 Pro

---

## [2026-04-18] UI — Accordion Tương hỗ (OMV / Monitor / SystemLog)

- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MainMenuScreen.kt`
- **Thay đổi**: Auto-collapse các section ngoài màn hình chính để tối ưu hiển thị
- **Lý do**: Không thể mở đồng thời 2 section detail (OMV + Monitor, v.v.)
- **Model**: Gemini 2.5 Pro
