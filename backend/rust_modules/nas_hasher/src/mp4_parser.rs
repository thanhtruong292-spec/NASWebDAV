use serde::Serialize;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct Mp4HeaderInfo {
    pub valid: bool,
    pub has_ftyp: bool,
    pub has_moov: bool,
    pub major_brand: String,
    pub duration_secs: f64,
    pub width: u32,
    pub height: u32,
    pub video_codec: String,
    pub audio_codec: String,
    pub file_size: u64,
}

fn read_u32_be(buf: &[u8], offset: usize) -> Option<u32> {
    let slice = buf.get(offset..offset + 4)?;
    Some(u32::from_be_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

fn read_u64_be(buf: &[u8], offset: usize) -> Option<u64> {
    let slice = buf.get(offset..offset + 8)?;
    Some(u64::from_be_bytes([
        slice[0], slice[1], slice[2], slice[3], slice[4], slice[5], slice[6], slice[7],
    ]))
}

fn map_codec_4cc(fourcc: &[u8; 4]) -> String {
    match fourcc {
        b"avc1" | b"avc2" | b"avc3" | b"avc4" => "h264".to_string(),
        b"hvc1" | b"hev1" => "hevc".to_string(),
        b"av01" => "av1".to_string(),
        b"vp09" => "vp9".to_string(),
        b"vp08" => "vp8".to_string(),
        b"mp4v" => "mpeg4".to_string(),
        b"mp4a" => "aac".to_string(),
        b"Opus" => "opus".to_string(),
        b"ac-3" => "ac3".to_string(),
        b"ec-3" => "eac3".to_string(),
        b"fLaC" => "flac".to_string(),
        b"alac" => "alac".to_string(),
        b".mp3" => "mp3".to_string(),
        other => String::from_utf8_lossy(other).trim().to_lowercase(),
    }
}

/// Đọc header 1 box MP4 tại vị trí hiện tại trong [start, parent_end].
/// Trả về (box_type, payload_start, box_end).
fn read_box_header(
    file: &mut File,
    box_start: u64,
    parent_end: u64,
) -> Option<([u8; 4], u64, u64)> {
    if box_start + 8 > parent_end {
        return None;
    }
    file.seek(SeekFrom::Start(box_start)).ok()?;
    let mut hdr = [0u8; 8];
    file.read_exact(&mut hdr).ok()?;

    let size32 = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
    let box_type = [hdr[4], hdr[5], hdr[6], hdr[7]];

    let (header_len, box_size) = if size32 == 1 {
        if box_start + 16 > parent_end {
            return None;
        }
        let mut ext = [0u8; 8];
        file.read_exact(&mut ext).ok()?;
        let sz64 = u64::from_be_bytes(ext);
        (16u64, sz64)
    } else if size32 == 0 {
        (8u64, parent_end - box_start)
    } else {
        (8u64, size32)
    };

    if box_size < header_len {
        return None;
    }
    let box_end = box_start.checked_add(box_size)?;
    if box_end > parent_end {
        return None;
    }
    Some((box_type, box_start + header_len, box_end))
}

fn read_small_payload(file: &mut File, start: u64, end: u64, max_bytes: usize) -> Option<Vec<u8>> {
    let len = (end.saturating_sub(start)).min(max_bytes as u64) as usize;
    let mut buf = vec![0u8; len];
    file.seek(SeekFrom::Start(start)).ok()?;
    file.read_exact(&mut buf).ok()?;
    Some(buf)
}

fn parse_mvhd(file: &mut File, start: u64, end: u64) -> Option<f64> {
    let buf = read_small_payload(file, start, end, 40)?;
    let version = *buf.first()?;
    if version == 0 {
        let timescale = read_u32_be(&buf, 12)?;
        let duration = read_u32_be(&buf, 16)?;
        if timescale > 0 {
            return Some(duration as f64 / timescale as f64);
        }
    } else if version == 1 {
        let timescale = read_u32_be(&buf, 20)?;
        let duration = read_u64_be(&buf, 24)?;
        if timescale > 0 {
            return Some(duration as f64 / timescale as f64);
        }
    }
    None
}

fn parse_tkhd_dimensions(file: &mut File, start: u64, end: u64) -> Option<(u32, u32)> {
    let buf = read_small_payload(file, start, end, 100)?;
    let version = *buf.first()?;
    let (w_off, h_off) = if version == 0 {
        (76, 80)
    } else if version == 1 {
        (88, 92)
    } else {
        return None;
    };
    let w = read_u32_be(&buf, w_off)? >> 16;
    let h = read_u32_be(&buf, h_off)? >> 16;
    Some((w, h))
}

fn parse_stsd_codec(file: &mut File, start: u64, end: u64) -> Option<String> {
    let buf = read_small_payload(file, start, end, 24)?;
    let entry_count = read_u32_be(&buf, 4)?;
    if entry_count == 0 || buf.len() < 16 {
        return None;
    }
    let fourcc = [buf[12], buf[13], buf[14], buf[15]];
    Some(map_codec_4cc(&fourcc))
}

fn parse_trak(file: &mut File, trak_start: u64, trak_end: u64, info: &mut Mp4HeaderInfo) {
    let mut pos = trak_start;
    let mut handler_type = [0u8; 4];
    let mut tk_w: u32 = 0;
    let mut tk_h: u32 = 0;
    let mut codec = String::new();
    let mut steps = 0u32;

    while pos + 8 <= trak_end && steps < 512 {
        steps += 1;
        let (btype, pstart, bend) = match read_box_header(file, pos, trak_end) {
            Some(v) => v,
            None => break,
        };
        if &btype == b"tkhd" {
            if let Some((w, h)) = parse_tkhd_dimensions(file, pstart, bend) {
                tk_w = w;
                tk_h = h;
            }
        } else if &btype == b"mdia" {
            let mut mpos = pstart;
            let mut msteps = 0u32;
            while mpos + 8 <= bend && msteps < 256 {
                msteps += 1;
                let (mtype, mpstart, mbend) = match read_box_header(file, mpos, bend) {
                    Some(v) => v,
                    None => break,
                };
                if &mtype == b"hdlr" {
                    if let Some(buf) = read_small_payload(file, mpstart, mbend, 16) {
                        if buf.len() >= 12 {
                            handler_type = [buf[8], buf[9], buf[10], buf[11]];
                        }
                    }
                } else if &mtype == b"minf" {
                    let mut ipos = mpstart;
                    let mut isteps = 0u32;
                    while ipos + 8 <= mbend && isteps < 256 {
                        isteps += 1;
                        let (itype, ipstart, ibend) = match read_box_header(file, ipos, mbend) {
                            Some(v) => v,
                            None => break,
                        };
                        if &itype == b"stbl" {
                            let mut spos = ipstart;
                            let mut ssteps = 0u32;
                            while spos + 8 <= ibend && ssteps < 256 {
                                ssteps += 1;
                                let (stype, spstart, sbend) = match read_box_header(file, spos, ibend) {
                                    Some(v) => v,
                                    None => break,
                                };
                                if &stype == b"stsd" {
                                    if let Some(c) = parse_stsd_codec(file, spstart, sbend) {
                                        codec = c;
                                    }
                                    break;
                                }
                                spos = sbend;
                            }
                        }
                        ipos = ibend;
                    }
                }
                mpos = mbend;
            }
        }
        pos = bend;
    }

    if &handler_type == b"vide" {
        if info.video_codec.is_empty() && !codec.is_empty() {
            info.video_codec = codec;
        }
        if info.width == 0 && tk_w > 0 {
            info.width = tk_w;
        }
        if info.height == 0 && tk_h > 0 {
            info.height = tk_h;
        }
    } else if &handler_type == b"soun" && info.audio_codec.is_empty() && !codec.is_empty() {
        info.audio_codec = codec;
    }
}

/// Đọc trực tiếp metadata atom ISO BMFF (MP4/M4V/MOV) bằng File::seek — bỏ qua hoàn toàn mdat.
/// Tiêu tốn < 64KB RAM, không fork tiến trình ffprobe.
pub fn parse_mp4_header(path: &Path) -> Result<Mp4HeaderInfo, String> {
    let mut file = File::open(path).map_err(|e| format!("Open failed: {}", e))?;
    let file_size = file
        .metadata()
        .map_err(|e| format!("Stat failed: {}", e))?
        .len();

    let mut info = Mp4HeaderInfo {
        valid: false,
        has_ftyp: false,
        has_moov: false,
        major_brand: String::new(),
        duration_secs: 0.0,
        width: 0,
        height: 0,
        video_codec: String::new(),
        audio_codec: String::new(),
        file_size,
    };

    if file_size < 16 {
        return Ok(info);
    }

    let mut pos: u64 = 0;
    let mut top_boxes: u32 = 0;

    while pos + 8 <= file_size && top_boxes < 4096 {
        top_boxes += 1;
        let (btype, pstart, bend) = match read_box_header(&mut file, pos, file_size) {
            Some(v) => v,
            None => break,
        };

        if &btype == b"ftyp" {
            info.has_ftyp = true;
            if let Some(buf) = read_small_payload(&mut file, pstart, bend, 4) {
                if buf.len() == 4 {
                    info.major_brand = String::from_utf8_lossy(&buf).trim().to_string();
                }
            }
        } else if &btype == b"moov" {
            info.has_moov = true;
            let mut mpos = pstart;
            let mut msteps = 0u32;
            while mpos + 8 <= bend && msteps < 1024 {
                msteps += 1;
                let (mtype, mpstart, mbend) = match read_box_header(&mut file, mpos, bend) {
                    Some(v) => v,
                    None => break,
                };
                if &mtype == b"mvhd" {
                    if let Some(dur) = parse_mvhd(&mut file, mpstart, mbend) {
                        info.duration_secs = dur;
                    }
                } else if &mtype == b"trak" {
                    parse_trak(&mut file, mpstart, mbend, &mut info);
                }
                mpos = mbend;
            }
            if info.has_ftyp {
                break;
            }
        }
        pos = bend;
    }

    info.valid = info.has_moov && (info.duration_secs > 0.0 || !info.video_codec.is_empty() || !info.audio_codec.is_empty());
    Ok(info)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;

    fn make_box(btype: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let len = (8 + payload.len()) as u32;
        let mut out = Vec::with_capacity(len as usize);
        out.extend_from_slice(&len.to_be_bytes());
        out.extend_from_slice(btype);
        out.extend_from_slice(payload);
        out
    }

    #[test]
    fn test_parse_synthetic_mp4_moov_after_mdat() {
        let tmp = std::env::temp_dir().join(format!("test_mp4_atom_{}.mp4", std::process::id()));

        // ftyp
        let ftyp = make_box(b"ftyp", b"isom\x00\x00\x02\x00isommp41");
        // mdat (simulated 256KB video payload)
        let mdat = make_box(b"mdat", &vec![0x55u8; 256 * 1024]);

        // mvhd v0: timescale=1000, duration=12500 -> 12.5s
        let mut mvhd_payload = vec![0u8; 24];
        mvhd_payload[12..16].copy_from_slice(&1000u32.to_be_bytes());
        mvhd_payload[16..20].copy_from_slice(&12500u32.to_be_bytes());
        let mvhd = make_box(b"mvhd", &mvhd_payload);

        // tkhd v0: width=1920, height=1080
        let mut tkhd_payload = vec![0u8; 84];
        tkhd_payload[76..80].copy_from_slice(&(1920u32 << 16).to_be_bytes());
        tkhd_payload[80..84].copy_from_slice(&(1080u32 << 16).to_be_bytes());
        let tkhd = make_box(b"tkhd", &tkhd_payload);

        // hdlr: vide
        let mut hdlr_payload = vec![0u8; 24];
        hdlr_payload[8..12].copy_from_slice(b"vide");
        let hdlr = make_box(b"hdlr", &hdlr_payload);

        // stsd: 1 entry, codec=avc1
        let mut stsd_payload = vec![0u8; 24];
        stsd_payload[4..8].copy_from_slice(&1u32.to_be_bytes());
        stsd_payload[8..12].copy_from_slice(&16u32.to_be_bytes());
        stsd_payload[12..16].copy_from_slice(b"avc1");
        let stsd = make_box(b"stsd", &stsd_payload);

        let stbl = make_box(b"stbl", &stsd);
        let minf = make_box(b"minf", &stbl);
        let mut mdia_inner = Vec::new();
        mdia_inner.extend_from_slice(&hdlr);
        mdia_inner.extend_from_slice(&minf);
        let mdia = make_box(b"mdia", &mdia_inner);

        let mut trak_inner = Vec::new();
        trak_inner.extend_from_slice(&tkhd);
        trak_inner.extend_from_slice(&mdia);
        let trak = make_box(b"trak", &trak_inner);

        let mut moov_inner = Vec::new();
        moov_inner.extend_from_slice(&mvhd);
        moov_inner.extend_from_slice(&trak);
        let moov = make_box(b"moov", &moov_inner);

        let mut file = fs::File::create(&tmp).unwrap();
        file.write_all(&ftyp).unwrap();
        file.write_all(&mdat).unwrap();
        file.write_all(&moov).unwrap();
        drop(file);

        let info = parse_mp4_header(&tmp).unwrap();
        assert!(info.valid);
        assert!(info.has_ftyp);
        assert!(info.has_moov);
        assert_eq!(info.major_brand, "isom");
        assert!((info.duration_secs - 12.5).abs() < 0.001);
        assert_eq!(info.width, 1920);
        assert_eq!(info.height, 1080);
        assert_eq!(info.video_codec, "h264");

        // Truncated file without moov
        let broken = std::env::temp_dir().join(format!("test_mp4_broken_{}.mp4", std::process::id()));
        let mut bf = fs::File::create(&broken).unwrap();
        bf.write_all(&ftyp).unwrap();
        bf.write_all(&mdat).unwrap();
        drop(bf);

        let broken_info = parse_mp4_header(&broken).unwrap();
        assert!(!broken_info.valid);
        assert!(broken_info.has_ftyp);
        assert!(!broken_info.has_moov);

        let _ = fs::remove_file(&tmp);
        let _ = fs::remove_file(&broken);
    }
}
