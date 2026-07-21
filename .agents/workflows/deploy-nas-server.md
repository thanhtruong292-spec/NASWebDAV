---
description: Deploy nas_api_server.py to NAS and restart server
---

# Deploy NAS API Server (An toàn — Không bao giờ kill Tailscale)

## Các bước thực hiện

### 1. Copy file lên NAS
// turbo
```
scp nas_api_server.py root@192.168.100.254:/root/nas_api_server.py
```

### 1b. Copy vào đúng nơi server chạy
// turbo
```
ssh root@192.168.100.254 "cp /root/nas_api_server.py /opt/nas_api_server.py"
```

### 2. Khởi động lại service bằng SystemD
// turbo
```
ssh root@192.168.100.254 "systemctl daemon-reload && systemctl restart nas_api.service"
```
> Việc khởi động lại qua systemd sẽ đảm bảo NAS tự động lấy đúng các biến môi trường (như WEBDAV_ROOT) và tự động chạy lại nếu bị lỗi.

### 5. Kiểm tra server chạy thành công
// turbo
```
ssh root@192.168.100.254 "sleep 5; tail -10 /tmp/nas_api.log"
```
> Phải thấy: `Server da khoi dong thanh cong!` và `[Thumbnail] Background generator da khoi dong.`

## Quản lý LAN Whitelist

### Thêm subnet LAN (cho phép tất cả thiết bị 192.168.1.x)
```bash
curl -X POST -H "Content-Type: application/json" -d '{"subnet":"192.168.1.0/24"}' http://192.168.100.254:5050/api/lan/whitelist
```

### Thêm 1 IP cụ thể
```bash
curl -X POST -H "Content-Type: application/json" -d '{"ip":"192.168.1.100"}' http://192.168.100.254:5050/api/lan/whitelist
```

### Xem danh sách whitelist
```bash
curl http://192.168.100.254:5050/api/lan/whitelist
```

### Xóa IP khỏi whitelist
```bash
curl -X DELETE -H "Content-Type: application/json" -d '{"ip":"192.168.1.100"}' http://192.168.100.254:5050/api/lan/whitelist
```

## Xử lý sự cố
- **Tailscale bị kill**: KHÔNG CÒN xảy ra (dùng PID file thay vì fuser)
- **Port still in use**: Chờ thêm 3 giây rồi thử lại bước 4
- **Kiểm tra log**: `ssh root@192.168.100.254 "cat /tmp/nas_api.log"`
