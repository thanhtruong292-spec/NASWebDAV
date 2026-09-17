# DEVELOPMENT_GUIDELINES.md

> **Mục đích**: Tài liệu này là "hàng rào" bảo vệ các tối ưu và sửa lỗi đã
> xây trong app NASWebDAV (B1–B36 + P1–P8 + Q1–Q8 + R1–R7). Trước khi chỉnh
> sửa bất kỳ Composable / ViewModel / Worker nào, hãy đọc phần liên quan để
> tránh làm hỏng các bất biến đã thiết lập.
>
> **Tham chiếu gốc**: Chi tiết kỹ thuật của từng fix nằm trong
> [`CHANGELOG_AUDIT_FIX.md`](./CHANGELOG_AUDIT_FIX.md) (§1 → §12.10).

---

## 0. Triết lý

1. **Không có "tối ưu nhỏ" — chỉ có "tối ưu có đo"**. Trước khi claim gì nhanh
   hơn, cần profile bằng Layout Inspector / Android Profiler / Baseline
   Profile. Nếu không đo được thì KHÔNG merge optimization.
2. **Đã sửa = không được quay lại anti-pattern**. File này liệt kê các
   anti-pattern đã từng gây bug. PR nào tái sử dụng chúng sẽ bị reject.
3. **Mọi thay đổi Worker/notification/SystemLogger phải đi kèm test case
   thủ công** (đề cập ở §7).

---

## 1. Timeline các đợt tối ưu (context cho người đọc mới)

| Đợt | Phạm vi | Kết quả chính |
|-----|---------|---------------|
| B1–B36 | UI/UX cơ bản | Material3 tokens thay hardcode color, fontSize ≥ 12sp, touch target ≥ 40dp, EmptyState/Skeleton common composable, derivedStateOf cho filter/sort, LazyColumn keys, contentDescription đầy đủ |
| P1–P8 | Performance & leak | LaunchedEffect key đúng, hoist SharedPreferences, Context leak audit, Regex compile hot path, File/InputStream `.use{}` |
| Q1–Q8 | Architecture & threading | I/O khỏi composable, remember(filter), OkHttp singleton 5 pool, Room off main thread, Bitmap recycle, stable lambda, `mutableStateOf private set` |
| R1–R7 | Background & Worker | Scope/dispatcher audit, Worker retry + foreground, WebSocket reconnect, OkHttp timeout 5 tier, exception propagation, Worker stability against OS stops (`setBackoffCriteria` + `isStopped` → `Result.retry()`) |

---

## 2. DO — Bất biến PHẢI giữ

### 2.1. Compose UI

**DO**: Luôn dùng `MaterialTheme.colorScheme.*` / `MaterialTheme.typography.*`
thay cho hardcode `Color.White` / `Color.Black` / `12.sp`. Lý do: dark mode +
accessibility tự động theo theme.

**DO**: Mọi clickable / toggleable có kích thước < 40dp PHẢI bọc
`Modifier.minimumInteractiveComponentSize()`. Lý do: A11y — touch target
Material3 yêu cầu ≥ 40dp.

**DO**: `LazyColumn` / `LazyVerticalGrid` / `LazyRow` PHẢI có `key = { it.id }`
(hoặc path / unique field). Lý do: scroll position giữ khi data update, tránh
recompose toàn bộ list.

**DO**: Filter / sort trong Composable PHẢI wrap bằng `remember(key) { ... }`
hoặc `derivedStateOf { ... }`. Lý do: không filter lại mỗi recomposition.

**DO**: `collectAsState(...)` và `produceState(...)` đi kèm `key1`, `key2`
đầy đủ. Lý do: khi dependency thay đổi mà không có key, state sẽ dùng giá trị
stale.

**DO**: Lambda callback truyền xuống child phải là function reference
(`onClick = ::handleClick`) hoặc wrap bằng `remember { { ... } }`. Lý do:
lambda inline tạo instance mới mỗi recomposition → child bị recompose vô ích.

### 2.2. ViewModel / State

**DO**: `mutableStateOf(...)` public phải dùng `var ... by mutableStateOf(...)
  private set`. UI chỉ đọc, setter chỉ ViewModel gọi. Ngoại lệ (rất hiếm):
  khi 2-way binding form cần `TextFieldValue`, vẫn phải có validation path.

