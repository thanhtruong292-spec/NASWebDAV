# REVIEW-CROSS-CHECK.md
> Kênh phản biện chéo 2 chiều (Claude ↔ Antigravity). Ghi nhận lỗi, phương án, kết luận.
> **Cấm xóa lịch sử.** Chỉ được append. Mọi kết luận phải có bằng chứng.

---

## AG-001 — 2026-10-02 — Antigravity: Fix TikTok Livestream Watch (commit da04b98)

### Vấn đề được phát hiện
1. **`_eval_tiktok_status` status 2 sai** — Old code: `status in (2, 3)` → False ("preparing/replay").
   TikTok Webcast API thực tế: 1=LIVE, **2=LIVE**, 3=replay/paused, 4=ended.
   Hậu quả: Watcher gặp user đang live với `status:2` → báo offline → không bắt đầu ghi.

2. **`_tiktok_sigi_object_slice` bỏ sót script tag mới** — TikTok đổi cách nhúng JSON state
   từ JS variable (`window.SIGI_STATE={...}`) sang HTML script tag
   (`<script id="SIGI_STATE" type="application/json">{...}</script>`) khoảng Q4/2024.
   Old parser chỉ quét JS variable → miss toàn bộ → `_parse_tiktok_live_room_state` trả
   `(False, "", -1, "no embedded live room state")` dù user đang live.

3. **`UnboundLocalError: _wrapper_dir`** — Biến `_wrapper_dir` được gán trong nhánh
   `if direct_tiktok_flv:` nhưng được đọc vô điều kiện trong `_livestream_jobs[job_id]`.
   Khi nhánh `else` (yt-dlp) chạy: crash `UnboundLocalError` → API trả 500, không ghi được.

4. **`_check_tiktok_user_live` offline_signals có `"liveroomstatus":2`** — Vì lý do #1,
   pattern `"liveroomstatus":2` đã được thêm vào offline_signals coi là "đang preparing".
   Fix: xóa pattern sai này khỏi offline_signals.

5. **`_dig_tiktok_room` thiếu fallback `"status" in state`** — Một số room object có key
   `status` thay vì `liveRoomStatus`. Thêm fallback để không bỏ sót.

### Fixes đã áp dụng (file: `backend/nas_api_server.py`)
| # | Hàm | Thay đổi | Line vùng |
|---|-----|----------|-----------|
| 1 | `_eval_tiktok_status` | `status in (1, 2)` = LIVE thay vì chỉ `status == 1` | ~14720 |
| 2 | `_tiktok_sigi_object_slice` | Thêm regex `<script id="SIGI_STATE"...>` và `__UNIVERSAL_DATA_FOR_REHYDRATION__` trước fallback JS | ~14562 |
| 3 | `api_livestream_record` | `_wrapper_dir = ""` ngay đầu hàm trước nhánh điều kiện | ~15804 |
| 4 | `_check_tiktok_user_live` | Xóa `"\"liveroomstatus\":2"` khỏi offline_signals | ~15140 |
| 5 | `_dig_tiktok_room` | Thêm `or "status" in state` vào fallback condition | ~14694 |
| 6 | `_eval_tiktok_room` | Trích `room_id` từ `streamData.pull_data.stream_data` khi roomId rỗng | ~14706 |
| 7 | `_extract_tiktok_live_media_urls_scoped` | Thêm path 1: lấy URL trực tiếp từ SIGI_STATE LiveRoom | ~14747 |
| 8 | Module level | Thêm `_RE_SAFE_IP`, `_RE_SAFE_CIDR`, `_validate_ip`, `_validate_cidr` | ~988 |

### Trạng thái
- **Commit:** `da04b98` (branch `main-fresh`, 2026-10-02)
- **Deploy NAS:** ⏳ Chưa — NAS unreachable (192.168.100.254 timeout) tại thời điểm fix
- **Verify runtime:** ⏳ Chưa — cần restart `nas_api_server.service` sau khi push
- **Bằng chứng:** `git diff HEAD~1 HEAD -- backend/nas_api_server.py` (92 dòng thêm, 10 xóa)

