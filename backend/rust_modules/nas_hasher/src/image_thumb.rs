use fast_image_resize::images::Image as FirImage;
use fast_image_resize::{FilterType, PixelType, ResizeAlg, ResizeOptions, Resizer};
use image::codecs::jpeg::JpegEncoder;
use image::{ColorType, ImageReader};
use std::fs::{self, File};
use std::io::BufWriter;
use std::path::Path;

/// Tính kích thước đích khớp 100% logic `PIL.Image.thumbnail((max_size, max_size))` (không phóng to).
pub fn calc_thumbnail_dims(src_w: u32, src_h: u32, max_size: u32) -> (u32, u32) {
    if src_w == 0 || src_h == 0 || max_size == 0 {
        return (src_w.max(1), src_h.max(1));
    }
    if src_w <= max_size && src_h <= max_size {
        return (src_w, src_h);
    }
    if src_w >= src_h {
        let new_h = ((src_h as f64) * (max_size as f64) / (src_w as f64)).round() as u32;
        (max_size, new_h.max(1))
    } else {
        let new_w = ((src_w as f64) * (max_size as f64) / (src_h as f64)).round() as u32;
        (new_w.max(1), max_size)
    }
}

/// Tạo thumbnail JPEG tăng tốc phần cứng SIMD (ARM64 NEON trên NAS RK3328) bằng `fast_image_resize` + `zune-jpeg`.
pub fn generate_image_thumbnail(
    src_path: &Path,
    dst_path: &Path,
    max_size: u32,
    quality: u8,
) -> Result<(u32, u32), String> {
    if let Some(parent) = dst_path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent).map_err(|e| format!("mkdir failed: {}", e))?;
        }
    }

    let reader = ImageReader::open(src_path)
        .map_err(|e| format!("open failed: {}", e))?
        .with_guessed_format()
        .map_err(|e| format!("format guess failed: {}", e))?;

    let decoded = reader
        .decode()
        .map_err(|e| format!("decode failed: {}", e))?;

    let rgb = decoded.to_rgb8();
    let (src_w, src_h) = rgb.dimensions();
    let (dst_w, dst_h) = calc_thumbnail_dims(src_w, src_h, max_size);

    let out_pixels: Vec<u8> = if dst_w == src_w && dst_h == src_h {
        rgb.into_raw()
    } else {
        let src_fir = FirImage::from_vec_u8(src_w, src_h, rgb.into_raw(), PixelType::U8x3)
            .map_err(|e| format!("fir src failed: {}", e))?;
        let mut dst_fir = FirImage::new(dst_w, dst_h, PixelType::U8x3);
        let mut resizer = Resizer::new();
        let options = ResizeOptions::new().resize_alg(ResizeAlg::Convolution(FilterType::Bilinear));
        resizer
            .resize(&src_fir, &mut dst_fir, Some(&options))
            .map_err(|e| format!("fir resize failed: {}", e))?;
        dst_fir.into_vec()
    };

    let out_file = File::create(dst_path).map_err(|e| format!("create dst failed: {}", e))?;
    let mut writer = BufWriter::with_capacity(32768, out_file);
    let mut encoder = JpegEncoder::new_with_quality(&mut writer, quality.clamp(1, 100));
    encoder
        .encode(&out_pixels, dst_w, dst_h, ColorType::Rgb8.into())
        .map_err(|e| format!("jpeg encode failed: {}", e))?;

    Ok((dst_w, dst_h))
}

/// Tính độ sáng trung bình (0.0 .. 255.0) của ảnh JPEG/PNG mà không cấp phát mảng pixel Python.
pub fn compute_frame_brightness(img_path: &Path) -> Result<f64, String> {
    let reader = ImageReader::open(img_path)
        .map_err(|e| format!("open failed: {}", e))?
        .with_guessed_format()
        .map_err(|e| format!("format guess failed: {}", e))?;

    let luma = reader
        .decode()
        .map_err(|e| format!("decode failed: {}", e))?
        .to_luma8();

    let raw = luma.as_raw();
    if raw.is_empty() {
        return Ok(0.0);
    }
    let sum: u64 = raw.iter().map(|&p| p as u64).sum();
    Ok(sum as f64 / raw.len() as f64)
}

