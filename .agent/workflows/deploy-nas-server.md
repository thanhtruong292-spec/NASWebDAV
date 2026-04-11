---
description: Deploy nas_api_server.py to NAS and restart server
---

# Deploy NAS API Server

// turbo-all

1. Copy file lên NAS:
```bash
scp d:\Android\NASWebDAV\nas_api_server.py root@100.90.135.102:/opt/nas_api_server.py
```

2. Dừng server cũ và khởi động lại:
```bash
ssh root@100.90.135.102 "pkill -f nas_api_server; sleep 1; nohup python3 /opt/nas_api_server.py > /var/log/nas_api.log 2>&1 &"
```

3. Xác nhận server đang chạy:
```bash
ssh root@100.90.135.102 "ps aux | grep nas_api_server | grep -v grep"
```

**Port mặc định:** 5050 (Flask API), 5051 (Tornado WebSocket)
