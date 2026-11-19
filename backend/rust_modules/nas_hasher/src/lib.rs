use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_ulonglong};
use std::path::Path;

pub mod duplicates;
pub mod exif_reader;
pub mod image_thumb;
pub mod mp4_parser;
pub use duplicates::{
    batch_md5_1mb_parallel, compute_md5_1mb_path, compute_sha256_path, find_duplicates_in_dir,
};
pub use exif_reader::{read_batch_exif, read_image_exif, ExifMetadata};
pub use image_thumb::{
    compute_batch_ahash, compute_frame_brightness, compute_image_ahash, generate_image_thumbnail,
    hamming_distance,
};
pub use mp4_parser::{parse_mp4_header, Mp4HeaderInfo};

/// Băm SHA256 toàn bộ file (chuẩn C-ABI).
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
    let hex_str = match compute_sha256_path(path) {
        Ok(h) => h,
        Err(_) => return -1,
    };
    let bytes = hex_str.as_bytes();
    if bytes.len() < 64 {
        return -1;
    }
    for i in 0..64 {
        *out_hex.add(i) = bytes[i] as c_char;
    }
    *out_hex.add(64) = 0;
    0
}

/// Băm MD5 của tối đa 1MB đầu tiên (chuẩn C-ABI).
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
    let hex_str = match compute_md5_1mb_path(path) {
        Ok(h) => h,
        Err(_) => return -1,
    };
    let bytes = hex_str.as_bytes();
    if bytes.len() < 32 {
        return -1;
    }
    for i in 0..32 {
        *out_hex.add(i) = bytes[i] as c_char;
    }
    *out_hex.add(32) = 0;
    0
}

/// C-ABI: Băm MD5 1MB song song đa luồng (rayon).
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_batch_md5_1mb_json(
    items_json_ptr: *const c_char,
    workers: c_int,
) -> *mut c_char {
    if items_json_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let items_str = match CStr::from_ptr(items_json_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let items: Vec<(String, String)> = match serde_json::from_str(items_str) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let result_map = batch_md5_1mb_parallel(&items, workers.max(1) as usize);
    let json = match serde_json::to_string(&result_map) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Quét tệp trùng lặp 3 tầng đa luồng (Size -> Partial MD5 1MB -> Full SHA-256).
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_find_duplicates_json(
    root_dir_ptr: *const c_char,
    min_size: c_ulonglong,
    workers: c_int,
    limit: c_int,
) -> *mut c_char {
    if root_dir_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let root_dir = match CStr::from_ptr(root_dir_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let report = match find_duplicates_in_dir(
        root_dir,
        min_size as u64,
        workers.max(1) as usize,
        limit.max(0) as usize,
    ) {
        Ok(r) => r,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&report) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Đọc trực tiếp header MP4/MOV/M4V (moov/mvhd/tkhd/stsd) không cần ffprobe.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_parse_mp4_json(path_ptr: *const c_char) -> *mut c_char {
    if path_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let path_str = match CStr::from_ptr(path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let info = match parse_mp4_header(Path::new(path_str)) {
        Ok(i) => i,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&info) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Tạo thumbnail JPEG bằng Rust + ARM64 NEON SIMD (`fast_image_resize` + `zune-jpeg`).
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_generate_thumb(
    src_ptr: *const c_char,
    dst_ptr: *const c_char,
    max_size: c_int,
    quality: c_int,
) -> c_int {
    if src_ptr.is_null() || dst_ptr.is_null() || max_size <= 0 {
        return -1;
    }
    let src_str = match CStr::from_ptr(src_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -2,
    };
    let dst_str = match CStr::from_ptr(dst_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -2,
    };
    let q = quality.clamp(1, 100) as u8;
    match generate_image_thumbnail(Path::new(src_str), Path::new(dst_str), max_size as u32, q) {
        Ok(_) => 0,
        Err(_) => -3,
    }
}

/// C-ABI: Tính độ sáng trung bình (0.0..=255.0) của ảnh JPEG/PNG.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_frame_brightness(img_ptr: *const c_char) -> f64 {
    if img_ptr.is_null() {
        return -1.0;
    }
    let img_str = match CStr::from_ptr(img_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -1.0,
    };
    match compute_frame_brightness(Path::new(img_str)) {
        Ok(b) => b,
        Err(_) => -1.0,
    }
}

/// C-ABI: Tính aHash 64-bit (16 ký tự hex) cho 1 file ảnh.
/// Buffer `out_hex` phải có dung lượng >= 17 bytes (16 hex + \0).
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_ahash(
    img_ptr: *const c_char,
    out_hex: *mut c_char,
) -> c_int {
    if img_ptr.is_null() || out_hex.is_null() {
        return -2;
    }
    let img_str = match CStr::from_ptr(img_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -1,
    };
    let hash = match compute_image_ahash(Path::new(img_str)) {
        Ok(h) => h,
        Err(_) => return -1,
    };
    let bytes = hash.as_bytes();
    if bytes.len() != 16 {
        return -1;
    }
    std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_hex as *mut u8, 16);
    *out_hex.add(16) = 0;
    0
}

/// C-ABI: Tính batch aHash đa luồng (Rayon) từ mảng JSON các đường dẫn ảnh.
/// Trả về chuỗi JSON: {"/path/1": "0123456789abcdef", "/path/2": null, ...}
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_batch_ahash_json(
    paths_json_ptr: *const c_char,
    workers: c_int,
) -> *mut c_char {
    if paths_json_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let json_str = match CStr::from_ptr(paths_json_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let paths: Vec<String> = match serde_json::from_str(json_str) {
        Ok(p) => p,
        Err(_) => return std::ptr::null_mut(),
    };
    let results = compute_batch_ahash(&paths, workers.max(1) as usize);
    let mut map = std::collections::BTreeMap::new();
    for (p, h) in results {
        map.insert(p, h);
    }
    let out_json = match serde_json::to_string(&map) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(out_json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Trích xuất siêu dữ liệu EXIF (ngày chụp, camera, GPS, orientation) cho 1 ảnh.
/// Trả về chuỗi JSON ExifMetadata hoặc null nếu không có EXIF hoặc lỗi.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_read_exif_json(path_ptr: *const c_char) -> *mut c_char {
    if path_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let path_str = match CStr::from_ptr(path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let meta = match read_image_exif(Path::new(path_str)) {
        Ok(m) => m,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&meta) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Trích xuất EXIF đa luồng (Rayon) từ mảng JSON các đường dẫn ảnh.
/// Trả về chuỗi JSON: {"/path/1.jpg": {...}, "/path/2.jpg": null, ...}
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_read_batch_exif_json(
    paths_json_ptr: *const c_char,
    workers: c_int,
) -> *mut c_char {
    if paths_json_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let json_str = match CStr::from_ptr(paths_json_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let paths: Vec<String> = match serde_json::from_str(json_str) {
        Ok(p) => p,
        Err(_) => return std::ptr::null_mut(),
    };
    let results = read_batch_exif(&paths, workers.max(1) as usize);
    let mut map = std::collections::BTreeMap::new();
    for (p, m) in results {
        map.insert(p, m);
    }
    let out_json = match serde_json::to_string(&map) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(out_json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Giải phóng chuỗi được cấp phát bởi `nas_hasher_*_json`.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}
