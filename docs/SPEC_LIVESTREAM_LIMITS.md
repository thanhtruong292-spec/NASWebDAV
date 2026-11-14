# SPEC — Gỡ giới hạn cứng Livestream/Social (config động + hàng đợi)

> Trạng thái: APPROVED 3 chốt 2026-10-08 (Q1=nới theo RAM, Q2=server-side queue, Q3=admin-only). Chưa sửa code.
> Ngày: 2026-10-08. Căn cứ audit 3 luồng 2026-10-08 + code hiện tại.
> Phạm vi: `backend/nas_api_server.py` (17384 dòng, Python 3.5, NAS RK3328 1–2GB RAM)
> + Android `livestream/LivestreamViewModel.kt`, `LivestreamMonitorWorker.kt`,
> `LivestreamDiscoveryWorker.kt`, `ui/dialogs/LivestreamRecordDialog.kt`,
> `ui/screens/SocialExtractorScreen.kt`.

## 1. Vấn đề

- Backend chặn cứng:
  - `nas_api_server.py:436` `SOCIAL_MAX_CONCURRENT = 2` → `:608-609`
    trả `"Max concurrent downloads reached (%d)..."`.
  - `:439` `LIVESTREAM_MAX_CONCURRENT = 2` → `:16448-16454` trả 429
    `{"error": "NAS đang ghi tối đa %d luồng, thử lại sau.", "reason": "max_concurrent"}`.
  - Đếm capacity gồm `recording/starting/stopping/timeout/finalizing` + claims 180s (`:16442-16447`).
- Client không hỗ trợ:
  - `startLivestreamRecord()` (`LivestreamViewModel.kt:288-331`) POST thẳng,
    không pre-flight `activeLivestreams.size`, không parse `reason=max_concurrent`,
    lỗi chỉ `livestreamMessage = "Lỗi: ${e.message}"` (`:326`).
  - `throwOnUnsuccessfulResponse` (`:219-224`) + `extractApiError` passthrough thô,
    không mã lỗi có cấu trúc → lỗi full-slot hiển thị y như link hỏng.
  - Dialog auto-clear mọi message sau 10s (`LivestreamRecordDialog.kt:343-348`),
    phát hiện lỗi bằng prefix tiếng Việt `startsWith("Lỗi")` (`:350`) — giòn.
- Hệ quả UX: user bấm "BẮT ĐẦU GHI" mới biết đầy slot (lỗi muộn, tốn round-trip),
  không có nút [Cho vào hàng đợi] / [Theo dõi user] / gợi ý quality nhẹ hơn.
- Rủi ro nếu chỉ đổi `2 → N`: mỗi live = 1 ffmpeg + 1 yt-dlp 24/7 + watchdog 60s/vòng
  (`:13618`) + auto-stop 12h (`_LIVESTREAM_MAX_HOURS = 12`, `:13071,13861-13863`).
  RK3328 1GB RAM → tăng concurrent bừa = OOM kill service. Phải giữ default 2
  nhưng cho chỉnh động + bảo vệ bởi heavy-gate.

## 2. Mục tiêu / Phi mục tiêu

Mục tiêu:
- (G1) Mọi giới hạn concurrency/capacity đọc từ env `NAS_*` > `/etc/nas/nas_api.conf`
  (JSON) > default, đổi được lúc runtime qua `GET/PATCH /api/admin/limits` không restart
  (trừ port/threads/pid-path yêu cầu restart + log warning rõ).
- (G2) Lỗi full-slot có cấu trúc: `reason`, `code=MAX_STREAMS`, `cap`, `active`,
  `retry_after_s` để client render CTA theo ngữ cảnh.
- (G3) Client pre-flight + hàng đợi server-side: chặn sớm khi `active >= cap`, dialog
  "NAS đang bận (x/cap)" + [Cho vào hàng đợi] [Theo dõi user này] [Thử quality nhẹ].
  Hàng đợi sống qua restart NAS (persist JSON trên HDD), có TTL + API xem/xóa,
  watchdog tự nhấc job đầu khi có slot trống (confirm theo setting, default confirm).
