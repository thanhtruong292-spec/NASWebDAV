# Bàn giao NASWebDAV cho Antigravity — 17/11/2026

## Mục tiêu và nguyên tắc đã chốt

Người dùng muốn toàn app dùng **một database chính trên NAS**. NAS làm chủ dữ liệu, quyền truy cập, phiên bản danh mục, hàng đợi, khóa công việc và tiến độ. PC đã kết nối phải có thể nhận việc còn dở như tạo thumbnail, hash để tìm trùng, hỗ trợ kiểm kê và cập nhật kết quả qua API xác thực. Khi đổi máy, worker tiếp tục từ checkpoint của NAS. Android là frontend, Room dùng cho cache/offline. Android WorkManager `requiresDeviceIdle` chỉ phản ánh điện thoại đang rảnh. Chính backend phải quyết định NAS rảnh dựa vào tải, RAM, hoạt động người dùng, ghi hình và nhiệt độ HDD.

Ảnh ban đầu hiển thị khoảng 112,5 nghìn tệp, 118 thư mục, 193,35 GB với trạng thái “chưa đầy đủ”. “Cập nhật 4 phút trước” và thời gian NAS online không chứng minh đã quét liên tục trong tháng. Muốn trả lời phải đối chiếu danh mục, lịch sử tiến độ và lý do tạm dừng ở các thời điểm khác nhau. Số tệp đã kiểm kê và số ảnh/video chờ tạo thumbnail là hai đại lượng khác nhau.

## Điểm tiếp quản

