use std::env;
use std::fs::File;
use std::io::{BufRead, BufReader, Read};
use std::path::Path;
use std::process::exit;

use md5::{Digest, Md5};
use serde::{Deserialize, Serialize};
use sha2::Sha256;

#[derive(Debug, Serialize, Deserialize, PartialEq)]
struct CaseRecord {
    path: String,
    #[serde(default)]
    exists: bool,
    #[serde(default)]
    size: u64,
    #[serde(default)]
    md5_1mb: Option<String>,
    #[serde(default)]
    sha256_full: Option<String>,
    #[serde(default)]
    error: Option<String>,
}

#[derive(Debug, Deserialize)]
struct BatchInputItem {
    path: Option<String>,
    local_path: Option<String>,
}

fn compute_file_hashes(filepath: &str) -> CaseRecord {
    let path_obj = Path::new(filepath);
    if !path_obj.is_file() {
        return CaseRecord {
            path: filepath.to_string(),
            exists: false,
            size: 0,
            md5_1mb: None,
            sha256_full: None,
            error: Some("not_found".to_string()),
        };
    }

    let file = match File::open(path_obj) {
        Ok(f) => f,
        Err(_) => {
            return CaseRecord {
                path: filepath.to_string(),
                exists: false,
                size: 0,
                md5_1mb: None,
                sha256_full: None,
                error: Some("open_failed".to_string()),
            }
        }
    };

    let metadata = match file.metadata() {
        Ok(m) => m,
        Err(_) => {
            return CaseRecord {
                path: filepath.to_string(),
                exists: false,
                size: 0,
                md5_1mb: None,
                sha256_full: None,
                error: Some("metadata_failed".to_string()),
            }
        }
    };
    let file_size = metadata.len();

    // 1. MD5 of first 1MB (1,048,576 bytes)
    let mut reader = BufReader::new(match File::open(path_obj) {
        Ok(f) => f,
        Err(_) => {
            return CaseRecord {
                path: filepath.to_string(),
                exists: false,
                size: 0,
                md5_1mb: None,
                sha256_full: None,
                error: Some("open_failed".to_string()),
            }
        }
    });

    let mut md5_chunk = vec![0u8; 1_048_576];
    let bytes_read = match reader.read(&mut md5_chunk) {
        Ok(n) => n,
        Err(_) => 0,
    };
    let mut md5_hasher = Md5::new();
    md5_hasher.update(&md5_chunk[..bytes_read]);
    let md5_hex = hex::encode(md5_hasher.finalize());

    // 2. Full SHA256 (64KB buffer streaming)
    let mut sha_reader = BufReader::new(match File::open(path_obj) {
        Ok(f) => f,
        Err(_) => {
            return CaseRecord {
                path: filepath.to_string(),
                exists: false,
                size: 0,
                md5_1mb: None,
                sha256_full: None,
                error: Some("open_failed".to_string()),
            }
        }
    });

    let mut sha_hasher = Sha256::new();
    let mut buffer = [0u8; 65536];
    loop {
        match sha_reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => sha_hasher.update(&buffer[..n]),
            Err(_) => break,
        }
    }
    let sha256_hex = hex::encode(sha_hasher.finalize());

    CaseRecord {
        path: filepath.to_string(),
        exists: true,
        size: file_size,
        md5_1mb: Some(md5_hex),
        sha256_full: Some(sha256_hex),
        error: None,
    }
}

fn run_verify(jsonl_path: &str) {
    let file = match File::open(jsonl_path) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("Failed to open cases file {}: {}", jsonl_path, e);
            exit(1);
        }
    };

    let reader = BufReader::new(file);
    let mut passed = 0;
    let mut total = 0;

    for (idx, line_res) in reader.lines().enumerate() {
        let line = match line_res {
            Ok(l) => l,
            Err(e) => {
                eprintln!("Error reading line {}: {}", idx + 1, e);
                exit(1);
            }
        };

        if line.trim().is_empty() {
            continue;
        }

        total += 1;
        let expected: CaseRecord = match serde_json::from_str(&line) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("JSON parse error on line {}: {}", idx + 1, e);
                exit(1);
            }
        };

        let actual = compute_file_hashes(&expected.path);

        if expected.exists != actual.exists {
            eprintln!(
                "DIFF on case #{}: exists mismatch (expected {}, got {})",
                idx + 1,
                expected.exists,
                actual.exists
            );
            exit(1);
        }

        if expected.exists {
            if expected.size != actual.size {
                eprintln!(
                    "DIFF on case #{}: size mismatch (expected {}, got {})",
                    idx + 1,
                    expected.size,
                    actual.size
                );
                exit(1);
            }

            if expected.md5_1mb != actual.md5_1mb {
                eprintln!(
                    "DIFF on case #{}: md5_1mb mismatch!\n  Expected: {:?}\n  Actual:   {:?}",
                    idx + 1,
                    expected.md5_1mb,
                    actual.md5_1mb
                );
                exit(1);
            }

            if expected.sha256_full != actual.sha256_full {
                eprintln!(
                    "DIFF on case #{}: sha256_full mismatch!\n  Expected: {:?}\n  Actual:   {:?}",
                    idx + 1,
                    expected.sha256_full,
                    actual.sha256_full
                );
                exit(1);
            }
        }

        passed += 1;
        println!(
            "PASS case #{}: {} [size: {}B]",
            idx + 1,
            expected.path,
            actual.size
        );
    }

    println!(
        "\nVERIFICATION GATE SUCCESS: {}/{} cases verified byte-for-byte against Python ground-truth!",
        passed, total
    );
}

fn run_batch_stdin() {
    let mut input_str = String::new();
    if std::io::stdin().read_to_string(&mut input_str).is_err() {
        eprintln!("Failed to read stdin");
        exit(1);
    }

    let items: Vec<BatchInputItem> = match serde_json::from_str(&input_str) {
        Ok(it) => it,
        Err(_) => {
            eprintln!("Invalid JSON array");
            exit(1);
        }
    };

    let mut result = std::collections::HashMap::new();

    for item in items {
        if let (Some(url_path), Some(local_path)) = (item.path, item.local_path) {
            let record = compute_file_hashes(&local_path);
            if record.exists {
                if let Some(md5) = record.md5_1mb {
                    result.insert(url_path, md5);
                }
            }
        }
    }

    let output = serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string());
    println!("{}", output);
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: nas_hasher <verify <cases.jsonl> | batch | file <path>>");
        exit(1);
    }

    match args[1].as_str() {
        "verify" => {
            if args.len() < 3 {
                eprintln!("Usage: nas_hasher verify <cases.jsonl>");
                exit(1);
            }
            run_verify(&args[2]);
        }
        "batch" => {
            run_batch_stdin();
        }
        "file" => {
            if args.len() < 3 {
                eprintln!("Usage: nas_hasher file <path>");
                exit(1);
            }
            let record = compute_file_hashes(&args[2]);
            println!("{}", serde_json::to_string(&record).unwrap());
        }
        _ => {
            eprintln!("Unknown command: {}", args[1]);
            exit(1);
        }
    }
}
