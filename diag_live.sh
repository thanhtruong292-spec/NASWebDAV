#!/bin/bash
echo "=== PID ==="
cat /var/run/nas_api_server.pid

echo "=== WEBDAV ROOT ==="
python3 -c "
import os, sys
sys.path.insert(0, '/')
config = '/var/www/webdav/config/config.php'
root = '/srv/dev-disk-by-label-data'
if os.path.exists(config):
    for line in open(config):
        if 'publicDir' in line and '=' in line:
            try:
                path = line.split(\"'\")[1]
                root = path.rstrip('/')
            except:
                pass
print('WEBDAV_ROOT=' + root)
print('META_DIR=' + root + '/.nas_meta')
print('LIVE_DIR=' + root + '/Livestream')
print('META_EXISTS=' + str(os.path.exists(root + '/.nas_meta')))
print('LIVE_EXISTS=' + str(os.path.exists(root + '/Livestream')))
"

echo "=== LIVESTREAM LOGS ==="
find / -name "live_*.log" -newer /tmp/nas_api.log 2>/dev/null | head -5

echo "=== YT-DLP TEST ==="
/usr/local/bin/yt-dlp --version

echo "=== MANUAL TEST ==="
/usr/local/bin/yt-dlp --list-extractors 2>/dev/null | grep -i tiktok | head -3
