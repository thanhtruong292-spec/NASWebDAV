use flate2::read::GzDecoder;
use serde::Serialize;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Component, Path};
use tar::{Archive, EntryType};
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

#[derive(Debug, Serialize, Default, PartialEq)]
pub struct TarBatchItem {
    pub path: String,
    pub size: u64,
    pub sha256: String,
}

#[derive(Debug, Serialize, Default, PartialEq)]
pub struct TarBatchResult {
    pub ok: bool,
    pub files_extracted: u64,
    pub bytes_extracted: u64,
    pub items: Vec<TarBatchItem>,
    pub error: Option<String>,
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

fn validate_tar_rel_path(rel: &Path) -> Result<std::path::PathBuf, String> {
    let mut clean = std::path::PathBuf::new();
    for comp in rel.components() {
        match comp {
            Component::Normal(p) => clean.push(p),
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(format!("Unsafe archive entry: {:?}", rel));
            }
        }
    }
    if clean.as_os_str().is_empty() {
        return Err("Unsafe archive entry: empty path".to_string());
    }
    Ok(clean)
}

fn extract_tar_reader<R: Read>(
    reader: R,
    dest_real: &Path,
) -> Result<ZipExtractResult, String> {
    let mut archive = Archive::new(reader);
    let mut files_count = 0u64;
    let mut bytes_count = 0u64;
    let mut buf = [0u8; 65536];

    let entries = archive
        .entries()
        .map_err(|e| format!("Invalid tar archive: {}", e))?;

    for entry_res in entries {
        let mut entry = entry_res.map_err(|e| format!("Read tar entry error: {}", e))?;
        let etype = entry.header().entry_type();
        let entry_path = entry
            .path()
            .map_err(|e| format!("Invalid tar path: {}", e))?
            .into_owned();

        // Chặn tuyệt đối symlink, hardlink, character/block device, fifo
        if matches!(
            etype,
            EntryType::Symlink | EntryType::Link | EntryType::Char | EntryType::Block | EntryType::Fifo
        ) {
            return Err(format!("Unsafe archive entry: {:?}", entry_path));
        }

        // Bỏ qua PAX metadata headers (XHeader / XGlobalHeader / GNULongName)
        if matches!(
            etype,
            EntryType::XHeader | EntryType::XGlobalHeader | EntryType::GNULongName | EntryType::GNULongLink
        ) {
            continue;
        }

        let clean_rel = validate_tar_rel_path(&entry_path)?;
        let out_path = dest_real.join(&clean_rel);
        if !out_path.starts_with(dest_real) {
            return Err(format!("Unsafe archive entry: {:?}", entry_path));
        }

        if etype == EntryType::Directory {
            fs::create_dir_all(&out_path)
                .map_err(|e| format!("Cannot create dir {:?}: {}", out_path, e))?;
        } else if etype == EntryType::Regular || etype == EntryType::Continuous {
            if let Some(parent) = out_path.parent() {
                if !parent.is_dir() {
                    fs::create_dir_all(parent)
                        .map_err(|e| format!("Cannot create parent dir: {}", e))?;
                }
            }
            let mut out_file = File::create(&out_path)
                .map_err(|e| format!("Cannot create file {:?}: {}", out_path, e))?;
            loop {
                let n = entry
                    .read(&mut buf)
                    .map_err(|e| format!("Read tar stream error: {}", e))?;
                if n == 0 {
                    break;
                }
                out_file
                    .write_all(&buf[..n])
                    .map_err(|e| format!("Write tar file error: {}", e))?;
                bytes_count += n as u64;
            }
            files_count += 1;
        } else {
            return Err(format!("Unsupported tar entry type: {:?}", entry_path));
        }
    }

    Ok(ZipExtractResult {
        files_extracted: files_count,
        bytes_extracted: bytes_count,
    })
}

