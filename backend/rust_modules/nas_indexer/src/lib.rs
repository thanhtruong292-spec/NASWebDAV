use std::ffi::CStr;
use std::fs::File;
use std::io::BufWriter;
use std::os::raw::{c_char, c_int};

pub mod streamer {
    use super::*;
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
