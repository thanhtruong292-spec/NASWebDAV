# Spec: NASWebDAV chuẩn production (BETA 6.5 → PRODUCTION)

## Objective

Đưa app từ BETA lên đủ chuẩn phát hành Google Play: hết crash/OOM đã biết,
state bền vững, có crash telemetry tự host, đủ vi+en locale, CI gate tối thiểu.
Người dùng: chủ NAS dùng app Android quản lý file/backup/monitor.
Success: 0 finding Critical/Required còn mở; `./gradlew lint testDebugUnitTest`
xanh; không regression core flow (auth/browse/backup/batch).

## Tech Stack

Kotlin 2.0.21, AGP 8.7.3, Compose M3 1.2.0, Room 2.7.1, WorkManager 2.10.0,
OkHttp/Sardine, minSdk 26 targetSdk 35. Thêm: `io.sentry:sentry-android:7.x`
(self-hosted DSN, opt-in).

## Commands

- Build debug: `./gradlew assembleDebug` (chạy từ `D:/Android/NASWebDAV`, Git Bash)
- Unit test: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lintDebug`
- Kiểm tra nhanh 1 module test: `./gradlew :app:testDebugUnitTest --tests "<class>"`

## Project Structure

```
app/src/main/java/com/nas/naswebdav/
  WebDavManager.kt      → HTTP/WebDAV client (sửa OOM tại đây)
  Database.kt           → Room DAOs (giới hạn query tại đây)
  NasApplication.kt     → init Sentry tại đây
  ui/screens/           → dời getSharedPreferences ra ViewModel
  ui/components/        → components Nas* mới (đã có, chưa commit)
app/src/main/res/values/ + values-en/ + values-vi/ (mới)
.github/workflows/ci.yml (mới)
docs/SPEC_PRODUCTION.md (file này)
```

## Code Style

Kotlin official style. Composable đọc state từ ViewModel/SavedStateHandle, không
gọi `getSharedPreferences` trực tiếp. Ví dụ:

```kotlin
// Xấu: đọc prefs trong composable
val showHidden = remember { prefs.getBoolean("show_hidden", false) }
// Tốt: state từ ViewModel, survive rotation
val showHidden by browserViewModel.showHidden.collectAsStateWithLifecycle()
```

Catch block luôn rethrow `CancellationException` trước `Exception`.

## Testing Strategy

JUnit4 + MockWebServer + Room in-memory (đã có 6 file test).
Mỗi fix logic phải kèm test: PROPFIND parse giới hạn size, DAO limit,
cancel scope, Sentry redaction. Không thêm Espresso trong đợt này (defer).

## Boundaries

- Always: test xanh trước commit; rethrow CancellationException; redaction URL/secret trong log/crash.
- Ask first: thêm dependency mới; đổi schema Room (cần migration test); đụng CI release/signing.
- Never: commit secret/keystore; đọc toàn bộ response vào RAM; cancel call không theo group.

## Success Criteria

1. PROPFIND/parse response stream + cap size (test chứng minh >cap bị từ chối an toàn).
2. `cancelActiveCalls(group)` — mọi caller truyền đúng group, test scope.
3. DAO scan lớn có LIMIT + paging; không query full-table trên UI thread.
4. Xoay màn hình giữ dialog/toggle state chính (test Robolectric hoặc manual checklist).
5. Sentry self-hosted init sau opt-in, DSN cấu hình được, `beforeSend` redact URL userinfo + Authorization.
6. `values-vi/` đầy đủ, test locale vi/en.
7. CI chạy build + unit test + lint trên mỗi push/PR.
8. Commit hết work tồn đọng trên `main-fresh`, tách cụm nhỏ.

## Open Questions — đã chốt với user

- Telemetry: Sentry self-hosted (không Crashlytics).
- God files: tách dần, ngoài scope đợt này (ghi nợ).
- Scope đợt này: Ổn định core + Chuẩn production. Nợ kiến trúc (tách file, recomposition, ETag) defer.
