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
}
