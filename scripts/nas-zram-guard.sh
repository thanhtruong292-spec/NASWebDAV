#!/bin/sh
# Protect /var/log (zram0 49MB) from filling up between daily logrotates.
# Install path on NAS: /etc/cron.hourly/nas-zram-guard (chmod 755)

LOG_USAGE=$(df /var/log | awk 'NR==2 {gsub(/%/,""); print $5}')
if [ -n "$LOG_USAGE" ] && [ "$LOG_USAGE" -gt 60 ]; then
    /usr/sbin/logrotate -f /etc/logrotate.d/nas-zram >/dev/null 2>&1
    NEW_USAGE=$(df /var/log | awk 'NR==2 {gsub(/%/,""); print $5}')
    if [ -n "$NEW_USAGE" ] && [ "$NEW_USAGE" -gt 75 ]; then
        for f in /var/log/syslog /var/log/daemon.log; do
            if [ -f "$f" ]; then
                tail -n 2000 "$f" > "$f.tmp" 2>/dev/null && cat "$f.tmp" > "$f" && rm -f "$f.tmp"
            fi
        done
        invoke-rc.d rsyslog rotate > /dev/null 2>&1 || true
    fi
fi
