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
  BACKUP_DIR=/var/log.hdd/nas_log_bak
  mkdir -p "$BACKUP_DIR" 2>/dev/null
  TS=$(date +%Y%m%d_%H%M)
  # FIX-P2-4: backup phan log can giu vao HDD TRUOC khi truncate, va verify
  # ket qua sao luu. Truoc day truncate truoc roi moi cp nen backup luon
  # rong (0-160 byte). Bay gio: cp -> kiem tra size > 0 -> chi truncate neu
  # backup OK. Neu backup that bai van truncate de giai phong zram nhung ghi
  # ca nhan (de biet mat backup).
  BACKUP_OK=1
  for f in syslog daemon.log; do
    if [ -f /var/log/$f ]; then
      if cp "/var/log/$f" "$BACKUP_DIR/$f.bak_$TS" 2>/dev/null; then
        if [ -s "$BACKUP_DIR/$f.bak_$TS" ]; then
          :
        else
          BACKUP_OK=0
        fi
      else
        BACKUP_OK=0
      fi
    fi
  done
  # 1) Giai phong zram bang truncate truc tiep (sau khi da backup xong).
  for f in auth.log syslog daemon.log messages kern.log ufw.log mail.log; do
    if [ -f /var/log/$f ]; then
      truncate -s 0 /var/log/$f 2>/dev/null
    fi
  done
  invoke-rc.d rsyslog rotate >/dev/null 2>&1 || true
  # 2) Force logrotate (da co space) de nen/xoay cac log con lai.
  /usr/sbin/logrotate -f /etc/logrotate.d/nas-zram 2>/dev/null
  if [ "$BACKUP_OK" -eq 1 ]; then
    sqlite3 /var/lib/nas_api/nas_index.db "INSERT INTO system_logs (type, module, message) VALUES ('WARNING', 'LogGuard', '/var/log dat ${USE_PCT}% — da backup vao HDD (${BACKUP_DIR}/*.bak_${TS}) roi truncate + xoay log');" 2>/dev/null
  else
    sqlite3 /var/lib/nas_api/nas_index.db "INSERT INTO system_logs (type, module, message) VALUES ('ERROR', 'LogGuard', '/var/log dat ${USE_PCT}% — backup HDD THAT BAI, da truncate + xoay log, backup co the bi rong');" 2>/dev/null
  fi
  # 3) Don backup cu >7 ngay.
  find "$BACKUP_DIR" -name '*.bak_*' -mtime +7 -delete 2>/dev/null
fi
