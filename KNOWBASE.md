# NASWebDAV · Knowbase bàn giao

## Giao diện HTML5 ngày 2026-11-25

- Fix giới hạn 300 của danh sách HTML5: tổng số khớp tính trước khi cắt trang trên snapshot thư mục Android. Có Trang trước/Trang sau, giữ tối đa 300 mục mỗi trang. Chrome đạt fixture 650 mục. APK 1.3.204 (261125.1947) đã cài trên Galaxy S21 Ultra; thư mục NAS thật hiện 1.174 mục, trang 2 mở đúng, bộ lọc tìm được mục ngoài 300 mục đầu và xóa lọc khôi phục đủ 1.174 mục.
- Đã tích hợp #407 vân tay, #415 breadcrumb, #472 lưu trữ, #080 sao lưu và #277 hiệu năng. APK NASWebDAV #403 1.3.204 (261125.1922) đã cài Galaxy S21 Ultra. Trang thật hiển thị thẻ lưu trữ, trình duyệt tệp nhận breadcrumb, hình đồng bộ và đồng hồ hiệu năng mở được. Chrome 320 px kiểm tra trạng thái bằng fixture. Lượt kiểm tra chỉ đọc sau đó trên Galaxy xác nhận chọn nhóm hiện tỷ lệ, mở màn Hiệu năng và Đồng bộ, mở thư mục thật và dùng breadcrumb quay về gốc WebDAV. Chưa quét vân tay, truyền tệp sao lưu hay chạy benchmark thật.
- Sửa hiệu ứng HTML5 để cuộn số tồn tại qua cập nhật, thẻ chuyển động khi mở màn, nút phản hồi sau khi nhả tay. Bản NASWebDAV #403 1.3.204 (261125.1906) đã cài trên Galaxy S21 Ultra. WebView đang đăng nhập xác nhận các cột số CPU có thay đổi transform, thẻ chạy hiệu ứng khi mở Ứng dụng và nút Đổi chế độ xem chạy hiệu ứng sau chạm. Chrome 390 px đạt thử cập nhật xen giữa và giảm chuyển động.
- Mở rộng #426 cho số liệu HTML5 sinh động, #420 cho thẻ thao tác và #438 cho nút nhấn cùng phản hồi tải Docker. Chrome kiểm thử ở 390 px hai chế độ màu, bàn phím và giảm chuyển động. APK 1.3.204 (261125.1900) đã cài và mở trên Galaxy S21 Ultra, tài nguyên khớp checkout. Chưa thử các hiệu ứng sau khi đăng nhập NAS thật.
- Lucian #426 gắn vào 13 số liệu HTML5 trên dashboard, danh sách tệp và các hộp thoại tệp, sao lưu, xem trước tệp trùng. Chrome xác nhận mở lại, hover, giá trị đến trong lúc cuộn và giảm chuyển động. Đã đóng gói và cài thành công trên Galaxy S21 Ultra vào bản NASWebDAV #403, 1.3.204 (261125.1845). Chưa kiểm tra #426 sau khi đăng nhập NAS trên máy.
- Màn đăng nhập HTML5 dùng Lucian #403 tại `login.html` và `login-403.css`, nối với callback Android trong `NasHtmlLoginScreen.kt`. Chrome đạt kiểm tra ở 320 px cho hai chế độ màu, bàn phím, kết quả xác thực giả lập và giảm chuyển động. Đã cài và mở NASWebDAV #403 (1.3.204, 261125.1810) song song trên Galaxy S21 Ultra. Qua DevTools xác nhận login.html hiển thị thật và đã gắn cầu nối Android. Còn xác nhận đăng nhập với NAS thật.
- Trên nhánh `codex-wip-20261114`, HEAD `932f005`, thay đổi giao diện theo Lucian nằm trong working tree. Đặc tả tại `.specify/specs/html5-component-refresh.md`.
- Chrome kiểm tra bố cục hẹp/rộng bằng dữ liệu tổng hợp. APK debug 1.3.204 build thành công, tài nguyên HTML5 khớp checkout. Bản cài song song trên Galaxy S21 Ultra Android 15 vượt 6/6 kiểm tra WebView ở foreground với fixture tổng hợp. Bản đang dùng 1.3.203 có chữ ký khác. Chưa thử luồng kết nối NAS bằng bản cài song song.

Cập nhật ngày 2026-11-24. Đọc mục này cùng `AGENTS.md` trước khi tiếp tục trên checkout dùng chung `\\nguyentruong-pc\Android\NASWebDAV`.

## Checkout và phạm vi

- Nhánh đã xác nhận `codex-wip-20261114`, HEAD `74d844f`. Bản sửa UI/UX, Android replay và quét/dọn trùng nằm trong working tree cùng các WIP khác. Xác nhận lại HEAD, kiểm tra diff từng file và giữ nguyên WIP concurrent trước khi sửa.
- AutoBackup đang khóa. Lượt bàn giao này giữ phạm vi local và kiểm thử thao tác ghi bằng fixture tổng hợp. Việc cài APK lên máy đang dùng, deploy hoặc restart NAS, commit/push và thao tác ghi lên dữ liệu thật cần chỉ đạo riêng.
- Hộp thư [Codex → Antigravity](.collaboration/codex-to-antigravity.md), MSG-0003/0004 và MSG-0020/0021, cùng [ACK MSG-0006](.collaboration/antigravity-to-codex.md) lưu bối cảnh database/file-manager.

## UI/UX — đã sửa, còn xác nhận trên APK mới

