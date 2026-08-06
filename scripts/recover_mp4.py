#!/usr/bin/env python3
"""recover_mp4.py — Recover MP4 files with missing moov atom.

Strategy:
  1. Scan raw mdat for H264/HEVC NAL start codes
  2. Extract SPS/PPS/SPSext from NAL units
  3. Build minimal moov atom referencing existing mdat
  4. Write fixed MP4 via ffmpeg remux

Target: Python 3.5.3, aarch64 NAS, ffmpeg 3.2
"""
import struct
import os
import sys
import subprocess
import re
import time

FFPROBE = "/usr/bin/ffprobe"
FFMPEG = "/usr/bin/ffmpeg"


def scan_mdat_for_nals(data, offset, end):
    """Scan for H264 NAL units in raw Annex B data. Return NAL types found."""
    nal_types = {}
    i = offset
    while i < end - 4:
        # H264 Annex B start code: 00 00 00 01 or 00 00 01
        if data[i:i+3] == b'\x00\x00\x01':
            nalu_type = data[i+3] & 0x1F
            if nalu_type not in nal_types:
                nal_types[nalu_type] = i
            i += 4
        elif data[i:i+4] == b'\x00\x00\x00\x01':
            nalu_type = data[i+4] & 0x1F
            if nalu_type not in nal_types:
                nal_types[nalu_type] = i
            i += 5
        else:
            i += 1
    return nal_types


def scan_mdat_for_hevc(data, offset, end):
    """Scan for HEVC NAL units (NAL type is upper 6 bits of first byte after start code)."""
    nal_types = {}
    i = offset
    while i < end - 4:
        if data[i:i+4] == b'\x00\x00\x00\x01':
            nalu_type = (data[i+4] >> 1) & 0x3F
            if nalu_type not in nal_types:
                nal_types[nalu_type] = i
            i += 5
        elif data[i:i+3] == b'\x00\x00\x01':
            nalu_type = (data[i+3] >> 1) & 0x3F
            if nalu_type not in nal_types:
                nal_types[nalu_type] = i
            i += 4
        else:
            i += 1
    return nal_types


def extract_nalu(data, start, end):
    """Extract a single NAL unit from raw data (find next start code boundary)."""
    # Find start code
    if data[start:start+4] == b'\x00\x00\x00\x01':
        payload_start = start + 4
    elif data[start:start+3] == b'\x00\x00\x01':
        payload_start = start + 3
    else:
        return None

    # Find next start code
    i = payload_start
    while i < end - 3:
        if data[i:i+3] == b'\x00\x00\x01' or data[i:i+4] == b'\x00\x00\x00\x01':
            return data[start:i]
        i += 1
    return data[start:end]


def extract_h264_stream_info(data, mdat_offset, mdat_end):
    """Extract H264 stream info (SPS/PPS, dimensions, framerate hints) from raw mdat."""
    nal_types = scan_mdat_for_nals(data, mdat_offset, mdat_end)
    info = {"codec": "h264", "nal_types": nal_types}

    # H264 NAL types: 7=SPS, 8=PPS, 5=IDR, 1=P-slice
    if 7 in nal_types:
        sps_data = extract_nalu(data, nal_types[7], mdat_end)
        if sps_data and len(sps_data) > 5:
            info["sps"] = sps_data
            # Parse SPS for dimensions (simplified - profile_idc, constraint, level, then resolution)
            try:
                # Find SPS RBSP (skip start code + NAL header byte)
                rbsp = sps_data[4:] if sps_data[:4] == b'\x00\x00\x00\x01' else sps_data[3:]
                if len(rbsp) > 10:
                    info["sps_raw"] = rbsp
            except Exception:
                pass

    if 8 in nal_types:
        pps_data = extract_nalu(data, nal_types[8], mdat_end)
        if pps_data:
            info["pps"] = pps_data

    info["has_idr"] = 5 in nal_types
    info["has_slice"] = 1 in nal_types
    info["has_sps"] = 7 in nal_types
    info["has_pps"] = 8 in nal_types
    return info


