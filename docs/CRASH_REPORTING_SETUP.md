# Crash Reporting Setup

**Status:** local-only export with privacy-safe redaction  
**Date:** 2026-07-25

## Current state

Crash data is captured at two levels:

1. **Uncaught exception handler** (in `NasApplication.onCreate`). When the app process is about to die from an unhandled exception, a synchronous Room insert writes the exception class and message into `system_log` with `type = "CRASH"`, then re-throws so the system handler can still write tombstones/logcat.

2. **Timber `DatabaseLogTree`** (planted when debug + logging enabled). Every WARN/ERROR log is persisted to Room as well.

3. **CrashLogExporter** (`utils/CrashLogExporter.kt`). On user request (System Log share button) it queries recent CRASH/ERROR logs from Room, applies privacy-safe redaction, and writes a timestamped `.txt` file to app cache. At most **3** crash-export files are retained at any time; older exports are pruned before each new write.

### Privacy redaction rules

`CrashLogExporter.buildCrashLogText()` passes every log message through `redactUrlUserinfo()` which strips:

| Pattern | Example | Redacted to |
|---|---|---|
| URL userinfo (`http://user:pass@host`) | `http://admin:s3cret@192.168.1.10/api` | `http://***@192.168.1.10/api` |
| Schemeless userinfo (`user:pass@host`) | `user:pass@nas.local/api` | `***@nas.local/api` |
| Bare username before host (`user@host`) | `myuser@nas.local/path` | `***@nas.local/path` |
| Labeled secrets (`password=secret`) | `password=hunter2` | `password=***` |
| Bearer/Basic auth headers | `Authorization: Basic dXNlcjpwYXNz` | `Authorization: Basic ***` |

**Rule:** any credential-bearing URL userinfo, labeled password/token/secret key-value, or Authorization header value is replaced before it enters the exported file.

### Current fallback (no remote provider)

Without a remote crash reporting service the only diagnostics path is:

1. User reproduces the issue.
2. User opens System Log and taps the share button.
3. A crash-export `.txt` file is shared via Android share sheet.
4. Developer receives the file and reads logs manually.

This works for reproducible issues but cannot detect or aggregate field failures on user devices.

## Adding a remote provider

### 1. Choose a service

Two common options for Android:

- **Firebase Crashlytics** -- free tier, automatic ANR/crash grouping, device metadata, symbolication via `mapping.txt`. Requires a Firebase project (`google-services.json`) and the `com.google.gms.google-services` + `com.google.firebase.crashlytics` plugins.
- **Sentry** -- open-source server option, more flexible data controls, optional self-hosting. Uses `io.sentry:sentry-android` without a Firebase dependency.

Either choice should coexist with the existing `SystemLogger` Room fallback. The remote provider handles production telemetry; the local Room table stays for in-app diagnostics and offline support.

### 2. Add the dependency

For Firebase Crashlytics, in `gradle/libs.versions.toml`:

```toml
[versions]
firebaseBom = "33.x.x"          # current stable
crashlytics = "19.x.x"          # current stable

[libraries]
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebaseBom" }
firebase-crashlytics = { group = "com.google.firebase", name = "firebase-crashlytics" }
```

In `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.google.gms.google-services") apply false
    id("com.google.firebase.crashlytics") apply false
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
}
```

For Sentry, add the dependency directly:

```toml
[versions]
sentry = "7.x.x"

[libraries]
sentry-android = { group = "io.sentry", name = "sentry-android", version.ref = "sentry" }
```

### 3. Respect the privacy rules

Before sending any data to a remote provider, enforce these constraints:

- **Never attach raw exception messages verbatim if they contain credential-bearing URLs.** Pass every message through `CrashLogExporter.redactUrlUserinfo()` before attaching to the remote provider. The existing `internal` method is reusable for this purpose.
- **Never attach user-supplied server URLs, usernames, or tokens as custom tags or breadcrumbs.** If you need a tag for server identification, use a hashed or truncated form (e.g. the host only).
- **Disable automatic breadcrumb collection** on network requests if the OkHttp interceptor attaches `Authorization` headers. The current `NasApplication.fastApiClient` interceptor injects `Authorization`; Sentry's OkHttp integration would capture it as a breadcrumb. Disable or filter it.
- **User consent:** require explicit opt-in before enabling the remote provider. Persist the consent decision in `SharedPreferences` (the existing `nas_debug` prefs or a new `crash_reporting_prefs` file). If the user declines, keep the local-only fallback.
- **Test export before production:** run `CrashLogExporter.buildCrashLogText()` against a test log containing `http://admin:s3cret@nas.local/api` and verify the exported text contains `***@` instead of the credential.

### 4. Wire the uncaught exception handler

In `NasApplication.onCreate()`, after the existing crash handler, add:

```kotlin
// Firebase Crashlytics
com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
    .setCrashlyticsCollectionEnabled(crashReportingEnabled)
```

The existing handler (which writes to Room with a 500ms timeout) must remain as the primary local fallback. The remote provider's handler runs afterward.

### 5. Map `mapping.txt` uploads

For Crashlytics, enable automatic symbol upload in the build file:

```kotlin
plugins {
    id("com.google.firebase.crashlytics") apply true
}

firebaseCrashlytics {
    mappingFileUploadEnabled = true
}
```

Without this, stack traces in the Crashlytics console will be obfuscated and unreadable.

### 6. Verify

After integration, verify these scenarios:

| Scenario | Expected result |
|---|---|
| Force a crash with `throw RuntimeException("http://admin:s3cret@host/api")` | Crash appears in remote dashboard; message contains `***@` |
| Force an ANR | ANR appears in remote dashboard with correct stack trace |
| `CrashLogExporter` share without remote provider | Local `.txt` file still works unchanged |
| User disables remote reporting in settings | Remote provider is silent; local logs still saved |

## Files touched by this preparation

| File | Change |
|---|---|
| `app/src/main/java/com/nas/naswebdav/utils/CrashLogExporter.kt` | Added `redactUrlUserinfo()`, `pruneOldExports()`, `MAX_CRASH_EXPORTS` |
| `app/src/test/java/com/nas/naswebdav/utils/CrashLogExporterTest.kt` | New: redaction + retention unit tests |
| `app/src/main/res/xml/file_paths.xml` | Already had `<cache-path name="crash_logs" path="." />` for FileProvider share |
| `app/src/main/AndroidManifest.xml` | FileProvider already declared for crash-export shares |
| `docs/CRASH_REPORTING_SETUP.md` | This file |