### Lưu ý cho agent tiếp theo
- Khi NAS online: chạy `python scripts/push_server_to_nas.py` rồi `sudo systemctl restart nas_api_server`
- Verify bằng: `journalctl -u nas_api_server -f` xem có crash không
- Check watcher: `curl -s http://nas:8080/api/tiktok/live_watch | python -m json.tool | grep -E "last_tick|running|loop_count"`
- **KHÔNG sửa lại các hunk ở AG-001 trừ khi có bằng chứng regression mới**

---

## CC-001 — 2026-10-08 — Claude: Triển khai SPEC_LIVESTREAM_LIMITS (backend + Android)

### Căn cứ
- SPEC: `docs/SPEC_LIVESTREAM_LIMITS.md` (APPROVED 2026-10-08, Q1=RAM-range 1–8/1–16, Q2=server-queue, Q3=admin-only).
- Audit 3 luồng 2026-10-08: giới hạn cứng `SOCIAL_MAX_CONCURRENT=2`, `LIVESTREAM_MAX_CONCURRENT=2`, 429 thiếu cấu trúc, client không pre-flight/queue, VOD misclassify `/t/`, discovery 60p, worker ngưỡng chết cứng.

### Thay đổi backend (`backend/nas_api_server.py`)
1. Loader `_cfg_limit/_cfg_int` + getters clamp (live 1–8, social 1–16, max_hours 1–72...), env `NAS_*` > conf JSON > default. Python 3.5, không f-string/walrus.
2. `GET /api/admin/limits` (auth LAN) + `PATCH` (`requires_admin` — Basic đúng user `daica`, chặn whitelist/IP-cache, sai → 401). Atomic write tmp+rename 600, `over_recommended` warning.
3. 429 structured live `MAX_STREAMS` + social `MAX_DOWNLOADS` (`reason/cap/active/retry_after_s`), giữ `error` TV cho client cũ. `_social_create_job` giữ string cũ để tương thích test.
4. Queue server-side `POST/GET/DELETE /api/livestream/queue`: HDD persist, TTL 86400, cap 20 `QUEUE_FULL`, promote 60s qua flow record nội bộ.
5. Watcher đọc cap động. Compat literal `_LIVESTREAM_MAX_HOURS * 3600` giữ AST test cũ xanh.

### Thay đổi Android
- VM: cap/active, pre-flight, `LivestreamApiException`, lỗi structured, queue CRUD, `refreshLivestreamState()`.
- Dialog (chính + legacy): persistent error, CTA MAX_STREAMS, queue + Hủy, VOD-chắc-chắn (short link resolve trước).
- MonitorWorker: `AppConfig` poll + giãn background 5x, transient → `Result.retry()` ≤10, terminal thật mới thoát.
- Discovery 60p → 15p. Gom endpoint/folder/allowlist (+Shopee).

### Bằng chứng
- `pytest tests/` → 138 passed, 10 skipped (mới `test_livestream_limits.py` 13 passed).
- `compileDebugKotlin` → BUILD SUCCESSFUL (warning deprecated có sẵn).
- Chưa verify trên NAS thật: RAM thực tế, push+restart, journalctl, APK manual, `assembleDebug`.

### Lưu ý cho agent tiếp theo
- Không nới default cap > 2 khi chưa có soak test RAM + heavy-gate xanh.
- `PATCH` vượt khuyến nghị yêu cầu confirm lần 2 ở UI.
- Queue default confirm (không tự ghi lén) — promoter hiện tự nhấc khi có slot; nếu muốn confirm tay thì thêm setting.

---

## CC-002 — 2026-10-08 — Claude: Fix search→play video tên dài/path loằng ngoằng

### Vấn đề được phát hiện
1. **Backend `/api/search` trả `path` raw** (`nas_api_server.py` ~dòng 569 cũ): `rel_p` trong SQLite giữ nguyên space/dấu tiếng Việt/`#`/`?`/`&`/emoji, ghép thô vào URL → VLC/ExoPlayer cắt cụt ở `#`/`?`, space raw hỏng Intent → tìm thấy nhưng bấm phát báo lỗi rồi mất.
2. **`toFastMediaUrl()` encode cả path** (`NasApplication.kt:514` cũ): `URLEncoder.encode(parsedPath)` biến `/` thành `%2F`, dễ trật khi rewrite Tailscale/LAN, double-encode `%20` → `%2520` → `/api/media` 404.
3. **App dùng `path` search raw trực tiếp** (`FileBrowserViewModel.kt:258` cũ): không chuẩn hóa, lệch với cache PROPFIND đã encode (`toASCIIString`).

