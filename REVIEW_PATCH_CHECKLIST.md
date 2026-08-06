# REVIEW TONG THE DU AN NASWebDAV

> Review date: 2026-06-02 | Base commit: e25d698 (HEAD)
> Scope: Toan bo du an — nas_api_server.py, 26 file Kotlin, lich su 30 commit
> CHI REVIEW — KHONG CHINH SUA CODE

---

## PHAN I — TINH NANG BI MAT QUA CAC LAN COMMIT

**Ket luan: KHONG co tinh nang nao bi mat vinh vien.** 30 commit gan nhat (daea2e1..e25d698) them +3968 dong, xoa -904 dong. Tat ca function, API endpoint, va Composable deu con ton tai.

Cac thay doi dang "xoa roi thay the":
| Commit | Bi xoa | Thay the bang |
|--------|--------|---------------|
| e25d698 | JSONL disk health history write | SQLite `disk_health_history` table |
| e25d698 | `fetchDiskHealth()` trong periodic loop | `fetchNasInsights()` tich hop ca disk health |
| e25d698 | `max(CPU, HDD)` fan logic | HDD-only fan control |
| cae18a7 | 30s metrics polling | Tach: realtime 5s (nhe) + history 10min (nang) |
| 3dc3110 | `while(true)` polling loops trong MainMenuScreen | Event-driven/debounced refresh |
| c25f3a5 | Old video player button layout | Redesigned controls (cung cac nut) |
| 8f2dd37 | `AGENTS.md` | Xoa file rac, khong phai tinh nang |

**Luu y:** `fetchDiskHealth()` van TON TAI nhu function (line 3601) va van duoc goi tu `DiskHealthDialog` (Dialogs.kt:3083, 3108). No chi bi xoa khoi PERIODIC LOOP — khong mat.

---

## PHAN II — LOI TRONG nas_api_server.py (Python Server)

### P1. [CRITICAL] SQL Injection pattern trong `_db_set_json` / `_db_get_json`

**Line:** 3985, 4000
```python
cur.execute("INSERT OR REPLACE INTO %s ..." % table, ...)
```
Table name dung `%` format truc tiep vao SQL. Tat ca caller hien truyen literal nhung pattern nay nguy hiem — mot caller tuong lai nhan user input se tao SQL injection.

**Huong sua:** Them whitelist:
```python
_ALLOWED_TABLES = {"hardware_status", "disk_health_history", "scheduler_state", "runtime_state"}
assert table in _ALLOWED_TABLES, f"Invalid table: {table}"
```

---

### P2. [CRITICAL] 43 cho `sqlite3.connect()` KHONG dung context manager — connection leak

**Line:** Toan bo file (43 call sites theo grep)
Pattern lap lai:
```python
conn = sqlite3.connect(DB_PATH, timeout=5.0)
cur = conn.cursor()
cur.execute(...)    # <-- neu exception o day
conn.commit()
conn.close()        # <-- khong bao gio chay
```
KHONG co `with conn`, KHONG co `try/finally`. Bat ky exception nao giua `connect()` va `close()` (disk full, locked, json loi) se leak connection + file handle. Tren NAS 1GB RAM chay 24/7, leak tich luy gay memory pressure va SQLite WAL bloat.

**Huong sua:** Tao helper context manager hoac dung `with sqlite3.connect(...) as conn:` (SQLite3 context manager tu commit/rollback, van can close rieng).

---

### P3. [HIGH] Watchdog sleep tinh sai — wake moi 1h thay vi 30 ngay

**Line:** 4316-4319
```python
if next_scan_at <= 0 or now_ts >= next_scan_at:
    _disk_health_sample_once()    # <-- cap nhat DB voi next_scan_at moi
    _disk_health_prune_old_records()
sleep_for = max(3600, min(21600, int((next_scan_at or now_ts + 3600) - now_ts)))
#                                     ^^^^^^^^^^^^^ STALE — van la gia tri CU
```
Sau khi scan, `next_scan_at` local van giu gia tri cu (0 hoac timestamp da qua). Ket qua `sleep_for` luon bi clamp ve `3600` (1h). Watchdog wake 24 lan/ngay thay vi 1 lan/30 ngay — ton I/O doc SQLite va CPU wake.

**Huong sua:** Re-read sau khi scan:
```python
if next_scan_at <= 0 or now_ts >= next_scan_at:
    _disk_health_sample_once()
    _disk_health_prune_old_records()
    state = _disk_health_scheduler_state()  # <-- re-read
    next_scan_at = int(state.get("next_scan_at") or 0)
```