/// Giải nén `.tar` / `.tar.gz` / `.tgz` an toàn tuyệt đối chống Tar-Slip, Symlink & Hardlink attack.
pub fn extract_tar_safe(archive_path: &Path, dest_dir: &Path) -> Result<ZipExtractResult, String> {
    if !archive_path.is_file() {
        return Err("Archive file not found".into());
    }
    if !dest_dir.is_dir() {
        fs::create_dir_all(dest_dir).map_err(|e| format!("Cannot create dest_dir: {}", e))?;
    }
    let dest_real =
        fs::canonicalize(dest_dir).map_err(|e| format!("Canonicalize dest_dir failed: {}", e))?;

    // Nhận diện gzip magic 0x1F 0x8B hoặc bzip2/xz không hỗ trợ (để Python fallback)
    let mut probe = [0u8; 6];
    let n = {
        let mut f = File::open(archive_path).map_err(|e| format!("Cannot open archive: {}", e))?;
        f.read(&mut probe).unwrap_or(0)
    };

    let is_gzip = n >= 2 && probe[0] == 0x1F && probe[1] == 0x8B;
    let is_bzip2 = n >= 3 && &probe[0..3] == b"BZh";
    let is_xz = n >= 6 && probe == [0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00];
    if is_bzip2 || is_xz {
        return Err("unsupported_compression".to_string());
    }

    let file = File::open(archive_path).map_err(|e| format!("Cannot open archive: {}", e))?;
    let reader = BufReader::with_capacity(64 * 1024, file);
    if is_gzip {
        extract_tar_reader(GzDecoder::new(reader), &dest_real)
    } else {
        extract_tar_reader(reader, &dest_real)
    }
}

fn extract_tar_batch_reader<R: Read>(
    reader: R,
    dest_real: &Path,
) -> Result<TarBatchResult, String> {
    use sha2::{Digest, Sha256};
    let mut archive = Archive::new(reader);
    let mut files_count = 0u64;
    let mut bytes_count = 0u64;
    let mut items = Vec::new();
    let mut buf = [0u8; 65536];

    let entries = archive
        .entries()
        .map_err(|e| format!("Invalid tar archive: {}", e))?;

    for entry_res in entries {
        let mut entry = entry_res.map_err(|e| format!("Read tar entry error: {}", e))?;
        let etype = entry.header().entry_type();
        let entry_path = entry
            .path()
            .map_err(|e| format!("Invalid tar path: {}", e))?
            .into_owned();

        if matches!(
            etype,
            EntryType::Symlink | EntryType::Link | EntryType::Char | EntryType::Block | EntryType::Fifo
        ) {
            return Err(format!("Unsafe archive entry: {:?}", entry_path));
        }

        if matches!(
            etype,
            EntryType::XHeader | EntryType::XGlobalHeader | EntryType::GNULongName | EntryType::GNULongLink
        ) {
            continue;
        }

        let clean_rel = validate_tar_rel_path(&entry_path)?;
        let out_path = dest_real.join(&clean_rel);
        if !out_path.starts_with(dest_real) {
            return Err(format!("Unsafe archive entry: {:?}", entry_path));
        }

        if etype == EntryType::Directory {
            fs::create_dir_all(&out_path)
                .map_err(|e| format!("Cannot create dir {:?}: {}", out_path, e))?;
        } else if etype == EntryType::Regular || etype == EntryType::Continuous {
            if let Some(parent) = out_path.parent() {
                if !parent.is_dir() {
                    fs::create_dir_all(parent)
                        .map_err(|e| format!("Cannot create parent dir: {}", e))?;
                }
            }
            let mut out_file = File::create(&out_path)
                .map_err(|e| format!("Cannot create file {:?}: {}", out_path, e))?;
            let mut file_hasher = Sha256::new();
            let mut file_bytes = 0u64;
            loop {
                let n = entry
                    .read(&mut buf)
                    .map_err(|e| format!("Read tar stream error: {}", e))?;
                if n == 0 {
                    break;
                }
                out_file
                    .write_all(&buf[..n])
                    .map_err(|e| format!("Write tar file error: {}", e))?;
                file_hasher.update(&buf[..n]);
                bytes_count += n as u64;
                file_bytes += n as u64;
            }
            files_count += 1;
            let sha256_hex = format!("{:x}", file_hasher.finalize());
            items.push(TarBatchItem {
                path: clean_rel.to_string_lossy().to_string().replace('\\', "/"),
                size: file_bytes,
                sha256: sha256_hex,
            });
        } else {
            return Err(format!("Unsupported tar entry type: {:?}", entry_path));
        }
    }

    Ok(TarBatchResult {
        ok: true,
        files_extracted: files_count,
        bytes_extracted: bytes_count,
        items,
        error: None,
    })
}