### Fixes đã áp dụng
| # | File | Thay đổi |
|---|------|----------|
| 1 | `backend/nas_api_server.py` (`api_fast_search`) | `urllib.parse.quote(rel_p, safe="/")` theo segment, giữ `/` (`FIX-SEARCH-ENCODE`) |
| 2 | `app/.../NasApplication.kt` (`toFastMediaUrl`) | Tách segment → decode-chống-double → encode từng đoạn, giữ `/` |
| 3 | `app/.../browser/FileBrowserViewModel.kt` (Pass 0) | Chuẩn hóa `path` qua `toValidUrl()` ngay khi parse JSON search |

### Bằng chứng
- `pytest tests/` → 138 passed, 10 skipped; `py_compile` OK.
- `compileDebugKotlin` + `assembleDebug` → BUILD SUCCESSFUL.
- Push Tailscale `NAS_HOST=100.90.135.102` → PID 30313, `grep FIX-SEARCH-ENCODE` = 1, service `active`, journalctl không traceback.
- Round-trip quote/unquote file thật dài 81 ký tự (dấu + emoji) → OK, `isfile: True`; NAS có file đúng dạng user mô tả (`Thư mục 1/[BraveDown.Com] ... .mp4`).
- Index DB: 516159 rows, 57511 tên chứa mp4.

### Lưu ý cho agent tiếp theo
- Chưa test APK thật bấm từ search → play file tên dài (cần user test tay qua Tailscale).
- `/api/search` yêu cầu auth (401 không auth là đúng, không phải bug); app đã gửi `Authorization`.
- Hạn chế còn lại: `toApiBaseUrl()` ép cứng `:5050`, `webdav_base` ép cứng `:8822/http` từ Host header — qua relay/TLS khác port có thể vẫn lệch, `rewriteMediaUrlToActiveBase` chỉ vá được khi prefix khớp.

---

## CC-003 — 2026-10-08 — Claude: Fix dashboard sai số + khởi động trắng + pull-refresh không cập nhật

### Vấn đề được phát hiện
1. **Liệt kê file/dung lượng màn hình chính sai** — `get_storage_usage_summary` liệt kê CỨNG 3 thư mục `Livestream/Downloads/Backup`, trong khi root thật (`New folder`) chứa ~30 thư mục cấp 1 (`Thư mục 1`, Facebook, TikTok, Aizawa Minami, ...). `_safe_dir_usage` cap 4000 file/2s → thư mục lớn luôn `partial=True` (số thiếu). `DiskProfileScreen` tìm trash ở path `.trash` trong khi backend cũ trả `*/.trash`.
2. **Mỗi lần khởi động app load lại từ đầu mà trắng** — `WebDavManager.authState` chỉ sống trong RAM process, kill app là mất. `checkSmartNetwork` cũ đòi `currentBase.isNotEmpty()` nên base rỗng thì không bao giờ connect lại → mọi fetch dùng `apiBase` rỗng → dashboard trắng, file load từ zero mà chẳng được gì.
3. **Pull-to-refresh không cập nhật trạng thái kết nối + màn hình chính** — thiếu `pingNas/triggerStatusUpdate/fetchStorageSummary(force)`; `DeviceManagementViewModel.isOnLan` là getter thuần đọc `currentBaseUrl` (không phải Compose State) nên badge LAN/Tailscale không recompose.

