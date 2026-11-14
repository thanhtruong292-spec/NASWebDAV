# Persistent file index and LAN/search verification

Spec: `.specify/specs/incremental-file-index.md`.

- [x] Preserve SQLite names/paths and per-directory scan stamps across restarts.
- [x] Discover additions inside existing nested directories; reconcile deletes/renames.
- [x] Keep old rows after failed reads; expose incomplete coverage to Android.
- [x] Index public same-volume directory aliases under their logical URLs; reject cycles/private mounts.
- [x] Fail closed when configured publicDir becomes unavailable, rather than selecting a broader parent.
- [x] Use the existing path index for scoped substring queries; preserve literal %, _, + and accents.
- [x] Return Room matches before the network reply; cancel superseded HTTP/PROPFIND work.
- [x] Finish authoritative empty results without BFS; continue discovery for explicitly partial results.
- [x] Save API descendants under their actual parent paths rather than the searched root.
- [x] Healthy authenticated LAN avoids Tailscale probes; try WebDAV before optional system API.
- [x] Android: 105 tests, no failures; built APK 1.0.127 (da04b98 / 261114.0716), installed via ADB.
- [x] APK DEX definitions include all nine manifest components.
- [x] Backend snapshot before the final fast-path change: 185 passed, 3 Windows symlink skips.
- [x] Actual NAS Python 3.5.3 compilation and Linux alias/add/delete/restart/cycle/private-boundary smoke passed.
- [x] Read-only review across spec, standards and security; root scope/partial-results/cache-parent issues fixed.
- [x] Isolated migration of a backup of the real NAS SQLite: 308.65 seconds; 454,957 current rows, 828 directories, Downloads 245 descendants; no unready directories; peak RSS 99,048 KiB.
- [x] Unchanged second pass enumerated zero directories (2.716 seconds before the pending fast-path change).
- [x] NAS production-function search on the validation DB: Livestream 83.6–83.9 ms warm (first 231 ms); Downloads no-match 15.8–17.4 ms; Samsung Notes no-match 14.1–15.8 ms. These are NAS-side times, not phone/LAN throughput.
- [x] Optimize unchanged normal directories; always revalidate direct aliases. Linux regression reproduced a same-inode ancestor-retarget policy failure in V1 and passed in V2/V3.
- [x] Refresh metadata for at most300 returned regular files so growing recordings show current size/mtime. V2 failed the in-place-growth case; V3 passed.
- [x] V3 actualNASPython3.5.3 fixture passed alias/add/delete/restart/cycle/private-boundary, unchanged fastpath, same-inode alias retarget, and in-place-growth cases. Evidence: `index-final-v3-runtime-check.txt`.
- [x] V3 measured on separate real-data DB: unchanged828dirs0.238–0.300s, zero enumeration; Livestream300results105–106ms warm; first search after freshness expiry326ms. Evidence: `index-final-v3-timings.txt`.
- [ ] Full backend suite for V3: attempted `python -m pytest tests/ -v`; six collection errors because local backend source was deleted by Kaspersky. NAS installed runtimes cannot run the unchanged modern test harness (no pytest; Python3.8 lacks ast.unparse). Earlier185passes belong to the earlier snapshot, not V3.
- [ ] Deploy staged backend safely; running livestreams currently prevent an uninterrupted API restart.
- [ ] Verify live API readiness/results after deployment.
- [ ] Phone search/resume visual QA on Tailscale for this final APK.
- [ ] LAN upload/download benchmark deferred by explicit user instruction: phone is away from home LAN.

Additional reuse measurement before the pending fast path: unchanged passes 1.904/1.881 seconds; first search after the 5-second freshness window took 2,071 ms. Warm query speed alone does not represent that refresh case.

The live API remains on baseline SHA256 `d5719f5d20aa2a1294a8d3bd4422c891627a159feed0acb84e451fdc5424f71d`.
The final internally reviewed candidate is `/opt/nas_api_server.py.index-final-candidate-20261114-v3`, SHA256 `a8bcf63a146709fb854a9fba8f416eee89496ad42c6e1fc2f9c05108e2c642d6`.
It includes the two reviewed raw-media-cookie header removals. Kaspersky still flagged the preceding cookie-hardened candidate; V3 has not been classified clean. User forbids external source submission; protection settings remain unchanged.
Validation DB is separate: `/var/lib/nas_fast_index.validation-20261114.db`.
Do not replace the live SQLite while the API/indexer is running.
Actual systemd KillMode=control-group and no ExecReload: applying needs a restart that terminates the active recording processes. Last check found two ffmpeg recordings. Obtain a concrete restart decision before stopping them.

User decision: keep recordings and do not deploy V3. Preserve the deployed
baseline and active sessions. Do not apply automatically when recordings end;
deployment remains pending the user's later decision.
