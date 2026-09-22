#!/bin/bash
# NAS log guard: chay moi gio qua cron. /var/log (zram 49M) >80% thi:
#   1) truncate truc tiep cac log he thong lon (giu inode, rsyslog tiep tuc ghi)
#      -> giai phong zram ngay lap tuc. Truoc day chi force logrotate, ma
#      chinh logrotate cung can tmp space tren zram da day -> "error create tmpfile".
#   2) force logrotate (da co space) de nen/xoay cac log con lai.
#   3) backup ngan vao /var/log.hdd (HDD, du cho), tranh /tmp (co the readonly/day).
#   4) ghi alert vao DB de app thay.
# Don backup cu >7 ngay de HDD khong day.
USE_PCT=$(df /var/log | tail -n 1 | awk '{print $5}' | tr -d '%')
if [ "$USE_PCT" -gt 80 ]; then
  # 1) Giai phong zram NGAY (truoc moi thu khac) bang truncate truc tiep.
  for f in auth.log syslog daemon.log messages kern.log ufw.log mail.log; do
    if [ -f /var/log/$f ]; then
      truncate -s 0 /var/log/$f 2>/dev/null
    fi
  done
  invoke-rc.d rsyslog rotate >/dev/null 2>&1 || true
  # 2) Force logrotate (da co space) de nen/xoay cac log con lai.
  /usr/sbin/logrotate -f /etc/logrotate.d/nas-zram 2>/dev/null
  # 3) Backup ngan vao HDD.
  BACKUP_DIR=/var/log.hdd/nas_log_bak
  mkdir -p "$BACKUP_DIR" 2>/dev/null
  TS=$(date +%Y%m%d_%H%M)
  cp /var/log/syslog "$BACKUP_DIR/syslog.bak_$TS" 2>/dev/null
  cp /var/log/daemon.log "$BACKUP_DIR/daemon.log.bak_$TS" 2>/dev/null
  sqlite3 /var/lib/nas_api/nas_index.db "INSERT INTO system_logs (type, module, message) VALUES ('WARNING', 'LogGuard', '/var/log dat ${USE_PCT}% — da truncate + xoay log, backup o /var/log.hdd/nas_log_bak/*.bak_$TS');" 2>/dev/null
  # 4) Don backup cu >7 ngay.
  find "$BACKUP_DIR" -name '*.bak_*' -mtime +7 -delete 2>/dev/null
fi
