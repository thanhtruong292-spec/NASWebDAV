# NAS API worker starvation — 2026-11-13

## Observed failure

- Authenticated loopback `/api/ping` and `/api/status` time out at 4 seconds.
- Port 5050 has queued connections; SIGUSR1 thread dump shows all six Waitress
  workers executing `os.path.isfile` inside `api_livestream_status`.
- Each status request scans the recording directory once per historical job,
  including metadata lookups for unrelated files before filtering by job stem.

## Required behavior

- Completed jobs return the metadata recorded by the watchdog, without rescanning.
- Active jobs use their known output path first; fallback lists each directory
  once per request and checks job identity before touching file metadata.
- Jobs without a known output or stem never claim another recording.
- GET status cannot finalize or stop a recording.
- Status refresh failures preserve the last known CPU/RAM/disk snapshot.
- Storage summary observes its scan deadline even within a large directory.
- Daily-report and insights fetches honor their existing refresh intervals.

## Verification

- [x] Endpoint regressions reproduce the original defects, including watchdog scans.
- [x] All pass after the backend fix; cache-recovery tests also pass.
- [x] Full local backend suite: 166 passed, including the large-directory deadline.
- [x] Compile staged backend using NAS Python 3.5.3.
- [x] Review the patch against the exact downloaded NAS source.
- [x] Standards, functional and security review of the changed paths.
- [x] Activate after the recording ended naturally; recheck media children before restart.
- [x] Measure loopback APIs: ping 40 ms, status 23 ms, livestream status 16 ms.
- [x] Concurrent check: six status requests plus storage scan and ping all succeed;
  status 0.549–0.819 s, ping 0.552 s, scan 45.115 s with `partial=true`.
- [ ] Verify app API requests over Tailscale after ADB reconnects.
- [x] Android build and all 94 unit tests pass, no failures or skips.
- [x] Verify all nine manifest component class definitions exist in APK DEX.
- [x] Installed APK on SM-G998U1 using `adb install -r` (2026-11-14).
- [x] Live LAN dashboard shows CPU/RAM, HDD 2.66 / 3.58 TB and partial scan label.
- [x] Foreground recovery tested from a canonical launcher task: HOT return,
  Downloads retained (five subdirectories), then LAN route restored.

## Deployment evidence

- Base: actual `/opt/nas_api_server.py` downloaded using SSH over Tailscale.
- Original SHA256: `7fa6b2322219c70b25e58a5a3f4cc56bc94d7952c4a403df28109654de62b59d`.
- Patched SHA256: `d5719f5d20aa2a1294a8d3bd4422c891627a159feed0acb84e451fdc5424f71d`.
- Backup: `/opt/nas_api_server.py.backup-20261113-worker-starvation` on NAS.
- New service PID after activation: 10713; first status returned CPU/RAM/disk.
- Android report/insights throttling and storage scan timestamp fixes are installed.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, version
  `1.0.127 (da04b98 · 261114.0051)`, including the compact filter chips.