- (G4) Không hồi quy OOM: default giữ 2/2, range cấu hình nới theo RAM thực tế
  (livestream 1–8, social 1–16, clamp + warning khi vượt ngưỡng khuyến nghị theo RAM),
  heavy-gate (`_background_heavy_work_allowed`, `:3049-3088`) vẫn là chốt chặn cuối.
- (G5) Sửa các bug cứng liên quan trực tiếp tới flow đầy slot (VOD misclassify `/t/`,
  sync không refresh list, worker tự sát sau lỗi transient).

Phi mục tiêu (đợt sau):
- i18n 376 chuỗi, 1494 màu cứng, chẻ god files, Sentry remote, SMB lifecycle.
- Đổi default cap > 2 trên RK3328 khi chưa có soak test RAM.

## 3. Thiết kế backend

### 3.1 Config loader (Python 3.5-compatible — cấm f-string, walrus)

Đặt ngay sau import `os/json`, trước `SOCIAL_MAX_CONCURRENT`:

```python
import json as _json_cfg
_CFG_PATH = os.environ.get("NAS_CONFIG", "/etc/nas/nas_api.conf")
try:
    with open(_CFG_PATH) as _f:
        _CFG = _json_cfg.load(_f)
except Exception:
    _CFG = {}
def _cfg(name, default, cast=None):
    v = os.environ.get("NAS_" + name, _CFG.get(name.lower(), default))
    try:
        return cast(v) if cast else v
    except Exception:
        return default
```

Áp dụng đợt 1 (liên quan trực tiếp livestream/social):

| Hằng hiện tại | Env / key conf | Default | Range + enforce |
|---|---|---|---|
| `SOCIAL_MAX_CONCURRENT = 2` | `NAS_SOCIAL_MAX_CONCURRENT` | 2 | 1–16, clamp + log khi ngoài range; ngưỡng khuyến nghị tính theo RAM (xem §3.4) |
| `LIVESTREAM_MAX_CONCURRENT = 2` | `NAS_LIVESTREAM_MAX_CONCURRENT` | 2 | 1–8, clamp; ngưỡng khuyến nghị tính theo RAM (xem §3.4) |
| `SOCIAL_MAX_FILESIZE = "2G"` | `NAS_SOCIAL_MAX_FILESIZE` | `"2G"` | cho `"0"` = unlimited + per-request override giữ nguyên |
| `SOCIAL_YTDLP_TIMEOUT = 300` | `NAS_YTDLP_TIMEOUT_S` | 300 | 60–1800 |
| `_LIVESTREAM_MAX_HOURS = 12` | `NAS_LIVESTREAM_MAX_HOURS` | 12 | 1–72, per-job `max_hours` override ở `POST /api/livestream/start` |
| `_LIVE_ERR_COOLDOWN 300 / _STALL 7200` | `NAS_LIVE_ERR_COOLDOWN_S / NAS_LIVE_STALL_COOLDOWN_S` | 300 / 7200 | > 0 |
| heavy-gate `RAM 78 / load 2.5 / cpu 75 / idle 90 / cache 5s` | `NAS_RAM_BLOCK_PCT / NAS_LOAD_BLOCK / NAS_CPU_BLOCK_PCT / NAS_USER_IDLE_S / NAS_HEAVY_GATE_CACHE_S` | như cũ | RAM 50–95, load 1.0–8.0 |
| social cleanup `sleep 300 / TTL 3600` | `NAS_SOCIAL_CLEANUP_INTERVAL_S / NAS_SOCIAL_JOB_TTL_S` | 300 / 3600 | > 0 |
| tiktok watch `60/180s`, HTML cap 768KB, timeout 25s/1 retry | `NAS_TIKTOK_WATCH_S / NAS_TIKTOK_HTML_CAP / NAS_TIKTOK_HTML_TIMEOUT` | như cũ | > 0 |

