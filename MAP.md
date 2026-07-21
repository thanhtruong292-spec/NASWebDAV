# NASWebDAV - BẢN ĐỒ KIẾN TRÚC DỰ ÁN (PROJECT MAP)

> **BẮT BUỘC ĐỌC:** Tài liệu này là ranh giới kiến trúc tuyệt đối. Tất cả AI Agent làm việc trên dự án này PHẢI đọc, hiểu và tuân thủ bản đồ này TRƯỚC KHI thực hiện bất kỳ thay đổi nào. Cấm "dẫm đạp" (overwrite) logic của các thành phần đã được định nghĩa ở đây.

---

## 1. SƠ ĐỒ LUỒNG DỮ LIỆU & KIẾN TRÚC (MERMAID)

```mermaid
graph TD
    subgraph Android App (Client - Xử lý tác vụ nặng)
        UI[Jetpack Compose UI] -->|CompositionLocal| VMP[DomainViewModelProvider]
        
        VMP --> Auth[AuthSessionViewModel]
        VMP --> Browser[FileBrowserViewModel]
        VMP --> System[SystemMonitorViewModel]
        VMP --> Device[DeviceManagementViewModel]
        VMP --> Smart[SmartToolsViewModel]
        VMP --> Live[LivestreamViewModel]
        VMP --> Backup[AutoBackupViewModel]
        VMP --> Global[GlobalUiViewModel]
        
        Auth & Browser & System & Device & Smart & Live & Backup --> WebDavManager
    end

    subgraph NAS Server (Chainedbox RK3328 - Dumb Storage)
        WebDavManager -->|Port 8822 - I/O| Nginx[Nginx WebDAV]
        WebDavManager -->|Port 5050 - HTTP| Flask[Flask API Backend]
        
        Nginx -->|Read/Write| Storage[(HDD Ngoài: Box Data, N300)]
        Flask -->|Metrics/Control| OS[Armbian OS]
        Flask -->|Metadata/Logs| SQLite[(nas_state.db)]
    end
    
    %% Quy tắc đỏ
    Flask -.->|CẤM CHẠY| Heavy[Tạo Thumbnail, Dò File Trùng]
    Smart -.->|Trích xuất Local| ThumbGen[ThumbnailGenerator.kt]
    ThumbGen -->|POST /api/thumb/upload| Flask
```

---

## 2. SERVER BACKEND (`nas_api_server.py`)
**Môi trường:** Chainedbox L1 Pro (CPU RK3328, 1-2GB RAM). Python 3.5.
**Trách nhiệm:** Chỉ cung cấp API điều khiển hệ thống, giám sát phần cứng và tiếp nhận file. KHÔNG xử lý các tác vụ ngốn CPU/RAM (Media processing).

### Danh mục API Endpoints (Flask - Port 5050):
*Được nhóm theo chức năng để tránh viết trùng lặp.*

#### 2.1. System & Hardware (Giám sát & Phần cứng)
- `GET /api/status`, `/api/status/realtime`: Thông số CPU, RAM, Disk, Uptime tổng hợp.
- `GET /api/system/workload`, `/api/system/idle`: Đo tải hệ thống để quyết định ngủ đông (Sleep).
- `GET /api/disk/smart`, `/api/disk/health`: S.M.A.R.T Disk health và lịch sử.
- `POST /api/fan/control`: Điều khiển quạt tản nhiệt (Custom curve).
- `POST /api/power/reboot`, `suspend`, `shutdown`: Điều khiển nguồn.

#### 2.2. Network & Security (Mạng & Bảo mật)
- `POST /api/auth/authorize`: Sinh token xác thực IP.
- `GET /api/lan/whitelist`, `POST`, `DELETE`: Quản lý IP được phép truy cập (Firewall).
- `GET /api/tailscale/status`: Trạng thái kết nối Tailscale IP (`100.90.135.102`).
- `POST /api/guest/create`, `revoke`: Quản lý tài khoản khách.

#### 2.3. Media & Smart Tools (Nhận dữ liệu từ Android)
- `POST /api/thumb/upload`: Nhận file thumbnail chuẩn JPEG 300x300 từ Android tải lên.
- `GET /api/thumb`: Lấy ảnh thumbnail (Trả 404 nếu không có, ép Android tự sinh rồi upload).
- `POST /api/tools/smart_organize/...`: Kích hoạt job di chuyển, sắp xếp file dựa trên metadata (Android tính toán, NAS thực thực thi dời file).

