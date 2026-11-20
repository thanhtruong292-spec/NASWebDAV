use rusqlite::{params, Connection, OpenFlags};
use serde::Serialize;
use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::Path;

#[derive(Debug, Serialize)]
pub struct PhotoTagScanResult {
    pub success: bool,
    pub total: u64,
    pub catalogue_revision: i64,
    pub categories_count: usize,
}

/// Bảng phân loại khớp 100% `_FOLDER_CATEGORY_MAP` trong `nas_api_server.py`.
const CATEGORY_RULES: &[(&str, &[&str])] = &[
    ("Khuon Mat", &["selfie", "portrait", "face", "avatar"]),
    (
        "Mang Xa Hoi",
        &["facebook", "tiktok", "telegram", "instagram", "zalo", "messenger"],
    ),
    (
        "Thien Nhien / Bien",
        &["beach", "sea", "ocean", "nature", "mountain", "sunset", "sky"],
    ),
    ("Video", &["video", "movie", "film", "clip"]),
    (
        "Tai Lieu",
        &["document", "doc", "scan", "notes", "samsung notes", "pdf"],
    ),
    (
        "Camera / Giam Sat",
        &["surveillance", "camera", "cctv", "security"],
    ),
    ("ảnh Tai Ve", &["download", "downloads", "saved"]),
    (
        "Album Dien Thoai",
        &["dcim", "camera", "screenshot", "album"],
    ),
];

#[inline]
fn is_image_ext(name: &str) -> bool {
    let dot = match name.rfind('.') {
        Some(i) => i,
        None => return false,
    };
    let ext = &name[dot..];
    ext.eq_ignore_ascii_case(".jpg")
        || ext.eq_ignore_ascii_case(".jpeg")
        || ext.eq_ignore_ascii_case(".png")
        || ext.eq_ignore_ascii_case(".webp")
        || ext.eq_ignore_ascii_case(".bmp")
        || ext.eq_ignore_ascii_case(".gif")
        || ext.eq_ignore_ascii_case(".heic")
}

#[inline]
fn classify_photo_path(lower_path: &str) -> &'static str {
    for (cat_name, keywords) in CATEGORY_RULES {
        for kw in *keywords {
            if lower_path.contains(kw) {
                return cat_name;
            }
        }
    }
    "Khac"
}

