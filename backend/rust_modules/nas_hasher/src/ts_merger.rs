use serde::Serialize;
use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;

#[derive(Debug, Serialize, Default)]
pub struct MergeResult {
    pub segments_merged: usize,
    pub total_bytes: u64,
    pub used_zero_copy: bool,
}

#[cfg(target_os = "linux")]
fn copy_fd_zero_copy(in_file: &File, out_file: &File, mut len: u64) -> Result<u64, std::io::Error> {
    use std::os::unix::io::AsRawFd;
    let in_fd = in_file.as_raw_fd();
    let out_fd = out_file.as_raw_fd();
    let mut copied: u64 = 0;

    while len > 0 {
        let to_copy = len.min(64 * 1024 * 1024) as libc::size_t;
        // Gọi trực tiếp syscall SYS_copy_file_range để tương thích tuyệt đối với glibc 2.24 (Debian 9)
        // trên kernel Linux 5.7.11-rockchip64.
        let ret = unsafe {
            libc::syscall(
                libc::SYS_copy_file_range,
                in_fd,
                std::ptr::null_mut::<libc::loff_t>(),
                out_fd,
                std::ptr::null_mut::<libc::loff_t>(),
                to_copy,
                0 as libc::c_uint,
            )
        };
        if ret < 0 {
            let err = std::io::Error::last_os_error();
            if err.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            return Err(err);
        }
        if ret == 0 {
            break;
        }
        let n = ret as u64;
        copied += n;
        len = len.saturating_sub(n);
    }
    Ok(copied)
}

/// Nối tuần tự các segment `part_000000.ts` .. `part_{total-1:06}.ts` thành 1 file `.ts` duy nhất.
/// Trên Linux kernel 5.7+ (Chainedbox RK3328) dùng zero-copy `copy_file_range` trong nhân;
/// tự động fallback về buffer 512KB thuần Rust (zero-GIL) nếu không hỗ trợ.
pub fn merge_ts_segments(
    segments_dir: &Path,
    total: usize,
    out_path: &Path,
) -> Result<MergeResult, String> {
    if !segments_dir.is_dir() {
        return Err("segments_dir_not_found".to_string());
    }
    let mut out_file = File::create(out_path).map_err(|e| format!("create_out_failed: {}", e))?;
    let mut total_bytes: u64 = 0;
    #[allow(unused_mut)]
    let mut used_zero_copy = false;
    let mut fallback_buf: Option<Vec<u8>> = None;

    for i in 0..total {
        let seg_path = segments_dir.join(format!("part_{:06}.ts", i));
        let mut in_file =
            File::open(&seg_path).map_err(|e| format!("open_segment_{}_failed: {}", i, e))?;
        #[allow(unused_variables)]
        let seg_len = in_file.metadata().map(|m| m.len()).unwrap_or(0);

        #[allow(unused_mut)]
        let mut copied_via_zc = false;

        #[cfg(target_os = "linux")]
        {
            if seg_len > 0 {
                match copy_fd_zero_copy(&in_file, &out_file, seg_len) {
                    Ok(n) if n == seg_len => {
                        total_bytes += n;
                        used_zero_copy = true;
                        copied_via_zc = true;
                    }
                    Ok(n) => {
                        total_bytes += n;
                        used_zero_copy = true;
                        // Copy phần còn lại bằng read/write nếu trả về ngắn
                    }
                    Err(_) => {
                        // ENOSYS / EXDEV / EINVAL -> fallback an toàn sang userspace buffer
                    }
                }
            } else {
                copied_via_zc = true;
            }
        }

        if !copied_via_zc {
            let buf = fallback_buf.get_or_insert_with(|| vec![0u8; 512 * 1024]);
            loop {
                let n = in_file
                    .read(buf)
                    .map_err(|e| format!("read_segment_{}_failed: {}", i, e))?;
                if n == 0 {
                    break;
                }
                out_file
                    .write_all(&buf[..n])
                    .map_err(|e| format!("write_out_failed: {}", e))?;
                total_bytes += n as u64;
            }
        }
    }

    out_file
        .flush()
        .map_err(|e| format!("flush_out_failed: {}", e))?;

    Ok(MergeResult {
        segments_merged: total,
        total_bytes,
        used_zero_copy,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_merge_ts_segments_roundtrip() {
        let tmp = std::env::temp_dir().join(format!("ts_merge_test_{}", std::process::id()));
        let seg_dir = tmp.join("segments");
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&seg_dir).unwrap();

        fs::write(seg_dir.join("part_000000.ts"), b"SEG0_HEADER_").unwrap();
        fs::write(seg_dir.join("part_000001.ts"), b"SEG1_BODY_").unwrap();
        fs::write(seg_dir.join("part_000002.ts"), b"SEG2_END").unwrap();

        let out_file = tmp.join("merged.ts");
        let res = merge_ts_segments(&seg_dir, 3, &out_file).unwrap();
        assert_eq!(res.segments_merged, 3);
        assert_eq!(res.total_bytes, 30);
        assert_eq!(fs::read(&out_file).unwrap(), b"SEG0_HEADER_SEG1_BODY_SEG2_END");

        let _ = fs::remove_dir_all(&tmp);
    }
}
