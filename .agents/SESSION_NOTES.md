# Session Notes — NASWebDAV (Android + Python NAS API)

Tổng kết phiên làm việc Vietnamese-language với Claude Code, tháng 5/2026.
Branch chính: `claude/festive-lehmann-419d28`. Build target Samsung Galaxy S21 Ultra (Android 15, targetSdk=35). NAS: Chainedbox L1 Pro (Rockchip + OMV 6 Arrakis).

## Phạm vi đã làm

### A. Livestream TikTok — fix dedup & playback

**Bug**: Watcher TikTok đẻ trùng 3-4 job/user; file `.flv` direct-curl không play được.

**Root cause**:
- `_tiktok_watch_user_has_recording()` chỉ match qua `info["url"]`. Khi nhánh TikTok direct-FLV ghi đè `live_url` thành URL FLV CDN, dedup token `@user/live` không còn → mỗi tick watcher đẻ thêm job mới.
- yt-dlp thiếu `--remux-video mp4` → lưu raw `.ts` mislabel.
- Direct curl path không validate `Content-Type` → ghi HLS playlist vào file `.flv`.

**Fix** (`nas_api_server.py`):
- Thêm field `watch_username` vào `_livestream_jobs[job_id]`; `/api/livestream/record` nhận body `watch_username`; `/api/livestream/status` expose lại.
- Dedup function match qua `watch_username` trước, fallback URL.
- Cooldown `_tiktok_watch_recent_starts` 90s/user trong watchdog.
- `job_id` đổi sang `live_<ms>_<uuid6>` để tránh collision khi 2 user cùng giây.
- yt-dlp lệnh thêm `--remux-video mp4` + `--postprocessor-args "ffmpeg:-movflags +faststart"`.
- Direct FLV: HEAD check thêm `%{content_type}`, nếu không phải FLV → fallback yt-dlp.
- `_remux_flv_to_mp4` thêm pass 3 với `-bsf:v h264_mp4toannexb`; 3 pass fail → rename `.broken` thay vì xoá.

**Fix Android** (`WebDavViewModel.kt`, `LivestreamMonitorWorker.kt`, `LivestreamDiscoveryWorker.kt` — NEW):
- `LivestreamJob` thêm field `watchUsername`.
- `startLivestreamRecord` tự extract username TikTok (regex `tiktok\.com/@([\w.-]+)`), POST kèm `watch_username`, **tự gọi `addTikTokLiveWatchUser`** nếu user chưa trong list theo dõi.
- `LivestreamMonitorWorker` forward `watch_username` từ `/api/livestream/status` qua progress data (`OUT_WATCH_USER`).
- `LivestreamDiscoveryWorker` MỚI (PeriodicWork 15p): poll status, enqueue Monitor cho job auto-record từ NAS watcher → notification chạy ngầm hiện cho mọi job kể cả khi app bị kill.
- `WebDavViewModel.syncLivestreamStateWithServer` đọc `watch_username` từ response.
- Panel "TÁC VỤ NỀN" hiển thị `TikTok • @username` thay vì `TikTok • <jobid 6 chữ>` + dòng dưới `@username` thay tên file `tiktok_xxx_direct.flv`.

**One-shot script** (`flv_repair.sh`): chạy SSH-side, remux 350 file `.flv` cũ → đã xử lý 12 OK + 39 broken + 80 trùng-mp4 xóa + 208 zero-byte xóa.

### B. Worker crashes — `MissingForegroundServiceTypeException`

**Bug**: Commit `17f7cae` "Harden background workers against crashes" regression — bỏ tham số `FOREGROUND_SERVICE_TYPE_DATA_SYNC` khỏi `ForegroundInfo`. targetSdk=35 + manifest đã khai báo `foregroundServiceType="dataSync"` → khi worker `setForeground()` ném exception, crash giữa chừng.

**Fix**: Thêm lại API Q+ ForegroundInfo overload ở 5 nơi:
- `NasWorker.makeForegroundInfo()` (`DuplicateScanWorker.kt:795`)
- `LivestreamMonitorWorker.kt:331` (build function)
- `LongRunningApiWorker.kt:84`
- `BatchOperationWorker.kt:130`
- `StreamPipeWorker.kt:107`

Pattern: `if (SDK_INT >= Q) ForegroundInfo(id, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, notif)`.

**Tier-1 fix bổ sung trong AutoBackupWorker**:
- Skip `processedFilesCount++` drift (intentional behavior, không đổi).
- File 0-byte: thêm `skippedCount++` để dialog tổng kết khớp `total = success + skipped + failed`.

### C. Server-side cron worker thêm

**`_clean_empty_files_and_dirs(root)`** (`nas_api_server.py`): quét đệ quy WEBDAV_FILE_ROOT, xoá file 0-byte + thư mục rỗng (bỏ qua `.trash`, `.nas_meta`, `.thumbnails`, `.git`, `.recycle`, `@eaDir`, dotfile như `.gitkeep`).
Cron step 4b mới: gọi mỗi 24h, push alert `EMPTY_CLEANED`.
Update: cleanup now also deletes old broken livestream FLV markers (`.flv.broken*` / `.broken.flv`) after 7 days, keeping fresh broken files for short-term debugging.