---

### P4. [HIGH] `_system_log_once_cache` lon vo han — memory leak

**Line:** 4349-4357
```python
_system_log_once_cache = {}   # module-level, never pruned
def _add_system_log_once(key, ...):
    _system_log_once_cache[key] = now   # them mai, khong bao gio xoa
```
Moi unique key ton tai vinh vien. Server chay hang thang -> cache lon dan. Tren 1GB RAM day la risk thuc te.

**Huong sua:** Cap size 500 entries, evict oldest khi day, hoac dung TTL dict.

---

### P5. [HIGH] `monitor_journalctl` — Popen stdout khong close khi exception

**Line:** 720-725
```python
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
for line_b in iter(proc.stdout.readline, b''):
    # ... neu exception o day -> zombie process + leaked fd
```
Khong co `try/finally: proc.kill(); proc.stdout.close()`.

**Huong sua:** Wrap trong `try/finally` hoac dung `with` pattern.

---

### P6. [HIGH] `_update_process_state` giu lock khi lam blocking I/O

**Line:** 4410-4413
`_save_process_state_locked()` va `_db_set_json()` thuc hien file write + SQLite write TRONG KHI giu `_process_state_lock`. Khi SQLite cham (HDD spin-up, WAL checkpoint), moi thread khac goi `_update_process_state` hoac `_load_process_state` bi block.

**Huong sua:** Copy data duoi lock, release lock, roi persist.

---

### P7. [MEDIUM] `api_disk_health()` — `sampling` luon False

**Line:** 4479-4488
```python
sample = dict(_disk_health_last_sample) if _disk_health_last_sample else None
if not sample:
    sample = _db_get_json("hardware_status", "disk_health_current", {}) or {}
# ...
"sampling": sample is None,   # sample la {} (truthy), KHONG BAO GIO None
```
Client Android se khong bao gio biet server dang "sampling".

**Huong sua:** Dung bien `has_sample` rieng:
```python
has_sample = bool(sample)  # check TRUOC fallback
```

---

### P8. [MEDIUM] `_DISK_HEALTH_SAMPLE_INTERVAL_SEC` — dead constant gay nham lan

**Line:** 3970, 4216, 4243
Constant `= 1800` van ton tai va duoc truyen cho `_read_dmesg_recent(seconds=1800)` va `_update_process_state(sample_interval_sec=1800)`. Nhung watchdog KHONG con dung no de schedule — da thay bang adaptive intervals (30d/7d/24h). Gia tri 1800 cho dmesg la hop ly (xem log 30 phut), nhung ten constant gay an tuong sai rang watchdog van chay moi 30 phut.

**Huong sua:** Doi ten thanh `_DMESG_WINDOW_SEC = 1800` de phan anh dung muc dich.

---

### P9. [MEDIUM] Duplicate prune — `DELETE FROM disk_health_history` chay 2 lan

**Line:** 4261 (trong `_disk_health_sample_once`) + 4293 (trong `_disk_health_prune_old_records`)
Ca hai chay cung lenh DELETE. Va trong watchdog (line 4317-4318), `_disk_health_sample_once()` roi `_disk_health_prune_old_records()` goi lien tiep => 2 lan DELETE cung dieu kien.

**Huong sua:** Xoa DELETE trong `_disk_health_sample_once`, giu o `_disk_health_prune_old_records`.

---

### P10. [MEDIUM] `safe_run_cmd` flag whitelist cung nhac

**Line:** ~1058
Hardcoded `allowed_flags`. Moi khi them subprocess call voi flag moi -> `ValueError` runtime. Da phai bypass cho `smartctl -A -H`.

**Huong sua:** Log warning thay vi raise, hoac dung allowlist per-command.

---

### P11. [LOW] Duplicate `import shutil` — 8 lan

**Line:** 28, 148, 475, 1842, 7830, 8027, 8174, 8332
Module-level import o line 28 la du. 7 import con lai la thua.

---

### P12. [LOW] `concurrent.futures` import nhung chi dung o 3 cho

**Line:** 36 (import), 8601, 9106, 11048
Import o top-level nhung chi dung trong 3 ham muon. Khong sai nhung thua memory khi import eagerly.

---

## PHAN III — LOI TRONG KOTLIN (Android App)

### K1. [HIGH] `onCleared()` khong cancel cac Job — resource leak