Đợt 2 (đã liệt kê đủ trong audit, làm sau khi G1 xong):
torrent 50MB, backup 64MB/10k files, transcode 4 sessions/2h, screen 3 sessions/64MB,
thumb 320px/q80/neg-cache 7d/5k, fan/PWM, disk-health, paths, ports/threads, firewall nets.
Không bắt buộc trong SPEC này nhưng loader phải thiết kế mở để thêm key mà không sửa API.

### 3.2 Endpoint `GET /api/admin/limits` + `PATCH /api/admin/limits`

- Phân quyền (theo chốt user: admin-only): `GET` reuse `@requires_auth` hiện tại
  (mọi client auth LAN đều xem được để render slot). `PATCH` dùng decorator mới
  `requires_admin` — bắt buộc `Authorization: Basic` với `username == WEBDAV_USER`
  (tài khoản `daica` load từ `auth.conf`, `:1558`), từ chối LAN-whitelist bypass
  và `authorized_ips` cache (không cho IP từng login tự nâng quyền). Sai auth → 401/403,
  không gợi ý password.
- `GET` trả toàn bộ key đợt 1 + `allowed + reasons` của `_background_heavy_work_allowed()`
  (tái sử dụng logic, không tính toán lại 2 lần nhờ cache 5s).
- `PATCH` nhận subset JSON, validate range/clamp, ghi xuống `_CFG_PATH` (atomic write
  tmp + rename), `globals().update()` cho các hằng đọc mỗi vòng (watchdog/cleanup/gate).
  Các hằng chỉ đọc lúc start (port/threads/MALLOC) trả `requires_restart: true` + log warning.
- social/livestream enforce đọc giá trị runtime mỗi request (không cache trong closure).

### 3.3 Lỗi full-slot có cấu trúc

`api_livestream_record` khi vượt cap (`:16448`) trả 429:

```json
{
  "error": "NAS đang ghi tối đa 2 luồng, thử lại sau.",
  "reason": "max_concurrent",
  "code": "MAX_STREAMS",
  "cap": 2,
  "active": 2,
  "retry_after_s": 60
}
```

- `cap/active` lấy từ đếm hiện tại (đã gồm stopping/timeout/finalizing + claims).
- `retry_after_s` = 60 (khớp nhịp watchdog) hoặc estimate từ job gần xong nhất nếu tính được.
- Tương tự cho social `:608`: `code=MAX_DOWNLOADS`, `cap/active`.
- Giữ `error` tiếng Việt để tương thích client cũ (client cũ vẫn hiện text thô).

### 3.4 Ngưỡng khuyến nghị theo RAM (theo chốt user: nới theo RAM NAS)

- Vì user chọn nới range (live 1–8, social 1–16), SPEC bắt buộc có guard theo RAM
  để không OOM trên RK3328:
  - `GET /api/admin/limits` trả thêm `ram_mb`, `recommended_live_max`,
    `recommended_social_max` tính lúc runtime: RAM ≤ 1500MB → khuyến nghị 2/2;
    RAM 1500–3000MB → 3/4; RAM > 3000MB → 4/8. Chỉ là khuyến nghị hiển thị,
    không tự chặn (chặn thật vẫn do heavy-gate + clamp tuyệt đối 8/16).
  - `PATCH` vượt khuyến nghị → chấp nhận nhưng trả `warning: "over_recommended"`
    + log warning, yêu cầu confirm lần 2 từ client (UI hiện dialog xác nhận).
- Heavy-gate giữ nguyên làm chốt cuối: RAM > 78% / load > 2.5 / đang recording
  thì daemon/watchdog vẫn từ chối việc nặng dù cap cao.

## 4. Thiết kế Android

### 4.1 Pre-flight + hàng đợi (LivestreamViewModel + Dialog)

- `LivestreamViewModel` thêm `serverLivestreamCap: Int = 2` + `serverLivestreamActive: Int`,
  refresh từ `GET /api/admin/limits` (hoặc `fetchLivestreamStatusOnly` mở rộng) mỗi lần mở dialog
  + sau mỗi start/stop.
