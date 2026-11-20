use crate::mp4_parser::parse_mp4_header;
use serde::Serialize;
use std::fs::File;
use std::io::Read;
use std::path::Path;

#[derive(Debug, Serialize, Default, Clone)]
pub struct MediaHeaderInfo {
    pub valid: bool,
    pub container: String,
    pub duration_secs: f64,
    pub video_codec: String,
    pub audio_codec: String,
    pub has_moov: bool,
}

/// Đọc EBML VINT cho Element ID (giữ nguyên bit dẫn đầu theo chuẩn Matroska/WebM).
fn read_ebml_id(data: &[u8], pos: &mut usize) -> Option<u32> {
    if *pos >= data.len() {
        return None;
    }
    let first = data[*pos];
    if first == 0 {
        return None;
    }
    let len = first.leading_zeros() as usize + 1;
    if len > 4 || *pos + len > data.len() {
        return None;
    }
    let mut val = 0u32;
    for i in 0..len {
        val = (val << 8) | (data[*pos + i] as u32);
    }
    *pos += len;
    Some(val)
}

/// Đọc EBML VINT cho Element Data Size (xóa bit dẫn đầu).
fn read_ebml_size(data: &[u8], pos: &mut usize) -> Option<u64> {
    if *pos >= data.len() {
        return None;
    }
    let first = data[*pos];
    if first == 0 {
        return None;
    }
    let len = first.leading_zeros() as usize + 1;
    if len > 8 || *pos + len > data.len() {
        return None;
    }
    let mask = if len == 8 { 0 } else { (1u8 << (8 - len)) - 1 };
    let mut val = (first & mask) as u64;
    for i in 1..len {
        val = (val << 8) | (data[*pos + i] as u64);
    }
    *pos += len;
    // Unknown / streaming size (tất cả bit 1)
    let all_ones = if len == 8 {
        (1u64 << 56) - 1
    } else {
        (1u64 << (7 * len)) - 1
    };
    if val == all_ones {
        return Some(u64::MAX);
    }
    Some(val)
}

fn read_uint_be(slice: &[u8]) -> u64 {
    let mut v = 0u64;
    for &b in slice.iter().take(8) {
        v = (v << 8) | (b as u64);
    }
    v
}

fn read_float_be(slice: &[u8]) -> Option<f64> {
    if slice.len() == 4 {
        let arr: [u8; 4] = [slice[0], slice[1], slice[2], slice[3]];
        Some(f32::from_be_bytes(arr) as f64)
    } else if slice.len() == 8 {
        let arr: [u8; 8] = [
            slice[0], slice[1], slice[2], slice[3], slice[4], slice[5], slice[6], slice[7],
        ];
        Some(f64::from_be_bytes(arr))
    } else {
        None
    }
}

fn map_mkv_codec_id(raw: &str) -> String {
    match raw {
        "V_MPEG4/ISO/AVC" => "h264".to_string(),
        "V_MPEGH/ISO/HEVC" => "hevc".to_string(),
        "V_VP8" => "vp8".to_string(),
        "V_VP9" => "vp9".to_string(),
        "V_AV1" => "av1".to_string(),
        "V_MPEG2" => "mpeg2video".to_string(),
        "V_MPEG4/ISO/ASP" | "V_MPEG4/ISO/SP" => "mpeg4".to_string(),
        "A_AAC" | "A_AAC/MPEG4/LC" | "A_AAC/MPEG2/LC" => "aac".to_string(),
        "A_OPUS" => "opus".to_string(),
        "A_VORBIS" => "vorbis".to_string(),
        "A_AC3" => "ac3".to_string(),
        "A_EAC3" => "eac3".to_string(),
        "A_DTS" => "dts".to_string(),
        "A_FLAC" => "flac".to_string(),
        "A_MPEG/L3" => "mp3".to_string(),
        other => other.to_ascii_lowercase(),
    }
}

