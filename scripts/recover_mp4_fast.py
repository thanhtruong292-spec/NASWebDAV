#!/usr/bin/env python3
"""recover_mp4_fast.py - Fast MP4 recovery for files missing moov atom.

Reads only first 64KB of mdat to detect codec, then uses ffmpeg to remux.
Python 3.5 compatible.
"""
import struct
import os
import sys
import subprocess
import time

FFMPEG = "/usr/bin/ffmpeg"
FFPROBE = "/usr/bin/ffprobe"
SCAN_BYTES = 65536  # 64KB - enough to find SPS/PPS/VPS


def find_mdat_offset(src):
    """Find mdat atom offset by scanning first 2MB for atom boxes."""
    try:
        with open(src, "rb") as f:
            header = f.read(min(2 * 1024 * 1024, os.path.getsize(src)))
    except IOError:
        return None, None

    i = 0
    total = len(header)
    while i < total - 8:
        atom_size = struct.unpack(">I", header[i:i+4])[0]
        atom_type = header[i+4:i+8]
        if atom_type == b'mdat':
            return i + 8, atom_size - 8 if atom_size > 8 else None
        if atom_size < 8 or atom_size > total - i:
            # Try reading as extended size (64-bit)
            if i + 16 <= total and header[i+4:i+8] == b'\x00\x00\x00\x01':
                ext_size = struct.unpack(">Q", header[i+8:i+16])[0]
                if ext_size > 8:
                    return i + 16, ext_size - 16
            break
        i += atom_size
    return None, None


def detect_codec(src, mdat_offset):
    """Read first 64KB of mdat, detect codec by NAL unit types."""
    try:
        with open(src, "rb") as f:
            f.seek(mdat_offset)
            data = f.read(SCAN_BYTES)
    except IOError:
        return None

    if len(data) < 10:
        return None

    # Check for Annex B start codes
    if data[:3] == b'\x00\x00\x01' or data[:4] == b'\x00\x00\x00\x01':
        first_byte = data[3] if data[:3] == b'\x00\x00\x01' else data[4]
        # H264 NAL: lower 5 bits = type
        h264_type = first_byte & 0x1F
        # HEVC NAL: upper 6 bits (first_byte >> 1) & 0x3F
        hevc_type = (first_byte >> 1) & 0x3F

        # HEVC VPS=32, SPS=33, PPS=34
        if hevc_type in (32, 33, 34):
            return "hevc"
        # H264 SPS=7, PPS=8, IDR=5
        if h264_type in (5, 7, 8):
            return "h264"

    # Scan first 4KB for start codes to determine codec
    h264_nals = set()
    hevc_nals = set()
    search = data[:4096]
    pos = 0
    while pos < len(search) - 4:
        if search[pos:pos+4] == b'\x00\x00\x00\x01':
            nalu = search[pos+4]
            h264_nals.add(nalu & 0x1F)
            hevc_nals.add((nalu >> 1) & 0x3F)
            pos += 5
        elif search[pos:pos+3] == b'\x00\x00\x01':
            nalu = search[pos+3]
            h264_nals.add(nalu & 0x1F)
            hevc_nals.add((nalu >> 1) & 0x3F)
            pos += 4
        else:
            pos += 1

    if hevc_nals & {32, 33, 34}:
        return "hevc"
    if h264_nals & {5, 7, 8}:
        return "h264"

    # Default: assume h264 (most screen recordings)
    return "h264"