- `startLivestreamRecord()`: nếu `active >= cap` → không POST, set `livestreamMessage`
  dạng structured (`code=MAX_STREAMS`) để dialog render CTA, vẫn cho phép [Cho vào hàng đợi].
- Hàng đợi server-side (theo chốt user): `POST /api/livestream/queue` nhận
  `{url, quality, watch_username?, max_hours?}` → persist JSON trên HDD
  (cùng thư mục `.naswebdav/`, không eMMC), mỗi entry `{queue_id, ts, url, quality, status=queued}`.
  `GET /api/livestream/queue` xem + `DELETE` hủy từng entry. Watcher/vòng check sau mỗi
  job kết thúc (finished/error/stopped/timeout) tự nhấc entry đầu còn hạn (TTL 24h,
  check `watch_username` còn live nếu có) → start như flow thường. Client chỉ hiển thị
  số thứ tự + nút hủy, không giữ queue local (kill app không mất).
  Không cho queue vượt quá 20 entry (chống spam) — vượt trả 429 `code=QUEUE_FULL`.
- Parse `code/reason/cap/active/retry_after_s` từ `extractApiError` — mở rộng parser trả object
  thay vì chỉ string (giữ overload cũ trả string để không vỡ call site khác).

### 4.2 Hiển thị lỗi persistent + mã lỗi thay prefix

- `LivestreamRecordDialog`: giữ lỗi persistent đến khi user dismiss/đổi link/thành công;
  chỉ auto-clear success sau 10s. Thay `startsWith("Lỗi")` bằng `isError: Boolean` / `code`
  từ ViewModel.
- Thêm CTA theo `code`: `MAX_STREAMS` → [Cho vào hàng đợi] [Theo dõi user] [Thử 720p/audio];
  `OFFLINE/BAD_URL` → [Sửa link]; transient → [Thử lại sau Xs].

### 4.3 Fix bug liên quan trực tiếp (không mở rộng phạm vi)

- VOD misclassify (`Dialog:361`): chỉ coi VOD khi chắc (`/video/`, `watch?v=`, youtu.be non-live);
  `tiktok.com/t/`, `vm.tiktok.com` phải resolve trước rồi mới phân loại. Ưu tiên cao — mất khả
  năng record short link.
- Discovery 60p + sync không refresh (`Discovery:81-83`, `VM:364-400`): hạ periodic xuống 15p
  (min WorkManager), trigger `sync + fetchLivestreamStatusOnly` khi app foreground; gộp thành
  `refreshLivestreamState()` vừa enqueue worker vừa update list.
- Worker ngưỡng chết cứng (`Monitor:238,307,318`: HTTP lỗi ≥5 / job mất ≥3 / exception ≥10 break):
  phân biệt transient (5xx/timeout/job tạm vắng) → `Result.retry()` + backoff; chỉ failure khi job
  terminal thật (`finished/error/stopped`).
- Poll (`Monitor:214-217`): dùng `AppConfig.LIVESTREAM_POLL_INTERVAL_MS` đã tồn tại, giãn khi
  background (`IS_APP_FOREGROUND` có sẵn), cân nhắc dồn nhiều job vào 1 `/status`.
- Thống nhất hằng số: 1 `LIVESTREAM_FOLDER` + 1 `SOCIAL_FOLDER` trong AppConfig (hiện
  `"Livestream/"` rải ở Monitor:334 + dialog:370 vs `SOCIAL_DOWNLOAD_FOLDER` ở NasApplication:478);
  gom endpoint paths vào 1 `ApiEndpoints` object; dùng chung 1 allowlist cho dialog + extractor
  (+ share parser) — fix Shopee giả "không hỗ trợ" (`Screen:953-955`).

Không làm đợt này: xóa pipe-mode dead code (~100 dòng), ExtractorConfig timeout/UA,
local timer resync, notif-id 100 slot, scheme https fallback, i18n strings.

## 5. Ràng buộc

