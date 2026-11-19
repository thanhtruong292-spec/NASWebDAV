#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
record_hasher_cases.py
Bước RECORD theo chuẩn rust-anything:
Chạy logic băm gốc của Python trên tập dữ liệu đa dạng (file rỗng, UTF-8, các kích thước)
để tạo file chân lý mặt đất (cases.jsonl) làm tiêu chuẩn đối soát tuyệt đối với Rust.
"""

import os
import sys
import json
import hashlib
import tempfile

def compute_python_hashes(filepath):
    """Tính toán theo đúng logic hiện hành của backend/nas_api_server.py"""
    if not os.path.isfile(filepath):
        return {
            "path": filepath,
            "exists": False,
            "error": "not_found"
        }
    
    size = os.path.getsize(filepath)
    
    # 1. MD5 chunk 1MB (dùng trong api_hash_batch)
    with open(filepath, "rb") as f:
        chunk = f.read(1048576)
        md5_1mb = hashlib.md5(chunk).hexdigest()
        
    # 2. SHA256 full file (dùng trong duplicates & verified hash store)
    h_sha = hashlib.sha256()
    with open(filepath, "rb") as f:
        while True:
            c = f.read(65536)
            if not c:
                break
            h_sha.update(c)
    sha256_full = h_sha.hexdigest()

    return {
        "path": filepath,
        "exists": True,
        "size": size,
        "md5_1mb": md5_1mb,
        "sha256_full": sha256_full
    }

def main():
    test_dir = os.path.join(tempfile.gettempdir(), "nas_rust_record_test")
    os.makedirs(test_dir, exist_ok=True)
    
    cases = []
    
    # Ca 1: File rỗng 0 bytes
    p1 = os.path.join(test_dir, "empty.bin")
    with open(p1, "wb") as f:
        pass
    cases.append(p1)
    
    # Ca 2: File nhỏ 13 bytes
    p2 = os.path.join(test_dir, "small_hello.txt")
    with open(p2, "wb") as f:
        f.write(b"Hello, World!")
    cases.append(p2)
    
    # Ca 3: File tên tiếng Việt UTF-8 có dấu & khoảng trắng
    p3 = os.path.join(test_dir, "Tài liệu sao lưu thử nghiệm 2026.dat")
    with open(p3, "wb") as f:
        f.write("Dữ liệu sao lưu tiếng Việt đặc biệt: 123456789".encode("utf-8"))
    cases.append(p3)
    
    # Ca 4: File nhị phân lặp lại 512KB (nhỏ hơn 1MB chunk)
    p4 = os.path.join(test_dir, "binary_512k.dat")
    with open(p4, "wb") as f:
        f.write(os.urandom(512 * 1024))
    cases.append(p4)
    
    # Ca 5: File lớn 2.5MB (vượt quá 1MB chunk)
    p5 = os.path.join(test_dir, "large_2_5mb.dat")
    with open(p5, "wb") as f:
        # Dùng byte có quy luật để deterministic
        pattern = b"NASWebDAV_Fast_Hasher_Block_2026_Pattern_" * 100
        for _ in range(500):
            f.write(pattern)
    cases.append(p5)
    
    # Ca 6: File không tồn tại (xử lý ngoại lệ)
    p6 = os.path.join(test_dir, "non_existent_file_12345.xyz")
    cases.append(p6)

    # Ghi nhận kết quả
    output_jsonl = os.path.join(os.path.dirname(__file__), "backend", "rust_modules", "nas_hasher", "cases.jsonl")
    os.makedirs(os.path.dirname(output_jsonl), exist_ok=True)
    
    with open(output_jsonl, "w", encoding="utf-8") as out_f:
        for p in cases:
            res = compute_python_hashes(p)
            out_f.write(json.dumps(res, ensure_ascii=False) + "\n")
            
    print(f"Recorded {len(cases)} test cases to: {output_jsonl}")

if __name__ == "__main__":
    main()
