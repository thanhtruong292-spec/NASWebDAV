use rusqlite::{Connection, OpenFlags};
use serde::Serialize;
use std::collections::BTreeMap;
use std::time::Duration;

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct TypeBucket {
    pub files: u64,
    pub bytes: u64,
}

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct LargestFile {
    pub path: String,
    pub bytes: u64,
}

#[derive(Debug, Serialize, Clone, PartialEq)]
pub struct StorageSummaryCore {
    pub total_files: u64,
    pub total_bytes: u64,
    pub total_dirs: u64,
    pub by_type: BTreeMap<&'static str, TypeBucket>,
    pub largest: Vec<LargestFile>,
}

/// Phân loại đuôi tệp khớp 100% với `os.path.splitext(name or '')[1].lower()` của Python.
/// Lưu ý: dotfile không có phần tên trước dấu chấm (vd `.jpg`, `..jpg`) có extension rỗng -> "other".
pub fn classify_storage_category(name: &str) -> usize {
    // 0=image, 1=video, 2=doc, 3=audio, 4=archive, 5=other
    let bytes = name.as_bytes();
    let dot_idx = match bytes.iter().rposition(|&b| b == b'.') {
        Some(i) => i,
        None => return 5,
    };
    // Python os.path.splitext: nếu toàn bộ ký tự trước dấu chấm cuối đều là '.' thì không có extension
    if dot_idx == 0 || bytes[..dot_idx].iter().all(|&b| b == b'.') {
        return 5;
    }
    let ext = &bytes[dot_idx + 1..];
    if ext.is_empty() || ext.len() > 6 {
        return 5;
    }
    let mut lower = [0u8; 6];
    for (i, &b) in ext.iter().enumerate() {
        lower[i] = b.to_ascii_lowercase();
    }
    match &lower[..ext.len()] {
        b"jpg" | b"jpeg" | b"png" | b"webp" | b"bmp" | b"gif" | b"heic" | b"heif" => 0,
        b"mp4" | b"mkv" | b"avi" | b"mov" | b"mpg" | b"mpeg" | b"wmv" | b"flv" | b"ts"
        | b"m4v" | b"webm" => 1,
        b"pdf" | b"doc" | b"docx" | b"xls" | b"xlsx" | b"ppt" | b"pptx" | b"txt" | b"md"
        | b"csv" => 2,
        b"mp3" | b"wav" | b"flac" | b"m4a" | b"ogg" => 3,
        b"zip" | b"rar" | b"7z" | b"tar" | b"gz" => 4,
        _ => 5,
    }
}

/// Tính toán thống kê lưu trữ trong 1 lượt quét duy nhất, zero-allocation trên từng dòng SQLite.
pub fn compute_storage_summary(db_path: &str) -> Result<StorageSummaryCore, String> {
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn = Connection::open_with_flags(db_path, flags)
        .map_err(|e| format!("Failed to open DB: {}", e))?;
    let _ = conn.busy_timeout(Duration::from_millis(5000));

    let mut stmt = conn
        .prepare("SELECT path, name, is_dir, size FROM file_index")
        .map_err(|e| format!("Prepare storage summary query failed: {}", e))?;

    let mut counts = [0u64; 6];
    let mut sizes = [0u64; 6];
    let mut total_dirs: u64 = 0;
    let mut top10: Vec<(u64, String)> = Vec::with_capacity(11);

    let mut rows = stmt
        .query([])
        .map_err(|e| format!("Query file_index failed: {}", e))?;

    while let Some(row) = rows.next().map_err(|e| e.to_string())? {
        let is_dir: i64 = row.get::<_, i64>(2).unwrap_or(0);
        if is_dir != 0 {
            total_dirs += 1;
            continue;
        }

        let sz_i64: i64 = row.get::<_, i64>(3).unwrap_or(0);
        let sz = if sz_i64 > 0 { sz_i64 as u64 } else { 0 };

        // Đọc name dạng zero-copy &str
        let name_str = row.get_ref(1).ok().and_then(|v| v.as_str().ok()).unwrap_or("");
        let cat = classify_storage_category(name_str);
        counts[cat] += 1;
        sizes[cat] = sizes[cat].saturating_add(sz);

        // Chỉ cấp phát path String khi thực sự có cơ hội lọt Top 10 (ORDER BY size DESC, path ASC)
        let qualifies = if top10.len() < 10 {
            true
        } else {
            let last = &top10[9];
            sz > last.0 || (sz == last.0 && {
                let p_ref = row.get_ref(0).ok().and_then(|v| v.as_str().ok()).unwrap_or("");
                p_ref < last.1.as_str()
            })
        };

        if qualifies {
            let path_str = row.get_ref(0).ok().and_then(|v| v.as_str().ok()).unwrap_or("").to_string();
            top10.push((sz, path_str));
            top10.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
            if top10.len() > 10 {
                top10.truncate(10);
            }
        }
    }

    let labels = ["image", "video", "doc", "audio", "archive", "other"];
    let mut by_type = BTreeMap::new();
    let mut total_files: u64 = 0;
    let mut total_bytes: u64 = 0;
    for i in 0..6 {
        total_files += counts[i];
        total_bytes = total_bytes.saturating_add(sizes[i]);
        by_type.insert(
            labels[i],
            TypeBucket {
                files: counts[i],
                bytes: sizes[i],
            },
        );
    }

    let largest = top10
        .into_iter()
        .map(|(bytes, path)| LargestFile { path, bytes })
        .collect();

    Ok(StorageSummaryCore {
        total_files,
        total_bytes,
        total_dirs,
        by_type,
        largest,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_classify_matches_python_splitext() {
        assert_eq!(classify_storage_category("photo.JPG"), 0);
        assert_eq!(classify_storage_category("clip.mp4"), 1);
        assert_eq!(classify_storage_category("report.PDF"), 2);
        assert_eq!(classify_storage_category("song.flac"), 3);
        assert_eq!(classify_storage_category("backup.tar.gz"), 4);
        assert_eq!(classify_storage_category("noext"), 5);
        // Python os.path.splitext('.jpg') -> ('.jpg', '') -> other
        assert_eq!(classify_storage_category(".jpg"), 5);
        assert_eq!(classify_storage_category("..mp4"), 5);
    }

    #[test]
    fn test_compute_storage_summary_sample_db() {
        let summary = compute_storage_summary("sample_index.db").unwrap();
        assert_eq!(summary.total_files, 7);
        assert_eq!(summary.total_dirs, 0);
        assert_eq!(summary.by_type["image"].files, 1);
        assert_eq!(summary.by_type["video"].files, 1);
        assert_eq!(summary.by_type["doc"].files, 2);
        assert_eq!(summary.by_type["archive"].files, 1);
        assert_eq!(summary.by_type["other"].files, 2);
        assert_eq!(summary.largest[0].path, "large_archive.tar.gz");
        assert_eq!(summary.largest[0].bytes, 4_294_967_296);
    }
}
