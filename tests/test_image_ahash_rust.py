# -*- coding: utf-8 -*-
"""
Tests for Rust-accelerated perceptual image aHash and similarity detection.
"""
import os
import sys
import tempfile
import pytest
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
import rust_bridge


def test_ahash_computation_and_parity():
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f1, \
         tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f2:
        p1 = f1.name
        p2 = f2.name

    try:
        # Anh 1: nua tren trang, nua duoi den (16x16)
        img1 = Image.new("RGB", (16, 16), (0, 0, 0))
        for y in range(8):
            for x in range(16):
                img1.putpixel((x, y), (255, 255, 255))
        img1.save(p1)

        # Anh 2: nua tren den, nua duoi trang (16x16)
        img2 = Image.new("RGB", (16, 16), (255, 255, 255))
        for y in range(8):
            for x in range(16):
                img2.putpixel((x, y), (0, 0, 0))
        img2.save(p2)

        h1 = rust_bridge.compute_image_ahash(p1)
        h2 = rust_bridge.compute_image_ahash(p2)

        assert h1 is not None and len(h1) == 16
        assert h2 is not None and len(h2) == 16
        # Anh 1 nua tren trang -> 32 bit dau la 1
        assert h1 == "ffffffff00000000"
        assert h2 == "00000000ffffffff"

        # Hamming distance giua 2 anh tuong phan phai la 64 bit
        dist = rust_bridge.ahash_hamming_distance(h1, h2)
        assert dist == 64

        # Hamming distance voi chinh no phai la 0
        assert rust_bridge.ahash_hamming_distance(h1, h1) == 0

        # Test batch
        batch = rust_bridge.compute_batch_ahash([p1, p2], workers=2)
        assert batch[p1] == "ffffffff00000000"
        assert batch[p2] == "00000000ffffffff"
    finally:
        if os.path.exists(p1):
            os.remove(p1)
        if os.path.exists(p2):
            os.remove(p2)


def test_fingerprint_api_compute_and_similar(monkeypatch):
    import nas_api_server
    import sqlite3
    import json

    with tempfile.TemporaryDirectory() as tmp_dir:
        db_path = os.path.join(tmp_dir, "test_index.db")
        public_dir = os.path.join(tmp_dir, "webdav_root")
        os.makedirs(public_dir)

        # Mock database và đường dẫn WebDAV
        monkeypatch.setattr(nas_api_server, "INDEX_DB_PATH", db_path)
        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: public_dir)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: True)

        # Tạo 2 ảnh thực tế trong webdav_root
        img1_path = os.path.join(public_dir, "photo1.png")
        img2_path = os.path.join(public_dir, "photo2.png")

        img1 = Image.new("RGB", (16, 16), (255, 255, 255))
        img1.save(img1_path)

        # Photo 2 gần giống Photo 1 (chỉ lệch 1 pixel)
        img2 = Image.new("RGB", (16, 16), (255, 255, 255))
        img2.putpixel((0, 0), (0, 0, 0))
        img2.save(img2_path)

        # Tạo bảng và chèn vào file_index
        conn = sqlite3.connect(db_path)
        conn.execute("CREATE TABLE file_index (path TEXT PRIMARY KEY, name TEXT, size INTEGER, mtime INTEGER, is_dir INTEGER, mime TEXT)")
        conn.execute("INSERT INTO file_index VALUES ('photo1.png', 'photo1.png', ?, ?, 0, 'image/png')", (os.path.getsize(img1_path), int(os.path.getmtime(img1_path)*1000)))
        conn.execute("INSERT INTO file_index VALUES ('photo2.png', 'photo2.png', ?, ?, 0, 'image/png')", (os.path.getsize(img2_path), int(os.path.getmtime(img2_path)*1000)))
        conn.commit()
        conn.close()

        # Gọi endpoint /api/fingerprint/compute
        client = nas_api_server.app.test_client()
        resp = client.post("/api/fingerprint/compute", json={"limit": 10}, headers={"X-Admin-Token": "test"})
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["result"] == "ok"
        assert data["computed"] == 2
        assert "/webdav/photo1.png" in data["results"]
        assert "/webdav/photo2.png" in data["results"]

        # Gọi endpoint /api/fingerprint/similar
        sim_resp = client.get("/api/fingerprint/similar?path=/webdav/photo1.png&threshold=5", headers={"X-Admin-Token": "test"})
        assert sim_resp.status_code == 200
        sim_data = sim_resp.get_json()
        assert sim_data["target"] == "/webdav/photo1.png"
        assert sim_data["total_matches"] == 1
        assert sim_data["matches"][0]["path"] == "/webdav/photo2.png"
        assert sim_data["matches"][0]["hamming_distance"] <= 5