/// Phân tích header nhị phân MKV / WebM (EBML) trong 2MB đầu file không cần fork `ffprobe`.
pub fn parse_ebml_header(path: &Path) -> Result<MediaHeaderInfo, String> {
    let mut f = File::open(path).map_err(|e| format!("open_failed: {}", e))?;
    let mut buf = vec![0u8; 2 * 1024 * 1024];
    let n = f.read(&mut buf).map_err(|e| format!("read_failed: {}", e))?;
    buf.truncate(n);

    if buf.len() < 4 || buf[0..4] != [0x1A, 0x45, 0xDF, 0xA3] {
        return Err("not_ebml".to_string());
    }

    let mut pos = 0usize;
    let mut timestamp_scale: u64 = 1_000_000; // Mặc định Matroska = 1ms
    let mut raw_duration: Option<f64> = None;
    let mut video_codec = String::new();
    let mut audio_codec = String::new();
    let mut container = "mkv".to_string();

    while pos < buf.len() {
        let id = match read_ebml_id(&buf, &mut pos) {
            Some(v) => v,
            None => break,
        };
        let size = match read_ebml_size(&buf, &mut pos) {
            Some(v) => v,
            None => break,
        };
        let end = if size == u64::MAX {
            buf.len()
        } else {
            pos.saturating_add(size as usize).min(buf.len())
        };

        match id {
            0x1A45DFA3 => {
                // EBML Header: kiểm tra DocType ("webm" vs "matroska")
                let mut sub = pos;
                while sub < end {
                    let sid = match read_ebml_id(&buf, &mut sub) {
                        Some(v) => v,
                        None => break,
                    };
                    let ssz = match read_ebml_size(&buf, &mut sub) {
                        Some(v) => v,
                        None => break,
                    };
                    let send = sub.saturating_add(ssz as usize).min(end);
                    if sid == 0x4282 {
                        if let Ok(dt) = std::str::from_utf8(&buf[sub..send]) {
                            if dt.trim_end_matches('\0').eq_ignore_ascii_case("webm") {
                                container = "webm".to_string();
                            }
                        }
                    }
                    sub = send;
                }
                pos = end;
            }
            0x18538067 => {
                // Segment: đi vào bên trong Segment để tìm Info (0x1549A966) và Tracks (0x1654AE6B)
                // Không nhảy qua end, tiếp tục parse các phần tử con ở `pos`
            }
            0x1549A966 => {
                // Segment Info
                let mut sub = pos;
                while sub < end {
                    let sid = match read_ebml_id(&buf, &mut sub) {
                        Some(v) => v,
                        None => break,
                    };
                    let ssz = match read_ebml_size(&buf, &mut sub) {
                        Some(v) => v,
                        None => break,
                    };
                    let send = sub.saturating_add(ssz as usize).min(end);
                    if sid == 0x2AD7B1 {
                        let ts = read_uint_be(&buf[sub..send]);
                        if ts > 0 {
                            timestamp_scale = ts;
                        }
                    } else if sid == 0x4489 {
                        raw_duration = read_float_be(&buf[sub..send]);
                    }
                    sub = send;
                }
                pos = end;
            }
            0x1654AE6B => {
                // Tracks
                let mut sub = pos;
                while sub < end {
                    let sid = match read_ebml_id(&buf, &mut sub) {
                        Some(v) => v,
                        None => break,
                    };
                    let ssz = match read_ebml_size(&buf, &mut sub) {
                        Some(v) => v,
                        None => break,
                    };
                    let send = sub.saturating_add(ssz as usize).min(end);
                    if sid == 0xAE {
                        // TrackEntry
                        let mut tpos = sub;
                        let mut track_type: u64 = 0;
                        let mut codec_raw = String::new();
                        while tpos < send {
                            let tid = match read_ebml_id(&buf, &mut tpos) {
                                Some(v) => v,
                                None => break,
                            };
                            let tsz = match read_ebml_size(&buf, &mut tpos) {
                                Some(v) => v,
                                None => break,
                            };
                            let tend = tpos.saturating_add(tsz as usize).min(send);
                            if tid == 0x83 {
                                track_type = read_uint_be(&buf[tpos..tend]);
                            } else if tid == 0x86 {
                                if let Ok(c) = std::str::from_utf8(&buf[tpos..tend]) {
                                    codec_raw = c.trim_end_matches('\0').to_string();
                                }
                            }
                            tpos = tend;
                        }
                        if track_type == 1 && video_codec.is_empty() && !codec_raw.is_empty() {
                            video_codec = map_mkv_codec_id(&codec_raw);
                        } else if track_type == 2 && audio_codec.is_empty() && !codec_raw.is_empty() {
                            audio_codec = map_mkv_codec_id(&codec_raw);
                        }
                    }
                    sub = send;
                }
                pos = end;
                if raw_duration.is_some() && !video_codec.is_empty() {
                    break;
                }
            }
            0x1F43B675 => {
                // Cluster: đã vào vùng dữ liệu media chính, dừng quét header
                break;
            }
            _ => {
                if size == u64::MAX || end <= pos {
                    break;
                }
                pos = end;
            }
        }
    }

    let duration_secs = match raw_duration {
        Some(d) if d > 0.0 => (d * (timestamp_scale as f64)) / 1_000_000_000.0,
        _ => 0.0,
    };

    Ok(MediaHeaderInfo {
        valid: duration_secs > 0.0 || !video_codec.is_empty(),
        container,
        duration_secs,
        video_codec,
        audio_codec,
        has_moov: true,
    })
}

