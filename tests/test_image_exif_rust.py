# -*- coding: utf-8 -*-
"""
Tests for Rust-accelerated EXIF metadata extraction and batch endpoints.
"""
import os
import sys
import tempfile
import pytest
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
import rust_bridge


def test_exif_extraction_and_fallback():
    with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as f:
        path = f.name

    try:
        # Tạo ảnh JPEG có nhúng thông tin EXIF qua Pillow
        img = Image.new("RGB", (32, 32), (100, 150, 200))
        exif_data = img.getexif()
        # 0x010f: Make, 0x0110: Model, 0x0132: DateTime, 0x9003: DateTimeOriginal
        exif_data[0x010f] = "TestCamera"
        exif_data[0x0110] = "ModelAlpha"
        exif_data[0x0132] = "2026:11:19 23:59:00"
        exif_data[0x9003] = "2026:11:19 23:59:00"
        img.save(path, "JPEG", exif=exif_data)

        meta = rust_bridge.read_image_exif(path)
        assert meta is not None
        assert meta.get("camera_make") == "TestCamera"
        assert meta.get("camera_model") == "ModelAlpha"
        assert meta.get("date_taken") == "2026-11-19 23:59:00"

        # Test batch
        batch = rust_bridge.read_batch_exif([path], workers=2)
        assert path in batch
        assert batch[path].get("camera_make") == "TestCamera"
    finally:
        if os.path.exists(path):
            os.remove(path)


def test_media_exif_endpoints(monkeypatch):
    import nas_api_server

    with tempfile.TemporaryDirectory() as tmp_dir:
        public_dir = os.path.join(tmp_dir, "webdav_root")
        os.makedirs(public_dir)

        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: public_dir)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: True)

        img_path = os.path.join(public_dir, "vacation.jpg")
        img = Image.new("RGB", (32, 32), (120, 180, 220))
        exif_data = img.getexif()
        exif_data[0x010f] = "Apple"
        exif_data[0x0110] = "iPhone 15 Pro"
        exif_data[0x9003] = "2026:11:19 12:00:00"
        img.save(img_path, "JPEG", exif=exif_data)

        client = nas_api_server.app.test_client()

        # Test GET /api/media/exif
        resp = client.get("/api/media/exif?path=/webdav/vacation.jpg")
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["ok"] is True
        assert data["path"] == "/webdav/vacation.jpg"
        assert data["exif"] is not None
        assert data["exif"]["camera_make"] == "Apple"
        assert data["exif"]["camera_model"] == "iPhone 15 Pro"

        # Test POST /api/media/exif/batch
        batch_resp = client.post("/api/media/exif/batch", json={"paths": ["/webdav/vacation.jpg"]})
        assert batch_resp.status_code == 200
        batch_data = batch_resp.get_json()
        assert batch_data["ok"] is True
        assert "/webdav/vacation.jpg" in batch_data["results"]
        assert batch_data["results"]["/webdav/vacation.jpg"]["camera_make"] == "Apple"
