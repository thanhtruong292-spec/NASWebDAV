use serde::Serialize;
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;

#[derive(Debug, Serialize, Default)]
pub struct FastCopyResult {
    pub bytes_copied: u64,
    pub sha256: String,
    pub used_zero_copy: bool,
    pub cancelled: bool,
}

/// Callback C-ABI nhận tổng số bytes đã copy; trả về `!= 0` để yêu cầu hủy ngay lập tức.
pub type ProgressCallback = extern "C" fn(u64) -> i32;

#[cfg(target_os = "linux")]
fn copy_chunk_zero_copy(in_file: &File, out_file: &File, chunk_len: usize) -> Result<usize, std::io::Error> {
    use std::os::unix::io::AsRawFd;
    let in_fd = in_file.as_raw_fd();
    let out_fd = out_file.as_raw_fd();
    loop {
        let ret = unsafe {
            libc::syscall(
                libc::SYS_copy_file_range,
                in_fd,
                std::ptr::null_mut::<libc::loff_t>(),
                out_fd,
                std::ptr::null_mut::<libc::loff_t>(),
                chunk_len as libc::size_t,
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
        return Ok(ret as usize);
    }
}

/// Sao chép tệp siêu tốc cho USB Import (zero-GIL, không chạm heap Python):
/// - Khi `compute_sha256 == false` trên Linux 5.7+: dùng kernel zero-copy `copy_file_range` theo từng khối 4MB
///   để vừa đạt trần bus USB 3.0 / SATA vừa cập nhật tiến độ & hỗ trợ hủy tức thì.
/// - Khi `compute_sha256 == true` hoặc fallback: đọc/ghi 1 lượt với buffer cố định 512KB + SHA-256 Rust.
pub fn fast_copy_file(
    src: &Path,
    dst: &Path,
    compute_sha256: bool,
    progress_cb: Option<ProgressCallback>,
) -> Result<FastCopyResult, String> {
    let mut in_file = File::open(src).map_err(|e| format!("open_src_failed: {}", e))?;
    let mut out_file = File::create(dst).map_err(|e| format!("create_dst_failed: {}", e))?;

    let mut total_copied: u64 = 0;
    #[allow(unused_mut)]
    let mut used_zero_copy = false;

    // Nhánh 1: Kernel Zero-Copy khi không cần tính SHA-256 trong lúc copy
    #[cfg(target_os = "linux")]
    if !compute_sha256 {
        let zc_chunk: usize = 4 * 1024 * 1024; // 4MB mỗi lần gọi để kiểm tra cancel + báo progress
        let mut zc_supported = true;
        while zc_supported {
            match copy_chunk_zero_copy(&in_file, &out_file, zc_chunk) {
                Ok(0) => {
                    out_file.flush().map_err(|e| format!("flush_failed: {}", e))?;
                    return Ok(FastCopyResult {
                        bytes_copied: total_copied,
                        sha256: String::new(),
                        used_zero_copy: true,
                        cancelled: false,
                    });
                }
                Ok(n) => {
                    used_zero_copy = true;
                    total_copied += n as u64;
                    if let Some(cb) = progress_cb {
                        if cb(total_copied) != 0 {
                            return Ok(FastCopyResult {
                                bytes_copied: total_copied,
                                sha256: String::new(),
                                used_zero_copy: true,
                                cancelled: true,
                            });
                        }
                    }
                }
                Err(_) => {
                    // EXDEV / ENOSYS / EINVAL (ví dụ FAT32/FUSE không hỗ trợ copy_file_range) -> fallback
                    zc_supported = false;
                }
            }
        }
    }

    // Nhánh 2: Single-pass 512KB buffer (zero-GIL, 0 byte Python heap) + tùy chọn SHA-256
    let mut buf = vec![0u8; 512 * 1024];
    let mut hasher = if compute_sha256 {
        Some(Sha256::new())
    } else {
        None
    };
    let mut since_cb: u64 = 0;
    const CB_INTERVAL: u64 = 4 * 1024 * 1024;

    loop {
        let n = in_file
            .read(&mut buf)
            .map_err(|e| format!("read_failed: {}", e))?;
        if n == 0 {
            break;
        }
        out_file
            .write_all(&buf[..n])
            .map_err(|e| format!("write_failed: {}", e))?;
        if let Some(ref mut h) = hasher {
            h.update(&buf[..n]);
        }
        total_copied += n as u64;
        since_cb += n as u64;
        if since_cb >= CB_INTERVAL {
            since_cb = 0;
            if let Some(cb) = progress_cb {
                if cb(total_copied) != 0 {
                    return Ok(FastCopyResult {
                        bytes_copied: total_copied,
                        sha256: String::new(),
                        used_zero_copy,
                        cancelled: true,
                    });
                }
            }
        }
    }

    out_file.flush().map_err(|e| format!("flush_failed: {}", e))?;
    let sha_hex = match hasher {
        Some(h) => hex::encode(h.finalize()),
        None => String::new(),
    };

    Ok(FastCopyResult {
        bytes_copied: total_copied,
        sha256: sha_hex,
        used_zero_copy,
        cancelled: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_fast_copy_with_and_without_sha256() {
        let tmp = std::env::temp_dir().join(format!("usb_copy_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();

        let src = tmp.join("source.bin");
        let dst1 = tmp.join("dst_nohash.bin");
        let dst2 = tmp.join("dst_hash.bin");
        let payload = b"Chainedbox RK3328 Fast USB Copy Payload 1234567890".repeat(200);
        fs::write(&src, &payload).unwrap();

        let r1 = fast_copy_file(&src, &dst1, false, None).unwrap();
        assert_eq!(r1.bytes_copied, payload.len() as u64);
        assert!(!r1.cancelled);
        assert_eq!(fs::read(&dst1).unwrap(), payload);

        let r2 = fast_copy_file(&src, &dst2, true, None).unwrap();
        assert_eq!(r2.bytes_copied, payload.len() as u64);
        assert_eq!(r2.sha256.len(), 64);
        assert_eq!(fs::read(&dst2).unwrap(), payload);

        let _ = fs::remove_dir_all(&tmp);
    }
}
