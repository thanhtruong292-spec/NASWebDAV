use md5::{Digest, Md5};
use rayon::prelude::*;
use serde::Serialize;
use sha2::Sha256;
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
#[cfg(unix)]
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, UNIX_EPOCH};
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

/// Băm nhanh Tầng 2: MD5 của 32KB đầu + 32KB cuối (tối đa 64KB, giảm 16x I/O HDD so với 1MB).
/// Tiền tố "ht64:" giúp phân biệt với cache 1MB cũ để tự động nâng cấp an toàn.
pub fn compute_fast_partial_path(path: &Path, size: u64) -> Result<String, std::io::Error> {
    let mut file = File::open(path)?;
    let mut hasher = Md5::new();
    const CHUNK: usize = 32768;
    let mut buf = [0u8; CHUNK];
    if size <= (CHUNK as u64) * 2 {
        loop {
            let n = file.read(&mut buf)?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
    } else {
        let mut rem = CHUNK;
        while rem > 0 {
            let n = file.read(&mut buf[..rem])?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
            rem -= n;
        }
        file.seek(SeekFrom::End(-(CHUNK as i64)))?;
        let mut rem2 = CHUNK;
        while rem2 > 0 {
            let n = file.read(&mut buf[..rem2])?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
            rem2 -= n;
        }
    }
    Ok(format!("ht64:{}", hex::encode(hasher.finalize())))
}

struct CachedHashEntry {
    size: u64,
    mtime: i64,
    partial: String,
    sha256: String,
}

fn load_hash_cache_tsv(path: &str) -> HashMap<String, CachedHashEntry> {
    let mut map = HashMap::new();
    if path.is_empty() {
        return map;
    }
    let file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return map,
    };
    let reader = BufReader::with_capacity(65536, file);
    for line in reader.lines().flatten() {
        let mut parts = line.splitn(5, '\t');
        let rel = match parts.next() {
            Some(s) if !s.is_empty() => s,
            _ => continue,
        };
        let sz = match parts.next().and_then(|s| s.parse::<u64>().ok()) {
            Some(v) => v,
            None => continue,
        };
        let mt = match parts.next().and_then(|s| s.parse::<i64>().ok()) {
            Some(v) => v,
            None => continue,
        };
        let partial = parts.next().unwrap_or("").to_string();
        let sha256 = parts.next().unwrap_or("").to_string();
        map.insert(
            rel.to_string(),
            CachedHashEntry {
                size: sz,
                mtime: mt,
                partial,
                sha256,
            },
        );
    }
    map
}

