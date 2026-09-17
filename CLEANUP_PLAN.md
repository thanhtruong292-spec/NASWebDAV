# Cleanup Plan — Code Rác & Feature Stubs Audit

**Date:** 2026-07-21
**Branch:** `codex/review-cleanup-20260603`
**Scan method:** 9 parallel agents + 3 deep audits + manual grep verification

---

## Problem Statement

Codebase `codex/review-cleanup-20260603` (97 commits over main) contains:

- **13 empty stub methods** in ViewModels that look implemented but do nothing
- **14 endpoint URL mismatches** — client calls server endpoints that return 404
- **~30 tracked scratch/debug files** that should never have been committed
- **Dead source files** (old_WebDavViewModel.kt, Type.kt + fonts, unused deps)
- **Duplicate logic** (PiP helpers, URL converters, scan pipelines)

---

## Category A: Stub/Incomplete Features (13 stubs)

### LivestreamViewModel.kt — 8 stubs (Phase 3b)
- Line 100: `startLivestreamRecord(url, onError)` — body `{ viewModelScope.launch { /* TODO Phase 3b */ } }`
- Line 104: `stopLivestreamRecord(context, jobId)` — body `{ viewModelScope.launch { /* TODO Phase 3b */ } }`
- Line 112: `restoreLivestreamStateIfRunning()` — body `{ viewModelScope.launch { /* TODO Phase 3b */ } }`
- Line 154: `observeLivestreamWorker(context)` — body `{ viewModelScope.launch { /* TODO Phase 3b */ } }`
- Line 157: `monitorYtdlpJob()` — body `{ /* TODO Phase 3b */ }`
- Line 160: `startStreamPipe(url, format, outputPath, onError)` — body `{ viewModelScope.launch { /* TODO Phase 3b */ } }`
- Line 164: `cancelStreamPipe(onError)` — body `{ viewModelScope.launch { /* TODO Phase 3b */ } }`
- Line 168: `requestSocialDownload(url, format, onError)` — body `{ viewModelScope.launch { /* TODO Phase 3b */ } }`

### SmartToolsViewModel.kt — 3 stubs (Phase 2b)
- Line 304: `triggerSmartOrganizeScan()` — body `{ /* TODO Phase 2b */ }`. Dead duplicate (real impl at L419)
- Line 305: `executeSmartOrganize(action)` — body `{ /* TODO Phase 2b */ }`. Dead duplicate (real impl at L456)
- Line 515: `organizeLegacyVideos()` — body `{ viewModelScope.launch { /* TODO Phase 2b */ } }`. Server HAS endpoint `/api/tools/organize_legacy_videos` at L9315 — never wired

### AutoBackupViewModel.kt — 2 flag-only stubs
- Line 117: `toggleAutoBackupPause()` — only flips local state; Worker checks different singleton `AutoBackupState.isPaused` (L177)
- Line 327: `requestLockNow()` — only sets `lockNowRequested = true`; UI must observe to trigger actual lock (intentional? undocumented)

---

## Category B: Endpoint URL Mismatches (14, will 404)

### AutoBackupViewModel.kt
- `/api/usb_import/resolve` → server has `/api/usb_import/resolve_conflicts` (L7359)
- `/api/hdd/spindown` → server has `/api/system/hdd_spindown_now` (L5717)

### DeviceManagementViewModel.kt
- `/api/docker/container/control` → server has `/api/docker/control` (L4059)
- `/api/telegram/test` → **NO SERVER ROUTE AT ALL**

### LivestreamViewModel.kt
- `/api/stream/pipe` → **NO SERVER ROUTE**
- `/api/stream/cancel` → **NO SERVER ROUTE**

### SmartToolsViewModel.kt
- `/api/thumb/stop` → server has `/api/thumb/control` with action=pause