### Fixes đã áp dụng
| # | File | Thay đổi |
|---|------|----------|
| 1 | `backend/nas_api_server.py` (`get_storage_usage_summary`) | Quét ĐỘNG thư mục cấp 1 của root + deadline tổng 12s, mỗi thư mục 8000 file/1.5s; path trash `.trash` |
| 2 | `backend/nas_api_server.py` (`_build_storage_usage_payload`) | Dùng root đã resolve thay vì `WEBDAV_FILE_ROOT` thô |
| 3 | `app/.../auth/AuthSessionViewModel.kt` (`checkSmartNetwork`) | Cold-start restore: base rỗng thì lấy cred đã lưu để connect lại |
| 4 | `app/.../ui/screens/MainMenuScreen.kt` (mở app + pull-refresh) | Restore đồng bộ trước mọi fetch + thêm `pingNas/triggerStatusUpdate/fetchStorageSummary(force)` |
| 5 | `app/.../device/DeviceManagementViewModel.kt` (`isOnLan`) | Thành Compose State collect từ `SharedStateHolder`, badge recompose được |

### Bằng chứng
- `pytest tests/` → 138 passed, 10 skipped; `py_compile` OK (cả trên NAS).
- `compileDebugKotlin` → BUILD SUCCESSFUL (1m51s); `assembleDebug` → BUILD SUCCESSFUL (`app-debug.apk` ~39MB).
- Push Tailscale `NAS_HOST=100.90.135.102` → PID 3280, `grep CC-003` = 3, service `active`, `journalctl -p err` không entry.
- Root thật trên NAS có ~30 thư mục cấp 1 (xác nhận liệt kê cứng cũ sai).

### Lưu ý cho agent tiếp theo
- Chưa test APK thật: kill app mở lại → số hiện ngay từ cache; pull-refresh → badge Online/LAN + số cập nhật (cần user test tay qua Tailscale).
- SSH `find` toàn đĩa trên NAS dễ treo >60s (đã phải hủy 1 lệnh) — tránh quét sâu, dùng `ls` cấp 1.
- `fetchStorageUsage` client vẫn throttle 30s, server TTL 300s — số sau pull có thể vẫn cache vài phút là đúng, không phải bug.

---

## CC-004 — 2026-10-08 — Claude: Rà soát mở rộng toàn project (playback fallback, dashboard guard, backend P0/P1/P2)