- Repo `D:\Android\NASWebDAV`, GitHub `thanhtruong292-spec/NASWebDAV`, nhánh `codex-wip-20261114`, HEAD `94800a5` (“Luu code de chuyen sang may khac”). Working tree sạch trước khi thêm bản bàn giao. Branch theo dõi `origin/codex-wip-20261114`. Kiểm tra lại HEAD và trạng thái từ xa khi nhận việc.
- Đọc [đặc tả chung](../.specify/specs/unified-nas-file-work.md), [các tác vụ](../.specify/tasks/unified-nas-file-work.md) và [bằng chứng kiểm tra trước đây](../.specify/tasks/shared-catalogue-validation-20261115.md). Đối chiếu mã hiện tại vì một số mục trong đặc tả đã được sửa sau khi ghi.
- Người dùng yêu cầu ba bên chia việc qua [GitHub issue #9](https://github.com/thanhtruong292-spec/NASWebDAV/issues/9). Ghi người phụ trách, file đang sửa, commit và kết quả kiểm tra. Xác minh phản hồi của hai agent bên ngoài, nhất là người giữ quyền triển khai/restart. Ba subagent nội bộ ở cuối phiên đã dừng vì hạn mức. Không có xác nhận họ vẫn đang chạy hoặc đã hoàn thành các giao việc cuối.
- Dùng Ponytail, sửa nhỏ tại nguồn, tận dụng API và test sẵn có. Đọc `AGENTS.md`, `DEVELOPMENT_GUIDELINES.md` và `CLAUDE.md`. Backend chạy trên Chainedbox L1 Pro RK3328 ARM64, RAM 1 GB, Debian 9, Python 3.5.3, Flask 1.1.4, Pillow 4.0.0. `/var/log` nằm trên zram khoảng 49 MB. Giữ mã nguồn và dữ liệu NAS nội bộ, không đưa log chứa thông tin xác thực lên GitHub.

## Trạng thái thật trên NAS

Kiểm tra chỉ đọc qua SSH LAN ngày 17/11 xác nhận **vẫn còn hai database hoạt động**. `/var/lib/nas_api/nas_index.db` có `system_logs`, chưa có `file_index`. `/var/lib/nas_fast_index.db` có `file_index` và hàng đợi. Cả hai chưa có bảng đánh dấu `catalogue_imports`. Thời điểm đọc, job `index` là `queued, 0/0`, job `thumbnail` là `paused, 85.472/467.572`. Đây là một lần lấy mẫu, chưa xác định vì sao dừng hoặc tốc độ thực tế. Không dùng các số này để kết luận cả tháng.

Đích trong mã là `/var/lib/nas_api/nas_index.db`. Bản nhập danh mục chạy trong một giao dịch, giữ DB cũ, có dấu đã nhập, xử lý bảng trùng `thumb_neg_cache`. Đã thử trên PC với cấu trúc đọc từ NAS, chạy hai lần và nhận `integrity_check=ok`. Đã có test với dữ liệu mẫu, WAL và rollback. **Chưa xác minh di chuyển toàn bộ dữ liệu thật**. Chưa triển khai bản hợp nhất, restart dịch vụ hoặc dừng livestream trong lần bàn giao. Trước triển khai, phối hợp với agent vận hành, kiểm tra bản sao lưu nhất quán gồm WAL, dung lượng trống, bản đang chạy, job đang giữ lease và cách khôi phục.

## Phần đã có trong mã và giới hạn bằng chứng

| Luồng | Trạng thái để tiếp tục |
|---|---|
| Danh mục, tìm kiếm, dashboard | Tìm kiếm, thống kê, fast index và timeline ảnh đọc `file_index`. Timeline đã bỏ vòng quét và SQLite riêng. Đã sửa revision tụt về 0 khi quét lại thư mục không đổi. Khi đưa thư mục vào thùng rác, backend dọn hash tệp con ở cả dạng đường dẫn tương đối và WebDAV. |
| Android | Ảnh mới nhất/video gần đây dùng `/api/media/recent`, đọc tối đa 100 mục từ danh mục NAS, nhận biết danh mục chưa đầy và dùng cache theo tài khoản khi cần. Tìm kiếm nhận được kết quả index chưa hoàn thiện hiển thị trạng thái một phần. Có test API cho tệp lồng thư mục, giới hạn phạm vi, thứ tự, đơn vị mili giây và đổi root. Bản Kotlin đã compile. Cần chạy luồng trên điện thoại có Room trống. |
| PC và hàng đợi | `scripts/nas_computer_worker.py` dùng lease/checkpoint NAS, lấy tổng một lần cho một lượt phân trang, chỉ dùng lại cursor khi revision phù hợp. Hash SHA-256 qua stream xác thực, kiểm định danh, phiên bản và số byte trước khi ghi. Nếu danh mục đổi sau trang cuối, job phải được xếp chạy lại. |
| Thumbnail | Backend có kiểm tra phiên bản nguồn, ghi JPEG tạm rồi công bố, hỗ trợ xattr và đường tương thích cache cũ. Worker PC và `OnDemandThumbGenerator.kt` có truyền `source_version`. Kiểm tra riêng script cũ `scripts/pc_thumb_batch.py` còn dùng PROPFIND. Chưa xác minh toàn bộ uploader, cache Android và trường hợp NAS thiếu xattr trên dữ liệu thật. |
| Thay đổi tệp | `_index_mutated_directories` đã có hook cho nhiều thao tác như USB import, xuất video/ghi hình, di chuyển và hoàn tác. Cần kiểm tra từng đường ghi, đặc biệt PHP/WebDAV, SMB và ghi đè tại chỗ. Mtime của thư mục cha có thể giữ nguyên trong khi kích thước/nội dung tệp đổi. |
| Smart Organize | Snapshot hiện tại có đọc ứng viên theo lô từ `file_index`, kiểm root/generation/revision và kiểm tra lại nguồn trước khi di chuyển. Cần kiểm thử preview, execute, undo, nguồn bị sửa giữa chừng, tên đụng nhau và danh mục đổi khi chính job đang di chuyển tệp. |

## Việc còn mở, theo thứ tự tiếp quản

1. **Fingerprint ảnh và phụ thuộc Room.** `FingerprintWorker.kt` vẫn lấy ứng viên qua `getFilesWithoutFingerprint` và ghi `imageFingerprint` về Room. Chuyển việc chọn ứng viên và kết quả aHash theo phiên bản tệp sang NAS, trả kết quả qua API để thiết bị khác dùng lại. aHash dùng tìm ảnh tương tự; SHA-256 dùng xác minh trùng nội dung. Kiểm tra refresh Room có xóa các giá trị vừa lưu không. Dùng hai client và ghi đè tệp cùng tên để thử.
2. **Phân loại ảnh theo thư mục.** `_scan_photos_lightweight` vẫn dùng `os.walk` và ghi `ai_tags.json`. Chuyển cả tác vụ theo lịch, nút quét, `/api/ai/tags` và `/api/ai/status` sang cùng danh mục. Tránh lưu thêm một bản danh sách đường dẫn cho từng tính năng.
3. **Công cụ PC đời cũ.** `pc_hash_manager.py` còn tự duyệt WebDAV, từng gửi `mtime=0` và dùng quy ước đường dẫn khác. Đối chiếu `file_hashes`, `verified_file_hashes`, backup và báo cáo duplicate để cùng nhận một kết quả có phiên bản. Rà `pc_thumb_batch.py`, HEIC/HEIF mà NAS không giải mã được, và việc PC có nhận phần việc đó sau khi NAS báo hoàn tất.
4. **Nhất quán đầu cuối và tiến độ.** Sau create, upload, overwrite, rename, move, trash, restore, USB và hoàn tất recording, kiểm tra search, dashboard, recent media, hash, thumbnail và gallery trên hai client. Đối chiếu hàng đợi SQLite với tiến độ JSON cũ. Ghi lại lý do gate đóng thực tế, hai mẫu tiến độ có thời gian, hành vi khi NAS bận trở lại và khi PC mất kết nối. Tránh cho PC mở SQLite của NAS qua SMB.
5. **Nhiệt, quạt và biểu đồ.** Người dùng muốn HDD dưới 45°C, ít chu kỳ bật/tắt; phòng khoảng 27–28°C, NAS thoáng. Người dùng gọi ổ là Toshiba N300; ghi chú SMART trước đây lại nhận diện `MG04ACA400N`. Xác minh lại model trên đúng ổ, tra tài liệu Toshiba đúng model rồi mới kết luận. Mặc định trong mã hiện tại là bật 42°C/tắt 40°C. Đọc cấu hình hiệu lực, SMART 194, độ mới mẫu, chế độ manual/auto và lịch sử 24 giờ khi quạt chạy liên tục. Có probe Android `ChartHoursLiveProbeTest.kt` cho 24h → 1h → 6h → 1h. Kiểm tra cả thao tác bấm chip trên UI và khoảng thời gian trả về.
6. **Nén livestream.** Người dùng hỏi AV1 hoặc codec tiết kiệm nhất. Luồng remux dùng `-c copy` giữ codec nguồn. Chưa có xác nhận đã triển khai job nén AV1/HEVC. So sánh bằng đoạn mẫu trên PC, ghi dung lượng, thời gian mã hóa, chất lượng và khả năng phát Android trước khi chốt. Giữ bản gốc cho đến khi xác minh kết quả.

## Điều kiện để báo hoàn tất

Đã chạy lại toàn bộ suite Python tại HEAD `94800a5` ngày 17/11: **257 passed, 6 skipped**. Lượt trước đã build thành công `:app:compileDebugKotlin --offline`. Có [ghi chú bàn giao song song](../HANDOFF-ANTIGRAVITY-20261117.md) về SHA-256 local/live, LAN/Tailscale, giao diện, kiểm tra đăng nhập và resume trên điện thoại. Hai tài liệu đang ở workspace, cần mang theo nếu chuyển sang máy khác.

Chạy `python -m pytest tests/ -v`, build Kotlin, test Android liên quan và ghi rõ các test skip. Đối chiếu bản source, bản triển khai và thiết bị. Trên NAS, chứng minh chỉ một DB nhận ghi mới, số liệu cũ còn đầy đủ, khởi động lại không nhập lặp và các client nhìn cùng kết quả. Bằng chứng tải CPU/RAM, nhiệt độ, tiến độ khi rảnh và việc nhường tài nguyên khi bận phải lấy từ NAS thật.