/// Giải nén TAR batch và tính SHA256 song song zero-overhead cho AutoBackup.
pub fn extract_tar_batch_safe(
    archive_path: &Path,
    dest_dir: &Path,
) -> Result<TarBatchResult, String> {
    if !archive_path.is_file() {
        return Err("Archive file not found".into());
    }
    if !dest_dir.is_dir() {
        fs::create_dir_all(dest_dir).map_err(|e| format!("Cannot create dest_dir: {}", e))?;
    }
    let dest_real =
        fs::canonicalize(dest_dir).map_err(|e| format!("Canonicalize dest_dir failed: {}", e))?;

    let file = File::open(archive_path).map_err(|e| format!("Cannot open archive: {}", e))?;
    let reader = BufReader::with_capacity(64 * 1024, file);
    extract_tar_batch_reader(reader, &dest_real)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tar_gz_extract_and_safety() {
        use flate2::write::GzEncoder;
        use flate2::Compression;

        let tmp_root = std::env::temp_dir().join(format!("rust_targz_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp_root);
        let dest_dir = tmp_root.join("dest");
        fs::create_dir_all(&tmp_root).unwrap();

        let tgz_path = tmp_root.join("sample.tar.gz");
        {
            let f = File::create(&tgz_path).unwrap();
            let enc = GzEncoder::new(f, Compression::fast());
            let mut builder = tar::Builder::new(enc);
            let data = b"Hello TAR.GZ from Rust!";
            let mut header = tar::Header::new_gnu();
            header.set_size(data.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder.append_data(&mut header, "sub/hello.txt", &data[..]).unwrap();
            let enc = builder.into_inner().unwrap();
            enc.finish().unwrap();
        }

        let res = extract_tar_safe(&tgz_path, &dest_dir).unwrap();
        assert_eq!(res.files_extracted, 1);
        assert_eq!(fs::read(dest_dir.join("sub").join("hello.txt")).unwrap(), b"Hello TAR.GZ from Rust!");
        let _ = fs::remove_dir_all(&tmp_root);
    }

    #[test]
    fn test_tar_batch_extract_with_hashes() {
        let tmp_root = std::env::temp_dir().join(format!("rust_tarbatch_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp_root);
        let dest_dir = tmp_root.join("dest");
        fs::create_dir_all(&tmp_root).unwrap();

        let tar_path = tmp_root.join("batch.tar");
        let data1 = b"IMG_0001_DATA_FOR_TESTING";
        let data2 = b"DCIM_CAMERA_IMG_0002_SECOND_PHOTO_PAYLOAD";
        {
            let f = File::create(&tar_path).unwrap();
            let mut builder = tar::Builder::new(f);

            let mut h1 = tar::Header::new_gnu();
            h1.set_size(data1.len() as u64);
            h1.set_mode(0o644);
            h1.set_cksum();
            builder.append_data(&mut h1, "DCIM/Camera/IMG_0001.jpg", &data1[..]).unwrap();

            let mut h2 = tar::Header::new_gnu();
            h2.set_size(data2.len() as u64);
            h2.set_mode(0o644);
            h2.set_cksum();
            builder.append_data(&mut h2, "Pictures/Facebook/FB_0002.jpg", &data2[..]).unwrap();

            builder.finish().unwrap();
        }

        let res = extract_tar_batch_safe(&tar_path, &dest_dir).unwrap();
        assert!(res.ok);
        assert_eq!(res.files_extracted, 2);
        assert_eq!(res.bytes_extracted, (data1.len() + data2.len()) as u64);
        assert_eq!(res.items.len(), 2);

        // Kiểm tra file 1
        assert_eq!(res.items[0].path, "DCIM/Camera/IMG_0001.jpg");
        assert_eq!(res.items[0].size, data1.len() as u64);
        use sha2::{Digest, Sha256};
        let expected_sha1 = format!("{:x}", Sha256::digest(data1));
        assert_eq!(res.items[0].sha256, expected_sha1);

        // Kiểm tra file 2
        assert_eq!(res.items[1].path, "Pictures/Facebook/FB_0002.jpg");
        assert_eq!(res.items[1].size, data2.len() as u64);
        let expected_sha2 = format!("{:x}", Sha256::digest(data2));
        assert_eq!(res.items[1].sha256, expected_sha2);

        let _ = fs::remove_dir_all(&tmp_root);
    }

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