| Hạng mục | Mã và hành vi |
| --- | --- |
| Quét trùng | `SmartToolsViewModel.kt`, `duplicateScanReadout` và `cleanupStatusText` hiển thị phạm vi WebDAV hay thư mục con, số tệp đã quét và tình trạng bằng chứng lỗi đọc. Lượt dọn rỗng có thông báo riêng. |
| Hộp thoại | `app/src/main/assets/html5/app.js`, `syncDialogs`, `closeTopModal` và `bindDialogNavigation` giữ focus, xử lý Escape, đóng lớp trên cùng và trả focus về điều khiển đã mở. `NasHtmlDashboard.kt` dùng `onModalVisibilityChanged` và Compose `BackHandler` để chuyển Back cho hộp thoại đang mở. |
| Trợ năng và bố cục | `index.html` và `style.css` thêm nhãn, điều hướng bàn phím, vùng chạm tối thiểu 48 CSS px cho năm nút quản lý tệp, phóng to và xử lý giảm chuyển động. `drawChart` giữ nhãn giờ trong canvas. |
| Trạng thái hệ thống | `app.js` Việt hóa Thumbnail và USB, lọc thông báo `Lỗi: null`. Docker tách đang tải, tải lỗi và danh sách rỗng qua `DeviceManagementViewModel.kt` và `NasHtmlDashboard.kt`. |

**Bằng chứng.** Lượt rà soát trước sửa mở 5/5 tab và 14 màn hình phụ trên Galaxy S21 Ultra, APK đang cài `1.3.203 (74d844f · 261124.1213)`, kết nối LAN bằng thao tác đọc. Bản APK đó tái hiện Back từ Hộp công cụ về Trang chủ. Chromium sau sửa dùng snapshot đọc từ NAS và kịch bản lỗi giả lập, đạt 30/30 tổ hợp bố cục ở 320–1024 px, mở 16/16 mục, đạt các kiểm tra focus, Escape, hộp thoại lồng, nhãn, vùng chạm, trạng thái và biểu đồ. Android đạt 25 pass, 0 fail, 0 skip. Full pytest đạt 341 pass, 0 fail, 6 skip với `TEMP` và `TMP` đường dẫn dài. Ba skip cần quyền symlink Windows và ba skip yêu cầu POSIX.

**Việc tiếp theo.**

1. Khi có chỉ đạo cài bản mới, ghi đúng `versionName` của APK rồi thử Back từ Hộp công cụ trong tab Ứng dụng. Kỳ vọng lần đầu chỉ đóng hộp thoại, giữ tab và trả focus. Kiểm tra tiếp hộp thoại lồng, TalkBack và pinch zoom trên WebView.
2. Xác nhận tìm kiếm toàn NAS, timeline và preview ảnh/video bằng thao tác đọc. Theo dõi các trạng thái rỗng, đang tải và lỗi thực sự trên thiết bị. Với Quét trùng, một báo cáo cho thư mục con hoặc thiếu `read_errors` phải được trình bày rõ về giới hạn phạm vi.
3. Giữ số liệu kiểm thử của APK cũ và bản source mới riêng. Các kết quả Chromium và Kotlin unit test chưa xác nhận Back trên APK mới chạy trên điện thoại.

## Database và dọn trùng — mã local và các giới hạn

- `FileMutationSync.kt` và `OfflineSyncWorker.kt` chia sẻ chuẩn hóa root, replay theo account/root, đọc được row `SYNC_MUTATION` cũ có root rỗng khi đúng scope, giữ dấu `+` và báo catalogue sau PUT của `UPLOAD_FAILED`. `FileMutationSyncReplayTest` chạy Room và MockWebServer.
- `backend/nas_api_server.py` gộp yêu cầu quét trùng cùng cấu hình đang chạy vào một `job_id`. Quyền hủy thuộc client khởi động. Reservation giữ đến khi Rust thoát. Fixture hai client xác nhận chỉ một lease tự dọn.
- Luồng quét NAS cấp snapshot `file_index` cho lệnh Rust `find-duplicates-catalogue`. Tự dọn yêu cầu danh mục đầy đủ, đúng revision và số lỗi đọc bằng 0. Luồng đơn và batch dùng chung `_duplicate_validate_quarantine` ngay trước unlink. Bằng chứng hash kiểm tra lease trên chính FD đã băm.
- Rust đã qua 16 unit test, kiểm tra target ARM64 và fixture endpoint chạy binary Windows mới. Đó là bằng chứng local. Phát hành tính năng quét mới cần đồng bộ backend, binary Rust ARM64 và APK. Chưa xác nhận quét/dọn mới trên hai APK thật. Khóa gộp áp dụng trong phiên đang hoạt động. Gửi lại sau phiên có thể mở lượt mới.

## Lệnh tái kiểm tra

- Git trên UNC nếu gặp dubious ownership — `git -c "safe.directory=%(prefix)///NGUYENTRUONG-PC/Android/NASWebDAV" status --short --branch`.
- JavaScript — `node --check app/src/main/assets/html5/app.js`.
- Android — `testDebugUnitTest --tests *NasCatalogueTest --tests *Html5BridgeContractTest --tests *VerifiedDuplicateRequestsTest --tests *AutoDuplicateCatalogueTest`. Bản đã chạy dùng JDK 21 và SDK có sẵn. Với UNC, gọi `gradlew.bat` qua `cmd.exe` sau `pushd`, dùng `--project-cache-dir` riêng nếu cache đang bị khóa.
- Python — `python -m pytest tests/ -v -rs`. Đặt `TEMP` và `TMP` về thư mục local dài trước khi chạy. Đặt `NAS_DUPLICATE_TEST_BINARY` trỏ đến CLI mới đã build nếu cần chạy ca tích hợp thực qua Rust.
