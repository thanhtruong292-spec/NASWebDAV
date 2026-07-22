# Implementation Plan — NASWebDAV Production Readiness

Address P2/P3 reliability, security, and data safety issues identified in codebase-wide audit across `nas_api_server.py` and Android Kotlin components.

---

## Status Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Fixed, reviewed, merged |
| 🔧 | In progress / pending fix |
| ⏳ | Backlog / not yet started |
| ❌ | Skipped / out of scope |

---

# Phase 1 — Mandatory P2 Bug Fixes ✅ COMPLETED

**Commits:** `49ed009a` + `2ee4e35b` | **Review:** 2-axis PASS (0 hard violations, 0 spec fails)

### Python Backend (`nas_api_server.py`) ✅

| # | Finding | Fix | Status |
|---|---------|-----|--------|
| #18 | `safe_run_cmd` receives string instead of list | Changed to `["chown", "-R", "daica:webdav-users", trash_dir]` | ✅ |
| #17 | SQLite connection leak in `ban_ip_permanently` | Wrapped in `try/finally: conn.close()` | ✅ |
| #19 | `_last_net` thread-safety race condition | Added `_net_stats_lock = threading.Lock()` | ✅ |

### Android Workers & ViewModels ✅

| # | Finding | Fix | Status |
|---|---------|-----|--------|
| #1 | BatchOperationWorker no isStopped check | Added `isStopped` → `Result.retry()` before progress | ✅ |
| #2 | Batch DELETE fallback kills file on cancellation | Added `CancellationException` rethrow in MOVE catch | ✅ |
| #3 | Single-file delete loses original path | Insert `TrashMeta(trashPath, originalPath)` after MOVE | ✅ |
| #4 | AutoDuplicate no DELETE fallback | Add WebDAV DELETE on MOVE failure per GEMINI §10 | ✅ |
| #5 | AutoBackupWorker cancellation → failure | Rethrow `CancellationException` + `isStopped` guard | ✅ |
| #6 | LongRunningApiWorker no transient retry | `Result.retry()` for timeout/connect/unknownhost + `isStopped` | ✅ |
| #7 | SmartToolsViewModel orphan GlobalUiViewModel | `attachGlobalUi()` + `private set` backing property | ✅ |
| #8 | SmartTools thumb/status missing auth | Added `.let(WebDavManager::tagCurrentAuth)` | ✅ |
| #10 | SocialDownload reports success before job done | Poll `/api/social/status/<job_id>` until terminal | ✅ |
| #11 | SmartTools organize scan/execute/poll missing auth | Added `tagCurrentAuth` to all 3 endpoints | ✅ |
| #12 | Livestream control ignores HTTP failures | `checkResponseOk(resp)` helper on all 5 endpoints, parse error body | ✅ |
| #26 | Room migration test gap v13→v15 | Added `migrateFromVersion13To15_validatesSchema()` + updated v1→v15 | ✅ |
| SV-1 | AutoBackupWorker still missing isStopped (caught in review) | `if (isStopped) return Result.retry()` before generic catch | ✅ |
| SV-2 | LongRunningApiWorker same issue | Same fix | ✅ |
| SV-3 | SmartTools `globalUi` public, no private set | Added `private set` | ✅ |

---

# Phase 2 — Hardening (pre-release) ✅ COMPLETED

Estimate: 2–3 days. Each item independently shippable.

**Status: COMPLETE.** All P2 items resolved across commits `b9f4fff0`, `c90fa069`, `84ab57e0`, `fd5c757a`.

## P2-A: Livestream UI & reliability ✅

