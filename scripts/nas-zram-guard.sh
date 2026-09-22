#!/bin/sh
# Protect /var/log (zram0 49MB) from filling up between daily logrotates.
# Install path on NAS: /etc/cron.hourly/nas-zram-guard (chmod 755)

LOG_USAGE=$(df /var/log | awk 'NR==2 {gsub(/%/,""); print $5}')
if [ -n "$LOG_USAGE" ] && [ "$LOG_USAGE" -gt 60 ]; then
    /usr/sbin/logrotate -f /etc/logrotate.d/nas-zram >/dev/null 2>&1
    NEW_USAGE=$(df /var/log | awk 'NR==2 {gsub(/%/,""); print $5}')
    if [ -n "$NEW_USAGE" ] && [ "$NEW_USAGE" -gt 75 ]; then
        # FIX-ENOSPC: tao temp tren /tmp (fs rieng), KHONG tao tren /var/log dang
        # day. Truoc day tao "$f.tmp" cung phan vung nen luon that bai khi con 0
        # byte kha dung -> khong the cat log de giai phong.
        for f in /var/log/syslog /var/log/daemon.log /var/log/auth.log /var/log/messages; do
            if [ -f "$f" ]; then
                tmp="$(mktemp /tmp/nas-zram-guard.XXXXXX)"
                if tail -n 2000 "$f" > "$tmp" 2>/dev/null && cat "$tmp" > "$f" 2>/dev/null; then
                    :
                fi
                rm -f "$tmp"
            fi
        done
        invoke-rc.d rsyslog rotate > /dev/null 2>&1 || true
    fi
fi
