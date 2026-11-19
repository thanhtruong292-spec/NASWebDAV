use std::ffi::CStr;
use std::fs::File;
use std::io::{BufReader, Read};
use std::os::raw::{c_char, c_int};
use std::path::Path;

use md5::{Digest, Md5};
use sha2::Sha256;

/// Băm SHA256 toàn bộ file (chuẩn C-ABI).
/// `out_hex` phải là buffer có ít nhất 65 bytes (64 hex chars + null terminator).
/// Trả về 0 nếu thành công, -1 nếu file không tồn tại/không mở được, -2 nếu buffer null.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_sha256(
    path_ptr: *const c_char,
    out_hex: *mut c_char,
) -> c_int {
    if path_ptr.is_null() || out_hex.is_null() {
        return -2;
    }

    let c_str = match CStr::from_ptr(path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let path = Path::new(c_str);
    if !path.is_file() {
        return -1;
    }

    let file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return -1,
    };

    let mut reader = BufReader::new(file);
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 65536];

    loop {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => hasher.update(&buffer[..n]),
            Err(_) => return -1,
        }
    }

    let hex_str = hex::encode(hasher.finalize());
    let bytes = hex_str.as_bytes();

    for i in 0..64 {
        *out_hex.add(i) = bytes[i] as c_char;
    }
    *out_hex.add(64) = 0; // null terminator

    0
}

/// Băm MD5 của tối đa 1MB đầu tiên (chuẩn C-ABI).
/// `out_hex` phải là buffer có ít nhất 33 bytes (32 hex chars + null terminator).
/// Trả về 0 nếu thành công, -1 nếu file không tồn tại/không mở được, -2 nếu buffer null.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_md5_1mb(
    path_ptr: *const c_char,
    out_hex: *mut c_char,
) -> c_int {
    if path_ptr.is_null() || out_hex.is_null() {
        return -2;
    }

    let c_str = match CStr::from_ptr(path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -1,
    };

    let path = Path::new(c_str);
    if !path.is_file() {
        return -1;
    }

    let file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return -1,
    };

    let mut reader = BufReader::new(file);
    let mut chunk = vec![0u8; 1_048_576];
    let bytes_read = match reader.read(&mut chunk) {
        Ok(n) => n,
        Err(_) => return -1,
    };

    let mut hasher = Md5::new();
    hasher.update(&chunk[..bytes_read]);
    let hex_str = hex::encode(hasher.finalize());
    let bytes = hex_str.as_bytes();

    for i in 0..32 {
        *out_hex.add(i) = bytes[i] as c_char;
    }
    *out_hex.add(32) = 0; // null terminator

    0
}