### D. UI — Login screen WoL + Restart

**Yêu cầu**: 2 nút bật nguồn + restart trên login screen để dùng được khi NAS lỗi không login.

**Fix**:
- `WebDavViewModel.sendPowerCommandFromLogin(ip, user, pass, endpoint, onResult)` MỚI: build URL trực tiếp từ IP form, không phụ thuộc `webDavManager.currentBaseUrl`. Dùng `fastApiClient.newBuilder()` + Basic auth thủ công.
- LoginScreen: 2 OutlinedButton "Bật nguồn (WoL)" + "Khởi động lại" dưới nút Đăng nhập. Reuse `WolDialog` + `RebootConfirmDialog`. MAC lưu `nas_prefs/mac_address`.
- Tailscale: WoL KHÔNG hoạt động qua Tailscale (broadcast L2 không tunnel), Restart OK (HTTP qua tunnel bình thường).

### E. UI — Biểu đồ giám sát per-point color

**Yêu cầu**: Đoạn nối 2 điểm trong line chart nhiệt độ/tài nguyên tô màu theo giá trị từng điểm (xanh→vàng→đỏ), không phải 1 màu chung theo giá trị cuối.

**Fix** (`MonitoringChartsScreen.kt`):
- `Series` thêm field `colorOf: ((Float) -> Color)?`.
- Tab "Nhiệt độ"/"Tài nguyên": gán `colorOf = ::cpuTempStatusColor` / `::hddTempStatusColor` / `::percentStatusColor`. Tab "Mạng": giữ màu cố định.
- Render canvas: per-segment fill trapezoid + line, dùng `lerpColor(c1, c2, 0.5f)` trung bình màu 2 đầu đoạn.
- Touch tooltip, halo, popup bg, legend, value strip dưới đều theo `colorAt(s, v)`.
- Đổi `_StatusYellow` từ `0xFFFFA726` (Material Orange 400, mờ) → `0xFFFFC400` (Amber A700, rực).

### F. UI — Panel inline mở độc quyền (chỉ 1 panel mở 1 lúc)

**Yêu cầu**: Trong MainMenu, OMV/Tasks/Chart panels không cùng mở 1 lúc.

**Fix** (`MainMenuScreen.kt`):
- Singleton object `ExclusivePanelState { val current = mutableStateOf<String?>(null) }`.
- 3 panel mỗi cái dùng `val expanded = current.value == "id"`, toggle: `ExclusivePanelState.toggle("id")`.
- Chart card chip giờ (1h/6h/24h): bấm chip force `current.value = "chart"` + fetch metrics.

### G. UI — Dialog Livestream redesign (mở rộng panel + auto-scroll)

**Yêu cầu**: Theo dõi TikTok + Thời gian loại trừ + Đang ghi hình → 3 collapsible section trong cùng ModalBottomSheet, mở 1 panel = đóng panel khác. Click ngoài KHÔNG đóng. Spacing giảm.

**Fix** (`Dialogs.kt`):
- Singleton `LivestreamPanelState` tương tự ExclusivePanelState (id: `"watchlist" | "exclude" | "active"`).
- ModalBottomSheet dùng `rememberModalBottomSheetState(skipPartiallyExpanded = true)`.
- Khi `expandedPanel` đổi: `sheetState.expand()` → delay 220ms → `dialogScrollState.animateScrollTo(maxValue)` để content panel mới lộ hoàn toàn.
- Spacers giảm 12-18dp → 6-10dp toàn dialog. `heightIn(max=720dp)` để chứa nhiều job.
- Tabs "Theo dõi user" / "Ghi link live": container `0xFF1A1A1A` khi unselected (không transparent), background `accentColor.copy(alpha=0.55f)` khi selected, text **trắng + bold** + 13sp.
- LazyColumn → Column.forEach (LazyColumn nested trong verticalScroll = crash).
- Job card: padding 16→12dp, fontSize duration 18→16sp.

### H. UI — File browser: bỏ icon ⋮, thêm red dot, sort menu

**Yêu cầu**: 
- Bỏ icon ⋮ góc phải file thumbnail. Long-press = menu.
- Red dot ở vị trí cũ cho file chưa xem; biến mất khi user mở.
- Thêm nút sort cạnh "Chọn file".

**Fix** (`BrowserScreen.kt`):
- Bỏ Icon(MoreVert) ở TopEnd của file card.
- `combinedClickable.onLongClick`: long-press LUÔN mở menu cho cả file+folder (selection mode entry qua nút toolbar).
- Red dot indicator (Box CircleShape đỏ viền trắng) hiện ở TopEnd khi `isNewFile = file.path !in viewedFiles`. State lưu `SharedPreferences("browser_prefs")/viewed_files` (Set<String>).
- Sort button (Icons.Default.Sort) cạnh "Chọn file" → DropdownMenu 6 mode: Tên A↔Z, Ngày mới/cũ trước, Kích thước lớn/nhỏ trước. Lưu `browser_prefs/file_sort`. Folders luôn lên đầu.
- `displayedFiles` `derivedStateOf`: filter search + sort.