fn map_flv_video_codec(id: u8) -> &'static str {
    match id {
        2 => "flv1",
        3 => "flashsv",
        4 => "vp6f",
        5 => "vp6a",
        6 => "flashsv2",
        7 => "h264",
        12 => "hevc",
        _ => "",
    }
}

/// Tìm giá trị f64 theo tên key trong payload AMF0 `onMetaData` của FLV.
fn find_amf0_number(payload: &[u8], key: &[u8]) -> Option<f64> {
    if key.len() > 255 || payload.len() < key.len() + 11 {
        return None;
    }
    let kl = key.len();
    for i in 0..=(payload.len() - kl - 11) {
        if payload[i] == 0x00
            && payload[i + 1] == (kl as u8)
            && &payload[i + 2..i + 2 + kl] == key
            && payload[i + 2 + kl] == 0x00
        {
            let num_start = i + 3 + kl;
            let arr: [u8; 8] = [
                payload[num_start],
                payload[num_start + 1],
                payload[num_start + 2],
                payload[num_start + 3],
                payload[num_start + 4],
                payload[num_start + 5],
                payload[num_start + 6],
                payload[num_start + 7],
            ];
            return Some(f64::from_be_bytes(arr));
        }
    }
    None
}

/// Phân tích header nhị phân FLV (AMF0 `onMetaData` + VideoTag CodecID) trong 256KB đầu file.
pub fn parse_flv_header(path: &Path) -> Result<MediaHeaderInfo, String> {
    let mut f = File::open(path).map_err(|e| format!("open_failed: {}", e))?;
    let mut buf = vec![0u8; 256 * 1024];
    let n = f.read(&mut buf).map_err(|e| format!("read_failed: {}", e))?;
    buf.truncate(n);

    if buf.len() < 13 || &buf[0..3] != b"FLV" {
        return Err("not_flv".to_string());
    }

    let data_offset = u32::from_be_bytes([buf[5], buf[6], buf[7], buf[8]]) as usize;
    let mut pos = data_offset + 4; // Bỏ qua PreviousTagSize0 (4 bytes)
    let mut duration_secs = 0.0f64;
    let mut video_codec = String::new();
    let mut audio_codec = String::new();

    while pos + 11 <= buf.len() {
        let tag_type = buf[pos] & 0x1F;
        let data_size = ((buf[pos + 1] as usize) << 16)
            | ((buf[pos + 2] as usize) << 8)
            | (buf[pos + 3] as usize);
        let payload_start = pos + 11;
        let payload_end = payload_start.saturating_add(data_size).min(buf.len());
        let payload = &buf[payload_start..payload_end];

        if tag_type == 18 {
            // ScriptData (onMetaData)
            if let Some(d) = find_amf0_number(payload, b"duration") {
                if d.is_finite() && d > 0.0 {
                    duration_secs = d;
                }
            }
            if video_codec.is_empty() {
                if let Some(vc) = find_amf0_number(payload, b"videocodecid") {
                    let mapped = map_flv_video_codec(vc as u8);
                    if !mapped.is_empty() {
                        video_codec = mapped.to_string();
                    }
                }
            }
        } else if tag_type == 9 && !payload.is_empty() && video_codec.is_empty() {
            // VideoTag: 4 bit thấp là CodecID
            let mapped = map_flv_video_codec(payload[0] & 0x0F);
            if !mapped.is_empty() {
                video_codec = mapped.to_string();
            }
        } else if tag_type == 8 && !payload.is_empty() && audio_codec.is_empty() {
            // AudioTag: 4 bit cao là SoundFormat
            audio_codec = match (payload[0] >> 4) & 0x0F {
                2 => "mp3".to_string(),
                10 => "aac".to_string(),
                _ => String::new(),
            };
        }

        if duration_secs > 0.0 && !video_codec.is_empty() {
            break;
        }
        let next_pos = payload_start.saturating_add(data_size).saturating_add(4);
        if next_pos <= pos || next_pos > buf.len() {
            break;
        }
        pos = next_pos;
    }

    Ok(MediaHeaderInfo {
        valid: duration_secs > 0.0 || !video_codec.is_empty(),
        container: "flv".to_string(),
        duration_secs,
        video_codec,
        audio_codec,
        has_moov: true,
    })
}