**File:** WebDavViewModel.kt:3426-3429
```kotlin
override fun onCleared() {
    super.onCleared()
    try { webSocket?.close(1000, "ViewModel cleared") } catch (_: Exception) {}
}
```
KHONG cancel: `metricsPollingJob`, `dashboardRealtimeJob`, `statusJob`, `streamPipeJob`, `livestreamObserverJob`, `foregroundRefreshJob`. Them 7+ `WorkManager.getWorkInfosByTagFlow().collect {}` chay vinh vien trong viewModelScope ma khong co Job reference de cancel rieng.

**Thuc te:** `viewModelScope` tu cancel khi ViewModel bi destroy — nen loi nay LOW hon ly thuyet. Nhung cac flow collect canh tranh tai nguyen cho den khi GC thu hoi.

**Huong sua:** Store Job references, cancel trong onCleared.

---

### K2. [MEDIUM] Race condition: `realtimeMetricInFlight` khong thread-safe

**File:** WebDavViewModel.kt:326, 2680, 2684, 2725
```kotlin
private var realtimeMetricInFlight = false  // khong @Volatile, khong AtomicBoolean
// Doc o Main thread (line 2680), ghi o IO thread (line 2725)
```
TOCTOU race: 2 coroutine co the dong thoi doc `false`, ca 2 vao ham -> 2 request dong thoi.
Cung pattern cho `usbImportStatusInFlight` (line 463, 4010, 4039).

**Huong sua:** Them `@Volatile` hoac dung `AtomicBoolean`.

---

### K3. [MEDIUM] `fetchDiskHealth()` guard set TRONG coroutine — race window

**File:** WebDavViewModel.kt:3602-3604
```kotlin
fun fetchDiskHealth() {
    if (isFetchingDiskHealth) return          // check tren Main
    viewModelScope.launch(Dispatchers.IO) {
        withContext(Dispatchers.Main) { isFetchingDiskHealth = true }  // set SAU launch
```
Giua `if` check va `launch` co race window: 2 rapid calls deu pass guard truoc khi flag duoc set. So sanh voi `fetchNasInsights()` (line 3661) da set flag TRUOC launch — pattern dung.

**Huong sua:** Set `isFetchingDiskHealth = true` TRUOC `viewModelScope.launch`.

---

### K4. [MEDIUM] `fetchRealtimeMetricPoint()` — khong backoff khi NAS offline

**File:** WebDavViewModel.kt:2680-2727
Khi NAS offline, ham fail -> log warning -> 5s sau thu lai -> fail -> log -> ...
Hang tram warning/phut, ton pin va bandwidth.

**Huong sua:** Exponential backoff: 5s -> 10s -> 20s -> 60s max. Reset khi thanh cong.

---

### K5. [MEDIUM] `maxPoints` khong match `metricsHours` o poll rate 5s

**File:** WebDavViewModel.kt:2704-2710
```kotlin
val maxPoints = when (metricsHours) {
    1 -> 720       // 720 points / (720 points/h @ 5s) = 1h OK
    6 -> 1440      // 1440 / 720 = 2h — CHI DU 2H, KHONG PHAI 6H
    else -> 1440   // tuong tu
}
```
Bieu do "6 gio" chi hien 2h data gan nhat. User thay data bi cat.

**Huong sua:** `maxPoints = metricsHours * 3600 / 5` (pollInterval) hoac dung downsampling.

---

### K6. [MEDIUM] `fetchDailyReport()` — `isDailyReportLoading` bi ket khi early return

**File:** WebDavViewModel.kt:2730-2771
```kotlin
withContext(Dispatchers.Main) { isDailyReportLoading = true }
try {
    // ...
    if (!resp.isSuccessful) return@launch    // SKIP finally
    if (j.has("error")) return@launch        // SKIP finally
    // ...
} catch (_: Exception) {}
withContext(Dispatchers.Main) { isDailyReportLoading = false }  // KHONG PHAI finally
```
`return@launch` o line 2741/2743 thoat coroutine KHONG chay dong reset o line 2771. Loading spinner quay mai.

**Huong sua:** Doi thanh `try { ... } finally { withContext(Main) { isDailyReportLoading = false } }`.

---

### K7. [LOW] `DiskHealthDialog` van goi `fetchDiskHealth()` rieng — chua migrate

**File:** Dialogs.kt:3083-3084, 3108
```kotlin
viewModel.fetchDiskHealth()
viewModel.fetchDiskHealthHistory(7)
```
`DiskProfileBottomSheet` da migrate sang `fetchNasInsights()`. Nhung `DiskHealthDialog` (dialog cu) van goi standalone `fetchDiskHealth()` — tao request thua len server. Neu dialog cu da khong con dung thi la dead code.