### I. File Properties Dialog

**Yêu cầu**: Long-press file → "Thuộc tính" → ModalBottomSheet kiểu Windows Properties.

**Fix** (`Dialogs.kt` thêm `FilePropertiesDialog`):
- 7 field: Tên (selectable), Đường dẫn WebDAV (selectable monospace), Loại (MIME), Phần mở rộng, Kích thước (formatted + raw bytes), Sửa lần cuối, Hash MD5.
- Hash lookup qua `FileDao.getFileByPath()` MỚI (added trong `Database.kt`): trả `partialHash`/`fullHash`/`imageFingerprint`.
- `BrowserScreen` thêm menu item "Thuộc tính" giữa "Đổi tên" và "Xóa tệp".

### J. Duplicate scan — gating + throttling

**Yêu cầu**: 
1. Bỏ logic auto-chạy duplicate scan khi manual sync.
2. Chỉ chạy 3AM khi NAS rảnh; bận → tạm dừng đến khi rảnh; 1x/tuần.
3. Dialog scan không tự pop-up lại sau "Thu nhỏ".
4. Dialog kết quả không đóng được khi click ngoài, chỉ đóng khi đã xử lý hết file trùng.
5. Đồng bộ UI bottom-sheet (giống livestream).

**Bug critical (BUG: dialog pop-up lại sau Thu nhỏ)**:
- `WebDavViewModel.kt:1791-1797`: collector `combine()` mỗi tick progress force `isScanningDuplicates = true` → user bấm Thu nhỏ thì 30ms sau dialog auto-bật lại.

**Fix**:
- Bỏ `isScanningDuplicates = true` khỏi collector, chỉ giữ `isWorkerRunning = true` (cho chip "Thu nhỏ"). Dialog chỉ mở khi user chủ động.
- `triggerManualBackup`: bỏ enqueue DuplicateScanWorker, chỉ chạy AutoBackup.
- Endpoint `/api/system/idle` MỚI (nas_api_server.py): trả `{idle, reason, cpu_pct, load_avg_1min, cores, mem_free_mb, mem_pct, recording_streams}`. Idle = CPU<50% & load1<cores*0.7 & mem_free>150MB & recording_streams==0.
- `AutoDuplicateScanWorker.doWork()`:
  - **Gate 1 — Khung giờ**: chỉ chạy `hour in 2..5`. Ngoài → `Result.retry()`.
  - **Gate 2 — NAS rảnh start**: poll `/api/system/idle` tối đa 5 lần × 5p (25 phút). Vẫn bận → `Result.retry()`.
  - **Live throttle**: parallel `throttleJob` poll mỗi 60s; NAS bận → set `DuplicateProgressState.isPaused = true` + flag `wasAutoPaused=true`; idle lại → auto-resume (chỉ nếu là tự auto-pause, không can thiệp user pause).
  - `throttleJob.cancel()` ở try/catch finalizer.
- Dialog scan progress → ModalBottomSheet với `skipPartiallyExpanded=true`. 3 action: Huỷ / Tạm dừng / Thu nhỏ.
- Dialog kết quả: `DialogProperties(dismissOnBackPress=false, dismissOnClickOutside=false)`. Nút "Hoàn tất" CHỈ hiện khi `duplicateFilesList.isEmpty()`; còn file thì hiện hint "Còn N tệp — tick chọn & xóa để đóng".

### K. Fan control bug (vừa fix)

**Bug**: `/api/fan/control` tham chiếu `STATUS_CACHE` (uppercase) không tồn tại → mỗi mode switch trả HTTP 500 `name 'STATUS_CACHE' is not defined`. Hardware vẫn đổi đúng vì `systemctl` + `echo duty_cycle` chạy trước exception, nhưng cache không cập nhật → `/api/status` hiển thị mode cũ tới khi cron 60s.

**Fix**: Đổi tất cả `STATUS_CACHE['...'] = x` → `with _cache_lock: _status_cache['...'] = x`. Tested OK (off → duty=0, on → duty=10000).

## State còn pending / chưa làm

- Phương án "WoL via Tailscale relay" — user chọn option A (giữ nguyên, chỉ LAN).
- AutoBackup/Log/Docker bottom-sheet → inline panel: user chọn chỉ làm inline (chart/OMV/tasks), KHÔNG đụng modal khác.
- `.broken.flv`/`.flv.broken` files trên NAS: đã thêm cron tự xóa file cũ hơn 7 ngày trong cleanup 24h.

## Deployment

- Server (`nas_api_server.py`): deploy qua `.agents/workflows/deploy-nas-server.md`. NAS Tailscale IP `100.90.135.102`. `scp` → `cp /root → /opt` → kill PID file → `nohup python3 /root/nas_api_server.py`.
- Android: build `gradlew.bat assembleDebug` (no-daemon), cài qua `adb install -r` đến port wireless debugging do user cung cấp.

## File mới

- `app/src/main/java/com/nas/naswebdav/LivestreamDiscoveryWorker.kt`
- `flv_repair.sh` (one-shot remux script)
- `.agents/SESSION_NOTES.md` (file này)
