use std::ffi::{CStr, CString};
use std::fs::File;
use std::io::BufWriter;
use std::os::raw::{c_char, c_int, c_longlong};

pub mod diacritics;
pub mod dir_scanner;
pub mod media_queries;
pub mod search;
pub mod storage_summary;
pub use diacritics::remove_diacritics;
pub use dir_scanner::{scan_directory_entries, DirScanResult};
pub use media_queries::{query_photos_timeline, query_recent_media, RecentMediaItem, TimelineResult};
pub use search::{build_fts_index, search_file_index};
pub use storage_summary::{compute_storage_summary, StorageSummaryCore};

pub mod streamer {
    pub use crate::stream_fast_index;
}

// Re-export stream_fast_index from main logic
pub use main_logic::stream_fast_index;

mod main_logic {
    use rusqlite::{Connection, OpenFlags};
    use serde::Serialize;
    use std::io::Write;

    #[derive(Serialize)]
    struct IndexMetadata {
        catalogue_protocol: u32,
        source: &'static str,
        index_ready: bool,
        index_generation: i64,
        catalogue_updated_at: f64,
        catalogue_revision: i64,
        truncated: bool,
        total: i64,
    }

    #[derive(Serialize)]
    struct FileItem<'a> {
        name: &'a str,
        path: String,
        size: i64,
        mtime: i64,
        mime: &'a str,
    }

    pub fn stream_fast_index<W: Write>(db_path: &str, mut writer: W) -> Result<usize, String> {
        let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
        let conn = Connection::open_with_flags(db_path, flags)
            .map_err(|e| format!("Failed to open DB in read-only mode: {}", e))?;

        let mut state_stmt = conn
            .prepare("SELECT root, generation, ready, scanned_at, catalogue_revision FROM index_state WHERE id=1")
            .map_err(|e| format!("Prepare state failed: {}", e))?;

        let state_row = state_stmt
            .query_row([], |row| {
                Ok((
                    row.get::<_, i64>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, f64>(3)?,
                    row.get::<_, i64>(4)?,
                ))
            })
            .map_err(|e| format!("Query state row failed: {}", e))?;

        let total: i64 = conn
            .query_row("SELECT COUNT(*) FROM file_index WHERE is_dir=0", [], |r| r.get(0))
            .map_err(|e| format!("Count total files failed: {}", e))?;

        let metadata = IndexMetadata {
            catalogue_protocol: 1,
            source: "file_index",
            index_ready: state_row.1 != 0,
            index_generation: state_row.0,
            catalogue_updated_at: state_row.2,
            catalogue_revision: state_row.3,
            truncated: false,
            total,
        };

        let meta_json = serde_json::to_string(&metadata)
            .map_err(|e| format!("Serialize metadata failed: {}", e))?;

        let header_prefix = &meta_json[..meta_json.len() - 1];
        writer
            .write_all(format!("{},\"files\":[", header_prefix).as_bytes())
            .map_err(|e| format!("Write header failed: {}", e))?;

        let mut stmt = conn
            .prepare("SELECT path, name, size, mtime, mime FROM file_index WHERE is_dir=0")
            .map_err(|e| format!("Prepare file query failed: {}", e))?;

        let mut rows = stmt
            .query([])
            .map_err(|e| format!("Query files failed: {}", e))?;

        let mut count = 0;
        let mut first = true;

        while let Some(row) = rows.next().map_err(|e| format!("Fetch row failed: {}", e))? {
            let path: String = row.get(0).map_err(|e| e.to_string())?;
            let name: String = row.get(1).map_err(|e| e.to_string())?;
            let size: i64 = row.get(2).map_err(|e| e.to_string())?;
            let mtime: i64 = row.get(3).map_err(|e| e.to_string())?;
            let mime: String = row.get(4).map_err(|e| e.to_string())?;

            let item = FileItem {
                name: &name,
                path: format!("/webdav/{}", path),
                size,
                mtime,
                mime: &mime,
            };

            let chunk = serde_json::to_string(&item)
                .map_err(|e| format!("Serialize item failed: {}", e))?;

            if !first {
                writer.write_all(b",").map_err(|e| e.to_string())?;
            }
            writer.write_all(chunk.as_bytes()).map_err(|e| e.to_string())?;

            first = false;
            count += 1;
        }

        writer.write_all(b"]}").map_err(|e| e.to_string())?;
        writer.flush().map_err(|e| e.to_string())?;

        Ok(count)
    }
}

/// C-ABI: Xuất luồng JSON của file_index ra một file đích trên disk.
/// Trả về số lượng tệp đã xuất (> 0) nếu thành công, hoặc số âm nếu lỗi.
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_stream_to_file(
    db_path_ptr: *const c_char,
    out_file_ptr: *const c_char,
) -> c_int {
    if db_path_ptr.is_null() || out_file_ptr.is_null() {
        return -1;
    }

    let db_path = match CStr::from_ptr(db_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -2,
    };

    let out_file = match CStr::from_ptr(out_file_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -2,
    };

    let file = match File::create(out_file) {
        Ok(f) => f,
        Err(_) => return -3,
    };

    let writer = BufWriter::with_capacity(128 * 1024, file);

    match stream_fast_index(db_path, writer) {
        Ok(count) => count as c_int,
        Err(_) => -4,
    }
}

