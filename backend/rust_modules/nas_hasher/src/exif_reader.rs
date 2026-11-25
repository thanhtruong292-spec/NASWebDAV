use exif::{Reader, Tag, Value};
use serde::Serialize;
use std::fs::File;
use std::io::BufReader;
use std::path::Path;

#[derive(Debug, Serialize, Default, PartialEq, Clone)]
pub struct ExifMetadata {
    pub date_taken: Option<String>,       // "YYYY-MM-DD HH:MM:SS"
    pub timestamp_ms: Option<i64>,       // epoch timestamp ms
    pub camera_make: Option<String>,      // "Apple", "Samsung"...
    pub camera_model: Option<String>,     // "iPhone 15 Pro"...
    pub latitude: Option<f64>,           // decimal degrees
    pub longitude: Option<f64>,          // decimal degrees
    pub orientation: Option<u32>,        // 1..8
}

fn unix_timestamp_from_datetime(year: i64, month: i64, day: i64, hour: i64, min: i64, sec: i64) -> i64 {
    let m = (month + 9) % 12;
    let y = year - m / 10;
    let days = 365 * y + y / 4 - y / 100 + y / 400 + (m * 306 + 5) / 10 + (day - 1) - 719468;
    days * 86400 + hour * 3600 + min * 60 + sec
}

fn parse_exif_datetime(raw: &str) -> (Option<String>, Option<i64>) {
    let s = raw.trim();
    if s.len() >= 19 {
        let y: i64 = s[0..4].parse().unwrap_or(0);
        let m: i64 = s[5..7].parse().unwrap_or(0);
        let d: i64 = s[8..10].parse().unwrap_or(0);
        let h: i64 = s[11..13].parse().unwrap_or(0);
        let min: i64 = s[14..16].parse().unwrap_or(0);
        let sec: i64 = s[17..19].parse().unwrap_or(0);
        if y >= 1970 && (1..=12).contains(&m) && (1..=31).contains(&d) {
            let normalized = format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", y, m, d, h, min, sec);
            let ts = unix_timestamp_from_datetime(y, m, d, h, min, sec);
            return (Some(normalized), Some(ts * 1000));
        }
    }
    (None, None)
}

fn parse_gps_coord(val: &Value, ref_str: Option<&str>) -> Option<f64> {
    if let Value::Rational(rats) = val {
        if rats.len() >= 3 && rats[0].denom != 0 && rats[1].denom != 0 && rats[2].denom != 0 {
            let deg = rats[0].num as f64 / rats[0].denom as f64;
            let min = rats[1].num as f64 / rats[1].denom as f64;
            let sec = rats[2].num as f64 / rats[2].denom as f64;
            let mut coord = deg + (min / 60.0) + (sec / 3600.0);
            if let Some(r) = ref_str {
                let trimmed = r.trim().to_uppercase();
                if trimmed == "S" || trimmed == "W" {
                    coord = -coord;
                }
            }
            return Some(coord);
        }
    }
    None
}

/// Trích xuất siêu dữ liệu EXIF siêu tốc từ header ảnh (chỉ đọc segment APP1/eXIf không decode pixel).
pub fn read_image_exif(img_path: &Path) -> Result<ExifMetadata, String> {
    let file = File::open(img_path).map_err(|e| format!("cannot open: {}", e))?;
    let mut bufreader = BufReader::new(file);
    let exifreader = Reader::new();
    let exif = exifreader
        .read_from_container(&mut bufreader)
        .map_err(|e| format!("exif error: {}", e))?;

    let mut meta = ExifMetadata::default();

    // 1. Ngày chụp: ưu tiên DateTimeOriginal, sau đó DateTimeDigitized, cuối cùng DateTime
    let dt_field = exif
        .fields()
        .find(|f| f.tag == Tag::DateTimeOriginal)
        .or_else(|| exif.fields().find(|f| f.tag == Tag::DateTimeDigitized))
        .or_else(|| exif.fields().find(|f| f.tag == Tag::DateTime));
    if let Some(f) = dt_field {
        let raw = f.display_value().to_string();
        let (dt_str, ts) = parse_exif_datetime(&raw);
        meta.date_taken = dt_str;
        meta.timestamp_ms = ts;
    }

    // 2. Camera Make & Model
    if let Some(f) = exif.fields().find(|f| f.tag == Tag::Make) {
        meta.camera_make = Some(
            f.display_value()
                .to_string()
                .trim_matches('"')
                .trim()
                .to_string(),
        );
    }
    if let Some(f) = exif.fields().find(|f| f.tag == Tag::Model) {
        meta.camera_model = Some(
            f.display_value()
                .to_string()
                .trim_matches('"')
                .trim()
                .to_string(),
        );
    }

    // 3. Orientation
    if let Some(f) = exif.fields().find(|f| f.tag == Tag::Orientation) {
        if let Some(v) = f.value.get_uint(0) {
            meta.orientation = Some(v);
        }
    }

    // 4. GPS
    let lat_ref = exif
        .fields()
        .find(|f| f.tag == Tag::GPSLatitudeRef)
        .map(|f| f.display_value().to_string());
    let lon_ref = exif
        .fields()
        .find(|f| f.tag == Tag::GPSLongitudeRef)
        .map(|f| f.display_value().to_string());

    if let Some(f) = exif.fields().find(|f| f.tag == Tag::GPSLatitude) {
        meta.latitude = parse_gps_coord(&f.value, lat_ref.as_deref());
    }
    if let Some(f) = exif.fields().find(|f| f.tag == Tag::GPSLongitude) {
        meta.longitude = parse_gps_coord(&f.value, lon_ref.as_deref());
    }

    Ok(meta)
}

/// Trích xuất EXIF đa luồng (Rayon) cho danh sách đường dẫn ảnh.
pub fn read_batch_exif(paths: &[String], workers: usize) -> Vec<(String, Option<ExifMetadata>)> {
    use rayon::prelude::*;

    let n_threads = workers.clamp(1, 8);
    let runner = || {
        paths
            .par_iter()
            .map(|p| {
                let m = read_image_exif(Path::new(p)).ok();
                (p.clone(), m)
            })
            .collect()
    };

    if let Some(pool) = crate::shared_rayon_pool(n_threads) {
        pool.install(runner)
    } else {
        runner()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_datetime_and_timestamp() {
        let (dt, ts) = parse_exif_datetime("2026:11:19 23:59:00");
        assert_eq!(dt.as_deref(), Some("2026-11-19 23:59:00"));
        assert!(ts.is_some());
        let ts_val = ts.unwrap();
        // 2026-11-19 23:59:00 UTC timestamp tương ứng khoảng 1.795 tỷ giây * 1000
        assert!(ts_val > 1_700_000_000_000);
    }

    #[test]
    fn test_parse_gps_coord() {
        use exif::Rational;
        // 10 độ 45 phút 30 giây Bắc (N)
        let rats = vec![
            Rational { num: 10, denom: 1 },
            Rational { num: 45, denom: 1 },
            Rational { num: 30, denom: 1 },
        ];
        let val = Value::Rational(rats);
        let coord_n = parse_gps_coord(&val, Some("N")).unwrap();
        assert!((coord_n - 10.758333333333333).abs() < 1e-6);

        // Nam (S) đổi dấu âm
        let coord_s = parse_gps_coord(&val, Some("S")).unwrap();
        assert!((coord_s - -10.758333333333333).abs() < 1e-6);
    }
}
