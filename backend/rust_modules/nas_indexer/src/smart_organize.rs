use rusqlite::{Connection, OpenFlags};
use serde::Serialize;
use std::collections::BTreeMap;

#[derive(Debug, Serialize, Clone)]
pub struct SampleFile {
    pub name: String,
    pub path: String,
    pub size: i64,
    pub mtime: i64,
}

#[derive(Debug, Serialize, Clone)]
pub struct OrganizeGroup {
    pub label: String,
    pub count: u64,
    pub size: i64,
    pub sample_files: Vec<SampleFile>,
}

#[derive(Debug, Serialize)]
pub struct SmartOrganizeResult {
    pub success: bool,
    pub total: u64,
    pub group_count: usize,
    pub groups: Vec<OrganizeGroup>,
}

/// Kiểm tra extension ASCII không phân biệt hoa thường (zero-allocation).
/// filter_mode: 0 = all media, 1 = image, 2 = video.
fn matches_filter(name: &str, filter_mode: u8) -> bool {
    let dot_idx = match name.rfind('.') {
        Some(i) => i,
        None => return false,
    };
    let ext = &name[dot_idx..];
    let is_image = ext.eq_ignore_ascii_case(".jpg")
        || ext.eq_ignore_ascii_case(".jpeg")
        || ext.eq_ignore_ascii_case(".png")
        || ext.eq_ignore_ascii_case(".webp")
        || ext.eq_ignore_ascii_case(".heic")
        || ext.eq_ignore_ascii_case(".heif")
        || ext.eq_ignore_ascii_case(".bmp")
        || ext.eq_ignore_ascii_case(".gif");
    if filter_mode == 1 {
        return is_image;
    }
    let is_video = ext.eq_ignore_ascii_case(".mp4")
        || ext.eq_ignore_ascii_case(".mkv")
        || ext.eq_ignore_ascii_case(".avi")
        || ext.eq_ignore_ascii_case(".mov")
        || ext.eq_ignore_ascii_case(".mpg")
        || ext.eq_ignore_ascii_case(".mpeg")
        || ext.eq_ignore_ascii_case(".wmv")
        || ext.eq_ignore_ascii_case(".flv")
        || ext.eq_ignore_ascii_case(".ts")
        || ext.eq_ignore_ascii_case(".m4v")
        || ext.eq_ignore_ascii_case(".3gp");
    if filter_mode == 2 {
        return is_video;
    }
    is_image || is_video
}

/// Chuyển Unix timestamp (giây đã cộng tz_offset) sang (year, month) theo thuật toán Howard Hinnant.
fn year_month_from_epoch_secs(local_secs: i64) -> (i64, u32) {
    let z = local_secs.div_euclid(86400) + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = (z - era * 146097) as u64; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; // [0, 399]
    let y = (yoe as i64) + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    let year = if m <= 2 { y + 1 } else { y };
    (year, m as u32)
}

fn is_valid_rel_path(rel: &str) -> bool {
    if rel.is_empty() || rel.starts_with('/') || rel.contains('\\') {
        return false;
    }
    for part in rel.split('/') {
        if part.is_empty() || part == "." || part == ".." {
            return false;
        }
    }
    true
}

