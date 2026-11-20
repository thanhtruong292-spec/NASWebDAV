use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_ulonglong};
use std::path::Path;

pub mod archive_worker;
pub mod duplicates;
pub mod exif_reader;
pub mod fs_scanner;
pub mod image_thumb;
pub mod media_header_parser;
pub mod mp4_parser;
pub mod ts_merger;
pub mod usb_importer;
pub use archive_worker::{
    create_zip_from_dir, extract_tar_batch_safe, extract_tar_safe, extract_zip_safe,
    TarBatchItem, TarBatchResult, ZipCreateResult, ZipExtractResult,
};
pub use duplicates::{
    batch_md5_1mb_parallel, compute_md5_1mb_path, compute_sha256_path, find_duplicates_in_dir,
};
pub use exif_reader::{read_batch_exif, read_image_exif, ExifMetadata};
pub use fs_scanner::{
    compute_dir_usage, scan_broken_mp4_candidates, BrokenMp4ScanResult, DirUsageResult,
};
pub use image_thumb::{
    compute_batch_ahash, compute_frame_brightness, compute_image_ahash, generate_image_thumbnail,
    hamming_distance,
};
pub use media_header_parser::{parse_ebml_header, parse_flv_header, parse_media_header, MediaHeaderInfo};
pub use mp4_parser::{parse_mp4_header, Mp4HeaderInfo};
pub use ts_merger::{merge_ts_segments, MergeResult};
pub use usb_importer::{fast_copy_file, FastCopyResult, ProgressCallback};

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

/// C-ABI: Giải nén an toàn file ZIP bằng Rust chống Zip-Slip và Symlink traversal.
/// Trả về JSON: {"files_extracted": N, "bytes_extracted": B} hoặc null nếu lỗi.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_extract_zip_json(
    archive_ptr: *const c_char,
    dest_ptr: *const c_char,
) -> *mut c_char {
    if archive_ptr.is_null() || dest_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let archive_str = match CStr::from_ptr(archive_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let dest_str = match CStr::from_ptr(dest_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let res = match extract_zip_safe(Path::new(archive_str), Path::new(dest_str)) {
        Ok(r) => r,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&res) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Nén một thư mục thành file ZIP bằng thuật toán Deflate tốc độ cao.
/// Trả về JSON: {"files_compressed": N, "total_bytes": B, "zip_size": Z} hoặc null nếu lỗi.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_create_zip_json(
    src_dir_ptr: *const c_char,
    dst_zip_ptr: *const c_char,
) -> *mut c_char {
    if src_dir_ptr.is_null() || dst_zip_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let src_str = match CStr::from_ptr(src_dir_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let dst_str = match CStr::from_ptr(dst_zip_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let res = match create_zip_from_dir(Path::new(src_str), Path::new(dst_str)) {
        Ok(r) => r,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&res) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Nối các file segment quay màn hình `part_000000.ts`.. thành 1 file `.ts` (zero-copy kernel `copy_file_range`).
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_merge_ts_segments_json(
    segments_dir_ptr: *const c_char,
    total: c_int,
    out_path_ptr: *const c_char,
) -> *mut c_char {
    if segments_dir_ptr.is_null() || out_path_ptr.is_null() || total < 0 {
        return std::ptr::null_mut();
    }
    let seg_str = match CStr::from_ptr(segments_dir_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let out_str = match CStr::from_ptr(out_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let res = match merge_ts_segments(Path::new(seg_str), total as usize, Path::new(out_str)) {
        Ok(r) => r,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&res) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Sao chép tệp siêu tốc cho USB Import (kernel `copy_file_range` zero-copy hoặc buffer 512KB + SHA-256).
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_fast_copy_file_json(
    src_ptr: *const c_char,
    dst_ptr: *const c_char,
    compute_sha256: c_int,
    progress_cb: Option<ProgressCallback>,
) -> *mut c_char {
    if src_ptr.is_null() || dst_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let src_str = match CStr::from_ptr(src_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let dst_str = match CStr::from_ptr(dst_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let res = match fast_copy_file(
        Path::new(src_str),
        Path::new(dst_str),
        compute_sha256 != 0,
        progress_cb,
    ) {
        Ok(r) => r,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&res) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Giải nén `.tar` / `.tar.gz` / `.tgz` an toàn chống Tar-Slip & Symlink/Hardlink traversal.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_extract_tar_json(
    archive_path_ptr: *const c_char,
    dest_dir_ptr: *const c_char,
) -> *mut c_char {
    if archive_path_ptr.is_null() || dest_dir_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let arch_str = match CStr::from_ptr(archive_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let dest_str = match CStr::from_ptr(dest_dir_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match extract_tar_safe(Path::new(arch_str), Path::new(dest_str)) {
        Ok(r) => serde_json::json!({
            "ok": true,
            "files_extracted": r.files_extracted,
            "bytes_extracted": r.bytes_extracted
        })
        .to_string(),
        Err(err_msg) => serde_json::json!({
            "ok": false,
            "error": err_msg
        })
        .to_string(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Giải nén `.tar` batch và tính SHA256 song song cho AutoBackup stream.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_extract_tar_batch_json(
    archive_path_ptr: *const c_char,
    dest_dir_ptr: *const c_char,
) -> *mut c_char {
    if archive_path_ptr.is_null() || dest_dir_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let arch_str = match CStr::from_ptr(archive_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let dest_str = match CStr::from_ptr(dest_dir_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match extract_tar_batch_safe(Path::new(arch_str), Path::new(dest_str)) {
        Ok(r) => serde_json::to_string(&r).unwrap_or_else(|_| "{\"ok\":false}".to_string()),
        Err(err_msg) => serde_json::json!({
            "ok": false,
            "error": err_msg,
            "files_extracted": 0,
            "bytes_extracted": 0,
            "items": []
        })
        .to_string(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Phân tích header đa định dạng (MP4/MOV/M4V + MKV/WebM EBML + FLV) không fork `ffprobe`.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_parse_media_header_json(
    path_ptr: *const c_char,
) -> *mut c_char {
    if path_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let path_str = match CStr::from_ptr(path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let info = match parse_media_header(Path::new(path_str)) {
        Ok(r) => r,
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

/// C-ABI: Tính nhanh dung lượng thư mục (dừng sớm theo `max_files` và `max_seconds`, bỏ qua `.nas_meta/.thumbnails/@eaDir`).
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_dir_usage_json(
    path_ptr: *const c_char,
    max_files: c_ulonglong,
    max_seconds: f64,
) -> *mut c_char {
    if path_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let path_str = match CStr::from_ptr(path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let res = compute_dir_usage(Path::new(path_str), max_files as u64, max_seconds);
    let json = match serde_json::to_string(&res) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Quét thư mục song song bằng Rust `walkdir` + `rayon` + `mp4_parser` tìm các file `.mp4` nghi ngờ hỏng.
#[no_mangle]
pub unsafe extern "C" fn nas_hasher_scan_broken_mp4_json(
    scan_dir_ptr: *const c_char,
    workers: c_int,
) -> *mut c_char {
    if scan_dir_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let dir_str = match CStr::from_ptr(scan_dir_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let res = scan_broken_mp4_candidates(Path::new(dir_str), workers.max(1) as usize);
    let json = match serde_json::to_string(&res) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
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
