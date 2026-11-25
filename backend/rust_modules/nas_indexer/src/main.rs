use rusqlite::{Connection, OpenFlags};
use serde::Serialize;
use std::env;
use std::fs;
use std::io::{self, Write};
use std::process::exit;

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
    // Mở ở chế độ READONLY để không gây lock với backend Python đang chạy
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn = Connection::open_with_flags(db_path, flags)
        .map_err(|e| format!("Failed to open DB in read-only mode: {}", e))?;

    // Đọc index_state
    let mut state_stmt = conn
        .prepare("SELECT root, generation, ready, scanned_at, catalogue_revision FROM index_state WHERE id=1")
        .map_err(|e| format!("Prepare state failed: {}", e))?;

    let state_row = state_stmt
        .query_row([], |row| {
            Ok((
                row.get::<_, i64>(1)?, // generation
                row.get::<_, i64>(2)?, // ready
                row.get::<_, f64>(3)?, // scanned_at
                row.get::<_, i64>(4)?, // catalogue_revision
            ))
        })
        .map_err(|e| format!("Query state row failed: {}", e))?;

    // Đếm tổng số tệp
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

    // Serialize header
    let meta_json = serde_json::to_string(&metadata)
        .map_err(|e| format!("Serialize metadata failed: {}", e))?;
    
    // Header format: metadata[:-1] + ',"files":['
    let header_prefix = &meta_json[..meta_json.len() - 1];
    writer
        .write_all(format!("{},\"files\":[", header_prefix).as_bytes())
        .map_err(|e| format!("Write header failed: {}", e))?;

    // Query các files
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

    // Đóng mảng
    writer.write_all(b"]}").map_err(|e| e.to_string())?;
    writer.flush().map_err(|e| e.to_string())?;

    Ok(count)
}

fn run_verify(db_path: &str, ground_truth_path: &str) {
    let mut rust_output = Vec::new();
    match stream_fast_index(db_path, &mut rust_output) {
        Ok(count) => {
            println!("Rust streamed {} files successfully ({} bytes).", count, rust_output.len());
        }
        Err(e) => {
            eprintln!("Error streaming from DB: {}", e);
            exit(1);
        }
    }

    let ground_truth_bytes = match fs::read(ground_truth_path) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("Failed to read ground truth file {}: {}", ground_truth_path, e);
            exit(1);
        }
    };

    println!("Ground truth size: {} bytes", ground_truth_bytes.len());
    println!("Rust output size:   {} bytes", rust_output.len());

    // So sánh cấu trúc JSON (để bỏ qua khác biệt nhỏ về whitespace nếu có)
    let parsed_truth: serde_json::Value = match serde_json::from_slice(&ground_truth_bytes) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("Ground truth JSON invalid: {}", e);
            exit(1);
        }
    };

    let parsed_rust: serde_json::Value = match serde_json::from_slice(&rust_output) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("Rust output JSON invalid: {}", e);
            exit(1);
        }
    };

    if parsed_truth == parsed_rust {
        println!("\n✅ SEMANTIC PARITY SUCCESS: Rust output matches Python ground-truth 100%!");
    } else {
        eprintln!("\n❌ MISMATCH: Rust output differs from Python ground truth!");
        exit(1);
    }

    // Kiểm tra độ khớp chuỗi
    if rust_output == ground_truth_bytes {
        println!("✅ EXACT BYTE-FOR-BYTE PARITY: 100% identical byte stream!");
    } else {
        println!("ℹ️  Notice: Byte stream has minor whitespace differences, but semantic JSON is 100% identical.");
    }
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: nas_indexer <verify <db_path> <ground_truth.json> | stream <db_path>>");
        exit(1);
    }

    match args[1].as_str() {
        "verify" => {
            if args.len() < 4 {
                eprintln!("Usage: nas_indexer verify <db_path> <ground_truth.json>");
                exit(1);
            }
            run_verify(&args[2], &args[3]);
        }
        "stream" => {
            if args.len() < 3 {
                eprintln!("Usage: nas_indexer stream <db_path>");
                exit(1);
            }
            let stdout = io::stdout();
            let handle = stdout.lock();
            // Goi thang ham chuan trong lib.rs (co unchecked_transaction read snapshot nhat quan)
            if let Err(e) = nas_indexer_core::stream_fast_index(&args[2], handle) {
                eprintln!("Stream error: {}", e);
                exit(1);
            }
        }
        "search" => {
            if args.len() < 4 {
                eprintln!("Usage: nas_indexer search <db_path> <query> [root_param] [limit]");
                exit(1);
            }
            let root = args.get(4).map(|s| s.as_str()).unwrap_or("");
            let limit = args
                .get(5)
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(300);
            match nas_indexer_core::search_file_index(&args[2], &args[3], root, limit) {
                Ok(rows) => {
                    if let Ok(json) = serde_json::to_string(&rows) {
                        println!("{}", json);
                    }
                }
                Err(e) => {
                    eprintln!("Search error: {}", e);
                    exit(1);
                }
            }
        }
        "build-fts" => {
            if args.len() < 3 {
                eprintln!("Usage: nas_indexer build-fts <db_path>");
                exit(1);
            }
            match nas_indexer_core::build_fts_index(&args[2]) {
                Ok(count) => println!("Built FTS5 trigram index for {} rows.", count),
                Err(e) => {
                    eprintln!("Build FTS error: {}", e);
                    exit(1);
                }
            }
        }
        "storage-summary" => {
            if args.len() < 3 {
                eprintln!("Usage: nas_indexer storage-summary <db_path>");
                exit(1);
            }
            match nas_indexer_core::compute_storage_summary(&args[2]) {
                Ok(summary) => {
                    println!("{}", serde_json::to_string(&summary).unwrap_or_else(|_| "{}".to_string()));
                }
                Err(e) => {
                    eprintln!("Storage summary error: {}", e);
                    exit(1);
                }
            }
        }
        _ => {
            eprintln!("Unknown command: {}", args[1]);
            exit(1);
        }
    }
}
