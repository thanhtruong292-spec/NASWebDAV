ssh root@100.90.135.102 "cp /root/nas_api_server.py /opt/nas_api_server.py"
ssh root@100.90.135.102 "if [ -f /var/run/nas_api_server.pid ]; then kill $((cat /var/run/nas_api_server.pid)) 2>/dev/null; sleep 2; fi"
ssh root@100.90.135.102 "pkill -f 'python3 /root/nas_api_server.py' 2>/dev/null; pkill -f 'python3 nas_api_server.py' 2>/dev/null; sleep 2; exit 0"
ssh root@100.90.135.102 "nohup python3 /root/nas_api_server.py > /tmp/nas_api.log 2>&1 &"
