# GEMINI.md — NASWebDAV Project Context

> File này được duy trì bởi **headroom learn** + **AI agent** sau mỗi phiên.
> Đây là context supplement cho Gemini CLI / Antigravity.
> Xem `.agents/hardrules.md` để biết quy tắc đầy đủ.

---

## Project Overview

- **App**: NASWebDAV — Android app (Kotlin/Jetpack Compose) quản lý NAS Chainedbox (RK3328, Armbian)
- **NAS IP LAN**: `192.168.100.254` | **Tailscale**: `100.90.135.102`
- **WebDAV Port**: `8822` (nginx) | **API Port**: `5050` (Python Flask)
- **WebDAV user**: `daica`
- **Python trên NAS**: Python **3.5** — không dùng f-string, walrus, type hints phức tạp

---

## Critical Gotchas (học từ các phiên trước)

### 1. iptables — DROP rules phải sau ACCEPT
```
SATH RẤT NGUY HIỂM:
rule 3: DROP all from 192.168.100.93
rule 5: ACCEPT all from 192.168.100.93  ← không bao giờ chạy!
```
→ Luôn `iptables -L INPUT -n --line-numbers` kiểm tra thứ tự trước khi thay đổi.

### 2. Kotlin type inference với nested class cùng tên
- `WebDavViewModel.LivestreamJob` ≠ `LivestreamJob` (top-level)
- `emptyList()` → `List<Nothing>` → mất type → `Unresolved reference`
- Fix: explicit type annotation + `.map { }` để convert

### 3. NasTheme vs MaterialTheme
- `MaterialTheme {}` trong `setContent` **không** truyền `AppTypography` → font Samsung One không hiển thị
- Phải dùng `NasTheme {}` (bọc trong `Theme.kt`)

### 4. Ping đo bằng TCP, không phải HTTP OPTIONS
- OPTIONS → nginx xử lý auth → ~300ms fake lag
- TCP socket connect → RTT thật (~1-5ms trên LAN)

### 5. PowerShell encoding
- `Set-Content -Encoding UTF8` ghi BOM → vỡ tiếng Việt trong .kt files
- Luôn dùng Antigravity `replace_file_content` tool

---

## Cấu trúc chính (Cập nhật sau Phase 7 - Strangler Fig)

Dự án áp dụng Strangler Fig Pattern, bẻ God Class `WebDavViewModel` thành 7 Domain VMs:
1. `AuthSessionViewModel` (Login, Kết nối)
2. `DeviceManagementViewModel` (SMB, Docker, Whitelist, Fan, Storage)
3. `SystemMonitorViewModel` (Metrics, Disk Health, OMV)
4. `FileBrowserViewModel` (Duyệt file, CRUD)
5. `AutoBackupViewModel` (Background backup)
6. `LivestreamViewModel` (Social extract, stream)
7. `SmartToolsViewModel` (Duplicate scan, Thumbnails)

```
MainActivity.kt       → Cấu hình DomainViewModelProvider & CompositionLocalProvider
WebDavManager.kt      → HTTP/WebDAV + TCP ping
WebDavViewModel.kt    → [SẮP XÓA] Đóng vai trò Facade delegates cho 7 VMs
ui/theme/Type.kt      → SamsungOneFontFamily + AppTypography
ui/theme/Theme.kt     → NasTheme (color + typography)
nas_api_server.py     → NAS backend (Python 3.5, Flask)
```

**Tiến độ UI Migration (Phase 7c):**
- Đã hoàn tất Nhóm 1 (Đơn nhiệm) và Nhóm 2 (Màn hình Quản trị).
- Đang chuẩn bị chuyển sang Nhóm 3 (Xương sống - BrowserScreen, MainMenu).

---

## AI Memory Stack

| Tool | Mục đích | Command |
|------|---------|---------|
| `neural-memory` | Lưu/nhớ ngữ cảnh qua `nmem` | `nmem remember "..."` |
| `headroom` | Nén context, học từ lỗi | `headroom learn --apply` |
| `claude-mem` | Session memory (cần Node.js) | `npx claude-mem install` |

**Để dùng `headroom learn`** (phân tích lỗi session → ghi vào GEMINI.md):
```powershell
$env:GEMINI_API_KEY = "YOUR_KEY"
headroom learn --agent gemini --apply --project .
```