### SystemMonitorViewModel.kt (6 endpoints)
- `/api/smart/health` → server has `/api/disk/health` (L5102)
- `/api/smart/history` → server has `/api/disk/health/history` (L5120)
- `/api/config/backups` → **NO SERVER ROUTE**
- `/api/config/backup` → **NO SERVER ROUTE**
- `/api/config/restore` → **NO SERVER ROUTE**

---

## Category C: Tracked Scratch/Debug Junk (~30 files)

### Delete from git tracking (`git rm --cached`)
- `old_WebDavViewModel.kt` (4,259 lines, dead snapshot)
- `skills-lock.json` (311 lines, matt-pocock skill lockfile)
- `task.md`, `check_log.py`, `check_watch.sh`, `fix.py`, `out.txt` (4 bytes, content "test")
- `patch.py`, `patch2.py`, `patch3.py`, `patch4.py`, `patch5.py`, `patch_sabre.py` (6 one-off patch scripts)
- `phase2_diff.txt`, `phase3_diff.txt`, `phase7a_diff.txt`, `diff.txt` (4 diff dumps)
- `plush commit git.cmd`, `reset_usb.py`, `restart_api.sh`, `search.py`
- `temp.txt` (stray UTF-16 python fragment), `test_tiktok.html`, `test_tiktok.py`
- `hs_err_pid8484.log` (1550 lines, JVM crash log)
- `.agents/SESSION_NOTES.md` (gitignored retroactively)
- `.agents/workflows/deploy-nas-server.md`
- `.agents/workflows/nas_development_guidelines.md`

### Delete from disk (untracked)
- `fix.sh`, `logcat.txt` (27 MB debug dump), `scratch/` dir, `scratch_bravedown*.py`, `scratch_fdown.py`
- `java_pid*.hprof` (3 heap dumps, ~4.5 GB on disk, gitignored but present)

---

## Category D: Dead Source Code (verified no references)

### Delete
- `app/src/main/java/com/nas/naswebdav/ui/theme/Type.kt` — 0 references, shadowed by `DashboardTheme.kt:63` internal `AppTypography`
- `app/src/main/res/font/samsung_one_regular.ttf` + `samsung_one_bold.ttf` — only referenced by Type.kt
- `app/src/main/java/com/nas/naswebdav/backup/BackupModels.kt` — 5 typealiases with 0 external imports
- `AndroidManifest.xml:9` — `READ_MEDIA_VISUAL_USER_SELECTED` (declared, never requested)
- `AndroidManifest.xml:16` — `VIBRATE` permission (no `Vibrator` usage)

### Verify before removal (high risk)
- `libs.androidx.appcompat` — 0 references; probably OK to remove
- `libs.androidx.biometric` — 0 references; verify no reflection
- `libs.androidx.media3.exoplayer.hls` — 0 references; OK to remove
- `libs.androidx.media3.session` — 0 references; verify no reflection
- `libs.sardine.android` — explicit comment in `WebDavManager.kt` says NOT used; safe to remove

### Unused import / duplicates in `nas_api_server.py`
- `import base64` (L33) — 0 usages
- Duplicate `import threading` (L25 and L9986)
- Duplicate `import hashlib` (L29 and L145)
- 8 dead functions: `get_ip_geo`, `_validate_ip`, `_validate_cidr`, `_save_photos_timeline_cache_file`, `_livestream_job_label`, `_invalidate_system_insights_cache`, `_usb_import_scan_files`, `_frame_brightness`

---

## Category E: Duplicate Logic (consolidation)

### Byte-for-byte duplicates
- PiP helpers (`buildPipActions`, `getSafePipRatio`, `enterPipMode`, `updatePipActions`):
  - `MediaScreens.kt:1217-1296`
  - `VideoPlayerScreen.kt:694-773`
- URL converters (`ipToFullUrl`/`fullUrlToIp`):
  - `LoginScreen.kt:73,79`
  - `MainMenuScreen.kt:2915,2921`