- Python 3.5: cấm f-string/walrus/thư viện 3.6+. Dùng `"%s" %`, `.format()`.
- Không ghi eMMC: conf default `/etc/nas/nas_api.conf` (đã là eMMC nhưng file nhỏ, đọc 1 lần
  lúc start + ghi khi PATCH — chấp nhận; không đặt DB/log/thumb/tmp lên eMMC).
- `WAKE_LOCK tools:node="replace"` bất khả xâm phạm; UI giữ `NasTheme`; worker nền qua
  WorkManager + notification; state qua StateFlow/MutableState trong viewModelScope.
- Tương thích ngược: client cũ (chỉ đọc `error` string) vẫn chạy; server cũ (chưa có
  `/api/admin/limits`) thì client fallback cap=2 + flow cũ (không crash).
- Bảo mật: `GET /api/admin/limits` sau auth hiện tại; `PATCH` chỉ admin `daica`
  qua Basic auth (chặn LAN-bypass/IP-cache nâng quyền), validate range + atomic write;
  không log password/token (tuân thủ P3-18 đã fix).

## 6. Tiêu chí nghiệm thu

- [ ] Đổi `NAS_LIVESTREAM_MAX_CONCURRENT=3` + restart → `GET /api/admin/limits` trả cap=3,
  start 3 live song song OK, job thứ 4 trả 429 có `code/cap/active/retry_after_s`.
- [ ] `PATCH` cap 2→3 lúc runtime (không restart) → job thứ 3 chấp nhận ngay.
- [ ] Client mở dialog khi đầy slot: thấy "NAS đang bận (2/2)" + 3 nút CTA, không POST thừa.
- [ ] [Cho vào hàng đợi] → khi 1 job xong, queue tự đề xuất thử lại (confirm), không ghi lén.
- [ ] Link `tiktok.com/t/xxx` live → record được (không reroute nhầm sang social/VOD).
- [ ] Mở app sau khi watchdog tự ghi: list hiện job trong < 1 phút (không chờ 60p).
- [ ] Mất mạng 30–60s giữa monitor: worker retry/backoff, không mất notification/job mồ côi.
- [ ] `pytest` ≥ 115 passed (giữ), thêm regression cho loader clamp + 429 structured + PATCH persist.
- [ ] `compileDebugKotlin` + `assembleDebug` xanh; manual test APK + `journalctl` không traceback.

## 7. Kế hoạch triển khai (dự kiến, chờ duyệt SPEC)

1. Backend loader `_cfg()` + chuyển 12 key đợt 1 sang đọc động (giữ default).
2. Endpoint `GET/PATCH /api/admin/limits` + 429 structured (live + social).
3. Regression tests backend (loader clamp, 429 shape, PATCH atomic/validate).
4. Android: parser structured error + `serverLivestreamCap/Active` + pre-flight + queue + dialog CTA.
5. Android: fix VOD `/t/`, discovery 15p + `refreshLivestreamState()`, worker transient retry, thống nhất folder/endpoint/allowlist.
6. Deploy NAS (`push_server_to_nas.py` + restart) + verify `live_watch last_tick/loop_count` + cron/APK thực tế.
7. Ghi CHANGELOG theo format repo + append REVIEW-CROSS-CHECK (cấm xóa lịch sử).

## 8. Quyết định đã chốt (2026-10-08)

- (Q1) Range: nới theo RAM thực tế — livestream 1–8, social 1–16, kèm ngưỡng khuyến nghị
  theo RAM (§3.4) + confirm lần 2 khi vượt khuyến nghị. Cần user báo RAM NAS để hiệu chỉnh
  ngưỡng (hiện giả định RK3328 1–2GB).
- (Q2) Hàng đợi: server-side — persist JSON trên HDD, TTL 24h, cap 20 entry, sống qua restart,
  watcher tự nhấc khi có slot (§4.1).
- (Q3) PATCH: admin-only — bắt buộc Basic auth `daica`, chặn LAN-bypass/IP-cache (§3.2).