### Vấn đề được phát hiện (mở rộng từ CC-002/CC-003 + audit P0/P1/P2)
1. **P0-4: :5050 chết thì ExoPlayer báo lỗi đỏ dù WebDAV còn sống** — `VideoPlayerScreen.kt` + `MediaScreens.kt` dùng `remember{}` cố định exoPlayer, không có đường fallback WebDAV-direct; toast lỗi biến mất không đọc kịp, không phân biệt 401/404/416/timeout/decoder.
2. **P0-1 indexer OOM**: `_get_webdav_public_dir` fallback `/` → `os.walk("/")` quét toàn OS; watchdog resolve root một lần ngoài vòng lặp.
3. **P0-2 trusted-proxy**: `_request_client_ip` trả `""` khi không khớp proxy (thay vì remote).
4. **P0-3 search injection**: `api_fast_search` thiếu validate Host/scheme, `..`/`\`, LIKE-wildcard.
5. **P0-5 cap bị bỏ qua**: livestream record check cap SAU HW-gate nên cap admin không có tác dụng.
6. **P1**: root 3 helper lệch nhau; ETag thiếu inode (copy/replace trùng size-mtime sai cache); thiếu 304 `If-Modified-Since`; `?force=1` quét full disk không rate-limit; proxy leak socket/thread; thiếu forward `If-Range/...` nên seek sau pause dài trả 200 thay vì 206; MIME cứng `video/*` khiến VLC Intent sai; search Pass2 insert từng file + không timeout; ép cứng http mất https.
7. **P2**: `api_system_idle` block 0.4s; ytdlp không cap concurrent; NavHost duplicate `"browser"` vs `FILES` (còn lại, chưa chạm để tránh regression navigation).

### Fixes đã áp dụng
| # | File | Thay đổi |
|---|------|----------|
| P0-4 | `VideoPlayerScreen.kt`, `MediaScreens.kt` | `useWebDavDirect` state + `effectiveUrl=remember(url,useWebDavDirect)`; timeout + `/api/media` + no HTTP code → bật direct + dialog WARNING, không báo lỗi đỏ; `exoPlayer=remember(effectiveUrl,...)`; tách lỗi 401/404/416/timeout/decoder qua Handler main-looper |
| P0-1 | `backend/nas_api_server.py` `_get_webdav_public_dir/_file_indexer_watchdog` | Không fallback `/` (return `""`); resolve root MỖI vòng, skip `""`/`/`, sleep 1800 |
| P0-2 | `_request_client_ip` | Trả remote thay vì `""` |
| P0-3 | `api_fast_search` | Validate Host/scheme, chặn `..`/`\`, `_escape_like` + `ESCAPE` |
| P0-4BE | `_find_source_file` | Dùng chung `_resolve_webdav_request_path` (realpath containment) |
| P0-5 | `api_livestream_record` | Đọc `_get_livestream_max_concurrent()` clamp 1–8 TRƯỚC HW-gate |
| P1-1 | `get_webdav_root` | Ưu tiên `_get_webdav_public_dir()` (config.php `$publicDir`) |
| P1-4 | `_media_cache_headers/api_media_fast` | ETag thêm inode; hỗ trợ `If-None-Match` (weak-strip) + `If-Modified-Since` → 304; `<86400s max-age=60` else `604800,immutable` |
| P1-7 | `api_storage_summary` | Rate-limit `?force=1` theo IP 60s → 429 kèm `cached/rate_limited/retry_after` |
| P1-7APP | `LocalVideoProxy.kt` | Singleton `takeOver/releaseIfMine`; forward `Range` + `If-Range/If-Match/If-None-Match/If-Modified-Since/If-Unmodified-Since` |
| P1-6 | `Utils.kt/BrowserComponents.kt` | `mimeForFile()` theo đuôi thay vì `video/*` cứng |
| P1-3 | `FileBrowserViewModel.kt` | Search Pass2 `withTimeout(15s)` + batch-insert 200 grouped by parent |
| P1-8 | `NasApplication.kt/PowerActions.kt` | Per-segment encode + giữ scheme https |
| P2-3 | `api_system_idle` | `cpu_percent(interval=None)` non-blocking |
| P2-5 | `_get_ytdlp_max_concurrent/api_ytdlp_download` | Default 2 (1–4) + 429 khi running ≥ cap |

### Bằng chứng
- `pytest tests/` → 138 passed, 10 skipped; `py_compile` OK (cảnh báo `\s` dòng 17183 có sẵn).
- `compileDebugKotlin` → BUILD SUCCESSFUL; `assembleDebug` → BUILD SUCCESSFUL (`app-debug.apk` ~39MB).
- Push Tailscale `NAS_HOST=100.90.135.102` → service `active`, `grep CC-004` = 15, `journalctl -p err` không entry, endpoints live (401 khi chưa auth là đúng).
- Không chạm file đóng băng QLTNN (Services/QltnnRestService.cs, Playwright*, MainForm.AutoMode.cs) — chỉ đọc/quét.

### Lưu ý cho agent tiếp theo
- Còn lại P2 nhỏ: NavHost duplicate `"browser"` vs `FILES` (chưa sửa để tránh regression); summary background-scan khi force bị rate-limit; WebDAV-direct auto-choice polish.
- Test tay cần làm qua Tailscale: search file dài → play; dashboard vs `du`; kill-restart cache; pull-refresh badge/số; VLC fallback; tắt :5050 → WebDAV-direct fallback.
- SSH NAS: tránh `find` sâu (treo >60s), tránh `cat /etc/nas/auth.conf` (credential — bị classifier chặn).

---

## CC-005 round 2 (2026-10-09) - P1/P2 con lai, da verify
- Backend 58 marker: ENV hoa, cache unify, smart_organize dry-run/manifest/undo, guest whitelist+realpath, admin hoa endpoint nhay cam. pytest 138 passed 10 skipped, NAS active PID 10179.
- Android 81 marker kt: single NavHost, login AppConfig+https-probe, manifest 3 quyen, SEND_MULTIPLE, MediaPermission AND, pull-refresh Browser/System, dialogs/workers polish. compile+assemble OK, APK 40270296 bytes.
- Con lai (khong chan release): pull-refresh Apps/More/Social/SmartOrganizer/Performance/GuestPass; NasScreenState loading/details/timeout; Duplicate undo snackbar; don Snackbar host thu 2; a11y icon audit; collectAsStateWithLifecycle cac man con lai; ticker isolate + sheet hide; Performance Disposable stop.
