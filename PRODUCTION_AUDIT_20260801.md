# Production Audit Report — NASWebDAV Android App

**Date:** 2026-08-01  
**Auditor:** Principal Software Engineer (Multi-domain)  
**Working Tree:** HEAD (unresolved)  
**Score:** 3/10 — NOT Production Ready  

---

## 1. Executive Summary

This codebase is in a **broken migration state**. The project underwent a Strangler Fig decomposition splitting a monolithic `WebDavViewModel` into 8 domain ViewModels, but the migration was left incomplete. The working tree is missing:

- All domain ViewModel implementations (8 packages, all empty)
- `build.gradle.kts`, `settings.gradle.kts` (build system)
- `AndroidManifest.xml`, `MainActivity.kt` (app entry points)
- `.git/index` and `.git/HEAD` (git is non-functional)

The app **cannot compile** in its current state. The git repository itself is corrupted.

| Metric | Value |
|---|---|
| Files audited | 27 Kotlin + XML + docs |
| Total source LOC (app) | ~16,644 |
| Total LOC (all docs/scripts) | ~39,500 |
| Domain packages | 8 (all empty) |
| Files missing from tree | ~40+ (VMs, manifest, build files) |
| Previous audit score (2026-07-24) | 6/10 (Beta) |
| Current score | 3/10 (Broken) |

---

## 2. Architecture Analysis

### 2.1 Module Graph

```
app/ ──► Domain VMs (8)     ❌ ALL MISSING
       ├── auth/AuthSessionViewModel      (empty)
       ├── browser/FileBrowserViewModel   (empty)
       ├── device/DeviceManagementViewModel (empty)
       ├── livestream/LivestreamViewModel  (empty)
       ├── monitor/SystemMonitorViewModel  (empty)
       ├── smarttools/SmartToolsViewModel  (empty)
       ├── backup/AutoBackupViewModel      (empty)
       └── GlobalUiViewModel               (missing)
       
       ├── Shared models: ✅ NasModels.kt, UsbModels.kt, BackupModels.kt
       ├── SMB transport: ✅ SmbClient.kt
       ├── UI layer:      ✅ 20 files (16K LOC)
       └── Utils:         ❌ FormatUtils, MediaUtils, CrashLogExporter (empty)
```

### 2.2 Critical Finding: Working Tree ≠ Buildable State

The working tree contains only UI code and models that reference packages with no implementations. The actual buildable code likely exists in one of the 5 worktrees:

```
.claude/worktrees/serene-fermi-7f5b8b/  (has WebDavViewModel.kt, Database.kt, MainMenuScreen.kt)
.claude/worktrees/clever-merkle-ec620f/
.claude/worktrees/epic-jemison-f12623/
.claude/worktrees/fervent-archimedes-3bac72/
.claude/worktrees/laughing-brattain-5ff596/
```

### 2.3 Data Flow (As Designed)

```
User → LoginScreen → AuthSessionViewModel → TCP ping → WebDAV/SMB auth
    → BrowserScreen → FileBrowserViewModel → WebDAV PROPFIND → UI list
    → Backup → AutoBackupViewModel → WorkManager → SMB/WebDAV upload
    → Monitor → SystemMonitorViewModel → Flask API → System status
```

### 2.4 Threading Model

- **Compose UI thread** for all rendering
- **SMB:** `ConcurrentHashMap` session cache, per-host locks (SmbClient.kt)
- **Background:** WorkManager for uploads, duplicate scanning, fingerprinting
- **API polling:** Coroutine-based in domain VMs (missing)
- **Network:** OkHttp with potentially excessive thread count (per previous audit: up to 96 threads)

---

## 3. Critical Issues (Must Fix Before Release)

### 🔴 CRITICAL-1: Git Repository Corrupted
- **File:** `.git/` directory
- **Impact:** All git operations fail. Cannot create commits, branches, or check status.
- **Root Cause:** `.git/index` file is missing. `.git/HEAD` does not exist.
- **Evidence:** `git status` → `fatal: not a git repository`
- **Fix:** Restore HEAD and index from `ORIG_HEAD` (7b068a40a3081371275718ec7b3ce15e2d4da307) or rebuild from objects.
- **Risk:** HIGH — All version control is currently non-functional.

