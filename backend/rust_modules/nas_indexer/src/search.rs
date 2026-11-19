use crate::diacritics::remove_diacritics;
use rusqlite::{params, Connection, OpenFlags};
use std::time::Duration;

fn escape_like(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '%' => out.push_str("\\%"),
            '_' => out.push_str("\\_"),
            other => out.push(other),
        }
    }
    out
}

pub type SearchRow = (String, String, i64, i64, i64, String);

/// Truy vấn file_index trong SQLite bằng Rust (READ_ONLY + NO_MUTEX).
/// Khớp 100% ngữ nghĩa với api_fast_search của Python.
pub fn search_file_index(
    db_path: &str,
    query: &str,
    root_param: &str,
    limit: i64,
) -> Result<Vec<SearchRow>, String> {
    let norm_query = remove_diacritics(&query.to_lowercase());
    let like_pattern = format!("%{}%", escape_like(&norm_query));
    let effective_limit = if limit > 0 { limit } else { 300 };

    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn = Connection::open_with_flags(db_path, flags)
        .map_err(|e| format!("Failed to open DB: {}", e))?;
    let _ = conn.busy_timeout(Duration::from_millis(5000));

    let mut results = Vec::new();
    let root_clean = root_param.trim_matches('/');

    if !root_clean.is_empty() {
        let lower_bound = format!("{}/", root_clean);
        let upper_bound = format!("{}0", root_clean);
        let mut stmt = conn
            .prepare(
                "SELECT path, name, is_dir, size, mtime, mime FROM file_index \
                 WHERE path >= ?1 AND path < ?2 AND norm_name LIKE ?3 ESCAPE '\\' \
                 ORDER BY is_dir DESC, name ASC LIMIT ?4",
            )
            .map_err(|e| format!("Prepare scoped search failed: {}", e))?;

        let rows = stmt
            .query_map(
                params![lower_bound, upper_bound, like_pattern, effective_limit],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, i64>(3).unwrap_or(0),
                        row.get::<_, i64>(4).unwrap_or(0),
                        row.get::<_, String>(5).unwrap_or_default(),
                    ))
                },
            )
            .map_err(|e| format!("Query scoped search failed: {}", e))?;

        for r in rows {
            results.push(r.map_err(|e| e.to_string())?);
        }
    } else {
        let mut stmt = conn
            .prepare(
                "SELECT path, name, is_dir, size, mtime, mime FROM file_index \
                 WHERE norm_name LIKE ?1 ESCAPE '\\' \
                 ORDER BY is_dir DESC, name ASC LIMIT ?2",
            )
            .map_err(|e| format!("Prepare global search failed: {}", e))?;

        let rows = stmt
            .query_map(params![like_pattern, effective_limit], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, i64>(3).unwrap_or(0),
                    row.get::<_, i64>(4).unwrap_or(0),
                    row.get::<_, String>(5).unwrap_or_default(),
                ))
            })
            .map_err(|e| format!("Query global search failed: {}", e))?;

        for r in rows {
            results.push(r.map_err(|e| e.to_string())?);
        }
    }

    Ok(results)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_escape_like() {
        assert_eq!(escape_like("100%_+"), "100\\%\\_+",);
        assert_eq!(escape_like("a\\b%c_d"), "a\\\\b\\%c\\_d");
    }

    #[test]
    fn test_search_sample_db() {
        let rows = search_file_index("sample_index.db", "tài liệu", "", 300).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].1, "Tài liệu tiếng Việt có dấu.docx");

        let scoped = search_file_index("sample_index.db", "pdf", "docs", 300).unwrap();
        assert_eq!(scoped.len(), 1);
        assert_eq!(scoped[0].0, "docs/baocao.pdf");

        let miss = search_file_index("sample_index.db", "pdf", "photos", 300).unwrap();
        assert_eq!(miss.len(), 0);
    }
}
