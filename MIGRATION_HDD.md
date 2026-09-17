# Hướng dẫn thay ổ HDD NAS Chainedbox L1 Pro

**Mục tiêu**: Chuyển dữ liệu từ ổ cũ `Seagate ST4000VX` (4 TB, 6.5 năm, 8 pending sectors, SATA timeout 131k) sang ổ mới `Toshiba N300 HDWG440` (4 TB, NAS-grade CMR, 7200 RPM).

**Total downtime ước tính**: 24-36 giờ (chủ yếu là thời gian ddrescue clone). NAS sẽ offline trong toàn bộ thời gian này.

**Mức rủi ro mất dữ liệu**: Chỉ ở 8 sector hỏng (~32 KB), số đó đã hỏng từ trước, không thể recover. 99.999% dữ liệu còn lại sẽ clone OK.

---

## ⚠️ Phụ kiện cần chuẩn bị

| Thiết bị | Mục đích | Ghi chú |
|---|---|---|
| Toshiba N300 4TB HDWG440 | Ổ đích | Mua mới, mở seal kiểm tra warranty trước |
| **2× USB-SATA adapter 3.0** hoặc **dock 2-bay** | Đọc/ghi 2 ổ trên PC | UASP support càng tốt |
| **PC Windows/Linux** với 2 cổng USB 3.0 | Chạy ddrescue | Để chạy 24-36h, cắm sạc |
| USB stick ≥ 4GB | Boot Ubuntu Live | Tải Ubuntu 22.04 LTS Desktop ISO |
| Tua-vít Philips số 0 hoặc 00 | Tháo ổ khỏi NAS | Vít rất nhỏ |
| Bao chống tĩnh điện (ESD bag) | Cất ổ cũ | Tránh hỏng thêm |

**Cảnh báo về USB-SATA adapter rẻ**:
- Chip JMS561/ASM1153/RTL9210 → tốt
- Chip JMS567 đời cũ → có thể truncate ổ > 2TB
- Test thử với `lsblk` trước khi clone — phải thấy đúng 4 TB

---

## 📋 Quy trình theo từng giai đoạn

### Phase 0: Chuẩn bị (trước ngày thực hiện, ~30 phút)

```bash
# 1. Verify Toshiba N300 mới: cắm vào PC, check SMART
sudo smartctl -i /dev/sdX
sudo smartctl -A /dev/sdX   # baseline metrics
sudo smartctl -t short /dev/sdX  # short test 2 phút, đảm bảo ổ healthy
```

Mong đợi với ổ mới:
- `Power_On_Hours` ≈ 0-50
- `Start_Stop_Count` ≈ 1-10
- `Reallocated_Sector_Ct` = 0
- `Current_Pending_Sector` = 0
- SMART overall-health: PASSED

Nếu có bất kỳ red flag nào → trả ổ, lấy ổ khác.

### Phase 1: Backup config + cookies (trên NAS, ~5 phút) — TRƯỚC khi tắt NAS

Mở app → **Công cụ & Cài đặt** → **Sao Lưu Cấu Hình NAS** → **TẠO BACKUP MỚI**.

Sau khi tạo xong, bấm **OneDrive** trên backup mới nhất → Android share sheet → chọn OneDrive → đợi upload.

Verify trên OneDrive: thấy file `Backup_NAS DDMMYYYY HHMMSS.tar.gz` (~100-200 KB).

**Quan trọng**: file backup này chứa cookies.txt, watcher state, fan settings, nginx configs, nas_api server — sau khi clone ổ và boot xong, hầu hết các config sẽ tự khôi phục từ HDD cũ. File OneDrive là **cold backup** phòng trường hợp cả 2 ổ hỏng.

### Phase 2: Stop services + power down (trên NAS, ~2 phút)

SSH vào NAS:

```bash
# Stop TikTok watcher để không có recording bị cắt giữa chừng
ssh root@192.168.100.254
systemctl stop nas_api    # stops watcher + API server
sync                       # flush mọi pending write
shutdown -h now
```

Đợi ~30 giây cho NAS tắt hẳn. Đèn LED tắt hết.

### Phase 3: Tháo ổ + cắm vào PC (~10 phút)

1. Rút điện NAS Chainedbox
2. Mở vỏ NAS (4 vít sau lưng, hoặc tuỳ model)
3. Tháo ổ cũ `Seagate ST4000VX` ra khỏi tray
4. Mang về PC chạy Ubuntu

**Trên PC** (Ubuntu Live USB hoặc Ubuntu cài sẵn):
1. Cắm ổ cũ qua USB-SATA adapter #1 → sẽ là `/dev/sdX` (X = b/c/d tuỳ máy)
2. Cắm ổ mới Toshiba qua USB-SATA adapter #2 → `/dev/sdY`
3. Identify:
   ```bash
   lsblk -d -o NAME,SIZE,VENDOR,MODEL,SERIAL
   ```
   Phải thấy 2 dòng 4TB, một là `Seagate ST4000VX`, một là `Toshiba HDWG440`. Note kỹ device name để không clone NHẦM CHIỀU (ghi đè data sang ổ cũ).

