#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
record_fast_index_cases.py
Giai đoạn RECORD theo chuẩn rust-anything:
Tạo SQLite database mẫu kiểm thử với các trường hợp biên và ghi nhận output
nguyên bản của Python generate_fast_index làm chân lý mặt đất (ground truth).
"""

import os
import sys
import json
import sqlite3
import tempfile

def create_sample_index_db(db_path):
    """Tạo database mẫu với đầy đủ schema và dữ liệu biên đa dạng."""
    if os.path.exists(db_path):
        os.remove(db_path)
        
    conn = sqlite3.connect(db_path)
    conn.executescript("""
    CREATE TABLE index_state (
        id INTEGER PRIMARY KEY,
        root TEXT NOT NULL,
        generation INTEGER NOT NULL,
        ready INTEGER NOT NULL,
        incomplete_reason TEXT,
        built INTEGER DEFAULT 0,
        scanned_at REAL DEFAULT 0,
        catalogue_revision INTEGER DEFAULT 1
    );

    CREATE TABLE index_directories (
        path TEXT PRIMARY KEY,
        stamp TEXT,
        complete INTEGER DEFAULT 1
    );

    CREATE TABLE file_index (
        path TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        name_no_accent TEXT NOT NULL,
        is_dir INTEGER NOT NULL,
        size INTEGER NOT NULL,
        mtime INTEGER NOT NULL,
        mime TEXT NOT NULL,
        parent TEXT NOT NULL
    );
    """)

    # Nạp index_state
    conn.execute("""
    INSERT INTO index_state (id, root, generation, ready, incomplete_reason, built, scanned_at, catalogue_revision)
    VALUES (1, '/srv/dev-disk-data/webdav', 42, 1, '', 1, 1795096000.0, 105)
    """)

    # Nạp 10 file mẫu với các trường hợp biên
    sample_files = [
        # (path, name, name_no_accent, is_dir, size, mtime, mime, parent)
        ("docs/baocao.pdf", "baocao.pdf", "baocao.pdf", 0, 1048576, 1795000000000, "application/pdf", "docs/"),
        ("docs/Tài liệu tiếng Việt có dấu.docx", "Tài liệu tiếng Việt có dấu.docx", "tai lieu tieng viet co dau.docx", 0, 524288, 1795000100000, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docs/"),
        ("photos/IMG_2026.HEIC", "IMG_2026.HEIC", "img_2026.heic", 0, 3145728, 1795000200000, "image/heic", "photos/"),
        ("videos/clip 01 [HD].mp4", "clip 01 [HD].mp4", "clip 01 [hd].mp4", 0, 104857600, 1795000300000, "video/mp4", "videos/"),
        ("empty_file.bin", "empty_file.bin", "empty_file.bin", 0, 0, 1795000400000, "application/octet-stream", ""),
        ("special/!@#$%^&()_+={}.dat", "!@#$%^&()_+={}.dat", "!@#$%^&()_+={}.dat", 0, 12345, 1795000500000, "application/octet-stream", "special/"),
        ("large_archive.tar.gz", "large_archive.tar.gz", "large_archive.tar.gz", 0, 4294967296, 1795000600000, "application/gzip", ""),
    ]

    for f in sample_files:
        conn.execute("INSERT INTO file_index VALUES (?, ?, ?, ?, ?, ?, ?, ?)", f)

    conn.commit()
    conn.close()


def generate_python_fast_index_string(db_path):
    """Mô phỏng logic gốc của generate_fast_index trên db_path."""
    conn = sqlite3.connect(db_path)
    try:
        state = conn.execute('SELECT root,generation,ready,scanned_at,catalogue_revision FROM index_state WHERE id=1').fetchone()
        total = conn.execute('SELECT COUNT(*) FROM file_index WHERE is_dir=0').fetchone()[0]
        metadata = {
            'catalogue_protocol': 1,
            'source': 'file_index',
            'index_ready': True,
            'index_generation': state[1],
            'catalogue_updated_at': state[3],
            'catalogue_revision': state[4],
            'truncated': False,
            'total': total
        }
        
        output_chunks = []
        output_chunks.append(json.dumps(metadata, ensure_ascii=False)[:-1] + ',"files":[')
        
        cursor = conn.execute('SELECT path,name,size,mtime,mime FROM file_index NOT INDEXED WHERE is_dir=0')
        first = True
        while True:
            rows = cursor.fetchmany(512)
            if not rows:
                break
            for path, name, size, mtime, mime in rows:
                chunk = ('' if first else ',') + json.dumps({
                    'name': name,
                    'path': '/webdav/' + path,
                    'size': size,
                    'mtime': mtime,
                    'mime': mime
                }, ensure_ascii=False)
                output_chunks.append(chunk)
                first = False
        output_chunks.append(']}')
        return ''.join(output_chunks)
    finally:
        conn.close()


def main():
    target_dir = os.path.join(os.path.dirname(__file__), "backend", "rust_modules", "nas_indexer")
    os.makedirs(target_dir, exist_ok=True)
    
    db_path = os.path.join(target_dir, "sample_index.db")
    create_sample_index_db(db_path)
    
    ground_truth = generate_python_fast_index_string(db_path)
    ground_truth_path = os.path.join(target_dir, "ground_truth.json")
    
    with open(ground_truth_path, "w", encoding="utf-8") as f:
        f.write(ground_truth)
        
    print(f"Sample DB created at: {db_path}")
    print(f"Ground truth recorded ({len(ground_truth)} bytes) at: {ground_truth_path}")
    print("Preview ground truth:")
    print(ground_truth[:200] + " ... " + ground_truth[-50:])

if __name__ == "__main__":
    main()
