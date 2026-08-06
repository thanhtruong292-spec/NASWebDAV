#!/bin/bash
# recover_quarantine.sh - Try to recover quarantined MP4 files via ffmpeg -c copy
SRC="/srv/dev-disk-by-label-data/New folder/AutoBackup/DCIM/Screen recordings/_broken_quarantine_20260728"
DST="/srv/dev-disk-by-label-data/New folder/AutoBackup/DCIM/Screen recordings/_recovered"
LOG="/tmp/recover.log"

mkdir -p "$DST"
: > "$LOG"

ok=0
fail=0
total_bytes=0

for src in "$SRC"/*.mp4; do
  [ -f "$src" ] || continue
  base="$(basename "$src")"
  dst="$DST/$base"

  if [ -f "$dst" ] && [ -s "$dst" ]; then
    echo "[SKIP] $base already in _recovered" >> "$LOG"
    continue
  fi

  size=$(stat -c%s "$src" 2>/dev/null || echo 0)
  echo "[TRY ] $base sz=$size" >> "$LOG"

  /usr/bin/ffmpeg -y -v error -i "$src" -c copy -movflags +faststart "$dst.tmp.mp4" 2>/tmp/fferr1.txt
  rc1=$?
  if [ $rc1 -eq 0 ] && [ -f "$dst.tmp.mp4" ] && [ -s "$dst.tmp.mp4" ]; then
    mv "$dst.tmp.mp4" "$dst"
    rec_size=$(stat -c%s "$dst" 2>/dev/null || echo 0)
    echo "[OK1 ] $base recovered=$rec_size/$size" >> "$LOG"
    ok=$((ok + 1))
    total_bytes=$((total_bytes + rec_size))
    continue
  fi
  rm -f "$dst.tmp.mp4"

  /usr/bin/ffmpeg -y -v error -i "$src" -c copy "$dst.tmp.mp4" 2>/tmp/fferr2.txt
  rc2=$?
  if [ $rc2 -eq 0 ] && [ -f "$dst.tmp.mp4" ] && [ -s "$dst.tmp.mp4" ]; then
    mv "$dst.tmp.mp4" "$dst"
    rec_size=$(stat -c%s "$dst" 2>/dev/null || echo 0)
    echo "[OK2 ] $base recovered=$rec_size/$size" >> "$LOG"
    ok=$((ok + 1))
    total_bytes=$((total_bytes + rec_size))
    continue
  fi
  rm -f "$dst.tmp.mp4"

  /usr/bin/ffmpeg -y -v error -fflags +genpts+igndts -i "$src" -c copy "$dst.tmp.mp4" 2>/tmp/fferr3.txt
  rc3=$?
  if [ $rc3 -eq 0 ] && [ -f "$dst.tmp.mp4" ] && [ -s "$dst.tmp.mp4" ]; then
    mv "$dst.tmp.mp4" "$dst"
    rec_size=$(stat -c%s "$dst" 2>/dev/null || echo 0)
    echo "[OK3 ] $base recovered=$rec_size/$size" >> "$LOG"
    ok=$((ok + 1))
    total_bytes=$((total_bytes + rec_size))
    continue
  fi
  rm -f "$dst.tmp.mp4"

  err1=$(cat /tmp/fferr1.txt 2>/dev/null | head -1)
  echo "[FAIL] $base rc1=$rc1 rc2=$rc2 rc3=$rc3 err=$err1" >> "$LOG"
  fail=$((fail + 1))
done

{
  echo ""
  echo "=== SUMMARY ==="
  echo "recovered: $ok"
  echo "failed:    $fail"
  echo "total bytes recovered: $total_bytes bytes"
} >> "$LOG"

cat "$LOG"