/// Băm MD5 1MB song song đa luồng (rayon) cho danh sách (url_path, local_path).
/// Giới hạn số luồng tối đa 1..=4 để bảo vệ HDD & RAM trên NAS RK3328.
pub fn batch_md5_1mb_parallel(
    items: &[(String, String)],
    workers: usize,
) -> HashMap<String, String> {
    let threads = workers.clamp(1, 4);
    let pool = match crate::shared_rayon_pool(threads) {
        Some(p) => p,
        None => {
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
    pub read_errors: usize,
    pub total_files_scanned: usize,
    pub size_candidates: usize,
    pub partial_candidates: usize,
    pub total_wasted_bytes: u64,
    pub cache_hits_partial: usize,
    pub cache_hits_sha256: usize,
}

fn is_skipped_dir(entry: &DirEntry) -> bool {
    if !entry.file_type().is_dir() {
        return false;
    }
    if let Some(name) = entry.file_name().to_str() {
        // Bỏ qua toàn bộ thư mục ẩn hệ thống (.thumbs, .thumbnails, .trash, .nas_meta, .backup_tmp, .cache, ...)
        name.starts_with('.')
            || matches!(
                name,
                "@eaDir" | "#recycle" | "lost+found" | "$RECYCLE.BIN" | "System Volume Information"
            )
    } else {
        false
    }
}

fn is_copy_like_name(rel: &str) -> bool {
    let lower = rel.rsplit('/').next().unwrap_or(rel).to_lowercase();
    lower.contains(" - copy")
        || lower.contains("_copy")
        || lower.contains(" (1)")
        || lower.contains(" (2)")
        || lower.contains(" (3)")
        || lower.starts_with("copy of ")
        || lower.contains("duplicate")
}

#[cfg(unix)]
fn file_dev_ino(meta: &std::fs::Metadata) -> (u64, u64) {
    (meta.dev(), meta.ino())
}

#[cfg(not(unix))]
fn file_dev_ino(_meta: &std::fs::Metadata) -> (u64, u64) {
    (0, 0)
}

// Linux ctime changes on writes and on timestamp restoration.
fn hash_version(path: &Path, expected_size: u64) -> Option<String> {
    let meta = std::fs::symlink_metadata(path).ok()?;
    if !meta.is_file() || meta.len() != expected_size {
        return None;
    }
    #[cfg(unix)]
    return Some(format!("v2:{}:{}:{}:{}", meta.dev(), meta.ino(), meta.ctime(), meta.ctime_nsec()));
    #[cfg(not(unix))]
    return Some(String::new()); // No persistent hash reuse without an inode version.
}

/// Quét tệp trùng lặp 3 tầng đa luồng (Size -> Partial 64KB đầu+cuối -> Full SHA-256).
/// - Không đi theo symlink (`follow_links(false)`) để đảm bảo an toàn ranh giới thư mục.
/// - Bỏ qua toàn bộ thư mục ẩn hệ thống (`.thumbs`, `.trash`, `.nas_meta`, `.backup_tmp`, ...).
pub fn find_duplicates_in_dir(
    root_dir: &str,
    min_size: u64,
    workers: usize,
    limit: usize,
) -> Result<DuplicateReport, String> {
    find_duplicates_in_dir_with_cache(root_dir, min_size, workers, limit, None, None, None)
}

/// Phiên bản có progress callback thời gian thực & hỗ trợ hủy tức thì (không dùng file cache).
pub fn find_duplicates_in_dir_with_progress(
    root_dir: &str,
    min_size: u64,
    workers: usize,
    limit: usize,
    progress_cb: Option<&(dyn Fn(i32, u64, u64, u64, &str) -> bool + Sync)>,
) -> Result<DuplicateReport, String> {
    find_duplicates_in_dir_with_cache(root_dir, min_size, workers, limit, None, None, progress_cb)
}

/// Phiên bản đầy đủ: hỗ trợ cache TSV (`cache_in`/`cache_out`) để tái sử dụng mã băm theo `(size, mtime)`,
/// băm nhanh 64KB đầu+cuối (giảm 16x I/O HDD), progress callback thời gian thực & hủy tức thì.
pub fn find_duplicates_in_dir_with_cache(
    root_dir: &str,
    min_size: u64,
    workers: usize,
    limit: usize,
    cache_in: Option<&str>,
    cache_out: Option<&str>,
    progress_cb: Option<&(dyn Fn(i32, u64, u64, u64, &str) -> bool + Sync)>,
) -> Result<DuplicateReport, String> {
    scan_duplicates(root_dir, min_size, workers, limit, cache_in, cache_out, progress_cb, None)
}

pub fn find_duplicates_from_catalogue(
    root_dir: &str,
    min_size: u64,
    workers: usize,
    limit: usize,
    cache_in: Option<&str>,
    cache_out: Option<&str>,
    progress_cb: Option<&(dyn Fn(i32, u64, u64, u64, &str) -> bool + Sync)>,
    manifest: &str,
) -> Result<DuplicateReport, String> {
    scan_duplicates(root_dir, min_size, workers, limit, cache_in, cache_out, progress_cb, Some(manifest))
}

fn catalogue_entry(root: &Path, line: &str) -> Result<(PathBuf, String), String> {
    let (relative, parent): (String, PathBuf) =
        serde_json::from_str(line).map_err(|err| err.to_string())?;
    if relative.contains('\\') || relative.split('/').any(|part| {
        part.is_empty() || part.starts_with('.') || part == "@eaDir" || part == "#recycle"
    }) || !Path::new(&relative).components().all(|part| matches!(part, std::path::Component::Normal(_))) {
        return Err("Invalid catalogue path".to_string());
    }
    #[cfg(windows)]
    let parent = parent.canonicalize().map_err(|err| err.to_string())?;
    let logical = root.join(&relative);
    let logical_parent = logical.parent().ok_or("Missing catalogue parent")?;
    if logical_parent.canonicalize().map_err(|err| err.to_string())? != parent {
        return Err("Catalogue parent changed".to_string());
    }
    let name = logical.file_name().ok_or("Missing catalogue file name")?;
    Ok((parent.join(name), relative))
}

fn scan_duplicates(
    root_dir: &str,
    min_size: u64,
    workers: usize,
    limit: usize,
    cache_in: Option<&str>,
    cache_out: Option<&str>,
    progress_cb: Option<&(dyn Fn(i32, u64, u64, u64, &str) -> bool + Sync)>,
    manifest: Option<&str>,
) -> Result<DuplicateReport, String> {
    let root = Path::new(root_dir);
    if !root.is_dir() {
        return Err(format!("Root directory does not exist: {}", root_dir));
    }

    let hash_cache = cache_in.map(load_hash_cache_tsv).unwrap_or_default();
    let cancelled = AtomicBool::new(false);
    let last_emit = Mutex::new(Instant::now() - Duration::from_secs(1));
    let emit_interval = Duration::from_millis(400);

    let emit_progress = |stage: i32, done: u64, total: u64, extra: u64, rel: &str, force: bool| {
        if let Some(cb) = progress_cb {
            if !force {
                if let Ok(mut guard) = last_emit.try_lock() {
                    if guard.elapsed() < emit_interval {
                        return;
                    }
                    *guard = Instant::now();
                } else {
                    return;
                }
            } else if let Ok(mut guard) = last_emit.lock() {
                *guard = Instant::now();
            }
            if cb(stage, done, total, extra, rel) {
                cancelled.store(true, Ordering::Relaxed);
            }
        }
    };

    emit_progress(1, 0, 0, 0, "", true);

    // Stage 1: Group by exact file size (0 file bytes read), skip hardlinks (same dev+ino)
    let mut by_size: HashMap<u64, Vec<(PathBuf, String, i64, u64)>> = HashMap::new();
    let mut seen_inodes: HashSet<(u64, u64)> = HashSet::new();
    let mut total_files_scanned: usize = 0;

    let entries: Box<dyn Iterator<Item = Result<(PathBuf, String), String>> + '_> = match manifest {
        Some(path) => {
            let reader = BufReader::new(File::open(path).map_err(|err| err.to_string())?);
            Box::new(reader.lines().map(|line| {
                catalogue_entry(root, &line.map_err(|err| err.to_string())?)
            }))
        }
        None => Box::new(WalkDir::new(root)
            .follow_links(false)
            .into_iter()
            .filter_entry(|entry| entry.depth() == 0 || !is_skipped_dir(entry))
            .filter_map(|entry| match entry {
                Ok(entry) if entry.file_type().is_file() => {
                    let path = entry.into_path();
                    let relative = path.strip_prefix(root).ok().and_then(Path::to_str)
                        .filter(|p| !(cfg!(unix) && p.contains('\\')))
                        .map(|p| p.replace(std::path::MAIN_SEPARATOR, "/"));
                    Some(relative.map(|p| (path, p)).ok_or_else(|| "Invalid file path".to_string()))
                }
                Ok(_) => None,
                Err(error) => Some(Err(error.to_string())),
            })),
    };

    let read_errors = AtomicUsize::new(0);
    for entry in entries {
        if cancelled.load(Ordering::Relaxed) {
            return Err("Cancelled".to_string());
        }
        total_files_scanned += 1;
        let (path_buf, rel) = match entry {
            Ok(entry) => entry,
            Err(_) => {
                read_errors.fetch_add(1, Ordering::Relaxed);
                continue;
            }
        };
        if total_files_scanned % 250 == 0 {
            emit_progress(1, total_files_scanned as u64, 0, 0, &rel, false);
        }
        if let Ok(meta) = std::fs::symlink_metadata(&path_buf) {
            if !meta.is_file() {
                read_errors.fetch_add(1, Ordering::Relaxed);
                continue;
            }
            let (dev, ino) = file_dev_ino(&meta);
            // Bo qua hardlink cung (dev, ino): khong chiem them dung luong
            if ino != 0 && !seen_inodes.insert((dev, ino)) {
                continue;
            }
            let sz = meta.len();
            if sz >= min_size {
                let mtime = meta
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_secs() as i64)
                    .unwrap_or(0);
                by_size.entry(sz).or_default().push((path_buf, rel, mtime, ino));
            }
        } else {
            read_errors.fetch_add(1, Ordering::Relaxed);
        }
    }

    if cancelled.load(Ordering::Relaxed) {
        return Err("Cancelled".to_string());
    }

    let mut size_candidate_files: Vec<(u64, PathBuf, String, i64, u64)> = by_size
        .into_iter()
        .filter(|(_, paths)| paths.len() >= 2)
        .flat_map(|(sz, paths)| paths.into_iter().map(move |(p, rel, mt, ino)| (sz, p, rel, mt, ino)))
        .collect();
    // Sap xep theo inode (chuan fclones/rmlint) de giam nhay dau tu HDD co khi doc Tang 2
    size_candidate_files.sort_unstable_by_key(|x| x.4);
    let size_candidates = size_candidate_files.len();

    emit_progress(
        1,
        total_files_scanned as u64,
        total_files_scanned as u64,
        size_candidates as u64,
        "",
        true,
    );
    if cancelled.load(Ordering::Relaxed) {
        return Err("Cancelled".to_string());
    }

    let threads = workers.clamp(1, 4);
    let pool = crate::shared_rayon_pool(threads)
        .ok_or_else(|| "Failed to initialize shared rayon pool".to_string())?;

    // Stage 2: Fast Partial MD5 (32KB head + 32KB tail = 64KB) with cache reuse + streaming flush
    emit_progress(2, 0, size_candidates as u64, total_files_scanned as u64, "", true);
    let md5_done = AtomicUsize::new(0);
    let cache_hits_partial = AtomicUsize::new(0);
    let cache_writer: Mutex<Option<(BufWriter<File>, usize)>> = Mutex::new(
        cache_out
            .filter(|p| !p.is_empty())
            .and_then(|p| File::create(p).ok())
            .map(|f| (BufWriter::with_capacity(65536, f), 0usize)),
    );

    let append_cache_row = |rel: &str, sz: u64, mt: i64, part: &str, sha: &str, flush_every: usize| {
        if let Ok(mut guard) = cache_writer.lock() {
            if let Some((ref mut w, ref mut pending)) = *guard {
                let clean_rel = rel.replace('\t', " ").replace('\n', "");
                let _ = writeln!(w, "{}\t{}\t{}\t{}\t{}", clean_rel, sz, mt, part, sha);
                *pending += 1;
                if *pending >= flush_every {
                    let _ = w.flush();
                    *pending = 0;
                }
            }
        }
    };

    let partial_hashed: Vec<((u64, String), PathBuf, String, i64, String, u64)> = pool.install(|| {
        size_candidate_files
            .into_par_iter()
            .filter_map(|(sz, path, rel, mt, ino)| {
                if cancelled.load(Ordering::Relaxed) {
                    return None;
                }
                let Some(version) = hash_version(&path, sz) else {
                    read_errors.fetch_add(1, Ordering::Relaxed);
                    return None;
                };
                let cached_partial = hash_cache.get(&rel).and_then(|c| {
                    let mut fields = c.partial.split('|');
                    let stamp = fields.next()?;
                    let digest = fields.next()?;
                    (c.size == sz && c.mtime == mt && !version.is_empty() &&
                        stamp == version && digest.starts_with("ht64:")).then(|| digest.to_owned())
                });
                let partial = if let Some(p) = cached_partial {
                    cache_hits_partial.fetch_add(1, Ordering::Relaxed);
                    Some(p)
                } else {
                    match compute_fast_partial_path(&path, sz) {
                        Ok(p) if hash_version(&path, sz).as_ref() == Some(&version) => {
                            append_cache_row(&rel, sz, mt, &format!("{}|{}", version, p), "", 200);
                            Some(p)
                        }
                        _ => {
                            read_errors.fetch_add(1, Ordering::Relaxed);
                            None
                        }
                    }
                };
                let done = md5_done.fetch_add(1, Ordering::Relaxed) + 1;
                emit_progress(2, done as u64, size_candidates as u64, total_files_scanned as u64, &rel, false);
                partial.map(|p| {
                    let tagged = format!("{}|{}", version, p);
                    ((sz, p), path, rel, mt, tagged, ino)
                })
            })
            .collect()
    });

    if let Ok(mut guard) = cache_writer.lock() {
        if let Some((ref mut w, ref mut pending)) = *guard {
            let _ = w.flush();
            *pending = 0;
        }
    }

    if cancelled.load(Ordering::Relaxed) {
        return Err("Cancelled".to_string());
    }

    let mut by_partial: HashMap<(u64, String), Vec<(PathBuf, String, i64, String, u64)>> = HashMap::new();
    for (key, path, rel, mt, p, ino) in partial_hashed {
        by_partial.entry(key).or_default().push((path, rel, mt, p, ino));
    }

    let mut partial_candidate_files: Vec<(u64, PathBuf, String, i64, String, u64)> = by_partial
        .into_iter()
        .filter(|(_, paths)| paths.len() >= 2)
        .flat_map(|((sz, _), paths)| paths.into_iter().map(move |(p, rel, mt, part, ino)| (sz, p, rel, mt, part, ino)))
        .collect();
    partial_candidate_files.sort_unstable_by_key(|x| x.5);
    let partial_candidates = partial_candidate_files.len();

    emit_progress(
        2,
        size_candidates as u64,
        size_candidates as u64,
        partial_candidates as u64,
        "",
        true,
    );
    if cancelled.load(Ordering::Relaxed) {
        return Err("Cancelled".to_string());
    }

    // Stage 3: Full SHA-256 verification in parallel with cache reuse + streaming flush
    emit_progress(3, 0, partial_candidates as u64, total_files_scanned as u64, "", true);
    let sha_done = AtomicUsize::new(0);
    let cache_hits_sha256 = AtomicUsize::new(0);
    let full_hashed: Vec<(String, u64, String, i64)> = pool.install(|| {
        partial_candidate_files
            .into_par_iter()
            .filter_map(|(sz, path, rel, mt, part, _ino)| {
                if cancelled.load(Ordering::Relaxed) {
                    return None;
                }
                let version = part.split('|').next()?;
                if hash_version(&path, sz).as_deref() != Some(version) {
                    read_errors.fetch_add(1, Ordering::Relaxed);
                    return None;
                }
                let cached_sha = hash_cache.get(&rel).and_then(|c| {
                    if !version.is_empty() && c.size == sz && c.mtime == mt && c.sha256.len() == 64 &&
                        c.partial == format!("{}|{}", part, c.sha256) {
                        Some(c.sha256.clone())
                    } else {
                        None
                    }
                });
                let sha = if let Some(s) = cached_sha {
                    cache_hits_sha256.fetch_add(1, Ordering::Relaxed);
                    Some(s)
                } else {
                    match compute_sha256_path(&path) {
                        Ok(s) if hash_version(&path, sz).as_deref() == Some(version) => {
                            append_cache_row(&rel, sz, mt, &format!("{}|{}", part, s), &s, 50);
                            Some(s)
                        }
                        _ => {
                            read_errors.fetch_add(1, Ordering::Relaxed);
                            None
                        },
                    }
                };
                let done = sha_done.fetch_add(1, Ordering::Relaxed) + 1;
                emit_progress(3, done as u64, partial_candidates as u64, total_files_scanned as u64, &rel, false);
                sha.map(|s| (s, sz, rel, mt))
            })
            .collect()
    });

    if let Ok(mut guard) = cache_writer.lock() {
        if let Some((ref mut w, ref mut pending)) = *guard {
            let _ = w.flush();
            *pending = 0;
        }
    }

    if cancelled.load(Ordering::Relaxed) {
        return Err("Cancelled".to_string());
    }

    let mut by_sha: HashMap<String, (u64, Vec<(String, i64)>)> = HashMap::new();
    for (sha, sz, rel, mt) in full_hashed {
        let entry = by_sha.entry(sha).or_insert_with(|| (sz, Vec::new()));
        entry.1.push((rel, mt));
    }

    let mut groups: Vec<DuplicateGroup> = by_sha
        .into_iter()
        .filter_map(|(sha, (sz, mut files_with_mt))| {
            if files_with_mt.len() < 2 {
                return None;
            }
            // Sap xep chuan giu goc: uu tien ten khong phai ban sao -> mtime cu nhat -> duong dan ngan nhat -> alphabet
            files_with_mt.sort_by(|a, b| {
                is_copy_like_name(&a.0)
                    .cmp(&is_copy_like_name(&b.0))
                    .then_with(|| a.1.cmp(&b.1))
                    .then_with(|| a.0.len().cmp(&b.0.len()))
                    .then_with(|| a.0.cmp(&b.0))
            });
            let files: Vec<String> = files_with_mt.into_iter().map(|(p, _)| p).collect();
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

    emit_progress(3, partial_candidates as u64, partial_candidates as u64, total_files_scanned as u64, "", true);

    Ok(DuplicateReport {
        groups,
        total_groups,
        total_files_scanned,
        read_errors: read_errors.load(Ordering::Relaxed),
        size_candidates,
        partial_candidates,
        total_wasted_bytes,
        cache_hits_partial: cache_hits_partial.load(Ordering::Relaxed),
        cache_hits_sha256: cache_hits_sha256.load(Ordering::Relaxed),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;

    #[test]
    fn missing_candidate_marks_scan_incomplete() {
        let root = std::env::temp_dir().join(format!("nas_dup_read_error_{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("a"), b"same").unwrap();
        fs::write(root.join("b"), b"same").unwrap();
        let interrupt = |stage, done, _total, _extra, _path: &str| {
            if stage == 2 && done == 0 {
                let _ = fs::remove_file(root.join("b"));
            }
            false
        };
        let report = find_duplicates_in_dir_with_cache(root.to_str().unwrap(), 0, 1, 0,
            None, None, Some(&interrupt)).unwrap();
        assert_eq!(report.read_errors, 1);
        assert_eq!(report.total_groups, 0);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn test_3_stage_duplicate_finder_accuracy() {
        let tmp = std::env::temp_dir().join(format!("nas_dup_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(tmp.join("sub")).unwrap();
        fs::create_dir_all(tmp.join(".trash")).unwrap();
        fs::create_dir_all(tmp.join(".thumbs")).unwrap();
        fs::create_dir_all(tmp.join(".backup_tmp")).unwrap();

        let payload_a = vec![b'A'; 4096];
        let payload_b = vec![b'B'; 4096]; // Same size as A, different content
        let payload_c = vec![b'C'; 2048]; // Unique size

        fs::File::create(tmp.join("a1.bin")).unwrap().write_all(&payload_a).unwrap();
        fs::File::create(tmp.join("sub/a2.bin")).unwrap().write_all(&payload_a).unwrap();
        fs::File::create(tmp.join("b1.bin")).unwrap().write_all(&payload_b).unwrap();
        fs::File::create(tmp.join("c1.bin")).unwrap().write_all(&payload_c).unwrap();
        // Files in .trash, .thumbs, .backup_tmp must be ignored
        fs::File::create(tmp.join(".trash/a3.bin")).unwrap().write_all(&payload_a).unwrap();
        fs::File::create(tmp.join(".thumbs/a4.jpg")).unwrap().write_all(&payload_a).unwrap();
        fs::File::create(tmp.join(".backup_tmp/a5.bin")).unwrap().write_all(&payload_a).unwrap();

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
