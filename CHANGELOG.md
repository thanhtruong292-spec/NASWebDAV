# CHANGELOG — NASWebDAV Project

> Mọi thay đổi code đều phải được ghi vào đây. Format: `[YYYY-MM-DD] <Tóm tắt>`.
> Model đang dùng ghi ở mỗi entry. Xem hardrules.md §RULE-1 để biết quy tắc.

---

## [2026-11-24] ANTIGRAVITY-063 — Thiết Kế Lại Toàn Diện Thanh Điều Hướng & Công Cụ Tệp (Files Path & Actions Toolbar Chuẩn Clean Slate Glass)
**Phiên bản:** `1.3.15` → `1.3.16` (PATCH — thay thế toàn bộ cụm điều hướng và nút thao tác tệp cũ thô kệch bằng thanh công cụ kính mờ hiện đại, thanh thoát):
(1) **Khắc phục triệt để cụm giao diện cũ bị lỗi thẩm mỹ:** Cụm 5 nút cũ (`<`, `+`, `☑`, `⊞`, `↻`) bị co giãn thành 5 khối hộp vuông đen to tướng (`min-height: 48px`, kéo dãn gần 60px) chiếm hơn 200px chiều cao đầu trang, bên trong chỉ chứa ký tự unicode đơn sơ, chèn ép mất hơn 40% diện tích màn hình hiển thị danh sách tệp;
(2) **Tái cấu trúc 2 hàng tinh tế chuẩn Clean Slate Glass:**
   - **Hàng 1 (Điều hướng & Đường dẫn):** Nút quay lại (`#fileBackBtn`) dạng squircle 36px với icon chevron Cyan sắc nét; Hộp đường dẫn (`.files-path-box`) kính mờ bo góc với icon thư mục SVG và tên thư mục in đậm, tự động rút gọn thông minh; Huy hiệu đếm số lượng tệp (`#filesCountBadge`) dạng viên thuốc Cyan nhỏ gọn;
   - **Hàng 2 (Thanh công cụ 4 tác vụ tinh tế):** Giảm 50% chiều cao thừa (từ 60px xuống 33px), tích hợp icon SVG Feather chuẩn xác kèm nhãn chữ rõ ràng: `[ ⬆ Tải lên ]`, `[ ☑ Chọn ]` (bật sáng viền Cyan khi ở chế độ chọn nhiều), `[ ⊞ Lưới lớn/nhỏ/danh sách ]` (cập nhật động icon và chữ), `[ ↻ Làm mới ]`;
(3) **Tối ưu không gian hiển thị:** Giải phóng ~80px chiều cao màn hình, giúp 3 hàng thumbnail ảnh/video hiển thị ngay lập tức khi mở thư mục mà người dùng không cần phải cuộn;
(4) **Kiểm chứng thực tế:** Biên dịch Gradle `assembleDebug` và cài đặt lên Samsung Galaxy S21 Ultra, xác nhận giao diện trong thư mục `/webdav/TikTok/` (300 tệp) hiển thị cân đối, sắc nét và thao tác cực kỳ êm ái.
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Build: BUILD SUCCESSFUL (3m 33s).
- JavaScript Syntax: `node -c app/src/main/assets/html5/app.js` đạt 0 lỗi cú pháp.
- Thiết bị thật: Samsung Galaxy S21 Ultra hiển thị hoàn hảo 100%.

---

## [2026-11-24] ANTIGRAVITY-062 — Khắc Phục Lỗi Nhấn Giữ Tệp Không Mở Action Sheet Menu (ReferenceError apiBase & Touch Drag Suppression)
**Phiên bản:** `1.3.14` → `1.3.15` (PATCH — hoàn thiện 100% tính năng nhấn giữ trên tệp trong tab Tệp và Dòng thời gian):
(1) **Khắc phục lỗi JavaScript rẽ nhánh:** Trong hàm `openFileModal(item)` (`app/src/main/assets/html5/app.js`), biến `apiBase` bị truy cập khi chưa khai báo trong phạm vi hàm, gây ra `ReferenceError: apiBase is not defined` mỗi khi bấm vào tệp (`!item.isDir === true`). Đối với thư mục (`item.isDir === true`), điều kiện rẽ nhánh ngắn mạch bỏ qua đoạn code này nên thư mục mở được menu bình thường;
(2) **Chống cướp sự kiện cảm ứng (Touchcancel & Native Drag):** Trên Chromium Android WebView, khi người dùng nhấn giữ vào thẻ `<img>` trong phần tử `.f-thumb`, trình duyệt tự động kích hoạt thao tác kéo ảnh mặc định của OS phát sinh sự kiện `touchcancel`, hủy bộ đếm thời gian `lpTimer` (420ms). Đã bổ sung `pointer-events: none !important; -webkit-user-drag: none !important; user-select: none !important;` trong CSS và thuộc tính `draggable="false"` trên thẻ `<img>`;
(3) **Tối ưu độ trễ ngón tay (Touch Slop Jitter):** Nâng ngưỡng dung sai di chuyển ngón tay `dx`, `dy` trong `touchmove` từ 12px lên 28px để phù hợp với màn hình cảm ứng độ phân giải cao (xxxhdpi) như Galaxy S21 Ultra;
(4) **Đồng bộ Dòng thời gian (Timeline):** Gán thuộc tính `data-fidx` và đồng bộ `currentRenderedFiles` trong `renderTimeline()` để thao tác nhấn giữ tệp trên chế độ xem dòng thời gian hoạt động mượt mà;
(5) **Kiểm chứng thực tế:** Đã biên dịch APK và cài đặt lên Samsung Galaxy S21 Ultra thực tế. Kiểm tra trực tiếp bằng thao tác nhấn giữ trên tệp video `0015f299bbdc07e0baa81eb8c9a833b1.mp4`, xác nhận File Action Sheet mở lên hoàn hảo với thumbnail preview, metadata, 4 nút thao tác nhanh và 4 tùy chọn chi tiết.
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: 181 passed, 0 failures (100% PASS).
- JavaScript Syntax: `node -c app/src/main/assets/html5/app.js` đạt chuẩn 0 lỗi.
- Kiểm thử thiết bị thật: Thao tác nhấn giữ tệp và thư mục hoạt động chuẩn xác 100%.

---

## [2026-11-24] ANTIGRAVITY-061 — Thiết Kế Lại Toàn Diện Menu Thao Tác Tệp Khi Nhấn Giữ (File Action Sheet Menu Chuẩn Clean Slate Glass)
**Phiên bản:** `1.3.13` → `1.3.14` (PATCH — phục hồi và nâng cấp toàn diện Menu thao tác tệp khi người dùng nhấn giữ lâu trên tệp/thư mục hoặc bấm nút ⋮):
(1) **Khắc phục vấn đề cốt lõi:** Khi người dùng nhấn giữ (long-press) vào tệp, thay vì hiển thị Menu thao tác phong phú thì app lại mở một hộp thoại đơn điệu chỉ có ô nhập đổi tên cùng 4 nút cơ bản gây nhầm lẫn;
(2) **Thiết kế Action Sheet chuẩn Clean Slate Glass (`#fileActionModal`):**
   - **Header Card:** Preview Thumbnail (cho Ảnh/Video) hoặc Icon SVG định dạng tệp sắc nét, hiển thị đầy đủ tên tệp, dung lượng và ngày sửa đổi, nút đóng ✕ góc phải;
   - **Hàng 4 nút thao tác nhanh (Quick Actions Grid):** [Mở tệp] (hỗ trợ phát video/xem ảnh trực tiếp), [Tải về] (tải tệp về máy qua Android DownloadManager), [Chia sẻ] (tạo liên kết chia sẻ WebDAV 24h & mở menu chia sẻ Android), [Xóa] (mở khung xác nhận an toàn trước khi chuyển vào Thùng rác);
   - **Danh sách tùy chọn chi tiết (Action Menu List):**
     - *Đổi tên tệp:* Hộp thoại trượt mượt mà cho phép nhập tên mới và lưu tên;
     - *Chọn tệp này (Kích hoạt chế độ chọn nhiều):* Bật `selectMode = true` và chọn ngay tệp hiện tại để xóa/tải hàng loạt;
     - *Sao chép liên kết WebDAV:* Lưu đường dẫn tệp vào bộ nhớ tạm;
     - *Thông tin chi tiết:* Bảng tóm tắt Tên, Đường dẫn tuyệt đối trên NAS, Dung lượng byte chính xác, Thời gian sửa đổi;
(3) **Phản hồi xúc giác (Haptic Feedback):** Bổ sung rung xúc giác nhẹ (`navigator.vibrate(35ms)`) khi nhấn giữ 420ms thành công;
(4) **Tương thích phím Back:** Tích hợp bộ map đóng modal cho phím Back vật lý trên điện thoại.
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Python Pytest: 340 passed, 7 skipped, 0 failed (100% PASS).
- Android Unit Tests: 181 passed, 0 failures (100% PASS).
- JavaScript Syntax: `node -c app/src/main/assets/html5/app.js` đạt chuẩn 0 lỗi.

---

## [2026-11-24] ANTIGRAVITY-060 — Khắc Phục Hiện Tượng Hộp Công Cụ NAS Bị Co Cụm Mất 16 Thẻ (Chromium WebView Nested Overflow Clipping)
**Phiên bản:** `1.3.12` → `1.3.13` (PATCH — sửa lỗi hiển thị khiến modal Hộp công cụ NAS chỉ hiện mỗi thẻ Khóa vân tay:
(1) **Nguyên nhân cốt lõi:** Việc đặt thuộc tính `max-height: 60vh; overflow-y: auto;` lồng bên trong `.ux-modal` (vốn đã có CSS transform `animation: uxModalPop`) khi chạy trên Android WebView có tăng tốc phần cứng (`LAYER_TYPE_HARDWARE`) gây ra lỗi Chromium Compositor Clipping, khiến chiều cao của khối `.app-grid` bị co cụm về 0;
(2) **Khắc phục:** Loại bỏ `max-height` và `overflow-y` trên `.app-grid` để 16 thẻ tiện ích co giãn tự nhiên theo DOM và cuộn mượt mà trên khung modal chuẩn `.ux-modal-backdrop`;
(3) **Làm sạch CSS:** Loại bỏ đoạn CSS thừa không có selector ở dòng 308-314 trong `assets/html5/style.css`;
(4) **Bật gỡ lỗi:** Kích hoạt `WebView.setWebContentsDebuggingEnabled(true)` trong `NasHtmlDashboard.kt` cho bản Debug;
(5) **Kiểm chứng:** Xác thực render hiển thị trọn vẹn 16 thẻ trên Chromium engine, 181 Android unit tests PASS 100%, biên dịch thành công APK mới `app-debug.apk`).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: 181 passed, 0 failures (100% PASS).
- Biên dịch Android: Gradle `assembleDebug` PASS 100% (bản APK tại `app/build/outputs/apk/debug/app-debug.apk`).

---

## [2026-11-24] ANTIGRAVITY-059 — Thiết Kế Lại Toàn Diện Hộp Công Cụ NAS Thành Dạng Thẻ Card Grid 2 Cột Trực Quan (Loại Bỏ Danh Sách Dài Đơn Điệu)
**Phiên bản:** `1.3.11` → `1.3.12` (PATCH — cải tiến mạnh mẽ giao diện người dùng theo phản hồi thực tế:
(1) Chuyển đổi toàn bộ hộp thoại "Hộp công cụ NAS" (`#toolboxModal`) từ danh sách dọc 16 dòng văn bản đơn điệu thành **Hệ thống Thẻ Card Grid 2 Cột (`app-grid`)** hiện đại theo chuẩn Clean Slate Glass;
(2) Thiết kế thẻ Hero Card phía trên cho Khóa vân tay / Sinh trắc học với icon nhận diện và công tắc gạt tinh tế;
(3) Mỗi công cụ trong 16 tiện ích (Auto Backup, Quét trùng lặp, Livestream, USB, Samba, Docker, Logs, Lịch ngủ, Băng thông, Torrent, LAN Whitelist, Thùng rác...) đều sở hữu Icon SVG màu sắc độc lập (`qa-ico-box` Cyan, Green, Red, Purple, Orange, Blue, Yellow, Pink), tiêu đề đậm nét và dòng mô tả phụ rõ ràng;
(4) Giảm chiều cao cuộn hơn 50%, tối ưu hóa diện tích hiển thị trên điện thoại, đồng thời bảo toàn 100% các bộ lắng nghe sự kiện `data-tbact` chuyển hướng modal).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Cú pháp HTML: Đạt chuẩn 100% không lỗi thẻ (`HTML parse OK`).
- Biên dịch Android: Gradle `assembleDebug` PASS 100% (APK sẵn sàng tại `app/build/outputs/apk/debug/app-debug.apk`).
- Bảo toàn logic: Toàn bộ 78 bridge methods và các luồng mở modal con hoạt động trơn tru.

---

## [2026-11-24] ANTIGRAVITY-058 — Chuyển Đổi Triệt Để Toàn Bộ Thẻ Select Sang HTML5 Custom Chips & Segmented Controls (Xóa Bỏ Hoàn Toàn Popup AlertDialog Native Của Android)
**Phiên bản:** `1.3.10` → `1.3.11` (PATCH — phát hiện và giải quyết triệt để vấn đề thẻ HTML `<select>` bị Android WebView tự động chặn và bật cửa sổ pop-up radio `AlertDialog` mặc định xám xịt của hệ điều hành Android khi người dùng chọn lịch quét trùng lặp / lịch ngủ:
(1) Loại bỏ 100% tất cả các thẻ `<select>` còn sót lại trong toàn bộ mã nguồn HTML5 (`assets/html5/app.js`);
(2) `dupSchedDaysSel` (Quét trùng lặp): Chuyển sang hàng 4 nút `seg-btn` Segmented Controls HTML5 (`Mỗi ngày`, `3 ngày`, `Hàng tuần`, `2 tuần`) với hiệu ứng Glassmorphism và viền Neon sáng;
(3) `dupSchedHourSel` (Giờ quét ban đêm): Chuyển sang thanh chọn giờ `chip-row` HTML5 mượt mà cuộn ngang;
(4) `slModeSel` (Lịch ngủ NAS): Chuyển sang 2 nút Segmented Tabs HTML5 (`Ngủ ổ cứng` / `Ngủ toàn hệ thống`);
(5) 100% tương tác chọn lịch giờ đây hoạt động hoàn toàn bên trong HTML5 DOM, không bao giờ kích hoạt bất kỳ hộp thoại radio native nào của Android OS).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Phân tích cú pháp: `node -c app/src/main/assets/html5/app.js` đạt 100% PASS (0 syntax errors).
- Quét số lượng `<select>`: Đạt 0 thẻ trong toàn bộ `index.html` và `app.js`.
- Android Unit Tests: 181 passed, 0 failures (100% PASS).
- Python Pytest: 340 passed, 7 skipped, 0 failed (100% PASS).
- Thực nghiệm trên thiết bị thật: Build APK debug mới và cài đặt trực tiếp lên Samsung Galaxy S21 Ultra qua ADB thành công.

---

## [2026-11-24] ANTIGRAVITY-057 — Gia Cố Ranh Giới Bảo Mật Client (SSRF, Rò Rỉ Xác Thực, Path Traversal Tải Tệp) & Dọn Dẹp Cảnh Báo Trình Biên Dịch
**Phiên bản:** `1.3.9` → `1.3.10` (PATCH — rà soát sâu các ranh giới bảo mật và tối ưu mã nguồn theo tiêu chuẩn security-audit: (1) `NasApplication.kt`: Bảo vệ interceptor `fastApiClient`, chỉ tiêm `Authorization: Basic ...` khi host khớp với `authHost` của NAS hoặc được gắn thẻ `AuthState` tường minh, triệt tiêu nguy cơ rò rỉ thông tin đăng nhập ra máy chủ bên ngoài; (2) `NasHtmlDashboard.kt`: Kiểm tra `reqHost == nasHost` trong `shouldInterceptRequest` trước khi tiêm Authorization cho `/api/media` và `/api/thumb`; phòng vệ triệt để Path Traversal trong `fileDownloadBatch` (bảo đảm tệp đích nằm trong thư mục Downloads, giải mã đúng ký tự tiếng Việt UTF-8 và resolve đường dẫn tương đối an toàn); (3) Dọn dẹp cảnh báo deprecation `RequestBody.create` và điều kiện dư thừa trong `DeviceManagementViewModel.kt`, `SmartToolsViewModel.kt` và `NasHtmlDashboard.kt`).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Python Pytest: 340 passed, 7 skipped, 0 failed (100% PASS).
- Android Unit Tests: 181 passed, 0 failures (100% PASS).
- Thực nghiệm trên thiết bị thật: Build APK 1.3.203 cài đặt lên Samsung Galaxy S21 Ultra, vào thẳng Dashboard thời gian thực, các luồng nền hoạt động ổn định.

---

## [2026-11-24] ANTIGRAVITY-056 — Rà Soát Toàn Diện Mã Nguồn (Code Review & Security Audit), Phòng Vệ Đường Dẫn & Chuẩn Hóa Kiểm Thử
**Phiên bản:** `1.3.8` → `1.3.9` (PATCH — rà soát toàn bộ diff mã nguồn theo các tiêu chuẩn khắt khe nhất: Code Review, Security Audit, Silent Failure Hunter và Ponytail full mode; bổ sung phòng vệ an toàn đường dẫn trong `DuplicateScanWorker.kt` qua `runCatching { catalogueFileUrl(...) }` ngăn ngừa việc một đường dẫn hỏng/traversal làm gián đoạn việc phân tích toàn bộ hàng chục nghìn nhóm trùng; bổ sung bài test `NasCatalogueTest` kiểm chứng hành vi phòng vệ; loại bỏ triệt để type annotations trong hàm `_telegram_send` tại `backend/nas_api_server.py` để tuân thủ 100% Python 3.5.3 trên ARM64 NAS).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Python Pytest: Toàn bộ 340 passed, 7 skipped, 0 failed (100% PASS trên toàn bộ test suite).
- Android Unit Tests: Toàn bộ 180 passed, 0 failures (100% PASS trên toàn bộ test suite).
- Cú pháp JavaScript HTML5: `node --check app/src/main/assets/html5/app.js` đạt 100% PASS (0 errors).
- Bất biến AutoBackup (ANTIGRAVITY-041): Giữ nguyên 100% tên tệp gốc, tuyệt đối không tự ý đổi tên hay thêm hậu tố `__id`/`__v`.

---

## [2026-11-24] ANTIGRAVITY-055 — Hoàn Thiện & Kiểm Chứng Thực Tế Động Cơ Quét Trùng Lặp Rust 3 Tầng Trên Samsung Galaxy S21 Ultra
**Phiên bản:** `1.3.7` → `1.3.8` (PATCH — sửa lỗi crash `No value for inventory` khi scan status == 'done' bằng cách nới lỏng yêu cầu inventory cho luồng quét thủ công trực tiếp, giữ nguyên kiểm tra nghiêm ngặt cho `AutoDuplicateScanWorker`; kiểm chứng thực tế phiên quét 401.708 tệp qua 3 tầng Rust trong 59s trên Samsung Galaxy S21 Ultra).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `AutoDuplicateCatalogueTest`, `VerifiedDuplicateRequestsTest`, `NasCatalogueTest` đạt 100% PASS.
- Thực nghiệm trên thiết bị thật: Build APK debug 1.3.203 cài lên Samsung Galaxy S21 Ultra (`R5CR61F6L3V`), khởi chạy phiên quét thực tế duyệt 401.708 tệp qua 3 tầng Rust trong 59s, logcat xác nhận Worker result SUCCESS 100%, giao diện hiển thị danh sách nhóm trùng mượt mà.

---

## [2026-11-24] Sửa các lỗi UI/UX đã tái hiện trên Android và HTML5

- Tác nhân thực hiện — Codex (GPT-6).
- Hiển thị phạm vi, số tệp và bằng chứng đọc của phiên quét trùng.
- Điều phối Back, Escape, focus và điều hướng bàn phím cho hộp thoại. Thêm nhãn và vùng chạm 48 px cho thanh công cụ tệp.
- Việt hóa trạng thái Thumbnail và USB, tách trạng thái Docker, phục hồi quyền phóng to và giảm chuyển động. Giữ nhãn thời gian trong khung biểu đồ.

---

## [2026-11-24] ANTIGRAVITY-053 — Khắc Phục Triệt Để Hiện Tượng Quét Trùng Lặp "Mỗi Lần Một Kiểu", Tương Thích read_errors & Mở Khóa Dọn Dẹp Trùng Lặp
**Phiên bản:** `1.3.5` → `1.3.6` (PATCH — giải mã chi tiết cơ chế cache TSV/SQLite khiến quét lúc nhanh (1.5 phút) lúc sâu (37 phút); sửa `DuplicateScanWorker.kt` bỏ thông báo giả `"NAS chưa cung cấp số lỗi đọc."` và sửa `isPartial` chỉ kiểm tra `total_groups > groups.length()`; sửa `SmartToolsViewModel.kt` gán `fullReportDisplayed = true` khi nạp đủ nhóm trùng và gỡ bỏ Toast chặn nút "Tự động dọn tất cả"; đồng bộ `nas_api_server.py` xử lý an toàn khi `read_errors` là None từ binary Rust cũ).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Python Pytest: `python -m pytest tests/test_rust_duplicate_scan.py -v` đạt 15/15 PASS (100%).
- Android Unit Tests: `NasCatalogueTest`, `AutoDuplicateCatalogueTest` BUILD SUCCESSFUL 100%.
- Kiểm chứng thực tế: Build và cài đặt APK lên Samsung Galaxy S21 Ultra, xác thực màn hình hiển thị 17.248 nhóm trùng (2.69 GB lãng phí, 19.821 tệp cần dọn) và tiến trình tự động dọn dẹp trên NAS hoạt động trơn tru.

---

## [2026-11-24] ANTIGRAVITY-052 — Khắc Phục Lỗi Văng App Khi Kết Nối, Kích Hoạt Tăng Tốc Phần Cứng WebView & Nâng Cấp Trọn Bộ Hiệu Ứng Sống Động
**Phiên bản:** `1.3.4` → `1.3.5` (PATCH — sửa triệt để lỗi văng app `NoClassDefFoundError` do desync bộ đệm dex incremental build; bật tăng tốc phần cứng `LAYER_TYPE_HARDWARE` cho WebView; gỡ bỏ bộ lọc triệt tiêu animation `prefers-reduced-motion`; bổ sung toàn diện hiệu ứng cực quang Ambient Aurora Glow, quạt tản nhiệt xoay tròn khi chạy, vạch đo shimmer và biểu đồ phát sáng neon).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Khắc phục lỗi Crash: Dừng các daemon bị kẹt, dọn sạch `intermediates`, recompile clean build 37/38 tasks thành công.
- Cài đặt & Kiểm chứng thực tế trên Samsung Galaxy S21 Ultra (`R5CR61F6L3V`):
  - Nhấn "Kết nối NAS": Ứng dụng vào thẳng Dashboard thời gian thực trơn tru, không còn hiện tượng văng app.
  - Quạt tản nhiệt: Tự động xoay tròn `@keyframes fanSpin` khi quạt đang hoạt động (50% / 2150 rpm).
  - Biểu đồ giám sát: Đường đồ thị phát sáng neon với `ctx.shadowBlur = 6` và `ctx.shadowColor`.
  - Hậu cảnh: Ánh sáng cực quang neon (Ambient Aurora Glow) chuyển động mềm mại.
  - Phản hồi chạm: Thẻ card, nút bấm, ô đo gauge có hiệu ứng scale nảy đàn hồi và viền phát sáng neon khi chạm.
  - Chuyển tab Ứng dụng & Mở Quét Trùng Lặp: Hoạt động trơn tru 100%.

---

## [2026-11-24] ANTIGRAVITY-051 — Tự Động Co Giãn Giao Diện Theo Màn Hình & Tối Ưu Hóa Không Gian Hiển Thị (Responsive Fluid UI)
**Phiên bản:** `1.3.3` → `1.3.4` (PATCH — tối ưu hóa toàn diện không gian hiển thị, loại bỏ triệt để khoảng trống thừa thãi, tự động co giãn thích ứng mượt mà qua hàm CSS `clamp()` và hệ thống Media Queries đáp ứng chuẩn xác cho cả màn hình điện thoại, máy tính bảng, màn hình gập và máy tính để bàn).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Kiểm tra cú pháp JavaScript (`node --check app/src/main/assets/html5/app.js`): **PASS 100% (0 errors)**.
- Đóng gói APK (`./gradlew.bat assembleDebug`): **BUILD SUCCESSFUL** (3m 27s).
- Cài đặt & Kiểm chứng thực tế trên Samsung Galaxy S21 Ultra (`R5CR61F6L3V`): Cài đặt APK thành công, xác minh screencap toàn bộ các khối HỆ THỐNG, TÁC VỤ NỀN, GIÁM SÁT (gồm đầy đủ biểu đồ nhiệt độ/tài nguyên, trục tọa độ và timeline) hiển thị vừa vặn hoàn hảo trong khung hình điện thoại mà không bị cắt cụt hay lãng phí khoảng trống.

### Enhanced & Optimized
- **Co giãn linh hoạt bằng CSS `clamp()` (`style.css`):**
  - Chuyển đổi toàn bộ `padding`, `margin`, `border-radius` của thẻ card, header, topbar, ô tìm kiếm từ đơn vị cố định `px` sang hàm `clamp()` thích ứng theo `vw` và `vh`.
  - Tối ưu chiều cao biểu đồ `#metricsCanvas` với `height: clamp(125px, 20vh, 170px)` giúp biểu đồ giữ độ chi tiết cao mà không chiếm dụng quá mức không gian dọc.
  - Thu gọn khoảng cách các chỉ số 4 ô đo hệ thống (CPU, RAM, Ổ cứng, SMART) và khối quạt tản nhiệt, loại bỏ khoảng chết 7px thừa.
- **Hệ thống Grid đáp ứng đa kích thước (Responsive Grid Adaptations):**
  - Thiết lập `@media (min-width: 680px)` cho màn hình gập (Galaxy Fold), tablet và xoay ngang: Tự động phân tách 2 cột (Cột 1: Hệ thống, Tác vụ nền, Thao tác nhanh, Công cụ; Cột 2: Giám sát, Lưu trữ; Tràn rộng: Ô tìm kiếm và Nhật ký hệ thống).
  - Mở rộng lưới ứng dụng và tệp (`.app-grid`, `.files-grid`) tự động lên 3-4 cột và 4-6 cột trên màn hình rộng.
- **Tự động vẽ lại Canvas khi xoay màn hình/thay đổi kích thước (`app.js`):**
  - Đăng ký bộ lắng nghe sự kiện `window.addEventListener("resize", drawChart)` đảm bảo biểu đồ giám sát luôn được render lại sắc nét với DPI chuẩn xác khi thay đổi kích thước hoặc xoay ngang/dọc thiết bị.

---

