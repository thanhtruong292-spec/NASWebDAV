#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
record_diacritics_cases.py
Ghi nhận ground-truth từ hàm _remove_diacritics_py của Python
theo quy trình rust-anything.
"""

import json
import os
import unicodedata

def _remove_diacritics_py(s):
    if not s: return ""
    nfd = unicodedata.normalize('NFD', str(s))
    s_clean = "".join(c for c in nfd if unicodedata.category(c) != 'Mn')
    return s_clean.replace('đ', 'd').replace('Đ', 'd')

test_strings = [
    "",
    "a",
    "A",
    "đ",
    "Đ",
    "đường",
    "ĐƯỜNG",
    "Việt Nam",
    "VIỆT NAM ĐẤT NƯỚC CON NGƯỜI",
    "Tài liệu báo cáo tài chính quý 4 năm 2026",
    "Ảnh chụp kỳ nghỉ lễ Đà Nẵng - Hội An đẹp tuyệt vời.jpg",
    "Video quay flycam bờ biển Nha Trang lúc hoàng hôn.mp4",
    "Bản vẽ thiết kế kỹ thuật công trình thủy điện Nho Quế 2.dwg",
    "Hợp đồng lao động và phụ lục bảo hiểm xã hội 2026.docx",
    "Đoàn Thị Điểm",
    "Nguyễn Trãi - Bình Ngô Đại Cáo",
    "Tiếng Việt có dấu: á à ả ã ạ ă ắ ằ ẳ ẵ ặ â ấn ầ ẩn lẫn ậ",
    "é è ẻ ẽ ẹ ê ế ề ể ễ ệ",
    "í ì ỉ ĩ ị",
    "ó ò ỏ õ ọ ô ố ồ ổ ỗ ộ ơ ớ ờ ở ỡ ợ",
    "ú ù ủ ũ ụ ư ứ ừ ử ữ ự",
    "ý ỳ ỷ ỹ ỵ",
    "Á À Ả Ã Ạ Ă Ắ Ằ Ẳ Ẵ Ặ Â Ấ Ầ Ẩ Ẫ Ậ",
    "É È Ẻ Ẽ Ẹ Ê Ế Ề Ể Ễ Ệ",
    "Í Ì Ỉ Ĩ Ị",
    "Ó Ò Ỏ Õ Ọ Ô Ố Ồ Ổ Ỗ Ộ Ơ ỚỜ Ở Ỡ Ợ",
    "Ú Ù Ủ Ũ Ụ Ư Ứ Ừ Ử Ữ Ự",
    "Ý Ỳ Ỷ Ỹ Ỵ",
    "Dấu câu & ký tự đặc biệt: !@#$%^&*()_+-=[]{}|;':\",./<>?",
    "Số: 0123456789",
    "English text: The quick brown fox jumps over the lazy dog.",
    "French: café, naïve, façade, déjà vu, Noël",
    "German: schön, über, Straße, Äpfel, Österreich",
    "Spanish: año, señor, niño, canción",
    "Complex filename: [2026-11-19] Báo Cáo Kỹ Thuật #01 (Bản Đầy Đủ) + Bản Vẽ.PDF",
    "Special: 100%_+ Tài liệu+%_",
]

cases = []
for s in test_strings:
    cases.append({
        "input": s,
        "input_lower": s.lower(),
        "expected": _remove_diacritics_py(s),
        "expected_lower": _remove_diacritics_py(s.lower()),
    })

out_dir = os.path.join("backend", "rust_modules", "nas_indexer")
os.makedirs(out_dir, exist_ok=True)
out_file = os.path.join(out_dir, "diacritics_cases.jsonl")

with open(out_file, "w", encoding="utf-8") as f:
    for c in cases:
        f.write(json.dumps(c, ensure_ascii=False) + "\n")

print("Recorded %d ground-truth cases to %s" % (len(cases), out_file))