/// Quét streaming 1 lượt bảng `file_index` và ghi trực tiếp vào `ai_image_tags` trong cùng transaction,
/// không load 400k tuples vào Python heap (tiết kiệm ~120-140MB RAM trên RK3328 1GB RAM).
pub fn scan_photo_tags(
    db_path: &str,
    expected_root: &str,
    ai_tags_json_path: &str,
    now_secs: f64,
    freshness_secs: f64,
    generated_at: &str,
) -> Result<PhotoTagScanResult, String> {
    if expected_root.is_empty() {
        return Err("catalogue_root_unavailable".to_string());
    }
    let flags = OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let mut conn =
        Connection::open_with_flags(db_path, flags).map_err(|e| format!("db_open_error: {}", e))?;
    let _ = conn.busy_timeout(std::time::Duration::from_millis(10000));

    let state: Option<(String, i64, i64, f64)> = conn
        .query_row(
            "SELECT root, ready, catalogue_revision, scanned_at FROM index_state WHERE id=1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        )
        .ok();

    let catalogue_revision = match state {
        Some((root, ready, rev, scanned_at))
            if root == expected_root
                && ready != 0
                && (now_secs - scanned_at) <= freshness_secs =>
        {
            rev
        }
        _ => return Err("catalogue_refresh_pending".to_string()),
    };

    let tx = conn
        .transaction()
        .map_err(|e| format!("tx_begin_error: {}", e))?;

    tx.execute(
        "DELETE FROM ai_image_tags WHERE catalogue_revision=?1",
        params![catalogue_revision],
    )
    .map_err(|e| format!("delete_old_tags_error: {}", e))?;

    let mut total: u64 = 0;
    let write_json = !ai_tags_json_path.is_empty();
    let mut cat_counts: BTreeMap<&'static str, u64> = BTreeMap::new();

    {
        let mut select_stmt = tx
            .prepare("SELECT path, name FROM file_index WHERE is_dir=0 ORDER BY path")
            .map_err(|e| format!("prepare_select_error: {}", e))?;
        let mut insert_stmt = tx
            .prepare(
                "INSERT OR REPLACE INTO ai_image_tags(path, category, catalogue_revision, updated_at) \
                 VALUES(?1, ?2, ?3, ?4)",
            )
            .map_err(|e| format!("prepare_insert_error: {}", e))?;

        let mut rows = select_stmt
            .query([])
            .map_err(|e| format!("query_error: {}", e))?;

        while let Some(row) = rows.next().map_err(|e| format!("row_error: {}", e))? {
            let name = row.get_ref(1).map_err(|e| e.to_string())?.as_str().unwrap_or("");
            if !is_image_ext(name) {
                continue;
            }
            let rel_path = row.get_ref(0).map_err(|e| e.to_string())?.as_str().unwrap_or("");
            let lower_path = rel_path.to_ascii_lowercase();
            let cat_name = classify_photo_path(&lower_path);

            insert_stmt
                .execute(params![rel_path, cat_name, catalogue_revision, now_secs])
                .map_err(|e| format!("insert_tag_error: {}", e))?;

            *cat_counts.entry(cat_name).or_insert(0) += 1;
            total += 1;
        }
    }

    tx.commit().map_err(|e| format!("tx_commit_error: {}", e))?;

    // Ghi đồng bộ ai_tags.json bằng streaming trực tiếp từ SQLite (không giữ list 400k strings trong RAM)
    if write_json {
        let json_p = Path::new(ai_tags_json_path);
        if total == 0 {
            let _ = fs::remove_file(json_p);
        } else {
            if let Some(parent) = json_p.parent() {
                let _ = fs::create_dir_all(parent);
            }
            let tmp_path = format!("{}.tmp", ai_tags_json_path);
            if let Ok(f) = File::create(&tmp_path) {
                let mut w = BufWriter::with_capacity(64 * 1024, f);
                let write_ok = (|| -> std::io::Result<()> {
                    w.write_all(b"{\"categories\":{")?;
                    let mut first_cat = true;
                    for (cat_name, _cnt) in &cat_counts {
                        if !first_cat {
                            w.write_all(b",")?;
                        }
                        first_cat = false;
                        let cat_json = serde_json::to_string(cat_name).unwrap_or_else(|_| "\"Khac\"".to_string());
                        w.write_all(cat_json.as_bytes())?;
                        w.write_all(b":[")?;

                        if let Ok(mut q) = conn.prepare(
                            "SELECT path FROM ai_image_tags WHERE catalogue_revision=?1 AND category=?2 ORDER BY path",
                        ) {
                            if let Ok(mut qrows) = q.query(params![catalogue_revision, *cat_name]) {
                                let mut first_item = true;
                                while let Ok(Some(r)) = qrows.next() {
                                    if let Ok(val) = r.get_ref(0) {
                                        if let Ok(p_str) = val.as_str() {
                                            if !first_item {
                                                w.write_all(b",")?;
                                            }
                                            first_item = false;
                                            let p_json = serde_json::to_string(p_str).unwrap_or_else(|_| "\"\"".to_string());
                                            w.write_all(p_json.as_bytes())?;
                                        }
                                    }
                                }
                            }
                        }
                        w.write_all(b"]")?;
                    }
                    let gen_json = serde_json::to_string(generated_at).unwrap_or_else(|_| "\"\"".to_string());
                    write!(
                        w,
                        "}},\"total\":{},\"generated_at\":{},\"catalogue_revision\":{},\"source\":\"file_index_db\"}}",
                        total, gen_json, catalogue_revision
                    )?;
                    w.flush()
                })();
                drop(w);
                if write_ok.is_ok() {
                    let _ = fs::rename(&tmp_path, json_p);
                } else {
                    let _ = fs::remove_file(&tmp_path);
                }
            }
        }
    }

    Ok(PhotoTagScanResult {
        success: true,
        total,
        catalogue_revision,
        categories_count: cat_counts.len(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_scan_photo_tags_streaming() {
        let tmp = std::env::temp_dir().join(format!("photo_tag_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();
        let db_path = tmp.join("idx.db");
        let json_path = tmp.join("ai_tags.json");

        let conn = Connection::open(&db_path).unwrap();
        conn.execute_batch(
            "CREATE TABLE index_state (id INTEGER PRIMARY KEY, root TEXT, generation INTEGER, catalogue_revision INTEGER, ready INTEGER, scanned_at REAL);
             CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, size INTEGER, mtime INTEGER, is_dir INTEGER);
             CREATE TABLE ai_image_tags (path TEXT PRIMARY KEY, category TEXT, catalogue_revision INTEGER, updated_at REAL);
             INSERT INTO index_state VALUES (1, '/srv/data', 1, 7, 1, 1800000000.0);
             INSERT INTO file_index VALUES ('Telegram/tg1.jpg', 'tg1.jpg', 100, 1000, 0);
             INSERT INTO file_index VALUES ('DCIM/cam1.PNG', 'cam1.PNG', 200, 2000, 0);
             INSERT INTO file_index VALUES ('Misc/random.webp', 'random.webp', 300, 3000, 0);
             INSERT INTO file_index VALUES ('Videos/clip.mp4', 'clip.mp4', 400, 4000, 0);",
        )
        .unwrap();
        drop(conn);

        let res = scan_photo_tags(
            db_path.to_str().unwrap(),
            "/srv/data",
            json_path.to_str().unwrap(),
            1800000010.0,
            300.0,
            "2026-11-20 07:00:00",
        )
        .unwrap();
        assert_eq!(res.total, 3);
        assert_eq!(res.catalogue_revision, 7);
        assert!(json_path.is_file());
        let json_txt = fs::read_to_string(&json_path).unwrap();
        assert!(json_txt.contains("Mang Xa Hoi"));
        assert!(json_txt.contains("Album Dien Thoai"));
        assert!(json_txt.contains("Khac"));

        let _ = fs::remove_dir_all(&tmp);
    }
}
