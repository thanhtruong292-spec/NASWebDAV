use rusqlite::{Connection, OpenFlags};
use serde::Serialize;

#[derive(Debug, Serialize, Clone)]
pub struct SimilarImageMatch {
    pub path: String,
    pub ahash: String,
    pub hamming_distance: u32,
    pub size: i64,
    pub mtime: i64,
}

#[inline]
fn parse_hex_u64_16(s: &str) -> Option<u64> {
    if s.len() != 16 {
        return None;
    }
    u64::from_str_radix(s, 16).ok()
}

/// Tìm các ảnh tương đồng theo khoảng cách Hamming trên bảng `image_fingerprints`
/// bằng `u64::count_ones()` (ARM64 NEON `VCNT` popcount), zero-GIL, streaming SQLite.
pub fn find_similar_fingerprints(
    db_path: &str,
    exclude_path: &str,
    target_hash_hex: &str,
    threshold: u32,
    limit: usize,
) -> Result<Vec<SimilarImageMatch>, String> {
    let target_u64 =
        parse_hex_u64_16(target_hash_hex).ok_or_else(|| "invalid_target_hash".to_string())?;
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn = Connection::open_with_flags(db_path, flags)
        .map_err(|e| format!("db_open_error: {}", e))?;
    let _ = conn.busy_timeout(std::time::Duration::from_millis(5000));

    let mut stmt = conn
        .prepare("SELECT path, ahash, size, mtime FROM image_fingerprints WHERE path<>?1 AND ahash<>''")
        .map_err(|e| format!("prepare_error: {}", e))?;

    let mut rows = stmt
        .query([exclude_path])
        .map_err(|e| format!("query_error: {}", e))?;

    let mut matches: Vec<SimilarImageMatch> = Vec::new();

    while let Some(row) = rows.next().map_err(|e| format!("row_error: {}", e))? {
        let ahash_ref = row.get_ref(1).map_err(|e| e.to_string())?.as_str().unwrap_or("");
        let candidate_u64 = match parse_hex_u64_16(ahash_ref) {
            Some(v) => v,
            None => continue,
        };
        let dist = (target_u64 ^ candidate_u64).count_ones();
        if dist <= threshold {
            let rel = row.get_ref(0).map_err(|e| e.to_string())?.as_str().unwrap_or("");
            let size = row.get::<_, i64>(2).unwrap_or(0);
            let mtime = row.get::<_, i64>(3).unwrap_or(0);
            matches.push(SimilarImageMatch {
                path: format!("/webdav/{}", rel),
                ahash: ahash_ref.to_string(),
                hamming_distance: dist,
                size,
                mtime,
            });
        }
    }

    matches.sort_by(|a, b| {
        a.hamming_distance
            .cmp(&b.hamming_distance)
            .then_with(|| a.path.cmp(&b.path))
    });
    if matches.len() > limit {
        matches.truncate(limit);
    }
    Ok(matches)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_find_similar_fingerprints_hamming() {
        let tmp = std::env::temp_dir().join(format!("fp_sim_test_{}.db", std::process::id()));
        let _ = std::fs::remove_file(&tmp);
        let conn = Connection::open(&tmp).unwrap();
        conn.execute_batch(
            "CREATE TABLE image_fingerprints (path TEXT PRIMARY KEY, ahash TEXT, size INTEGER, mtime INTEGER);
             INSERT INTO image_fingerprints VALUES ('a.jpg', '0000000000000000', 100, 1000);
             INSERT INTO image_fingerprints VALUES ('b.jpg', '0000000000000003', 200, 2000);
             INSERT INTO image_fingerprints VALUES ('c.jpg', '0000000000000001', 300, 3000);
             INSERT INTO image_fingerprints VALUES ('d.jpg', 'ffffffffffffffff', 400, 4000);",
        )
        .unwrap();
        drop(conn);

        let res = find_similar_fingerprints(
            tmp.to_str().unwrap(),
            "a.jpg",
            "0000000000000000",
            5,
            10,
        )
        .unwrap();
        assert_eq!(res.len(), 2);
        assert_eq!(res[0].path, "/webdav/c.jpg");
        assert_eq!(res[0].hamming_distance, 1);
        assert_eq!(res[1].path, "/webdav/b.jpg");
        assert_eq!(res[1].hamming_distance, 2);

        let _ = std::fs::remove_file(&tmp);
    }
}
