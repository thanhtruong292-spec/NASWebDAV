use crate::diacritics::remove_diacritics;
use serde::Serialize;
use std::fs;
use std::path::Path;
use std::time::UNIX_EPOCH;

/// Entry tuple khớp 100% với `_scan_file_index_pass` của Python:
/// (child, name, norm_name, is_dir, size, mtime_ms, mime, parent)
pub type IndexedFileEntry = (String, String, String, i64, u64, i64, String, String);

#[derive(Debug, Serialize)]
pub struct DirScanResult {
    pub entries: Vec<IndexedFileEntry>,
    /// Danh sách tên thư mục con & symlink để Python kiểm tra boundary/cycle bằng _index_resolve_directory.
    pub special: Vec<String>,
}

/// Bảng đoán MIME tĩnh O(1) cho các định dạng phổ biến, khớp mimetypes.guess_type của Python.
pub fn guess_mime_fast(name: &str) -> &'static str {
    let bytes = name.as_bytes();
    let dot_idx = match bytes.iter().rposition(|&b| b == b'.') {
        Some(i) => i,
        None => return "application/octet-stream",
    };
    if dot_idx == 0 || bytes[..dot_idx].iter().all(|&b| b == b'.') {
        return "application/octet-stream";
    }
    let ext = &bytes[dot_idx + 1..];
    if ext.is_empty() || ext.len() > 6 {
        return "application/octet-stream";
    }
    let mut lower = [0u8; 6];
    for (i, &b) in ext.iter().enumerate() {
        lower[i] = b.to_ascii_lowercase();
    }
    match &lower[..ext.len()] {
        b"jpg" | b"jpeg" | b"jpe" => "image/jpeg",
        b"png" => "image/png",
        b"gif" => "image/gif",
        b"webp" => "image/webp",
        b"bmp" => "image/x-ms-bmp",
        b"svg" => "image/svg+xml",
        b"ico" => "image/x-icon",
        b"tif" | b"tiff" => "image/tiff",
        b"heic" => "image/heic",
        b"heif" => "image/heif",
        b"mp4" | b"m4v" => "video/mp4",
        b"mkv" => "video/x-matroska",
        b"avi" => "video/x-msvideo",
        b"mov" | b"qt" => "video/quicktime",
        b"webm" => "video/webm",
        b"flv" => "video/x-flv",
        b"wmv" => "video/x-ms-wmv",
        b"mpg" | b"mpeg" => "video/mpeg",
        b"ts" => "video/mp2t",
        b"3gp" => "video/3gpp",
        b"mp3" => "audio/mpeg",
        b"wav" => "audio/x-wav",
        b"flac" => "audio/flac",
        b"m4a" => "audio/mp4",
        b"ogg" => "audio/ogg",
        b"aac" => "audio/aac",
        b"pdf" => "application/pdf",
        b"txt" | b"log" | b"md" => "text/plain",
        b"html" | b"htm" => "text/html",
        b"css" => "text/css",
        b"js" => "application/javascript",
        b"json" => "application/json",
        b"xml" => "application/xml",
        b"csv" => "text/csv",
        b"zip" => "application/zip",
        b"gz" => "application/gzip",
        b"tar" => "application/x-tar",
        b"rar" => "application/x-rar-compressed",
        b"7z" => "application/x-7z-compressed",
        b"doc" => "application/msword",
        b"docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        b"xls" => "application/vnd.ms-excel",
        b"xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        b"ppt" => "application/vnd.ms-powerpoint",
        b"pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        b"apk" => "application/vnd.android.package-archive",
        _ => "application/octet-stream",
    }
}

/// Quét 1 thư mục bằng `std::fs::read_dir` + `symlink_metadata` (lstat) + `remove_diacritics` nội bộ.
/// Trả về tất cả tệp thường đã chuẩn hóa sẵn và danh sách `special` (thư mục con / symlink) để Python kiểm tra boundary.
pub fn scan_directory_entries(dir_path: &str, rel_prefix: &str) -> Result<DirScanResult, String> {
    let dir = Path::new(dir_path);
    let read_dir = fs::read_dir(dir).map_err(|e| format!("read_dir failed: {}", e))?;

    let mut entries = Vec::new();
    let mut special = Vec::new();

    for item in read_dir {
        let entry = match item {
            Ok(e) => e,
            Err(_) => continue,
        };
        let os_name = entry.file_name();
        let name = match os_name.to_str() {
            Some(s) => s,
            None => continue,
        };
        if name.starts_with('.') || name == "@eaDir" || name == "#recycle" {
            continue;
        }
        let lmeta = match fs::symlink_metadata(entry.path()) {
            Ok(m) => m,
            Err(_) => {
                special.push(name.to_string());
                continue;
            }
        };
        let ft = lmeta.file_type();
        if ft.is_symlink() || ft.is_dir() {
            special.push(name.to_string());
            continue;
        }

        let size = lmeta.len();
        let mtime_ms = lmeta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| (d.as_secs() as i64) * 1000 + (d.subsec_millis() as i64))
            .unwrap_or(0);

        let child = format!("{}{}", rel_prefix, name);
        let norm_name = remove_diacritics(&name.to_lowercase());
        let mime = guess_mime_fast(name).to_string();

        entries.push((
            child,
            name.to_string(),
            norm_name,
            0i64,
            size,
            mtime_ms,
            mime,
            rel_prefix.to_string(),
        ));
    }

    Ok(DirScanResult { entries, special })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn test_scan_directory_entries_basic() {
        let tmp = std::env::temp_dir().join(format!("nas_dir_scan_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(tmp.join("SubDir")).unwrap();
        fs::create_dir_all(tmp.join(".hidden")).unwrap();
        fs::File::create(tmp.join("Ảnh đẹp.jpg")).unwrap().write_all(b"12345").unwrap();
        fs::File::create(tmp.join(".dotfile.txt")).unwrap().write_all(b"x").unwrap();

        let res = scan_directory_entries(tmp.to_str().unwrap(), "photos/").unwrap();
        assert_eq!(res.entries.len(), 1);
        assert_eq!(res.entries[0].0, "photos/Ảnh đẹp.jpg");
        assert_eq!(res.entries[0].2, "anh dep.jpg");
        assert_eq!(res.entries[0].4, 5);
        assert_eq!(res.entries[0].6, "image/jpeg");
        assert_eq!(res.special, vec!["SubDir".to_string()]);

        let _ = fs::remove_dir_all(&tmp);
    }
}
