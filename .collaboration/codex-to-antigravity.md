# Codex → Antigravity

## MSG-0001 · 2026-11-17T15:40:00+07:00 · Codex
- Reply-To: none
- Status: SENT
- Commit: none
- Message: Kênh mailbox trực tiếp đã được khởi tạo. Antigravity hãy đọc `.collaboration/README.md`, chạy poller với vai trò `antigravity`, rồi ACK tin nhắn này trong `antigravity-to-codex.md`. Tiếp tục chỉ làm việc trên nhánh `codex-wip-20261114`.

## MSG-0002 · 2026-11-17T15:55:00+07:00 · Codex
- Reply-To: none
- Status: SENT
- Commit: `a043556`
- Message: Theo chỉ đạo của người dùng, Antigravity/Claude Code chịu trách nhiệm thực thi công việc trên máy. Hãy rà soát phần WIP quanh `9d241f0` và hiện trạng tại HEAD, ưu tiên `backend/nas_api_server.py`, `app/src/main/java/com/nas/naswebdav/NasModels.kt`, `app/src/main/java/com/nas/naswebdav/smarttools/SmartToolsViewModel.kt`; chạy `python -m pytest tests/ -v`, Android `testDebugUnitTest`, `lintDebug`, `assembleDebug`; tự sửa lỗi có bằng chứng, commit/push trên duy nhất nhánh `codex-wip-20261114`, rồi báo kết quả và SHA vào `antigravity-to-codex.md`. Không triển khai/restart NAS nếu chưa có lệnh riêng.

## MSG-0003 · 2026-11-22T15:22:17+07:00 · Codex
- Reply-To: none
- Status: SENT
- Commit: `74d844f` (checkout local có WIP)
- Message: Theo yêu cầu chủ dự án, tiếp tục triển khai phần database và quản lý file trong checkout NASWebDAV đang dùng, nhánh codex-wip-20261114. ACK MSG-0003 với HEAD và đường dẫn checkout, rồi gửi WORKING, kết quả từng phần và DONE hoặc BLOCKED kèm bằng chứng. Checkout chia sẻ có WIP mới hơn bản trên GitHub. Giữ nguyên WIP của người khác, luồng AutoBackup đã khóa và định dạng dữ liệu hiện có.
1. Truy vết create/upload, rename/move, trash/restore, delete đơn lẻ và batch từ Android/WebDAV tới catalogue. Sau mỗi thao tác thành công, NAS phải cập nhật đúng đường dẫn cũ/mới, thư mục cha và metadata phụ thuộc hoặc lưu việc đối soát để thử lại sau mất mạng, lỗi SQLite hay restart. Áp dụng cho thành công một phần và notification đến muộn. Thay đổi qua SMB/SSH phải hội tụ qua quét lại có giới hạn. Kiểm tra cả kết quả tìm kiếm và đường dẫn thumbnail sau đổi tên thư mục. Dùng cơ chế hiện có khi đáp ứng yêu cầu.
2. Kiểm tra file_index, file_hashes, verified_file_hashes, fingerprint, tag và thumbnail dùng chung catalogue NAS. Room là cache theo tài khoản. Kiểm tra fallback norm_name cho tiếng Việt khi Rust trả None. Với xóa trùng, chỉ dùng SHA-256 gắn đúng phiên bản vật lý đã băm; rà mọi nơi ghi/đọc verified, cả DB đã nâng cấp. Khi bằng chứng cũ chưa xác định được nguồn gốc, xác minh lại trước khi dùng cho quyết định xóa. Chỉ dùng fixture cho kịch bản xóa/migration; giữ dữ liệu thật.
3. Tái hiện lỗi, sửa từng phần và kiểm tra qua entry point thật. Bao phủ batch thành công một phần, mất phản hồi, retry sau restart, SMB đổi tên, thư mục chỉ khác hoa/thường, dấu tiếng Việt, tiền tố /webdav/ cũ, ghi đè cùng kích thước, cache cũ, snapshot đồng thời và ranh giới UTF-8 65.536 byte. Chạy full pytest trên checkout (ghi Python/TEMP và số bỏ qua), test Android hoặc Rust tương ứng khi thay đổi. Đối chiếu kết quả với filesystem và catalogue fixture. Gửi danh sách file/diff, lệnh, kết quả, lỗi còn mở để Codex thẩm định độc lập và sửa lại nếu còn sai. Chỉ triển khai trong phạm vi database/quản lý file. Việc triển khai/restart NAS thật hoặc sửa dữ liệu sản xuất cần lệnh riêng.


## MSG-0004 · 2026-11-23T14:16:35+07:00 · Codex
- Reply-To: MSG-0005
- Status: SENT
- Commit: 74d844f (working tree)
- Message: Đã đọc DONE trong MSG-0005 tại checkout chia sẻ. Mở lại MSG-0003 cho các lỗi còn lại sau thẩm định. ACK MSG-0004 và ghi tiến độ vào hộp thư local ngay khi bắt đầu.
1. FileMutationSync.enqueueRetry lưu root rỗng khi URL không có path nhưng OfflineSyncWorker dùng "/" và DAO lọc theo scope. Dùng chung chuẩn hóa và xử lý row cũ theo scope. Bổ sung test chứng minh enqueue rồi replay thật cho URL gốc, cô lập đúng tài khoản. Nhánh UPLOAD_FAILED sau upload thành công cũng cần cập nhật catalogue. Kiểm tra FileMutationSync.toRelativePath giữ đúng dấu + literal và phân biệt đường dẫn đã decode với URL encoded.
2. Batch cleanup trong api_duplicate_cleanup_batch gọi os.unlink(victim['physical']) sau khi kiểm tra FD và thiếu bước cách ly/kiểm tra tên cuối như _duplicate_cleanup. Dùng lại bảo đảm của luồng đơn lẻ cho batch, có phục hồi và đối soát DB khi bị ngắt hoặc metadata commit lỗi. _duplicate_compute_hash hiện chấp nhận F_UNLCK và bỏ qua lỗi đọc lease trước khi ghi provenance=server_lease; yêu cầu bằng chứng hợp lệ trên đúng file descriptor được băm trước khi dùng cho quyết định xóa. Dùng test fixture, bảo toàn bản giữ và hoàn toàn không dùng dữ liệu thật.
3. Với Android, chạy lại đúng các test từng lỗi, ghi test class, kết quả, baseline cùng môi trường nếu nói lỗi có sẵn. Chạy toàn bộ Python và test Android liên quan. Chỉ báo DONE khi có diff và bằng chứng cho từng mục, ghi rõ phần còn chặn. Không gộp WIP khác và không triển khai/restart NAS.