**Huong sua:** Migrate hoac xoa dialog cu.

---

### K8. [LOW] Empty coroutine body — waste

**File:** WebDavViewModel.kt:1835-1837
```kotlin
viewModelScope.launch(Dispatchers.IO) {
    // Removed redundant fetch log
}
```
Launch coroutine khong lam gi. CPU waste nho nhung code ban.

**Huong sua:** Xoa block.

---

### K9. [LOW] `temperatureHistory` tao ArrayDeque moi moi 5s

**File:** WebDavViewModel.kt:2713-2716
Moi lan nhan realtime point: tao ArrayDeque moi -> copy 40 items -> them 1 -> gan lai. GC pressure khong can thiet.

**Huong sua:** Dung mutable list pattern nhu `metricsHistory`.

---

### K10. [LOW] Missing `key` trong LazyColumn items

**File:** MainMenuScreen.kt:~4273, ~4422
`items(displayProcesses.size) { index -> }` khong co `key`. Compose khong diff dung khi list thay doi.

**Huong sua:** Them `key = { displayProcesses[it].pid }`.

---

## PHAN IV — DANH GIA CHUC NANG DA HOAN THANH

| STT | Tinh nang | Trang thai | Danh gia |
|-----|-----------|------------|----------|
| 1 | Adaptive disk health schedule (30d/7d/24h) | HOAN THANH | Tot — giam SMART I/O dang ke |
| 2 | SQLite state centralization (4 bang) | HOAN THANH | Tot — schema ro, co index, cold-start restore |
| 3 | `/api/status/realtime` endpoint | HOAN THANH | Tot — chi doc RAM cache, khong I/O |
| 4 | Fan control HDD-only | HOAN THANH | Tot — loai bo CPU oscillation |
| 5 | Realtime metric polling (Android) | HOAN THANH | Tot nhung can backoff (K4) |
| 6 | Segmented screen recording | HOAN THANH | Robust — co ScreenRecordService rieng |
| 7 | USB import dual-write migration | HOAN THANH | An toan — fallback JSON con |
| 8 | Duplicate scan (MainMenu + Worker) | HOAN THANH | On dinh sau nhieu fix |
| 9 | Livestream watcher + TikTok | HOAN THANH | On dinh sau c573134 |
| 10 | Photo timeline SQLite cache | HOAN THANH | Giam HDD reads |
| 11 | LAN media streaming optimize | HOAN THANH | ExoPlayer buffer tuned |
| 12 | Video player redesign | HOAN THANH | Tat ca control con |
| 13 | OMV service toggle | HOAN THANH | Tap-to-enable/disable tu menu |
| 14 | Auto backup integration | HOAN THANH | WorkManager + singleton state |
| 15 | NAS config backup/restore | HOAN THANH | API + UI |

---

## PHAN V — KE HOACH HANH DONG CHI TIET

### PHASE 1: CRITICAL + HIGH (nen lam truoc khi deploy tiep)

| # | Ticket | File | Line | Mo ta | Estimated |
|---|--------|------|------|-------|-----------|
| 1 | P1 | nas_api_server.py | 3979-4007 | Them `_ALLOWED_TABLES` whitelist trong `_db_set_json`/`_db_get_json` | 5 min |
| 2 | P2 | nas_api_server.py | 43 sites | Chuyen 43 cho `sqlite3.connect` sang `try/finally: conn.close()` hoac tao helper `_db_conn()` context manager | 30 min |
| 3 | P3 | nas_api_server.py | 4316-4319 | Re-read `_disk_health_scheduler_state()` sau scan, cap nhat `next_scan_at` | 5 min |
| 4 | P4 | nas_api_server.py | 4349 | Them cap size cho `_system_log_once_cache` (evict khi >500) | 5 min |
| 5 | P5 | nas_api_server.py | 720-725 | Wrap `monitor_journalctl` Popen trong try/finally | 5 min |
| 6 | P6 | nas_api_server.py | 4410 | Copy data duoi lock, persist ngoai lock | 10 min |
| 7 | K1 | WebDavViewModel.kt | 3426 | Store Job refs, cancel trong onCleared | 15 min |
| 8 | K6 | WebDavViewModel.kt | 2730-2771 | Wrap fetchDailyReport trong try/finally reset flag | 5 min |

**Subtotal Phase 1: ~80 min**

### PHASE 2: MEDIUM (nen lam trong sprint tiep theo)