/// Tính perceptual hash (aHash - 64-bit Average Hash) của ảnh khớp 100% logic của Android:
/// 1. Resize ảnh về 8x8 pixels bằng NEON SIMD Bilinear.
/// 2. Chuyển sang Grayscale theo trọng số chuẩn (0.299*R + 0.587*G + 0.114*B).
/// 3. Tính độ xám trung bình của 64 pixels.
/// 4. Đóng gói 64 bits (pixel >= avg là 1, ngược lại 0; pixel (0,0) là bit cao nhất MSB).
/// 5. Trả về chuỗi 16 ký tự hex chữ thường.
pub fn compute_image_ahash(img_path: &Path) -> Result<String, String> {
    let reader = ImageReader::open(img_path)
        .map_err(|e| format!("open failed: {}", e))?
        .with_guessed_format()
        .map_err(|e| format!("format guess failed: {}", e))?;

    let decoded = reader
        .decode()
        .map_err(|e| format!("decode failed: {}", e))?;

    let rgb = decoded.to_rgb8();
    let (src_w, src_h) = rgb.dimensions();
    if src_w == 0 || src_h == 0 {
        return Err("empty image dimensions".into());
    }

    let src_fir = FirImage::from_vec_u8(src_w, src_h, rgb.into_raw(), PixelType::U8x3)
        .map_err(|e| format!("fir src failed: {}", e))?;
    let mut dst_fir = FirImage::new(8, 8, PixelType::U8x3);
    let mut resizer = Resizer::new();
    let options = ResizeOptions::new().resize_alg(ResizeAlg::Convolution(FilterType::Bilinear));
    resizer
        .resize(&src_fir, &mut dst_fir, Some(&options))
        .map_err(|e| format!("fir resize failed: {}", e))?;

    let pixels_8x8 = dst_fir.into_vec();
    let mut grays = [0.0f64; 64];
    let mut sum = 0.0f64;

    for i in 0..64 {
        let r = pixels_8x8[i * 3] as f64;
        let g = pixels_8x8[i * 3 + 1] as f64;
        let b = pixels_8x8[i * 3 + 2] as f64;
        let gray = 0.299 * r + 0.587 * g + 0.114 * b;
        grays[i] = gray;
        sum += gray;
    }

    let avg = sum / 64.0;
    let mut hash_val = 0u64;
    for (i, &g) in grays.iter().enumerate() {
        if g >= avg {
            hash_val |= 1u64 << (63 - i);
        }
    }

    Ok(format!("{:016x}", hash_val))
}

/// Tính khoảng cách Hamming giữa hai chuỗi aHash 16 ký tự hex (số lượng bit khác biệt 0..64).
pub fn hamming_distance(hash1: &str, hash2: &str) -> Option<u32> {
    if hash1.len() != 16 || hash2.len() != 16 {
        return None;
    }
    let h1 = u64::from_str_radix(hash1, 16).ok()?;
    let h2 = u64::from_str_radix(hash2, 16).ok()?;
    Some((h1 ^ h2).count_ones())
}

