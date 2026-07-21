# NASWebDAV - PROJECT ARCHITECTURE MAP

> **BẮT BUỘC ĐỌC:** Tất cả AI Agent làm việc trên dự án này PHẢI đọc, hiểu và ghi nhớ bản đồ này TRƯỚC KHI thực hiện bất kỳ thay đổi nào. Sau khi thay đổi kiến trúc hoặc tính năng, BẮT BUỘC phải cập nhật lại file này.

## 1. Tổng quan hệ thống (System Overview)
NASWebDAV là một hệ sinh thái gồm 2 thành phần chính:
- **Server (NAS):** Chạy trên thiết bị phần cứng yếu (Chainedbox L1 Pro - CPU RK3328, 1-2GB RAM). Chỉ đóng vai trò lưu trữ thuần túy (Dumb Storage).
- **Client (Android):** Ứng dụng quản lý NAS (Kotlin + Jetpack Compose), đảm nhiệm toàn bộ tác vụ nặng (Tạo thumbnail, quét file trùng lặp, xử lý media).

---

## 2. Server Backend (`nas_api_server.py`)
- **Ngôn ngữ:** Python 3.5 (KHÔNG hỗ trợ f-string, walrus operator `:=`, hay type hints phức tạp).
- **Framework:** Flask.
- **Port hoạt động:** `5050` (API), `8822` (Nginx WebDAV).
- **Môi trường:** Armbian, giới hạn tài nguyên khắt khe.

### Các giới hạn & Quy tắc sinh tử (Hard Rules) trên NAS:
1. **Tuyệt đối KHÔNG chạy tác vụ nặng:** Các tính năng như tạo Thumbnail (`_thumbnail_generator`), nén/giải nén file zip lớn, quét trùng lặp phải được offload sang Android. NAS chỉ làm nhiệm vụ nhận/trả file (`POST /api/thumb/upload`).
2. **eMMC Protection (Bảo vệ bộ nhớ trong):** 
   - Tuyệt đối không lưu file tạm, file rác, hay thư mục `.trash/` vào phân vùng root của hệ điều hành.
   - Các thao tác file (xóa, chuyển) phải được thực hiện trên **cùng một ổ cứng ngoài** (vd: `Box Data`, `N300`) để tránh hiện tượng copy chéo làm tràn eMMC.
3. **Mạng & IP:** 
   - LAN IP: `192.168.100.254` (Ưu tiên dùng khi ở nhà).
   - Tailscale IP: `100.90.135.102` (Dùng khi ra ngoài).
   - Firewall: Khi cấu hình `iptables`, rule `DROP` bắt buộc phải đặt SAU rule `ACCEPT`.
4. **Security:** Không được khóa tài khoản `daica` bằng `pam_tally2`. Không dùng `subprocess.run` trực tiếp mà phải dùng hàm wrapper bảo mật `safe_run_cmd`.

---

## 3. Android Client (Kotlin / Jetpack Compose)
- **Kiến trúc:** MVVM + Strangler Fig Pattern (Đang trong quá trình bẻ God Class `WebDavViewModel` thành các Domain VMs nhỏ).
- **DI (Dependency Injection):** Sử dụng `DomainViewModelProvider` và `CompositionLocalProvider` tại `MainActivity.kt`.

### Cấu trúc Domain ViewModels (Phase 7):
1. **`AuthSessionViewModel`**: Đăng nhập, quản lý phiên WebDAV, TCP Ping.
2. **`DeviceManagementViewModel`**: SMB, Docker, Quạt (Fan), Ổ cứng (Storage), Whitelist.
3. **`SystemMonitorViewModel`**: Giám sát hiệu năng (RAM, CPU), nhiệt độ, Disk Health.
4. **`FileBrowserViewModel`**: Duyệt file, CRUD (Tạo, Đọc, Cập nhật, Xóa).
5. **`AutoBackupViewModel`**: Tự động sao lưu ngầm qua `WorkManager`.
6. **`LivestreamViewModel`**: TikTok live watcher, Social extractor.
7. **`SmartToolsViewModel`**: Xóa file trùng lặp, xử lý metadata/thumbnail.

### Quy tắc sinh tử (Hard Rules) trên Android:
1. **Thumbnail Generation:** App tự động sinh thumbnail cho ảnh/video bằng `MediaMetadataRetriever` hoặc `BitmapFactory` tại local, sau đó upload lên `/api/thumb/upload` thông qua `ThumbnailGenerator.kt`.
2. **WAKE_LOCK:** CẤM TUYỆT ĐỐI xóa `tools:node="replace"` ở thẻ `WAKE_LOCK` trong `AndroidManifest.xml` (tránh thư viện bên thứ 3 giới hạn quyền ngầm `maxSdkVersion=25`).
3. **Giao diện (UI):** Sử dụng `NasTheme {}` bọc ngoài cùng. Tuyệt đối không dùng trực tiếp `MaterialTheme {}` trong `setContent` vì sẽ làm mất font chữ hệ thống `SamsungOneFontFamily`.

---

## 4. API Endpoints Quan Trọng
- **Auth:** `POST /api/login`, `GET /api/status`
- **File & WebDAV:** `PROPFIND /webdav/...`, `MKCOL`, `MOVE`, `DELETE`
- **Thumbnail:** `GET /api/thumb` (Lấy ảnh), `POST /api/thumb/upload` (Android upload lên NAS)
- **System:** `GET /api/system/monitor`, `GET /api/system/storage`, `POST /api/lan/whitelist`
- **Trash:** `POST /api/trash/move`, `POST /api/trash/restore`, `DELETE /api/trash/empty`

---

## 5. Quy trình Deploy / Cập nhật
1. Chỉnh sửa code trên PC (Android Studio / Antigravity IDE).
2. Khi sửa Backend Python (`nas_api_server.py`), ưu tiên deploy qua LAN:
   ```bash
   scp nas_api_server.py root@192.168.100.254:/root/nas_api_server.py
   ssh root@192.168.100.254 "cp /root/nas_api_server.py /opt/nas_api_server.py && systemctl daemon-reload && systemctl restart nas_api.service"
   ```
3. Luôn kiểm tra lại Log `tail -10 /tmp/nas_api.log` sau khi deploy.
4. Cập nhật file `MAP.md` (file này) nếu có bất kỳ module, service, hay luồng dữ liệu nào thay đổi.
