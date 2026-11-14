# SPEC.md — NASWebDAV Comprehensive Product-Readiness Audit & Fix

## 1. Mục tiêu
Rà soát tổng thể, đánh giá độ hoàn chỉnh chức năng, sửa lỗi phát hiện và đảm bảo ứng dụng NASWebDAV đạt chuẩn product hoàn chỉnh nhất trước khi release.

## 2. Phạm vi
- **Backend NAS** (`backend/nas_api_server.py`, ~817KB, chạy Python 3.5.3 + Flask 1.1.4 trên Chainedbox L1 Pro RK3328 ARM64, 1GB RAM)
- **Android Client** (Kotlin + Jetpack Compose, ~60+ file `.kt` trong `app/src/main/java/com/nas/naswebdav/`)
- **Test suite** (`tests/`, 160 test Python hiện tại, chạy trên Python 3.14 local)
- **Database migrations** (`Database.kt`, Room v19)
- **OKHttp/Network layer** (5 singleton client, auth injection)
- **WorkManager Workers** (AutoBackup, DuplicateScan, Livestream, SocialDownload, etc.)

## 3. Ràng buộc cốt lõi (BẤT BIẾN)

### 3.1 Python 3.5.3 + Flask 1.1.4 (backend/nas_api_server.py)
- KHÔNG f-strings, KHÔNG variable type annotation, KHÔNG walrus, KHÔNG numeric underscore
- KHÔNG `secrets`, `dataclasses`, `contextvars`, `zoneinfo`, `tomllib`
- KHÔNG `with os.scandir(...)`, KHÔNG `m[0]`/`m[1]` trên re Match → dùng `m.group(0)`/`m.group(1)`
- KHÔNG `capture_output=True`, `text=True`, `encoding=`, `errors=` trong subprocess
- KHÔNG truyền `bytes` vào `json.loads(...)` → `.decode("utf-8", errors="ignore")` trước
- Flask 1.1.4: `cache_timeout=`, `attachment_filename=`, `add_etags=` (KHÔNG `max_age=`, `download_name=`, `etag=`)
- `@app.route(..., methods=["GET/POST"])` (KHÔNG `@app.get()`/`@app.post()`)
- File phải LF, UTF-8 no BOM, zero undefined names (AST scan)

### 3.2 Android / Jetpack Compose
- Theme bắt buộc dùng `NasTheme {}` (KHÔNG `MaterialTheme {}` trực tiếp)
- Clickable/toggleable < 40dp phải `Modifier.minimumInteractiveComponentSize()`
- LazyColumn/LazyRow phải có `key = { ... }`
- Filter/sort trong composable phải `remember(...)` hoặc `derivedStateOf`
- `mutableStateOf` public phải `private set`
- I/O phải `withContext(Dispatchers.IO)`, coroutine trên `viewModelScope`
- Worker > 10s phải `setForeground()` đầu `doWork()`, `setBackoffCriteria(EXPONENTIAL, 15-60s)`
- Worker catch phải kiểm tra `isStopped || e is CancellationException` → `Result.retry()`
- Notification phải có completion state (ongoing=false, autoCancel=true)
- 5 singleton OkHttp client (KHÔNG `OkHttpClient.Builder().build()` ad-hoc)
- `InputStream`/`OutputStream`/`Bitmap` phải `.use {}` / `.recycle()`
- WAKE_LOCK với `tools:node="replace"` là bất khả xâm phạm

### 3.3 Test requirement
- `python -m pytest tests/ -v` phải PASS 100% sau mỗi thay đổi backend
- Mọi thay đổi Worker/notification/SystemLogger phải đi kèm test case thủ công

## 4. Các vòng rà soát (phân phối cho agent)

| Vùng | Nội dung kiểm tra |
|------|------------------|
| **A. Backend Python 3.5.3 compatibility** | Quét AST, token, stdlib kwargs, Flask 1.1.4 kwargs, zero undefined names |
| **B. Backend API correctness** | Tất cả ~70 endpoint, auth decorator, input validation, error handling |
| **C. Backend livestream/social/ytdlp** | Watchdog control flow, SIGI_STATE parser, queue TTL/persist, 429 limits |
| **D. Android ViewModel/State** | private set, Dispatchers.IO, viewModelScope, no GlobalScope, no Color literal |
| **E. Android Worker** | setForeground, backoff, isStopped check, notification completion, SystemLogger START/END |
| **F. Android Network/OkHttp** | 5 singleton client, `.use {}`, auth header injection, timeout tiers |
| **G. Android UI/Compose** | NasTheme, min interactive size, LazyColumn key, remember/filter, contentDescription |
| **H. Database/Room** | Migration v1→v19, no destructive fallback, accountKey scoping |
| **I. Test coverage** | Thêm test cho các khoảng trống phát hiện |
| **J. Build verification** | `./gradlew assembleDebug` (nếu có Android SDK), lint |

## 5. Định nghĩa xong (Definition of Done)
- Tất cả 160 test hiện tại vẫn PASS
- Không còn vi phạm Python 3.5.3/Flask 1.1.4 trong backend
- Không còn anti-pattern Android trong DEVELOPMENT_GUIDELINES.md
- Tất cả lỗi phát hiện đã được fix hoặc có lý do rõ ràng không fix
- Ứng dụng đạt chuẩn product: auth an toàn, input validation, error handling, graceful degradation
