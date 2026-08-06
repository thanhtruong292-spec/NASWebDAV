# API Endpoint Map — Android ↔ Server

**Source of truth:**
- Android client: `app/src/main/java/com/nas/naswebdav/**/*.kt`
- Server: `nas_api_server.py` (Flask `@app.route(...)` decorators)

**Last verified:** 2026-07-21 (commit `18f40e5b`)
**Total:** 79 Android endpoint references, 84 server routes (incl. parameterized variants)
**How to re-verify:**
```bash
# Android endpoints
rg -oN '/api/[a-zA-Z0-9_/<>\-]+' app/src/main/java | sort -u
# Server routes
rg -oN '"/api/[a-zA-Z0-9_/<>\-]+"' nas_api_server.py | sort -u
```

---

## Legend

| Symbol | Meaning |
|---|---|
| ✅ | Client URL exactly matches server route — works |
| 🟡 | Client URL is a parameterized variant — verified match (`/api/foo/<bar>` ↔ `/api/foo`) |
| ⚠️ | Client URL exists but server route NOT FOUND → 404 always |
| 🔵 | Server has route but client never calls it → dead server code |
| 🟢 | Newly wired in commit `18f40e5b` (was orphan before) |
| 🟠 | Newly 404-handled in commit `18f40e5b` (was silent fail before) |

---

## A. ENDPOINTS THAT WORK (Client URL = Server Route)

### Auth & Session
| Android (file:line) | Client URL | Server route (line) |
|---|---|---|
| `AuthSessionViewModel.kt:197,279` | `/api/auth/authorize` | ✅ `POST /api/auth/authorize` |
| `AuthSessionViewModel.kt:386` | `/api/guest/create` | ✅ `POST /api/guest/create` |
| `AuthSessionViewModel.kt:439` | `/api/guest/revoke` | ✅ `POST /api/guest/revoke` |
| `DeviceManagementViewModel.kt:1168,1186` | `/api/auth/approve_ip` | ✅ `POST /api/auth/approve_ip` |
| `auth/AuthSessionViewModel.kt:133`, `WebDavManager.kt:305`, `monitor/SystemMonitorViewModel.kt:722` | `/api/ping` | ✅ `HEAD /api/ping` |

### Backup / Schedule / Sleep
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `backup/AutoBackupViewModel.kt:125,159`, `NasModels.kt:14` | `/api/backup/schedule` | ✅ `GET/POST /api/backup/schedule` (L7388, L7394) |
| `backup/AutoBackupViewModel.kt:267,299` | `/api/system/sleep_schedule` | ✅ `GET/POST /api/system/sleep_schedule` (L5688, L5697) |
| `backup/AutoBackupViewModel.kt:315` (🟢 fixed in `18f40e5b`) | `/api/system/hdd_spindown_now` | ✅ `POST /api/system/hdd_spindown_now` (L5717) |
| `monitor/SystemMonitorViewModel.kt:635` | `/api/backup/download` | ✅ `GET /api/backup/download` (L7590) |

### USB Import
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `backup/AutoBackupViewModel.kt:177`, `device/DeviceManagementViewModel.kt:870` | `/api/usb_import/status` | ✅ `GET /api/usb_import/status` |
| `backup/AutoBackupViewModel.kt:208`, `device/DeviceManagementViewModel.kt:916` | `/api/usb_import/settings` | ✅ `POST /api/usb_import/settings` (L7302) |
| `backup/AutoBackupViewModel.kt:220`, `device/DeviceManagementViewModel.kt:954` | `/api/usb_import/start` | ✅ `POST /api/usb_import/start` (L7331) |
| `backup/AutoBackupViewModel.kt:233`, `device/DeviceManagementViewModel.kt:993` | `/api/usb_import/cancel` | ✅ `POST /api/usb_import/cancel` (L7348) |
| `backup/AutoBackupViewModel.kt:255` (🟢), `device/DeviceManagementViewModel.kt:1035` | `/api/usb_import/resolve_conflicts` | ✅ `POST /api/usb_import/resolve_conflicts` (L7359) |