| # | Ticket | File | Line | Mo ta | Estimated |
|---|--------|------|------|-------|-----------|
| 9 | P7 | nas_api_server.py | 4479 | Fix `sampling` flag logic | 3 min |
| 10 | P8 | nas_api_server.py | 3970 | Doi ten `_DISK_HEALTH_SAMPLE_INTERVAL_SEC` -> `_DMESG_WINDOW_SEC` | 3 min |
| 11 | P9 | nas_api_server.py | 4261 | Xoa duplicate DELETE trong `_disk_health_sample_once` | 3 min |
| 12 | K2 | WebDavViewModel.kt | 326, 463 | Them `@Volatile` cho `realtimeMetricInFlight` + `usbImportStatusInFlight` | 3 min |
| 13 | K3 | WebDavViewModel.kt | 3602 | Set `isFetchingDiskHealth = true` truoc launch | 3 min |
| 14 | K4 | WebDavViewModel.kt | 2680 | Them exponential backoff cho `fetchRealtimeMetricPoint` | 15 min |
| 15 | K5 | WebDavViewModel.kt | 2704 | Fix maxPoints calculation theo metricsHours | 5 min |
| 16 | P10 | nas_api_server.py | ~1058 | Chuyen safe_run_cmd sang log warning thay vi raise | 10 min |

**Subtotal Phase 2: ~45 min**

### PHASE 3: LOW (khi co thoi gian)

| # | Ticket | File | Mo ta | Estimated |
|---|--------|------|-------|-----------|
| 17 | K7 | Dialogs.kt | Migrate DiskHealthDialog sang fetchNasInsights hoac xoa | 10 min |
| 18 | K8 | WebDavViewModel.kt:1835 | Xoa empty coroutine body | 1 min |
| 19 | K9 | WebDavViewModel.kt:2713 | Dung mutable list cho temperatureHistory | 5 min |
| 20 | K10 | MainMenuScreen.kt | Them key cho LazyColumn items | 5 min |
| 21 | P11 | nas_api_server.py | Xoa 7 duplicate `import shutil` | 3 min |
| 22 | P12 | nas_api_server.py | Move `concurrent.futures` import vao ham dung no | 3 min |
| 23 | — | nas_api_server.py | Xoa `_DISK_HEALTH_HISTORY_FILE` tham chieu trong `_update_process_state` | 3 min |
| 24 | — | nas_api_server.py | Them `Cache-Control: max-age=3` cho `/api/status/realtime` | 3 min |
| 25 | — | nas_api_server.py | Tang `hardware_status.latest` write interval tu 30s -> 120s | 3 min |

**Subtotal Phase 3: ~36 min**

---

**TONG CONG: 27 items, ~161 min estimated**
- Phase 1 (Critical+High): 8 items, 80 min — uu tien cao nhat
- Phase 2 (Medium): 8 items, 45 min
- Phase 3 (Low): 11 items, 36 min

---

## PATCH EXECUTION STATUS (2026-06-02)

### Da apply
- `WebDavViewModel.kt`: fix corruption `${...}` trong `revokeGuestPass()` + `setFanMode()`
- `WebDavViewModel.kt`: thumbnail API base URL, realtime metric AtomicBoolean/backoff, daily report finally, guest revoke rollback
- `Dialogs.kt`: `DiskHealthDialog` -> `fetchNasInsights()`
- `MainMenuScreen.kt`: `LazyColumn` items key theo `pid`
- `nas_api_server.py`: 7 DB hot paths -> `timeout=` + `try/finally close`

### Da verify
- `git diff --check` -> clean
- `findstr` -> khong con `+ D +`, `String.fromCharCode`, `.url('...')`
- `sqlite3.connect(DB_PATH)` khong con site nao thieu `timeout=` (ngoai comment/docstring)

### Build
- `./gradlew.bat assembleDebug` bi chan boi Android SDK local:
  - `Error parsing ...\platforms\android-35\package.xml`
  - license chua accept: `Android SDK Build-Tools 36`, `Android SDK Platform 35`



### Final status
- Code patch sweep: done
- Remaining blocker: local Android SDK licensing/package.xml issue, external to repo
- `git diff --check`: clean
- Temp scripts cleaned

### Final build status
- `compileSdk/targetSdk` keep at `35` in `app/build.gradle.kts`
- Gradle now points to workspace SDK mirror at `.android-sdk` via `local.properties`
- `./gradlew.bat assembleDebug --no-daemon` => **BUILD SUCCESSFUL**
- APK output: `app/build/outputs/apk/debug/app-debug.apk`