#### 2.4. Services (Docker, SMB, Torrent, etc)
- `GET /api/docker/containers`, `POST /api/docker/control`: Quản lý Container.
- `GET /api/smb/status`, `POST /api/smb/toggle`: Bật/Tắt Samba.
- `POST /api/torrent/control`, `add_file`: Quản lý tải Torrent (Transmission).

### Quy tắc sinh tử trên NAS (Anti-Patterns):
- **Cấm ghi eMMC:** Không được lưu file vào các thư mục `/root`, `/home`, hay phân vùng hệ điều hành. Chỉ làm việc trên `/media/Box Data/` hoặc ổ USB.
- **Python 3.5:** Tuyệt đối cấm dùng f-string (`f"..."`), walrus (`:=`), hoặc thư viện chỉ có ở Python 3.6+. Phải dùng `"...".format(...)`.
- **An toàn mạng:** `daica` là tài khoản hệ thống bất tử, đã được cấu hình PAM bypass `pam_tally2`. Không được can thiệp khóa tài khoản này.
- **Iptables:** Lệnh `DROP` luôn phải đẩy xuống cuối cùng (sau `ACCEPT`). Dùng lệnh `iptables-save > /etc/iptables/rules.v4` sau khi chỉnh sửa.

---

## 3. ANDROID CLIENT (App Kotlin)
**Kiến trúc:** Strangler Fig Pattern. Phân rã `WebDavViewModel` khổng lồ thành 8 Domain ViewModels độc lập, cung cấp qua `DomainViewModelProvider`.

### 3.1. Các Domain ViewModels (Ranh giới tính năng):
1. **`AuthSessionViewModel.kt`**: Chuyên trách quản lý kết nối TCP Ping, xác thực WebDAV, IP LAN (`192.168.100.254`) vs Tailscale (`100.90.135.102`). Cấm viết logic UI hay file vào đây.
2. **`DeviceManagementViewModel.kt`**: Chuyên tương tác API Service (SMB, Docker, Fan, Whitelist). 
3. **`SystemMonitorViewModel.kt`**: Polling liên tục `GET /api/status` và vẽ biểu đồ hiệu năng, Disk S.M.A.R.T.
4. **`FileBrowserViewModel.kt`**: Logic lõi của ứng dụng - Duyệt WebDAV (PROPFIND), tạo thư mục, đổi tên, tải xuống.
5. **`SmartToolsViewModel.kt`**: Nhận luồng dữ liệu từ NAS, tính toán MD5, phát hiện file trùng lặp, điều phối `ThumbnailGenerator.kt` trích xuất ảnh/video từ máy local rồi đẩy lên NAS.
6. **`LivestreamViewModel.kt`**: Theo dõi TikTok Live, gọi API tải stream.
7. **`AutoBackupViewModel.kt`**: Giao tiếp với `WorkManager` để đẩy ảnh/video ngầm lên NAS. Đảm bảo chạy nền hoàn hảo.
8. **`GlobalUiViewModel.kt`**: Quản lý State của Toast, Snackbar, Dialog cảnh báo chung toàn cục.

### Quy tắc sinh tử trên Android:
- **WAKE_LOCK:** Thẻ `<uses-permission android:name="android.permission.WAKE_LOCK" tools:node="replace"/>` trong `AndroidManifest.xml` là BẤT KHẢ XÂM PHẠM. Cấm xóa `tools:node="replace"` để tránh lỗi mất quyền chạy nền trên Android đời cao.
- **Giao diện (UI):** Theme bắt buộc dùng `NasTheme {}`. Gọi `MaterialTheme {}` trực tiếp sẽ ghi đè và làm mất font hệ thống `SamsungOneFontFamily`.
- **Background Workers:** Việc upload/download nền phải dùng `WorkManager` (Foreground Service) kết hợp Notification. Tại đây, `AutoBackupWorker` phải gọi `ThumbnailGenerator` để tự sinh thumbnail trước khi upload.
- **State Flow:** Tất cả State phải được expose ra UI qua `StateFlow` hoặc `MutableState`. Hạn chế gọi trực tiếp `suspend` function từ UI mà không bọc trong `viewModelScope`.
