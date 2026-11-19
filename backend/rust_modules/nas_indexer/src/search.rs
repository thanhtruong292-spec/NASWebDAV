use crate::diacritics::remove_diacritics;
use rusqlite::{params, Connection, OpenFlags};
use std::fs;
use std::path::Path;
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

fn format_fts5_phrase(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        if c == '"' {
            out.push_str("\"\"");
        } else {
            out.push(c);
        }
    }
    out.push('"');
    out
}

pub fn fts_db_path(db_path: &str) -> String {
    format!("{}.fts.db", db_path)
}

pub type SearchRow = (String, String, i64, i64, i64, String);

fn read_index_state_version(conn: &Connection) -> (i64, i64) {
    conn.query_row(
        "SELECT generation, catalogue_revision FROM index_state WHERE id=1",
        [],
        |r| Ok((r.get::<_, i64>(0).unwrap_or(0), r.get::<_, i64>(1).unwrap_or(0))),
    )
    .unwrap_or((0, 0))
}

/// Xây dựng sidecar FTS5 trigram index (<db_path>.fts.db) bằng SQLite 3.45 bundled trong Rust.
/// Không chạm vào schema của nas_index.db nên 100% tương thích Python 3.5.3 (SQLite 3.16.2).
pub fn build_fts_index(db_path: &str) -> Result<usize, String> {
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let src_conn = Connection::open_with_flags(db_path, flags)
        .map_err(|e| format!("Failed to open source DB: {}", e))?;
    let _ = src_conn.busy_timeout(Duration::from_millis(5000));

    let (generation, revision) = read_index_state_version(&src_conn);

    let final_path = fts_db_path(db_path);
    let tmp_path = format!("{}.tmp.{}", final_path, std::process::id());
    if Path::new(&tmp_path).exists() {
        let _ = fs::remove_file(&tmp_path);
    }

    let build_res = (|| -> Result<usize, String> {
        let mut fts_conn = Connection::open(&tmp_path)
            .map_err(|e| format!("Failed to create FTS DB: {}", e))?;

        fts_conn
            .execute_batch(
                "PRAGMA journal_mode=OFF;
                 PRAGMA synchronous=OFF;
                 PRAGMA temp_store=MEMORY;
                 PRAGMA cache_size=-16000;
                 CREATE TABLE fts_meta (
                     id INTEGER PRIMARY KEY,
                     generation INTEGER NOT NULL,
                     catalogue_revision INTEGER NOT NULL,
                     row_count INTEGER NOT NULL
                 );
                 CREATE VIRTUAL TABLE file_fts USING fts5(
                     norm_name,
                     tokenize='trigram'
                 );",
            )
            .map_err(|e| format!("Failed to init FTS5 schema: {}", e))?;

        let tx = fts_conn
            .transaction()
            .map_err(|e| format!("Begin FTS tx failed: {}", e))?;

        let mut src_stmt = src_conn
            .prepare("SELECT rowid, norm_name FROM file_index")
            .map_err(|e| format!("Prepare source read failed: {}", e))?;
        let mut ins_stmt = tx
            .prepare("INSERT INTO file_fts(rowid, norm_name) VALUES (?1, ?2)")
            .map_err(|e| format!("Prepare FTS insert failed: {}", e))?;

        let mut rows = src_stmt
            .query([])
            .map_err(|e| format!("Query source rows failed: {}", e))?;

        let mut count: usize = 0;
        while let Some(row) = rows.next().map_err(|e| e.to_string())? {
            let rowid: i64 = row.get(0).map_err(|e| e.to_string())?;
            let norm_name: String = row.get::<_, String>(1).unwrap_or_default();
            ins_stmt
                .execute(params![rowid, norm_name])
                .map_err(|e| format!("Insert FTS row failed: {}", e))?;
            count += 1;
        }
        drop(ins_stmt);

        tx.execute(
            "INSERT INTO fts_meta(id, generation, catalogue_revision, row_count) VALUES (1, ?1, ?2, ?3)",
            params![generation, revision, count as i64],
        )
        .map_err(|e| format!("Insert fts_meta failed: {}", e))?;

        tx.commit()
            .map_err(|e| format!("Commit FTS tx failed: {}", e))?;

        Ok(count)
    })();

    match build_res {
        Ok(count) => {
            if let Err(e) = fs::rename(&tmp_path, &final_path) {
                let _ = fs::remove_file(&tmp_path);
                return Err(format!("Atomic rename FTS DB failed: {}", e));
            }
            Ok(count)
        }
        Err(e) => {
            let _ = fs::remove_file(&tmp_path);
            Err(e)
        }
    }
}

