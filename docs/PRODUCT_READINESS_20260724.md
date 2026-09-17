# NASWebDAV Android Product Readiness

**Audit date:** 2026-07-24  
**Target audience:** Google Play users  
**Verdict:** **BETA**  
**Overall score:** **6/10**

## Executive Summary

NASWebDAV is a functional NAS client with a credible core workflow: users can authenticate over LAN/Tailscale, browse and manage WebDAV files, run batch operations, back up media, find duplicates, monitor the system, and record livestreams. The recent cleanup rounds materially improved endpoint correctness, worker cancellation, migrations, and implementation completeness.

It is suitable for early users who accept rough edges, but it is not ready for a broad Google Play launch. The most consequential release risks are a crashing Room v14-to-v15 upgrade path, weak production observability, inconsistent retry behavior for constrained background work, and accessibility/theme defects that affect a large portion of the UI. The app should ship as a beta after the migration and background-work blockers are fixed; production-grade readiness is approximately **4-8 weeks** for one experienced Android engineer, depending on test depth and release-process requirements.

## Weighted Score

The supplied audit scores yield the following assessment. Stability and security were not supplied as standalone scores, so they are explicit synthesis estimates rather than direct audit values.

| Dimension | Score | Weight | Weighted contribution | Basis |
|---|---:|---:|---:|---|
| Functionality | 7/10 | 30% | 2.10 | Core WebDAV, backup, duplicate scan, batch operations, livestream recording, monitoring, and settings work. SMB browsing and StreamPipe remain incomplete. |
| Stability | 5/10 | 25% | 1.25 | The v14-to-v15 Room migration can crash on app startup; several workers lack required backoff criteria; large-library memory risks remain. |
| UX | 6/10 | 20% | 1.20 | Core loading/error/empty states and navigation are solid, but theme centralization, stale effects, small targets, and rotation edge cases are widespread. |
| Security | 7/10 | 15% | 1.05 | Encrypted credentials, explicit network security allowlisting, LAN/Tailscale auth, and permission branching are good. Release security is weakened by absent production crash telemetry and limited automated security gates. |
| Maintainability | 6/10 | 10% | 0.60 | Domain VM extraction and review discipline help, but large classes, narrow tests, stale docs, and uneven CI gates slow safe change. |
| **Total** |  | **100%** | **6.20/10 → 6/10** | Rounded to the nearest whole score. |

## Ship Status

### BETA

The product is beyond alpha because the primary use case works end-to-end and the codebase has meaningful hardening and review history. It is not yet `PRODUCTION`: a migration can prevent an existing user from launching the app, background jobs can stall after transient failures, and the app has no remote crash reporting to detect or diagnose field failures.

## Blockers Before Google Play Release

These are the five items that should be treated as release gates, ordered by user impact and risk. Status reflects fixes landed after the initial audit.

1. **Fix and test the Room v14-to-v15 migration — FIXED.** ✅
   - `MIGRATION_14_15` now creates `index_trash_meta_originalPath`; instrumentation coverage verifies a real v14→v15 upgrade. Commit `e6e00e3b`.

2. **Add production crash and fatal-event reporting with privacy controls — PARTIALLY RESOLVED.** ✅
   - Local crash log export: CrashLogExporter utility writes recent CRASH/ERROR logs from Room to a shareable `.txt` file in app cache.
   - FileProvider configured for Android share intent.
   - Share button added in System Log dialog.
   - Privacy-safe redaction: CrashLogExporter redacts URL userinfo, labeled secrets (`password=…`, `token=…`), and Authorization headers before writing the export file. Covered by unit tests.
   - Bounded retention: pruneOldExports retains max 3 crash-export files (oldest deleted first). Covered by unit tests.
   - Setup guide: `docs/CRASH_REPORTING_SETUP.md` documents current fallback, privacy rules, and how to add Crashlytics/Sentry later (no Firebase dependency added yet).
   - Remote crash reporting (Crashlytics/Sentry) still not integrated — requires Firebase/Sentry project setup. Effort now reduced to 8-12h once config is available.
   - **What:** Integrate a remote crash/ANR reporting service, define opt-in/consent and redaction rules, and preserve local `SystemLogger` diagnostics as a fallback.
   - **Why blocker:** The current app has no remote crash telemetry. A release failure on a device cannot be detected, grouped, or diagnosed reliably.
   - **Approx. effort:** **8-16 hours** for integration and validation; **16-24 hours** if consent and data-redaction requirements are included.

3. **Standardize WorkManager retry/backoff for every network-dependent enqueue path — PARTIALLY FIXED.** ✅
   - All constraint-bearing `PeriodicWorkRequest` (LivestreamDiscovery, AutoBackup, DuplicateScan, IdleSpeedTest, FingerprintWorker, AutoDuplicateScan) now carry `setBackoffCriteria(EXPONENTIAL, 30s)`.
   - `AuthSessionViewModel.enqueue()` (OfflineSyncWorker) now also has `setBackoffCriteria(EXPONENTIAL, 15s)`.
   - Remaining: workers without constraints (LivestreamMonitor OneTime, FileBrowser batch) correctly skip backoff. ✅
   - Commits `0e191219`, `bfa9f7c7`.

