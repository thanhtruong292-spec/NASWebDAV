use crate::mp4_parser::parse_mp4_header;
use rayon::prelude::*;
use serde::Serialize;
use std::path::Path;
use std::time::Instant;
use walkdir::{DirEntry, WalkDir};

#[derive(Debug, Serialize, Default)]
pub struct DirUsageResult {
    pub total: u64,
    pub count: u64,
    pub partial: bool,
}

#[derive(Debug, Serialize, Default)]
pub struct BrokenMp4ScanResult {
    pub total_scanned: u64,
    pub suspect_files: Vec<String>,
}

#[inline]
fn is_ignored_usage_dir(entry: &DirEntry) -> bool {
    if !entry.file_type().is_dir() {
        return false;
    }
    match entry.file_name().to_str() {
        Some(".nas_meta") | Some(".thumbnails") | Some("@eaDir") => true,
        _ => false,
    }
}

/// Tính nhanh dung lượng thư mục bằng Rust `walkdir` (zero-GIL, dừng sớm theo `max_files` & `max_seconds`).
/// Giữ parity 100% với `_safe_dir_usage` trong `nas_api_server.py`.
pub fn compute_dir_usage(path: &Path, max_files: u64, max_seconds: f64) -> DirUsageResult {
    if !path.exists() {
        return DirUsageResult::default();
    }
    let start = Instant::now();
    let mut total: u64 = 0;
    let mut count: u64 = 0;
    let mut partial = false;

    let iter = WalkDir::new(path)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| e.depth() == 0 || !is_ignored_usage_dir(e));

    for entry_res in iter {
        let entry = match entry_res {
            Ok(e) => e,
            Err(_) => {
                partial = true;
                continue;
            }
        };
        if !entry.file_type().is_file() {
            continue;
        }
        if count >= max_files
            || (count & 63 == 0 && start.elapsed().as_secs_f64() > max_seconds)
        {
            return DirUsageResult {
                total,
                count,
                partial: true,
            };
        }
        if let Ok(meta) = entry.metadata() {
            total = total.saturating_add(meta.len());
            count += 1;
        }
    }

    DirUsageResult {
        total,
        count,
        partial,
    }
}

/// Quét thư mục song song bằng Rust `walkdir` + `rayon` + `parse_mp4_header` để lọc ra
/// danh sách file `.mp4` nghi ngờ hỏng (thiếu `moov` atom hoặc header lỗi).
/// Bỏ qua mọi thư mục chứa `_broken_quarantine`.
pub fn scan_broken_mp4_candidates(scan_dir: &Path, workers: usize) -> BrokenMp4ScanResult {
    if !scan_dir.is_dir() {
        return BrokenMp4ScanResult::default();
    }

    let mut mp4_paths: Vec<String> = Vec::new();
    let iter = WalkDir::new(scan_dir)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| {
            if e.depth() == 0 {
                return true;
            }
            if e.file_type().is_dir() {
                if let Some(name) = e.file_name().to_str() {
                    if name.contains("_broken_quarantine") {
                        return false;
                    }
                }
            }
            true
        });

    for entry in iter.filter_map(|e| e.ok()) {
        if !entry.file_type().is_file() {
            continue;
        }
        if let Some(ext) = entry.path().extension().and_then(|s| s.to_str()) {
            if ext.eq_ignore_ascii_case("mp4") {
                if let Some(s) = entry.path().to_str() {
                    if !s.contains("_broken_quarantine") {
                        mp4_paths.push(s.to_string());
                    }
                }
            }
        }
    }

    let total_scanned = mp4_paths.len() as u64;
    if total_scanned == 0 {
        return BrokenMp4ScanResult::default();
    }

    let n_threads = workers.clamp(1, 4);
    let check_all = || {
        mp4_paths
            .par_iter()
            .filter_map(|p| {
                match parse_mp4_header(Path::new(p)) {
                    Ok(info) if info.valid && info.has_moov => None,
                    _ => Some(p.clone()),
                }
            })
            .collect::<Vec<String>>()
    };

    let suspect_files = match rayon::ThreadPoolBuilder::new().num_threads(n_threads).build() {
        Ok(pool) => pool.install(check_all),
        Err(_) => check_all(),
    };

    BrokenMp4ScanResult {
        total_scanned,
        suspect_files,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_dir_usage_and_broken_mp4_scan() {
        let tmp = std::env::temp_dir().join(format!("fs_scan_p4_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(tmp.join(".thumbnails")).unwrap();
        fs::create_dir_all(tmp.join("_broken_quarantine_20261120")).unwrap();
        fs::create_dir_all(tmp.join("sub")).unwrap();

        fs::write(tmp.join("a.txt"), vec![0u8; 100]).unwrap();
        fs::write(tmp.join("sub").join("bad.mp4"), b"not a valid mp4").unwrap();
        // Bị bỏ qua bởi filter
        fs::write(tmp.join(".thumbnails").join("skip.jpg"), vec![0u8; 999]).unwrap();
        fs::write(tmp.join("_broken_quarantine_20261120").join("old.mp4"), b"bad").unwrap();

        let usage = compute_dir_usage(&tmp, 1000, 5.0);
        assert!(!usage.partial);
        assert_eq!(usage.count, 3); // a.txt + bad.mp4 + old.mp4 (khong tinh .thumbnails)
        assert_eq!(usage.total, 100 + 15 + 3);

        let broken = scan_broken_mp4_candidates(&tmp, 2);
        assert_eq!(broken.total_scanned, 1);
        assert_eq!(broken.suspect_files.len(), 1);
        assert!(broken.suspect_files[0].ends_with("bad.mp4"));

        let _ = fs::remove_dir_all(&tmp);
    }
}