### Phase 4: Clone bằng ddrescue (~24-36 giờ)

```bash
# Cài ddrescue nếu chưa có
sudo apt update && sudo apt install -y gddrescue

# Cd tới thư mục có ổ trống đủ chỗ (cần ~5MB cho mapfile)
cd ~

# PHASE 1 ddrescue: pass đầu, đọc nhanh, skip vùng lỗi
sudo ddrescue -d -f -n -r0 /dev/sdX /dev/sdY ddrescue.map

# -d: direct disk access (bypass kernel buffer, nhanh + tin cậy hơn)
# -f: force overwrite output device (cần thiết khi output là raw disk)
# -n: no scraping (không retry vùng lỗi ngay, đi tiếp)
# -r0: 0 retries pass đầu
# /dev/sdX = SOURCE (ổ cũ - kiểm tra kỹ!)
# /dev/sdY = TARGET (ổ Toshiba mới)
```

**Theo dõi tiến độ**: ddrescue in real-time:
- `ipos`: vị trí đọc hiện tại
- `non-trimmed/non-scraped/bad-sector`: vùng có vấn đề
- `time since last successful read`: nếu tăng cao = đĩa stall

Thời gian thực tế: ~15-25 giờ cho 4TB nếu source ổ vẫn đọc được phần lớn.

```bash
# PHASE 2 ddrescue: retry vùng có vấn đề
sudo ddrescue -d -f -r3 /dev/sdX /dev/sdY ddrescue.map

# -r3: 3 retries trên mỗi vùng error
# ddrescue.map có metadata từ pass trước → chỉ retry vùng cần
# Phase này thường nhanh hơn (vài phút - vài giờ tuỳ số bad sector)
```

Sau pass 2:
```bash
# Xem báo cáo cuối
ddrescuelog -t ddrescue.map
```

Output:
- `rescued: 3999.99 GB` (gần như toàn bộ)
- `errsize: 32 kB` (8 sector × 4KB = 32 KB — đúng như SMART báo)
- `errors: 8` (8 vùng không đọc được)

**Đây là kết quả KỲ VỌNG** — 8 bad sector từ SMART đã được skip, mọi thứ khác clone thành công.

### Phase 5: Verify ổ clone (~30 phút)

```bash
# 1. Kiểm tra partition table
sudo fdisk -l /dev/sdY
# Phải thấy 1 partition ext4 chiếm hết ổ, giống ổ cũ

# 2. fsck filesystem (sẽ tự sửa metadata corruption nhẹ nếu có)
sudo e2fsck -fy /dev/sdY1

# Output mong đợi:
#   Pass 1: Checking inodes, blocks, and sizes
#   Pass 2: Checking directory structure
#   ...
#   /dev/sdY1: clean, XXXXX/XXXXX files

# Nếu báo "Inodes that were part of a corrupted orphan linked list":
# → bình thường, fsck đã sửa. Tiếp tục.

# 3. Mount + đọc sample file
sudo mkdir -p /mnt/newdisk
sudo mount /dev/sdY1 /mnt/newdisk
ls -la /mnt/newdisk/                          # phải thấy "New folder", v.v.
ls /mnt/newdisk/'New folder'/ | head -20     # liệt kê WebDAV root

# 4. Sample read: chọn vài file lớn, đọc + checksum
md5sum /mnt/newdisk/'New folder'/Livestream/*.mp4 | tail -3
# Nếu không có "Input/output error" → clone OK

# 5. Verify watcher state
cat /mnt/newdisk/'New folder'/.nas_meta/tiktok_live_watch.json | python3 -m json.tool | head

sudo umount /mnt/newdisk
```

### Phase 6: Lắp ổ mới vào NAS + boot (~10 phút)

1. Tháo cả 2 ổ khỏi USB adapter
2. **Cất ổ cũ Seagate vào ESD bag** — không vứt, làm cold backup phòng khi cần recover thêm
3. Lắp ổ Toshiba vào tray của NAS Chainedbox
4. Đóng vỏ NAS, cắm điện, bật nguồn
5. Đợi ~60-90 giây cho boot + OMV detect ổ mới

### Phase 7: Verify hệ thống trên NAS (~10 phút)

```bash
ssh root@192.168.100.254

# 1. Mount
mount | grep sda
# Phải thấy: /dev/sda1 on /srv/dev-disk-by-label-data type ext4 (rw,...)

# 2. fsck status (nếu cần)
sudo tune2fs -l /dev/sda1 | grep -E 'Last check|state'
# Filesystem state: clean

# 3. Services
systemctl is-active nas_api nginx
# Cả 2: active

# 4. Disk Health Monitor verify
curl -s -u 'daica:<YOUR_PASSWORD>' http://127.0.0.1:5050/api/disk/health | python3 -m json.tool

# Output mong đợi:
#   "score": 100,
#   "smart_status": "PASSED",
#   "reallocated_sectors": 0,
#   "pending_sectors": 0,
#   "offline_uncorrectable": 0,
#   "command_timeout": 0,
#   "power_on_hours": 0-50,
#   "warnings": []

# 5. Watcher state preserved (đã clone từ HDD cũ)
curl -s -u 'daica:<your_password>' http://127.0.0.1:5050/api/tiktok/live_watch | head -c 200
# Phải thấy 12 user TikTok như trước

# 6. Cookies still valid
ls -la '/srv/dev-disk-by-label-data/New folder/cookies.txt'

# 7. Free space check
df -h /srv/dev-disk-by-label-data
# 3.6T total, ~377G used (giống trước), 3.3T free
```