/// Bộ phân tích header thống nhất cho MP4/MOV/M4V + MKV/WebM + FLV (loại bỏ 100% fork `ffprobe`).
pub fn parse_media_header(path: &Path) -> Result<MediaHeaderInfo, String> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();

    match ext.as_str() {
        "mkv" | "webm" => parse_ebml_header(path),
        "flv" => parse_flv_header(path),
        "mp4" | "m4v" | "mov" => {
            let info = parse_mp4_header(path)?;
            Ok(MediaHeaderInfo {
                valid: info.valid,
                container: ext,
                duration_secs: info.duration_secs,
                video_codec: info.video_codec,
                audio_codec: info.audio_codec,
                has_moov: info.has_moov,
            })
        }
        _ => Err("unsupported_container".to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_parse_synthetic_mkv_and_flv() {
        let tmp = std::env::temp_dir().join(format!("media_hdr_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();

        // 1. Tạo file MKV tổng hợp tối giản có EBML + Segment + Info(Duration=12.5s) + Tracks(V_MPEG4/ISO/AVC)
        let mut mkv = Vec::new();
        // EBML header: ID 0x1A45DFA3, size 0x80 (0 bytes)
        mkv.extend_from_slice(&[0x1A, 0x45, 0xDF, 0xA3, 0x80]);
        // Segment: ID 0x18538067, unknown size 0x01FFFFFFFFFFFFFF
        mkv.extend_from_slice(&[0x18, 0x53, 0x80, 0x67, 0xFF]);
        // Info: ID 0x1549A966, chứa Duration (0x4489, len 8, f64 = 12500.0 ms)
        let mut info = Vec::new();
        info.extend_from_slice(&[0x44, 0x89, 0x88]);
        info.extend_from_slice(&12500.0f64.to_be_bytes());
        mkv.extend_from_slice(&[0x15, 0x49, 0xA9, 0x66, 0x80 | (info.len() as u8)]);
        mkv.extend_from_slice(&info);
        // Tracks: ID 0x1654AE6B -> TrackEntry (0xAE) -> TrackType(0x83)=1 + CodecID(0x86)="V_MPEG4/ISO/AVC"
        let codec_bytes = b"V_MPEG4/ISO/AVC";
        let mut entry = Vec::new();
        entry.extend_from_slice(&[0x83, 0x81, 0x01]);
        entry.extend_from_slice(&[0x86, 0x80 | (codec_bytes.len() as u8)]);
        entry.extend_from_slice(codec_bytes);
        let mut tracks = Vec::new();
        tracks.extend_from_slice(&[0xAE, 0x80 | (entry.len() as u8)]);
        tracks.extend_from_slice(&entry);
        mkv.extend_from_slice(&[0x16, 0x54, 0xAE, 0x6B, 0x80 | (tracks.len() as u8)]);
        mkv.extend_from_slice(&tracks);

        let mkv_path = tmp.join("sample.mkv");
        fs::write(&mkv_path, &mkv).unwrap();
        let mkv_info = parse_media_header(&mkv_path).unwrap();
        assert!(mkv_info.valid);
        assert_eq!(mkv_info.video_codec, "h264");
        assert!((mkv_info.duration_secs - 12.5).abs() < 0.01);

        // 2. Tạo file FLV tổng hợp tối giản có onMetaData duration=42.0 và VideoTag CodecID=7 (h264)
        let mut flv = Vec::new();
        flv.extend_from_slice(b"FLV\x01\x05\x00\x00\x00\x09\x00\x00\x00\x00");
        let mut meta_payload = Vec::new();
        meta_payload.extend_from_slice(&[0x00, 0x08]);
        meta_payload.extend_from_slice(b"duration");
        meta_payload.push(0x00);
        meta_payload.extend_from_slice(&42.0f64.to_be_bytes());
        let psz = meta_payload.len();
        flv.push(18);
        flv.extend_from_slice(&[((psz >> 16) & 0xFF) as u8, ((psz >> 8) & 0xFF) as u8, (psz & 0xFF) as u8]);
        flv.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0]);
        flv.extend_from_slice(&meta_payload);
        flv.extend_from_slice(&((psz + 11) as u32).to_be_bytes());
        // VideoTag (type 9, size 1, payload=0x17 -> keyframe + h264)
        flv.extend_from_slice(&[9, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0x17, 0, 0, 0, 12]);

        let flv_path = tmp.join("sample.flv");
        fs::write(&flv_path, &flv).unwrap();
        let flv_info = parse_media_header(&flv_path).unwrap();
        assert!(flv_info.valid);
        assert_eq!(flv_info.video_codec, "h264");
        assert!((flv_info.duration_secs - 42.0).abs() < 0.01);

        let _ = fs::remove_dir_all(&tmp);
    }
}
