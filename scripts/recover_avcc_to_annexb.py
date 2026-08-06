#!/usr/bin/env python3
"""recover_avcc_to_annexb.py — Convert AVCC/HVCC MP4 mdat to Annex B H264, remux to MP4.

For MP4 files missing moov atom where mdat contains valid H.264 data
in AVCC format (4-byte length-prefixed NALUs).

Python 3.5 compatible.
"""
import struct
import os
import sys
import subprocess
import time

FFMPEG = "/usr/bin/ffmpeg"
FFPROBE = "/usr/bin/ffprobe"


def find_mdat_info(src):
    """Parse MP4 atoms to find mdat offset and actual byte count."""
    try:
        sz = os.path.getsize(src)
        with open(src, "rb") as f:
            # Read enough to find ftyp + mdat header
            header = f.read(min(256, sz))
    except IOError:
        return None, None, None

    if len(header) < 20:
        return None, None, None

    # Parse ftyp
    ftyp_sz = struct.unpack(">I", header[0:4])[0]
    ftyp_type = header[4:8]
    if ftyp_type != b'ftyp' or ftyp_sz < 8 or ftyp_sz > 128:
        return None, None, None

    # Parse mdat
    pos = ftyp_sz
    if pos + 8 > len(header):
        return None, None, None

    mdat_size_field = struct.unpack(">I", header[pos:pos+4])[0]
    mdat_type = header[pos+4:pos+8]
    if mdat_type != b'mdat':
        return None, None, None

    mdat_header_sz = 8
    if mdat_size_field == 1:
        # 64-bit extended size
        mdat_header_sz = 16

    mdat_offset = pos + mdat_header_sz
    mdat_bytes = sz - mdat_offset

    if mdat_bytes < 100:
        return None, None, None

    return mdat_offset, mdat_bytes, sz


def convert_avcc_to_annexb(src, mdat_offset, mdat_bytes, out_path, max_bytes=None):
    """Read mdat, convert length-prefixed NALUs to Annex B start codes."""
    limit = min(mdat_bytes, max_bytes) if max_bytes else mdat_bytes

    try:
        with open(src, "rb") as f:
            f.seek(mdat_offset)
            data = f.read(limit)
    except IOError:
        return False

    if len(data) < 8:
        return False

    # Check first NALU: is it length-prefixed (AVCC) or Annex B start codes?
    first4 = struct.unpack(">I", data[0:4])[0]
    if first4 == 0x00000001 or first4 >> 8 == 0x000001:
        # Already Annex B - just copy
        with open(out_path, "wb") as f:
            f.write(data)
        return True

    # AVCC format: 4-byte length prefix, then NALU data
    # Scan through, write Annex B format
    Annex_B_START = b'\x00\x00\x00\x01'
    output = bytearray()
    pos = 0
    nalus_written = 0
    first_nalu = True

    while pos + 4 <= len(data):
        nalu_len = struct.unpack(">I", data[pos:pos+4])[0]
        pos += 4

        if nalu_len <= 0 or pos + nalu_len > len(data):
            break

        nalu_data = data[pos:pos+nalu_len]
        pos += nalu_len

        # Write Annex B start code + NALU
        output.extend(Annex_B_START)
        output.extend(nalu_data)
        nalus_written += 1

        if first_nalu:
            first_nalu = False

    if nalus_written == 0:
        return False

    with open(out_path, "wb") as f:
        f.write(output)
    return True


def remux_annexb_to_mp4(annexb_path, output_path, fps="30"):
    """Wrap Annex B H264 bitstream into MP4 container."""
    tmp = output_path + ".tmp.mp4"
    try:
        r = subprocess.run(
            [FFMPEG, "-y", "-v", "error",
             "-r", fps,
             "-i", annexb_path,
             "-c", "copy",
             "-movflags", "+faststart",
             tmp],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120
        )
        if r.returncode == 0 and os.path.exists(tmp) and os.path.getsize(tmp) > 1000:
            os.rename(tmp, output_path)
            return True, "remux_ok"
    except Exception:
        pass
    if os.path.exists(tmp):
        os.remove(tmp)
    return False, "remux_failed"


