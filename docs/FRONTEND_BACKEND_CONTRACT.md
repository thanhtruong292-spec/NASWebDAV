# Ranh giới Frontend / Backend (source of truth)

Backend (NAS Flask) là source of truth cho mọi xử lý nặng và trạng thái chia sẻ.
Phone (Android) hiển thị + cache + enqueue, không làm lại việc NAS đã làm.

| Việc | Backend | Phone | Ghi chú |
|---|---|---|---|
| Thumbnail video | Daemon 24/7 + `/api/thumb` on-demand | Chỉ khi NAS fail, video ≤100MB, bỏ qua AV1/AVIF | `OnDemandThumbGenerator` |
| Hash trùng lặp | `/api/disk/fast_index` liệt kê | Phone hash partial + full-verify trước xóa | Không dùng `hash_batch` (route còn nhưng chết) |
| Quét trùng định kỳ | — | Một schedule `AutoCleanDuplicates` (30 ngày) | `scheduleIdleDuplicateScan` đảm bảo; manual qua `Unique_Scan_V3` |
| Backup dedup | Head check tồn tại | Fingerprint + size gợi ý skip, record theo dest path | Perceptual hash không phải bằng chứng |
| Transcode/stream | `/api/stream/transcode`, HLS | Chỉ play URL | `/api/stream/pipe` không tồn tại — không gọi |
| Schedule backup/USB | API schedule trên NAS | UI fetch/save, không tự tính lịch local | |
| Auth | `requires_auth` mọi route, Basic + whitelist | Fire-and-forget `/api/auth/authorize`, không parse body | Envelope v1 tương thích |

Endpoint đã xóa/sửa tên (không gọi): `/api/stream/pipe`, `/api/torrent/list`
(dùng field `torrents` của `/api/status`), `/api/guest_pass/create`
(`→ /api/guest/create`), `/api/daily-report` (`→ /api/report/daily`),
`/api/insights` (`→ /api/system/insights`).