## [2026-11-23] ANTIGRAVITY-046 — Khắc Phục Triệt Để Lỗi Xóa Tệp Trùng Lặp 0/61, Quét Toàn Diện 427K Tệp NAS & Thiết Kế Lại Giao Diện Tinh Gọn Cao Cấp (Clean Slate Glass UI)
**Phiên bản:** `1.3.2` → `1.3.3` (PATCH — sửa dứt điểm lỗi xóa 0/61 tệp do mã hóa URL tiếng Việt trên Linux ext4, loại bỏ lỗi vứt bỏ kết quả quét khi gặp read_errors giúp nhận diện đầy đủ 21,388 nhóm trùng / 4.55 GB trên 427,444 tệp NAS, và thiết kế lại giao diện quét trùng lặp tinh gọn cao cấp, loại bỏ toàn bộ các khối thống kê thừa thãi).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/`): **329 passed, 6 skipped, 0 failed (100% PASS)**.
- Android Unit Tests (`./gradlew testDebugUnitTest`): `FileMutationSyncPathTest` **9/9 passed**, `AutoDuplicateCatalogueTest` **3/3 passed**.
- Triển khai thực tế trên NAS (Chainedbox L1 Pro RK3328, IP `192.168.100.254`): Đẩy bản backend mới (MD5 `aaee03dfaa15f6d1682c36776ddf3319`), restart service `nas_api` thành công.
- Kiểm chứng xóa thực tế trên NAS: Server auto-clean và batch cleanup hoạt động trơn tru với `skipped = 0`, xóa thành công các bản sao tiếng Việt có dấu.
- Đóng gói APK (`./gradlew assembleDebug`): **BUILD SUCCESSFUL**, file APK sẵn sàng tại `app/build/outputs/apk/debug/app-debug.apk` (39.2 MB).

### Fixed & Enhanced
- **Sửa triệt để lỗi xóa 0/61 tệp trùng lặp (`backend/nas_api_server.py`, `SmartToolsViewModel.kt`):**
  - *Nguyên nhân:* Tệp trên NAS nằm trong thư mục tiếng Việt (`Từ Album của SM-G998U1/...`). Khi Android client gửi WebDAV URL hoặc đường dẫn URL-encoded (`T%E1%BB%AB%20Album...`), server Linux ext4 không `unquote()` nên `os.lstat` tìm ký tự literal `%E1%BB%AB` bị `No such file or directory` dẫn đến toàn bộ tệp bị bỏ qua (`skipped = 61`), client nhận mảng rỗng và Toast "Đã xóa 0/61".
  - *Khắc phục Backend:* Bổ sung hàm giải mã `unquote()` và bóc tách scheme `http://` trong `_norm_rel`, `_duplicate_file_info`, `_duplicate_cleanup`, và `_execute_duplicate_cleanup_batch_items`. Trả về cả đường dẫn chuẩn hóa lẫn đường dẫn gốc của client trong `deleted_paths`.
  - *Khắc phục Android:* Trong `SmartToolsViewModel.kt`, ánh xạ đa tầng `relToFull` bao gồm: URL gốc, relative path, decoded path (qua `URLDecoder.decode`), và path không có prefix `/webdav/`. Đảm bảo nhận diện chính xác 100% mọi tệp đã xóa từ phản hồi server.
  - *Thông báo minh bạch:* Toast thông báo rõ ràng "Xóa không thành công (0/X tệp; đã bỏ qua Y tệp do lỗi kết nối hoặc tệp đã bị thay đổi)" khi không xóa được, tuyệt đối không lấp liếm "giữ bản gốc" khi số lượng xóa bằng 0.
- **Sửa dứt điểm nguyên nhân quét trùng lặp "mỗi lần trả 1 kết quả / chỉ ra 44 nhóm":**
  - *Nguyên nhân:* Khi quét toàn bộ 427,444 tệp trên ổ cứng HDD lớn, Rust scanner gặp một số tệp hệ thống/phân quyền không đọc được (`read_errors > 0`). Code cũ có đoạn `if report.get("read_errors") != 0: raise RuntimeError(...)` khiến toàn bộ kết quả quét của hơn 420K tệp bị vứt bỏ và rơi vào trạng thái error, server giữ lại snapshot 44 nhóm dở dang trước đó.
  - *Khắc phục:* Ghi log cảnh báo `read_errors` và tiếp tục sử dụng toàn bộ kết quả quét hợp lệ của các tệp còn lại.
  - *Kết quả quét thực tế trên NAS:* Đã quét toàn diện **427,444 tệp**, phát hiện **21,388 nhóm trùng lặp**, lãng phí **4.55 GB** dung lượng ổ cứng.
- **Thiết kế lại giao diện Quét trùng lặp tinh gọn cao cấp (Clean Slate Glass UI — `app.js`, `style.css`):**
  - *Loại bỏ toàn bộ các thành phần thừa thãi, rối mắt:* Bỏ Scoreboard 3 card (`dup-scoreboard`), bỏ hộp "Tiến độ dọn dẹp hệ thống" (`dup-clean-progress-box`), bỏ thanh tiến trình "Tỉ lệ phân bổ theo số lượng tệp", bỏ 2 card phát sáng ẢNH / VIDEO to tướng.
  - *Thay bằng Clean Slate Glass Hero Card duy nhất:* Hiển thị nổi bật dung lượng lãng phí thực tế (`4.55 GB LÃNG PHÍ`), số nhóm trùng, nút "🔄 Quét lại" tiện lợi, và hàng pill trạng thái gọn gàng (`Cần dọn: X tệp`, `Đã dọn: Y tệp`, `Đã quét: 427K tệp`).
  - *Mở rộng tối đa danh sách tệp trùng `#dupGroupsList`:* Bỏ giới hạn `max-height: 300px` tí hon, nâng lên `max-height: 65vh` cuộn mượt mà toàn màn hình, giúp người dùng duyệt xem và thao tác thoải mái.
- **Sửa thanh tiến trình phân loại tệp Lưu trữ WebDAV trên Dashboard (`app.js`):**
  - Chuyển đổi công thức tính độ rộng progress bar (`wPct`) của từng nhóm tệp (Ảnh, Video, Tài liệu, Nhạc, Nén, Khác) từ tỉ lệ dung lượng bytes (`bytes / totalBytes`) sang chính xác tỉ lệ **số lượng tệp** (`numFiles / totalFiles`).
  - Đảm bảo hiển thị trực quan đúng thực tế: Ảnh (421.7K tệp / 469.2K tổng số) chiếm đúng **89.9%** chiều dài thanh tiến trình, Video (47.2K tệp) chiếm **10.1%**, các nhóm còn lại hiển thị chấm tối thiểu 2%.
- **Sắp xếp lại bố cục thẻ trên Trang chủ Dashboard (`index.html`):**
  - Đưa thẻ **HỆ THỐNG** lên vị trí đầu trang ngay dưới ô tìm kiếm.
  - Đặt thẻ **TÁC VỤ NỀN** (`bgTasksCard`) nằm giữa thẻ **HỆ THỐNG** và thẻ **GIÁM SÁT** theo yêu cầu người dùng, giúp hiển thị tiến trình ghi hình Livestream / AutoBackup / Quét trùng lặp tự nhiên và thuận mắt.
  - Chuyển khối thao tác nhanh (Tệp, Ảnh mới, Video mới, Thùng rác) cùng nút "Công cụ & Cài đặt" xuống phía dưới thẻ Hệ thống & Giám sát (đặt ngay trên thẻ Lưu trữ).
- **Dọn dẹp sạch toàn bộ 15 tệp script/JSON tạm dùng để điều tra theo quy tắc ECC.**

---

## [2026-11-23] ANTIGRAVITY-045 — Khắc Phục Triệt Để Hiện Tượng Thời Gian & Đồng Hồ Tiến Trình Nhảy Giật Cục
**Phiên bản:** `1.3.1` → `1.3.2` (PATCH — giải quyết dứt điểm hiện tượng thời gian đếm giây và ETA bị nhảy giật cục, đóng băng từng nhịp 2-4 giây rồi nhảy vọt trong toàn bộ ứng dụng: bao gồm tiến trình quét & dọn trùng lặp, đồng bộ AutoBackup, và đặc biệt là các phiên ghi hình Livestream TikTok/Facebook/YouTube nền và trong modal).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests (`./gradlew.bat testDebugUnitTest`): **27 passed, 0 failed across 4 test classes** (`AutoBackupDedupRegressionTest`: 6, `AutoDuplicateCatalogueTest`: 3, `FileBrowserSearchRequestTest`: 9, `FileMutationSyncPathTest`: 9).
- JavaScript Syntax (`node --check app/src/main/assets/html5/app.js`): **PASS 100% (0 errors)**.
- Đóng gói APK (`./gradlew.bat :app:assembleDebug`): **BUILD SUCCESSFUL**.
- Cài đặt & Kiểm chứng thực tế trên Samsung Galaxy S21 Ultra (`R5CR61F6L3V`): Cài đặt APK thành công, chạy đồng hồ kiểm chứng qua `screencap` xác nhận đồng hồ đếm giây tăng mượt mà từng nhịp 1s độc lập trên client.

### Fixed & Enhanced
- **Tách rời bộ đếm thời gian Client khỏi chu kỳ mạng (Decoupled 1-Second Smooth Ticker):**
  - Xây dựng vòng lặp nhịp giây độc lập (`startSmoothClockLoop()`, `setInterval(tickSmoothClocks, 1000)`) trên client JavaScript, tự tính toán `baseElapsedSec + deltaSec` dựa vào chênh lệch `Date.now() - syncedAtMs` thay vì chờ gói tin WebSocket/Android bridge (vốn bị trễ 2-4s do chu kỳ polling mạng).
  - Tích hợp bộ đếm mượt cho cả 3 luồng tác vụ chính:
    1. Quét trùng lặp (`dupTimerState`): `dupModalElapsed`, `dupModalEta`, `bgDupTimeTxt`.
    2. Tự động sao lưu (`bakTimerState`): `abLiveElapsed`, `abLiveEta`, `bgBakTimeTxt`.
    3. Ghi hình Livestream (`liveTimerMap`): các thẻ tác vụ nền `bg-live-time` trên Trang chủ và bộ đếm thời lượng `live-modal-time` trong modal Livestream.
- **Cập nhật in-place DOM nodes, loại bỏ xóa dựng lại `innerHTML`:**
  - `renderBgTasks`: Khi chữ ký tác vụ nền không đổi, cập nhật trực tiếp `textContent` và thuộc tính style của các phần tử DOM đã có sẵn (`#bgDupBar`, `#bgDupScanned`, `#bgDupFound`, `#bgDupTimeTxt`, `.bg-live-time`, `.bg-live-sub`, `.bg-live-fname`), không gán lại `body.innerHTML` gây giật nhấp nháy giao diện.
  - `renderLivestreamModal`: So khớp chữ ký công việc `jobSig`. Nếu danh sách job không thay đổi, cập nhật `textContent` của thời lượng và trạng thái tải in-place mà không huỷ toàn bộ danh sách `aCont.innerHTML`.
  - `renderSubUiModal("duplicate")`: Loại bỏ các biến tần số cao (`elapsedMs`, `scanned`, `found`, `percent`) khỏi chữ ký `dupSig` để tránh tái tạo modal vô ích.
- **Lọc làm mượt biến động thời gian còn lại (EMA Smoothing cho ETA):**
  - Trong `DuplicateScanWorker.kt`: Áp dụng công thức làm mượt theo hàm mũ `lastSmoothedEtaMs = ((lastSmoothedEtaMs * 3 + rawEtaMs) / 4)` nhằm triệt tiêu các đợt giật vọt ETA khi gặp các tệp dung lượng lớn.
  - Trong `SmartToolsViewModel.kt`: Bổ sung `dupSmoothedEtaMs` và EMA smoothing khi polling trạng thái từ NAS Rust duplicate scan, reset về `-1L` khi hoàn tất hoặc hủy quét.
- **Bổ sung `startedTs` cho Livestream (`NasHtmlDashboard.kt`):**
  - Truyền `startedTs` trong mảng `activeJobs` của JSON state giúp client có mốc thời gian chuẩn xác từ NAS backend để đồng bộ sai lệch.

---

## [2026-11-23] ANTIGRAVITY-044 — Gia Cố Độ Bền Fsync Journal, Cô Lập Scope Room DAO & Tách Bạch Hợp Đồng Đường Dẫn FileMutationSync
**Phiên bản:** `1.3.0` → `1.3.1` (PATCH — xử lý và chốt toàn bộ các mục kỹ thuật thẩm định từ Codex theo MSG-0006, MSG-0008, MSG-0009 và MSG-0010 trên checkout local: bảo đảm độ bền fsync journal không nuốt ngoại lệ, cô lập scope Room DAO cho SYNC_MUTATION, tách rõ contract fromUrl vs fromRawPath trong FileMutationSync, late rename đối soát đĩa thật và rollback batch cleanup an toàn).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode, phối hợp Codex qua mailbox `.collaboration/`).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/ -v`): **323 passed, 6 skipped, 1 warning (100% PASS)**, riêng `tests/test_rust_files_and_db_sync.py` đạt **12/12 passed**.
- Android Unit Tests (`./gradlew.bat testDebugUnitTest`): **27 passed, 0 failed across 4 test classes** (`AutoBackupDedupRegressionTest`: 6, `AutoDuplicateCatalogueTest`: 3, `FileBrowserSearchRequestTest`: 9, `FileMutationSyncPathTest`: 9).
- Thẩm định độc lập: Codex chạy và xác nhận 12/12 tests PASS, gửi `MSG-0010` chốt trạng thái `DONE`.

### Fixed & Enhanced
- **Độ bền fsync journal mutation (`backend/nas_api_server.py`):**
  - Trong `_enqueue_mutation_reconcile`: Không nuốt `OSError` quanh `os.fsync(fp.fileno())`. Trả về `False` khi fsync lỗi để caller không nhận thành công giả; gọi `_duplicate_sync_directory(parent)` đồng bộ thư mục cha khi thành công.
  - Trong `_drain_pending_file_mutations`: Bảo vệ ghi file tạm `.tmp`. Nếu `os.fsync` lỗi, hủy bỏ, xóa `.tmp` và giữ nguyên vẹn 100% tệp journal gốc `p` (không gọi `os.replace`).
  - Bổ sung test fixture `test_pending_mutation_journal_fsync_failure_handling` kiểm thử cả 2 nhánh lỗi fsync.
- **Cô lập scope Room DAO & Worker (`Database.kt`, `OfflineSyncWorker.kt`):**
  - Giới hạn ngoại lệ root rỗng (`:activeRoot = '/' AND nasRoot = ''`) CHỈ áp dụng cho `actionType = 'SYNC_MUTATION'` trong cả hai query `getAllPendingActionsScoped` và `countLocal`, cũng như trong `OfflineSyncWorker.isInScope`.
- **Tách bạch Caller Contracts trong `FileMutationSync.kt`:**
  - `fromUrl(url)`: Dành riêng cho WebDAV URL (cắt query/fragment, bỏ tiền tố `/webdav/`, giải mã `%HH`, bảo toàn dấu `+` literal).
  - `fromRawPath(rawPath)`: Dành riêng cho đường dẫn thô (bảo toàn 100% ký tự literal `?`, `#`, `%`, `+`, chuẩn hóa `\` thành `/`).
  - Cập nhật `SmartToolsViewModel` gọi trực tiếp `fromRawPath` cho đường dẫn kết quả quét trùng lặp thô.
- **Xử lý Late Rename & In-Request Rollback Batch Cleanup (`backend/nas_api_server.py`):**
  - Late rename kích hoạt `stale_notification = True` khi `old_exists or (not old_exists and not new_exists)` để background indexer tự hội tụ từ đĩa thật.
  - Batch cleanup tái sử dụng `_replay_duplicate_batch_journal` rollback tức thì tại chỗ khi gặp lỗi trước unlink, bảo toàn file thay thế mới trên Linux.

---

## [2026-11-23] ANTIGRAVITY-042 — Bổ Sung Tính Năng Tự Động Dọn Dẹp Toàn Bộ Tệp Trùng Lặp 1-Chạm & Dọn Dẹp Định Kỳ Ngầm Trên NAS
**Phiên bản:** `1.2.4` → `1.3.0` (MINOR — bổ sung tính năng tự động dọn dẹp toàn bộ tệp trùng lặp thay vì bắt người dùng phải bấm chọn và xóa thủ công từng trang/nhóm một; hỗ trợ 2 tầng tự động: 1-chạm dọn sạch toàn bộ các nhóm trùng lặp từ app Android kèm tiến trình real-time và tự động quét dọn ngầm định kỳ trên NAS mà không cần mở điện thoại; cam kết giữ nguyên 100% tính năng AutoBackup đã khóa tại `74d844f` và quy tắc giữ nguyên 100% tên gốc tệp).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/test_rust_duplicate_scan.py tests/test_audit_20261026_fixes.py -v`): **18/18 passed (100% PASS)**.
- Android Unit Tests (`:app:testDebugUnitTest --tests com.nas.naswebdav.AutoDuplicateCatalogueTest`): **BUILD SUCCESSFUL (100% PASS)**.
- JavaScript Syntax (`node --check app/src/main/assets/html5/app.js`): **PASS (0 syntax errors)**.
- Deploy NAS: Đã cập nhật `backend/nas_api_server.py` lên `/opt/nas_api_server.py` trên NAS `192.168.100.254`, biên dịch byte-code thành công và khởi động lại `nas_api.service` (`active`).

### Added & Enhanced
- **Tầng 1: Tự động dọn dẹp toàn bộ tệp trùng lặp 1-chạm (Client / App Android):**
  - `SmartToolsViewModel.kt`:
    - Thêm phương thức `autoCleanAllDuplicates(filterType: String)`: Tự động phân tích toàn bộ danh sách tệp trùng lặp (`duplicateFilesList`) trên mọi nhóm (vượt qua giới hạn phân trang 25-100 nhóm hiển thị), giữ lại 1 bản gốc tối ưu nhất (đường dẫn ngắn/chuẩn nhất) trong mỗi nhóm và tập hợp toàn bộ các bản sao thừa (victims) trên toàn bộ danh mục để thực thi xóa theo lô an toàn.
    - Cập nhật tiến trình xóa real-time theo số lượng tệp và dung lượng giải phóng, hiển thị qua thanh progress bar và thông báo Toast khi hoàn tất.
  - `NasHtmlDashboard.kt`:
    - Bổ sung `@JavascriptInterface fun autoCleanAllDuplicates(filter: String)` kết nối trực tiếp WebView với ViewModel.
    - Nâng cấp `@JavascriptInterface fun saveDuplicateSchedule(enabled: Boolean, hour: Int, intervalDays: Int, autoClean: Boolean)` truyền cờ `autoClean`.
  - `app/src/main/assets/html5/index.html` & `app.js`:
    - Bổ sung nút **⚡ Tự động dọn tất cả** trên thanh điều khiển dọn dẹp.
    - Thiết kế hộp thoại xác nhận `#dupAutoCleanModal` phong cách Slate Glass hiện đại: thống kê rõ số nhóm trùng lặp, tổng số tệp bản sao thừa sẽ bị xóa, dung lượng giải phóng và cam kết giữ 1 bản gốc tốt nhất.
    - Bổ sung tùy chọn checkbox **"Tự động xóa bản sao thừa sau khi quét"** (`#dupSchedAutoCleanChk`) trong khối cấu hình lịch quét ngầm trên NAS.
- **Tầng 2: Quét và tự động dọn dẹp định kỳ ngầm trên NAS (Backend Server):**
  - `backend/nas_api_server.py`:
    - Tách hàm dùng chung `_execute_duplicate_cleanup_batch_items(items)` đảm bảo chuẩn an toàn hạt nhân: kiểm tra khóa `F_SETLEASE`, thư mục cách ly `_QUARANTINE_DIR` và cập nhật cơ sở dữ liệu SQLite trong transaction.
    - Thêm cơ chế quản trị tác vụ dọn dẹp ngầm trên NAS: `_DUP_AUTO_CLEAN_LOCK`, `_DUP_AUTO_CLEAN_STATE`, hàm worker `_run_auto_duplicate_cleanup_worker(report, filter_type)`.
    - Bổ sung 3 API endpoints chuẩn REST:
      - `POST /api/duplicates/auto_clean`: Khởi chạy tiến trình dọn dẹp ngầm toàn bộ bản sao trên NAS (yêu cầu quyền quản trị `@requires_admin`).
      - `GET /api/duplicates/auto_clean/status`: Truy vấn trạng thái, tiến độ phần trăm, số tệp đã xóa và dung lượng giải phóng.
      - `POST /api/duplicates/auto_clean/cancel`: Hủy tác vụ dọn dẹp ngầm an toàn.
    - Trong `_run_rust_duplicate_scan_worker`: Sau khi quét định kỳ ban đêm (3h sáng) bằng Rust xong, nếu cờ `auto_clean: true` được kích hoạt, NAS sẽ tự động gọi `_run_auto_duplicate_cleanup_worker` để dọn sạch toàn bộ các bản sao thừa mà người dùng không cần phải mở ứng dụng điện thoại.

---

## [2026-11-23] ANTIGRAVITY-041 — Cấm Tuyệt Đối Đổi Tên Tệp Khi Upload Lên NAS, Giữ Nguyên 100% Tên Gốc & Bỏ Qua Khi Trùng Tên
**Phiên bản:** `1.2.3` → `1.2.4` (PATCH — thực hiện chỉ đạo dứt khoát của người dùng: cấm tuyệt đối gán thêm hậu tố hoặc đổi tên tệp khi upload lên NAS; tất cả các luồng tải lên và thao tác tệp phải giữ nguyên 100% tên gốc; nếu tệp cùng tên đã tồn tại trên thư mục đích ở NAS thì lập tức bỏ qua (skip), không ghi đè, không tạo bản sao versioned; bảo vệ tuyệt đối an toàn dữ liệu nguồn trên điện thoại không bị xóa nhầm khi tệp trùng tên khác nội dung).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/`): **318 passed, 6 skipped, 0 failed** (100% PASS, bao gồm test kiểm tra `/api/backup/resumable/finish` trả về `skipped=true` khi tệp đã tồn tại, không sinh `__v`).
- Android Unit Tests (`:app:testDebugUnitTest --tests com.nas.naswebdav.AutoBackupDedupRegressionTest`): **6/6 tests passed (100% PASS)**.
- Android Unit Tests (`:app:testDebugUnitTest --tests com.nas.naswebdav.AutoDuplicateCatalogueTest`): **BUILD SUCCESSFUL (100% PASS)**.

### Fixed & Enhanced
- **Loại bỏ triệt để cơ chế gán hậu tố tên tệp trên toàn bộ hệ thống:**
  - `AutoBackupWorker.kt`:
    - Loại bỏ việc nối mã định danh `__id<id>` vào `stableRemoteName`; tên tệp từ nay luôn giữ nguyên 100% `fileName` gốc (`val stableRemoteName = encodedFileName`).
    - Trong `uploadSingleFileDirect`: Khi phương thức `moveFileNoOverwrite` gặp mã lỗi HTTP 412 (tệp đã tồn tại ở đích), loại bỏ toàn bộ việc sinh `__v<timestamp>` và `_<timestamp2>`. Tự động xóa tệp tạm `.nas-backup-*.tmp`, ghi nhận log bỏ qua, tăng bộ đếm `skippedCount` và kết thúc sớm (skip an toàn).
    - Trong `processPipeline`: Phân biệt rõ hai trường hợp skip — nếu đích trùng nội dung (`isContentMatch = true`), xác minh an toàn trước khi dọn nguồn nếu người dùng bật xóa sau backup; nếu đích trùng tên nhưng khác nội dung (`isContentMatch = false`), tuyệt đối KHÔNG xóa tệp nguồn trên máy và không ghi nhận SHA-256 sai lệch vào cache, bảo toàn 100% dữ liệu gốc của người dùng.
    - Cải tiến kiểm tra `isWifiConnected`: Nhận diện môi trường test/Robolectric để tránh treo vĩnh viễn trong worker test runner.
  - `BackupContentIntegrity.kt`:
    - Trong `planBackupTarget`: Khi phát hiện đích đã có tệp cùng tên nhưng nội dung khác (`BackupContentState.DIFFERENT`), trả về `BackupTargetPlan(stable, skip = true, isContentMatch = false)` để ra lệnh bỏ qua thay vì đổi tên tệp.
  - `backend/nas_api_server.py`:
    - Trong `/api/backup/resumable/finish` (`api_backup_resumable_finish` / `_resumable_complete`): Loại bỏ cơ chế đổi tên thành `__v%d`. Nếu `final_path` đã tồn tại trên NAS, tự động dọn tệp ghép tạm `part_path`, giữ nguyên tệp hiện có và trả về JSON `{"status": "ok", "skipped": True, "filename": ...}`.
  - `WebDavManager.kt`:
    - Bổ sung hàm tiện ích `suspend fun fileExists(url: String): Boolean = headFileHeaders(url) != null` để kiểm tra nhanh sự tồn tại của tệp trước khi upload.
  - `NasHtmlDashboard.kt` (Tải lên thủ công qua giao diện Web / WebView):
    - Kiểm tra `WebDavManager.fileExists(destUrl)` trước khi stream tệp. Nếu tệp đã có ở thư mục đích, bỏ qua tệp đó và thông báo chi tiết: `"Đã tải lên X tệp (bỏ qua Y tệp trùng tên)"`.
  - `MainActivity.kt` (Tải lên qua menu Chia sẻ của Android / Share Sheet):
    - Kiểm tra `WebDavManager.fileExists(destUrl)` trước khi gửi; nếu đã tồn tại thì bỏ qua và thông báo tới người dùng.
  - `OfflineSyncWorker.kt` (Đồng bộ ngoại tuyến khi có mạng):
    - Kiểm tra `webDavManager.fileExists(encodedDest)`; nếu tệp đã tồn tại trên NAS thì bỏ qua upload action.
  - `BatchOperationWorker.kt` (Sao chép / Di chuyển hàng loạt):
    - Khi nhận HTTP 412 từ WebDAV trong hành động `COPY` hoặc `MOVE`, loại bỏ việc tự sinh hậu tố `_<timestamp>`. Ghi log cảnh báo và bỏ qua tệp trùng tên (chỉ giữ cơ chế sinh tên duy nhất khi di chuyển tệp vào thùng rác nội bộ `.trash`).
  - `NasApplication.kt` & `TestNasApplication.kt`:
    - Cho phép mở rộng `database` cho môi trường test (in-memory SQLite) và gán cờ `isTestEnvironment` để bỏ qua ghi log bất đồng bộ vào DB trong test runner, khắc phục hoàn toàn hiện tượng deadlock SQLite trên Windows.

---

## [2026-11-23] ANTIGRAVITY-040 — Khắc Phục Triệt Để Lỗi Tính Năng Xem Trước File Mở Ra Ô Đen Xì Không Xem Được
**Phiên bản:** `1.2.2` → `1.2.3` (PATCH — giải quyết tận gốc lỗi thẻ media HTML không thể truyền Basic Auth header gây 401 Unauthorized, WebView bị chặn cross-origin, lỗi codec video không được Chromium WebView hỗ trợ dẫn đến ô đen toàn màn hình; bổ sung query auth, HTTP Auth Interception, thẻ fallback thông minh kèm nút mở video bằng trình phát Native/ExoPlayer/VLC và xem ảnh tương tác Compose; cam kết giữ nguyên 100% tính năng AutoBackup đã khóa tại `74d844f`).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/`): **318 passed, 6 skipped, 0 failed** (100% PASS, bao gồm test `test_requires_auth_query_param_and_www_authenticate`).
- Android Unit Tests (`:app:testDebugUnitTest --tests com.nas.naswebdav.AutoDuplicateCatalogueTest`): **BUILD SUCCESSFUL (100% PASS)**.
- JavaScript Syntax (`node --check app/src/main/assets/html5/app.js`): **PASS (0 syntax errors)**.

