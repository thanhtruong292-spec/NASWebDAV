# NASWebDAV · Bàn giao

Cập nhật 2026-11-26 trên nhánh `codex-wip-20261114`. Đọc cùng `AGENTS.md`. Checkout UNC dùng chung với agent khác. Đối chiếu diff và [hộp thư phối hợp](.collaboration/codex-to-antigravity.md) trước khi sửa tệp chung.

## WebView và bằng chứng

| Phạm vi | Hành vi và kiểm tra |
| --- | --- |
| #403, #407 | Form đăng nhập và nút vân tay dùng callback Android. Đã mở trên Galaxy S21 Ultra. Chrome 320 px đã kiểm tra hai giao diện màu, bàn phím, giảm chuyển động và trạng thái đăng nhập giả lập. Chưa xác nhận quét vân tay thật. |
| #426, #420, #438 | Cuộn số khi mở màn hoặc khi dữ liệu đến, chuyển động thẻ và phản hồi nút sau khi nhả. Chrome 390 px kiểm tra cập nhật xen giữa và giảm chuyển động. Galaxy đã quan sát hiệu ứng số CPU, thẻ Ứng dụng và nút đổi chế độ xem. |
| #415 và phân trang | Breadcrumb có Home, bỏ nút Back bên trái và bỏ viền khung. Giới hạn 300 là kích thước trang. Galaxy thử thư mục NAS 1.174 mục, trang 2 và bộ lọc tìm được mục ngoài 300 kết quả đầu. Fixture Chrome 650 mục đã đạt. |
| #472 dashboard | Độ rộng theo số tệp từng loại từ catalogue, giữ dung lượng nhóm, tóm tắt quét và danh sách tệp lớn. Galaxy kiểm tra snapshot đang cập nhật với 469.179 tệp, trong đó 421.711 ảnh (89,88%) và 47.217 video (10,06%). Chrome kiểm tra tệp 0 B, dữ liệu rỗng và giảm chuyển động. |
| #080, #277 | Màn Đồng bộ NAS và Hiệu năng & Mạng mở được trên Galaxy. Chưa truyền tệp sao lưu hay chạy benchmark thật. |
| #408 | Menu tài khoản dùng dữ liệu thật, avatar thu gọn. Galaxy kiểm tra Back, Giới thiệu và Cài đặt Android. Chưa đăng xuất thật. |
| #436 | Xác nhận xóa một tệp, thư mục hoặc nhiều mục. Biểu tượng và tiêu đề căn giữa. Galaxy kiểm tra Hủy giữ lựa chọn và hiệu ứng nắp thùng rác/tờ giấy với bridge giả lập. Chrome kiểm tra 320 px, hai giao diện màu, Escape và giảm chuyển động. Chưa gửi lệnh xóa đến NAS. |
| Hộp thoại phản hồi | Thành công, Cảnh báo và Lỗi dùng bố cục #436. Thông tin thường vẫn hiện ngắn ở cuối màn hình. Galaxy kiểm tra ba tình huống sao chép giả lập, Đóng, Escape và Android Back. Chưa xác nhận focus trở về nút mở khi dùng Android Back. |
| #032 | Hộp tải lên dùng trình chọn nhiều tệp của Android, trạng thái và tiến độ từng tệp từ WebDAV, bỏ qua trùng tên, và đặt nút Thử lại ở mép phải. Galaxy đã kiểm tra mở/hủy trình chọn và đóng bằng Back. Các hàng tiến độ/thành công/thử lại trên Galaxy dùng fixture. Chưa thử PUT hoặc retry thật đến NAS. Kéo thả Android chưa được tích hợp. |

APK kiểm thử mới nhất đã cài qua ADB là `NASWebDAV-upload-032-uicheck.apk` (package `com.nas.naswebdav.uicheck`). APK đó có trước chỉnh sửa cuối cùng căn phải nút Thử lại. Ảnh và APK kiểm thử nằm trong thư mục build cục bộ `%LOCALAPPDATA%/NASWebDAV-build-20261125-173837`. Bộ pytest gần nhất cho #032 đạt 341, bỏ qua 7, một cảnh báo SQLite. Bản test cũ và dữ liệu giả lập không xác nhận thao tác ghi NAS.

## Phối hợp và việc còn mở

- Autocomplete #429 đang có trên checkout chung. MSG-0025/0026 ghi nhận build thành công và còn kiểm tra bố cục hẹp, bàn phím và thiết bị. Compose toast là WIP riêng của agent khác.
- WebView dùng `syncDialogs`, `closeTopModal`, bridge `onModalVisibilityChanged` và Compose `BackHandler`. Còn kiểm tra TalkBack, pinch zoom, Back trong Hộp công cụ và focus của hộp thoại lồng trên bản mới.
- `FileMutationSync` và `OfflineSyncWorker` chuẩn hóa root, cô lập replay theo account/root và báo catalogue sau PUT. Catalogue NAS `file_index` dùng chung cho danh sách, số lượng, tìm kiếm và quét trùng. Snapshot catalogue và lease theo FD bảo vệ bước xác minh trước xóa. Lượt quét/dọn hai APK thực và runtime ARM64 vẫn cần xác nhận riêng.
- AutoBackup vẫn khóa. Giữ phạm vi fixture cho kiểm thử ghi, giữ WIP của agent khác, và tránh triển khai hoặc restart NAS khi chưa có chỉ đạo riêng. NAS chạy Python 3.5.3 và Flask 1.1.4.

## Lệnh kiểm tra

- WebView chạy `node --check app/src/main/assets/html5/app.js` và kiểm tra bố cục 320 px.
- Android chạy `:app:assembleDebug` với JDK 21. Dùng bản build local hoặc `pushd` qua cmd trên UNC và cache Gradle riêng khi cần.
- Backend chạy `python -m pytest tests/ -v -rs`. Chỉ định `NAS_DUPLICATE_TEST_BINARY` nếu muốn chạy ca tích hợp Rust với binary tương ứng.
