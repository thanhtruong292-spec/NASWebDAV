use unicode_normalization::char::is_combining_mark;
use unicode_normalization::UnicodeNormalization;

/// Chuẩn hóa chuỗi bằng cách loại bỏ dấu tiếng Việt và dấu phụ Latin (NFD),
/// thay thế 'đ' và 'Đ' bằng 'd'.
/// Đạt độ khớp 100% byte-for-byte với _remove_diacritics_py của Python.
pub fn remove_diacritics(s: &str) -> String {
    if s.is_empty() {
        return String::new();
    }
    let mut out = String::with_capacity(s.len());
    for c in s.nfd() {
        if is_combining_mark(c) {
            continue;
        }
        match c {
            'đ' => out.push('d'),
            'Đ' => out.push('d'),
            other => out.push(other),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::{BufRead, BufReader};
    use serde_json::Value;

    #[test]
    fn test_ground_truth_parity() {
        let file = File::open("diacritics_cases.jsonl").expect("diacritics_cases.jsonl must exist");
        let reader = BufReader::new(file);

        let mut count = 0;
        for line in reader.lines() {
            let line = line.unwrap();
            if line.trim().is_empty() {
                continue;
            }
            let v: Value = serde_json::from_str(&line).unwrap();
            let input = v["input"].as_str().unwrap();
            let expected = v["expected"].as_str().unwrap();
            let input_lower = v["input_lower"].as_str().unwrap();
            let expected_lower = v["expected_lower"].as_str().unwrap();

            let actual = remove_diacritics(input);
            assert_eq!(
                actual, expected,
                "Mismatch on raw input: '{}' => got '{}', expected '{}'",
                input, actual, expected
            );

            let actual_lower = remove_diacritics(input_lower);
            assert_eq!(
                actual_lower, expected_lower,
                "Mismatch on lower input: '{}' => got '{}', expected '{}'",
                input_lower, actual_lower, expected_lower
            );

            count += 1;
        }
        println!("Verified {} ground-truth cases successfully!", count);
    }
}