def extract_hevc_stream_info(data, mdat_offset, mdat_end):
    """Extract HEVC stream info from raw mdat."""
    nal_types = scan_mdat_for_hevc(data, mdat_offset, mdat_end)
    info = {"codec": "hevc", "nal_types": nal_types}

    # HEVC NAL types: 32=VPS, 33=SPS, 34=PPS, 19/20=IDR
    if 33 in nal_types:
        info["sps"] = extract_nalu(data, nal_types[33], mdat_end)
    if 34 in nal_types:
        info["pps"] = extract_nalu(data, nal_types[34], mdat_end)
    if 32 in nal_types:
        info["vps"] = extract_nalu(data, nal_types[32], mdat_end)

    info["has_vps"] = 32 in nal_types
    info["has_sps"] = 33 in nal_types
    info["has_pps"] = 34 in nal_types
    info["has_idr"] = 19 in nal_types or 20 in nal_types
    return info


def write_h264_raw_to_mp4(sps_raw, pps_raw, input_path, output_path):
    """Write H264 Annex B raw to MP4 via ffmpeg."""
    # Create a temporary Annex B file with SPS/PPS headers
    tmp_annex = input_path + ".annex.tmp"
    tmp_mp4 = input_path + ".recover.tmp.mp4"

    try:
        # Write SPS/PPS as Annex B header
        with open(tmp_annex, "wb") as f:
            f.write(b'\x00\x00\x00\x01')
            f.write(sps_raw)
            f.write(b'\x00\x00\x00\x01')
            f.write(pps_raw)

        # Try remux with -c copy (just needs valid mdat structure)
        r = subprocess.run(
            [FFMPEG, "-y", "-v", "error",
             "-r", "30",  # assume 30fps for screen recording
             "-i", input_path,
             "-c", "copy",
             "-movflags", "+faststart",
             tmp_mp4],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60
        )
        if r.returncode == 0 and os.path.exists(tmp_mp4) and os.path.getsize(tmp_mp4) > 1000:
            os.rename(tmp_mp4, output_path)
            return True
        os.remove(tmp_mp4) if os.path.exists(tmp_mp4) else None
    except Exception as e:
        os.remove(tmp_annex) if os.path.exists(tmp_annex) else None
        os.remove(tmp_mp4) if os.path.exists(tmp_mp4) else None
    return False


def try_ffmpeg_probe_first(path):
    """Quick check: can ffmpeg already read this file?"""
    try:
        r = subprocess.run(
            [FFPROBE, "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=codec_name",
             "-of", "csv=p=0", path],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10
        )
        codec = r.stdout.decode("utf-8", errors="ignore").strip()
        if codec and codec != "unknown":
            return codec
    except Exception:
        pass
    return None