### Disk / SMART / Health
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `device/DeviceManagementViewModel.kt:257,453` | `/api/disk/smart` | ✅ `GET /api/disk/smart` |
| `monitor/SystemMonitorViewModel.kt:374` (🟢), `ui/dialogs/Dialogs.kt:2661` | `/api/disk/health` | ✅ `GET /api/disk/health` (L5102) |
| `monitor/SystemMonitorViewModel.kt:414` (🟢) | `/api/disk/health/history` | ✅ `GET /api/disk/health/history` (L5120) |
| `AutoDuplicateScanWorker.kt:68,82,104`, `DuplicateScanWorker.kt:213`, `IdleSpeedTestWorker.kt:53` | `/api/disk/fast_index`, `/api/system/idle` | ✅ L3182, L5459 |
| `DuplicateScanWorker.kt:821` | `/api/thumb/activity` | ✅ `GET /api/thumb/activity` |

### Docker / SMB / LAN / OMV / Fan / Service
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `device/DeviceManagementViewModel.kt:161` | `/api/docker/containers` | ✅ `GET /api/docker/containers` |
| `device/DeviceManagementViewModel.kt:197,212` | `/api/docker/power` | ✅ `GET/POST /api/docker/power` |
| `device/DeviceManagementViewModel.kt:728` (🟢) | `/api/docker/control` | ✅ `POST /api/docker/control` (L4059) |
| `device/DeviceManagementViewModel.kt:373,400` | `/api/lan/whitelist` | ✅ `GET/POST /api/lan/whitelist` |
| `device/DeviceManagementViewModel.kt:294,426` | `/api/omv/overview` | ✅ `GET /api/omv/overview` |
| `device/DeviceManagementViewModel.kt:139` | `/api/smb/toggle` | ✅ `POST /api/smb/toggle` (L3737) |
| `device/DeviceManagementViewModel.kt:230` | `/api/smb/status` | ✅ `GET /api/smb/status` (L3721) |
| `device/DeviceManagementViewModel.kt:515` | `/api/fan/control` | ✅ `POST /api/fan/control` (L7743) |
| `device/DeviceManagementViewModel.kt:745` | `/api/service/toggle` | ✅ `POST /api/service/toggle` (L3788) |
| `device/DeviceManagementViewModel.kt:536` | `/api/storage/usage` | ✅ `GET /api/storage/usage` (L3110) |
| `device/DeviceManagementViewModel.kt:481,688` | `/api/disk/speedtest` | ✅ `POST /api/disk/speedtest` (L3842) |

### Livestream / TikTok
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `LivestreamDiscoveryWorker.kt:38`, `LivestreamMonitorWorker.kt:188`, `LivestreamViewModel.kt:120,320` | `/api/livestream/status` | ✅ `GET /api/livestream/status` (L13414) |
| `LivestreamViewModel.kt:287` | `/api/livestream/record` | ✅ `POST /api/livestream/record` (L12702) |
| `LivestreamViewModel.kt:304` | `/api/livestream/stop` | ✅ `POST /api/livestream/stop` (L13591) |
| `LivestreamViewModel.kt:180` | `/api/tiktok/live_watch` | ✅ `GET /api/tiktok/live_watch` (L12569) |
| `LivestreamViewModel.kt:225` | `/api/tiktok/live_watch/add` | ✅ `POST /api/tiktok/live_watch/add` (L12628) |
| `LivestreamViewModel.kt:241` | `/api/tiktok/live_watch/remove` | ✅ `POST /api/tiktok/live_watch/remove` (L12664) |
| `LivestreamViewModel.kt:263` | `/api/tiktok/live_watch/settings` | ✅ `POST /api/tiktok/live_watch/settings` (L12686) |

### Monitor / Metrics / Status
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `monitor/SystemMonitorViewModel.kt:144` | `/api/status` | ✅ `GET /api/status` (L3038) |
| `monitor/SystemMonitorViewModel.kt:261` | `/api/metrics/history` | ✅ `GET /api/metrics/history` (L2949) |
| `monitor/SystemMonitorViewModel.kt:307`, `NasModels.kt:13` | `/api/status/realtime` | ✅ `GET /api/status/realtime` (L3087) |
| `monitor/SystemMonitorViewModel.kt:339`, `NasModels.kt:336` | `/api/report/daily` | ✅ `GET /api/report/daily` (L3000) |
| `monitor/SystemMonitorViewModel.kt:560`, `ui/dialogs/Dialogs.kt:3776`, `InsightsDialogs.kt:308` | `/api/system/insights` | ✅ `GET /api/system/insights` (L5493) |
| `monitor/SystemMonitorViewModel.kt:660` | `/api/processes` | ✅ `GET /api/processes` (L3621) |
| `monitor/SystemMonitorViewModel.kt:693` | `/api/processes/kill` | ✅ `POST /api/processes/kill` (L3697) |
| `monitor/SystemMonitorViewModel.kt:711` | `/api/process_state` | ✅ `GET /api/process_state` (L10554) |
| `device/DeviceManagementViewModel.kt:614` | `/api/system_logs` | ✅ `GET /api/system_logs` (L8508) |
| `device/DeviceManagementViewModel.kt:658` | `/api/cron/trash/clean` | ✅ `POST /api/cron/trash/clean` (L8494) |