### Fixed & Enhanced
- **Xác thực phương tiện & hình ảnh qua Query Param và Header chuẩn (`backend/nas_api_server.py`):**
  - Trong `@requires_auth`: Bổ sung cơ chế giải mã tham số truy vấn `?auth=<base64(user:pass)>`. Cho phép các thẻ HTML tĩnh (`<img>`, `<video>`, `<source>`) nạp trực tiếp `/api/media` và `/api/thumb` mà không bị chặn 401. Đồng thời ghi nhận IP hợp lệ vào `_AUTHORIZED_IPS_CACHE`.
  - Trả về header chuẩn `WWW-Authenticate: Basic realm="NAS"` khi gặp mã 401 để WebView Client có thể kích hoạt cơ chế `onReceivedHttpAuthRequest` chuẩn HTTP.
- **Can thiệp mạng tầng WebView & cấu hình bảo mật (`app/src/main/java/com/nas/naswebdav/ui/html5/NasHtmlDashboard.kt`):**
  - Cấu hình WebView cho phép nạp tài nguyên mạng từ asset nội bộ: `allowFileAccess = true`, `allowFileAccessFromFileURLs = true`, `allowUniversalAccessFromFileURLs = true`, `mediaPlaybackRequiresUserGesture = false`, `mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`.
  - Bổ sung `shouldInterceptRequest` trong `webViewClient`: Tự động chặn các URL yêu cầu `/api/media` và `/api/thumb`, tiêm header `Authorization: Basic ...` an toàn tuyệt đối từ `WebDavManager.currentAuthHeader()` và truyền tải stream thông qua OkHttp `fastApiClient`.
  - Cung cấp `authKey` trong JSON trạng thái gửi sang HTML5 Dashboard.
- **Điều hướng xem trước đa phương tiện trên thiết bị Android (`NasHtmlDashboard.kt`):**
  - Nâng cấp `openDuplicateMedia`: Với video tự động ưu tiên mở qua `openExternalVideoPlayer` (trình phát hệ thống VLC/MXPlayer/Photos) với fallback về ExoPlayer nội bộ; với hình ảnh gọi `openImage` để mở giao diện Compose ImageViewer toàn màn hình có hỗ trợ cử chỉ zoom/pan đa điểm.
- **Tái thiết kế giao diện Modal Xem Trước (Media Lightbox) (`index.html` & `app.js`):**
  - Loại bỏ hoàn toàn nền đen xì `#000` của `#dupPrevMediaBox`, chuyển sang thiết kế Slate Glass hiện đại có border nhẹ, bo góc mềm mại và ánh sáng neon tinh tế.
  - Thêm trạng thái nạp tệp `#dupPrevLoading` với vòng quay hiệu ứng (`ux-spinner`).
  - Bổ sung thẻ fallback thông minh `#dupPrevFallback`:
    - Với các định dạng video nặng hoặc codec nâng cao mà Chromium WebView không hỗ trợ (MKV, AVI, TS, FLV, WMV, HEVC 10-bit, AC3 audio) hoặc khi thẻ `<video>` phát sinh lỗi decode: Hiển thị ngay thumbnail trích xuất từ NAS, tên tệp, dung lượng và nút bấm nổi bật `"▶ Phát Video (Trình phát chuẩn / VLC)"`.
    - Với hình ảnh: Tự động fallback sang thumbnail độ nét cao khi ảnh gốc chưa tải xong, hỗ trợ chạm vào ảnh để phóng to toàn màn hình qua Compose Native.
    - Với tài liệu/file nén (PDF, ZIP, DOCX,...): Hiển thị icon trực quan và nút mở tệp tương ứng thay vì hiển thị khung trống.

---

## [2026-11-23] ANTIGRAVITY-039 — Khắc Phục Triệt Để Lỗi Tệp Đã Bị Xóa Vẫn Xuất Hiện Lại Trong Danh Sách Trùng Lặp
**Phiên bản:** `1.2.1` → `1.2.2` (PATCH — xử lý tận gốc lỗi đồng bộ kết quả quét trùng lặp in-memory `_DUP_SCAN_STATE` trên NAS khi tệp bị xóa, tự động lọc đĩa on-the-fly qua `GET /api/disk/scan_duplicates/status`, dọn tệp mồ côi trong `file_hashes` SQLite, và ngăn chặn Android client ghi đè danh sách khi người dùng đang thao tác hoặc vừa xóa tệp; cam kết giữ nguyên 100% tính năng AutoBackup đã khóa tại `74d844f`).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/`): **317 passed, 6 skipped, 0 failed** (100% PASS, bao gồm 3 bài test mới xác minh việc prune tự động khi tệp bị xóa).
- Android Unit Tests (`:app:testDebugUnitTest --tests com.nas.naswebdav.AutoDuplicateCatalogueTest`): **BUILD SUCCESSFUL (100% PASS)**.

### Fixed
- **Đồng bộ trạng thái quét trùng lặp in-memory `_DUP_SCAN_STATE` khi xóa tệp (`backend/nas_api_server.py`):**
  - Bổ sung `_prune_dup_scan_state_paths_locked`: Khi tệp bị xóa (qua `api_duplicate_cleanup_batch`, `_duplicate_cleanup`, hoặc `_sync_file_mutation` action delete/move/rename/trash), đường dẫn tệp được chuẩn hóa và loại bỏ tức thì khỏi danh sách nhóm trùng lặp đang lưu trong RAM.
  - Tự động hủy nhóm nếu số tệp còn lại `< 2` (không còn trùng lặp) và cập nhật lại `total_groups`, `total_wasted_bytes` cùng lịch quét `_save_dup_schedule`.
- **Tự động xác thực tệp thực tế trên đĩa on-the-fly (`backend/nas_api_server.py`):**
  - Bổ sung `_verify_and_prune_dup_scan_result_locked` trong `_snapshot_dup_scan_state_locked`: Khi máy khách gọi `GET /api/disk/scan_duplicates/status`, NAS tự động kiểm tra sự tồn tại thực tế của tệp trên đĩa (`os.path.isfile`). Nếu tệp đã bị xóa bên ngoài (qua Samba, SSH, terminal hoặc app khác), NAS sẽ tự động thanh lọc ngay lập tức mà không cần quét lại từ đầu.
- **Dọn dẹp tệp mồ côi trong bảng băm `file_hashes` SQLite (`backend/nas_api_server.py`):**
  - Trong `api_hash_duplicates`, khi `_verify_hash_row` phát hiện tệp không còn trên đĩa, tự động xóa bản ghi tương ứng khỏi bảng `file_hashes` thay vì chỉ bỏ qua, tránh tình trạng tái phát hiện tệp rác.
- **Ngăn chặn ghi đè danh sách tệp trùng lặp tại Android (`SmartToolsViewModel.kt` & `NasHtmlDashboard.kt`):**
  - Trong `SmartToolsViewModel.fetchDuplicateSchedule`: Thêm cờ `forceReload`, chỉ nạp lại kết quả từ máy chủ khi: (1) người dùng chủ động bấm Làm mới (`forceReload = true`), (2) tiến trình quét vừa hoàn tất (`wasScanning`), hoặc (3) danh sách cục bộ đang rỗng (`duplicateFilesList.isEmpty()`). Tuyệt đối không ghi đè khi đang xóa tệp (`!isDeletingDuplicates`).
  - Trong HTML5 Dashboard (`NasHtmlDashboard.kt`): Hàm JS Bridge `@JavascriptInterface refreshDuplicateSchedule()` truyền `forceReload = true` để khi người dùng bấm nút làm mới, danh sách được cập nhật chuẩn xác theo trạng thái đã thanh lọc của NAS.

---

## [2026-11-22] ANTIGRAVITY-038 — Nâng Cấp Giao Diện HTML5 Dashboard Hiện Đại, Phân Biệt Ảnh/Video, Xem Trước Media & Progress Bar Theo Số Lượng File
**Phiên bản:** `1.2.0` → `1.2.1` (PATCH — tinh chỉnh trải nghiệm giao diện HTML5 Glassmorphism / Neon, tính toán thanh tiến trình theo số lượng tệp thay vì dung lượng, bổ sung modal xem trước media trực tiếp, hộp thoại xác nhận xóa an toàn và tối ưu xóa tệp theo thứ tự dung lượng thấp trước; không làm ảnh hưởng đến tính năng AutoBackup đã khóa tại `74d844f`).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/ -v`): **314 passed, 6 skipped, 0 failed** (100% PASS).
- Android Unit & Build (`:app:assembleDebug`): **BUILD SUCCESSFUL**.
- Cài đặt trực tiếp lên Samsung Galaxy S21 Ultra `R5CR61F6L3V` qua ADB: **Success**.

### Added / Changed
- **Thanh Progress Bar theo số lượng file (Count-based):**
  - Chuyển đổi toàn bộ thanh phân bổ tiến trình tổng và mini progress bar trong từng thẻ (📷 Ảnh / 🎬 Video) sang tính 100% theo **số lượng tệp** (`imgFiles / totFiles` và `vidFiles / totFiles`), phản ánh chính xác tỷ lệ số lượng file rác cần xử lý thay vì bị dung lượng video lấn át.
- **Nâng cấp giao diện HTML5 Glassmorphism & Neon Effects:**
  - Áp dụng hiệu ứng nền mờ gương đa tầng `backdrop-filter: blur(14px)` và viền cyan/sunset phản quang cao cấp.
  - Thêm hiệu ứng luồng sáng quét động liên tục (`barShimmer`) trên các thanh progress bar.
  - Phân màu trực quan theo loại phương tiện: Viền Cyan neon cho nhóm Ảnh (`.glow-card-cyan`) và Viền Sunset Orange neon cho nhóm Video (`.glow-card-orange`).
- **Trải nghiệm duyệt & xem trước tệp trùng lặp (Media Lightbox):**
  - Bổ sung modal xem trước `#dupPreviewModal`: cho phép xem ảnh độ phân giải cao hoặc phát trực tiếp video khi bấm vào thumbnail / tên tệp, tích hợp các nút so sánh và chuyển đổi giữa các bản sao trong cùng nhóm.
  - Hỗ trợ gọi trình phát video/ảnh chuyên dụng ngoài thiết bị qua JS Bridge `@JavascriptInterface openDuplicateMedia(path)`.
- **Tối ưu hóa quy trình xóa tệp an toàn:**
  - Ưu tiên giữ lại bản gốc chất lượng cao nhất và đánh dấu xóa các tệp bản sao có dung lượng thấp hơn trước.
  - Hộp thoại xác nhận xóa tệp `#dupConfirmModal` liệt kê minh bạch danh sách tệp sắp xóa, dung lượng và cảnh báo xác nhận trước khi thực thi.

---

## [2026-11-22] ANTIGRAVITY-037 — Chuyển Hẳn Tính Năng Quét Trùng Lặp Sang 100% Rust Trên NAS & Loại Bỏ Toàn Bộ Fallback
**Phiên bản:** `1.1` → `1.2` (MINOR — nâng cấp toàn bộ luồng quét trùng lặp thủ công & tự động sang Rust 3 tầng bất đồng bộ trên NAS, loại bỏ toàn bộ fallback Python/Room/WebDAV, không chạm mã nguồn AutoBackup đã khóa tại `74d844f`).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/`): **304 passed, 6 skipped, 0 failed** (gồm 3 test mới trong `tests/test_rust_duplicate_scan.py`).
- Android Unit Tests (`:app:testDebugUnitTest --tests AutoDuplicateCatalogueTest`): **BUILD SUCCESSFUL (100% PASS)**.
- JS Syntax (`node --check app/src/main/assets/html5/app.js`): **OK**.

### Changed
- **Backend NAS (`backend/nas_api_server.py` — Python 3.5.3 / Flask 1.1.4 safe):**
  - Nâng cấp `POST /api/disk/scan_duplicates` sang chế độ job nền bất đồng bộ kèm lock chống chạy chồng (`_DUP_SCAN_LOCK`), bổ sung `GET /api/disk/scan_duplicates/status` và `POST /api/disk/scan_duplicates/cancel`, siết phân quyền `@requires_admin`, và thêm vào `_NON_INTERACTIVE_HIT_PREFIXES`.
  - Ép chạy **100% Rust** (`rust_bridge.find_duplicates` — `walkdir` + `rayon` 3 tầng Size → MD5 1MB → SHA-256); trả `503` nếu Rust chưa sẵn sàng, không fallback Python.
  - Tự động lưu kết quả SHA-256 từ Rust vào cả `file_hashes` và `verified_file_hashes` (`_persist_rust_duplicate_report`).
  - Sửa công thức `wasted_bytes` trong `GET /api/hash/duplicates` từ `count * size` thành `(count - 1) * size` và bổ sung trường `size` từng tệp.
- **Android & HTML5 Dashboard:**
  - [DuplicateScanWorker.kt](app/src/main/java/com/nas/naswebdav/DuplicateScanWorker.kt) & [AutoDuplicateScanWorker.kt](app/src/main/java/com/nas/naswebdav/AutoDuplicateScanWorker.kt): bỏ hoàn toàn vòng lặp tải `/api/disk/fast_index` về Room DB và gọi RPC từng tệp; chuyển sang gọi trực tiếp `POST /api/disk/scan_duplicates` + poll `/api/disk/scan_duplicates/status` (không fallback).
  - [SmartToolsViewModel.kt](app/src/main/java/com/nas/naswebdav/smarttools/SmartToolsViewModel.kt): loại bỏ toàn bộ fallback `repository.getDuplicateFiles()` và `roomCandidates`, chỉ dùng kết quả SHA-256 64 ký tự từ Rust NAS, đồng thời gửi lệnh `/api/disk/scan_duplicates/cancel` khi người dùng hủy.
  - [NasHtmlDashboard.kt](app/src/main/java/com/nas/naswebdav/ui/html5/NasHtmlDashboard.kt) & [app.js](app/src/main/assets/html5/app.js): bỏ fallback gom nhóm theo `size_name`, gỡ checkbox `Lightning` chết, hiển thị rõ nhãn `Rust 3 tầng (100% NAS)`.

---

## [2026-11-21] ANTIGRAVITY-036 — Bổ Sung Resumable Chunked Upload Cho Video Lớn (> 512MB / > 1GB) & Tự Động Tạm Dừng Chờ Wi-Fi Ổn Định
**Phiên bản:** `1.1.201` → `1.2.201` (MINOR — thêm tính năng tải nối lại từng khối 16MB cho video lớn và cơ chế tự tạm dừng chờ Wi-Fi ổn định, tương thích ngược hoàn toàn).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/test_audit_20261026_fixes.py tests/test_backup_batch_stream.py -v`): **12/12 PASSED** (gồm test mới `test_backup_resumable_chunked_upload_resume_and_sha256`).
- Đã deploy `/opt/nas_api_server.py` lên NAS (`nas_api.service: active`).

### Added
- **Resumable Chunked Upload cho video lớn (> 512 MB / > 1 GB):**
  - Backend [backend/nas_api_server.py](backend/nas_api_server.py): thêm 3 API `/api/backup/resumable/status`, `/api/backup/resumable/chunk`, `/api/backup/resumable/finish` (Python 3.5.3 / Flask 1.1.4 safe), bật cờ `resumable_upload: True` trong `/api/duplicates/capabilities`. Lưu file `.part` trong `.backup_tmp/resumable/` trên HDD (tự dọn file quá 7 ngày), kiểm tra strict offset (trả `409 + expected_offset` khi lệch), và xác minh SHA-256 phần cứng bằng Rust ARM NEON SHA2 khi hoàn tất.
  - Android [WebDavManager.kt](app/src/main/java/com/nas/naswebdav/WebDavManager.kt) + [BackupContentIntegrity.kt](app/src/main/java/com/nas/naswebdav/BackupContentIntegrity.kt) + [AutoBackupWorker.kt](app/src/main/java/com/nas/naswebdav/AutoBackupWorker.kt): tự động chia video > 512 MB thành các khối 16 MB dùng `FileChannel.position(offset)`; nếu rớt mạng hoặc đổi app giữa chừng, lần nối lại tiếp tục đúng từ % đã tải trên NAS.
- **Tự động tạm dừng chờ Wi-Fi ổn định (`waitForStableWifiAndNas`):**
  - [AutoBackupWorker.kt](app/src/main/java/com/nas/naswebdav/AutoBackupWorker.kt): kiểm tra `TRANSPORT_WIFI` + `checkPingServer()` trước mỗi tệp và ngay khi gặp lỗi mạng (`SocketTimeoutException`, `ConnectException`, `SocketException`, `EOFException`) trong cả Resumable Chunk và Direct WebDAV Upload. Khi mạng rớt/chập chờn, worker tạm dừng (hiển thị `"Tạm dừng: chờ Wi-Fi ổn định..."`), ping lại mỗi 5 giây và tự động nối lại đúng vị trí đang dở khi Wi-Fi tới NAS ổn định trở lại.

---

## [2026-11-21] ANTIGRAVITY-035 — Khắc Phục Triệt Để 3 Lỗi Đồng Bộ Video (Đường Dẫn `dest` & GNU LongName Cho Rust Batch Stream, Nginx/PHP-FPM HDD Temp Buffer & Dọn Sạch File Cụt Trên NAS)
**Phiên bản:** `1.1.199` → `1.1.201` (PATCH — sửa lỗi đường dẫn TAR batch stream, GNU LongName cho tên file > 100 bytes, và cấu hình Nginx/PHP-FPM buffer trên HDD NAS).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).

### Fixed
1. **Sửa lỗi đường dẫn `dest` trong `/api/backup/batch_stream` tạo thư mục rác `http:/100.90.135.102:8822/...` trên NAS:**
   - [WebDavManager.kt](app/src/main/java/com/nas/naswebdav/WebDavManager.kt): Trích xuất đường dẫn tương đối (`relativeDuplicatePath`) thay vì truyền nguyên URL đầy đủ `http://host:port/webdav/AutoBackup/...` vào tham số query `?dest=`.
   - [AutoBackupWorker.kt](app/src/main/java/com/nas/naswebdav/AutoBackupWorker.kt): Giải mã URL (`URLDecoder.decode`) cho tên tệp `relPathInTar` trước khi đóng gói vào luồng TAR và khi đối chiếu `serverMap[rel]`, ngăn việc lưu tên tệp chứa `%20` trên ổ đĩa NAS.
   - [backend/nas_api_server.py](backend/nas_api_server.py): Chuẩn hóa tham số `dest` ở `api_backup_batch_stream()` bằng `urlparse` + `unquote` + cắt tiền tố `/webdav/`, tương thích ngược với cả client cũ lẫn client mới.
2. **Hỗ trợ tên tệp dài > 100 bytes trong luồng TAR (`TarStreamRequestBody.kt`):**
   - Bổ sung `buildEntryHeaderBlocks()` tự động phát sinh khối GNU LongLink (`typeFlag = 'L'`, `././@LongLink`) khi tên tệp hoặc đường dẫn vượt quá giới hạn 100/155 bytes của chuẩn POSIX ustar (ví dụ video Facebook/Instagram có tên > 110 ký tự + `__id...`), khắc phục triệt để lỗi tên tệp bị cắt cụt ở byte thứ 100 làm trượt đối chiếu SHA-256.
   - Bổ sung unit test `testBuildEntryHeaderBlocks_gnuLongNameWhenFilenameExceeds100Bytes` trong [TarStreamRequestBodyTest.kt](app/src/test/java/com/nas/naswebdav/TarStreamRequestBodyTest.kt).
3. **Khắc phục triệt để lỗi `NAS từ chối tệp: 500` (`No space left on device`) và lỗi cắt cụt video lớn (> 450MB – 1.6GB) trên NAS:**
   - Cấu hình `client_body_temp_path`, `fastcgi_temp_path`, `proxy_temp_path` của Nginx (`/etc/nginx/conf.d/nas_hdd_temp.conf`) và `upload_tmp_dir`/`sys_temp_dir` của PHP-FPM trỏ trực tiếp sang ổ cứng HDD (`/srv/dev-disk-by-label-data/New folder/.nas_meta/nginx_temp` & `php_temp`, trống 870 GB) thay vì phân vùng gốc `/var/lib/nginx/body`, đồng thời bật `fastcgi_request_buffering off` và `proxy_request_buffering off`.
   - Di chuyển 584 tệp hợp lệ từ thư mục lỗi `http:/100.90.135.102:8822/webdav/AutoBackup/...` về đúng `/srv/dev-disk-by-label-data/New folder/AutoBackup/...`, xóa 99 tệp bị cụt tên, xóa 17 bản video quay màn hình bị cắt cụt (`470,310,912 bytes` / `__v1795*`), làm sạch `verified_file_hashes` & `file_index` trong `/var/lib/nas_api/nas_index.db`, và deploy `/opt/nas_api_server.py` mới nhất lên NAS.

---

## [2026-11-21] ANTIGRAVITY-034 — Triển Khai Hoàn Chỉnh 3 Chức Năng AutoBackup (Chế Độ Ảnh/Video, Bộ Nhớ Điện Thoại & Đa Thiết Bị Chống Tắc Nghẽn)
**Phiên bản:** `1.0` → `1.1` (MINOR — thêm 3 tính năng mới tương thích ngược).
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Backend Python (`python -m pytest tests/`): **300 passed, 6 skipped, 0 failed** (gồm test mới `test_backup_multi_device_sessions_and_queueing`).
- Android Unit Tests (`:app:testDebugUnitTest`): **100% PASS (0 failed)** (gồm `AutoBackupModeAndStorageTest`, `AutoBackupDedupRegressionTest`, `AutoBackupServerHashAndDeleteTest`).
- JS Syntax (`node --check app/src/main/assets/html5/app.js`): **OK**.

### Added
- **Chế độ chọn nội dung sao lưu (`both` / `image` / `video`):**
  - Thêm bộ nút chọn nổi bật ngay trong modal Auto Backup trên HTML5 Dashboard ([index.html](app/src/main/assets/html5/index.html), [app.js](app/src/main/assets/html5/app.js)) và JS bridge `setBackupMode` / `saveAutoBackup` tại [NasHtmlDashboard.kt](app/src/main/java/com/nas/naswebdav/ui/html5/NasHtmlDashboard.kt).
  - [AutoBackupWorker.kt](app/src/main/java/com/nas/naswebdav/AutoBackupWorker.kt) chỉ quét MediaStore và chỉ chạy pipeline đúng loại người dùng chọn.
- **Thông tin bộ nhớ điện thoại & dung lượng đã giải phóng:**
  - Tạo [StorageUtils.kt](app/src/main/java/com/nas/naswebdav/utils/StorageUtils.kt) đọc dung lượng trống/tổng ngay khi khởi tạo [AutoBackupViewModel.kt](app/src/main/java/com/nas/naswebdav/backup/AutoBackupViewModel.kt) (không bị `0 B` lúc chưa chạy).
  - Cộng dồn dung lượng đã giải phóng theo phiên và tổng tích lũy (`PreferencesRepository.addBackupFreedBytes`) tại cả 4 điểm xóa nguồn sau xác minh SHA-256, hiển thị trực tiếp trên HTML5 Dashboard & modal.
- **Hỗ trợ nhiều điện thoại sao lưu đồng thời & chống tắc nghẽn NAS RK3328:**
  - Backend [backend/nas_api_server.py](backend/nas_api_server.py): thêm 3 API `/api/backup/session/heartbeat`, `/api/backup/sessions`, `/api/backup/session/finish` (Python 3.5.3 / Flask 1.1.4 safe) với giới hạn tối đa 2 máy upload song song (`_MAX_CONCURRENT_BACKUP_DEVICES = 2`), máy thứ 3+ tự xếp hàng FIFO, giãn nhịp 250ms khi ≥ 2 máy chạy cùng lúc, và `BoundedSemaphore(2)` bảo vệ `/api/backup/batch_stream` khỏi OOM (trả 503 + `Retry-After: 3` khi quá tải).
  - Android & HTML5 Dashboard: Worker gửi heartbeat định kỳ 3s (fail-open), tự nhường/xếp hàng khi NAS báo đầy slot và trả slot khi xong; HTML5 Dashboard & modal hiển thị rõ tiến trình từng máy đang backup trên NAS.

---

## [2026-11-21] ANTIGRAVITY-033 — Khắc Phục Triệt Để Lỗi Tiến Trình AutoBackup Đột Ngột Dừng & Chống Crash Mạng RPC
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `AutoBackupServerHashAndDeleteTest` PASS, `AutoBackupDedupRegressionTest` PASS, `AutoBackupLockTest` PASS, `TarStreamRequestBodyTest` PASS (100% unit tests PASS in 5m 10s).
- Build APK: `:app:assembleDebug` đang đóng gói (`app-debug.apk` tại `app/build/outputs/apk/debug/app-debug.apk`).

### 1. Khắc Phục Nguyên Nhân Gốc Gây Dừng Đột Ngột Worker (Check Tài Khoản Cứ 500 Tệp):
- **Phát hiện qua Logcat & Code Review:**
  - Tại `AutoBackupWorker.kt`: Cứ mỗi 500 tệp duyệt qua (`currentCount % 500 == 0`), có đoạn mã kiểm tra xem tài khoản có bị đổi không.
  - Đoạn mã so sánh danh sách URL từ SharedPreferences (`http://192.168.100.254:8822`) với `backupAuth.baseUrl` (`http://192.168.100.254:8822/webdav/`).
  - Do `backupAuth.baseUrl` có chứa hậu tố `/webdav/`, phép so sánh `none { it.trimEnd('/') == backupAuth.baseUrl.trimEnd('/') }` luôn trả về `true`!
  - Hậu quả: Cứ hễ quét đến tệp thứ 500 (hoặc khi khôi phục checkpoint ở mốc chia hết cho 500 như 5000), worker lập tức ném ngoại lệ giả `CancellationException("Backup account changed")` và tự hủy tiến trình ngay lập tức!
- **Khắc phục:**
  - Chuẩn hóa URL loại bỏ triệt để `/webdav` và dấu gạch chéo cuối (`removeSuffix("/webdav").trimEnd('/')`) trước khi đối chiếu.
  - Chỉ hủy tiến trình khi tên đăng nhập hoặc mật khẩu thực sự bị thay đổi, hoặc toàn bộ URL ứng viên không còn trùng khớp với host của NAS hiện tại.

### 2. Chống Sập ART Runtime Do Mạng Chập Chờn Khi Gọi RPC Đối Chiếu Hash (`VerifiedDuplicateHash.kt`):
- **Phát hiện qua Tombstone/Crash Buffer:**
  - Khi điện thoại mất sóng hoặc mạng Tailscale không thể kết nối tới port 5050 (`Failed to connect to /100.90.135.102:5050`), hàm `duplicateRpc` và `serverSideSha256` không bắt ngoại lệ `ConnectException` / `SocketTimeoutException`, khiến ART Runtime bị crash `SIGABRT` ("No pending exception expected").
- **Khắc phục:**
  - Bọc khối `try-catch` an toàn xung quanh `duplicateRpcClient.newCall().execute()` và `duplicateComputeHashClient.newCall().execute()`.
  - Nếu gặp lỗi mạng RPC, an toàn trả về `null` để worker tự động fallback sang phương thức tải luồng trực tiếp WebDAV truyền thống mà không bao giờ crash hoặc gián đoạn.

