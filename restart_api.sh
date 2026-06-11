#!/bin/sh
# Server LIVE do systemd quản lý: nas_api.service chạy /opt/nas_api_server.py.
# (Trước đây script này pkill + nohup /root/... — sai path & sẽ xung đột với
#  systemd tự respawn. Dùng systemctl mới đúng.)
if systemctl list-units --type=service 2>/dev/null | grep -q 'nas_api.service'; then
    systemctl restart nas_api.service
    sleep 8
    systemctl is-active nas_api.service
    journalctl -u nas_api.service -n 15 --no-pager
else
    # Fallback nếu không có systemd unit
    pkill -f nas_api_server.py 2>/dev/null
    sleep 2
    nohup python3 /opt/nas_api_server.py > /tmp/nas_api.log 2>&1 &
    sleep 7
    tail -15 /tmp/nas_api.log
fi
echo "EXIT_OK"
