# QUY TẮC PHÁT TRIỂN NASWebDAV (TỐI ƯU HÓA TOKEN)

## 1. CẤU HÌNH MẠNG NAS
- **NAS LAN IP**: `192.168.100.254`[cite: 1]
- **NAS Tailscale IP**: `100.90.135.102`[cite: 1]
- **WebDAV port**: `8822`[cite: 1]
- **API port**: `5050`[cite: 1]
- **SSH**: `root@100.90.135.102` hoặc `root@192.168.100.254`[cite: 1]
- **WebDAV user**: `daica`[cite: 1]
- **iptables whitelist**: `192.168.100.93`, `192.168.100.209`, `100.64.0.0/10`[cite: 1]
- **Lưu ý iptables**: Chain INPUT policy là DROP[cite: 1]. Mọi IP không nằm trong whitelist đều bị block[cite: 1]. Lệnh lưu sau khi reset iptables là: `iptables-save > /etc/iptables/rules.v4`[cite: 1].

## 2. RÀNG BUỘC MÔI TRƯỜNG SERVER (nas_api_server.py)
- Server chạy trên môi trường Armbian Stretch (RK3328)[cite: 1].
- Ràng buộc cấu hình Python 3.5 không hỗ trợ các tính năng sau:
  + Không dùng f-string (bắt buộc phải dùng `.format()`)[cite: 1]
  + Không dùng `async/await` nâng cao[cite: 1]
  + Không dùng walrus operator `:=`[cite: 1]
  + Không dùng Type hints phức tạp[cite: 1]

## 3. RÀNG BUỘC CHỈNH SỬA CODE
- **Cấm dùng PowerShell Set-Content cho file Kotlin**: Lệnh `Set-Content -Encoding UTF8` ghi BOM vào file làm sai encoding dẫn đến tiếng Việt bị garbled[cite: 1]. Phải dùng tool `replace_file_content` hoặc `multi_replace_file_content` để thay thế[cite: 1].
- **Hạn chế Android Studio Live Edit**: Live Edit không xử lý đúng `by lazy`, `data class` mới, hoặc nested class[cite: 1]. Khi có các thay đổi này, phải build full APK bằng lệnh `./gradlew assembleDebug` và cài lại[cite: 1].

### 6. CẤM XÓA tools:node="replace" TRONG QUYỀN WAKE_LOCK
- Các thư viện bên thứ 3 (như WorkManager hoặc legacy libraries) có thể lén lút khai báo thuộc tính `android:maxSdkVersion="25"` cho các quyền nền tảng như `WAKE_LOCK`.
- Khi Manifest Merger hoạt động, nó sẽ gộp thuộc tính `maxSdkVersion` này vào app, khiến app bị HĐH tước mất quyền trên Android đời cao (API > 25), gây ra lỗi `SecurityException` chết người khi chạy tiến trình nền.
- **RÀNG BUỘC TUYỆT ĐỐI**: Khi khai báo `<uses-permission android:name="android.permission.WAKE_LOCK" />` trong `AndroidManifest.xml`, bắt buộc phải có `tools:node="replace"`.
- BẤT CỨ AI (bao gồm AI, con người) khi sửa đổi file `AndroidManifest.xml` đều **KHÔNG ĐƯỢC PHÉP** vô tình hay cố ý xóa bỏ thuộc tính `tools:node="replace"` ở dòng quyền `WAKE_LOCK`.

### 7. PROJECT MAP (BẢN ĐỒ DỰ ÁN)
- Toàn bộ AI bắt buộc phải đọc file `MAP.md` ở thư mục gốc trước khi bắt tay thực hiện bất kỳ thay đổi nào. Khi có thay đổi kiến trúc hoặc tính năng, phải cập nhật nội dung tương ứng vào `MAP.md`.

### 8. QUY TẮC CẤM KHAI BÁO HÀM TRONG KHỐI THỰC THI (INDENTATION TRAP TRÊN PYTHON)
- **CẤM TUYỆT ĐỐI** định nghĩa hàm helper (`def _something():`) trực tiếp bên trong khối `if __name__ == '__main__':` hoặc lồng trong hàm khác của `nas_api_server.py`.
- **Hậu quả:** Cú pháp thụt đầu dòng (indentation) của Python sẽ làm các dòng khởi tạo thread server (`Waitress`/`Tornado`) bị nuốt chửng vào thân hàm đó. Python chạy xong hàm tự kết thúc (exit 0), dịch vụ `nas_api.service` liên tục crash/restart loop làm App sập kết nối/không đăng nhập được.
- **Quy tắc:** Tất cả các hàm helper phải luôn khai báo ở cấp độ module (top-level, 0 space indentation). Khối `if __name__ == '__main__':` chỉ gọi hàm phẳng.
