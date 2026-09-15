# Hardware budget — Chainedbox L1 Pro (RK3328)

## Thông số (chuẩn RK3328 + số đo thực tế phiên 2026-09-15)

| Thành phần | Giá trị | Nguồn |
|---|---|---|
| SoC | Rockchip RK3328, 4x Cortex-A53 ~1.5GHz, 28nm | spec chuẩn |
| VPU decode | H.264/H.265/VP9 (4K), **không AV1** | spec chuẩn + log `no decoder` |
| RAM | ~1GB (process backend RSS ~172MB) | `ps` 2026-09-15 |
| Threads backend | ~28 lúc chạy ổn định | `systemctl status` |
| eMMC rootfs | 7GB, dùng 4.3GB (64%) | `df` |
| `/var/log` | zram 49MB | `df` |
| `/tmp` | tmpfs 490MB | `df` |
| HDD data | 3.6TB tại `/srv/dev-disk-by-label-data` | `df` |
| ffmpeg | 3.2.18 (Debian 9, 2017), không libaom/dav1d | `ffmpeg -version` |
| Python | 3.5 (systemd log) | journal |

## Ngân sách tài nguyên (đã áp trong code)

| Tài nguyên | Cap | Vị trí |
|---|---|---|
| ffmpeg đồng thời | 1 (`_ffmpeg_semaphore`) | backend:10970 |
| Livestream recording | 2 job (mỗi job 1 ffmpeg + 1 yt-dlp) | `LIVESTREAM_MAX_CONCURRENT` |
| Social download | 2 job | `SOCIAL_MAX_CONCURRENT` |
| Transcode HLS session | 4 | `_TRANSCODE_SESSION_MAX` |
| Hash batch workers | 2-4 theo RAM (>75% → 2) | `api_hash_batch` |
| Log file | 1MB x2 + logrotate zram | `RotatingFileHandler`, `logrotate-nas-zram` |
| Thumb neg-cache | 5000 entry, TTL 7 ngày | `_THUMB_NEG_CACHE_*` |
| Thumb rescan | 1800s | `_RESCAN_INTERVAL_S` |
| Tmp job/wrapper | HDD (`.nas_meta/nas_meta_tmp`), không `/tmp` | `_make_hdd_tmp_dir` |
| glibc arena | `MALLOC_ARENA_MAX=2` (đã có) | backend:17 |

## Quy tắc thêm mới

1. Mỗi ffmpeg/ffprobe/yt-dlp thường trú mới phải có cap đếm + trả 429 khi đầy.
2. Không spawn process không timeout trong request handler.
3. File tạm >1MB luôn lên HDD, không tmpfs.
4. Log thêm phải qua rotation 1MB — zram 49MB không chịu nổi spam.
5. Endpoint nặng thêm phải có `requires_auth` + kiểm tra gate
   `_background_heavy_work_allowed()` (livestream/USB/RAM/load).