### Phase 8: Resize partition (TÙY CHỌN, nếu ổ mới > 4TB hoặc cùng 4TB nhưng exact size khác)

Toshiba N300 4TB và Seagate ST4000VX 4TB thường có exact capacity giống nhau (~4001 GB). Nhưng nếu khác 1-2 GB:

```bash
# Mở rộng partition ext4 ra full disk
sudo umount /srv/dev-disk-by-label-data
sudo e2fsck -fy /dev/sda1
sudo parted /dev/sda resizepart 1 100%
sudo resize2fs /dev/sda1
sudo mount /dev/sda1 /srv/dev-disk-by-label-data
```

Skip nếu df -h đã hiển thị 3.6T total.

### Phase 9: Backup nâng cao (1-7 ngày sau migration)

Trong tuần đầu sau migration, theo dõi Disk Health Monitor:
- Score phải 100/100 (ổ mới, không bad block)
- Không có SATA reset trong dmesg
- TikTok recording chạy bình thường, không drop frame

Sau 1 tuần, kích hoạt **Scheduled Auto-Backup** trong app (Sprint 1 đã ship):
- Frequency: weekly
- Hour: 3 (3 giờ sáng)
- Retention: 7 (giữ 4 bản gần nhất ~1 tháng)
- rclone_remote: cấu hình OneDrive trên NAS bằng `rclone config`

---

## 🚨 Troubleshooting

### ddrescue stall (`time since last successful read` tăng cao > 60s)
→ Ổ source đang gặp bad sector cứng đầu. Cách xử lý:
1. Ctrl+C dừng ddrescue
2. Power-cycle USB adapter của source (rút cắm lại)
3. Tiếp tục: `sudo ddrescue -d -f -n -r0 /dev/sdX /dev/sdY ddrescue.map` (cùng mapfile)
4. Nếu cứ stall liên tục → tốc độ source quá kém, chuyển sang `--no-scrape --reverse` đọc ngược

### Sau khi swap ổ, NAS không boot
→ OMV cache UUID. Boot vào rescue mode:
```bash
# Tìm UUID mới của /dev/sda1
sudo blkid /dev/sda1
# UUID="<new-uuid>"

# Sửa /etc/fstab thay UUID cũ bằng UUID mới
sudo nano /etc/fstab
```
Hoặc: vì là clone trực tiếp, UUID **được giữ nguyên** — fstab không cần sửa. Vấn đề chỉ xảy ra nếu mkfs lại.

### Score Disk Health vẫn báo có warnings sau khi swap
→ Daemon đang đọc cached SMART. Restart:
```bash
systemctl restart nas_api
# Đợi 5-10 phút cho sample đầu tiên
curl -s -u 'daica:xxx' http://127.0.0.1:5050/api/disk/health | python3 -m json.tool
```

### Phát hiện file bị corrupt sau migration
→ Mount ổ cũ Seagate qua USB adapter, mount read-only, copy file cần thiết. Ổ cũ vẫn có 99.999% data, chỉ 8 sector mất.

---

## ⏱ Tổng thời gian dự kiến

| Phase | Thời gian | Có thể parallel? |
|---|---|---|
| 0 — Chuẩn bị | 30 phút | Trước ngày D |
| 1 — Backup config | 5 phút | Trên NAS đang chạy |
| 2 — Stop + shutdown | 2 phút | — |
| 3 — Tháo ổ + cắm PC | 10 phút | — |
| 4 — ddrescue clone | **24-36 giờ** | PC chạy, bạn ngủ/đi làm |
| 5 — Verify | 30 phút | — |
| 6 — Lắp ổ mới | 10 phút | — |
| 7 — NAS verify | 10 phút | — |
| 8 — Resize (nếu cần) | 5 phút | — |
| **Total** | **~26-38 giờ** | NAS offline trong giai đoạn 2→7 |

---

## 📞 Khi nào cần tôi support tiếp

- Phase 0: nếu smartctl của ổ Toshiba mới có warnings → tôi đánh giá có nên trả ổ
- Phase 4: nếu ddrescue stall liên tục — tôi tư vấn chiến lược fallback
- Phase 7: nếu Disk Health Monitor báo bất thường — tôi debug
- Bất kỳ phase nào: nếu có lỗi kernel/dmesg lạ — paste log, tôi phân tích

Trước khi bắt đầu Phase 2 (shutdown NAS), báo tôi 1 lần để tôi run pre-flight check qua SSH (đảm bảo không có recording đang chạy, sync flush hết writes).
