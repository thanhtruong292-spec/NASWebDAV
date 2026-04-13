#!/bin/bash
scp nas_api_server.py root@100.90.135.102:/root/nas_api_server.py
ssh root@100.90.135.102 "cp /root/nas_api_server.py /opt/nas_api_server.py ; pkill -f nas_api_server.py 2>/dev/null; sleep 2 ; nohup python3 /opt/nas_api_server.py > /tmp/nas_api.log 2>&1 & sleep 3 ; tail -5 /tmp/nas_api.log"