### 🔴 CRITICAL-2: Missing Build System Files
- **Files:** `build.gradle.kts` (root + app), `settings.gradle.kts`, `gradle.properties`
- **Impact:** Project cannot be built by any tool.
- **Root Cause:** Files exist only in worktree `serene-fermi-7f5b8b/`, not in main working tree.
- **Fix:** Copy build files from worktree to root.
- **Risk:** CRITICAL — No build possible.

### 🔴 CRITICAL-3: Missing App Entry Points
- **Files:** `AndroidManifest.xml`, `MainActivity.kt`, `NasTheme.kt`
- **Impact:** No app can be installed or launched.
- **Root Cause:** These files were lost during migration or live only in worktree.
- **Fix:** Recover from worktree or git objects.
- **Risk:** CRITICAL — App non-functional.

### 🔴 CRITICAL-4: All 8 Domain ViewModels Are Empty
- **Directories:** `auth/`, `browser/`, `device/`, `livestream/`, `monitor/`, `smarttools/`, `shared/`, `utils/`
- **Impact:** 9 unresolved imports in `CompositionLocals.kt` → compile error for every file in the project.
- **Import list:**
  - `com.nas.naswebdav.auth.AuthSessionViewModel`
  - `com.nas.naswebdav.browser.FileBrowserViewModel`
  - `com.nas.naswebdav.device.DeviceManagementViewModel`
  - `com.nas.naswebdav.livestream.LivestreamViewModel`
  - `com.nas.naswebdav.monitor.SystemMonitorViewModel`
  - `com.nas.naswebdav.smarttools.SmartToolsViewModel`
  - `com.nas.naswebdav.utils.CrashLogExporter`
  - `com.nas.naswebdav.utils.FormatUtils`
  - `com.nas.naswebdav.utils.MediaUtils`
- **Fix:** Recover all VM files from worktree `serene-fermi-7f5b8b/` or `old_WebDavViewModel.kt`.
- **Risk:** CRITICAL — Complete compile failure.

### 🔴 CRITICAL-5: Missing `GlobalUiViewModel`
- **File:** `CompositionLocals.kt:53`
- **Impact:** `val LocalGlobalUiVM = staticCompositionLocalOf<GlobalUiViewModel>` — type not defined anywhere in working tree.
- **Referenced in:** `LoginScreen.kt:94`, `BrowserScreen.kt:156`
- **Fix:** Restore or implement `GlobalUiViewModel` class.
- **Risk:** CRITICAL — Compile error.

### 🔴 CRITICAL-6: Missing `NasFile` Class
- **File:** `LoginScreen.kt:61` (`import com.nas.naswebdav.NasFile`)
- **Impact:** `NasFile` is used in `BrowserScreen.kt` (selectedFiles list) but not defined in any working tree file.
- **Fix:** Recover from old codebase or define in `NasModels.kt`.
- **Risk:** CRITICAL — Compile error.

---

## 4. High-Severity Issues

### 🟠 HIGH-1: Missing Utility Functions Referenced Across Codebase
The following functions are called but not defined in any working tree file:

| Function | Called From | Purpose |
|---|---|---|
| `pingUrlsForDisplay()` | LoginScreen:133 | Health-check URLs |
| `scheduleIdleDuplicateScan()` | LoginScreen:255 | Schedule WorkManager |
| `scheduleIdleSpeedTest()` | LoginScreen:255 | Schedule WorkManager |
| `scheduleFingerprintWorker()` | LoginScreen:255 | Schedule WorkManager |
| `sendWakeOnLan()` | LoginScreen:411 | WoL magic packet |
| `sendPowerCommandFromLogin()` | LoginScreen:424 | POST reboot API |
| `SearchHistoryManager` | BrowserScreen:164 | Search history |
| `SecurePrefsHelper` | LoginScreen:97-108 | Credential storage |
| `WebDavManager` | BrowserScreen:294 | WebDAV operations |
| `WolDialog`, `RebootConfirmDialog` | LoginScreen | Emergency dialogs |
| `FolderPickerDialog` | BrowserScreen:293 | Copy/Move dest |
| `AppStatusDialog`, `DialogType` | Multiple screens | Status display |

