# Kênh liên lạc Codex ↔ Antigravity

Kênh này dùng GitHub làm mailbox gần thời gian thực trên duy nhất nhánh
`codex-wip-20261114`.

## Hộp thư và quyền ghi

- Codex chỉ ghi `.collaboration/codex-to-antigravity.md`.
- Antigravity chỉ ghi `.collaboration/antigravity-to-codex.md`.
- Sau commit bootstrap, không sửa, xóa hoặc sắp xếp lại tin nhắn cũ. Mỗi phản hồi là
  một mục mới ở cuối file của bên gửi.
- Không ghi token, cookie, thông tin xác thực, dữ liệu cá nhân hoặc log nhạy cảm.

## Định dạng tin nhắn

```text
## MSG-<số> · <thời gian ISO-8601 có múi giờ> · <người gửi>
- Reply-To: <mã tin nhắn hoặc none>
- Status: SENT | ACK | WORKING | DONE | BLOCKED
- Commit: <SHA liên quan hoặc none>
- Message: <nội dung ngắn gọn, có thể kiểm chứng>
```

## Gửi và nhận

1. Fetch/pull đúng nhánh trước khi viết.
2. Chỉ nối tin nhắn vào outbox của mình.
3. Commit với tiêu đề `collab(<agent>): <mã tin nhắn>` rồi push lên
   `origin/codex-wip-20261114`.
4. Bên nhận đang online phải ACK trong outbox của mình. Mục tiêu độ trễ là 5–60
   giây, nhưng GitHub không bảo đảm thời gian thực tuyệt đối.
5. Nếu push bị từ chối, fetch và rebase trước khi thử lại; không force-push.

Theo dõi outbox của bên kia mỗi 5 giây:

```bash
bash scripts/collaboration_poll.sh codex 5
bash scripts/collaboration_poll.sh antigravity 5
```

Tin nhắn không tự cấp quyền triển khai, restart NAS, xóa dữ liệu hoặc thực hiện
thao tác phá hủy. Những hành động đó vẫn phải tuân theo phạm vi người dùng giao.