fn try_search_via_fts(
    conn: &Connection,
    fts_path: &str,
    norm_query: &str,
    like_pattern: &str,
    root_clean: &str,
    effective_limit: i64,
) -> Option<Vec<SearchRow>> {
    if norm_query.chars().count() < 3 || !Path::new(fts_path).is_file() {
        return None;
    }

    let (src_gen, src_rev) = read_index_state_version(conn);

    if conn
        .execute("ATTACH DATABASE ?1 AS fts", params![fts_path])
        .is_err()
    {
        return None;
    }

    let query_result = (|| -> Option<Vec<SearchRow>> {
        let fts_meta: (i64, i64) = conn
            .query_row(
                "SELECT generation, catalogue_revision FROM fts.fts_meta WHERE id=1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .ok()?;

        // Chỉ dùng FTS5 khi khớp đúng phiên bản catalogue hiện tại
        if fts_meta != (src_gen, src_rev) {
            return None;
        }

        let fts_phrase = format_fts5_phrase(norm_query);
        let mut results = Vec::new();

        if !root_clean.is_empty() {
            let lower_bound = format!("{}/", root_clean);
            let upper_bound = format!("{}0", root_clean);
            let mut stmt = conn
                .prepare(
                    "SELECT fi.path, fi.name, fi.is_dir, fi.size, fi.mtime, fi.mime \
                     FROM fts.file_fts AS ff \
                     JOIN main.file_index AS fi ON fi.rowid = ff.rowid \
                     WHERE ff.norm_name MATCH ?1 \
                       AND fi.path >= ?2 AND fi.path < ?3 \
                       AND fi.norm_name LIKE ?4 ESCAPE '\\' \
                     ORDER BY fi.is_dir DESC, fi.name ASC LIMIT ?5",
                )
                .ok()?;

            let rows = stmt
                .query_map(
                    params![fts_phrase, lower_bound, upper_bound, like_pattern, effective_limit],
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
                .ok()?;

            for r in rows {
                results.push(r.ok()?);
            }
        } else {
            let mut stmt = conn
                .prepare(
                    "SELECT fi.path, fi.name, fi.is_dir, fi.size, fi.mtime, fi.mime \
                     FROM fts.file_fts AS ff \
                     JOIN main.file_index AS fi ON fi.rowid = ff.rowid \
                     WHERE ff.norm_name MATCH ?1 \
                       AND fi.norm_name LIKE ?2 ESCAPE '\\' \
                     ORDER BY fi.is_dir DESC, fi.name ASC LIMIT ?3",
                )
                .ok()?;

            let rows = stmt
                .query_map(params![fts_phrase, like_pattern, effective_limit], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, i64>(3).unwrap_or(0),
                        row.get::<_, i64>(4).unwrap_or(0),
                        row.get::<_, String>(5).unwrap_or_default(),
                    ))
                })
                .ok()?;

            for r in rows {
                results.push(r.ok()?);
            }
        }

        Some(results)
    })();

    let _ = conn.execute("DETACH DATABASE fts", []);
    query_result
}

/// Truy vấn file_index trong SQLite bằng Rust (READ_ONLY + NO_MUTEX).
/// Tự động tận dụng sidecar FTS5 trigram (<db_path>.fts.db) khi đồng bộ phiên bản và query >= 3 ký tự;
/// tự động fallback về truy vấn SQLite LIKE chuẩn khi query < 3 ký tự hoặc FTS chưa sẵn sàng.
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

    let root_clean = root_param.trim_matches('/');

    // Fast path: FTS5 trigram sidecar
    let fts_path = fts_db_path(db_path);
    if let Some(fts_rows) = try_search_via_fts(
        &conn,
        &fts_path,
        &norm_query,
        &like_pattern,
        root_clean,
        effective_limit,
    ) {
        return Ok(fts_rows);
    }

    // Fallback path: standard SQLite LIKE
    let mut results = Vec::new();
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
    fn test_search_sample_db_and_fts5_trigram() {
        let fts_file = fts_db_path("sample_index.db");
        let _ = fs::remove_file(&fts_file);

        // 1. Search without FTS
        let rows_plain = search_file_index("sample_index.db", "tài liệu", "", 300).unwrap();
        assert_eq!(rows_plain.len(), 1);
        assert_eq!(rows_plain[0].1, "Tài liệu tiếng Việt có dấu.docx");

        // 2. Build FTS5 trigram sidecar
        let indexed = build_fts_index("sample_index.db").unwrap();
        assert_eq!(indexed, 7);
        assert!(Path::new(&fts_file).is_file());

        // 3. Verify FTS5 fast path actually executes (not fallback) and matches 100%
        let conn = Connection::open_with_flags(
            "sample_index.db",
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .unwrap();
        let fts_direct = try_search_via_fts(&conn, &fts_file, "tai lieu", "%tai lieu%", "", 300);
        assert!(fts_direct.is_some(), "FTS5 trigram fast path must execute");
        assert_eq!(fts_direct.unwrap(), rows_plain);

        let rows_fts = search_file_index("sample_index.db", "tài liệu", "", 300).unwrap();
        assert_eq!(rows_fts, rows_plain);

        let scoped = search_file_index("sample_index.db", "pdf", "docs", 300).unwrap();
        assert_eq!(scoped.len(), 1);
        assert_eq!(scoped[0].0, "docs/baocao.pdf");

        let miss = search_file_index("sample_index.db", "pdf", "photos", 300).unwrap();
        assert_eq!(miss.len(), 0);

        let _ = fs::remove_file(&fts_file);
    }
}
