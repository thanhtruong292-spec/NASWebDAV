# NASWebDAV — Product Readiness Tasks (speckit-tasks)

## Completed — Code-level bug fixes
- [x] ToolboxDialog.kt: differentiate else branch (show "Không có ảnh nào thiếu thumbnail" instead of rescanning)
- [x] MainActivity.kt: consume lockNowRequested → trigger showBiometricLock; fix declaration order
- [x] AutoBackupViewModel.kt: lockNowRequested internal set + requestLockNow() setter
- [x] RecordingSettingsDialogs.kt: use autoBackupVM.requestLockNow() instead of direct field set
- [x] OfflineSyncWorker.kt line 216: remove redundant tempPath != null check
- [x] Removed unused androidx.appcompat dependency
- [x] Removed unused libs.sardine.android dependency
- [x] Removed unused VIBRATE permission from AndroidManifest.xml

## Completed — New Android unit tests
- [x] SmartToolsThumbnailTest.kt — ThumbnailAuditData state verification
- [x] AutoBackupLockTest.kt — lockNowRequested flag behavior
- [x] MediaPermissionHelperTest.kt — media access permission checks
- [x] FormatUtilsExtendedTest.kt — formatBytes, formatElapsedTime, sanitizeFileName, HashUtils
- [x] WebDavManagerUrlTest.kt — toValidUrl, call groups, constants

## Completed — Cleanup
- [x] Delete dead Type.kt stub (0 references; removed from root source tree)
- [x] Remove dead scratch/debug files (old_dialogs.kt, old_storage_dialogs.kt, patch*.py, phase*_diff.txt, hs_err logs, out.txt, temp.txt, test_output*.txt, test_final*.txt, assemble_*.txt, build_out.txt, *_build.log, NASWebDAV-screen-record-optimized-debug.apk, thumb.log, thumb-dry2.log, replay_pid*.log, tmp_*_diff.txt, compile_current.txt, compile_err.txt, diff_MainMenuScreen.txt)
- [x] Remove unused gradle deps — verified androidx.biometric (3 usages) and media3.session (4 usages) still referenced → kept
- [x] Add .gitignore entries for scratch files — .gitignore already covers *.log, *.tmp, *.apk, old_*.kt, patch*.py, out.txt, temp.txt, *_diff.txt, hs_err*.log, compile_current.txt
- [x] Update ProGuard rules — removed dead keep rules (SmartNetworkManager, WebDavViewModel), kept only active ones
- [x] Backend dashboard status cache — preserve last good CPU/RAM/disk snapshot after refresh exceptions; Android keeps it marked stale and retries at 3 seconds
- [x] UI accessibility sweep — remove duplicate screen-reader announcements from decorative icons and label icon-only actions for LAN, backup, media, and background-task controls
- [x] System tab pull-to-refresh waits for the NAS requests to finish before hiding its progress indicator

## Completed — Verification
- [x] `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — BUILD SUCCESSFUL; 83 Android unit tests pass; lint reports 0 errors
- [x] `./gradlew assembleRelease` — BUILD SUCCESSFUL (no errors)
- [x] `python -m pytest tests/ -v` — 160/160 PASS
- [x] `./gradlew test` — 83/83 PASS
- [x] Build signed release APK — .secrets/release-signing.properties configured (NAS_RELEASE_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD)
- [x] speckit-converge — verify implementation matches SPEC.md: all detected errors fixed or documented; app meets product-grade (auth safety, input validation, error handling, graceful degradation)

## Definition of Done (from SPEC.md)
- [x] All 160 backend tests PASS
- [x] No Python 3.5.3/Flask 1.1.4 violations in backend (pytest suite passes; constraints audited)
- [x] No anti-pattern Android violations in DEVELOPMENT_GUIDELINES.md (private set, Dispatchers.IO, viewModelScope, no GlobalScope, Worker setForeground/backoff, notification completion, 5 singleton OkHttp, .use{} streams, WAKE_LOCK tools:node="replace")
- [x] All detected errors fixed or documented with reason
- [x] App meets product-grade: auth safety, input validation, error handling, graceful degradation
- [ ] Runtime visual QA on a small phone, landscape, and large system font — pending because `agent-device` cannot find `adb` in PATH