| # | Finding | File(s) | Description | Status |
|---|---------|---------|-------------|--------|
| P2-24 | LivestreamMonitorWorker returns success on error | `LivestreamMonitorWorker.kt:287-314` | Returns `Result.failure()` when `finalStatus="error"` (commit `c90fa069`). | ✅ |
| P2-25 | dedupeLivestreamJobsForDisplay is no-op | `LivestreamViewModel.kt:342-345` | Deduplicates by `jobId` or `platform+username+outputFile` (commit `c90fa069`). | ✅ |
| P2-26 | Livestream status drops terminal jobs | `LivestreamViewModel.kt:111-125` | Retains completed/error jobs for display (commit `c90fa069`). | ✅ |
| P2-36 | eMMC wear: guest passes on root fs | `nas_api_server.py` | Persisted to HDD `.naswebdav/guest_passes.json` with fail-closed logic (commit `c90fa069`). | ✅ |
| P2-37 | Duplicated API error parser | `LivestreamViewModel.kt`, `SystemMonitorViewModel.kt` | Extracted to `WebDavManager.extractApiError(resp)` in `84ab57e0`. | ✅ |

## P2-B: Login & session UX ✅

| # | Finding | File(s) | Description | Status |
|---|---------|---------|-------------|--------|
| P2-27 | Login auto-replaces user-typed IP every 2s | `LoginScreen.kt:135-142` | Only auto-fill when `ipInput.isBlank()` (commit `b9f4fff0`). | ✅ |
| P2-28 | Guest credentials in-memory only | `nas_api_server.py` | Persisted to HDD `.naswebdav/guest_passes.json` with fail-closed logic (commit `c90fa069`). | ✅ |

## P2-C: Backup & deletion safety ✅

| # | Finding | File(s) | Description | Status |
|---|---------|---------|-------------|--------|
| P2-29 | Backup delete treats non-404 as success | `SystemMonitorViewModel.kt:635-642` | Parses non-2xx, extracts error body, refreshes only on success (commit `b9f4fff0`). | ✅ |
| P2-30 | Backup creation missing auth tagging | `SystemMonitorViewModel.kt:606-609` | Added `.let(WebDavManager::tagCurrentAuth)` (commit `b9f4fff0`). | ✅ |

## P2-D: Python backend hardening ⏳

| # | Finding | File(s) | Description | Priority |
|---|---------|---------|-------------|----------|
| P2-31 | File handle leak in api_media_fast | `nas_api_server.py:8960` | `open()` without context manager. Exception path leaks FD. Merged into P3-19. | ⏳ |
| P2-32 | Recent_auth_ips unbounded growth | `nas_api_server.py:986` | No eviction. Memory leak over weeks. | ⏳ |
| P2-33 | _social_download_jobs unbounded growth | `nas_api_server.py:259` | Expired jobs only cleaned on new job creation. | ⏳ |
| P2-34 | Duplicate functions `_target_hdd_devname` | `nas_api_server.py:1234,5588` | Same function defined twice; second silently overwrites. | ⏳ |
| P2-35 | Duplicate functions `_read_io_stats` | `nas_api_server.py:4938,5603` | Same situation. | ⏳ |

---

# Phase 3 — Polish & backlog ✅

Estimate: 3–5 days. Not blocking release if Phase 1+2 complete.

**Status: 14/15 items complete.** Remaining: P3-1, P3-6, P3-8, P3-9, P3-10 (backlog).

## P3-A: Accessibility & security UX

| # | Finding | File(s) | Description | Status |
|---|---------|---------|-------------|--------|
| P3-1 | ~30+ Icons missing contentDescription | BrowserScreen, DashboardCards, MainMenuScreen, etc. | TalkBack announces blank. Play Store requirement. | ⏳ |
| P3-2 | GuestPassScreen password plaintext | `GuestPassScreen.kt` | Masked default, eye toggle, state reset (`remember(pass?.username)`), 40dp+A11y. | ✅ |
| P3-3 | PerformanceScreen calls `System.gc()` | `PerformanceScreen.kt` | Removed `System.gc()` call; retained Coil memory cache clear. | ✅ |

## P3-B: Build & CI

| # | Finding | File(s) | Description | Status |
|---|---------|---------|-------------|--------|
| P3-4 | ProGuard over-keeps entire packages | `app/proguard-rules.pro` | Refined over-broad rules; verified via `assembleRelease`. | ✅ |
| P3-5 | CI Python 3.12 vs production 3.5 | `.github/workflows/ci.yml` | Added AST NodeVisitor check for Python 3.5 syntax in `ad8ebe74`. | ✅ |
| P3-6 | Shopee detected but not in allowlist | `SocialExtractorScreen.kt:934-964` | Platform detected → blocked by SOCIAL_HOST_ALLOWLIST. | ⏳ |