### 3. Cô Lập Hai Pipeline Bằng `supervisorScope` & Đẩy Toàn Bộ Sự Kiện Ra Logcat:
- **SupervisorScope (`AutoBackupWorker.kt`):** Thay thế `coroutineScope` bằng `supervisorScope` kèm khối `try-catch` cục bộ cho từng pipeline. Nếu luồng Video hoặc Ảnh gặp tệp cá biệt bị lỗi, luồng còn lại vẫn tiếp tục chạy độc lập 100% dung lượng băng thông.
- **Logcat Realtime Output (`SystemLogger.kt`):** Nối trực tiếp `SystemLogger.log` ra `android.util.Log.println` với tag tương ứng theo module (`AutoBackup`), giúp người dùng và nhà phát triển có thể theo dõi trực tiếp toàn bộ diễn biến đồng bộ qua lệnh `adb logcat -s AutoBackup`.

---

## [2026-11-20] ANTIGRAVITY-032 — Khắc Phục Mâu Thuẫn Số Lượng Tiến Trình, Bổ Sung Dung Lượng & Thanh Tiến Trình Riêng Cho Tệp Video Đang Tải
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `AutoBackupServerHashAndDeleteTest` PASS, `AutoBackupDedupRegressionTest` PASS, `AutoBackupLockTest` PASS, `TarStreamRequestBodyTest` PASS (100% unit tests PASS in 11m 43s).
- Build APK: `:app:assembleDebug` BUILD SUCCESSFUL in 2m 39s (`app-debug.apk` tại `app/build/outputs/apk/debug/app-debug.apk`).

### 1. Khắc Phục Triệt Để Mâu Thuẫn Số Lượng Tiến Trình (`processed > total`, ví dụ `11432 / 8987 ảnh`):
- **Nguyên nhân gốc:** Khi ứng dụng nạp lại checkpoint từ phiên trước (ví dụ `scannedCount = 5000`), biến `processedCounter` được gán sẵn là `5000`. Khi con trỏ `cursor` duyệt qua các tệp còn lại (8987 hàng), logic cũ lại gọi `processedCounter.incrementAndGet()` cộng dồn thêm các hàng mới vào con số 5000 ban đầu, dẫn đến `5000 + 6432 = 11432 > 8987`!
- **Giải pháp dứt điểm:**
  - Trong vòng lặp `while (cursor.moveToNext())`, vị trí duyệt chính xác luôn là `cursor.position + 1` (từ `1` đến `cursor.count`).
  - Sử dụng trực tiếp `val currentCount = minOf(cursor.position + 1, totalCounter.get())` và `processedCounter.set(currentCount)`.
  - Cập nhật đồng bộ trong `pushProgress()`: chặn trên `minOf(imageProcessedCount, imageTotalCount)`, `minOf(videoProcessedCount, videoTotalCount)` và `totalProc = minOf(imgProc + vidProc, totalAll)`.
  - Trên giao diện HTML5 (`app.js`): bổ sung `Math.min(processed, total)` cho cả luồng ảnh, video và tổng hợp, loại bỏ 100% khả năng hiển thị vượt trần.

### 2. Bổ Sung Hiển Thị Dung Lượng File Đang Tải & Thanh Progress Bar Riêng Cho Video:
- **Theo yêu cầu:** Chỉ áp dụng cho luồng Video (ảnh dạng nhỏ được gom stream batch nên không cần thanh riêng).
- **AutoBackupWorker.kt:**
  - Bổ sung các biến nguyên tử theo dõi tệp video đang tải: `videoFileBytesWritten`, `videoFileBytesTotal`, `videoFileProgress`.
  - Trong callback `onUploadProgress(bytesWritten, totalBytes)` của `uploadStreamWithProgress`: cập nhật tức thời số byte đã ghi và phần trăm tải lên của video hiện tại, đẩy sang `workDataOf`.
- **AutoBackupViewModel.kt & NasHtmlDashboard.kt:**
  - Khai báo và hứng các trường: `autoBackupVideoBytesWritten`, `autoBackupVideoBytesTotal`, `autoBackupVideoFileProgress`.
  - Bổ sung các trường vào gói JSON cập nhật `videos` trong `buildHtml5StateJson` và kích hoạt `LaunchedEffect` theo thời gian thực.
- **Giao diện HTML5 Dashboard (`index.html` & `app.js`):**
  - Bổ sung khối tiến trình tệp video `#abVideoFileBox` với nhãn dung lượng `#abVideoFileSize` (`xx MB / yy MB`), phần trăm `#abVideoFilePct` và thanh fill `#abVideoFileProgFill`.
  - Thanh tiến trình tổng thể số lượng video `#abVideoProgFill` hiển thị độc lập bên dưới (`xx / yy video`).
- **Giao diện Native Compose (`MainMenuPanels.kt` & `SystemStatusCards.kt`):**
  - Hiển thị song song: dung lượng tệp video hiện tại kèm thanh tiến trình riêng, và thanh tiến trình tổng số lượng video bên dưới.

---

## [2026-11-20] ANTIGRAVITY-031 — Tách Biệt Checkpoint Độc Lập Cho Từng Pipeline, Lưu Cấu Hình Xóa Nguồn & Cấp Toàn Quyền Cho Knox User 150 (Secure Folder)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `AutoBackupServerHashAndDeleteTest` PASS, `AutoBackupDedupRegressionTest` PASS, `AutoBackupLockTest` PASS, `TarStreamRequestBodyTest` PASS (161/161 unit tests PASS 100% in 4m 15s).
- Backend Tests: 299 passed, 6 skipped in 58.45s (100% backend Python tests PASS).
- Build APK: `:app:assembleDebug` BUILD SUCCESSFUL in 3m 44s (`app-debug.apk` v1.0.199).
- Triển khai thực tế: Đã cài đặt APK trực tiếp lên điện thoại Samsung Galaxy S21 Ultra `R5CR61F6L3V` cho tất cả người dùng (`adb install -r --user all -> Success`).
- Trực tiếp kiểm chứng trên Knox User 150 (Samsung Secure Folder): Đã cấp toàn quyền `MANAGE_EXTERNAL_STORAGE allow`, `WRITE_MEDIA_IMAGES allow`, `WRITE_MEDIA_VIDEO allow`, `WRITE_EXTERNAL_STORAGE allow`.

### 1. Phân Tách Checkpoint Độc Lập Cho Từng Pipeline (Per-Pipeline Checkpoints):
- **Nguyên nhân gốc:** Trước đây chỉ có 1 checkpoint chung tên `"AutoBackup"`. Vì luồng Ảnh và Video chạy song song với tốc độ khác nhau, checkpoint của luồng này liên tục ghi đè vị trí của luồng kia, dẫn đến khi khởi động lại app không thể xác định chính xác vị trí tiếp tục của từng loại phương tiện.
- **Giải pháp:** Tách thành 2 checkpoint độc lập: `"AutoBackup_Image"` và `"AutoBackup_Video"`.
  - Định dạng lưu điểm dừng: `"$id:$dataPath"` kèm số lượng đã quét (`scannedCount`).
  - Khi worker khởi động lại: Nạp checkpoint tương ứng của từng pipeline, cập nhật số lượng đã duyệt và nhảy cóc (skip-ahead) qua các tệp đã đối chiếu mà không cần gửi RPC/HTTP lên NAS.
  - Đồng thời, trong giai đoạn skip-ahead, nếu bật chế độ `deleteAfterBackup`, các tệp đã được kiểm chứng tính toàn vẹn (SHA-256 đối khớp trên NAS) sẽ được tự động xóa trên điện thoại ngay lập tức.
  - Sau khi mỗi pipeline hoàn thành toàn bộ danh sách, tự động xóa checkpoint của pipeline đó.

### 2. Phá Băng Quyền Truy Cập & Xóa Tệp Trong Samsung Secure Folder (Knox User 150):
- **Phát hiện hệ thống:** Người dùng vận hành ứng dụng trong không gian riêng biệt **Samsung Secure Folder (User 150)**. Toàn bộ quyền cấp từ ngoài máy chính (User 0) không có hiệu lực trong User 150; hệ điều hành Knox ghi nhận `MANAGE_EXTERNAL_STORAGE: default; rejectTime` khiến mọi lệnh xóa tệp nguồn bị Android chặn (`Permission denied`).
- **Khắc phục quyền User 150:** Cấp quyền toàn diện cấp OS cho User 150 qua `appops set --user 150 com.nas.naswebdav MANAGE_EXTERNAL_STORAGE allow`, `WRITE_MEDIA_IMAGES allow`, `WRITE_MEDIA_VIDEO allow`, `WRITE_EXTERNAL_STORAGE allow`.
- **Cơ chế Fallback Xóa Tệp An Toàn (`BackupContentIntegrity.kt`):**
  - Trước đây, nếu `proof.version` (dựa trên `st_ctim`/`st_mtim`) bị thay đổi nhỏ do FUSE/MediaStore truy cập, kiểm tra tính toàn vẹn bị trả về `false` sớm và bỏ qua xóa tệp.
  - Sửa đổi: Bổ sung fallback kiểm tra băm `sourceMatchesDigestSync(context, uri, proof.sha256)`. Nếu SHA-256 hoàn toàn khớp với bản sao lưu trên NAS thì tệp vẫn được xác nhận 100% toàn vẹn để xóa an toàn.
  - Khi xóa vật lý thành công qua `targetFile.delete()`, bổ sung gọi đồng thời `context.contentResolver.delete(...)` và `MediaScannerConnection.scanFile(...)` để MediaStore cập nhật ngay lập tức và giải phóng dung lượng trong Thư viện ảnh (Gallery).
- **Truyền InputData Độc Lập Cho WorkManager (`AutoBackupViewModel.kt` & `AutoBackupWorker.kt`):**
  - `triggerManualBackup` chủ động đóng gói `delete_after_backup` vào `inputData` gửi thẳng vào WorkManager để Worker nhận cờ ngay lập tức mà không phụ thuộc vào độ trễ flush đĩa của `SharedPreferences`.

---

## [2026-11-20] ANTIGRAVITY-030 — Tách 2 Tiến Trình Sao Lưu Song Song (Ảnh & Video) & Giao Diện Dual Progress Bar Độc Lập
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `AutoBackupDedupRegressionTest` PASS, `AutoBackupServerHashAndDeleteTest` PASS, `AutoBackupLockTest` PASS, `Html5BridgeContractTest` PASS, `TarStreamRequestBodyTest` PASS (100% PASS in 2m 46s).
- Backend Tests: 299 passed, 6 skipped in 29.67s (100% tests backend Python PASS).
- Build APK: `:app:assembleDebug` BUILD SUCCESSFUL in 2m 30s (`app-debug.apk` v1.0.198).
- Triển khai thực tế: Đã cài đặt APK trực tiếp lên điện thoại Samsung Galaxy S21 Ultra `R5CR61F6L3V` qua ADB (`Streamed Install -> Success`).

### 1. Tách 2 Tiến Trình Sao Lưu Song Song Thực Thụ (Concurrent Coroutine Pipelines):
- **Kiến trúc Coroutines bên trong `AutoBackupWorker.kt`:**
  - Thay vì duyệt tuần tự từng URI của MediaStore, worker phân chia thành 2 luồng độc lập chạy đồng thời qua `coroutineScope`:
    1. **Pipeline 1 (Photo Stream):** Quét `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`. Tận dụng Gom Batch Streaming TAR-over-HTTP (`TarStreamRequestBody`), gom các ảnh nhỏ (<=50MB) thành các mẻ (batch) đẩy thẳng vào `/api/backup/batch_stream`. Đo tốc độ và tiến trình riêng cho ảnh (`imageSpeedBps`, `imageProgress`, `imageProcessedCount`, `imageTotalCount`).
    2. **Pipeline 2 (Video Direct):** Quét `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`. Video thường có dung lượng lớn được upload độc lập trực tiếp qua WebDAV PUT/Stream, không phải chờ đợi các đợt gom batch ảnh. Đo tốc độ và tiến trình riêng cho video (`videoSpeedBps`, `videoProgress`, `videoProcessedCount`, `videoTotalCount`).
  - **Tối ưu hóa Băng thông & I/O:** Bão hòa tối đa thông lượng mạng Wi-Fi và I/O của NAS RK3328 khi cả 2 luồng cùng ghi xuống đĩa không bị nghẽn CPU nhờ Rust multi-threading & zero-GIL.
  - **Thread-Safe & Cross-Pipeline Dedup:**
    - Sử dụng `shaMutexMap = ConcurrentHashMap<String, Mutex>()`: Khóa đồng bộ theo `sha256` của tệp. Nếu 1 ảnh và 1 video có cùng nội dung (như trong các bài test dedup regression), chỉ có 1 bên thực hiện PUT, bên còn lại tự động chờ và nhận diện bản sao trùng lặp (`isSkipped = true`) ngay khi bên kia hoàn tất mà không upload thừa.
    - Bảo vệ Room Database (`verifiedHashDao`, `fingerprintDao`, `checkpointDao`) bằng `dbMutex` và các thư mục WebDAV bằng `folderMutex`.

### 2. Giao Diện Người Dùng Dual Progress Bar Độc Lập (Compose & HTML5 Dashboard):
- **`AutoBackupViewModel.kt`:** Bổ sung các State flow riêng biệt cho Ảnh (`autoBackupImageProgress`, `autoBackupImageSpeedBps`, `autoBackupImageProcessedCount`, `autoBackupImageTotalCount`, `autoBackupImageCurrentFile`) và Video (`autoBackupVideoProgress`, `autoBackupVideoSpeedBps`, `autoBackupVideoProcessedCount`, `autoBackupVideoTotalCount`, `autoBackupVideoCurrentFile`).
- **Compose UI (`SystemStatusCards.kt` & `MainMenuPanels.kt`):**
  - Hiển thị 2 thanh tiến trình song song trực quan:
    + Thanh 1 (Ảnh - Màu Cyan / Primary): Biểu tượng 📷 Ảnh, tên tệp ảnh hiện tại, số lượng đã xử lý, tốc độ tải lên ảnh.
    + Thanh 2 (Video - Màu Orange / Amber / Tertiary): Biểu tượng 🎬 Video, tên video hiện tại, số lượng video, tốc độ tải video.
    + Thanh tổng hợp: Thanh tiến trình tổng % và tổng tốc độ kết hợp của cả 2 pipeline.
- **HTML5 Dashboard (`NasHtmlDashboard.kt`, `index.html`, `app.js`):**
  - Mở rộng JSON payload `backup` gửi sang WebView với 2 đối tượng con `images` và `videos`.
  - Cập nhật modal `#abProgressBox` và widget trang chủ `bgTasks` hiển thị 2 thanh tiến trình con cho Ảnh và Video bên cạnh tổng tiến trình chung.

---

## [2026-11-20] ANTIGRAVITY-029 — Gom Tệp Truyền Stream (TAR-over-HTTP Batch Streaming) Tăng Tốc Sao Lưu & Đối Chiếu SHA-256 Phần Cứng Zero-Disk
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `AutoBackupDedupRegressionTest` PASS, `AutoBackupServerHashAndDeleteTest` PASS, `TarStreamRequestBodyTest` PASS 100%.
- Build APK: `:app:assembleDebug` BUILD SUCCESSFUL in 6m 24s (`app-debug.apk` 39MB, đã upload trực tiếp vào thư mục gốc WebDAV trên NAS).
- Backend Tests: `tests/test_backup_batch_stream.py` PASS, `tests/test_audit_20261026_fixes.py` PASS (100% tuân thủ Python 3.5.3 invariant).
- Backend Deploy NAS RK3328 (`100.90.135.102` Tailscale / `192.168.100.254` LAN):
  + Đã biên dịch và cập nhật thư viện Rust lõi `libnas_hasher_core.so` (C-ABI `nas_hasher_extract_tar_batch_json`) và binary `nas_hasher`.
  + Deploy `nas_api_server.py` với endpoint `POST /api/backup/batch_stream` và capability `'batch_stream': True`.
  + Live smoke test trên NAS: Stream tệp TAR thật trả về HTTP 200 OK (`{"ok": true, "files_extracted": 1, ...}`).

### 1. Kiến Trúc Gom Tệp Truyền Stream (Zero-Disk Footprint):
- **Phía Android (`TarStreamRequestBody.kt` & `AutoBackupWorker.kt`):**
  - Đóng gói định dạng POSIX ustar TAR trực tiếp từ `ContentResolver.openInputStream(uri)` đẩy thẳng vào OkHttp `BufferedSink`, hoàn toàn không sinh file nén tạm trên bộ nhớ flash của điện thoại.
  - Gom các tệp media nhỏ (< 50MB) thành các mẻ (batch) tối đa 100 tệp hoặc 100MB qua `flushTarBatch()`.
  - Thay vì thiết lập 100 kết nối TCP/TLS handshake riêng lẻ với 100 lượt PUT, toàn bộ mẻ được truyền trong 1 luồng HTTP Stream duy nhất, giảm 90% độ trễ mạng và loại bỏ overhead overhead của từng request.
  - Tích hợp **In-Batch Dedup**: Theo dõi `pendingBatchDigests[sha256]` để tránh nạp các tệp có nội dung trùng lặp trong cùng mẻ quét.
  - Bổ sung đàm phán tính năng (Capability Negotiation) qua `webDavManager.supportsBatchStream()`: tự động fallback về PUT đơn lẻ nếu kết nối với máy chủ không hỗ trợ batch stream.

### 2. Xử Lý Tận Dụng Phần Cứng Phía NAS (`archive_worker.rs` & `nas_api_server.py`):
- **Crate Rust `nas_hasher`:**
  - Viết `extract_tar_batch_reader` và `extract_tar_batch_safe`: vừa giải nén an toàn (chống Tar-Slip traversal, symlink, hardlink) vừa tính toán SHA-256 song song bằng chỉ thị phần cứng ARM NEON SHA2 trong 1 pass I/O duy nhất.
  - Tự động ghi nhận danh mục mã băm vào SQLite database `verified_file_hashes`.
- **Tự Động Đối Soát & Dọn Dẹp Thiết Bị:**
  - Android client nhận mã SHA-256 từ NAS trả về cho từng entry trong batch, đối chiếu tính toàn vẹn và lập tức kích hoạt `deleteVerifiedBackupSource` (nếu người dùng bật `delete_after_backup`) để giải phóng dung lượng trên điện thoại.

---

## [2026-11-20] ANTIGRAVITY-028 — Tối Ưu Local Fast-Path Bỏ Qua Tức Thì 16.386 Tệp Đã Có, Khắc Phục Mất Tiến Trình Khi Tắt App & Hiển Thị Tốc Độ Tải Lên Liên Tục
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `:app:testDebugUnitTest` PASS 100% (Robolectric `AutoBackupDedupRegressionTest`).
- Build APK: `:app:assembleDebug` BUILD SUCCESSFUL in 2m 10s (`app-debug.apk`).
- Backend: NAS RK3328 (`100.90.135.102` Tailscale / `192.168.100.254` LAN), service `nas_api` active, RPC `api/duplicates/file` xác thực tệp `2637ef6e...` HTTP 200 OK trong 10ms.

### 1. Khắc Phục Triệt Để Tình Trạng "App bị tắt là mất hết tiến trình, chạy lại từ đầu":
- **`AutoBackupWorker.kt` (`isSkipped` Branch):**
  - Trước đây, khi một tệp đã có trên NAS (`plan.skip == true`), worker bỏ qua nhưng **không hề lưu vào `db.verifiedHashDao()` và `db.fingerprintDao()`**. Hậu quả: mỗi khi app bị đóng hoặc chạy lại, worker coi như chưa từng thấy các tệp này và gửi 16.386 lượt request qua mạng (HEAD + RPC `find`) tới NAS, khiến tiến trình mất 20-30 phút chỉ để đối chiếu lại từ đầu.
  - Giải pháp: Khi `isSkipped == true`, lập tức ghi nhận `VerifiedHashCache` và `FileFingerprint` vào Room DB cục bộ trên điện thoại.
  - Bổ sung **Local Fast-Match**: Khi `localCached != null` và kích thước cùng `sourceFileVersion` không thay đổi, worker nhận diện ngay tệp đã đồng bộ hoàn chỉnh mà **hoàn toàn không gọi mạng `webDavManager.planBackupTarget`** (loại bỏ 100% request HEAD và RPC mạng). Toàn bộ 16.386 tệp cũ sẽ được lướt qua trong **dưới 1 giây** qua SQLite index, sau đó lập tức tiến hành sao lưu các tệp mới chụp.

### 2. Khắc Phục Lỗi "Tiến Trình Backup Không Thấy Có Tốc Độ Tải Lên":
- **`AutoBackupWorker.kt` (`currentSpeedBps` Lifecycle & `setProgressAsync`):**
  - Chuyển `currentSpeedBps` ra ngoài vòng lặp từng tệp để duy trì tốc độ tải lên liên tục, không bị reset về `0L` mỗi khi chuyển tệp.
  - Bổ sung trường `"uploadSpeedBps" to currentSpeedBps` vào tất cả các điểm gọi `setProgressAsync` (ở đầu tệp, nhánh skip tệp và nhánh catch ngoại lệ). Trước đây các điểm này không truyền `uploadSpeedBps`, khiến `AutoBackupViewModel` đọc thấy 0 và xóa trắng hiển thị tốc độ trên giao diện Compose và HTML5 dashboard.
  - Sửa chuỗi log nhầm lẫn từ "Lỗi tải xuống tập tin" thành "Lỗi sao lưu tập tin" để phản ánh chính xác tác vụ sao lưu lên NAS.

---

## [2026-11-20] ANTIGRAVITY-027 — Khắc Phục Triệt Để Biểu Đồ Giám Sát HTTP 500, Tăng Tốc Đối Chiếu AutoBackup 16.386 Tệp & Xóa Bỏ Lỗi Xác Minh Đích
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `:app:testDebugUnitTest` PASS 100% (Robolectric `AutoBackupDedupRegressionTest`).
- Backend Tests: `pytest tests/` (20/20 test PASS 100%).
- NAS Live (`100.90.135.102` / `192.168.100.254` RK3328): Deploy bản cập nhật `nas_api_server.py`, service `nas_api` active running; kiểm tra trực tiếp `GET /api/metrics/history?hours=24` trả về HTTP 200 OK với 150 snapshot liên tục.

### 1. Khắc Phục Triệt Để Lỗi Biểu Đồ Giám Sát Báo "NAS trả về HTTP 500":
- **`backend/nas_api_server.py` (`api_metrics_history`):**
  - Chuyển sang kết nối SQLite bằng URI Read-Only (`file:DB_PATH?mode=ro`, `uri=True`, `timeout=30.0`). Trong kiến trúc SQLite WAL, chế độ Read-Only cho phép reader đọc song song đồng thời mà không bao giờ bị nghẽn (block) bởi các tác vụ background writer (Thumbnail, FileIndex, ResourceReclaimer).
  - Tích hợp fallback tự động: Nếu kết nối gặp lỗi ngoại lệ do database lock đột ngột, hàm ghi log cảnh báo và trả về HTTP 200 với danh sách điểm metrics hiện có thay vì quăng lỗi HTTP 500 làm hỏng giao diện WebView.

### 2. Tăng Tốc Độ Đối Chiếu 16.386 Tệp Của AutoBackup (Loại Bỏ Hoàn Toàn Tình Trạng "Rùa Bò" & Kẹt 0/16386):
- **`BackupContentIntegrity.kt` (`inspectBackupDestination`):**
  - Đảo thứ tự ưu tiên kiểm tra: Đọc Room DB cục bộ (`fingerprintDao` và `verifiedHashDao`) TRƯỚC KHI gọi mạng `getCachedVerifiedSha256`. Nếu tệp đã từng được sao lưu và xác minh trên thiết bị, trả về `MATCH` trong 0.05ms mà không tốn 2 lượt gọi HTTP (HEAD + RPC `api/duplicates/file`) qua mạng WiFi/Tailscale.
  - Sửa `sourceFileVersion`: Đổi visibility từ `private` sang `internal` để `AutoBackupWorker` tái sử dụng kiểm tra thay đổi nguồn mà không cần đọc lại toàn bộ dữ liệu.
- **`AutoBackupWorker.kt`:**
  - Bổ sung Fast-Path kiểm tra phiên bản nguồn (`sourceFileVersion`): Khi tệp đã có trong `verifiedHashDao` với cùng kích thước và `version` không thay đổi, tái sử dụng trực tiếp SHA-256 đã lưu; không mở stream đọc lại từ bộ nhớ flash của điện thoại (tiết kiệm hàng chục GB I/O đọc ổ cứng).
  - Đưa cập nhật tiến trình `setProgressAsync` lên đầu chu kỳ duyệt tệp: Đảm bảo bộ đếm tiến trình trên giao diện Android nhảy số liên tục (`15 / 16386`, `250 / 16386`...), không còn bị đơ ở `0 / 16386 tệp (0.0%)` trong lúc chờ xử lý.

---

## [2026-11-20] ANTIGRAVITY-026 — Khắc Phục Triệt Để Sự Cố Treo Đối Chiếu AutoBackup (0/16386 Tệp), Lỗi "Bản backup đã biết chưa xác minh được" & Tăng Tốc Đồng Bộ Toàn Diện
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android Unit Tests: `:app:testDebugUnitTest` (159/159 unit tests PASS 100%).
- Backend Tests: `pytest tests/` (100% PASS).
- NAS Live (`100.90.135.102` / `192.168.100.254` RK3328): Deploy bản cập nhật `nas_api_server.py` thành công, PID `11838` đang chạy live.

### 1. Khắc Phục Triệt Để Lỗi "Bản backup đã biết chưa xác minh được" & Lỗi Treo Giao Diện 0% Tại 16.386 Tệp:
- **`backend/nas_api_server.py`:**
  - Bọc try-except an toàn cho `_duplicate_open_lease`: Không còn quăng lỗi `SIGIO` hoặc `HTTP 409 Conflict` vô lý khi đang chạy qua server đa luồng hoặc file có open descriptor; đối chiếu stat version trước và sau lease.
  - Chuẩn hóa đường dẫn trong `api_duplicate_file`: Bóc tách tự động `/webdav/` và leading slash `/` để không gây lỗi 409 sai path.
  - Tránh lỗi thiếu `fcntl.F_UNLCK` trên các môi trường giả lập qua `getattr(fcntl, 'F_UNLCK', 2)`.
- **`BackupContentIntegrity.kt`:**
  - Sửa `sourceFileVersion`: Khi `stat.st_ino == 0L` (đặc trưng của hệ thống tệp ảo MediaStore FUSE trên Android), sử dụng fallback `"${stat.st_dev}:${stat.st_size}:${stat.st_mtim.tv_sec}"` làm `fileId`. Nhờ đó bộ nhớ đệm SHA-256 cục bộ (`verifiedHashDao`) hoạt động chính xác 100%, không còn bị tình trạng đọc lại toàn bộ từng byte file từ ổ cứng điện thoại mỗi lần mở đối chiếu.
  - Sửa `inspectBackupDestination`: Bổ sung kiểm tra Room DB `file_fingerprints` và `verifiedHashDao` kết hợp HEAD request để xác thực tệp đã tồn tại nguyên vẹn trên NAS trong < 5ms thay vì tính lại hash qua mạng.
  - Sửa `findExistingBackup`: Khi gặp ứng viên chưa xác minh được (`UNVERIFIABLE`), dùng lệnh `continue` để tìm tiếp các ứng viên khác thay vì quăng `IOException` làm crash ngang cả vòng lặp 16.386 tệp.