def recover_with_ffmpeg(src, dst, codec):
    """Try multiple ffmpeg strategies to remux a broken MP4."""
    tmp = dst + ".tmp.mp4"

    # Strategy 1: force codec container mode
    fmt = "h264" if codec == "h264" else "hevc"
    try:
        r = subprocess.run(
            [FFMPEG, "-y", "-v", "error",
             "-f", fmt, "-r", "30", "-i", src,
             "-c", "copy",
             "-movflags", "+faststart",
             tmp],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120
        )
        if r.returncode == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 1000:
            os.rename(tmp, dst)
            return True, "fmt_%s_remux" % fmt
    except Exception:
        pass
    if os.path.exists(tmp):
        os.remove(tmp)

    # Strategy 2: use error-tolerant input with codec hint
    try:
        args = [FFMPEG, "-y", "-v", "error",
                "-fflags", "+genpts+igndts+discardcorrupt",
                "-analyzeduration", "0", "-probesize", "32",
                "-f", fmt, "-r", "30", "-i", src,
                "-c", "copy",
                "-movflags", "+faststart",
                tmp]
        r = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        if r.returncode == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 1000:
            os.rename(tmp, dst)
            return True, "fmt_%s_tolerant" % fmt
    except Exception:
        pass
    if os.path.exists(tmp):
        os.remove(tmp)

    # Strategy 3: extract raw bitstream from mdat, re-wrap
    mdat_off, mdat_sz = find_mdat_offset(src)
    if mdat_off and mdat_sz:
        raw_path = dst + ".raw"
        try:
            with open(src, "rb") as f:
                f.seek(mdat_off)
                raw_data = f.read(min(mdat_sz, 200 * 1024 * 1024))
            with open(raw_path, "wb") as f:
                f.write(raw_data)

            r = subprocess.run(
                [FFMPEG, "-y", "-v", "error",
                 "-f", fmt, "-r", "30", "-i", raw_path,
                 "-c", "copy",
                 "-movflags", "+faststart",
                 tmp],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120
            )
            os.remove(raw_path) if os.path.exists(raw_path) else None
            if r.returncode == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 1000:
                os.rename(tmp, dst)
                return True, "raw_mdat_rewrap"
        except Exception:
            os.remove(raw_path) if os.path.exists(raw_path) else None
    if os.path.exists(tmp):
        os.remove(tmp)

    return False, "all_failed"


def recover_one(src, dst):
    """Recover a single MP4 file. Returns (path_or_None, codec, note)."""
    # Quick probe
    try:
        r = subprocess.run(
            [FFPROBE, "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=codec_name", "-of", "csv=p=0", src],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10
        )
        codec = r.stdout.decode("utf-8", errors="ignore").strip()
        if codec and codec != "unknown":
            # Already valid - just copy
            import shutil
            shutil.copy2(src, dst)
            return dst, codec, "already_valid"
    except Exception:
        pass

    mdat_off, mdat_sz = find_mdat_offset(src)
    if mdat_off is None:
        return None, None, "no_mdat"

    codec = detect_codec(src, mdat_off)
    if not codec:
        return None, None, "unknown_codec"

    ok, note = recover_with_ffmpeg(src, dst, codec)
    if ok:
        return dst, codec, note
    return None, codec, note


def main():
    if len(sys.argv) < 3:
        print("Usage: recover_mp4_fast.py <src_dir> <dst_dir>")
        sys.exit(1)

    src_dir = sys.argv[1]
    dst_dir = sys.argv[2]
    os.makedirs(dst_dir, exist_ok=True)

    files = sorted([f for f in os.listdir(src_dir) if f.endswith(".mp4")])
    print("Found %d files to recover" % len(files))
    print("")

    ok = 0
    fail = 0
    total_recovered = 0

    for fname in files:
        src = os.path.join(src_dir, fname)
        dst = os.path.join(dst_dir, fname)
        if os.path.exists(dst) and os.path.getsize(dst) > 0:
            print("[SKIP] %s (already recovered)" % fname)
            ok += 1
            continue

        sz = os.path.getsize(src) if os.path.exists(src) else 0
        print("[TRY ] %s (%.1f MB)..." % (fname, sz / (1024.0 * 1024.0)), end=" ")
        sys.stdout.flush()

        t0 = time.time()
        recovered_path, codec, note = recover_one(src, dst)
        elapsed = time.time() - t0

        if recovered_path and os.path.exists(recovered_path):
            rec_sz = os.path.getsize(recovered_path)
            ratio = rec_sz * 100.0 / sz if sz > 0 else 0
            print("OK codec=%s recovered=%.1fMB/%.1fMB (%.0f%%) %.1fs [%s]" % (
                codec, rec_sz / (1024.0 * 1024.0), sz / (1024.0 * 1024.0), ratio, elapsed, note))
            ok += 1
            total_recovered += rec_sz
        else:
            print("FAIL codec=%s note=%s %.1fs" % (codec, note, elapsed))
            fail += 1

    print("")
    print("=== SUMMARY ===")
    print("recovered:    %d" % ok)
    print("failed:       %d" % fail)
    print("total bytes:  %d (%.1f MB)" % (total_recovered, total_recovered / (1024.0 * 1024.0)))


if __name__ == "__main__":
    main()
