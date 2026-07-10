#!/bin/sh
pkill -f nas_api_server.py 2>/dev/null
sleep 2
nohup python3 /root/nas_api_server.py > /tmp/nas_api.log 2>&1 &
sleep 7
tail -15 /tmp/nas_api.log
echo "EXIT_OK"
