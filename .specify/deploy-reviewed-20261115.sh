#!/bin/sh
set -eu
release=/opt/naswebdav-release-20261115
test "$(sha256sum "$release/nas_api_server.py" | cut -d' ' -f1)" = 70c851db90691faa3571675dd460b2687015af5008c666cea75e8d2d31b49aa5
test "$(sha256sum /opt/nas_api_server.py | cut -d' ' -f1)" = d5719f5d20aa2a1294a8d3bd4422c891627a159feed0acb84e451fdc5424f71d
test "$(sha256sum /var/www/webdav/public/index.php | cut -d' ' -f1)" = 128029377dd0136e7ed8a717046a432a47e21361a4baac4d246d98163e013335
test "$(pgrep -c ffmpeg || true)" = 0
python3 -c 'import ast; ast.parse(open("/opt/naswebdav-release-20261115/nas_api_server.py").read())'
rollback() {
    install -o root -g root -m 644 "$release/rollback/nas_api_server.py" /opt/nas_api_server.py
    install -o root -g root -m 644 "$release/rollback/index.php" /var/www/webdav/public/index.php
    systemctl restart nas_api.service
}
changed=0
trap 'status=$?; if [ "$status" -ne 0 ] && [ "$changed" -eq 1 ]; then rollback; fi' EXIT
trap 'exit 1' HUP INT TERM
changed=1
install -o root -g root -m 644 "$release/nas_namespace_guard.php" /var/www/webdav/bootstrap/nas_namespace_guard.php
install -o root -g root -m 644 "$release/naswebdav-namespace.tmpfiles.conf" /etc/tmpfiles.d/naswebdav-namespace.conf
systemd-tmpfiles --create /etc/tmpfiles.d/naswebdav-namespace.conf
install -o root -g root -m 644 "$release/index.php.candidate" /var/www/webdav/public/index.php.next
mv /var/www/webdav/public/index.php.next /var/www/webdav/public/index.php
install -o root -g root -m 644 "$release/namespace-manifest.json" /var/lib/naswebdav-namespace-guard.json
python3 - <<'PY'
import hashlib, json, os, stat
with open('/var/lib/naswebdav-namespace-guard.json') as handle:
    manifest=json.load(handle)
for path, digest in manifest['files'].items():
    current=os.lstat(path)
    assert stat.S_ISREG(current.st_mode) and current.st_uid==0 and not current.st_mode & 0o022
    with open(path,'rb') as handle:
        assert hashlib.sha256(handle.read()).hexdigest()==digest
PY
install -o root -g root -m 644 "$release/nas_api_server.py" /opt/nas_api_server.py.next
mv /opt/nas_api_server.py.next /opt/nas_api_server.py
systemctl restart nas_api.service
ready=0
for attempt in $(seq 1 45); do
    if python3 "$release/nas-live-integrity-check.py" --health > "$release/health.json" 2>/dev/null; then ready=1; break; fi
    sleep 1
done
test "$ready" = 1
systemctl is-active --quiet nas_api.service
cat "$release/health.json"
sha256sum /opt/nas_api_server.py
systemctl is-active nas_api.service