- `extractHost`: `PowerActions.kt:27` + `SocialExtractorScreen.kt:943`
- `insightRate`: `DashboardWidgets.kt:64` + `MainMenuScreen.kt:1586`

### Worker pipeline duplicated
- `DuplicateScanWorker` (one-time) vs `AutoDuplicateScanWorker` (periodic 30d) — same fast-index + SHA-256-via-Range + trash-via-MOVE pipeline

---

## Category F: StreamPipeWorker (never scheduled)

`app/src/main/java/com/nas/naswebdav/StreamPipeWorker.kt` (254 lines, fully implemented CDN→WebDAV streaming pipeline) has **no call site** — not enqueued by any VM/UI. Either wire it up or delete it.

---

## Category G: AutoBackup subtle bugs

From deep audit:
1. **13 progress fields** in `AutoBackupViewModel.kt:33-58` never written by Worker — UI reads stale zeros
2. **`isAutoBackupRunning` flicker** (set true L88, reset false L101 before Worker actually starts)
3. **Pause toggle doesn't pause** — VM flips local state but Worker checks different singleton
4. **Phone toggle does NOT enable server backup** — `auto_backup` SharedPrefs only controls WorkManager 12h task, server has separate `_scheduled_backup_worker` flag
5. **No UI for backup schedule** — `fetchBackupSchedule`/`saveBackupSchedule` implemented but no UI calls them

---

## Category H: Repo config + project structure

### `.gitignore` gaps
Add: `*.log`, `scratch/`, `scratch*.py`, `logcat.txt`, `fix.sh`, `out.txt`, `temp.txt`, `patch*.py`, `old_*.kt`, `test_tiktok.*`, `*_diff.txt`, `plush commit git.cmd`

### ProGuard redundancy
- `-dontwarn androidx.**` — R8 never warns about androidx
- `-keep class androidx.compose.**` / `-keep class androidx.media3.**` — consumer rules exist
- `-keep class androidx.core.app.**` / `-keep class androidx.documentfile.**` — consumer rules exist
- `-keep @androidx.room.Entity class * { *; }` redundant with `-keepclassmembers`

### gradle.properties leak
- `org.gradle.java.home=C:\Program Files\Android\Android Studio\jbr` — hard-coded dev-specific path

---

## Solution Strategy

Total scope: ~80 distinct cleanup actions across 8 categories. Out of scope is in the next section.

### Recommended execution order (tiny commits, behavior-preserving)

1. **Cleanup of dead scratch files** (low risk) — `git rm` of junk + `.gitignore` additions
2. **Delete verified-dead source** (Type.kt, fonts, BackupModels.kt, old_WebDavViewModel.kt)
3. **Fix endpoint URL mismatches** (14 strings) — server endpoint matches
4. **Delete stub methods** (13 methods) — remove empty bodies; add `TODO` tracking comment if feature is planned
5. **Remove unused Gradle deps + manifest permissions**
6. **Consolidate duplicate logic** (PiP, URL converters)
7. **Fix AutoBackup progress + pause flag bugs**
8. **Resolve StreamPipeWorker orphan** — wire it or delete it
9. **Update ProGuard + gradle.properties**
10. **Write tests for fixed endpoints**

---

## Out of Scope

- **Implementing Phase 3b / Phase 2b features** — these are feature work, not cleanup. Stub methods should be deleted or marked `TODO` for separate issues.
- **Server-side Telegram/Rules engine implementation** — server endpoints missing for app's UI (Issue #2, #6). Needs design + Python work.
- **Performance optimization** — separate concern.
- **UI redesign** — separate concern.

---

## Further Notes

- The user's report "UI shell only, no logic" is now fully verified with specific file:line citations
- 13 empty methods + 14 endpoint mismatches = "looks done but doesn't work" features across 6 ViewModels
- The most invasive fix is endpoint URL alignment — touches 6 files, 14 string constants
- All findings verified against actual file content via grep + Read tool — no speculation
