---
description: Deploy nas_api_server.py to NAS and restart server
---

# Deploy NAS API Server (An toàn — Không bao giờ kill Tailscale)

## Các bước thực hiện

### 1. Copy file lên NAS
// turbo
```
scp nas_api_server.py root@100.90.135.102:/root/nas_api_server.py
```

### 1b. Copy vào đúng nơi server chạy
// turbo
```
ssh root@100.90.135.102 "cp /root/nas_api_server.py /opt/nas_api_server.py"
```

### 2. Kill ĐÚNG tiến trình cũ bằng PID file (an toàn, không chạm Tailscale)
// turbo
```
ssh root@100.90.135.102 "if [ -f /var/run/nas_api_server.pid ]; then kill $(cat /var/run/nas_api_server.pid) 2>/dev/null; sleep 2; fi"
```
> PID file `/var/run/nas_api_server.pid` chỉ chứa PID của nas_api_server.py
> → Chắc chắn KHÔNG BAO GIỜ kill Tailscale hay bất kỳ tiến trình nào khác

### 3. Fallback: nếu PID file không tồn tại, kill bằng tên tiến trình cụ thể
// turbo
```
ssh root@100.90.135.102 "pkill -f 'python3 /root/nas_api_server.py' 2>/dev/null; pkill -f 'python3 nas_api_server.py' 2>/dev/null; sleep 2; exit 0"
```
> Chỉ kill đúng `python3 nas_api_server.py`, KHÔNG dùng `fuser -k` (có thể kill Tailscale)

### 4. Khởi động server mới
// turbo
```
ssh root@100.90.135.102 "nohup python3 /root/nas_api_server.py > /tmp/nas_api.log 2>&1 &"
```

### 5. Kiểm tra server chạy thành công
// turbo
```
ssh root@100.90.135.102 "sleep 5; tail -10 /tmp/nas_api.log"
```
> Phải thấy: `Server da khoi dong thanh cong!` và `[Thumbnail] Background generator da khoi dong.`

## Quản lý LAN Whitelist

### Thêm subnet LAN (cho phép tất cả thiết bị 192.168.1.x)
```bash
curl -X POST -H "Content-Type: application/json" -d '{"subnet":"192.168.1.0/24"}' http://100.90.135.102:5050/api/lan/whitelist
```

### Thêm 1 IP cụ thể
```bash
curl -X POST -H "Content-Type: application/json" -d '{"ip":"192.168.1.100"}' http://100.90.135.102:5050/api/lan/whitelist
```

### Xem danh sách whitelist
```bash
curl http://100.90.135.102:5050/api/lan/whitelist
```

### Xóa IP khỏi whitelist
```bash
curl -X DELETE -H "Content-Type: application/json" -d '{"ip":"192.168.1.100"}' http://100.90.135.102:5050/api/lan/whitelist
```

## Xử lý sự cố
- **Tailscale bị kill**: KHÔNG CÒN xảy ra (dùng PID file thay vì fuser)
- **Port still in use**: Chờ thêm 3 giây rồi thử lại bước 4
- **Kiểm tra log**: `ssh root@100.90.135.102 "cat /tmp/nas_api.log"`