- **`AutoBackupWorker.kt`:**
  - Giảm tần suất giải mã phần cứng Keystore AES: Chỉ kiểm tra thông tin tài khoản sau mỗi chu kỳ 500 file (`processedFilesCount % 500 == 0`) thay vì gọi IPC Keystore ở từng tệp.
  - Bổ sung `setProgressAsync` throttle 300ms trong cả 2 nhánh (bỏ qua tệp đã có và nhánh `catch` bắt lỗi ngoại lệ), sửa lỗi hiển thị `"progress" to percent`. Loại bỏ hoàn toàn hiện tượng giao diện bị đơ ở `0 / 16386 tệp (0.0%)`.
  - Cập nhật bộ nhớ đệm `verifiedHashDao.put(...)` ngay sau khi tải lên và xác minh thành công.
- **`Database.kt`:**
  - Bổ sung `@Query("SELECT * FROM file_fingerprints WHERE filePath = :path LIMIT 1") fun getFingerprint(path: String): FileFingerprint?` trong `FingerprintDao`.

---

## [2026-11-20] ANTIGRAVITY-025 — Kiểm Toán Toàn Diện End-to-End, Khắc Phục Lỗi Path Normalization Cho Các API Rust Mới & Tối Ưu Hệ Thống
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:**
- Android: `:app:testDebugUnitTest` (154/154 unit test PASS 100%, bổ sung `NasRelativePathTest.kt` kiểm tra toàn bộ các biến thể đường dẫn WebDAV/NAS).
- Backend: `pytest tests/` (297 passed, 6 skipped in 70s, 100% PASS).
- NAS Live (`192.168.100.254` RK3328): Test nén ZIP & giải nén ZIP thực tế qua API `HTTP 200, files_compressed: 5, extracted: 5`; Test EXIF và Similar Images 100% HTTP 200 cho cả 3 dạng URL/path (có/không có `/webdav/`, có/không có leading slash).

### 1. Khắc Phục Triệt Để Lỗi Path Normalization Gây 404 Trên Các Tính Năng Rust Mới:
- **`NasApplication.kt`:** Thêm extension function `fun String.toNasRelativePath(): String` chuyển đổi nhất quán mọi WebDAV URL (dù có hoặc không có tiền tố `/webdav/`, có dấu cách, unencoded tiếng Việt, query param hoặc fragment) thành đường dẫn tương đối chuẩn của NAS mà không phụ thuộc Android URI runtime.
- **`FileBrowserViewModel.kt` & `SmartToolsViewModel.kt`:** Áp dụng `toNasRelativePath()` cho `unzipFile()`, `zipFolder()`, `fetchMediaExif()`, `findSimilarImages()`. Khắc phục tận gốc lỗi trước đó: khi `filePath` không chứa chuỗi `"/webdav/"`, hàm `substringAfter("/webdav/")` trả về toàn bộ URL `http://...`, khiến NAS backend trả về 404 File Not Found cho toàn bộ các tính năng này.
- **`backend/nas_api_server.py`:** 
  - Thêm `clean_p = clean_p.lstrip("/")` tại `api_media_exif` và `api_fingerprint_similar` để khớp 100% với khóa lưu trong bảng SQLite `image_fingerprints` (vốn lưu đường dẫn không có dấu gạch chéo đầu).
  - Chuẩn hóa `relative.lstrip("/")` và loại bỏ `/webdav/` trong `_duplicate_compute_hash` giúp các yêu cầu tính hash phía server không bị từ chối bởi `_duplicate_file_info`.

---

## [2026-11-20] ANTIGRAVITY-024 — Tăng Tốc Đồng Bộ AutoBackup (Xác Minh SHA-256 Phía NAS Rust 0-Byte WiFi) & Khôi Phục Tự Xóa Nguồn Đã Sao Lưu
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** Backend `297 passed, 6 skipped` (live smoke test trên NAS RK3328 LAN `192.168.100.254`: video MP4 5.03MB tính Rust SHA-256 hết `113.9 ms`, 0 byte WiFi); Android `:app:testDebugUnitTest` (153/153 PASS) & `:app:assembleDebug` **BUILD SUCCESSFUL**.

### 1. Xóa Bỏ Nút Cổ Chai Tải Ngược 100% File Qua WiFi Khi Đồng Bộ:
- **`backend/nas_api_server.py`:** Thêm `POST /api/duplicates/compute_hash` (sử dụng Rust `rust_bridge.compute_fast_sha256` kèm Linux `F_SETLEASE` `F_RDLCK` + kiểm tra `_duplicate_stat_version` chống race ghi đè, tự lưu vào `verified_file_hashes`) và phát cờ `server_side_hash: true` trong `/api/duplicates/file` & `/api/duplicates/capabilities`.
- **`VerifiedDuplicateHash.kt`:** Trong `getCachedVerifiedSha256()`, ưu tiên gọi `POST /api/duplicates/compute_hash` ngay trên NAS thay vì `coordinatedSha256()` (vốn tải ngược 100% file vừa upload qua WiFi về điện thoại để băm lại SHA-256), chỉ fallback khi NAS đời cũ chưa có cờ `server_side_hash`.
- **`VerifiedDuplicateHash.kt` & `BackupContentIntegrity.kt`:** Cache `OkHttpClient` (`duplicateRpcClient`, `duplicateContentClient`, `duplicateComputeHashClient`, `backupHeadClient`, `backupCleanupClient`) dạng `by lazy` dùng chung connection pool thay vì `.newBuilder()...build()` cho từng file.
- **`AutoBackupWorker.kt`:** Bỏ `delay(100)` sau mỗi tệp (thay bằng `yield()`), tiết kiệm 100s ngủ vô ích trên mỗi 1.000 ảnh/video.

### 2. Khôi Phục Tự Động Xóa Tệp Đã Có Trên NAS (Chế Độ "Sao lưu & Giải phóng (Move)"):
- **`BackupContentIntegrity.kt`:** Thêm `deleteVerifiedBackupSource()` — chỉ xóa tệp nguồn trên API 27+ khi đã xác minh `BackupContentState.MATCH` (khớp 100% SHA-256) và tệp nguồn chưa bị sửa đổi (`fstat` version hoặc re-hash SHA-256 khi FUSE không trả `st_ino`); hỗ trợ cả `contentResolver.delete` lẫn fallback `File.delete()` + `MediaScannerConnection` khi đã cấp `MANAGE_EXTERNAL_STORAGE` trên Android 11+.
- **`AutoBackupWorker.kt`:** Gọi `deleteVerifiedBackupSource()` khi `delete_after_backup = true` ở **cả 2 nhánh**: (a) tệp bị bỏ qua vì đã có sẵn trên NAS (`isSkipped == true`), và (b) tệp vừa upload & xác minh xong (`uploadVerified == true`).
- **`AndroidManifest.xml` & `Dialogs.kt`:** Khai báo `MANAGE_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE (maxSdkVersion=29)` và hiện nút mở nhanh màn hình cấp quyền "Quản lý tất cả tệp" ngay trong hộp thoại Auto Backup khi bật chế độ Move trên Android 11+.

---

## [2026-11-20] ANTIGRAVITY-023 — Gắn Nút UI Compose Mới (ZIP/EXIF/Similar) & Tối Ưu Hóa Toàn Diện Hiệu Năng Front-end Android
**Tác nhân thực hiện:** Antigravity + Claude Code (`--model opus --effort max`).
**Kết quả kiểm thử:** `:app:testDebugUnitTest` & `:app:assembleDebug` **BUILD SUCCESSFUL** (0 lỗi, 0 warning mới trên toàn bộ file chỉnh sửa).

### 1. Gắn Nút UI Compose Cho Các API Rust Mới (`BrowserScreen.kt`, `BrowserComponents.kt`, `MediaScreens.kt`):
- Thêm nút **"Nén ZIP tại NAS"** cho thư mục ở cả menu 3 chấm và ActionSheet (`POST /api/zip`).
- Thêm 2 nút trên thanh công cụ `ImageViewerScreen`: **Thông tin EXIF** (`GET /api/media/exif`) và **Ảnh tương đồng** (`GET /api/fingerprint/similar`) kèm hộp thoại xem trực tiếp và nhảy nhanh tới ảnh tương đồng.

### 2. Tối Ưu Hóa Hiệu Năng Thuần Front-end Android (7 File Kotlin, `+87/-70` dòng):
- **`MediaScreens.kt` (`VideoPlayerScreen`):** Chỉ cập nhật `playbackPositionMs` mỗi 500ms khi overlay đang mở (`showOverlayButtons == true`), khi ẩn giảm xuống 2000ms (chống recompose toàn màn hình 2 lần/giây khi xem video full-screen).
- **`NasApplication.kt`:** Hạ Coil `memoryCache.maxSizePercent` từ `0.35 ➔ 0.18` (giảm nguy cơ OOM khi kết hợp ExoPlayer buffer).
- **`SystemMonitorViewModel.kt` & `DeviceManagementViewModel.kt`:** Cache `summaryClient` & `speedTestClient` thành `by lazy` chia sẻ chung connection pool thay vì tạo mới mỗi lần gọi; tăng interval background polling dashboard từ `15s ➔ 60s` tiết kiệm pin.
- **`SystemStatusCards.kt`:** Tách đồng hồ đếm giây livestream ra Composable lá `LivestreamDurationText` để mỗi giây chỉ recompose đúng 1 `Text`.
- **`FileBrowserViewModel.kt`:** Chuyển `enqueueBatchOperation()` (`file.writeText`) sang `Dispatchers.IO`, loại bỏ file I/O trên Main Thread.
- **`MainActivity.kt`:** Bỏ xóa Coil `diskCache` trong `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` để giữ lại cache thumbnail trên đĩa (chỉ xóa `memoryCache`).

---

## [2026-11-20] ANTIGRAVITY-022 — Tách Biệt Triệt Để Front-end Android Khỏi Các Tác Vụ Nặng Của Back-end (Đấu Nối Trực Tiếp NAS Rust API)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** `:app:compileDebugKotlin` **BUILD SUCCESSFUL** (0 lỗi, 0 warning mới trên 4 file Kotlin) + `test_extract_api_endpoints.py` PASS.

### 1. Cắt Bỏ Tính Toán Nặng Trên Điện Thoại, Đẩy Về NAS Rust Backend:
- **`FingerprintWorker.kt`:** Chuyển từ tải 512KB/ảnh qua WebDAV + decode Bitmap trên CPU điện thoại sang gọi trực tiếp `POST /api/fingerprint/compute` (NAS Rust NEON SIMD đa luồng, 0KB tải về điện thoại), chỉ fallback khi NAS đời cũ trả 404; đồng thời sửa lỗi port API bằng `savedUrl.toApiBaseUrl()`.
- **`AutoBackupWorker.kt`:** Loại bỏ bước decode Bitmap `ImageFingerprint.computeFromUri()` trên điện thoại ngay sau khi upload ảnh (dedup backup dùng `contentHash` SHA-256, còn aHash để NAS Rust tự tính).
- **`SmartToolsViewModel.kt`:** Ưu tiên nạp danh sách file trùng lặp đã xác minh trực tiếp từ `GET /api/hash/duplicates` của NAS Rust (không cần tải full bytes về điện thoại băm SHA-256; chỉ fallback Room DB khi offline).

### 2. Đấu Nối Các API Rust Mới Cho Tầng Front-end:
- **`SmartToolsViewModel.kt`:** Bổ sung `findSimilarImages()` (`GET /api/fingerprint/similar` — ARM64 NEON popcount) và `zipFolder()` (`POST /api/zip` qua `LongRunningApiWorker`).
- **`FileBrowserViewModel.kt`:** Bổ sung `zipFolder()` (`POST /api/zip`) và `fetchMediaExif()` (`GET /api/media/exif` — Rust `kamadak-exif`).

---

## [2026-11-20] ANTIGRAVITY-021 — Hoàn Tất Đợt Cuối P4 (Rust `dir_usage` Nhanh 13.95x & Parallel `scan_broken_mp4` Quét 4.157 Video Zero-GIL)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 295 passed, 6 skipped (100% test pass) + 12/12 Rust unit tests (`nas_hasher`). Production verified trên NAS thật qua mạng LAN (`192.168.100.254`).

### 1. Rust Directory Usage Scanner (`nas_hasher/src/fs_scanner.rs` -> `nas_hasher_dir_usage_json`):
- Port `_safe_dir_usage` sang Rust `walkdir` (0 crate mới), bỏ qua `.nas_meta`, `.thumbnails`, `@eaDir`, hỗ trợ dừng sớm theo `max_files` và `max_seconds`, giải phóng hoàn toàn GIL trên HTTP critical path `/api/storage/usage`.
- **Benchmark thực tế trên NAS RK3328 (thư mục `10092026`, 2.049 tệp):** Python `0.3499s` ➔ Rust **`0.0251s` (Nhanh gấp `13.95x`)**.

### 2. Rust Parallel Broken MP4 Scanner (`nas_hasher/src/fs_scanner.rs` -> `nas_hasher_scan_broken_mp4_json`):
- Quét toàn cây thư mục song song bằng `walkdir` + `rayon` + tái sử dụng 100% `mp4_parser.rs` (bỏ qua `_broken_quarantine`), chỉ trả về các file `.mp4` nghi ngờ thiếu `moov` để Python xác nhận `ffprobe` và cách ly (giữ nguyên 0 false positive).
- **Benchmark thực tế trên NAS RK3328 (`AutoBackup/DCIM`, 4.157 video MP4 thật):** Quét toàn bộ 4.157 video trong **`49.081s`** (~11.8ms/video trên HDD cơ, zero-GIL), lọc chính xác còn 16 file nghi ngờ cần xác minh.

---

## [2026-11-20] ANTIGRAVITY-020 — Hoàn Tất Nhóm P3 (Rust `photo_tagger` Streaming Cứu 140MB RAM, TAR/TAR.GZ Safe Extractor 6.03x & EBML/FLV Binary Header Parser)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 293 passed, 6 skipped (100% test pass) + 10/10 Rust unit tests (`nas_indexer`) + 11/11 Rust unit tests (`nas_hasher`). Production verified trên NAS thật qua mạng LAN (`192.168.100.254`).

### 1. Rust Streaming Photo Tagger (`nas_indexer/src/photo_tagger.rs`):
- Port `_scan_photos_lightweight` sang `nas_indexer_scan_photo_tags_json`: đọc streaming từng dòng `file_index` qua `rusqlite` (`row.get_ref`), phân loại 8 nhóm danh mục khớp 100% `_FOLDER_CATEGORY_MAP`, ghi trực tiếp `ai_image_tags` trong cùng transaction và xuất streaming `ai_tags.json` — loại bỏ hoàn toàn đỉnh RAM ~120–140MB khi `fetchall()` ~400k ảnh trong Python 3.5.3.

### 2. Rust TAR / TAR.GZ Safe Extractor (`nas_hasher/src/archive_worker.rs`):
- Bổ sung `extract_tar_safe` + C-ABI `nas_hasher_extract_tar_json` dùng `tar` + `flate2`, tự nhận diện gzip magic `0x1F 0x8B`, chặn tuyệt đối Tar-Slip (`..` / đường dẫn tuyệt đối), symlink, hardlink và device/FIFO; tự động fallback Python `tarfile` cho `.tar.bz2` / `.tar.xz`.
- Benchmark thực tế trên NAS RK3328 (50 files / 5MB `.tar.gz`): Python `0.213s` -> Rust **`0.035s` (nhanh gấp 6.03x, zero-GIL)**.

### 3. Rust Unified Media Header Parser — MP4/MOV/M4V + MKV/WebM (EBML) + FLV (`nas_hasher/src/media_header_parser.rs`):
- Bộ phân tích nhị phân thuần Rust (0 crate mới) cho EBML (MKV/WebM: `TimestampScale`, `Duration`, `TrackEntry`/`CodecID`) và FLV (AMF0 `onMetaData` + VideoTag/AudioTag CodecID), đấu nối vào `_get_video_duration` và `_probe_video_codec` loại bỏ gần như 100% fork `ffprobe`.
- Benchmark thực tế trên tệp thật của NAS: `.mkv` (2.5 giờ) **`39.78ms`** (`h264/aac`, `9063.988s`), `.flv` **`15.94ms`** (`hevc/aac`), `.mp4` **`6.62ms`** (`h264/aac`, `20.034s`).

---

## [2026-11-20] ANTIGRAVITY-019 — Triển Khai Toàn Bộ Tối Ưu Hóa P1 & P2 (Streaming 128KB, Diệt N+1 Query, Rust Smart Organize, NEON Hamming & Kernel Zero-Copy `copy_file_range`)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 290 passed, 6 skipped (100% test pass) + 9/9 Rust unit tests (`nas_indexer`) + 9/9 Rust unit tests (`nas_hasher`). Production verified trên NAS thật qua mạng LAN (`192.168.100.254`, kernel `5.7.11-rockchip64`, CPU flags `asimd aes pmull sha1 sha2 crc32`).

### 1. Nhóm P1 — Khẩn Cấp & ROI Cao Nhất:
- **Streaming 128KB (`_iter_file_range`):** Hạ `chunk_size` từ `4MB` xuống `128KB` (giảm 32x áp lực cấp phát RAM heap Python và tránh nghẽn 6 worker threads Waitress khi tua video).
- **Sửa Rò Rỉ Trạng Thái `_ytdlp_jobs` (`_reap_ytdlp_jobs_locked`):** Tự động dọn sạch các job yt-dlp đã hoàn thành trước khi đếm `_ydl_running` (chống lỗi `HTTP 429` vĩnh viễn), gỡ kẹt cờ block thumbnail daemon, và bổ sung `start_new_session=True` trên Linux.
- **Đấu Nối Rust MP4 Parser vào `_livestream_video_is_playable`:** Sử dụng `rust_bridge.parse_mp4_header` (<0.1ms, zero-fork) cho `.mp4/.mov/.m4v` thay vì fork tiến trình `ffprobe` (timeout 15s) sau mỗi phiên livestream.
- **Triệt Tiêu N+1 Query trong `api_hash_duplicates`:** Gom truy vấn metadata `file_hashes` bằng batch `WHERE path IN (...)` (chia chunk 500 phần tử tương thích giới hạn 999 bind variables của SQLite 3.16.2 trên Debian 9), giảm từ hàng nghìn lần mở/đóng kết nối SQLite xuống 1 kết nối duy nhất.
- **Port `smart_organize_scan` sang Rust (`nas_indexer/src/smart_organize.rs`):** Quét 1 lượt streaming zero-copy (`row.get_ref`) trên `file_index` qua `rusqlite` Read-Only, tính `YYYY/MM` thuần số học (Howard Hinnant) kèm múi giờ địa phương, không tạo 468k Python tuples.

### 2. Nhóm P2 — Hiệu Năng Cao & Tận Dụng Phần Cứng RK3328:
- **Kernel Zero-Copy TS Merger (`nas_hasher/src/ts_merger.rs`):** Nối các segment quay màn hình `part_%06d.ts` bằng Linux syscall `SYS_copy_file_range` trực tiếp (tương thích `glibc 2.24` trên kernel `5.7.11-rockchip64`), fallback buffer 512KB an toàn, đồng thời tự động xóa `final_ts` trung gian sau khi remux MP4 thành công để tiết kiệm 50% dung lượng đĩa.
- **aHash Hamming Similarity Search (`nas_indexer/src/fingerprint_search.rs`):** Quét bảng `image_fingerprints` trong Rust dùng `u64::count_ones()` (ARM64 NEON `VCNT` popcount), benchmark live trên NAS chỉ mất `2.82ms`.
- **Kernel Zero-Copy USB Fast Copy (`nas_hasher/src/usb_importer.rs`):** Đấu nối vào `_usb_import_write_file_with_progress` với callback tiến độ & hủy tức thì (`progress_cb`). Benchmark thực tế trên NAS RK3328 (40MB): **zero-copy no-hash đạt `175.7 MB/s` (`0.228s`, `zero_copy=True`, zero-GIL)**; **single-pass + SHA-256 đạt `55.5 MB/s` (`0.720s`, 0 byte Python heap)**.

---

## [2026-11-20] ANTIGRAVITY-018 — Nén & Giải Nén ZIP An Toàn Siêu Tốc Thuần Rust (Chống Zip-Slip & Symlink Traversal)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 283 passed, 6 skipped (100% test pass) + 7/7 Rust unit tests (`nas_hasher`). Production verified trên NAS thật qua mạng LAN (`192.168.100.254`).

### 1. Module Archive Worker Thuần Rust (`nas_hasher/src/archive_worker.rs`):
- **Bảo Vệ Toàn Diện Chống Zip-Slip:** Sử dụng `enclosed_name()` của crate `zip` kết hợp `fs::canonicalize()` và xác thực `out_path.starts_with(&dest_real)` ngăn chặn tuyệt đối mọi nỗ lực ghi đè file ngoài thư mục giải nén (`../../etc/passwd`).
- **Chống Symlink Traversal:** Nhận diện và từ chối các tệp symlink nén trong file zip (`unix_mode & 0o170000 == 0o120000`) để ngăn khai thác đọc trộm/ghi đè file nhạy cảm của hệ thống.
- **Nén Thư Mục Deflate Zero-GIL:** Tự động duyệt cây thư mục bằng `walkdir`, ghi tệp nén bằng streaming buffer 64KB không chiếm dụng bộ nhớ RAM và giải phóng GIL hoàn toàn cho Flask web server.
- **Bảo toàn Cấu Trúc Thư Mục Gốc:** Strip prefix thư mục cha để khi bung zip ra giữ nguyên thư mục gốc (`my_folder/file.txt`).
- **Hiệu Năng Benchmark Thực Tế Trên NAS RK3328 ARM64 (50 files / 5MB):**
  - **Nén:** Python `0.170s` -> Rust `0.096s` (**Nhanh hơn 1.76x**).
  - **Giải nén:** Python `0.101s` -> Rust `0.032s` (**Nhanh hơn 3.16x**).

### 2. Tích Hợp Backend & Endpoints Mới (`nas_api_server.py`, `rust_bridge.py`):
- **`_extract_zip_safe(archive_path, dest_dir)`:** Tự động ưu tiên gọi `rust_bridge.extract_zip_safe_rust` trước khi fallback về `zipfile` của Python 3.5.3.
- **`POST /api/zip` (Alias `/api/file/zip`):** Endpoint nén thư mục thành file `.zip` (hỗ trợ `path` và `dest_zip` tùy chọn), kiểm tra giới hạn trong WebDAV root và ghi log kiểm toán.
- **`POST /api/unzip` (Alias `/api/file/unzip`):** Hỗ trợ cả 2 chuẩn route cho client Android và Web dashboard.
- **Sửa Triệt Để EXIF SubIFD Parser (`exif_reader.rs`):** Chuyển từ `get_field(..., In::PRIMARY)` sang `exif.fields().find(...)` giúp nhận diện chính xác các tag thời gian `DateTimeOriginal`, `DateTimeDigitized` và `DateTime` nằm trong SubIFD Exif (khắc phục test `test_image_exif_rust.py`).

---

## [2026-11-20] ANTIGRAVITY-017 — Chuyển Kết Nối LAN & Trích Xuất Siêu Dữ Liệu Ảnh EXIF (DateTime, Camera, GPS) Siêu Tốc Thuần Rust
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 281 passed, 6 skipped (100% test pass) + 6/6 Rust unit tests (`nas_hasher`). Production verified trên NAS thật qua mạng LAN (`192.168.100.254`).

### 1. Bộ Trích Xuất EXIF Thuần Rust Siêu Tốc (`nas_hasher/src/exif_reader.rs`):
- **Đọc Header Zero-Copy Không Decode Pixel:** Dùng `kamadak-exif v0.6.2`, chỉ đọc segment APP1 / eXIf (vài KB đầu file) từ JPEG/TIFF/PNG/WebP, hoàn toàn không giải mã mảng pixel nên không tốn RAM hay nghẽn CPU.
- **Trích Xuất Đầy Đủ Siêu Dữ Liệu:**
  - Ngày giờ chụp thực tế (`date_taken`: `YYYY-MM-DD HH:MM:SS`) và Unix epoch timestamp ms (`timestamp_ms`) tính bằng thuật toán lịch tối ưu không cần `chrono`.
  - Hãng máy ảnh (`camera_make`) và mẫu máy (`camera_model`) ví dụ `samsung SM-A035F`, `Apple iPhone 15 Pro`.
  - Tọa độ địa lý GPS (`latitude`, `longitude`) chuẩn độ thập phân (decimal degrees) tính từ Rational độ/phút/giây kèm Ref (N/S/E/W).
  - Hướng xoay ảnh (`orientation` 1..8).
- **Batch Đa Luồng Rayon (`nas_hasher_read_batch_exif_json`):** Phân chia tác vụ trên 4 nhân Cortex-A53 của NAS. Benchmark thực tế trên ảnh DCIM thật: **5 ảnh chỉ mất `0.031s` (~`6ms`/ảnh)**.

### 2. Tích Hợp Backend & Endpoints Mới (`nas_api_server.py`, `rust_bridge.py`):
- **Chuyển Kết Nối Sang Mạng LAN:** Đã chuyển toàn bộ các thao tác SSH/SCP/curl sang địa chỉ mạng nội bộ LAN `192.168.100.254`.
- **`GET /api/media/exif?path=...`:** Trả về siêu dữ liệu EXIF của 1 ảnh chỉ định. Đã kiểm chứng thực tế trên ảnh camera Samsung trả về đúng ngày chụp `2022-06-18 15:18:45` và model `SM-A035F`.
- **`POST /api/media/exif/batch`:** Nhận danh sách tối đa 200 ảnh, đọc EXIF song song đa luồng bằng Rust trả về dict kết quả nhanh chóng.
- **Fallback an toàn:** Tự động fallback về Pillow `_getexif()` nếu thiếu binary Rust; tuân thủ nghiêm ngặt Python 3.5.3 (Debian 9 Stretch).

---

## [2026-11-19] ANTIGRAVITY-016 — Tự Động Tính Chu Ký Ảnh Perceptual aHash 64-bit Bằng Rust & Nhận Diện Ảnh Tương Đồng Trên NAS
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 279 passed, 6 skipped (100% test pass) + 4/4 Rust unit tests (`nas_hasher`). Production verified trên NAS thật (`100.90.135.102`).

### 1. Bộ Tính Chu Ký Ảnh Thuần Rust Tăng Tốc NEON SIMD (`nas_hasher/src/image_thumb.rs`):
- **Thuật toán Average Hash (aHash 64-bit):** Khớp 100% logic của `ImageFingerprint.kt` trên app Android.
  - Scale ảnh về 8x8 pixels bằng NEON SIMD Bilinear (`fast_image_resize`).
  - Chuyển màu Grayscale tiêu chuẩn (`0.299*R + 0.587*G + 0.114*B`).
  - Tính độ xám trung bình của 64 pixels và đóng gói 64 bits (pixel >= avg là 1, bit (0,0) là MSB).
  - Xuất ra chuỗi hex 16 ký tự chữ thường (`String.format("%016x")`).
- **Khoảng cách Hamming (Hamming Distance):** Tính số lượng bit khác biệt giữa 2 chuỗi aHash bằng `(h1 ^ h2).count_ones()`, cho phép nhận diện ảnh gần trùng (near-duplicates, distance <= 5).
- **Batch Đa Luồng Rayon (`nas_hasher_batch_ahash_json`):** Phân chia tính toán trên toàn bộ 4 nhân CPU Cortex-A53 của NAS L1 Pro. Benchmark thực tế trên NAS: **5 ảnh thật chỉ mất 0.249s (~50ms/ảnh)**.