/// C-ABI: Xóa dấu chuỗi tiếng Việt và dấu phụ Latin.
/// Ghi kết quả vào out_buf (chuỗi kết thúc bằng null).
/// Trả về số byte đã ghi nếu thành công, hoặc số âm nếu lỗi.
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_remove_diacritics(
    input_ptr: *const c_char,
    out_buf: *mut c_char,
    max_len: c_int,
) -> c_int {
    if input_ptr.is_null() || out_buf.is_null() || max_len <= 0 {
        return -1;
    }

    let input_str = match CStr::from_ptr(input_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -2,
    };

    let cleaned = remove_diacritics(input_str);
    let bytes = cleaned.as_bytes();
    if bytes.len() >= max_len as usize {
        return -3; // Buffer too small
    }

    std::ptr::copy_nonoverlapping(bytes.as_ptr() as *const c_char, out_buf, bytes.len());
    *out_buf.add(bytes.len()) = 0;

    bytes.len() as c_int
}

/// C-ABI: Tìm kiếm trong file_index SQLite, trả về con trỏ chuỗi JSON mảng các tuple.
/// Caller PHẢI gọi nas_indexer_free_string(ptr) sau khi đọc xong để giải phóng bộ nhớ.
/// Trả về null nếu có lỗi.
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_search_json(
    db_path_ptr: *const c_char,
    query_ptr: *const c_char,
    root_ptr: *const c_char,
    limit: c_int,
) -> *mut c_char {
    if db_path_ptr.is_null() || query_ptr.is_null() {
        return std::ptr::null_mut();
    }

    let db_path = match CStr::from_ptr(db_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let query = match CStr::from_ptr(query_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let root_param = if root_ptr.is_null() {
        ""
    } else {
        match CStr::from_ptr(root_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };

    let rows = match search_file_index(db_path, query, root_param, limit as i64) {
        Ok(r) => r,
        Err(_) => return std::ptr::null_mut(),
    };

    let json = match serde_json::to_string(&rows) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };

    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Giải phóng chuỗi được cấp phát bởi nas_indexer_search_json.
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}

/// C-ABI: Xây dựng sidecar FTS5 trigram index (<db_path>.fts.db).
/// Trả về số bản ghi đã lập chỉ mục (>= 0) nếu thành công, hoặc số âm nếu lỗi.
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_build_fts(db_path_ptr: *const c_char) -> c_int {
    if db_path_ptr.is_null() {
        return -1;
    }
    let db_path = match CStr::from_ptr(db_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return -2,
    };
    match build_fts_index(db_path) {
        Ok(count) => count as c_int,
        Err(_) => -3,
    }
}

/// C-ABI: Tính toán thống kê lưu trữ (by_type, largest top 10, total_files/bytes/dirs) trong 1 lượt quét.
/// Caller PHẢI gọi `nas_indexer_free_string(ptr)` sau khi đọc xong.
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_storage_summary_json(db_path_ptr: *const c_char) -> *mut c_char {
    if db_path_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let db_path = match CStr::from_ptr(db_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let summary = match compute_storage_summary(db_path) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&summary) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Quét 1 thư mục bằng Rust (`read_dir` + `lstat` + `remove_diacritics` + MIME O(1)).
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_scan_dir_json(
    dir_path_ptr: *const c_char,
    rel_prefix_ptr: *const c_char,
) -> *mut c_char {
    if dir_path_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let dir_path = match CStr::from_ptr(dir_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let rel_prefix = if rel_prefix_ptr.is_null() {
        ""
    } else {
        match CStr::from_ptr(rel_prefix_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };
    let res = match scan_directory_entries(dir_path, rel_prefix) {
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

/// C-ABI: Truy vấn media gần nhất (`api_recent_media`) trực tiếp trên index `mtime DESC, path`.
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_recent_media_json(
    db_path_ptr: *const c_char,
    kind_ptr: *const c_char,
    scope_ptr: *const c_char,
    limit: c_int,
) -> *mut c_char {
    if db_path_ptr.is_null() || kind_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let db_path = match CStr::from_ptr(db_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let kind = match CStr::from_ptr(kind_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let scope = if scope_ptr.is_null() {
        ""
    } else {
        match CStr::from_ptr(scope_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };
    let items = match query_recent_media(db_path, kind, scope, limit.max(1) as usize) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let json = match serde_json::to_string(&items) {
        Ok(j) => j,
        Err(_) => return std::ptr::null_mut(),
    };
    match CString::new(json) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// C-ABI: Truy vấn Timeline ảnh (`api_photos_timeline`) 1 lượt quét duy nhất trên index `mtime DESC, path`.
#[no_mangle]
pub unsafe extern "C" fn nas_indexer_photos_timeline_json(
    db_path_ptr: *const c_char,
    start_ms: c_longlong,
    end_ms: c_longlong,
    limit: c_int,
    offset: c_int,
) -> *mut c_char {
    if db_path_ptr.is_null() {
        return std::ptr::null_mut();
    }
    let db_path = match CStr::from_ptr(db_path_ptr).to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let res = match query_photos_timeline(
        db_path,
        start_ms as i64,
        end_ms as i64,
        limit.max(1) as usize,
        offset.max(0) as usize,
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