### 🟠 HIGH-2: Previous Build Errors (Last Attempted Build)
From `compile_err.txt`:
1. **WebDavManager.kt:977** — `companion` modifier inside `standalone object`
2. **LivestreamViewModel.kt:187** — Unresolved reference `extractApiError`
3. **LivestreamViewModel.kt:188** — Argument type mismatch (`Any` vs `String!`)
4. **LivestreamViewModel.kt:362** — Unresolved reference `title`

These errors existed before the migration and may persist in the worktree version.

### 🟠 HIGH-3: Previous Audit Critical Findings Still Open
From `PRODUCT_READINESS_20260724.md` and `deep_scan_report_final.md`:

| Finding | Status | Impact |
|---|---|---|
| AutoBackup cancel bypass (SMB→WebDAV fallback) | UNFIXED | User cannot cancel uploads |
| CancellationException swallowing | UNFIXED | Coroutine lifecycle broken |
| PiP crash on Android ≤7 | UNFIXED | Crash on older devices |
| LivestreamMonitorWorker exits after 5 errors | UNFIXED | Ghost recordings |
| DuplicateScanWorker god file (57KB) | PARTIALLY FIXED | Maintenance burden |
| OkHttp 96-thread overloading | UNFIXED | NAS CPU overload |
| BrowserScreen 2000+ line god component | UNFIXED | Recomposition perf |

### 🟠 HIGH-4: Redundant Typography Definitions
- **`Type.kt`**: Defines `SamsungOneFontFamily` and `AppTypography` using Material3 `Typography`
- **`DashboardTheme.kt:64`**: Defines a *second* `AppTypography` object (internal, different font sizes)
- **`DashboardTheme.kt:212`**: Defines yet another `NasTypography` using the second `AppTypography`
- **Impact:** Two competing typography systems. `Type.kt`'s `AppTypography` is unused if `NasTheme` from `DashboardTheme.kt` is the actual theme.
- **Fix:** Consolidate to one typography source. Remove `Type.kt`'s `AppTypography` or `DashboardTheme.kt`'s.

### 🟠 HIGH-5: SmbClient.kt — Password Stored in Memory
- **File:** `SmbClient.kt:78` — `pass.toCharArray()` creates password char array that is never zeroed
- **Impact:** Password remains in JVM heap until GC collects it
- **Fix:** Clear char array after authentication in `finally` block
- **Risk:** MEDIUM-HIGH — Sensitive data exposure on heap dumps

---

## 5. Medium-Severity Issues

### 🟡 MED-1: Hardcoded Colors Throughout UI
- `LoginScreen.kt`: 30+ hardcoded `Color(0xFF...)` values instead of `MaterialTheme.colorScheme`
- `BrowserScreen.kt`: Dozens more
- **Impact:** Dark/light theme support inconsistent, accessibility gaps
- **Previous audit noted:** 1,494 hardcoded colors across entire app
- **Fix:** Migrate to `MaterialTheme.colorScheme` (est. 24-40 hours)

### 🟡 MED-2: `@file:Suppress("DEPRECATION")` in Every Screen
- **Files:** `LoginScreen.kt`, `BrowserScreen.kt`, `DashboardTheme.kt`
- **Impact:** Suppresses warnings about deprecated APIs — may mask breaking changes when upgrading Compose/Material3
- **Fix:** Address underlying deprecations rather than suppressing

### 🟡 MED-3: Wildcard Imports
- Nearly every UI file uses `import com.nas.naswebdav.*` and `import androidx.compose.material3.*`
- **Impact:** Namespace pollution, potential ambiguity, harder refactoring
- **Fix:** Use explicit imports

### 🟡 MED-4: Excessive `remember` State in BrowserScreen
- **File:** `BrowserScreen.kt` — 20+ `remember` states at top-level composable
- **Impact:** Large recomposition scope, performance degradation on scroll
- **Fix:** Extract sub-composables, move state down, use `derivedStateOf`

