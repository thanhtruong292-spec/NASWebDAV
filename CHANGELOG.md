# CHANGELOG — NASWebDAV Project

> Mọi thay đổi code đều phải được ghi vào đây. Format: `[YYYY-MM-DD] <Tóm tắt>`.
> Model đang dùng ghi ở mỗi entry. Xem hardrules.md §RULE-1 để biết quy tắc.

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