### Thumbnail
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `smarttools/SmartToolsViewModel.kt:311,371` | `/api/thumb/status` | ✅ `GET /api/thumb/status` (L10521) |
| `smarttools/SmartToolsViewModel.kt:340,355,405` (🟢 L337 fixed) | `/api/thumb/control` | ✅ `POST /api/thumb/control` (L10567) — body `{action: "pause"\|"resume"}` |
| `NasUtils.kt:215,218`, `FingerprintWorker.kt`, `BrowserComponents.kt`, `DuplicateScanWorker.kt:188` | `/api/thumb` | ✅ `GET /api/thumb` |

### File Tools
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `browser/FileBrowserViewModel.kt:428`, `smarttools/SmartToolsViewModel.kt:248`, `LongRunningApiWorker.kt:27` | `/api/file/unzip` | ✅ `POST /api/file/unzip` (L8306) |
| `smarttools/SmartToolsViewModel.kt:432` | `/api/tools/smart_organize/scan` | ✅ `POST /api/tools/smart_organize/scan` (L9383) |
| `smarttools/SmartToolsViewModel.kt:467` | `/api/tools/smart_organize/execute` | ✅ `POST /api/tools/smart_organize/execute` (L9622) |
| `smarttools/SmartToolsViewModel.kt:503` | `/api/tools/smart_organize/status/<job_id>` | 🟡 `GET /api/tools/smart_organize/status/<job_id>` (L9675) — parameterized, works |
| `smarttools/SmartToolsViewModel.kt:532` (🟢), `ui/screens/BrowserScreen.kt:454` | `/api/tools/organize_legacy_videos` | ✅ `POST /api/tools/organize_legacy_videos` (L9315) |

### Torrent / Power / Screen Record / Social / Media
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `PowerActions.kt:158` | `/api/download` | ✅ `GET /api/download` |
| `PowerActions.kt:209` | `/api/torrent/add_file` | ✅ `POST /api/torrent/add_file` |
| `device/DeviceManagementViewModel.kt:779` | `/api/torrent/control` | ✅ `POST /api/torrent/control` (L7818) |
| `ui/screens/LoginScreen.kt:319` | `/api/power/reboot` | ✅ `POST /api/power/reboot` |
| `ScreenRecordService.kt:372,387,416,464` | `/api/screen_record/{start,segment,finish,cancel}` | ✅ all 4 routes |
| `SocialDownloadWorker.kt:33`, `LivestreamViewModel.kt:387` | `/api/social/download` | ✅ `POST /api/social/download` (L8179) |
| `NasApplication.kt:430`, `ui/screens/MediaScreens.kt:580,VideoPlayerScreen.kt:170` | `/api/media` | ✅ `GET /api/media` |
| `NasApplication.kt:665` | `/api/ping` (via `AppConfig.API_PORT`) | ✅ same route |

### Stream / Transcode
| Android (file:line) | Client URL | Server route |
|---|---|---|
| `ui/screens/MediaScreens.kt:718`, `VideoPlayerScreen.kt:191` | `/api/stream/transcode` | ✅ `GET /api/stream/transcode` (L9149) |

---

## B. ENDPOINTS WITHOUT SERVER ROUTE (Client → 404)

**Status: ✅ All removed (commit `360baacc`)**
All 6 orphan endpoints have been fully removed from both client code and UI.
No Kotlin runtime call now references a non-existent server route.

