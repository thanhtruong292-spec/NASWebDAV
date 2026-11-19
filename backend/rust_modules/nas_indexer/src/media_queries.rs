use rusqlite::{params, Connection, OpenFlags};
use serde::Serialize;
use std::time::Duration;

#[derive(Debug, Serialize, PartialEq)]
pub struct RecentMediaItem {
    pub path: String,
    pub name: String,
    pub mtime: i64,
    pub size: i64,
    pub mime: String,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct TimelineItem {
    pub path: String,
    pub mtime: i64,
    pub size: i64,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct TimelineResult {
    pub total: u64,
    pub items: Vec<TimelineItem>,
}

/// Kiểm tra đuôi ảnh khớp 100% với `_IMAGE_EXTS` của `nas_api_server.py` (O(1) zero-alloc).
pub fn is_image_ext(name: &str) -> bool {
    let bytes = name.as_bytes();
    let dot_idx = match bytes.iter().rposition(|&b| b == b'.') {
        Some(i) => i,
        None => return false,
    };
    let ext = &bytes[dot_idx + 1..];
    if ext.is_empty() || ext.len() > 4 {
        return false;
    }
    let mut lower = [0u8; 4];
    for (i, &b) in ext.iter().enumerate() {
        lower[i] = b.to_ascii_lowercase();
    }
    matches!(
        &lower[..ext.len()],
        b"jpg" | b"jpeg" | b"png" | b"webp" | b"heic" | b"heif" | b"bmp" | b"gif"
    )
}

/// Kiểm tra đuôi video khớp 100% với `_VIDEO_EXTS` của `nas_api_server.py` (O(1) zero-alloc).
pub fn is_video_ext(name: &str) -> bool {
    let bytes = name.as_bytes();
    let dot_idx = match bytes.iter().rposition(|&b| b == b'.') {
        Some(i) => i,
        None => return false,
    };
    let ext = &bytes[dot_idx + 1..];
    if ext.is_empty() || ext.len() > 4 {
        return false;
    }
    let mut lower = [0u8; 4];
    for (i, &b) in ext.iter().enumerate() {
        lower[i] = b.to_ascii_lowercase();
    }
    matches!(
        &lower[..ext.len()],
        b"mp4" | b"mkv" | b"avi" | b"mov" | b"mpg" | b"mpeg" | b"wmv" | b"flv" | b"ts" | b"m4v"
            | b"3gp"
    )
}

/// Truy vấn media gần nhất (`api_recent_media`) trực tiếp trên index `mtime DESC, path`,
/// dừng sớm ngay khi đủ `limit` (mặc định 100) mà không dùng chuỗi 8-11 điều kiện `OR lower(name) LIKE`.
pub fn query_recent_media(
    db_path: &str,
    kind: &str,
    scope: &str,
    limit: usize,
) -> Result<Vec<RecentMediaItem>, String> {
    let matcher: fn(&str) -> bool = match kind {
        "image" => is_image_ext,
        "video" => is_video_ext,
        _ => return Err(format!("Unsupported kind: {}", kind)),
    };

    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn = Connection::open_with_flags(db_path, flags)
        .map_err(|e| format!("Failed to open DB: {}", e))?;
    let _ = conn.busy_timeout(Duration::from_millis(5000));

    let effective_limit = if limit > 0 { limit } else { 100 };
    let mut items = Vec::with_capacity(effective_limit.min(256));
    let scope_clean = scope.trim_matches('/');

    if !scope_clean.is_empty() {
        let lower_bound = format!("{}/", scope_clean);
        let upper_bound = format!("{}0", scope_clean);
        let mut stmt = conn
            .prepare(
                "SELECT path, name, mtime, size, mime FROM file_index \
                 WHERE is_dir=0 AND path>=?1 AND path<?2 \
                 ORDER BY mtime DESC, path",
            )
            .map_err(|e| format!("Prepare recent scoped failed: {}", e))?;

        let mut rows = stmt
            .query(params![lower_bound, upper_bound])
            .map_err(|e| format!("Query recent scoped failed: {}", e))?;

        while let Some(row) = rows.next().map_err(|e| e.to_string())? {
            let name_ref = row.get_ref(1).ok().and_then(|v| v.as_str().ok()).unwrap_or("");
            if !matcher(name_ref) {
                continue;
            }
            let path: String = row.get(0).map_err(|e| e.to_string())?;
            let mtime: i64 = row.get::<_, i64>(2).unwrap_or(0);
            let size: i64 = row.get::<_, i64>(3).unwrap_or(0);
            let mime: String = row.get::<_, String>(4).unwrap_or_default();
            items.push(RecentMediaItem {
                path: format!("/webdav/{}", path),
                name: name_ref.to_string(),
                mtime,
                size,
                mime,
            });
            if items.len() >= effective_limit {
                break;
            }
        }
    } else {
        let mut stmt = conn
            .prepare(
                "SELECT path, name, mtime, size, mime FROM file_index \
                 WHERE is_dir=0 \
                 ORDER BY mtime DESC, path",
            )
            .map_err(|e| format!("Prepare recent global failed: {}", e))?;

        let mut rows = stmt
            .query([])
            .map_err(|e| format!("Query recent global failed: {}", e))?;

        while let Some(row) = rows.next().map_err(|e| e.to_string())? {
            let name_ref = row.get_ref(1).ok().and_then(|v| v.as_str().ok()).unwrap_or("");
            if !matcher(name_ref) {
                continue;
            }
            let path: String = row.get(0).map_err(|e| e.to_string())?;
            let mtime: i64 = row.get::<_, i64>(2).unwrap_or(0);
            let size: i64 = row.get::<_, i64>(3).unwrap_or(0);
            let mime: String = row.get::<_, String>(4).unwrap_or_default();
            items.push(RecentMediaItem {
                path: format!("/webdav/{}", path),
                name: name_ref.to_string(),
                mtime,
                size,
                mime,
            });
            if items.len() >= effective_limit {
                break;
            }
        }
    }

    Ok(items)
}

/// Truy vấn Timeline ảnh (`api_photos_timeline`) trong 1 lượt quét duy nhất trên index `mtime DESC, path`,
/// vừa đếm `total` vừa lấy đúng trang `[offset .. offset + limit]` (thay vì 2 lượt 8-LIKE SQL).
/// `start_ms` / `end_ms`: truyền < 0 nếu không lọc theo tháng.
pub fn query_photos_timeline(
    db_path: &str,
    start_ms: i64,
    end_ms: i64,
    limit: usize,
    offset: usize,
) -> Result<TimelineResult, String> {
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn = Connection::open_with_flags(db_path, flags)
        .map_err(|e| format!("Failed to open DB: {}", e))?;
    let _ = conn.busy_timeout(Duration::from_millis(5000));

    let effective_limit = limit.clamp(1, 2000);
    let mut items = Vec::with_capacity(effective_limit.min(512));
    let mut total: u64 = 0;

    let has_range = start_ms >= 0 && end_ms > start_ms;

    if has_range {
        let mut stmt = conn
            .prepare(
                "SELECT path, name, mtime, size FROM file_index \
                 WHERE is_dir=0 AND mtime>=?1 AND mtime<?2 \
                 ORDER BY mtime DESC, path",
            )
            .map_err(|e| format!("Prepare timeline ranged failed: {}", e))?;

        let mut rows = stmt
            .query(params![start_ms, end_ms])
            .map_err(|e| format!("Query timeline ranged failed: {}", e))?;

        while let Some(row) = rows.next().map_err(|e| e.to_string())? {
            let name_ref = row.get_ref(1).ok().and_then(|v| v.as_str().ok()).unwrap_or("");
            if !is_image_ext(name_ref) {
                continue;
            }
            if total >= offset as u64 && items.len() < effective_limit {
                let path: String = row.get(0).map_err(|e| e.to_string())?;
                let mtime: i64 = row.get::<_, i64>(2).unwrap_or(0);
                let size: i64 = row.get::<_, i64>(3).unwrap_or(0);
                items.push(TimelineItem {
                    path,
                    mtime: mtime / 1000,
                    size,
                });
            }
            total += 1;
        }
    } else {
        let mut stmt = conn
            .prepare(
                "SELECT path, name, mtime, size FROM file_index \
                 WHERE is_dir=0 \
                 ORDER BY mtime DESC, path",
            )
            .map_err(|e| format!("Prepare timeline global failed: {}", e))?;

        let mut rows = stmt
            .query([])
            .map_err(|e| format!("Query timeline global failed: {}", e))?;

        while let Some(row) = rows.next().map_err(|e| e.to_string())? {
            let name_ref = row.get_ref(1).ok().and_then(|v| v.as_str().ok()).unwrap_or("");
            if !is_image_ext(name_ref) {
                continue;
            }
            if total >= offset as u64 && items.len() < effective_limit {
                let path: String = row.get(0).map_err(|e| e.to_string())?;
                let mtime: i64 = row.get::<_, i64>(2).unwrap_or(0);
                let size: i64 = row.get::<_, i64>(3).unwrap_or(0);
                items.push(TimelineItem {
                    path,
                    mtime: mtime / 1000,
                    size,
                });
            }
            total += 1;
        }
    }

    Ok(TimelineResult { total, items })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_recent_media_and_timeline_sample_db() {
        let imgs = query_recent_media("sample_index.db", "image", "", 10).unwrap();
        assert_eq!(imgs.len(), 1);
        assert_eq!(imgs[0].path, "/webdav/photos/IMG_2026.HEIC");

        let vids = query_recent_media("sample_index.db", "video", "", 10).unwrap();
        assert_eq!(vids.len(), 1);
        assert_eq!(vids[0].name, "clip 01 [HD].mp4");

        let tl = query_photos_timeline("sample_index.db", -1, -1, 500, 0).unwrap();
        assert_eq!(tl.total, 1);
        assert_eq!(tl.items[0].path, "photos/IMG_2026.HEIC");
        assert_eq!(tl.items[0].mtime, 1795000200);
    }
}
