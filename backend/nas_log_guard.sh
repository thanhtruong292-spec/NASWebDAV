#!/bin/bash
# NAS log guard: chay moi gio qua cron. /var/log (zram 49M) >80% thi force
# logrotate + ghi alert vao DB de app thay. Backup truoc khi cat.
USE_PCT=$(df /var/log | tail -n 1 | awk '{print $5}' | tr -d '%')
if [ "$USE_PCT" -gt 80 ]; then
  TS=$(date +%Y%m%d_%H%M)
  cp /var/log/syslog "/var/log.hdd/syslog.bak_$TS" 2>/dev/null
  cp /var/log/daemon.log "/var/log.hdd/daemon.log.bak_$TS" 2>/dev/null
  /usr/sbin/logrotate -f /etc/logrotate.d/nas-zram 2>/dev/null
  sqlite3 /var/lib/nas_api/nas_index.db "INSERT INTO system_logs (type, module, message) VALUES ('WARNING', 'LogGuard', '/var/log dat ${USE_PCT}% — da xoay log, backup o /var/log.hdd/*.bak_$TS');" 2>/dev/null
fi
# Don backup cu >7 ngay de HDD khong day
find /var/log.hdd -name '*.bak_*' -mtime +7 -delete 2>/dev/null