def recover_one(src, dst):
    """Attempt to recover a single MP4 file.

    Returns (recovered_path_or_None, codec_used, notes).
    """
    # Quick probe - if already works, just copy
    codec = try_ffmpeg_probe_first(src)
    if codec:
        return src, codec, "already_valid"

    try:
        sz = os.path.getsize(src)
    except OSError:
        return None, None, "stat_failed"

    # Read file to scan for NAL units
    # Only read first 100MB for NAL scanning (don't need entire 500MB)
    scan_limit = min(sz, 100 * 1024 * 1024)

    try:
        with open(src, "rb") as f:
            # Scan first 1MB for mdat atom
            header = f.read(min(1024 * 1024, sz))
    except IOError:
        return None, None, "read_failed"

    # Find mdat atom position
    mdat_offset = None
    i = 0
    while i < len(header) - 8:
        atom_size = struct.unpack(">I", header[i:i+4])[0]
        atom_type = header[i+4:i+8]
        if atom_type == b'mdat':
            mdat_offset = i + 8  # skip atom header
            mdat_atom_size = atom_size - 8 if atom_size > 8 else sz - i - 8
            break
        if atom_size < 8 or atom_size > sz:
            break
        i += atom_size

    if mdat_offset is None:
        # No mdat found in first MB, assume it starts after ftyp (typical layout)
        # Look for ftyp box
        ftyp_end = header.find(b'ftyp')
        if ftyp_end >= 0:
            # Skip ftyp box
            i = ftyp_end - 4
            if i >= 0 and i < len(header) - 8:
                ftyp_size = struct.unpack(">I", header[i:i+4])[0]
                mdat_offset = i + ftyp_size
                mdat_atom_size = sz - mdat_offset
        else:
            return None, None, "no_mdat_found"

    # Read mdat region for NAL scanning
    try:
        with open(src, "rb") as f:
            f.seek(mdat_offset)
            mdat_data = f.read(min(scan_limit, sz - mdat_offset))
    except IOError:
        return None, None, "mdat_read_failed"

    mdat_end = len(mdat_data)

    # Detect codec: try H264 first, then HEVC
    h264_info = extract_h264_stream_info(mdat_data, 0, mdat_end)
    hevc_info = extract_hevc_stream_info(mdat_data, 0, mdat_end)

    if h264_info.get("has_sps") and h264_info.get("has_pps"):
        info = h264_info
        codec_name = "h264"
    elif hevc_info.get("has_sps") and hevc_info.get("has_pps"):
        info = hevc_info
        codec_name = "hevc"
    elif h264_info.get("has_idr") or h264_info.get("has_slice"):
        info = h264_info
        codec_name = "h264"
    elif hevc_info.get("has_idr"):
        info = hevc_info
        codec_name = "hevc"
    else:
        return None, None, "no_codec_detected"

    # For H264 with SPS+PPS: write raw Annex B then remux
    if codec_name == "h264" and "sps_raw" in info and "pps" in info:
        pps_nalu = info["pps"]
        pps_raw = pps_nalu[3:] if pps_nalu[:3] == b'\x00\x00\x01' else pps_nalu[4:]
        if write_h264_raw_to_mp4(info["sps_raw"], pps_raw, src, dst):
            return dst, codec_name, "recovered_h264_annex_b"

    # Fallback: try ffmpeg with error-tolerant flags
    tmp_mp4 = dst + ".tmp.mp4"
    try:
        r = subprocess.run(
            [FFMPEG, "-y", "-v", "error",
             "-fflags", "+genpts+igndts",
             "-i", src,
             "-c", "copy",
             "-movflags", "+faststart",
             tmp_mp4],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120
        )
        if r.returncode == 0 and os.path.exists(tmp_mp4) and os.path.getsize(tmp_mp4) > 1000:
            os.rename(tmp_mp4, dst)
            return dst, codec_name, "recovered_ffmpeg_tolerant"
        if os.path.exists(tmp_mp4):
            os.remove(tmp_mp4)
    except Exception:
        if os.path.exists(tmp_mp4):
            os.remove(tmp_mp4)

    # Last resort: for H264, extract raw H264 stream and wrap in MP4
    if codec_name == "h264" and info.get("sps"):
        tmp_raw = dst + ".raw.h264"
        try:
            # Write raw Annex B H264
            with open(tmp_raw, "wb") as f:
                if "sps" in info:
                    f.write(b'\x00\x00\x00\x01')
                    f.write(info["sps"][3:] if info["sps"][:3] == b'\x00\x00\x01' else info["sps"][4:])
                if "pps" in info:
                    f.write(b'\x00\x00\x00\x01')
                    f.write(info["pps"][3:] if info["pps"][:3] == b'\x00\x00\x01' else info["pps"][4:])
                # Append raw mdat content
                f.write(mdat_data)

            r = subprocess.run(
                [FFMPEG, "-y", "-v", "error",
                 "-r", "30",
                 "-i", tmp_raw,
                 "-c", "copy",
                 "-movflags", "+faststart",
                 dst],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120
            )
            os.remove(tmp_raw) if os.path.exists(tmp_raw) else None
            if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 1000:
                return dst, codec_name, "recovered_raw_h264_wrap"
        except Exception:
            os.remove(tmp_raw) if os.path.exists(tmp_raw) else None

    return None, codec_name, "all_methods_failed"


def main():
    if len(sys.argv) < 3:
        print("Usage: recover_mp4.py <src_dir> <dst_dir>")
        sys.exit(1)

    src_dir = sys.argv[1]
    dst_dir = sys.argv[2]
    os.makedirs(dst_dir, exist_ok=True)

    files = sorted([f for f in os.listdir(src_dir) if f.endswith(".mp4")])
    print("Found %d files to recover" % len(files))

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
        print("[TRY ] %s (%d MB)..." % (fname, sz / (1024*1024)))

        t0 = time.time()
        recovered_path, codec, note = recover_one(src, dst)
        elapsed = time.time() - t0

        if recovered_path and os.path.exists(recovered_path):
            rec_sz = os.path.getsize(recovered_path)
            print("[OK  ] %s codec=%s recovered=%d/%d bytes (%.1fs) [%s]" % (
                fname, codec, rec_sz, sz, elapsed, note))
            ok += 1
            total_recovered += rec_sz
        else:
            print("[FAIL] %s codec=%s note=%s (%.1fs)" % (fname, codec, note, elapsed))
            fail += 1

    print("")
    print("=== SUMMARY ===")
    print("recovered:    %d" % ok)
    print("failed:       %d" % fail)
    print("total bytes:  %d" % total_recovered)


if __name__ == "__main__":
    main()