/// Quét streaming 1 lượt trên `file_index` để gom nhóm Smart Organize (YYYY/MM).
/// Giữ parity 100% với `_smart_organize_candidates` + `api_smart_organize_scan` của Python.
pub fn scan_smart_organize(
    db_path: &str,
    expected_root: &str,
    filter_mode: u8,
    now_secs: f64,
    freshness_secs: f64,
    tz_offset_secs: i64,
) -> Result<SmartOrganizeResult, String> {
    if expected_root.is_empty() {
        return Err("catalogue_root_unavailable".to_string());
    }
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn = Connection::open_with_flags(db_path, flags)
        .map_err(|e| format!("db_open_error: {}", e))?;
    let _ = conn.busy_timeout(std::time::Duration::from_millis(5000));

    let state: Option<(String, i64, f64)> = conn
        .query_row(
            "SELECT root, ready, scanned_at FROM index_state WHERE id=1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .ok();

    match state {
        Some((root, ready, scanned_at))
            if root == expected_root
                && ready != 0
                && (now_secs - scanned_at) <= freshness_secs => {}
        _ => return Err("catalogue_refresh_pending".to_string()),
    }

    let mut stmt = conn
        .prepare("SELECT path, name, size, mtime FROM file_index WHERE is_dir=0 ORDER BY path")
        .map_err(|e| format!("prepare_error: {}", e))?;

    let mut rows = stmt.query([]).map_err(|e| format!("query_error: {}", e))?;

    struct GroupAcc {
        count: u64,
        size: i64,
        sample_files: Vec<SampleFile>,
    }

    let mut groups_map: BTreeMap<String, GroupAcc> = BTreeMap::new();
    let mut total: u64 = 0;

    while let Some(row) = rows.next().map_err(|e| format!("row_error: {}", e))? {
        let name = row.get_ref(1).map_err(|e| e.to_string())?.as_str().unwrap_or("");
        if !matches_filter(name, filter_mode) {
            continue;
        }
        let rel = row.get_ref(0).map_err(|e| e.to_string())?.as_str().unwrap_or("");
        if !is_valid_rel_path(rel) {
            return Err("Invalid indexed path".to_string());
        }
        let size = row.get::<_, i64>(2).unwrap_or(0);
        let mtime = row.get::<_, i64>(3).unwrap_or(0);

        // Bỏ qua file mới sửa trong vòng 24 giờ
        if now_secs - (mtime as f64) / 1000.0 < 86400.0 {
            continue;
        }

        let local_secs = mtime.div_euclid(1000) + tz_offset_secs;
        let (year, month) = year_month_from_epoch_secs(local_secs);
        let label = format!("{:04}/{:02}", year, month);

        // Kiểm tra file đã nằm sẵn trong thư mục YYYY/MM chưa
        if let Some(slash_idx) = rel.rfind('/') {
            let parent = &rel[..slash_idx];
            if parent == label
                || (parent.len() > label.len()
                    && parent.ends_with(&label)
                    && parent.as_bytes()[parent.len() - label.len() - 1] == b'/')
            {
                continue;
            }
        }

        let acc = groups_map.entry(label).or_insert_with(|| GroupAcc {
            count: 0,
            size: 0,
            sample_files: Vec::new(),
        });
        acc.count += 1;
        acc.size += size;
        total += 1;
        if acc.sample_files.len() < 5 {
            let file_name = match rel.rfind('/') {
                Some(idx) => &rel[idx + 1..],
                None => rel,
            };
            acc.sample_files.push(SampleFile {
                name: file_name.to_string(),
                path: format!("/{}", rel),
                size,
                mtime,
            });
        }
    }

    let groups: Vec<OrganizeGroup> = groups_map
        .into_iter()
        .rev()
        .map(|(label, acc)| OrganizeGroup {
            label,
            count: acc.count,
            size: acc.size,
            sample_files: acc.sample_files,
        })
        .collect();

    Ok(SmartOrganizeResult {
        success: true,
        total,
        group_count: groups.len(),
        groups,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_year_month_and_smart_organize_scan() {
        // 2025-03-15 12:00:00 UTC = 1742040000
        assert_eq!(year_month_from_epoch_secs(1742040000), (2025, 3));
        // 2020-02-29 00:00:00 UTC = 1582934400 (leap year)
        assert_eq!(year_month_from_epoch_secs(1582934400), (2020, 2));

        let tmp = std::env::temp_dir().join(format!("smart_org_test_{}.db", std::process::id()));
        let _ = std::fs::remove_file(&tmp);
        let conn = Connection::open(&tmp).unwrap();
        conn.execute_batch(
            "CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, catalogue_revision INTEGER, ready INTEGER, scanned_at REAL);
             CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, size INTEGER, mtime INTEGER, is_dir INTEGER);
             INSERT INTO index_state VALUES (1, '/srv/data', 1, 1, 1, 1800000000.0);
             INSERT INTO file_index VALUES ('DCIM/a.jpg', 'a.jpg', 1000, 1742040000000, 0);
             INSERT INTO file_index VALUES ('DCIM/b.MP4', 'b.MP4', 2000, 1742040000000, 0);
             INSERT INTO file_index VALUES ('2025/03/already.jpg', 'already.jpg', 500, 1742040000000, 0);
             INSERT INTO file_index VALUES ('DCIM/recent.jpg', 'recent.jpg', 500, 1799990000000, 0);
             INSERT INTO file_index VALUES ('docs/note.txt', 'note.txt', 300, 1742040000000, 0);",
        )
        .unwrap();
        drop(conn);

        let res = scan_smart_organize(
            tmp.to_str().unwrap(),
            "/srv/data",
            0,
            1800000010.0,
            300.0,
            0,
        )
        .unwrap();
        assert_eq!(res.total, 2);
        assert_eq!(res.group_count, 1);
        assert_eq!(res.groups[0].label, "2025/03");
        assert_eq!(res.groups[0].size, 3000);

        let img_only = scan_smart_organize(
            tmp.to_str().unwrap(),
            "/srv/data",
            1,
            1800000010.0,
            300.0,
            0,
        )
        .unwrap();
        assert_eq!(img_only.total, 1);
        assert_eq!(img_only.groups[0].sample_files[0].name, "a.jpg");

        let _ = std::fs::remove_file(&tmp);
    }
}