| Former Client URL | What was removed |
|---|---|
| `/api/telegram/test` | Test button in TelegramSettingsDialog + network call in DeviceManagementViewModel; kept local save only |
| `/api/stream/pipe` | LivestreamViewModel.startStreamPipe methods + SocialExtractorScreen pipe-mode calls; usePipeMode now false |
| `/api/stream/cancel` | LivestreamViewModel.cancelStreamPipe methods + SocialExtractorScreen back-button cancel |
| `/api/config/backups` | fetchNasConfigBackups method + list UI in DialogsNasConfigBackupDialog (deleted) |
| `/api/config/backup` | createNasConfigBackup, deleteNasConfigBackup methods + create/delete buttons (deleted) |
| `/api/config/restore` | restoreNasConfigBackup method + restore button (deleted) |

**If you need these features later:** implement the corresponding server routes first, then re-add the client calls.

---

## C. SERVER ROUTES WITHOUT CLIENT CALLER (Dead Server Code)

Routes declared in `nas_api_server.py` but never referenced from Android source. Useful for `curl`/scripted use but never invoked by the app.

| Server route | Server line | Note |
|---|---|---|
| `GET /api/ai/status` | 8588 | AI status — feature not exposed in UI |
| `GET /api/ai/tags` | 8544 | AI tags listing |
| `POST /api/ai/trigger` | 8571 | AI scan trigger |
| `POST /api/alerts/clear` | 8446 | Clears server alert queue |
| `GET /api/alerts/poll` | 8404 | Polls alerts — client uses `/api/system/insights` instead |
| `GET /api/cron/status` | 8455 | Cron status |
| `POST /api/disk/hash_batch` | 9861 | Server-side hash batch (commented: "phone handles it locally") |
| `POST /api/disk/trash_batch` | 9787 | Trash batch delete |
| `GET /api/disk/health/trend` | 5435 | Health trend (aggregated via `/api/system/insights`) |
| `GET /api/photos/timeline` | 4480 | Photo timeline — feature not in app |
| `GET /api/screen_record/status` | 9031 | Status — client only uses start/segment/finish/cancel |
| `GET /api/system/data_flow` | 5453 | Data flow (aggregated) |
| `GET /api/system/emmc_guard` | 5447 | eMMC guard (aggregated) |
| `GET /api/system/maintenance_advisor` | 5487 | Maintenance advisor (aggregated) |
| `GET /api/system/temperature_history` | 8476 | Temp history (client polls `/api/status`) |
| `GET /api/system/weekly_report` | 8386 | Weekly report |
| `GET /api/system/workload` | 5441 | Workload (aggregated) |
| `GET /api/tailscale/status` | — | Tailscale status (no client ref) |
| `GET /api/social/status/<job_id>` | — | Social download job status (no client polling) |
| `GET /api/report/generate` | 3024 | On-demand report generation |
| `GET /api/backup/{list,create,delete,restore}` | various | Server-side backup CRUD — Android client doesn't call these |
| `POST /api/power/shutdown`, `POST /api/power/suspend` | — | Power actions (Android calls `/api/power/reboot` only) |
| `POST /api/ytdlp/download`, `GET /api/ytdlp/status` | — | yt-dlp — feature not in app |
| `GET /api/stream/hls/<session_id>/<filename>` | 9206 | HLS segment — used internally by video player, not direct API call |

---

## D. KNOWN GAPS / TODO

1. **Telegram test** — UI button exists, server has no route. Fix: implement `POST /api/telegram/test` in `nas_api_server.py` that calls the Bot API with the user's `bot_token` + `chat_id`.
2. **Stream pipe** — `StreamPipeWorker.kt` is implemented but never enqueued. Either wire it into a share-intent handler or delete the worker + stub VM methods.
3. **Config backup UI** — 4 buttons in SystemMonitorViewModel call routes that don't exist. Either implement `/api/config/*` server endpoints or remove the UI section.
4. **Livestream stubs (Phase 3b)** — 8 methods in `LivestreamViewModel.kt` are empty `viewModelScope.launch { /* TODO Phase 3b */ }`. UI doesn't crash but does nothing.
5. **SmartTools stubs (Phase 2b)** — `organizeLegacyVideos` now wired ✓; `triggerSmartOrganizeScan`/`executeSmartOrganize` are dead duplicates of working methods.

---

## E. CHANGELOG

- **2026-07-21 (commit `18f40e5b`)** — Fixed 6 endpoint mismatches, wired `organizeLegacyVideos`, added 404-handling for 4 orphan routes.
- **2026-07-21 (this doc)** — Initial map created.