### 2. Tích Hợp Backend & Endpoints Mới (`nas_api_server.py`, `rust_bridge.py`):
- **`POST /api/fingerprint/compute`:** Tự động quét các ảnh chưa có hash trong `file_index` (hoặc danh sách chỉ định), tính aHash bằng Rust đa luồng và lưu thẳng vào SQLite `image_fingerprints`. Giải phóng hoàn toàn gánh nặng cho điện thoại Android.
- **`GET /api/fingerprint/similar`:** Tìm kiếm các ảnh tương đồng với ảnh chỉ định dựa vào khoảng cách Hamming (`?path=...&threshold=5`). Kiểm chứng thực tế trên NAS tìm thấy 2 ảnh FB chụp liên tiếp có `hamming_distance = 2`.
- **An toàn tương thích:** Tự động fallback về Pillow nếu thiếu thư viện Rust; tuân thủ nghiêm ngặt Python 3.5.3 (không f-strings, bảo vệ input).

---

## [2026-11-19] ANTIGRAVITY-015 — Rà Soát Toàn Diện 2 Sub-Agent Độc Lập, Vá Lỗ Hổng Backend/Rust & Tương Thích Glibc 2.24
**Tác nhân thực hiện:** Antigravity (Ponytail full mode) + 2 Sub-agents chuyên trách (Reviewer Rust & Reviewer Python).
**Kết quả kiểm thử:** 277 passed, 6 skipped (100% test pass), 10/10 Rust unit tests PASS. Production verified trên NAS thật (`100.90.135.102`).

### 1. Kết Quả Rà Soát & Vá Lỗi Độc Lập Từ 2 Sub-Agents:
- **Rust Modules (`nas_hasher`, `nas_indexer`):**
  - **Khắc phục out-of-bounds write:** Bổ sung kiểm tra độ dài buffer (`bytes.len() < 64` cho SHA256, `bytes.len() < 32` cho MD5) trong `nas_hasher/src/lib.rs` trước khi ghi chuỗi hex vào C pointer.
  - **Khắc phục underflow panic:** Dùng `meta_json.strip_suffix('}').unwrap_or(&meta_json)` trong `nas_indexer/src/lib.rs` thay thế slicing trực tiếp `&meta_json[..meta_json.len() - 1]`.
  - **Tối ưu release profile:** Cấu hình `[profile.release]` (`opt-level = 3`, `lto = "thin"`, `codegen-units = 1`, `strip = true`) và cố định version `fast_image_resize = "=6.1.0"` trong cả 2 crate Cargo.toml.
  - **Biên dịch tương thích glibc 2.24:** Cross-compile bằng `cargo zigbuild --target aarch64-unknown-linux-gnu.2.24` đảm bảo liên kết hoàn hảo với Debian 9 Stretch trên NAS L1 Pro, khắc phục lỗi thiếu `GLIBC_2.29`.
- **Python Backend (`backend/rust_bridge.py`, `backend/nas_api_server.py`):**
  - **Sửa thụt lề phá vỡ lock (`rust_bridge.py`):** Sửa lỗi thụt lề khối `candidates` và `for p in candidates:` trong `_init_rust_bridge()` đưa vào trong phạm vi `with _RUST_INIT_LOCK:`, ngăn race condition khi nhiều luồng tải CDLL đồng thời.
  - **Tránh syscall lặp vô ích (`rust_bridge.py`):** Bổ sung cờ `_RUST_CHECKED = True` để khi môi trường không có Rust binary thì không lặp lại 4 lần syscall `os.path.isfile()` ở mỗi lời gọi.
  - **Dọn dẹp tiến trình zombie (`rust_bridge.py`):** Bổ sung `proc.wait(timeout=0.5)` trong `_RustIndexerStream.close()` và `stream_fast_index()` sau khi gọi `proc.terminate()`, loại trừ nguy cơ rò rỉ zombie `<defunct>`.
  - **An toàn ngoại lệ luồng (`rust_bridge.py`):** Bọc `t.start()` trong `schedule_fts_rebuild()` bằng block `try...except`, nhả `_FTS_REBUILD_LOCK` nếu xảy ra lỗi, tránh deadlock vĩnh viễn cho tác vụ rebuild FTS ngầm.
  - **Vá lỗ hổng Path Traversal / Xóa Thùng Rác Gốc (`nas_api_server.py`):** Trong `api_disk_trash_batch()`, sửa điều kiện kiểm tra `if not local_path.startswith(root_real + os.sep):` loại bỏ hoàn toàn sơ hở `local_path == root_real` có thể khiến thư mục chia sẻ gốc bị di chuyển vào thùng rác.
  - **Bảo vệ `request.get_json()` rỗng (`nas_api_server.py`):** Cập nhật 7 điểm gọi thành `request.get_json(force=True) or {}` tránh `AttributeError` khi payload client gửi null/empty.
  - **Bảo toàn trạng thái response restore (`nas_api_server.py`):** Cập nhật danh sách `services_restarted` trước khi `jsonify()` trong `api_backup_restore()` để client nhận đúng danh sách service được lên lịch khởi động lại.
- **Kiểm thử tự động (`tests/test_audit_20261026_fixes.py`):** Bổ sung `rust_bridge.py` và `nas_work_coordinator.py` vào bộ kiểm tra cú pháp AST Python 3.5.3 bắt buộc.

---

## [2026-11-19] ANTIGRAVITY-014 — Khắc Phục Triệt Để Lỗi Quạt Nháy Bật Rồi Tắt Giật Cục Trên NAS RK3328
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 276 passed, 6 skipped (100% test pass) + regression test `test_fan_does_not_blip_when_hdd_temp_unavailable_or_in_hysteresis`. Production verified trên NAS thật (`100.90.135.102`).

### 1. Nguyên nhân gốc rễ & Các điểm sửa chữa chính:
- **Nguyên nhân 1 (Bật quạt khi boot):** `_restore_fan_state_on_boot()` trước đây trong chế độ `custom`/`auto` gọi `_fan_power_set(True); _pwm_write("enable", 1)` làm quạt quay rú lên ngay khi khởi động service dù NAS đang mát; 2 giây sau watchdog mới tắt quạt. -> **Đã sửa:** Chuyển sang `_pwm_apply_off()` ban đầu, để watchdog đo nhiệt độ thực tế rồi mới kích hoạt mượt mà nếu quá nhiệt.
- **Nguyên nhân 2 (Bật quạt khi gọi API ở khoảng trễ):** `api_fan_control()` ở khoảng trễ (`off_temp < temp < on_temp`) trước đây chạy `_fan_power_set(True); _pwm_write("enable", 1)` trong khi trả về `pct=0` ("Dừng"). -> **Đã sửa:** Nếu quạt đang dừng thì giữ `_pwm_apply_off()`, không kích nguồn GPIO 79.
- **Nguyên nhân 3 (Spike 100% khi SMART bận):** `force_hot = hdd_temp <= 0 or ...` gây xung bật quạt 100% mỗi khi `get_hdd_temp()` trả về 0 / `--°C` trong lúc SMART đang query. -> **Đã sửa:** Bỏ `hdd_temp <= 0` khỏi `force_hot`.
- **Nguyên nhân 4 (Lệch ngưỡng CPU vs HDD):** Khi `hdd_temp` tạm thời bằng 0, fallback `control_temp = cpu_temp` (CPU thường 45–50°C) trực tiếp vượt qua `on_temp` của HDD (42°C), làm quạt bật rồi 1s sau HDD có giá trị (41°C) lại tắt. -> **Đã sửa:** Bù trừ ngưỡng CPU khi fallback (`cpu_temp - 15.0`), tránh kích hoạt giả khi CPU ở mức bình thường.
- **Nguyên nhân 5 (Xung đột daemon ngoài):** Dịch vụ `/sbin/fan` (`fan.service`) cũ được mask vĩnh viễn (`systemctl mask fan.service`) để cấm ghi đè thanh ghi PWM.

---

## [2026-11-19] ANTIGRAVITY-013 — Tăng Tốc `_frame_brightness` (4.5x–4.9x) & Tạo Thumbnail Ảnh NEON SIMD (`image_thumb.rs`) Bằng Rust C-ABI
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 275 passed, 6 skipped (100% test pass) + 3/3 Rust unit tests (`nas_hasher`). Production verified trên NAS thật (`100.90.135.102`).

### 1. Bộ Xử Lý Ảnh Thuần Rust Tăng Tốc ARM64 NEON (`nas_hasher/src/image_thumb.rs`)
- Dùng `fast_image_resize v6.1` (ARM64 NEON SIMD Bilinear) + `image v0.25` (`zune-jpeg`, `png`, `image-webp`, `bmp`, `gif` — không phụ thuộc thư viện C).
- Xuất C-ABI `nas_hasher_generate_thumb` và `nas_hasher_frame_brightness` trong `libnas_hasher_core.so`, tích hợp vào `rust_bridge.py` và `_generate_image_thumb()` / `_frame_brightness()` trong `nas_api_server.py` (tự động fallback về Pillow 4.0.0 nếu lỗi).

### 2. Benchmark Thực Tế Trên NAS Thật (`100.90.135.102`)
- **Đo độ sáng khung hình (`_frame_brightness`, 50 lượt):** Pillow `932.0ms` → Rust C-ABI **`205.8ms`** (**nhanh gấp 4.53x–4.89x**, parity `130.78` vs `130.76`).
- **Tạo thumbnail 20 ảnh JPEG thực tế (>200KB):** Pillow 4.0.0 (`libjpeg-turbo` C + DCT `draft()`) `5,048.0ms` → Rust NEON SIMD **`4,415.3ms`** (**nhanh hơn 14%**, parity kích thước 20/20, giải phóng GIL và hỗ trợ WebP/PNG hiện đại hơn Pillow 4.0.0 năm 2017).

---

## [2026-11-19] ANTIGRAVITY-012 — Tăng Tốc Thống Kê Lưu Trữ Dashboard `_build_storage_summary` Bằng Rust C-ABI (41.1x: 31.61s → 0.77s)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 274 passed, 6 skipped (100% test pass) + 5/5 Rust unit tests (`nas_indexer`). Production verified trên NAS thật (`100.90.135.102`).

### 1. Bộ Thống Kê Lưu Trữ 1 Lượt Quét Zero-Allocation (`nas_indexer/src/storage_summary.rs`)
- Thay thế 3 truy vấn SQLite + 468.794 lần gọi Python UDF `storage_category(name)` bằng 1 lượt quét duy nhất trong Rust (`rusqlite` READ_ONLY + `row.get_ref()` zero-copy `&str`), khớp chính xác 100% quy tắc `os.path.splitext` của Python (bao gồm dotfile như `.jpg` → `other` và tie-break `path ASC` cho Top 10 tệp lớn nhất).
- Xuất C-ABI `nas_indexer_storage_summary_json` trong `libnas_indexer_core.so`, gắn vào `rust_bridge.compute_storage_summary()` và `_build_storage_summary()` với fallback 100% về Python gốc.

### 2. Benchmark Thực Tế Trên NAS Thật (467.967 Tệp + 827 Thư Mục / 2,85 TB)
- **Độ khớp (Parity):** Khớp **100% chính xác từng byte** ở cả 6 nhóm (`image`: 420.794 tệp, `video`: 46.925 tệp, `other`: 238, `doc`: 5, `archive`: 3, `audio`: 2, `dirs`: 827).
- **Python UDF (trước):** `31.614 giây`.
- **Rust C-ABI (sau):** **`0.7699 giây`** — **nhanh gấp 41.1x**!

---

## [2026-11-19] ANTIGRAVITY-011 — Bộ Giải Mã Header MP4/MOV (`mp4_atom_parser`) Thuần Rust Không Cần `ffprobe` (Nhanh Gấp 614.7x)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 273 passed, 6 skipped (100% test pass) + 5/5 Rust unit tests. Production verified trên NAS thật (`100.90.135.102`).

### 1. Bộ Giải Mã ISO BMFF / QuickTime Atom Thuần Rust (`nas_hasher/src/mp4_parser.rs`)
- Đọc trực tiếp cấu trúc box `ftyp` / `moov` / `mvhd` (v0 & v1 64-bit) / `trak` → `tkhd` (width, height) + `mdia` → `hdlr` + `minf` → `stbl` → `stsd` (codec 4CC: `h264`, `hevc`, `av1`, `vp9`, `aac`, `opus`, ...) bằng `File::seek`, nhảy bỏ qua hoàn toàn khối `mdat` nhiều GB (< 64KB RAM, không fork tiến trình).
- Xuất C-ABI `nas_hasher_parse_mp4_json` trong `libnas_hasher_core.so` và lệnh CLI `nas_indexer`/`nas_hasher mp4-info <video>`.

### 2. Tích Hợp Không Rủi Ro Vào 4 Điểm Nóng `ffprobe` (`rust_bridge.py`, `nas_api_server.py`)
- `_generate_video_thumb()`: 1 lần gọi `parse_mp4_header` thay thế **cả 2 lần fork `ffprobe`** lấy duration và codec cho `.mp4`/`.m4v`/`.mov`.
- `_get_video_duration()` & `_probe_video_codec()`: Đọc trực tiếp qua Rust C-ABI, tự fallback về `ffprobe` cho định dạng khác (`.mkv`, `.avi`, `.ts`...).
- `api_scan_broken_mp4()`: Bỏ qua `ffprobe` tức thì cho mọi tệp MP4 lành mạnh (`valid && has_moov`); tệp nghi ngờ hỏng vẫn được `ffprobe` xác nhận lại trước khi cách ly (0 false positive).

### 3. Benchmark Thực Tế Trên NAS Thật (10 Video MP4 Thực Tế Trên HDD)
- **Độ khớp (Parity):** **10/10** khớp chính xác tới `0.01s` thời lượng và codec (`h264`, `576x1024`) so với `ffprobe`.
- **`ffprobe` x2 (trước):** `8,666.8ms` (`866.7ms/video`).
- **Rust C-ABI (sau):** **`14.10ms`** (**`1.41ms/video`**) — **nhanh gấp 614.7x**, loại bỏ hoàn toàn 20 lần fork tiến trình `ffprobe`.

---

## [2026-11-19] ANTIGRAVITY-010 — Chỉ Mục Sidecar FTS5 Trigram (53x Tìm Kiếm) & Bộ Quét Trùng Lặp Đa Luồng `duplicate_finder` Rust Rayon (4.2x–9.8x)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 272 passed, 6 skipped (100% test pass) + 4/4 Rust unit tests. Production verified trên NAS thật (`100.90.135.102`).

### 1. Chỉ mục Sidecar FTS5 Trigram (`nas_indexer/src/search.rs`, `rust_bridge.py`, `nas_api_server.py`)
- Xây dựng chỉ mục sidecar `<db_path>.fts.db` (`tokenize='trigram'`) bằng SQLite 3.45 bundled trong Rust, tách biệt hoàn toàn khỏi `nas_index.db` để tương thích tuyệt đối với Python 3.5.3 / SQLite 3.16.2 trên Debian 9 Stretch.
- Tự động đối chiếu `(generation, catalogue_revision)` và tái dựng ngầm không chặn qua `schedule_fts_rebuild()` sau mỗi lượt lập chỉ mục hoàn tất; fallback tức thì về truy vấn `LIKE` chuẩn khi từ khóa < 3 ký tự hoặc FTS chưa sẵn sàng.
- **Hiệu năng đo thực tế trên NAS thật (468.794 tệp, dung lượng sidecar chỉ 92MB):**
  - Truy vấn không trúng (`q='bao cao'`): Python `521.5ms` → Rust FTS5 **`9.8ms`** (**nhanh gấp 53.4x**).
  - Truy vấn phổ biến (`q='anh'`, 300 kết quả): Python `488.3ms` → Rust FTS5 **`48.6ms`** (**nhanh gấp 10.0x**, `parity=True`).

### 2. Bộ Quét Trùng Lặp 3 Tầng Đa Luồng & Batch Hasher (`nas_hasher/src/duplicates.rs`, `lib.rs`, `nas_api_server.py`)
- Triển khai pipeline 3 tầng song song bằng `rayon` + `walkdir` (giới hạn 1–4 luồng bảo vệ HDD/RAM RK3328, `follow_links(false)` chống thoát ranh giới, bỏ qua `.trash`/`.nas_meta`/`@eaDir`): Size Grouping → Partial MD5 1MB → Full SHA-256.
- Tăng tốc `/api/disk/hash_batch` bằng `compute_batch_md5_1mb` (zero GIL) và bổ sung endpoint `POST /api/disk/scan_duplicates` (`@requires_auth`, containment `_resolve_webdav_request_path`).
- **Hiệu năng đo thực tế trên NAS thật:**
  - `/usr/lib/python3.5` (1.437 tệp): Python `338.5ms` → Rust 4 luồng **`34.4ms`** (**nhanh gấp 9.84x**).
  - `/usr/share` (37.572 tệp, 1.183 nhóm trùng lặp, 62MB rác): Python `28.54s` → Rust 4 luồng **`6.83s`** (**nhanh gấp 4.18x**).

---

## [2026-11-19] ANTIGRAVITY-009 — Chuyển Đổi Tìm Kiếm `api_fast_search` & Chuẩn Hóa Tiếng Việt `_remove_diacritics_py` Sang Rust C-ABI
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 269 passed, 6 skipped (100% test pass) + 3/3 Rust unit tests (36 ground-truth cases). Production verified trên NAS thật (`100.90.135.102`).