**DO**: Mọi coroutine trong ViewModel launch trên `viewModelScope`. Job nào
cần cancel khi user rời screen thì giữ `Job?` và `job?.cancel()` trong
`onCleared()` hoặc khi re-trigger.

**DO**: I/O và Room query PHẢI chạy trên `Dispatchers.IO`. Composable
`LaunchedEffect { withContext(Dispatchers.IO) { ... } }`.

### 2.3. WorkManager

**DO**: Mọi `OneTimeWorkRequestBuilder` / `PeriodicWorkRequestBuilder` có
`setConstraints(...)` PHẢI đi kèm `setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
15L, TimeUnit.SECONDS)` (hoặc 30s/60s tùy tần suất). Lý do: khi constraint vi
phạm mid-run (rút sạc, mất Wi-Fi) hoặc task fail, chu kỳ kế không phải đợi
24h/7 ngày.

**DO**: Worker chạy lâu (> 10s) PHẢI gọi `setForeground(...)` đầu doWork() với
notification rõ tiến trình. Dùng `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.

**DO**: Trong `catch (e: Exception)` của Worker, kiểm tra `isStopped ||
  e is CancellationException` TRƯỚC các catch khác, trả `Result.retry()`.
  Lý do: hệ thống stop ≠ fatal error.

**DO**: Notification "đang chạy" (ongoing) phải được thay bằng notification
"hoàn tất" (`setOngoing(false).setAutoCancel(true)`) trong cả success path VÀ
fail path. KHÔNG dùng `cancel(notifId)` vì user sẽ không biết kết quả.

**DO**: Cặp `SystemLogger.log` START/END cho mọi Worker ≥ 10s (AutoBackup,
DuplicateScan, Livestream, StreamPipe, BatchOperation). Mẫu:
```kotlin
SystemLogger.log("INFO", "AutoBackup", "Bắt đầu chu kỳ (attempt=${runAttemptCount+1}, ...)")
// ... work ...
SystemLogger.log("SUCCESS"/"ERROR", "AutoBackup", "Kết quả: ...")
```

### 2.4. Network (OkHttp)

**DO**: Reuse 5 singleton client trong `NasApplication.kt`:
- `sharedHttpClient` — catch-all WebDAV/API
- `fastApiClient` — polling nhanh < 30s
- `thumbnailApiClient` — grid thumbnail
- `longRunningApiClient` — 15 phút (unzip/organize)
- `videoStreamingClient` — infinite (stream pipe)

KHÔNG `OkHttpClient.Builder().build()` mới theo request. Lý do: mỗi client
tạo connection pool riêng → leak FD + consume RAM.

**DO**: Request có body PHẢI `.use {}` response để đóng body. Request không
body PHẢI `.close()` ngay sau đọc header.

### 2.5. Resource management

**DO**: Mọi `InputStream`, `OutputStream`, `Cursor`, `Reader`, `Writer`,
`FileInputStream`, `FileOutputStream` BẮT BUỘC dùng `.use {}`.

**DO**: `Bitmap` load từ file → khi không dùng nữa gọi `.recycle()`. Tốt nhất
dùng `try/finally { bitmap.recycle() }`.

**DO**: SharedPreferences đọc trong Composable phải hoist lên biến
`remember { prefs.getString(...) }` hoặc đọc trong ViewModel, KHÔNG gọi
`prefs.getString()` trực tiếp trong body composable.

---

## 3. DON'T — Anti-pattern TUYỆT ĐỐI KHÔNG dùng

| Anti-pattern | Tại sao sai | Thay bằng |
|--------------|-------------|-----------|
| `LaunchedEffect(Unit)` / `LaunchedEffect(true)` | Effect không rerun khi dep đổi | `LaunchedEffect(actualDep)` |
| `Color.White` / `Color.Black` literal trong UI | Vỡ dark mode | `MaterialTheme.colorScheme.surface/onSurface` |
| Filter/sort không có `remember` | Filter mỗi recomposition | `remember(list, query) { list.filter... }` |
| LazyColumn không có `key` | Lost scroll position, full recompose | `items(list, key = { it.id }) { ... }` |
| `Log.d(TAG, "%s".format(x))` trong hot path | String.format compile lại | Lazy log `if (BuildConfig.DEBUG) Log.d(...)` |
| `OkHttpClient.Builder().build()` ad-hoc | Leak FD, tạo pool mới | `NasApplication.instance.sharedHttpClient` |
| `inputStream.read()` không `.use{}` | Leak FD | `inputStream.use { it.read() }` |
| `withContext(Dispatchers.Main) { db.query() }` | ANR Main thread | `withContext(Dispatchers.IO) { db.query() }` |
| `GlobalScope.launch` | Leak lifecycle | `viewModelScope.launch` / `applicationScope.launch` |
| `try { ... } catch (e: Exception) { /* nuốt */ }` | Mất lỗi | Log `SystemLogger.log("ERROR", tag, e.message)` hoặc rethrow |
| Worker không có `setBackoffCriteria` khi có constraint | Đợi 24h khi constraint fail | Thêm `setBackoffCriteria(EXPONENTIAL, 15s)` |
| Worker catch → `Result.failure()` khi `isStopped=true` | Mất checkpoint, không retry | Kiểm tra `isStopped` → `Result.retry()` |
| `NotificationManagerCompat.cancel(notifId)` trong finally | User không biết kết quả | Show notification hoàn tất autoCancel |
| Unicode bullet `•`, `\u2022` trong text | Không localize được | Dùng `LevelFormat.BULLET` / UI icon |
| `ShadingType.SOLID` cho table docx | Render đen trên một số viewer | `ShadingType.CLEAR` |
| `var state by mutableStateOf(...)` public (không `private set`) | UI có thể ghi đè | `var state by mutableStateOf(...) private set` |
| Lambda mới inline mỗi recompose: `onClick = { vm.doX() }` | Recompose child thừa | `onClick = vm::doX` hoặc `remember { { vm.doX() } }` |
| Đọc `SharedPreferences` trực tiếp trong Composable body | Disk I/O mỗi recomposition | Hoist sang ViewModel hoặc `remember` |

---

## 4. Checklist review PR

Trước khi approve một PR chạm vào app, tick từng mục:

### 4.1. UI/Compose
- [ ] Không có `Color.White`/`Color.Black` literal mới.
- [ ] `LaunchedEffect` có key cụ thể (không `Unit`/`true`).
- [ ] LazyColumn mới có `key = { ... }`.
- [ ] Filter/sort trong composable có `remember(...)` hoặc `derivedStateOf`.
- [ ] Icon clickable < 40dp có `minimumInteractiveComponentSize()`.
- [ ] Icon interactive có `contentDescription`.
- [ ] Font size ≥ 12sp.

### 4.2. ViewModel/State
- [ ] `mutableStateOf` public là `private set`.
- [ ] I/O trong `withContext(Dispatchers.IO)`.
- [ ] Coroutine launch trên `viewModelScope` / `applicationScope` (không `GlobalScope`).
- [ ] Job cancel hợp lý khi re-trigger.

### 4.3. Worker (CRITICAL)
- [ ] `setForeground(...)` đầu `doWork()` nếu chạy > 10s.
- [ ] `setBackoffCriteria(EXPONENTIAL, 15–60s)` cho mọi enqueue.
- [ ] Catch block kiểm tra `isStopped || e is CancellationException` → `Result.retry()`.
- [ ] Notification có completion state (ongoing=false, autoCancel=true).
- [ ] `SystemLogger.log` START + END.
- [ ] WakeLock (nếu dùng) có `acquire(timeout)` và `release()` trong finally.

### 4.4. Network
- [ ] Dùng 5 singleton OkHttp (không new Builder).
- [ ] Response `.use {}` hoặc `.close()`.
- [ ] Timeout phù hợp tier (short/medium/long/stream).

### 4.5. Resource
- [ ] `InputStream`/`OutputStream`/`Cursor` dùng `.use {}`.
- [ ] `Bitmap` có `.recycle()`.
- [ ] SharedPreferences không đọc trực tiếp trong composable body.

### 4.6. Settings change audit
- [ ] Nếu PR thêm toggle cài đặt mới, có `SystemLogger.log("INFO", "Cài đặt", ...)` ghi lại hành động.
- [ ] KHÔNG log password / token / raw credential.

### 4.7. Verification
- [ ] Brace balance: chạy `python3 kt_brace_check.py` → OK trên tất cả file chạm.
- [ ] Build Debug: `./gradlew assembleDebug` pass.
- [ ] Manual smoke test: list scenario trong §7.

---

## 5. Kiến trúc file & conventions

### 5.1. Cấu trúc package
```
com.nas.naswebdav/
├── NasApplication.kt          # 5 OkHttp client + applicationScope
├── WebDavViewModel.kt         # VM chính (large)
├── DuplicateScanViewModel.kt  # VM quét trùng
├── TransferViewModel.kt       # VM transfer/autobackup pause
├── *Worker.kt                 # Background work (AutoBackup, DuplicateScan, Livestream, StreamPipe, BatchOp, LongRunningApi, OfflineSync, Fingerprint, IdleSpeedTest)
├── utils/
│   ├── SystemLogger.kt        # Ghi log ra Room
│   ├── ImageFingerprint.kt    # pHash
│   └── SecurePrefsHelper.kt   # EncryptedSharedPrefs
└── ui/
    ├── screens/*.kt
    ├── dialogs/*.kt
    └── theme/*.kt
```

### 5.2. Quy tắc đặt tên Worker

- `ManualXxxWorker`: user chủ động bấm. KHÔNG có constraint sạc/Wi-Fi.
- `AutoXxxWorker` + `PeriodicWorkRequestBuilder`: chu kỳ tự động. Có constraint
  sạc + UNMETERED. Bắt buộc có `setBackoffCriteria`.
- `OfflineXxxWorker`: worker consume action queue từ Room khi có mạng lại.
- `IdleXxxWorker`: chỉ chạy khi `setRequiresDeviceIdle(true)`.

### 5.3. Quy tắc SystemLogger level

| Level | Dùng khi |
|-------|----------|
| `INFO` | Bắt đầu process, setting change, info chung |
| `SUCCESS` | Hoàn tất có kết quả tích cực (n files backed up) |
| `WARNING` | Bất thường nhưng không fatal (isStopped retry, file skip) |
| `ERROR` | Fatal, task fail, exception không retry được |

---

## 6. Known gotchas (đã va phải, đừng va lại)

### 6.1. WorkManager constraint re-evaluation mid-run
Khi Worker đang chạy mà constraint vi phạm (rút sạc, mất Wi-Fi), WorkManager
gọi `onStopped()`. Coroutine trong Worker sẽ nhận `CancellationException`.
**Nếu catch generic `Exception` và trả `Result.failure()`** → mất checkpoint,
không retry. **Phải** kiểm tra `isStopped` trước.

### 6.2. `setForeground()` phải gọi TRONG doWork()
Gọi `setForeground()` ở `onBeforeWorkEnqueued` hoặc constructor là sai. Phải
gọi ngay đầu `doWork()` và bọc `try {} catch (_: Exception) {}` vì Android 14+
có thể throw `ForegroundServiceStartNotAllowedException`.

### 6.3. SharedPreferences read trên main thread gây ANR
Lần đầu mở app, `getSharedPreferences()` sẽ xfer toàn bộ XML từ disk. File
lớn (> 1000 keys) có thể block main thread 100-500ms. **Giải**: đọc trong
`LaunchedEffect { withContext(Dispatchers.IO) { prefs.getXxx() } }`.

### 6.4. Room query trên main thread
`@Query` trả `Flow<T>` thì an toàn (collect tự off main). Trả `T` trực tiếp
thì phải gọi trong `withContext(Dispatchers.IO)`. **Kiểm tra** bằng
`allowMainThreadQueries = false` trong Database builder (đã enforce).

### 6.5. Bitmap OOM khi load ảnh lớn
Luôn dùng `BitmapFactory.Options.inSampleSize` khi load từ file. Sau khi dùng
xong phải `.recycle()`. **Không dùng** `Glide.load(...)` trực tiếp lên
`Composable` — dùng `AsyncImage` của Coil với `remember { ImageRequest }`.

### 6.6. OkHttp response body leak
`response.body?.string()` đọc và đóng. Nhưng `response.body?.byteStream()` KHÔNG
tự đóng → phải `.use {}`. Tương tự `charStream()`, `source()`.

### 6.7. WebDAV URL với Unicode/space
Path chứa ký tự non-ASCII phải URL-encode bằng `Uri.encode(path, "/")` trước
khi nối base URL. KHÔNG encode toàn bộ URL vì sẽ escape cả `://` và `/`.

### 6.8. Android 14 FOREGROUND_SERVICE_TYPE_DATA_SYNC limit
Android 14+ giới hạn FGS `dataSync` 6 giờ/24h. Nếu AutoBackup cực dài (> 6h)
sẽ bị cắt. **Giảm thiểu**: chia batch nhỏ, mỗi batch là 1 Worker riêng, hoặc
dùng `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (Android 14+) nếu hợp.

### 6.9. OEM battery manager (Xiaomi/Oppo/Huawei)
Một số OEM ignore WhiteList Android. User phải vào Settings OEM bật
"Auto-launch" thủ công. **App không override được** — nên thêm dialog hướng
dẫn ở lần đầu enable AutoBackup.

### 6.10. Force-stop từ Settings
Android 3.1+ có "stopped state" — app bị force-stop không thể tự resume
WorkManager đến khi user mở lại app 1 lần. **Không phải bug** — giới hạn OS.

### 6.11. Samsung FreecessController (đông lạnh tiến trình)
Samsung One UI có hệ thống riêng `FreecessController` đông lạnh app khi
`uidIdle` (không tương tác vài phút). Log mẫu:
```
FreecessController: FZ : com.nas.naswebdav reason: LEV
FreecessController: com.nas.naswebdav state: Initial -> Frozen, Reason: uidIdle
```
Khi bị freeze, mọi coroutine/thread/alarm đều dừng cho đến khi user mở lại app.
**Giải pháp**: Hướng dẫn user thêm app vào "Ứng dụng không bao giờ ở chế độ ngủ"
(`OemBatteryHelper` đã xử lý).

### 6.12. SCHEDULE_EXACT_ALARM mất sau cài đặt lại
Android 12+ strip quyền `SCHEDULE_EXACT_ALARM` sau mỗi lần package update.
Log mẫu:
```
AlarmManager: Package com.nas.naswebdav lost permission to set exact alarms!
```
**Bắt buộc**: khai báo `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />`
trong `AndroidManifest.xml`. Đã thêm từ R8b.

---

## 7. Manual smoke test (chạy trước mỗi release)

Bất kỳ PR nào chạm `*Worker.kt` / `ui/screens/*.kt` / `WebDavViewModel.kt`
phải chạy các scenario sau:

### 7.1. AutoBackup lifecycle
1. Bật AutoBackup → cắm sạc + Wi-Fi → verify notification "đang chạy" xuất hiện.
2. Giữa chừng rút sạc → notification chuyển "tạm dừng".
3. Cắm sạc lại → Worker resume → notification "đang chạy" trở lại.
4. Hoàn tất → notification "hoàn tất" với count backed up / skipped / failed.
5. Mở SystemLog → thấy cặp START/END.

### 7.2. Livestream recording
1. Bắt đầu ghi live → notification ongoing + SystemLog START.
2. Kill app → mở lại → notification vẫn còn, polling tiếp.
3. Stream kết thúc → notification "Video đã lưu" + SystemLog SUCCESS.

### 7.3. DuplicateScan (manual trigger)
1. Bấm quét → notification + progress % trên thanh thông báo.
2. Xoay màn hình → progress không reset.
3. Lock màn hình 30s → unlock → progress tiếp tục.
4. Hoàn tất → badge "X cặp trùng tìm thấy".

### 7.4. Dark mode
1. Settings → Display → Dark Theme → toggle.
2. Mọi screen (Home, Browser, Monitoring, Media, SystemLog, Dialogs) phải
   chuyển theme mượt, không còn text trắng trên nền trắng.

### 7.5. Offline action queue
1. Tắt Wi-Fi + data.
2. Delete/move/rename 5 file trên NAS qua app → action đi vào Room queue.
3. Bật Wi-Fi → OfflineSyncWorker chạy → 5 action apply lên NAS.
4. SystemLog ghi đủ 5 SUCCESS hoặc ERROR.

### 7.6. Low-memory stress
1. Mở 10 ảnh preview liên tiếp → không OOM.
2. adb shell `am send-trim-memory <pkg> RUNNING_CRITICAL` → app không crash.

---

## 8. Khi thêm Worker mới

Template bắt buộc:

```kotlin
class MyNewWorker(appContext: Context, params: WorkerParameters) : NasWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try { setForeground(makeForegroundInfo("my_channel", "Tên task", 9xxx, "Đang chạy...")) } catch (_: Exception) {}
        SystemLogger.log("INFO", "MyTask", "Bắt đầu tiến trình (attempt=${runAttemptCount+1}).")
        try {
            // ... work ...
            // Show "hoàn tất" notification
            showDoneNotification(...)
            SystemLogger.log("SUCCESS", "MyTask", "Hoàn tất: ...")
            Result.success()
        } catch (e: Exception) {
            // System stop → retry
            if (isStopped || e is CancellationException) {
                SystemLogger.log("WARNING", "MyTask", "Hệ thống tạm dừng — sẽ tự resume.")
                return@withContext Result.retry()
            }
            // Transient error → retry
            if (e is SocketTimeoutException || e is ConnectException || e is UnknownHostException) {
                if (runAttemptCount < 3) return@withContext Result.retry()
            }
            // Fatal
            SystemLogger.log("ERROR", "MyTask", "Lỗi: ${e.message}")
            showFailNotification(e.message)
            Result.failure()
        }
    }
}
```

Enqueue template:
```kotlin
WorkManager.getInstance(context).enqueueUniqueWork(
    "MyWorkName",
    ExistingWorkPolicy.REPLACE,
    OneTimeWorkRequestBuilder<MyNewWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
        .build()
)
```

---

## 9. Khi thêm Composable mới

1. Wrap trong `MaterialTheme` token (không Color.White/Black).
2. Nếu có `LaunchedEffect`, xác định key cụ thể (không `Unit`).
3. Nếu có filter/sort, wrap `remember(input) { ... }` hoặc `derivedStateOf`.
4. Nếu có LazyColumn, set `key = { it.uniqueField }`.
5. Nếu lambda callback, dùng function reference `vm::handler` hoặc `remember { { ... } }`.
6. Nếu có I/O khi mở screen, làm trong `LaunchedEffect { withContext(IO) { ... } }`.
7. Test dark mode + landscape + font size Large.

---

## 10. Khi thêm Setting toggle mới

1. Lưu vào SharedPreferences qua ViewModel (không đọc trực tiếp trong Composable).
2. Toggle state là `mutableStateOf(...) private set` trong VM.
3. Khi user toggle → `SystemLogger.log("INFO", "Cài đặt", "Bật/Tắt <tên>")`.
4. Nếu toggle điều khiển Worker → enqueue hoặc cancel tương ứng.
5. KHÔNG log password / token / URL đầy đủ (chỉ hash hoặc take(32)).

---

## 11. Đánh giá "đã tốt nhất chưa?"

Câu trả lời trung thực: **chưa đo được thì chưa khẳng định được**.

Các chỉ số nên thêm vào CI/CD:
- [ ] Baseline Profile (`androidx.benchmark:benchmark-macro`)
- [ ] Startup time đo bằng `am start -W`
- [ ] Frame metrics qua `Choreographer.FrameCallback`
- [ ] Memory leak check qua LeakCanary (debug build)
- [ ] APK size budget (Proguard/R8 verify)
- [ ] Lint rule `--severity error` no warnings

Đến khi 6 mục này xanh, có thể nói "đã tối ưu tốt nhất theo phần cứng
hiện tại". Trước đó chỉ là "đã tuân thủ đúng best-practice".

---

## 12. Lịch sử — nơi đọc sâu

Toàn bộ pattern ở file này được cô đọng từ các đợt audit dưới đây. Khi nghi
vấn "tại sao lại làm thế?", đọc:

| Tài liệu | Nội dung |
|----------|----------|
| `CHANGELOG_AUDIT_FIX.md` §1–§9 | B1–B36 UI/UX + emoji sweep |
| `CHANGELOG_AUDIT_FIX.md` §10 | P1–P8 leak/performance |
| `CHANGELOG_AUDIT_FIX.md` §11 | Q1–Q8 architecture/threading |
| `CHANGELOG_AUDIT_FIX.md` §12 | R1–R7 Worker/network/exception/OS stop |
| `DEVELOPMENT_GUIDELINES.md` (file này) | Tổng hợp DO/DON'T/checklist |

---

## 13. Quy trình cập nhật file này

File này là **living document**. Khi phát hiện anti-pattern mới hoặc pattern
mới cần bảo vệ:

1. Thêm entry vào §2 (DO) hoặc §3 (DON'T) với lý do cụ thể.
2. Thêm scenario smoke test vào §7 nếu cần verify thủ công.
3. Link với CHANGELOG entry tương ứng.
4. Tăng số đợt (R8, R9...) trong §1 timeline.
5. Commit với message: `docs(guidelines): thêm <pattern> từ <Rx>`.

Không xóa entry cũ trừ khi pattern thực sự obsolete (ví dụ: API Android
deprecate). Xóa thì phải ghi `<strikethrough>` + lý do, không xóa hẳn.

---

*Cập nhật gần nhất: 2026-04-18 — đợt R7 Worker stability.*
