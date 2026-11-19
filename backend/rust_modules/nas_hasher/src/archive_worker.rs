use serde::Serialize;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::Path;
use walkdir::WalkDir;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

#[derive(Debug, Serialize, Default, PartialEq)]
pub struct ZipExtractResult {
    pub files_extracted: u64,
    pub bytes_extracted: u64,
}

#[derive(Debug, Serialize, Default, PartialEq)]
pub struct ZipCreateResult {
    pub files_compressed: u64,
    pub total_bytes: u64,
    pub zip_size: u64,
}

/// Giải nén ZIP an toàn tuyệt đối chống Zip-Slip và Symlink attack (chuẩn C-ABI).
pub fn extract_zip_safe(archive_path: &Path, dest_dir: &Path) -> Result<ZipExtractResult, String> {
    if !archive_path.is_file() {
        return Err("Archive file not found".into());
    }
    if !dest_dir.is_dir() {
        fs::create_dir_all(dest_dir).map_err(|e| format!("Cannot create dest_dir: {}", e))?;
    }
    let dest_real = fs::canonicalize(dest_dir).map_err(|e| format!("Canonicalize dest_dir failed: {}", e))?;

    let file = File::open(archive_path).map_err(|e| format!("Cannot open archive: {}", e))?;
    let mut reader = BufReader::new(file);
    let mut zip = ZipArchive::new(&mut reader).map_err(|e| format!("Invalid zip archive: {}", e))?;

    let mut files_count = 0u64;
    let mut bytes_count = 0u64;
    let mut buf = [0u8; 65536]; // 64KB buffer

    for i in 0..zip.len() {
        let mut file = zip.by_index(i).map_err(|e| format!("Read zip entry error: {}", e))?;
        
        // 1. Chống Zip-Slip bằng enclosed_name() của crate zip
        let enclosed = match file.enclosed_name() {
            Some(p) => p.to_path_buf(),
            None => return Err(format!("Unsafe zip entry path: {}", file.name())),
        };

        // 2. Chống Symlink
        if let Some(mode) = file.unix_mode() {
            if mode & 0o170000 == 0o120000 {
                return Err(format!("Unsafe symlink in archive: {}", file.name()));
            }
        }

        let out_path = dest_real.join(&enclosed);

        // 3. Đảm bảo path con nằm hoàn toàn trong thư mục đích
        if !out_path.starts_with(&dest_real) {
            return Err(format!("Unsafe path traversal: {}", file.name()));
        }

        if file.is_dir() {
            fs::create_dir_all(&out_path).map_err(|e| format!("Cannot create dir {:?}: {}", out_path, e))?;
        } else {
            if let Some(parent) = out_path.parent() {
                if !parent.is_dir() {
                    fs::create_dir_all(parent).map_err(|e| format!("Cannot create parent dir: {}", e))?;
                }
            }
            let mut out_file = File::create(&out_path).map_err(|e| format!("Cannot create file {:?}: {}", out_path, e))?;
            loop {
                let n = file.read(&mut buf).map_err(|e| format!("Read zip stream error: {}", e))?;
                if n == 0 {
                    break;
                }
                out_file.write_all(&buf[..n]).map_err(|e| format!("Write file error: {}", e))?;
                bytes_count += n as u64;
            }
            files_count += 1;
        }
    }

    Ok(ZipExtractResult {
        files_extracted: files_count,
        bytes_extracted: bytes_count,
    })
}

/// Nén một thư mục thành file ZIP bằng thuật toán Deflate tối ưu tốc độ và dung lượng.
pub fn create_zip_from_dir(src_dir: &Path, dst_zip: &Path) -> Result<ZipCreateResult, String> {
    if !src_dir.is_dir() {
        return Err("Source directory not found".into());
    }
    if let Some(parent) = dst_zip.parent() {
        if !parent.is_dir() {
            fs::create_dir_all(parent).map_err(|e| format!("Cannot create zip parent dir: {}", e))?;
        }
    }

    let out_file = File::create(dst_zip).map_err(|e| format!("Cannot create zip file: {}", e))?;
    let mut zip_writer = ZipWriter::new(BufWriter::new(out_file));
    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated);

    let src_canonical = fs::canonicalize(src_dir).map_err(|e| format!("Canonicalize failed: {}", e))?;
    let parent_dir = src_canonical.parent().unwrap_or(&src_canonical);
    let mut files_count = 0u64;
    let mut total_bytes = 0u64;
    let mut buf = [0u8; 65536];

    for entry in WalkDir::new(&src_canonical).into_iter().filter_map(|e| e.ok()) {
        let path = entry.path();
        if path == dst_zip {
            continue; // Không tự nén chính file zip đang tạo
        }
        let rel_path = match path.strip_prefix(parent_dir) {
            Ok(p) => p,
            Err(_) => continue,
        };
        let rel_str = rel_path.to_string_lossy().replace('\\', "/");
        if rel_str.is_empty() {
            continue;
        }

        if path.is_dir() {
            let dir_name = if rel_str.ends_with('/') { rel_str } else { format!("{}/", rel_str) };
            zip_writer.add_directory(&dir_name, options).map_err(|e| format!("Zip add dir error: {}", e))?;
        } else if path.is_file() {
            zip_writer.start_file(&rel_str, options).map_err(|e| format!("Zip start file error: {}", e))?;
            let mut f = File::open(path).map_err(|e| format!("Cannot open file {:?}: {}", path, e))?;
            loop {
                let n = f.read(&mut buf).map_err(|e| format!("Read file error: {}", e))?;
                if n == 0 {
                    break;
                }
                zip_writer.write_all(&buf[..n]).map_err(|e| format!("Write zip error: {}", e))?;
                total_bytes += n as u64;
            }
            files_count += 1;
        }
    }

    zip_writer.finish().map_err(|e| format!("Finish zip error: {}", e))?;
    let zip_size = fs::metadata(dst_zip).map(|m| m.len()).unwrap_or(0);

    Ok(ZipCreateResult {
        files_compressed: files_count,
        total_bytes,
        zip_size,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_zip_roundtrip_and_safety() {
        let tmp_root = std::env::temp_dir().join(format!("rust_zip_test_{}", std::process::id()));
        let src_dir = tmp_root.join("src");
        let dest_dir = tmp_root.join("dest");
        let zip_path = tmp_root.join("archive.zip");

        fs::create_dir_all(&src_dir).unwrap();
        fs::write(src_dir.join("hello.txt"), b"Hello World from Rust!").unwrap();
        let sub_dir = src_dir.join("sub");
        fs::create_dir_all(&sub_dir).unwrap();
        fs::write(sub_dir.join("nested.txt"), b"Nested content data").unwrap();

        // 1. Test create zip
        let create_res = create_zip_from_dir(&src_dir, &zip_path).unwrap();
        assert_eq!(create_res.files_compressed, 2);
        assert!(create_res.zip_size > 0);

        // 2. Test extract zip
        let extract_res = extract_zip_safe(&zip_path, &dest_dir).unwrap();
        assert_eq!(extract_res.files_extracted, 2);
        assert_eq!(fs::read(dest_dir.join("src").join("hello.txt")).unwrap(), b"Hello World from Rust!");
        assert_eq!(fs::read(dest_dir.join("src").join("sub").join("nested.txt")).unwrap(), b"Nested content data");

        // Cleanup
        let _ = fs::remove_dir_all(&tmp_root);
    }
}