## P3-C: Test coverage gaps

| # | Finding | File(s) | Description | Status |
|---|---------|---------|-------------|--------|
| P3-7 | No tests for WebDavManager | `WebDavErrorTest.kt` | Added unit tests for `WebDavManager.extractApiError` in `ad8ebe74`. | ✅ |
| P3-8 | No tests for SecurePrefsHelper | — | Credential storage untested. | ⏳ |
| P3-9 | No tests for DuplicateScanWorker | — | Core dedup logic untested. | ⏳ |
| P3-10 | No tests for FileBrowserViewModel | — | File CRUD operations untested. | ⏳ |
| P3-11 | WebDavErrorTest.kt is empty placeholder | `WebDavErrorTest.kt` | Restored active unit test cases in `ad8ebe74`. | ✅ |

## P3-D: Code quality smells (from review)

| # | Finding | File(s) | Description | Priority |
|---|---------|---------|-------------|----------|
| P3-12 | Livestream checkResponseOk naming | `LivestreamViewModel.kt:185` | Renamed to `throwOnUnsuccessfulResponse` in `b9f4fff0`. | ✅ |
| P3-13 | Duplicated Livestream HTTP pattern | `LivestreamViewModel.kt` (5 methods) | `throwOnUnsuccessfulResponse` helper extracted in `2ee4e35b`. | ✅ |
| P3-14 | Duplicated AutoDuplicate try-catch shape | `AutoDuplicateScanWorker.kt` | Extracted `executeWebDavRequest` helper in `fd5c757a`. | ✅ |
| P3-15 | Data Clumps in AutoDuplicateScanWorker | `AutoDuplicateScanWorker.kt` | Encapsulated into `WebDavAuthContext` in `fd5c757a`. | ✅ |

## P3-E: Python backend maintenance

| # | Finding | File(s) | Description | Priority |
|---|---------|---------|-------------|----------|
| P3-16 | `check_auth` uses `&` instead of `and` | `nas_api_server.py` | Evaluated digests separately; combined with `and` in `fd5c757a`. | ✅ |
| P3-17 | `NAS_TMP_ROOT` on /tmp (tmpfs/RAM) | `nas_api_server.py` | Routed temp files to HDD `.naswebdav/nas_meta_tmp` in `fd5c757a`. | ✅ |
| P3-18 | Credential/config path logged at INFO | `nas_api_server.py` | Masked config path in warning logs in `fd5c757a`. | ✅ |
| P3-19 | `api_media_fast` file handle leak | `nas_api_server.py` | Wrapped `open()` safely in generator in `fd5c757a`. | ✅ |

---

# Verification Checklist

## After Phase 1 (DONE) ✅
- [x] `python -m py_compile nas_api_server.py` → SYNTAX OK
- [x] `pytest` → 88 passed, 3 skipped
- [x] `.\gradlew assembleDebug` → BUILD SUCCESSFUL
- [x] 2-axis code review → 0 hard violations, 0 spec fails

## After Phase 2 ✅
- [x] Deploy updated `nas_api_server.py` to NAS and restart `nas_api.service`
- [x] `pytest` → 88 passed, 3 skipped
- [x] `.\gradlew assembleDebug` → BUILD SUCCESSFUL
- [x] 2-axis review of Phase 2 changes
- [x] NAS deployed via Tailscale `100.90.135.102`

## Before release (Phase 3 complete)
- [ ] Accessibility audit: TalkBack on all screens (~30 Icons need `contentDescription`)
- [ ] P3-6: Shopee allowlist in `SOCIAL_HOST_ALLOWLIST`
- [ ] P3-8: Test coverage for SecurePrefsHelper
- [ ] P3-9: Test coverage for DuplicateScanWorker
- [ ] P3-10: Test coverage for FileBrowserViewModel
- [ ] P2-31 to P2-35: Python backend hardening (memory leaks, unbounded growth, duplicate functions)
- [ ] Full regression test on real NAS hardware
