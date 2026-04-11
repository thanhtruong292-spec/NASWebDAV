#!/bin/bash
cp /root/nas_api_server.py /opt/nas_api_server.py
PID_FILE="/var/run/nas_api_server.pid"
if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE")
  kill "$OLD_PID" 2>/dev/null
  sleep 2
fi
pkill -f 'python3 /root/nas_api_server.py' 2>/dev/null
sleep 1
nohup python3 /root/nas_api_server.py > /tmp/nas_api.log 2>&1 &
sleep 6
tail -12 /tmp/nas_api.log