### 🟡 MED-5: Missing ProGuard/R8 Rules
- No `proguard-rules.pro` found in working tree
- **Impact:** Release builds may strip needed classes or fail obfuscation
- **Fix:** Add ProGuard rules for SMB, OkHttp, Coil, and reflection-heavy classes

### 🟡 MED-6: Old/Dead Files at Project Root
- `old_WebDavViewModel.kt` (592KB!), `old_dialogs.kt`, `old_storage_dialogs.kt`, `restored_dialogs.kt`, `restored_storage.kt`
- `nas_ui.xml`, `nas_ui2.xml`, `nas_ui3.xml`, `nas_ui4.xml` (UI dump scratch files)
- `*.hprof` files (3 files, total ~4.5GB!) — heap dumps from debugging
- `logcat.txt` (68MB), `*.py` (utility scripts), `*.png` (screenshots)
- **Impact:** Repository bloat, confusion about source of truth
- **Fix:** Remove all non-source files, move scripts to `scripts/`

### 🟡 MED-7: LoginScreen Contains GuestPassScreen
- **File:** `LoginScreen.kt:442+` — `GuestPassScreen` composable is defined inside the same file
- **Impact:** File has 2 distinct screens in one file, violating single-responsibility
- **Fix:** Move to `GuestPassScreen.kt` (there's already a `GuestPassScreen.kt` — check for duplication)

---

## 6. Low-Severity Issues

### 🔵 LOW-1: SwipeDeleteRow Redundant Branch
- **File:** `SwipeDeleteRow.kt:60-68`
- The `if (!requireConfirmation && confirmDismiss())` and `else if (requireConfirmation && confirmDismiss())` branches do exactly the same thing — both call `onDelete()` and return `true`
- **Fix:** Simplify to: `if (confirmDismiss()) { onDelete(); true } else false`

### 🔵 LOW-2: Unnecessary `mutableStateOf` for `ipPingStatus`
- **File:** `LoginScreen.kt:122` — Uses `mutableStateOf<Map<String, Long>>` which triggers full recomposition when any value changes
- **Fix:** Use `mutableStateMapOf()` for granular updates

### 🔵 LOW-3: `markBrowserFilesViewed` synchronized on SharedPreferences
- **File:** `BrowserScreen.kt:101` — `synchronized(prefs)` holds a lock while doing JSON parsing
- **Impact:** Potential UI jank if called from compose thread
- **Fix:** Move to background dispatcher

### 🔵 LOW-4: BackupModels.kt Contains Only Type Aliases
- **File:** `backup/BackupModels.kt` — 14 lines of typealias to top-level classes
- **Impact:** Unnecessary indirection, could confuse readers
- **Fix:** Remove file; update imports to use top-level names directly

### 🔵 LOW-5: Missing `@Preview` Composables
- None of the 20 UI files contain `@Preview` functions
- **Impact:** Cannot preview UI in Android Studio without running app
- **Fix:** Add `@Preview` to each screen/component

### 🔵 LOW-6: No Unit Tests for UI
- `app/src/test/` directory exists but contains no test files
- `app/src/androidTest/` directory is empty
- **Impact:** Zero test coverage for presentation layer

---

## 7. Security Audit

### 🔴 SEC-1: Hardcoded Default Credentials
- **File:** `LoginScreen.kt:105` — `SecurePrefsHelper.getUser(context).getOrElse { "" }.ifEmpty { "daica" }`
- **Impact:** Default username "daica" is hardcoded; first login pre-fills this
- **Fix:** Remove default, let user type it manually

### 🟠 SEC-2: Password in Memory (SmbClient)
- **File:** `SmbClient.kt:78` — `pass.toCharArray()` not cleared after use
- **Risk:** Heap dump contains plaintext password
- **Fix:** Zero char array in `finally` block

### 🟠 SEC-3: Network Security Config Allows Cleartext
- **File:** `network_security_config.xml` — Allows cleartext for `192.168.100.254` and `100.90.135.102`
- **Risk:** Credentials sent over HTTP on LAN; acceptable for LAN-only but risky on shared networks
- **Note:** Documented and intentional for NAS LAN communication

### 🟡 SEC-4: Biometric Auth Bypass Window
- **File:** `LoginScreen.kt:322` — 300ms delay before triggering biometric (`delay(300)`)
- **Risk:** User could interact with form before biometric prompt appears
- **Fix:** Disable form inputs while biometric auto-trigger is pending

### 🟡 SEC-5: WoL MAC Address Stored in SharedPreferences (Unencrypted)
- **File:** `LoginScreen.kt:115` — `sharedPrefs.getString("mac_address", "")`
- **Risk:** Low (MAC is not secret), but inconsistent with credential storage approach

### ✅ SEC-6: CrashLogExporter Has Privacy Redaction
- **From docs:** Redacts URL userinfo, labeled secrets, Authorization headers
- **Status:** PASS

### ✅ SEC-7: BiometricPrompt Uses DEVICE_CREDENTIAL
- **File:** `LoginScreen.kt:279` — Allows PIN/pattern fallback
- **Status:** PASS (intentional for accessibility)

---

## 8. Performance Audit

### 🟠 PERF-1: BrowserScreen 2000+ LOC God Composable
- **Impact:** Massive recomposition scope; any state change redraws entire screen
- **Evidence:** 20+ `remember` states at root level
- **Fix:** Extract file grid, search bar, selection bar, sort menu into separate composables

### 🟠 PERF-2: `markBrowserFilesViewed` Sync on Main Thread
- **File:** `BrowserScreen.kt:101` — `synchronized(prefs)` + JSON parsing
- **Impact:** Frame drops when marking many files as viewed
- **Fix:** Move to `Dispatchers.IO`

### 🟡 PERF-3: LoginScreen Infinite Ping Loop
- **File:** `LoginScreen.kt:128` — `while(isActive)` with `delay(2000)` pings all known URLs
- **Impact:** Continuous network traffic even when user is not interacting
- **Fix:** Pause when screen not visible, reduce frequency, or use WebSocket

### 🟡 PERF-4: Image Loading Without Pagination in Some Views
- **BrowserScreen.kt:276** — `animateFloatAsState` recomputes on every image load
- **Impact:** Recomposition cascade for image-heavy folders

### ✅ PERF-5: SwipeDeleteRow Uses `positionalThreshold`
- Status: PASS — Efficient threshold calculation

---

## 9. Code Quality

### 🟠 QUALITY-1: Duplicate Typography System
- Two `AppTypography` definitions, two typography configurations
- **Fix:** Consolidate to single design token source

### 🟠 QUALITY-2: Dead Code in Working Tree
- 5 old/restored files at root (~900KB total)
- 3 heap dump files (~4.5GB total)
- Multiple `.py` scratch scripts at root level
- **Fix:** Remove, move to archive, or add to `.gitignore`

### 🟡 QUALITY-3: Type Aliases Add No Value
- `backup/BackupModels.kt` is 14 lines of `typealias` to already-accessible top-level types
- **Fix:** Delete file, use direct imports

### 🟡 QUALITY-4: Mixed Vietnamese/English in Code
- Some comments in Vietnamese, some in English, some mixed
- **Fix:** Standardize on one language for comments (suggest English for code, Vietnamese only in user-facing strings)

### 🟡 QUALITY-5: Inconsistent Error Handling Pattern
- `SmbClient.kt` properly re-throws `CancellationException` ✅
- LoginScreen uses `runCatching { ... }.getOrElse { ... }` everywhere, masking errors
- **Fix:** Use structured error handling, log exceptions before swallowing

---

## 10. Completeness Check

| Feature | Status | Notes |
|---|---|---|
| Login (LAN/Tailscale) | ✅ Implemented | UI present, but VM missing |
| Biometric Login | ✅ Implemented | Auto-trigger present |
| File Browser | ✅ Implemented | 2000+ LOC, missing VM |
| WebDAV CRUD | 🔧 UI present | VM logic missing |
| Auto Backup | 🔧 Models present | Worker logic missing |
| Livestream Monitor | 🔧 UI present | VM logic missing |
| Duplicate Scanner | 🔧 UI present | VM logic missing |
| System Monitor | 🔧 UI present | VM logic missing |
| Docker Management | ❓ Unknown | Not in working tree |
| Fan Control | ❓ Unknown | Not in working tree |
| WoL (Wake-on-LAN) | ✅ UI present | Network function missing |
| Guest Pass | ✅ UI present | API function missing |
| Dark Mode | ✅ Theme present | `NasDarkColorScheme` defined |
| Light Mode | ✅ Theme present | `NasLightColorScheme` defined |
| Tablet/Responsive | ✅ Implemented | `Responsive.kt` with3 breakpoints |
| Accessibility | 🔧 Partial | Previous audit: ~30 contentDescription added |
| i18n/RTL | ❌ Not implemented | All strings hardcoded Vietnamese |
| Unit Tests | ❌ Zero | Empty test directories |
| Instrumented Tests | ❌ Zero | Empty test directories |
| Crash Reporting | 🔧 Local only | CrashLogExporter exists; Sentry/Firebase missing |
| Error Recovery | ❓ Unknown | VM logic missing |

---

## 11. Scores (Out of 10)

| Dimension | Score | Rationale |
|---|---:|---|
| **Architecture** | 5 | Good Strangler Fig plan, but migration incomplete |
| **Maintainability** | 4 | Large files, no tests, broken git |
| **Security** | 5 | Good practices (biometric, encrypted prefs) but password in memory, hardcoded defaults |
| **Performance** | 5 | Some known perf issues, god composables, no profiling |
| **Readability** | 5 | Mixed languages, good comments, but massive files |
| **Scalability** | 4 | Domain VM split was correct direction, but unfinished |
| **Testing** | 1 | Zero tests in working tree |
| **Documentation** | 7 | Excellent docs (MAP.md, reviews, changelogs) |
| **Reliability** | 3 | Cannot compile; known open critical bugs |
| **Production Readiness** | 3 | Broken build, missing code, no crash telemetry |
| **OVERALL** | **4.2/10** | **NOT PRODUCTION READY** |

---

## 12. Recovery Plan (Priority Order)

### Phase 0: Restore Git (2-4 hours)
1. Restore `HEAD`: `echo "ref: refs/heads/main" > .git/HEAD` (or appropriate branch)
2. Restore `index` from `index.stash.741` or rebuild via `git read-tree`
3. Verify `git status` works
4. Create a clean commit point

### Phase 1: Restore Missing Files (4-8 hours)
1. Copy build system files from worktree to root
2. Restore `AndroidManifest.xml`, `MainActivity.kt`
3. Restore all domain VMs from worktree or `old_WebDavViewModel.kt`
4. Restore utility classes (`FormatUtils`, `MediaUtils`, `CrashLogExporter`, etc.)
5. Verify project compiles

### Phase 2: Fix Critical Bugs (8-16 hours)
1. Fix compile errors from `compile_err.txt`
2. Fix CancellationException swallowing
3. Fix AutoBackup cancel bypass
4. Fix LivestreamMonitorWorker exit-after-errors
5. Clear SMB password from heap

### Phase 3: Quality Sprint (40-80 hours)
1. Add unit tests for domain VMs (target: 200+ tests)
2. Migrate hardcoded colors to `MaterialTheme.colorScheme`
3. Break up BrowserScreen into sub-composables
4. Consolidate typography system
5. Clean up dead files and `.gitignore`

### Phase 4: Production (16-24 hours)
1. Integrate Crashlytics or Sentry
2. Add integration tests for login→browse→backup flow
3. Build and sign release APK
4. Add `proguard-rules.pro`
5. Create release checklist

---

## 13. Immediate Actions Required

1. **URGENT:** Restore git `HEAD` and `index` to make repository functional
2. **URGENT:** Copy all missing source files from worktree to main working tree
3. **URGENT:** Verify full project compiles after restoration
4. **HIGH:** Address all 6 CRITICAL findings before any new feature work
5. **HIGH:** Clean up the 4.5GB of heap dump files

---

*End of audit. Report generated 2026-08-01.*
