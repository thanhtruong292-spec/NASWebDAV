use md5::{Digest, Md5};
use rayon::prelude::*;
use serde::Serialize;
use sha2::Sha256;
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};
use walkdir::{DirEntry, WalkDir};

pub fn compute_sha256_path(path: &Path) -> Result<String, std::io::Error> {
    let file = File::open(path)?;
    let mut reader = BufReader::with_capacity(65536, file);
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 65536];
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

pub fn compute_md5_1mb_path(path: &Path) -> Result<String, std::io::Error> {
    let file = File::open(path)?;
    let mut reader = BufReader::with_capacity(65536, file).take(1_048_576);
    let mut hasher = Md5::new();
    let mut buffer = [0u8; 65536];
    loop {
        let n = reader.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// Băm MD5 1MB song song đa luồng (rayon) cho danh sách (url_path, local_path).
/// Giới hạn số luồng tối đa 1..=4 để bảo vệ HDD & RAM trên NAS RK3328.
pub fn batch_md5_1mb_parallel(
    items: &[(String, String)],
    workers: usize,
) -> HashMap<String, String> {
    let threads = workers.clamp(1, 4);
    let pool = match rayon::ThreadPoolBuilder::new().num_threads(threads).build() {
        Ok(p) => p,
        Err(_) => {
            let mut map = HashMap::with_capacity(items.len());
            for (url, local) in items {
                if let Ok(h) = compute_md5_1mb_path(Path::new(local)) {
                    map.insert(url.clone(), h);
                }
            }
            return map;
        }
    };

    pool.install(|| {
        items
            .par_iter()
            .filter_map(|(url, local)| {
                let p = Path::new(local);
                if !p.is_file() {
                    return None;
                }
                compute_md5_1mb_path(p).ok().map(|h| (url.clone(), h))
            })
            .collect()
    })
}

#[derive(Debug, Serialize, Clone)]
pub struct DuplicateGroup {
    pub sha256: String,
    pub size: u64,
    pub count: usize,
    pub wasted_bytes: u64,
    pub files: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct DuplicateReport {
    pub groups: Vec<DuplicateGroup>,
    pub total_groups: usize,
    pub total_files_scanned: usize,
    pub size_candidates: usize,
    pub partial_candidates: usize,
    pub total_wasted_bytes: u64,
}

fn is_skipped_dir(entry: &DirEntry) -> bool {
    if !entry.file_type().is_dir() {
        return false;
    }
    if let Some(name) = entry.file_name().to_str() {
        matches!(name, ".trash" | ".nas_meta" | "@eaDir" | "#recycle" | "lost+found")
    } else {
        false
    }
}

/// Quét tệp trùng lặp 3 tầng đa luồng (Size -> Partial MD5 1MB -> Full SHA-256).
/// - Không đi theo symlink (`follow_links(false)`) để đảm bảo an toàn ranh giới thư mục.
/// - Bỏ qua thư mục hệ thống `.trash`, `.nas_meta`, `@eaDir`, `lost+found`.
pub fn find_duplicates_in_dir(
    root_dir: &str,
    min_size: u64,
    workers: usize,
    limit: usize,
) -> Result<DuplicateReport, String> {
    let root = Path::new(root_dir);
    if !root.is_dir() {
        return Err(format!("Root directory does not exist: {}", root_dir));
    }

    // Stage 1: Group by exact file size (0 file bytes read)
    let mut by_size: HashMap<u64, Vec<PathBuf>> = HashMap::new();
    let mut total_files_scanned: usize = 0;

    let walker = WalkDir::new(root)
        .follow_links(false)
        .into_iter()
        .filter_entry(|e| e.depth() == 0 || !is_skipped_dir(e));

    for entry in walker.flatten() {
        if !entry.file_type().is_file() {
            continue;
        }
        total_files_scanned += 1;
        if let Ok(meta) = entry.metadata() {
            let sz = meta.len();
            if sz >= min_size {
                by_size.entry(sz).or_default().push(entry.into_path());
            }
        }
    }

    let size_candidate_files: Vec<(u64, PathBuf)> = by_size
        .into_iter()
        .filter(|(_, paths)| paths.len() >= 2)
        .flat_map(|(sz, paths)| paths.into_iter().map(move |p| (sz, p)))
        .collect();
    let size_candidates = size_candidate_files.len();

    let threads = workers.clamp(1, 4);
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .build()
        .map_err(|e| format!("Failed to build rayon pool: {}", e))?;

    // Stage 2: Partial MD5 (first 1MB) in parallel
    let partial_hashed: Vec<((u64, String), PathBuf)> = pool.install(|| {
        size_candidate_files
            .into_par_iter()
            .filter_map(|(sz, path)| {
                compute_md5_1mb_path(&path)
                    .ok()
                    .map(|md5| ((sz, md5), path))
            })
            .collect()
    });

    let mut by_partial: HashMap<(u64, String), Vec<PathBuf>> = HashMap::new();
    for (key, path) in partial_hashed {
        by_partial.entry(key).or_default().push(path);
    }

    let partial_candidate_files: Vec<(u64, PathBuf)> = by_partial
        .into_iter()
        .filter(|(_, paths)| paths.len() >= 2)
        .flat_map(|((sz, _), paths)| paths.into_iter().map(move |p| (sz, p)))
        .collect();
    let partial_candidates = partial_candidate_files.len();

    // Stage 3: Full SHA-256 verification in parallel
    let full_hashed: Vec<(String, u64, String)> = pool.install(|| {
        partial_candidate_files
            .into_par_iter()
            .filter_map(|(sz, path)| {
                let sha = compute_sha256_path(&path).ok()?;
                let rel = path
                    .strip_prefix(root)
                    .unwrap_or(&path)
                    .to_string_lossy()
                    .replace('\\', "/");
                Some((sha, sz, rel))
            })
            .collect()
    });

    let mut by_sha: HashMap<String, (u64, Vec<String>)> = HashMap::new();
    for (sha, sz, rel) in full_hashed {
        let entry = by_sha.entry(sha).or_insert_with(|| (sz, Vec::new()));
        entry.1.push(rel);
    }

    let mut groups: Vec<DuplicateGroup> = by_sha
        .into_iter()
        .filter_map(|(sha, (sz, mut files))| {
            if files.len() < 2 {
                return None;
            }
            files.sort();
            let count = files.len();
            let wasted_bytes = sz.saturating_mul((count - 1) as u64);
            Some(DuplicateGroup {
                sha256: sha,
                size: sz,
                count,
                wasted_bytes,
                files,
            })
        })
        .collect();

    groups.sort_by(|a, b| {
        b.wasted_bytes
            .cmp(&a.wasted_bytes)
            .then_with(|| a.sha256.cmp(&b.sha256))
    });

    let total_groups = groups.len();
    let total_wasted_bytes: u64 = groups.iter().map(|g| g.wasted_bytes).sum();
    if limit > 0 && groups.len() > limit {
        groups.truncate(limit);
    }

    Ok(DuplicateReport {
        groups,
        total_groups,
        total_files_scanned,
        size_candidates,
        partial_candidates,
        total_wasted_bytes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;

    #[test]
    fn test_3_stage_duplicate_finder_accuracy() {
        let tmp = std::env::temp_dir().join(format!("nas_dup_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(tmp.join("sub")).unwrap();
        fs::create_dir_all(tmp.join(".trash")).unwrap();

        let payload_a = vec![b'A'; 4096];
        let payload_b = vec![b'B'; 4096]; // Same size as A, different content
        let payload_c = vec![b'C'; 2048]; // Unique size

        fs::File::create(tmp.join("a1.bin")).unwrap().write_all(&payload_a).unwrap();
        fs::File::create(tmp.join("sub/a2.bin")).unwrap().write_all(&payload_a).unwrap();
        fs::File::create(tmp.join("b1.bin")).unwrap().write_all(&payload_b).unwrap();
        fs::File::create(tmp.join("c1.bin")).unwrap().write_all(&payload_c).unwrap();
        // File in .trash must be ignored
        fs::File::create(tmp.join(".trash/a3.bin")).unwrap().write_all(&payload_a).unwrap();

        let report = find_duplicates_in_dir(tmp.to_str().unwrap(), 1024, 2, 500).unwrap();
        assert_eq!(report.total_files_scanned, 4);
        assert_eq!(report.size_candidates, 3); // a1, a2, b1
        assert_eq!(report.partial_candidates, 2); // a1, a2
        assert_eq!(report.total_groups, 1);
        assert_eq!(report.total_wasted_bytes, 4096);
        assert_eq!(report.groups[0].files, vec!["a1.bin", "sub/a2.bin"]);

        let _ = fs::remove_dir_all(&tmp);
    }
}