/// Tính batch aHash đa luồng (Rayon) cho danh sách đường dẫn ảnh.
pub fn compute_batch_ahash(paths: &[String], workers: usize) -> Vec<(String, Option<String>)> {
    use rayon::prelude::*;

    let n_threads = workers.clamp(1, 8);
    let runner = || {
        paths
            .par_iter()
            .map(|p| {
                let h = compute_image_ahash(Path::new(p)).ok();
                (p.clone(), h)
            })
            .collect()
    };

    if let Ok(pool) = rayon::ThreadPoolBuilder::new().num_threads(n_threads).build() {
        pool.install(runner)
    } else {
        runner()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{Rgb, RgbImage};

    #[test]
    fn test_thumbnail_and_brightness_roundtrip() {
        let tmp_src = std::env::temp_dir().join(format!("rust_thumb_src_{}.png", std::process::id()));
        let tmp_dst = std::env::temp_dir().join(format!("rust_thumb_dst_{}.jpg", std::process::id()));

        // Tạo ảnh 800x400 màu xám sáng (RGB = 200, 200, 200)
        let img = RgbImage::from_pixel(800, 400, Rgb([200, 200, 200]));
        img.save(&tmp_src).unwrap();

        let (dw, dh) = generate_image_thumbnail(&tmp_src, &tmp_dst, 320, 82).unwrap();
        assert_eq!((dw, dh), (320, 160));
        assert!(fs::metadata(&tmp_dst).unwrap().len() > 100);

        let brightness = compute_frame_brightness(&tmp_dst).unwrap();
        assert!((brightness - 200.0).abs() < 3.0, "Expected ~200.0, got {}", brightness);

        let _ = fs::remove_file(&tmp_src);
        let _ = fs::remove_file(&tmp_dst);
    }

    #[test]
    fn test_ahash_and_hamming_distance() {
        let tmp_half = std::env::temp_dir().join(format!("rust_ahash_half_{}.png", std::process::id()));
        let tmp_inv = std::env::temp_dir().join(format!("rust_ahash_inv_{}.png", std::process::id()));

        // Ảnh 1: Nửa trên trắng (y < 8), nửa dưới đen (y >= 8) kích thước 16x16
        let mut img1 = RgbImage::new(16, 16);
        for y in 0..16 {
            for x in 0..16 {
                if y < 8 {
                    img1.put_pixel(x, y, Rgb([255, 255, 255]));
                } else {
                    img1.put_pixel(x, y, Rgb([0, 0, 0]));
                }
            }
        }
        img1.save(&tmp_half).unwrap();

        // Ảnh 2: Đảo ngược - Nửa trên đen, nửa dưới trắng
        let mut img2 = RgbImage::new(16, 16);
        for y in 0..16 {
            for x in 0..16 {
                if y < 8 {
                    img2.put_pixel(x, y, Rgb([0, 0, 0]));
                } else {
                    img2.put_pixel(x, y, Rgb([255, 255, 255]));
                }
            }
        }
        img2.save(&tmp_inv).unwrap();

        let h1 = compute_image_ahash(&tmp_half).unwrap();
        let h2 = compute_image_ahash(&tmp_inv).unwrap();

        assert_eq!(h1.len(), 16);
        assert_eq!(h2.len(), 16);
        // Nửa trên trắng thì 32 bit đầu là 1 -> "ffffffff00000000"
        assert_eq!(h1, "ffffffff00000000");
        assert_eq!(h2, "00000000ffffffff");

        // Khoảng cách Hamming giữa 2 ảnh tương phản 100% phải là 64 bits
        let dist = hamming_distance(&h1, &h2).unwrap();
        assert_eq!(dist, 64);

        // Khoảng cách của ảnh với chính nó là 0
        let dist_self = hamming_distance(&h1, &h1).unwrap();
        assert_eq!(dist_self, 0);

        // Test batch
        let batch_paths = vec![
            tmp_half.to_string_lossy().to_string(),
            tmp_inv.to_string_lossy().to_string(),
        ];
        let batch_results = compute_batch_ahash(&batch_paths, 2);
        assert_eq!(batch_results.len(), 2);
        assert_eq!(batch_results[0].1.as_deref(), Some("ffffffff00000000"));
        assert_eq!(batch_results[1].1.as_deref(), Some("00000000ffffffff"));

        let _ = fs::remove_file(&tmp_half);
        let _ = fs::remove_file(&tmp_inv);
    }
}
