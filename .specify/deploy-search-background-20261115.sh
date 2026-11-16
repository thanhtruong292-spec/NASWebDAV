#!/bin/sh
set -eu
release=/opt/naswebdav-release-20261115
test "$(sha256sum /opt/nas_api_server.py | cut -d' ' -f1)" = 70c851db90691faa3571675dd460b2687015af5008c666cea75e8d2d31b49aa5
test "$(sha256sum "$release/nas_api_server.background.py" | cut -d' ' -f1)" = 6a95aba44f8269f4fc38ffecdaed77a1fb669edde13fabafa566bfac2733f831
test "$(pgrep -c ffmpeg || true)" = 0
cp -p /opt/nas_api_server.py "$release/rollback/before-background.py"
changed=0
trap 'status=$?; if [ "$status" -ne 0 ] && [ "$changed" -eq 1 ]; then install -o root -g root -m 644 "$release/rollback/before-background.py" /opt/nas_api_server.py; systemctl restart nas_api.service; fi' EXIT
trap 'exit 1' HUP INT TERM
install -o root -g root -m 644 "$release/nas_api_server.background.py" /opt/nas_api_server.py.next
changed=1
mv /opt/nas_api_server.py.next /opt/nas_api_server.py
systemctl restart nas_api.service
ready=0
for attempt in $(seq 1 45); do
    if python3 "$release/nas-live-integrity-check.py" --health 2>/dev/null; then ready=1; break; fi
    sleep 1
done
test "$ready" = 1
systemctl is-active --quiet nas_api.service
sha256sum /opt/nas_api_server.py
