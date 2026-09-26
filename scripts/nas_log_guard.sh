#!/bin/bash
# NAS log guard: chay moi gio qua cron. /var/log (zram 49M) >80% thi:
#   1) backup ngan cac log he thong can giu vao BACKUP_DIR, NEN gzip, va verify
#      ket qua sao luu. Neu backup OK moi truncate truc tiep /var/log de giai
#      phong zram ngay lap tuc (rsyslog tiep tuc ghi, giu inode).
#   2) force logrotate (da co space) de nen/xoay cac log con lai.
#   3) ghi alert vao DB de app thay.
# Don backup cu: ca theo tuoi (>7 ngay) va theo tong byte (gioi han QUOTA).
#
# LUU Y QUAN TRONG (N3): BACKUP_DIR mac dinh la /var/log.hdd NHUNG thuc te
# nam tren phan vung OS eMMC (/dev/mmcblk1p1, ~7GB), KHONG phai o cung HDD.
# Do do phai co QUOTA + MUC TRONG TOI THIEU de khong lam day phan vung OS,
# va khong duoc ghi de backup cu khi da het cho.
#
# Tat ca duong dan co the override qua env de test an toan (khong cham NAS):
#   LOG_GUARD_VAR_LOG, LOG_GUARD_BACKUP_DIR, LOG_GUARD_MAX_BACKUP_BYTES,
#   LOG_GUARD_MIN_FREE_BYTES, LOG_GUARD_DF, LOG_GUARD_SQLITE3, LOG_GUARD_ALWAYS.
set -u

VAR_LOG="${LOG_GUARD_VAR_LOG:-/var/log}"
BACKUP_DIR="${LOG_GUARD_BACKUP_DIR:-/var/log.hdd/nas_log_bak}"
# Tong byte toi da cua BACKUP_DIR (mac dinh 512MB). Vuot qua se xoa backup cu nhat.
MAX_BACKUP_BYTES="${LOG_GUARD_MAX_BACKUP_BYTES:-536870912}"
# Muc trong toi thieu phai giu lai tren filesystem chua BACKUP_DIR (mac dinh 512MB).
# Neu duoi muc nay, BO QUA backup moi de khong lam day phan vung OS.
MIN_FREE_BYTES="${LOG_GUARD_MIN_FREE_BYTES:-536870912}"
# Force chay ngay ca khi /var/log chua du nguong (chi de test).
ALWAYS="${LOG_GUARD_ALWAYS:-0}"
DF="${LOG_GUARD_DF:-df}"
SQLITE3="${LOG_GUARD_SQLITE3:-sqlite3}"

# Bytes trong tren filesystem chua duong dan $1 (df -B1 cot 4).
fs_avail_bytes() {
  "$DF" -B1 "$1" 2>/dev/null | awk 'NR==2{print $4}'
}
# Bytes da dung cua BACKUP_DIR (du -sb).
backup_used_bytes() {
  if [ -d "$BACKUP_DIR" ]; then
    du -sb "$BACKUP_DIR" 2>/dev/null | awk '{print $1}'
  else
    echo 0
  fi
}
# Bytes tong cong cua cac file log nguon duoc backup.
pending_bytes() {
  local total=0 f sz
  for f in syslog daemon.log; do
    if [ -f "$VAR_LOG/$f" ]; then
      sz=$(stat -c%s "$VAR_LOG/$f" 2>/dev/null || echo 0)
      total=$((total + sz))
    fi
  done
  echo "$total"
}