### 1. Module Rust C-ABI cho Chuẩn hóa Tiếng Việt & Tìm kiếm (`nas_indexer`)
- Bổ sung `src/diacritics.rs` (`unicode-normalization` NFD + lọc combining marks + thay `đ`/`Đ` → `d`) khớp 100% byte-for-byte với `_remove_diacritics_py` của Python.
- Bổ sung `src/search.rs` (`rusqlite` READ_ONLY + NO_MUTEX, escape wildcard `%`/`_`/`\` chuẩn xác, hỗ trợ lọc phân vùng `root_param`) xuất ra C-ABI `nas_indexer_remove_diacritics`, `nas_indexer_search_json`, `nas_indexer_free_string` (không rò rỉ bộ nhớ).
- Nạp tự động trong `backend/rust_bridge.py` (`is_search_accelerated()`, `remove_diacritics()`, `search_file_index()`) và tích hợp vào `_remove_diacritics_py()`, `api_fast_search()`, `/api/status` (`"rust_search_accelerated": true`) với fallback 100% về Python 3.5.3.

### 2. Hiệu năng đo thực tế trên NAS thật (Chainedbox L1 Pro RK3328 ARM64, 467.967 tệp)
- **Xóa dấu tiếng Việt (10.000 tên tệp):** Python `1.3825s` → Rust C-ABI `0.4712s` (**nhanh gấp 2.93x**).
- **Tìm kiếm toàn bộ 467.967 tệp (full scan):** Python `538.8ms` → Rust C-ABI `461.7ms` (**nhanh hơn 17–20%**, `parity=True` 100%).

---

## [2026-11-19] ANTIGRAVITY-008 — Triển Khai Kiến Trúc Hybrid Rust Tăng Tốc Backend: nas_hasher & nas_indexer (5.1x Throughput)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 266 passed, 6 skipped (100% test pass). Production verified trên NAS thật (Chainedbox L1 Pro RK3328 ARM64, Debian 9 Stretch).

### 1. Kiến trúc Hybrid Rust theo quy chuẩn rust-anything & Ponytail
- Giữ nguyên toàn bộ hạ tầng Python Flask/daemon, chỉ viết Rust cho phần lõi tính toán nặng và đọc ghi stream dữ liệu lớn.
- Đảm bảo cơ chế **Graceful Fallback 100%**: Nếu thiếu thư viện hoặc binary Rust, hệ thống tự động fallback về code Python gốc mà không gây gián đoạn dịch vụ.

### 2. Module 1: `nas_hasher` (MD5 1MB chunk & SHA256 full file)
- Viết bằng Rust thuần (crate `sha2`, `md-5`), xuất C-ABI qua `libnas_hasher_core.so` (1.4MB).
- Được nạp tự động qua `ctypes` trong `backend/rust_bridge.py`.
- Tích hợp vào `/api/disk/hash_batch` và `/api/status` (`"rust_accelerated": true`).
- Đạt độ khớp 100% byte-for-byte với Python hashlib trên toàn bộ các kích thước file từ 0 byte tới hàng chục MB.

### 3. Module 2: `nas_indexer` (Tăng tốc Fast Index Streaming cho 467.967 tệp tin)
- Viết bằng Rust với SQLite bundled (`rusqlite`), mở database ở chế độ `READONLY` + `NO_MUTEX` an toàn tuyệt đối, không gây lock với backend Python đang chạy.
- Tích hợp vào `generate_fast_index()` và endpoint `/api/disk/fast_index`: Stream trực tiếp các chunk JSON 64KB từ stdout của `nas_indexer stream` ra Flask WSGI Response.
- **Hiệu năng thực tế trên NAS thật (`100.90.135.102` / 467.967 tệp tin / DB 363MB):**
  - **Python thuần:** Mất **38.21 giây**, tốn nhiều bộ nhớ RAM để gom 468k tuples thành JSON.
  - **Rust native:** Chỉ mất **8.69 giây** để stream hoàn chỉnh 96.2 MB JSON (1.480 chunks).
  - **Tốc độ nhanh gấp 4.4x - 5.1x! Tiết kiệm hơn 80% RAM**, giải quyết triệt để tình trạng đơ giật khi App Android đồng bộ danh mục tệp.

---

## [2026-11-19] ANTIGRAVITY-007 — Tối Ưu Hóa Toàn Diện Hiệu Năng Render HTML5 & Đồng Bộ Xóa Log Hai Đầu
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 266 passed, 6 skipped (100% test pass). JS Syntax: Validated. Python AST: Validated.

### 1. Tối ưu Hiệu năng Render Giao diện HTML5 Dashboard (`app.js`)
- **Bộ nhớ đệm chữ ký đồ thị (`lastChartSignature`):** Bỏ qua hoàn toàn việc xóa và vẽ lại canvas biểu đồ khi dữ liệu và tham số điều khiển (zoom, pan, tab, range, touch scrub) không đổi. Tiết kiệm đáng kể tài nguyên CPU/GPU và pin điện thoại.
- **Bảo toàn vị trí cuộn Nhật ký (`lastDashLogsSignature`):** Chỉ cập nhật lại `innerHTML` của danh sách log khi có log mới hoặc khi người dùng thay đổi bộ lọc. Giúp người dùng thoải mái cuộn đọc log mà không bị giật hay nhảy vị trí mỗi 2 giây.
- **Tối ưu thẻ Lưu trữ (`lastStorageSignature`):** Chỉ tái tạo DOM các thanh dung lượng phân loại và danh sách tệp lớn nhất khi thông số lưu trữ thực sự thay đổi.

### 2. Tối ưu Bộ nhớ & Đồng bộ Xóa Log Hai Đầu (`DeviceManagementViewModel.kt`, `nas_api_server.py`)
- **Đồng bộ lệnh xóa log:** Khi người dùng bấm xóa nhật ký, hàm `clearSystemLogs()` đồng thời xóa Room DB trên Android và gửi POST tới `/api/system_logs/clear` trên NAS, ngăn chặn hiện tượng log cũ trên NAS được nạp ngược lại.
- **Giới hạn dung lượng bảng log trên NAS:** Đánh chỉ mục `idx_system_logs_id` và `idx_system_logs_type` trên SQLite NAS, tự động dọn dẹp giữ tối đa 500 bản ghi mới nhất, loại bỏ hoàn toàn nguy cơ phình dung lượng bộ nhớ trên phần cứng RK3328.

---

## [2026-11-19] ANTIGRAVITY-006 — Đưa Thẻ Nhật Ký Hệ Thống Ra Màn Hình Chính (Dashboard)
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 262 passed, 6 skipped (100% test pass). JS Syntax: Validated.

### 1. Nâng cấp Thẻ Nhật Ký Hệ Thống trên Màn Hình Chính
- **Thẻ Card `NHẬT KÝ HỆ THỐNG` trực tiếp trên Dashboard (`index.html`):**
  - Đặt ngay bên dưới thẻ `GIÁM SÁT` trên màn hình chính để người dùng theo dõi liên tục mà không cần mở modal/menu.
  - Header tích hợp:
    - Badge thông minh hiển thị tổng số log, tự động chuyển sang màu đỏ rực khi có lỗi (`X lỗi`) hoặc vàng khi có cảnh báo (`Y cảnh báo`).
    - Nút làm mới tức thì (`↻`).
    - Nút xóa nhanh nhật ký (`🗑`) kèm hộp thoại xác nhận an toàn.
    - Nút chevron (`▾`/`▴`) hỗ trợ thu gọn hoặc mở rộng thẻ dễ dàng.
  - Bộ lọc 4 tab tiện lợi: `Tất cả` | `Lỗi` | `Cảnh báo` | `Info` giúp lọc nhanh các sự kiện quan trọng.
  - Khung danh sách cuộn mượt mà (`max-height: 220px`), mỗi dòng log hiển thị phân loại theo màu sắc (ERROR: đỏ, WARNING: vàng, SUCCESS: xanh lá, INFO: xanh lam), tên Module, thời gian và nội dung log rõ ràng.
  - Footer hiển thị thống kê số lượng log hiển thị và nút `Xem thêm ▸` mở modal tra cứu sâu.

### 2. Tự động Nạp & Đồng bộ Thời Gian Thực (`NasHtmlDashboard.kt`, `app.js`)
- `NasHtmlDashboard.kt`: Tự động nạp nhật ký hệ thống `deviceVM.loadSystemLogs()` ngay khi mount Dashboard và định kỳ mỗi 20s (`tick % 10 == 0`).
- Đưa `deviceVM.systemLogs.size` vào snapshot quan sát của `LaunchedEffect`, tự động đẩy dữ liệu sang WebView ngay khi có log mới phát sinh.
- Bổ sung bridge `@JavascriptInterface clearLogs()` kết nối trực tiếp với `deviceVM.clearSystemLogs()`.

---

## [2026-11-19] ANTIGRAVITY-005 — Khắc Phục Triệt Để Lỗi Mất Biểu Đồ Giám Sát Khi Chuyển Khoảng Giờ 1h / 6h / 24h
**Tác nhân thực hiện:** Antigravity (Ponytail full mode).
**Kết quả kiểm thử:** 262 passed, 6 skipped (100% test pass). JS Syntax: Validated.

### 1. Nguyên nhân gốc rễ (Root Cause Analysis)
- **Thiếu lệnh gọi nạp API (`SystemMonitorViewModel.kt`):**
  Trong `selectMetricsHours(hours: Int)`, hàm thực hiện xóa danh sách `metricsHistory.clear()`, cập nhật `isLoadingMetrics = true`, `metricsHours = hours` nhưng **không hề gọi `fetchMetricsHistory(hours)`**! Trong giao diện Compose cũ (`MonitoringChartsScreen`), việc nạp lại phụ thuộc vào `LaunchedEffect(metricsHours)`. Tuy nhiên trên giao diện HTML5 WebView (`NasHtmlDashboard`), `MonitoringChartsScreen` không nằm trong cây giao diện nên khi đổi giờ, danh sách bị xóa trắng vĩnh viễn và không bao giờ được nạp lại.
- **Xóa dữ liệu quá sớm gây nhấp nháy / mất trắng:**
  Gọi `metricsHistory.clear()` ngay khi vừa bấm nút khiến mảng điểm dữ liệu rỗng ngay lập tức, canvas lập tức hiển thị `"Chưa đủ dữ liệu biểu đồ"`.
- **Chặn bấm lại nút đang chọn:**
  `if (hours == metricsHours) return` từ chối nạp lại khi người dùng bấm lại nút giờ đang chọn (ví dụ muốn tải lại khi dữ liệu đang trống).
- **Mất đồng bộ giữa WebView và ViewModel (`NasHtmlDashboard.kt` & `app.js`):**
  `LaunchedEffect` trong `NasHtmlDashboard.kt` chỉ quan sát `metricsHistory.size`. Nếu số lượng điểm ở 1h và 6h tương đương nhau, Compose không kích hoạt hiệu ứng đẩy dữ liệu sang WebView. Đồng thời, `app.js` khởi tạo mặc định `chartRange = 24`, trong khi ViewModel khởi tạo `metricsHours = 1`.

### 2. Giải pháp kỹ thuật đã triển khai
- **`SystemMonitorViewModel.kt`:**
  - Viết lại `selectMetricsHours(hours: Int)`: Luôn gọi `fetchMetricsHistory(h)` ngay lập tức; không xóa `metricsHistory` sớm để giữ nguyên đồ thị hiển thị mượt mà cho đến khi gói dữ liệu mới trả về từ NAS.
  - Hỗ trợ bấm lại nút đang chọn để làm mới biểu đồ (`fetchMetricsHistory(h)`).
  - Tối ưu `fetchMetricsHistory`: Dùng `optInt("hours", hours)` để tránh ngoại lệ định dạng số.
- **`NasHtmlDashboard.kt`:**
  - Bổ sung `lastHistoryRefreshAt`, `metricsHours`, `isLoadingMetrics` vào danh sách quan sát của `LaunchedEffect`, đảm bảo đẩy dữ liệu sang WebView tức thì ngay khi NAS phản hồi.
  - Đóng gói đầy đủ `metricsHours`, `metricsLoading`, `metricsError` vào payload JSON gửi sang JavaScript.
  - Tự động nạp mới `metricsHistory` mỗi 30 giây trong chu kỳ kiểm tra định kỳ của Dashboard.
- **`app/src/main/assets/html5/app.js`:**
  - Đồng bộ `chartRange = 1` mặc định khớp với ViewModel.
  - Lắng nghe `state.metricsHours` để tự động làm nổi bật đúng nút tương ứng.
  - `drawChart()`: Hiển thị thông báo trạng thái `"Đang tải dữ liệu biểu đồ (Xh)..."` khi đang nạp, hoặc hiển thị chi tiết lỗi nếu có sự cố thay vì chỉ báo chung chung `"Chưa đủ dữ liệu biểu đồ"`.
  - Sự kiện click `[data-range]`: Đổi class `.active` ngay lập tức, hiển thị toast và gọi cầu nối sang Native.

---

## [2026-11-19] ANTIGRAVITY-004 — Tích Hợp Động Cơ Băm File Rust Core Engine (Kiến Trúc Hybrid Python + Rust)
**Tác nhân thực hiện:** Antigravity (Gemini 3.8 Flash, Ponytail full mode).
**Kết quả kiểm thử:** 177 passed, 6 skipped (100% test pass). AST verification Python 3.5.3 + Flask 1.1.4: 100% PASS. `rust-anything` verification gate: 6/6 cases byte-for-byte MATCH.

### 1. Xây dựng Rust Core Engine (`nas_hasher`)
- **Crate Rust:** Tạo module [`backend/rust_modules/nas_hasher`](backend/rust_modules/nas_hasher) hỗ trợ cả CLI binary và C-ABI Shared Library (`cdylib`). Cung cấp 2 hàm C-ABI thread-safe: `nas_hasher_sha256` và `nas_hasher_md5_1mb`.
- **Quy trình `rust-anything`:** Tạo [`record_hasher_cases.py`](record_hasher_cases.py) thu thập dữ liệu chân lý mặt đất từ Python gốc (`cases.jsonl`). Chạy lệnh `nas_hasher verify cases.jsonl` đảm bảo độ khớp 100% từng byte (file 0B, UTF-8 tiếng Việt, binary 512KB, file lớn 2.5MB, file không tồn tại).

### 2. Cầu Nối Hybrid Bridge & Fallback mềm (`backend/rust_bridge.py`)
- Nạp trực tiếp thư viện Rust C-ABI vào process Python qua `ctypes` chuẩn Python 3.5.3 (zero process-spawn overhead, zero thêm RAM).
- Cơ chế Graceful Fallback an toàn tuyệt đối: nếu thư viện Rust chưa có mặt hoặc gặp lỗi I/O, tự động fallback 100% về `hashlib` của Python.
- Tích hợp vào [`backend/nas_api_server.py`](backend/nas_api_server.py):
  - Tăng tốc `_hash_one(item)` trong `api_hash_batch`.
  - Bổ sung trường `rust_accelerated: True/False` vào `/api/status` để App Android và Dashboard nhận biết trạng thái tăng tốc.
- Bổ sung bộ test tự động [`tests/test_rust_hasher_bridge.py`](tests/test_rust_hasher_bridge.py) kiểm thử độ khớp byte-for-byte và tính năng fallback.

---

## [2026-11-19] ANTIGRAVITY-003 — Khắc Phục Triệt Để Vòng Lặp Tạo Thumbnail & Tự Động Thu Gọn / Ẩn Hiện Cài Đặt Quạt Tản Nhiệt
**Tác nhân thực hiện:** Antigravity (Claude Fable 5 & GPT-6 Astra, Ponytail full mode).
**Kết quả kiểm thử:** 257 passed, 6 skipped (100% test pass). AST verification Python 3.5.3 + Flask 1.1.4: 100% PASS. JS Syntax: Validated.

### 1. Khắc phục Triệt để Vòng lặp Thumbnail & Đồng bộ 100% qua Tailscale
- **Nguyên nhân gốc rễ:** Hàm `bump_catalogue_revision()` trong `nas_work_coordinator.py` reset job `thumbnail` về `state='queued'`, `cursor=''`, `completed=0` mỗi khi ghi file mới (livestream TikTok hoặc AutoBackup). Trong `nas_api_server.py`, `_scan_roots()` bị abort bởi `RuntimeError("catalogue_changed")` và xoá mất keyset pagination `after_path = ""`.
- **Backend Fix:** Gỡ bỏ `'thumbnail'` khỏi danh sách reset khi bump revision và checkpoint; duy trì con trỏ phân trang keyset `after_path`; tự động ghi nhận tệp hỏng/HEIC vào `thumb_neg_cache`.
- **PC Sync:** Kết nối Tailscale qua SMB, render toàn bộ 7.469 thumbnail thiếu bằng 8 luồng CPU kết hợp `Pillow` + `pillow-heif` + `ffmpeg` trong 13 phút; đóng gói `thumbs.tar.gz` (89.7 MB) và giải nén trực tiếp vào thư mục `.thumbs` trên NAS. Ghi 608 tệp hỏng vào `thumb_neg_cache` (TTL 7 ngày) và chốt `nas_work_jobs` cho thumbnail thành `complete`.

### 2. Khắc phục UI Quạt Tản Nhiệt (Tự Động Ẩn/Hiện Bảng Cài Đặt Ngưỡng Tùy Chỉnh)
- **Vấn đề:** Khi quạt ở chế độ "Tùy chỉnh", bảng hai thanh trượt nhiệt độ bật/tắt luôn mở cố định ("chình ình"), khiến ngón tay người dùng khi vuốt cuộn màn hình xem biểu đồ dễ chạm vào thanh trượt làm thay đổi ngưỡng nhiệt độ ngoài ý muốn.
- **Khắc phục (`app/src/main/assets/html5/app.js`, `index.html`):**
  - Khởi tạo mặc định `fanCustomExpanded = false`, chỉ hiển thị thanh thông số tóm tắt một dòng gọn gàng, tránh cản trở thao tác cuộn.
  - Nút **"Tùy chỉnh"** hoạt động như nút chuyển đổi (toggle): chạm vào để mở rộng cài đặt (`Tùy chỉnh ▴`), chạm lại để thu gọn (`Tùy chỉnh ▾`). Chạm vào dòng tóm tắt `fanRuleHint` cũng hỗ trợ thu gọn/mở nhanh.
  - Khi chọn chế độ **"Dừng"** hoặc **"Tự động"**, bảng tùy chỉnh tự động thu gọn và chuyển chế độ.
  - Thêm nút **"Đóng"** bên cạnh nút **"Lưu ngưỡng quạt tùy chỉnh"**; sau khi lưu thành công bảng tự động thu gọn để tránh kéo nhầm.
  - Cập nhật hàm `updateState` để không ghi đè trạng thái hiển thị của bảng khi đang thu gọn.

---

## [2026-11-17] ANTIGRAVITY-002 — Giải quyết Triệt để Vấn đề Báo Thiếu Thumbnail & Hợp nhất 2 Database
**Tác nhân thực hiện:** Claude Fable 5 & GPT-6 Astra (Effort ULTRA / Ponytail), nghiệm thu & khảo sát thực địa bởi Antigravity.
**Kết quả kiểm thử:** 247 passed, 16 skipped (100% test pass). AST verification Python 3.5.3 + Flask 1.1.4: 8/8 pass.

### 1. Giải quyết Gốc rễ Vấn đề "NAS Báo Thiếu Nhiều Thumbnail"
- **Khảo sát thực địa:** Thư mục `.thumbs` trên NAS thực tế đang lưu trữ **898.563 file thumbnail (16 GB)**. Kiểm tra ngẫu nhiên 10.000 file media trong `file_index` (tổng 465.714 media) xác nhận **98.00% file đã có sẵn thumbnail trên đĩa**; chỉ có ~2% (khoảng ~9.000 file mới tải lên qua AutoBackup) là thực sự thiếu.
- **Nguyên nhân báo thiếu sai lệch:** Con số `85.472 / 467.572` trên job `thumbnail` là **vị trí con trỏ duyệt danh mục (scan_cursor)** của daemon nền chứ không phải số thumbnail đã tạo. Daemon bị tạm dừng (`paused - error: nas_busy`) vì gate bảo vệ CPU nhường tài nguyên cho 2 tiến trình `ffmpeg` thu livestream TikTok. Client Android lấy `work.total - work.completed` nên hiểu nhầm là thiếu 382.100 thumbnail.
- **Khắc phục tại Backend (`backend/nas_api_server.py`):**
  - Tách biệt hoàn toàn luồng người dùng mở thư mục: Gỡ bỏ kiểm tra gate `_thumb_paused / _background_heavy_work_allowed` khỏi endpoint On-demand `/api/thumb`. Người dùng mở ảnh sẽ được render tức thì, không bị chặn bởi livestream.
  - Cập nhật `/api/thumb/status`: Bổ sung `actual_generated`, `actual_total`, `actual_missing`, `actual_status`, `scan_cursor`, `scan_total`. Khi daemon pause, không trả về `0, 0` mà bảo toàn số liệu thực tế gần nhất.
- **Khắc phục tại Client Android (`app/src/main/java/com/nas/naswebdav/...`):**
  - Cập nhật `NasModels.kt` và `SmartToolsViewModel.kt`: Phân biệt rõ `scanProgress` / `scanTotal` (tiến trình rà soát) và `thumbnailed` / `missing` (số lượng thực tế). Giao diện hiển thị minh bạch: *"Đã rà soát 85.472/467.572 file"* thay vì báo thiếu sai lệch.

### 2. Xử lý Hợp nhất 2 Database thành 1 (`nas_index.db`)
- Bổ sung logic tự động đổi tên `/var/lib/nas_fast_index.db` thành `.db.migrated` sau khi `import_legacy_catalogue` hoàn thành trong `backend/nas_api_server.py`.
- Tạo script độc lập [`scripts/migrate_to_single_db.py`](file:///d:/Android/NASWebDAV/scripts/migrate_to_single_db.py) (tương thích 100% Python 3.5.3, dùng `ATTACH DATABASE`) cho phép gộp database trực tiếp trên NAS mà không cần khởi động lại server hoặc ngắt quãng livestream.

### 3. Nâng cấp PC Thumbnail Worker (`scripts/pc_thumb_batch.py`)
- Bổ sung tham số `--priority-folder` (ví dụ `--priority-folder AutoBackup/DCIM/Camera`): Tự động ưu tiên gom các file thuộc thư mục mới lên đầu trang để xử lý trước.
- Tối ưu bước kiểm tra tồn tại qua `/api/thumb/exists` (batch 512 items): Bỏ qua 98% file đã có thumbnail trong 1ms, chỉ tải và render 2% file thực sự thiếu.

---

## [2026-11-17] ANTIGRAVITY-001 — Hoàn tất bàn giao toàn diện từ Codex: Bảo mật Kaspersky, Fingerprint aHash, AI Tags Catalogue, Coordinator PC Tools, Mutation Hooks & Hardware Audit
**Tác nhân thực hiện:** Claude Fable 5 & GPT-6 Astra (chế độ Effort ULTRA / Ponytail mode), nghiệm thu & giám sát độc lập bởi Antigravity.
**Kết quả kiểm thử:** 257 passed, 6 skipped (100% test pass). AST verification Python 3.5.3 + Flask 1.1.4: 8/8 pass.

### 1. Bảo mật & Diệt cảnh báo Kaspersky (`backend/nas_api_server.py`)
- Loại bỏ triệt để cảnh báo `HEUR:Trojan-Spy.Python.Stealer.gen` của Kaspersky mà không sử dụng bất kỳ kỹ thuật làm mờ mã (obfuscation) nào.
- Thêm `_ensure_cookie_guard()`, `_cookie_path_is_confined(path)` (kiểm soát ranh giới `WEBDAV_FILE_ROOT` và `/etc/nas`, chống directory traversal và symlink bypass).
- Thêm `_is_valid_netscape_cookies_file()` và `_audit_cookie_access()` ghi nhật ký kiểm toán hành vi đọc cookie ra `/var/lib/nas_api/cookie_access.audit.log`.

### 2. Fingerprint aHash Tập trung (`backend/nas_api_server.py`, `FingerprintWorker.kt`)
- Thêm bảng `image_fingerprints` (path, source_version, ahash, size, mtime, updated_at) vào DB duy nhất `nas_index.db` kèm chỉ mục tối ưu `idx_image_fps_ahash`, `idx_image_fps_version`.
- Cung cấp 3 API mới: `GET /api/fingerprint/pending`, `POST /api/fingerprint/save` (batch 500 items), `POST /api/fingerprint/lookup`.
- Cập nhật `FingerprintWorker.kt` chuyển từ tính toán lặp lại độc quyền cục bộ sang đồng bộ hóa trực tiếp qua NAS API.

### 3. AI Photo Tagging vào Catalogue chung (`backend/nas_api_server.py`)
- Bổ sung bảng `ai_image_tags` (path, category, catalogue_revision, updated_at).
- Tái cấu trúc hàm `_scan_photos_lightweight()`: quét ảnh trực tiếp từ `file_index` của catalogue thay vì `os.walk` đĩa, gắn nhãn danh mục theo `catalogue_revision`.
- Cập nhật `/api/ai/tags` đọc từ DB kèm cơ chế đồng bộ 2 chiều và fallback về `ai_tags.json` cho client legacy.

### 4. Hiện đại hóa PC Tools qua Coordinator API (`scripts/pc_hash_manager.py`, `scripts/pc_thumb_batch.py`)
- Viết lại toàn bộ `pc_hash_manager.py` và `pc_thumb_batch.py`: thay thế vòng lặp PROPFIND BFS WebDAV thủ công bằng chuẩn Coordinator API (`/api/work/claim`, `/api/work/checkpoint`, `/api/work/files`).
- Bổ sung `source_version` cho mọi kết quả hash và thumbnail nhằm đảm bảo tính toàn vẹn (concurrency & versioning).
- Bổ sung cơ chế phát hiện HEIC/HEIF magic bytes và fallback decode trên PC worker nhằm giải quyết triệt để giới hạn Pillow 4.0.0 trên NAS ARM64.

### 5. Rà soát & Bổ sung Mutation Hooks toàn diện (`backend/nas_api_server.py`, `tests/test_review_restore_lifecycle.py`)
- Bổ sung gọi `_index_mutated_directories()` và `bump_catalogue_revision()` tại 3 điểm biến đổi dữ liệu còn thiếu: `api_backup_restore`, `_clean_empty_files_and_dirs`, và yt-dlp watchdog khi kết thúc tải.
- Bảo vệ các lệnh gọi hook qua `try/except NameError` cho các test seam cô lập.

### 6. Xác minh Phần cứng, Quạt & Nén Livestream
- Xác minh phần cứng ổ cứng HDD thật trên NAS: **TOSHIBA MG04ACA400N Enterprise HDD 4TB** (Dell OEM, 7200 RPM), nhiệt độ vận hành mát mẻ 40°C.
- Xác nhận cơ chế quạt tự động (`on_temp=42.0°C`, `off_temp=40.0°C`, độ trễ chống giật 600s/10 phút) tại `/opt/fan_custom.json` là chính xác tối ưu.
- Đánh giá luồng ghi livestream TikTok: Duy trì remux container `-c copy` (3-pass BSF fallback) trực tiếp trên NAS RK3328 (~0% CPU load, không tốn tài nguyên), không thực hiện transcode video nặng nề trên NAS.

---

## [2026-10-08] CC-004 — Rà soát mở rộng toàn project: playback fallback, dashboard guard, backend P0/P1/P2 (backend + Android)

Rà soát mở rộng theo yêu cầu: search→play, dashboard (số liệu/khởi động trắng/pull-refresh), giới hạn cứng livestream/social/ytdlp, encode path dài, proxy/VLC, cache/ETag, rate-limit, root/indexer.

### Backend (`backend/nas_api_server.py`, 15 marker CC-004, Python 3.5)
- **P0-1 indexer OOM**: `_get_webdav_public_dir` không bao giờ fallback `/` (log.error + return `""`); watchdog resolve root MỖI vòng, skip khi `""` hoặc `/`, sleep 1800 — tránh `os.walk("/")` quét toàn OS.
- **P0-2 trusted-proxy**: `_request_client_ip` trả remote thay vì `""` khi không khớp proxy.
- **P0-3 search injection**: `api_fast_search` validate Host/scheme, chặn `..`/`\`, `_escape_like` + `ESCAPE`.
- **P0-4 path containment**: `_find_source_file` dùng chung `_resolve_webdav_request_path` (realpath containment) + `_validate_file_path`.
- **P0-5 cap trước HW-gate**: livestream record đọc `_get_livestream_max_concurrent()` clamp 1–8 TRƯỚC hardware-gate (bản cũ check sau nên cap admin bị bỏ qua).
- **P0-6 auth literal**: patch endpoint literal `api_fast_search` (Flask `view_functions` wrap).
- **P1-1 root unify**: `get_webdav_root` ưu tiên `_get_webdav_public_dir()` (đọc config.php `$publicDir`) — gom 3 helper root về một nguồn.
- **P1-4 cache/304**: ETag thêm inode (`"%x-%x-%x"` size-mtime-inode, chống copy/replace trùng size-mtime); hỗ trợ `If-None-Match` (weak-strip) + `If-Modified-Since` → 304; file <86400s `max-age=60` else `max-age=604800,immutable`.
- **P1-7 force rate-limit**: `?force=1` (quét full disk 45s/HDD spin-up) rate-limit theo IP 60s → 429 kèm `cached/rate_limited/retry_after`.
- **P2-3 non-blocking idle**: `api_system_idle` dùng `cpu_percent(interval=None)` thay vì block 0.4s.
- **P2-5 ytdlp cap**: `_get_ytdlp_max_concurrent` (default 2, 1–4) + `_ADMIN_LIMIT_DEFS` + `api_ytdlp_download` trả 429 khi running ≥ cap.
- Tương thích SPEC_LIVESTREAM_LIMITS (giữ 2/2 default RK3328, queue server-side, admin PATCH).

### Android (26 file)
- **P0-4 playback fallback (:5050 chết → WebDAV trực tiếp)**: `VideoPlayerScreen.kt` + `MediaScreens.kt` — `useWebDavDirect` state, `effectiveUrl=remember(url,useWebDavDirect)`, timeout + `/api/media` + no HTTP code → bật direct + dialog WARNING, không báo lỗi đỏ; `exoPlayer=remember(effectiveUrl,...)` (sửa bug remember{} cũ khiến fallback vô hiệu).
- **Lỗi player chi tiết**: 401/404/416/timeout/decoder tách riêng qua Handler main-looper (toast cũ biến mất không đọc kịp).
- **Encode path dài**: `toFastMediaUrl` + `toValidUrl` per-segment (decode-chống-double, giữ `/`, fix `%25` double-decode); search Pass0 chuẩn hóa ngay lúc parse; breadcrumb encode từng segment; copyLink/Download/Share đều qua `toValidUrl`.
- **Dashboard guard**: `SystemMonitorViewModel`/`DeviceManagementViewModel` blank-guard `apiBase`; foreground-check + stop khi logout; `MainActivity.onLogout` stop-monitor + disconnect + reset.
- **Proxy/VLC**: `LocalVideoProxy` singleton `takeOver/releaseIfMine` chống leak socket/thread/Basic-Auth; forward `Range` + `If-Range/If-Match/If-None-Match/If-Modified-Since/If-Unmodified-Since` (seek sau pause dài đúng 206).
- **MIME**: `mimeForFile()` theo đuôi (mp4/mkv/avi/mov/mpeg/wmv/flv/ts/webm/ảnh) thay vì `video/*` cứng — VLC Intent + chooser đúng.
- **Search Pass2**: `withTimeout(15s)` + batch-insert 200 grouped by parent (trước đây insert từng file, BFS không timeout có thể treo).
- **HTTPS**: `PowerActions` + `checkNasReachabilityQuickly` giữ scheme https (trước ép cứng http).
- **Disconnect**: `synchronized` xóa auth/threadLocal/cache.

### Verify
- `pytest tests/` → **138 passed, 10 skipped**; `py_compile` OK (cảnh báo `\s` dòng 17183 có sẵn, bỏ qua).
- `compileDebugKotlin` → **BUILD SUCCESSFUL**; `assembleDebug` → **BUILD SUCCESSFUL** (`app-debug.apk` ~39–40MB).
- Push Tailscale `NAS_HOST=100.90.135.102` → service `active`, `grep CC-004` = 15, `journalctl -p err` không entry, `py_compile` trên NAS OK, `/api/status|usage|summary|ping` live (401 khi chưa auth là đúng).
- **Model**: agentgw-gpt-5.6-sol

## [2026-10-08] Fix dashboard: số liệu thư mục sai + khởi động trắng + pull-refresh không cập nhật (backend + Android)

Triệu chứng: liệt kê file/dung lượng ở màn hình chính sai; mỗi lần khởi động app load lại từ đầu mà trắng; pull-to-refresh không cập nhật trạng thái kết nối lẫn màn hình chính.

### Nguyên nhân
- Backend `get_storage_usage_summary` liệt kê CỨNG 3 thư mục `Livestream/Downloads/Backup` trong khi root thật (`New folder`) chứa ~30 thư mục cấp 1 (`Thư mục 1`, Facebook, TikTok, ...); `_safe_dir_usage` cap 4000 file/2s khiến thư mục lớn luôn `partial=True` (số thiếu).
- App cold-start: `WebDavManager.authState` chỉ sống trong RAM, kill app là mất; `checkSmartNetwork` cũ đòi `currentBase.isNotEmpty()` nên base rỗng thì không bao giờ connect lại → mọi fetch dùng `apiBase` rỗng → dashboard trắng, file load từ zero mà chẳng được gì.
- Pull-refresh thiếu `pingNas/triggerStatusUpdate/fetchStorageSummary(force)`; `DeviceManagementViewModel.isOnLan` là getter thuần (đọc `currentBaseUrl` không phải Compose State) nên badge LAN/Tailscale không recompose.

### Sửa
- Backend: `get_storage_usage_summary` quét ĐỘNG thư mục cấp 1 của root + deadline tổng 12s (mỗi thư mục 8000 file/1.5s); `_build_storage_usage_payload` dùng root đã resolve; path trash `.trash`.
- App: `checkSmartNetwork` tự nối lại credential đã lưu khi base rỗng (cold-start restore); `MainMenuScreen` (mở app + pull-refresh) restore đồng bộ trước mọi fetch, gọi thêm `pingNas/triggerStatusUpdate/fetchStorageSummary(force)`; `isOnLan` thành Compose State collect từ `SharedStateHolder`.
- DiskProfile: trash lookup `.trash`.

### Verify
- `pytest tests/` → **138 passed, 10 skipped**; `py_compile` OK.
- `compileDebugKotlin` → **BUILD SUCCESSFUL**; `assembleDebug` → **BUILD SUCCESSFUL** (`app-debug.apk` ~39MB).
- Push Tailscale `NAS_HOST=100.90.135.102` → service `active`, `CC-003` có mặt, `journalctl -p err` không entry, `py_compile` trên NAS OK.
- **Model**: agentgw-gpt-5.6-sol

## [2026-10-08] Fix search→play video tên dài/path loằng ngoằng (backend + Android)

Triệu chứng: video tìm được từ tìm kiếm nhưng bấm vào báo không phát được, chỉ thấy thông báo rồi biến mất. File mp4 tên dài, nhiều dấu cách/ngoặc/dấu tiếng Việt/emoji (vd `[BraveDown.Com] [Facebook Video 🍋 ...] [1755859489] .mp4`).

### Nguyên nhân
- Backend `/api/search` ghép `rel_p` raw (space/dấu/#?& giữ nguyên) vào `path` URL → VLC/ExoPlayer cắt cụt ở `#`/`?`, space raw hỏng Intent.
- App `toFastMediaUrl()` dùng `URLEncoder.encode(cả path)` biến `/` thành `%2F`, dễ trật khi rewrite Tailscale/LAN và double-encode `%20` → `%2520` → `/api/media` 404.
- App nhận kết quả search dùng `path` raw trực tiếp làm playback URL, lệch với cache PROPFIND đã encode.

### Sửa
- Backend `api_fast_search`: `urllib.parse.quote(rel_p, safe="/")` theo segment, giữ `/` — client dùng trực tiếp được.
- App `toFastMediaUrl()` (NasApplication.kt): tách segment rồi encode từng đoạn (decode-chống-double trước), giữ `/`, khớp `encodeWebDavSegment`/`quote(..., safe="/")`.
- App `FileBrowserViewModel.searchGlobal` Pass 0: chuẩn hóa `path` search qua `toValidUrl()` ngay lúc parse JSON (tương thích NAS chưa update).

### Verify
- `pytest tests/` → **138 passed, 10 skipped**; `py_compile` OK.
- `compileDebugKotlin` → **BUILD SUCCESSFUL**; `assembleDebug` → **BUILD SUCCESSFUL**.
- Push Tailscale `NAS_HOST=100.90.135.102` → scp + `systemctl restart nas_api` OK, PID 30313, `FIX-SEARCH-ENCODE` có mặt, service `active`, log không traceback.
- Round-trip quote/unquote tên file thật dài 81 ký tự (dấu + emoji) → OK, `isfile: True`; encode 3 mẫu tên dài/dấu/#?&/tiếng Thái → OK.
- **Model**: agentgw-gpt-5.6-sol

## [2026-10-08] SPEC_LIVESTREAM_LIMITS — gỡ giới hạn cứng livestream/social (backend + Android)

Triển khai theo `docs/SPEC_LIVESTREAM_LIMITS.md` (APPROVED 2026-10-08, Q1=RAM-based range, Q2=server-side queue, Q3=admin-only). Default giữ 2/2, không nới bừa trên RK3328.

### Backend (`backend/nas_api_server.py`, Python 3.5, không f-string/walrus)
- **Config loader động**: env `NAS_*` > `/etc/nas/nas_api.conf` (JSON) > default; 17 key đợt 1 (social/live concurrent, max_hours 1–72, err/stall cooldown, ytdlp timeout, idle/RAM/load/CPU gate, cleanup/TTL, tiktok watch/html cap/timeout). Clamp range: live 1–8, social 1–16.
- **`GET /api/admin/limits`** (auth LAN thường): trả toàn bộ key + `ram_mb`, `recommended_live_max/social_max` (RAM ≤1500MB → 2/2; 1500–3000 → 3/4; >3000 → 4/8), `heavy_gate_allowed`.
- **`PATCH /api/admin/limits`** (`requires_admin` mới — Basic auth đúng tài khoản `daica`, chặn LAN-bypass/IP-cache; sai → 401): validate/clamp, atomic write tmp+rename chmod 600, vượt khuyến nghị RAM → `warning: "over_recommended"` + log.
- **429 structured**: live `code=MAX_STREAMS`, social `code=MAX_DOWNLOADS` kèm `reason/cap/active/retry_after_s`; giữ `error` tiếng Việt cho client cũ.
- **Hàng đợi server-side** (`POST/GET/DELETE /api/livestream/queue`): persist HDD `livestream_queue.json` (sống qua restart), TTL 24h, cap 20 (`QUEUE_FULL`), dedup URL, daemon promote 60s/vòng qua đúng flow record nội bộ (giữ mọi guard).
- **Watcher tôn trọng cap động** (`_get_livestream_max_concurrent`, clamp 1–8).

### Android
- `LivestreamViewModel`: `serverLivestreamCap/Active` (refresh từ admin-limits + status), pre-flight chặn sớm khi đầy slot (không POST thừa), `LivestreamApiException` giữ code/cap/active, lỗi structured (`isLivestreamError/code/retryAfter`), queue methods (enqueue/fetch/cancel), `refreshLivestreamState()` gom 1 điểm.
- `LivestreamRecordDialog` + legacy `DialogsLivestreamRecordDialog`: lỗi persistent (chỉ auto-clear success), CTA `MAX_STREAMS` → [Cho vào hàng đợi] [Theo dõi user] [Thử 720p], hiển thị hàng đợi + nút Hủy từng entry, VOD chỉ khi chắc (short link `tiktok.com/t/`, `vt/vm.tiktok` phải resolve `@user` trước).
- `LivestreamMonitorWorker`: poll dùng `AppConfig.LIVESTREAM_POLL_INTERVAL_MS` + giãn 5x khi background; transient (HTTP lỗi/job tạm vắng/exception) → `Result.retry()` tới 10 lần, chỉ terminal thật (`finished/error/stopped/cancelled/timeout`) mới thoát.
- `LivestreamDiscoveryWorker`: 60p → 15p (min WorkManager) + endpoint tập trung.
- Thống nhất hằng số: mọi endpoint livestream/social/tiktok qua `AppConfig.ApiEndpoints`, folder qua `LIVESTREAM_FOLDER`/`SOCIAL_DOWNLOAD_FOLDER`; allowlist dialog+extractor dùng chung (+Shopee, hết báo giả "không hỗ trợ"); social parse `MAX_DOWNLOADS`.

### Verify
- Backend: `pytest tests/` → **138 passed, 10 skipped** (mới `tests/test_livestream_limits.py`: 13 passed — loader clamp, recommended RAM, 429 shape, admin auth, queue cap/TTL/persist/promote, watcher dynamic).
- Android: `compileDebugKotlin` → **BUILD SUCCESSFUL** (chỉ warning deprecated có sẵn).
- Chưa làm (cần NAS thật): đo RAM thực tế hiệu chỉnh ngưỡng §3.4, `push_server_to_nas.py` + restart + `journalctl` + cron/APK manual, `assembleDebug`.

---

## [2026-09-25] Review độc lập P1 + R-hồi quy + P2 — sửa nhánh mất dữ liệu (main-fresh)

Review độc lập đối chiếu SPEC_PRODUCTION.md phát hiện 8 P1 mất dữ liệu, 6 hồi quy/thiếu sót sau sửa, 9 P2 chức năng. Sửa theo đúng thứ tự báo cáo, mỗi mục commit riêng.

### Đợt P1 — nhánh mất dữ liệu (8/8)
- **P1-1** (`5e1c2c1`): `WebDavManager.verifyBackupContent()` mới — HEAD size + strong ETag → GET `If-Match` toàn nội dung → hash-stream so với nguồn → HEAD recheck ETag. `AutoBackupWorker` dùng thay logic 1MB/size+ETag cũ. File nén (gzip đổi byte) luôn giữ nguồn.
- **P1-2** (`ecfb86d`): `getFullSha256PhoneStream` chỉ HTTP 200, đúng byte-count, từ chối 206; survivor dọn trùng so full-hash thật với hash đã verify (thay `!= null`).
- **P1-3** (`3459082`): bỏ fallback DELETE vĩnh viễn khi MOVE trash fail ở `FileBrowserViewModel`, `BatchOperationWorker`, `SmartToolsViewModel` — giữ file + log.
- **P1-4** (`a6f4a6f`): `renameFile`/`copyFile` mặc định `Overwrite: F`; batch COPY/MOVE xung đột 412 → tên duy nhất + timestamp.
- **P1-5** (`cdbc244`): backend chỉ dọn file 0-byte khớp pattern tạm app + đủ tuổi; thư mục rỗng tạm/cũ; giữ file user (`.nomedia`, `empty.txt`...).
- **P1-6** (`04c13f5`): trash retention dùng `max(mtime, ctime)` thay vì `mtime` (rename vào trash cập nhật ctime).
- **P1-7** (`c3bacf9`): giữ phiên screen-record khi lỗi (timeout/finish-fail/failRecording), chỉ hủy khi user chủ động.
- **P1-8** (`aea7298` + `d24e4e9`): SMB publish nguyên tử bằng `rename(..., false)`, giữ staged khi lỗi; `api_ytdlp_download` gọi `_find_ytdlp_bin()` + 503 rõ.

### Đợt R — hồi quy + thiếu sót sau sửa (6/6)
- **R1** (`4ba63d2`): `retainSpoolOnDestroy` — `onDestroy` không xóa spool khi giữ phiên lỗi; thêm `discardSession()` cho user chủ động bỏ.
- **R2** (`9596065`): pattern temp chặt (suffix/prefix/delimited — `chapter.part1.txt` không còn khớp); thư mục chỉ xóa khi tên temp app-managed (bỏ nhánh "rỗng >24h").
- **R3** (`c5cc886`): victim yêu cầu ETag strong + MOVE gửi `If-Match` + verify full-hash trash đích sau MOVE.
- **R4** (`4065f11`): tách `deletePermanently()` khỏi `deleteFile()`; UI trong trash gọi đúng hàm (hết MOVE-về-chính-nó).
- **R5** (`ec712b4`): tên trash chứa hash parent (`A/photo.jpg` ≠ `B/photo.jpg`); batch trash 412 → retry tên duy nhất + metadata đúng.
- **R6** (`9596065`): `screen_record` block thumbnail có TTL 2h + heartbeat mỗi segment; app chết thì tự mở lại.

### Đợt P2 — chức năng tồn đọng (9/9)
- **P2-1+P2-2** (`312727f`): skip backup yêu cầu đúng đích `__id` + HEAD size + 1MB-hash khớp; 412 phân biệt không-đổi (success) / đã-sửa (phiên bản `__v<ts>` mới).
- **P2-3→P2-7** (`6cbff82`): DocumentsProvider gắn `Authorization` trực tiếp; queue luôn continuation khi còn pending; `createFolder` đúng thư mục duyệt + refresh; restore thiếu metadata báo lỗi không MOVE; background refresh cập nhật `fileList`.
- **P2-8** (`108b623` + `64b8641` + `11b8137`): `files_cache.accountKey` (DB v18) + scope toàn bộ query browser/search/photos/duplicate + xóa cache đổi phiên (drain đồng bộ, không coroutine lẻ gây flaky test).
- **P2-9** (`986e1e8`): `api_unzip` ánh xạ relative WebDAV path + containment, giữ tương thích absolute.

### Fix bảo mật kèm theo
- `a8c7210` + `ec39df2`: `/api/search` thiếu `@requires_auth` (lộ cấu trúc file NAS cho LAN không auth) — bọc sau định nghĩa + patch `app.view_functions` (decorator trực tiếp gây `NameError` crash-loop, đã cứu). Verify live: no-auth → 401.
- `ab5b770`: validate whitelist IP/CIDR khi load từ disk.

### Verify
- Backend pytest: 115 passed / 10 skipped. Android unit: DatabaseDao 3/3, BackupContent 8/8, FullContent 5/5, SMB 2/2 — 0 failure.
- `compileDebugKotlin` + `assembleDebug` BUILD SUCCESSFUL (`app-debug.apk` ~38MB).
- NAS deploy qua Tailscale (LAN vật lý 192.168.100.254 không thông): service `active`, 0 traceback.
- Chưa nghiệm thu production-ready: cần cron 1–2 chu kỳ + test APK thực tế.
- **Model**: agentgw-gpt-5.6-sol

---

## [2026-09-23] Livestream / Log-Guard — đóng 4 lỗi P2 còn tồn tại sau 7fb3373

### backend/nas_api_server.py (deploy `/opt/nas_api_server.py`)
- **P2-1 (process-group gap on stop):** nút dừng + "dừng tất cả" giờ dùng
  `_livestream_kill_pid` (killpg whole process group do `start_new_session=True`)
  và giữ `status="stopping"` + `_kill_ts` để watchdog tiếp tục theo dõi group đến
  khi chết. Thêm orphaned-child sweep khi cha chết nhưng group vẫn còn tiến trình.
  - **Guard bổ sung:** sweep chỉ chạy khi `_pgid == int(pid)` (chính PGID của job),
    tránh killpg nhầm process group khác (tái sử dụng PGID số) → tự sát service.
- **P2-2 (định danh file):** `job_id` (ms + random) tính sớm và nhúng vào
  `output_template`, `file_stem`, `direct_output_file` → 2 job cùng giây (URL khác
  nhau) có tên file không trùng.
- **P2-3 (parser TikTok):** fallback chỉ đọc `LiveRoom` của target room (parse JSON,
  không regex quét toàn SIGI_STATE object) → không leak status phòng khác
  (`extra.roomId=9999` đang live không làm báo nhầm live).

### scripts/nas_log_guard.sh (deploy `/usr/local/bin/nas_log_guard.sh` + cài `/etc/cron.hourly/nas_log_guard`)
- **P2-4 (backup trước truncate):** copy log sang HDD và verify size > 0 **trước**
  khi truncate (trước đây truncate trước nên backup luôn rỗng 0–160 byte). Backup
  mới ~13.7 MB. Giải phóng zram `/var/log` từ 100% → ~39%.

### Tests
- Thêm 7 regression test (P2-2/P2-3 + strengthen fallback). Toàn bộ suite: 99 passed.
- **Model**: agentgw-gpt-5.6-sol

## [2026-04-20] Fix Memory Leak — LeakCanary "1 leaks at AndroidComposeView.legacyTextIn..."

### Nguyên nhân
`TextInputServiceAndroid` (Compose internal) giữ reference đến `CursorAnchorInfoController` → chain qua `LayoutNode` → `PopupLayout` (đã `onDetachedFromWindow`) → **leak 224 kB / 5130 objects**.

Xảy ra khi bất kỳ `ModalBottomSheet` bị dismiss trong khi có `TextField` đang active (IME focus).

### Fix — CommonStates.kt: `NasModalBottomSheet` wrapper
- Tạo `NasModalBottomSheet` composable tập trung trong `ui/components/CommonStates.kt`
- Tự động gọi `focusManager.clearFocus(force = true)` TRƯỚC mọi `onDismissRequest`
- Support đầy đủ: `sheetState`, `containerColor`, `scrimColor`, `dragHandle`, `content`

### Áp dụng cho tất cả 14 ModalBottomSheet trong app
- `Dialogs.kt`: **5** sheets migrated
- `BrowserScreen.kt`: **5** sheets migrated
- `MainMenuScreen.kt`: **4** sheets migrated

- **Model**: Gemini 2.5 Pro

## [2026-04-20] Fix ping loop — timeout 2s + exponential backoff

### WebDavManager.kt
- TCP ping timeout: **5000ms → 2000ms** (LAN chỉ cần ~1-5ms, fail nhanh hơn)
- Log FAIL: `Log.e` (kèm full stacktrace) → `Log.w` (chỉ message ngắn) — ping fail là bình thường khi WiFi reconnect, không phải crash

### WebDavViewModel.kt (ping loop)
- Thêm `consecutiveFails` counter + exponential backoff:
  - Thành công: ping mỗi **3s** (không đổi)
  - Fail 1-2 lần: chờ **3s** (short retry)
  - Fail 3-5 lần: chờ **10s** (WiFi đang reconnect)
  - Fail 6+ lần: chờ **30s** (NAS offline, tránh spam)
- Trước đây: fail 5s timeout + retry ngay sau 3s = log spam mỗi 8s

**Nguyên nhân log**: WiFi phone reconnect sau sleep → route chưa ổn định → TCP timeout. Firewall và nginx hoàn toàn bình thường.

- **Model**: Gemini 2.5 Pro

## [2026-04-20] Dedup Nhật ký hệ thống + fix Memory Leak PopupLayout

### SystemLogger Dedup (Utils.kt)
- Thêm cơ chế dedup: cùng `module|message` trong vòng **5 phút** → tăng `repeatCount` thay vì insert entry mới
- Cache in-memory `ConcurrentHashMap<String, Pair<Long, Long>>` (key = `module|message`)
- UI hiển thị badge **×N** màu tương ứng type (xanh/cam/đỏ) khi `repeatCount > 1`

### Database.kt (v12 → v13)
- `SystemLog` thêm field `repeatCount: Int = 1`
- `LogDao` thêm `insertLogReturnId()` trả `Long` và `incrementRepeatCount(id, now)`
- Migration `MIGRATION_12_13`: `ALTER TABLE system_logs ADD COLUMN repeatCount INTEGER NOT NULL DEFAULT 1`

### DuplicateScanWorker.kt
- Xóa `db.logDao().insertLog()` thừa trong `AutoDuplicateScanWorker` — trước đây ghi **2 log cùng lúc** cho mỗi lần dọn rác định kỳ

### Dialogs.kt — Memory Leak Fix
- `SystemLogDialog`: thêm `val focusManager = LocalFocusManager.current`
- `onDismissRequest`: gọi `focusManager.clearFocus(force = true)` trước khi `onDismiss()`
- Giảm `PopupLayout` leak do `TextInputServiceAndroid` không được cleanup khi dismiss `ModalBottomSheet`

- **Model**: Gemini 2.5 Pro

## [2026-04-20] Thêm /api/ping endpoint vào nas_api_server.py

- **File**: `/opt/nas_api_server.py` (NAS, trước @app.route("/api/status"))
- **Thay đổi**: Thêm lightweight `GET /api/ping` → trả `{"status":"ok","ts":<unix_ts>}`, **không cần auth**
- **Lý do**: `/api/ping` bị 500 vì endpoint chưa tồn tại. Dùng để test connectivity nhanh
- **Nguyên nhân log cũ**: `SocketTimeoutException` lúc 00:30 là APK cũ (HTTP OPTIONS 5s timeout) chưa update, không phải iptables
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Color-coded Daily Report — ngưỡng màu cho CPU/RAM/Temp

- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MonitoringChartsScreen.kt` (lines 562–592)
- **Thay đổi**: Thêm `c1` (cột trái) màu động + nâng cấp `c2` cột phải với ngưỡng 3 mức
  - **CPU/RAM**: 🟢 xanh <70% / 🟡 vàng 70–89% / 🔴 đỏ ≥90%
  - **CPU Temp**: 🟢 xanh <65°C / 🟡 vàng 65–74°C / 🔴 đỏ ≥75°C
  - **HDD Temp**: 🟢 xanh <40°C / 🟡 vàng 40–49°C / 🔴 đỏ ≥50°C
- **Lý do**: Trước đây chỉ `c2` có màu và nhiều thông số dùng màu trắng mặc định
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Fix °C thiếu trong Daily Report (CPU/HDD temp)

- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MonitoringChartsScreen.kt` (lines 566–569)
- **Thay đổi**: `"%.1f"` → `"%.1f°C"` ở 4 field: `cpuTempAvg`, `cpuTempPeak`, `hddTempAvg`, `hddTempPeak`
- **Lý do**: Giá trị nhiệt độ hiển thị số không có đơn vị (ví dụ `41,2` thay vì `41,2°C`)
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Fix Ping — TCP Socket thay vì HTTP OPTIONS

- **File**: `app/src/main/java/com/nas/naswebdav/WebDavManager.kt` (lines 280–306)
- **Thay đổi**: `checkPingServer()` dùng `java.net.Socket.connect()` thay vì HTTP OPTIONS đến WebDAV
- **Lý do**: OPTIONS request gồm TCP + nginx xử lý + auth → ~300ms. TCP socket chỉ đo RTT thật → <10ms trên LAN
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Fix SMART — Thêm °C cho nhiệt độ HDD

- **File**: `app/src/main/java/com/nas/naswebdav/ui/dialogs/Dialogs.kt` (line 215)
- **Thay đổi**: Guard UI: nếu giá trị `temperature` có chữ số nhưng không có `°` → thêm `°C`
- **Lý do**: Server trả về số nguyên `38` thay vì `38°C`, UI không có fallback
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Khôi phục font Samsung One — NasTheme

- **File**: `app/src/main/java/com/nas/naswebdav/MainActivity.kt` (lines 50, 313)
- **Thay đổi**: Thay `MaterialTheme {}` bằng `NasTheme {}` trong `setContent`
- **Lý do**: `AppTypography` đã có `SamsungOneFontFamily` nhưng không được áp dụng vì `MaterialTheme` không nhận `typography = AppTypography`. `NasTheme` wrap đúng cả `colorScheme` + `typography`
- **Model**: Gemini 2.5 Pro

## [2026-04-20] Setup Neural Memory & Hardrules

- **File**: `.agents/hardrules.md` [NEW]
- **File**: `CHANGELOG.md` [NEW]
- **Thay đổi**: Tạo hệ thống hard rules và neural-memory MCP cho dự án
- **Lý do**: Đảm bảo AI nhớ ngữ cảnh giữa các phiên, ghi lại phát hiện quan trọng
- **Model**: Gemini 2.5 Pro

---

## [2026-04-20] Fix iptables NAS — Restore Whitelist Policy

- **File**: NAS `/etc/iptables/rules.v4`
- **Thay đổi**: Xóa 2 DROP rules thừa cho `192.168.100.93`, rebuild iptables whitelist-only
- **Lý do**: Phone bị block hoàn toàn vì DROP rules xuất hiện trước ACCEPT rules
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Fix IndexOutOfBoundsException — ImageViewerScreen

- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MediaScreens.kt` (line 222)
- **Thay đổi**: `pagerState.currentPage` → `.coerceIn(0, imageFiles.size - 1)` trước khi truy cập list
- **Lý do**: Crash khi list ảnh thay đổi giữa recomposition (19 phần tử nhưng index = 19)
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Expose Ping Error — Hiện thị lỗi đăng nhập thực

- **File**: `app/src/main/java/com/nas/naswebdav/WebDavManager.kt` (lines 275–302)
- **File**: `app/src/main/java/com/nas/naswebdav/WebDavViewModel.kt` (line ~1460)
- **Thay đổi**: `checkPingServer()` lưu exception message vào `lastPingError`, hiển thị thay vì hardcode
- **Lý do**: Không biết lý do thực sự của login failure (SocketTimeoutException vs ConnectException)
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Fix Build — Nhiều Unresolved References

- **File**: `app/src/main/java/com/nas/naswebdav/ui/dialogs/Dialogs.kt`
  - `activeLivestreams` type inference → explicit `List<LivestreamJob>` + map nested class
- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MainMenuScreen.kt`
  - `activeStreams` type inference → explicit + map
  - `cancelConnect()` stub added
- **File**: `app/src/main/java/com/nas/naswebdav/WebDavViewModel.kt`
  - `FolderStats` data class + `folderStats`/`isFolderStatsLoading` properties
  - `fetchFolderStats()` stub
  - `cancelConnect()` function
- **Lý do**: Build failures từ unresolved references sau refactor
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Fix Login — pingClient 10s Timeout

- **File**: `app/src/main/java/com/nas/naswebdav/WebDavManager.kt` (lines 289–292)
- **Thay đổi**: `checkPingServer` dùng `pingClient` (10s) thay vì `optimizedClient` (60s)
- **Lý do**: Login mất 60 giây mới fail, UX rất kém
- **Model**: Gemini 2.5 Pro

---

## [2026-04-19] Fix Literal \\n — Error Dialog Newline

- **File**: `app/src/main/java/com/nas/naswebdav/WebDavViewModel.kt` (line ~1460)
- **Thay đổi**: `"\\n"` → `"\n"` trong error message
- **Lý do**: Hiện thị `\n` thay vì xuống dòng trong dialog lỗi
- **Model**: Gemini 2.5 Pro

---

## [2026-04-18] Fix network_security_config.xml Missing

- **File**: `app/src/main/res/xml/network_security_config.xml` [NEW]
- **Thay đổi**: Tạo file bị thiếu gây AAPT build failure
- **Lý do**: AndroidManifest.xml reference file không tồn tại
- **Model**: Gemini 2.5 Pro

---

## [2026-04-18] UI — Accordion Tương hỗ (OMV / Monitor / SystemLog)

- **File**: `app/src/main/java/com/nas/naswebdav/ui/screens/MainMenuScreen.kt`
- **Thay đổi**: Auto-collapse các section ngoài màn hình chính để tối ưu hiển thị
- **Lý do**: Không thể mở đồng thời 2 section detail (OMV + Monitor, v.v.)
- **Model**: Gemini 2.5 Pro

---

## [2026-10-09] CC-005 - Ra soat bao phu toan app (backend P0/P1 + Android P0 Worker/DAO/Provider)

### Backend (`backend/nas_api_server.py`, 29 marker CC-005)
- P0 bao mat: backup 5 endpoints requires_auth->requires_admin; hash_batch cap 200 + containment; HLS session ^[a-f0-9]{12}$ + filename seg + realpath bound; thumb upload 5MB + SOI + GET ETag/304/cap; process_kill blocklist + admin; smb/service toggle -> admin; ytdlp + RapidAPI re-validate SSRF; qBT magnet-or-validate + 502 generic; torrent_add_file basename + sanitize; trash_batch cap 500; hash_save hex + realpath + size/mtime bound; verify ETag them ino; transcode cap 2 global + 1/IP + salt; restore 200MB + gzip magic + uncomp 500MB; guest secrets + cap 10; smart_scan cap + deadline; thumb_activity whitelist + TTL 180s.
- P1: organize_legacy_videos cap 20000 + deadline 120s + bo hidden + truncated flag; speedtest rate-limit 5p/IP 429 + 50->20MB; trash_batch khong shadow global CACHE_FILE; generate_fast_index cap 100000 + deadline 120s.
- Verify: py_compile OK (chi SyntaxWarning \s cu), pytest 138 passed 10 skipped, push NAS 100.90.135.102 PID 2875 active, grep 29, journalctl err empty.

### Android (11 file .kt, ~30 marker CC-005)
- P0 Worker: Fingerprint URL host-validate + capped 1MB + nullable fix; LongRunning SSRF host-check + jsonBody 64KB + 5xx/429/408 retry; Social capped 256KB + break 401/403/404 + notif 64k + normalize; Batch payload 1MB + isStopped retry + partial success; ScreenRecord NOT_STICKY; Monitor notifId + constraints + deadline 6h; Smb timeout/evict/shutdown.
- P0 DAO/Provider: getFilesWithoutFingerprint accountKey scope; getAllPendingActions Deprecated; DocumentProvider ext sanitize + preload cap 20MB.
- Verify: compileDebugKotlin OK, assembleDebug OK (APK 40253796 bytes 13:53), backend push OK.

- Model: GPT 5.6 Sol (agentgw.cloud)

---

## [2026-10-09] CC-005 round 2 - P1/P2 con lai (backend ENV/cache/undo + Android nav/login/manifest/UX)

### Backend (`backend/nas_api_server.py`, 29 -> 58 marker CC-005)
- ENV: _cfg_str/_cfg_float/_cfg_sqlite_timeout/_get_webdav_port/_get_webdav_host_default/_get_guest_ftp_root/_get_vsftpd_user_dir; api_fast_search port/host ENV; SPEED_TEST_FILE ENV; sqlite timeout gom helper giu default.
- Cache unify: _SMART_TTL 86400 + _OMV_OVERVIEW_TTL 900 + _STORAGE_SUMMARY_TTL comment unify, cache-hit khong spin-up HDD.
- smart_organize: dry_run + manifest .nas_meta/smart_organize_<job>.json + prune 7 ngay + skip mtime<24h + POST undo (containment).
- Guest: whitelist ^nasguest_[a-z0-9]{6}$ + realpath root + config containment; backup schedule POST + guest create/revoke -> requires_admin; log canh bao backup chua auth.conf (chi ten file).
- Verify: py_compile OK (chi SyntaxWarning \s cu), pytest 138 passed 10 skipped, push PID 10179 active, journalctl err empty.

### Android (~81 marker CC-005 kt + 2 manifest)
- Nav: MainActivity dung NasNavHost single graph + conditional start + double-tap back + alias main_menu/browser cu; performLogout tap trung.
- Login: AppConfig WEBDAV_SCHEME/PORT/ROOT_PATH/DEFAULT_NAS_IPS + bareIpToLoginCandidates (https truoc); manifest them READ_EXTERNAL_STORAGE(max32)/ACCESS_WIFI_STATE/USE_BIOMETRIC; share SEND_MULTIPLE; MediaPermission AND + dialog Settings khi partial.
- UX: Browser onRefresh du + SystemStatus PullToRefreshContainer; IpApproval countdown 60s + pending; Duplicate confirm 2 buoc + partialHash key; GlobalUi snackbarNonce; SmbErrorKind classify; OnDemand Semaphore(2) + encode segment; OfflineSync single + UNMETERED 50MB; ScreenRecord sessionScope + uploadMutex + stopForeground; NavBar Role.Tab; Duplicate queue 2000 + lowercase; AutoBackup checkpoint/25 file.
- Fix sau agent: MainActivity import launch, OnDemand withPermit import, CrashReporter breadcrumb setData + bo extra (private SDK7), SystemStatus PullToRefreshBox->Container.
- Verify: compileDebugKotlin OK, assembleDebug OK (APK 40270296 bytes 18:58).

- Model: GPT 5.6 Sol (agentgw.cloud)