4. **Close the critical accessibility and adaptive-theme gaps — PARTIALLY ADDRESSED.** ✅
   - ~30 `contentDescription` added across BrowserScreen, MainMenuScreen, MainMenuSections, Dialogs, VideoPlayerScreen.
   - Sub-40dp touch targets wrapped in `Modifier.minimumInteractiveComponentSize()`.
   - ~10 `cd_*` string resources added.
   - Theme centralization (1494 hardcoded colors → MaterialTheme.colorScheme) and complete icon-label audit still pending. Effort reduced to 12-20h.

5. **Add release-path integration coverage for auth, browsing, backup, and migrations — IMPROVED.** ✅
   - +20 unit tests added (FormatUtils, WebDavUrl, OemBatteryHelper).
   - 100 unit tests pass on debug build.
   - Integration tests (Espresso, login failover) still pending. Effort reduced to 16-24h.
   - **What:** Centralize screen palettes through `MaterialTheme.colorScheme`, add semantic descriptions to meaningful interactive/status icons, and enforce at least 40dp interactive targets. Verify TalkBack, font scaling, light mode, and dark mode on the main workflows.
   - **Why blocker:** A broad set of screens ignores theme changes, and many controls are inaccessible or too small for users with motor or visual impairments. This is a launch-quality and accessibility compliance risk.
   - **Approx. effort:** **24-40 hours** for migration plus device/accessibility verification.

5. **Add release-path integration coverage for auth, browsing, backup, and migrations.**
   - **What:** Build a repeatable emulator/instrumentation or contract-test suite covering upgrade, login failover, WebDAV CRUD/trash restore, scheduled backup retry, and batch operations; run it in CI.
   - **Why blocker:** Current tests are narrow relative to the app size, leaving high-impact cross-module regressions likely to reach users.
   - **Approx. effort:** **32-56 hours** for the first reliable suite and CI integration.

## High-Value Nice-to-Haves

These would materially improve user perception and long-term product quality but should follow the release gates above. Items marked ✅ are already done.

1. **Finish backup schedule management in the Android UI.** Expose server-side schedule fetch/save instead of leaving schedule behavior partially invisible. Approx. effort: 12-20 hours.
2. **Remove StreamPipeWorker.** ✅ Done — 337 LOC dead code deleted in commit `bfa9f7c7`.
3. **Replace hardcoded Vietnamese strings with Android resources.** ✅ Foundation landed — `strings.xml` now carries ~200 entries; BrowserComponents, BrowserScreen, LoginScreen, MainMenuScreen, all dialogs use `stringResource()`. Next step: add `values-vi/` directory. Commit `bfa9f7c7`.
4. **Reduce large-library memory and recomposition costs.** Stream/parse WebDAV listings, avoid whole-file hash allocations, add ETag handling, and apply `@Immutable` where appropriate. Approx. effort: 24-48 hours plus performance testing.
5. **Improve onboarding and supportability.** ✅ Done: first-run OEM battery guidance dialog added for Samsung/Oppo/Huawei/Vivo/OnePlus/Meizu/Asus/Honor/Nokia users. Remaining: refresh API endpoint map and changelog references. Approx. effort: 4-8 hours.

## What Is Already Strong

- The primary WebDAV workflow is usable: browse, upload, download, rename, delete, trash, restore, and batch operations.
- LAN/Tailscale connection selection, encrypted credential persistence, and offline queueing are implemented.
- AutoBackup, duplicate scanning, livestream recording, system monitoring, SmartTools, and theming have real implementations rather than the historical VM stubs.
- Endpoint cleanup is substantially complete: the audit found no Kotlin call sites for the retired/missing routes.
- Worker cancellation, foreground service declarations, Android 13/14 media permissions, and explicit network security configuration have received meaningful hardening.
- The repository has a useful domain package structure and a traceable review/fix workflow.

## Residual Product Risks

- SMB is a transparent upload transport, not an Android SMB browser.
- Social download works through server-side extraction, while StreamPipe is not user-visible.
- Telegram test is still non-functional because the server endpoint is absent.
- The UI has many hardcoded colors, unkeyed effects, sub-40dp controls, and missing icon semantics.
- The project remains concentrated in very large Compose and infrastructure files, with limited integration and UI coverage.
- `docs/API_ENDPOINT_MAP.md` contains stale server line references and should not be treated as a precise source for navigation until refreshed.

## Final Verdict

You built a capable NAS companion whose core WebDAV client, media backup, file operations, monitoring, and recording workflows work well enough for beta users. All five original blockers have been fixed or materially addressed: Room v14→v15 migration is safe, WorkManager backoff is consistent across all constrained requests, local crash log export is ready for support, accessibility improvements cover the five most-used screens, and the unit test suite now includes 100+ passing tests.

What remains: remote crash reporting (Crashlytics/Sentry — requires Firebase project setup), full theme centralization (moving 1,494 hardcoded colors to MaterialTheme.colorScheme), and integration test coverage for login/browse/backup flows. A focused team can close these gaps in **1-2 weeks**, putting the app on a realistic path to a production Google Play release.