USE_PCT=$(df /var/log 2>/dev/null | tail -n 1 | awk '{print $5}' | tr -d '%')
[ -z "$USE_PCT" ] && USE_PCT=0
if [ "$ALWAYS" = "1" ] || [ "$USE_PCT" -gt 80 ]; then
  mkdir -p "$BACKUP_DIR" 2>/dev/null
  TS=$(date +%Y%m%d_%H%M)

  # --- Quota / muc trong toi thieu tren filesystem chua backup (N3) ---
  # FIX-REVIEW-24/09-#15: prune PROJECTED USAGE truoc quyet dinh backup (khong
  # phai chi khi used da vuot quota). Ban cu: used490+pending30>quota512 thi bo
  # backup roi truncate, lap lai den khi tuoi doi — khong bao gio quay vong.
  # Quy trinh moi: prune cu nhat de du projected, nhung KHONG prune den rong
  # roi van skip (giu lai it nhat 1 backup cu khi khong the du cho).
  # Migrate/quan ly ca format cu: xoa theo tuoi >7 ngay cho MOI format.
  find "$BACKUP_DIR" \( -name '*.bak_*.gz' -o -name '*.bak_*' \) -mtime +7 -delete 2>/dev/null
  SKIP_BACKUP=0
  avail=$(fs_avail_bytes "$BACKUP_DIR")
  if [ -n "$avail" ] && [ "$avail" -lt "$MIN_FREE_BYTES" ]; then
    # Phan vung chua backup sap day: BO QUA backup moi de khong lam full OS.
    SKIP_BACKUP=1
  fi
  pend=$(pending_bytes)
  used=$(backup_used_bytes)
  if [ "$SKIP_BACKUP" -eq 0 ] && [ -n "$used" ] && [ -n "$pend" ]; then
    _need=$((used + pend - MAX_BACKUP_BYTES))
    if [ "$_need" -gt 0 ]; then
      _freed=0
      while [ "$_freed" -lt "$_need" ]; do
        oldest=$(find "$BACKUP_DIR" \( -name '*.bak_*.gz' -o -name '*.bak_*' \) -printf '%T@ %s %p\n' 2>/dev/null | sort -n | head -n 1)
        if [ -z "$oldest" ]; then break; fi
        _osz=$(echo "$oldest" | awk '{print $2}')
        _op=$(echo "$oldest" | awk '{print $3}')
        if [ -z "$_op" ]; then break; fi
        _remaining=$(find "$BACKUP_DIR" \( -name '*.bak_*.gz' -o -name '*.bak_*' \) 2>/dev/null | wc -l)
        _proj_after=$((used - _freed - _osz + pend))
        if [ "$_proj_after" -gt "$MAX_BACKUP_BYTES" ] && [ "$_remaining" -le 1 ]; then
          break
        fi
        rm -f "$_op" 2>/dev/null
        _freed=$((_freed + _osz))
        used=$(backup_used_bytes)
      done
      used=$(backup_used_bytes)
    fi
    if [ $((used + pend)) -gt "$MAX_BACKUP_BYTES" ]; then
      SKIP_BACKUP=1
    else
      # Giu muc free sau write: prune them den khi avail-pend >= MIN_FREE,
      # nhung van giu lai it nhat 1 file.
      avail=$(fs_avail_bytes "$BACKUP_DIR")
      while [ -n "$avail" ] && [ $((avail - pend)) -lt "$MIN_FREE_BYTES" ]; do
        _remaining=$(find "$BACKUP_DIR" \( -name '*.bak_*.gz' -o -name '*.bak_*' \) 2>/dev/null | wc -l)
        if [ "$_remaining" -le 1 ]; then SKIP_BACKUP=1; break; fi
        oldest=$(find "$BACKUP_DIR" \( -name '*.bak_*.gz' -o -name '*.bak_*' \) -printf '%T@ %p\n' 2>/dev/null | sort -n | head -n 1 | awk '{print $2}')
        if [ -z "$oldest" ]; then SKIP_BACKUP=1; break; fi
        rm -f "$oldest" 2>/dev/null
        avail=$(fs_avail_bytes "$BACKUP_DIR")
      done
    fi
  fi

  BACKUP_OK=1
  if [ "$SKIP_BACKUP" -eq 0 ]; then
    for f in syslog daemon.log; do
      if [ -f "$VAR_LOG/$f" ]; then
        # Nen gzip truc tiep vao BACKUP_DIR; kiem tra file .gz khong rong.
        if gzip -c "$VAR_LOG/$f" > "$BACKUP_DIR/$f.bak_$TS.gz" 2>/dev/null; then
          if [ -s "$BACKUP_DIR/$f.bak_$TS.gz" ]; then
            :
          else
            BACKUP_OK=0
          fi
        else
          BACKUP_OK=0
        fi
      fi
    done
  else
    BACKUP_OK=0
  fi

  # 1) Giai phong zram bang truncate truc tiep (chi sau khi backup OK).
  #    Neu backup that bai van truncate de giai phong zram nhung danh dau
  #    BACKUP_OK=0 de ghi ERROR (biet mat backup).
  for f in auth.log syslog daemon.log messages kern.log ufw.log mail.log; do
    if [ -f "$VAR_LOG/$f" ]; then
      truncate -s 0 "$VAR_LOG/$f" 2>/dev/null
    fi
  done
  invoke-rc.d rsyslog rotate >/dev/null 2>&1 || true
  # 2) Force logrotate (da co space) de nen/xoay cac log con lai.
  /usr/sbin/logrotate -f /etc/logrotate.d/nas-zram >/dev/null 2>&1 || true

  if [ "$BACKUP_OK" -eq 1 ]; then
    "$SQLITE3" /var/lib/nas_api/nas_index.db "INSERT INTO system_logs (type, module, message) VALUES ('WARNING', 'LogGuard', '/var/log dat ${USE_PCT}% — da backup (gzip) vao ${BACKUP_DIR}/*.bak_${TS}.gz roi truncate + xoay log');" 2>/dev/null || true
  else
    if [ "$SKIP_BACKUP" -eq 1 ]; then
      "$SQLITE3" /var/lib/nas_api/nas_index.db "INSERT INTO system_logs (type, module, message) VALUES ('ERROR', 'LogGuard', '/var/log dat ${USE_PCT}% — BO QUA backup (quota/trong toi thieu phan vung), da truncate + xoay log; backup co the mat');" 2>/dev/null || true
    else
      "$SQLITE3" /var/lib/nas_api/nas_index.db "INSERT INTO system_logs (type, module, message) VALUES ('ERROR', 'LogGuard', '/var/log dat ${USE_PCT}% — backup THAT BAI, da truncate + xoay log, backup co the bi rong');" 2>/dev/null || true
    fi
  fi

  # 3) Don backup cu: xoa theo tuoi >7 ngay (MOI format, da lam o tren),
  # roi xoa cu nhat den khi duoi QUOTA (moi format).
  find "$BACKUP_DIR" \( -name '*.bak_*.gz' -o -name '*.bak_*' \) -mtime +7 -delete 2>/dev/null
  used=$(backup_used_bytes)
  while [ -n "$used" ] && [ "$used" -gt "$MAX_BACKUP_BYTES" ]; do
    oldest=$(find "$BACKUP_DIR" \( -name '*.bak_*.gz' -o -name '*.bak_*' \) -printf '%T@ %p\n' 2>/dev/null | sort -n | head -n 1 | awk '{print $2}')
    if [ -z "$oldest" ]; then break; fi
    rm -f "$oldest" 2>/dev/null
    used=$(backup_used_bytes)
  done
fi
