#!/bin/bash
# One-shot remux all existing .flv in Livestream dir -> .mp4
# Cung logic 3-pass nhu _remux_flv_to_mp4 trong nas_api_server.py.
# Chay nohup, log /tmp/flv_repair.log

LIVE_DIR="/srv/dev-disk-by-label-data/New folder/Livestream"
LOG=/tmp/flv_repair.log
echo "[$(date)] Bat dau remux FLV trong $LIVE_DIR" > "$LOG"

OK=0
FAIL=0
SKIP=0

while IFS= read -r -d $'\0' flv; do
    if [ ! -s "$flv" ]; then
        echo "[SKIP empty] $flv" >> "$LOG"
        SKIP=$((SKIP+1))
        continue
    fi
    base="${flv%.flv}"
    mp4="${base}.mp4"
    if [ -f "$mp4" ]; then
        echo "[SKIP mp4-exists] $flv" >> "$LOG"
        SKIP=$((SKIP+1))
        continue
    fi
    # Pass 1: aac_adtstoasc + faststart
    if ffmpeg -y -loglevel error -fflags +genpts -i "$flv" \
        -c copy -movflags +faststart -bsf:a aac_adtstoasc "$mp4" 2>>"$LOG"; then
        if [ -s "$mp4" ] && [ "$(stat -c%s "$mp4")" -gt 1024 ]; then
            rm -f "$flv"
            echo "[OK pass1] $flv" >> "$LOG"
            OK=$((OK+1))
            continue
        fi
        rm -f "$mp4"
    fi
    # Pass 2: bo aac BSF
    if ffmpeg -y -loglevel error -fflags +genpts -i "$flv" \
        -c copy -movflags +faststart "$mp4" 2>>"$LOG"; then
        if [ -s "$mp4" ] && [ "$(stat -c%s "$mp4")" -gt 1024 ]; then
            rm -f "$flv"
            echo "[OK pass2] $flv" >> "$LOG"
            OK=$((OK+1))
            continue
        fi
        rm -f "$mp4"
    fi
    # Pass 3: them h264_mp4toannexb video BSF
    if ffmpeg -y -loglevel error -fflags +genpts -i "$flv" \
        -c copy -bsf:v h264_mp4toannexb -movflags +faststart "$mp4" 2>>"$LOG"; then
        if [ -s "$mp4" ] && [ "$(stat -c%s "$mp4")" -gt 1024 ]; then
            rm -f "$flv"
            echo "[OK pass3] $flv" >> "$LOG"
            OK=$((OK+1))
            continue
        fi
        rm -f "$mp4"
    fi
    # Het cach: rename .flv -> .broken.flv
    mv "$flv" "${flv}.broken"
    echo "[FAIL all3] $flv -> renamed .broken" >> "$LOG"
    FAIL=$((FAIL+1))
done < <(find "$LIVE_DIR" -maxdepth 1 -type f -name "*.flv" -print0)

echo "[$(date)] DONE — OK=$OK FAIL=$FAIL SKIP=$SKIP" >> "$LOG"
echo "DONE: OK=$OK FAIL=$FAIL SKIP=$SKIP"