**Để cài `claude-mem`** (cần Node.js >= 18):
```powershell
# Cài Node.js từ https://nodejs.org trước
npx claude-mem install --ide gemini-cli
```

---

## Thay đổi gần nhất

Xem [CHANGELOG.md](./CHANGELOG.md) để biết chi tiết từng thay đổi.

### 6. CẤM TUYỆT ĐỐI GHI VÀO BỘ NHỚ TRONG (eMMC)
- Tất cả các thao tác file (xoá, chuyển vào thùng rác `.trash/`, copy, move) PHẢI nằm trên cùng một ổ đĩa cứng ngoài (HDD như `Box Data`, `N300`, `USB Import`).
- **Cấm tuyệt đối** việc tạo `.trash/` ở thư mục gốc của WebDAV (ví dụ `/var/www/webdav/public/.trash/`) vì thư mục gốc nằm trên bộ nhớ trong eMMC/SD Card của NAS. Việc copy/move file dung lượng lớn (video) vào đó sẽ làm cháy/hỏng thẻ nhớ hoặc tràn bộ nhớ hệ thống.
- Bất cứ tính năng nào liên quan đến Trash Bin hay thao tác file đều phải xử lý trên cùng một phân vùng ổ cứng (vd: `/Data N300/.trash/`).

### 7. WAKE_LOCK & Manifest Merger (Xem chi tiết hardrules)
- CẤM TUYỆT ĐỐI xóa 	ools:node=" replace\ ở thẻ WAKE_LOCK trong AndroidManifest.xml. Thư viện ngoài lén lút giới hạn maxSdkVersion=25 gây lỗi mất quyền ngầm trên tiến trình nền.

### 8. CẤM KHAI BÁO HÀM TRONG KHỐI THỰC THI (INDENTATION TRAP TRÊN PYTHON)
- **CẤM TUYỆT ĐỐI** định nghĩa hàm helper (`def _something():`) trực tiếp bên trong khối `if __name__ == '__main__':` hoặc lồng trong hàm khác của `nas_api_server.py`.
- **Hậu quả:** Thụt đầu dòng (indentation) sẽ vô tình nuốt toàn bộ các thread khởi tạo server (`Waitress`/`Tornado`) vào trong hàm đó. Kết quả: Python chạy xong hàm tự thoát (exit 0) làm `nas_api.service` liên tục crash/restart loop làm App không đăng nhập được.
- **Quy tắc:** Mọi hàm helper BẮT BUỘC phải nằm ở cấp độ module (top-level scope, 0 space indentation). Khối `if __name__ == '__main__':` chỉ chứa các lời gọi hàm phẳng.

### 9. ĐỒNG BỘ ĐỒNG THỜI VÒNG TRÒN CPU/RAM VÀ BẢNG TIẾN TRÌNH
- Khi ứng dụng Android lấy dữ liệu tiến trình từ `/api/processes`, API trả về `total_cpu` và `total_ram` live tại cùng thời điểm. App Android (`SystemMonitorViewModel.kt`) BẮT BUỘC phải cập nhật `systemStatus` CPU/RAM đồng thời để Vòng Tròn CPU phía trên và Bảng Tiến Trình phía dưới khớp đúng 100% từng decimal điểm tại cùng 1 thời điểm.

### 10. QUẢN LÝ FILE: THÙNG RÁC .TRASH VÀ CƠ CHẾ DỰ PHÒNG XÓA VĨNH VIỄN
- Khi xóa file/thư mục, App thử di chuyển vào `.trash/` qua WebDAV `MOVE`.
- Nếu WebDAV `MOVE` thất bại (do `.trash` chưa được mount hoặc lỗi phân vùng), App BẮT BUỘC phải tự động fallback sang lệnh WebDAV `DELETE` (xóa thẳng vĩnh viễn), đồng thời xóa bản ghi khỏi SQLite Room DB (`repository.removeDuplicateFromDb(file.path)`).
- Server NAS (`nas_api_server.py`) khởi động tự động kiểm tra và khởi tạo thư mục `.trash/` quyền `777` trên tất cả phân vùng `/srv/dev-disk-by-*`.