def detect_codec_from_nalu(data):
    """Detect H264 vs HEVC from NALU data (after length prefix removed)."""
    if len(data) < 2:
        return "h264"
    first_byte = data[0]
    h264_type = first_byte & 0x1F
    hevc_type = (first_byte >> 1) & 0x3F
    # HEVC NAL types: 32=VPS, 33=SPS, 34=PPS, 19=IDR_W_RADL, 20=IDR_N_LP
    if hevc_type in (32, 33, 34, 19, 20):
        return "hevc"
    return "h264"


def recover_one(src, dst, annexb_dir=None):
    """Recover a single MP4 file. Returns (path_or_None, note)."""
    # Quick validity check
    try:
        r = subprocess.run(
            [FFPROBE, "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=codec_name", "-of", "csv=p=0", src],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10
        )
        codec = r.stdout.decode("utf-8", errors="ignore").strip()
        if codec and codec != "unknown":
            import shutil
            shutil.copy2(src, dst)
            return dst, "already_valid_%s" % codec
    except Exception:
        pass

    mdat_off, mdat_bytes, total_sz = find_mdat_info(src)
    if mdat_off is None:
        return None, "no_mdat"

    # Read first NALU to detect codec
    try:
        with open(src, "rb") as f:
            f.seek(mdat_off)
            probe = f.read(16)
        if len(probe) >= 5:
            codec = detect_codec_from_nalu(probe[4:])  # skip length prefix
        else:
            codec = "h264"
    except IOError:
        codec = "h264"

    if codec != "h264":
        return None, "codec_%s_not_h264" % codec

    # Convert AVCC to Annex B
    annexb_path = dst + ".annexb.tmp"
    try:
        ok = convert_avcc_to_annexb(src, mdat_off, mdat_bytes, annexb_path, max_bytes=150*1024*1024)
        if not ok or not os.path.exists(annexb_path) or os.path.getsize(annexb_path) < 100:
            if os.path.exists(annexb_path):
                os.remove(annexb_path)
            return None, "avcc_to_annexb_failed"

        # Remux Annex B to MP4
        ok2, note = remux_annexb_to_mp4(annexb_path, dst)
        os.remove(annexb_path) if os.path.exists(annexb_path) else None
        if ok2:
            # Verify the output is actually playable
            try:
                r = subprocess.run(
                    [FFPROBE, "-v", "error", "-select_streams", "v:0",
                     "-show_entries", "stream=codec_name,duration,nb_frames",
                     "-of", "csv=p=0", dst],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10
                )
                info = r.stdout.decode("utf-8", errors="ignore").strip()
                return dst, "recovered_%s_info=%s" % (codec, info)
            except Exception:
                return dst, "recovered_%s_verify_unknown" % codec
        return None, "remux_failed"
    except Exception as e:
        if os.path.exists(annexb_path):
            os.remove(annexb_path)
        return None, "exception_%s" % str(e)[:80]


def main():
    if len(sys.argv) < 3:
        print("Usage: recover_avcc_to_annexb.py <src_dir> <dst_dir>")
        sys.exit(1)

    src_dir = sys.argv[1]
    dst_dir = sys.argv[2]
    annexb_dir = dst_dir + "_annexb"
    os.makedirs(dst_dir, exist_ok=True)
    os.makedirs(annexb_dir, exist_ok=True)

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
        recovered_path, note = recover_one(src, dst)
        elapsed = time.time() - t0

        if recovered_path and os.path.exists(recovered_path):
            rec_sz = os.path.getsize(recovered_path)
            ratio = rec_sz * 100.0 / sz if sz > 0 else 0
            print("OK recovered=%.1fMB/%.1fMB (%.0f%%) %.1fs [%s]" % (
                rec_sz / (1024.0 * 1024.0), sz / (1024.0 * 1024.0), ratio, elapsed, note))
            ok += 1
            total_recovered += rec_sz
        else:
            print("FAIL note=%s %.1fs" % (note, elapsed))
            fail += 1

    print("")
    print("=== SUMMARY ===")
    print("recovered:    %d" % ok)
    print("failed:       %d" % fail)
    print("total bytes:  %d (%.1f MB)" % (total_recovered, total_recovered / (1024.0 * 1024.0)))


if __name__ == "__main__":
    main()
