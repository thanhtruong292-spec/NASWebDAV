# -*- coding: utf-8 -*-
"""
Tests for Rust-accelerated safe zip compression and decompression.
"""
import os
import sys
import tempfile
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "backend"))
import rust_bridge


def test_rust_zip_compression_and_extraction():
    with tempfile.TemporaryDirectory() as tmp_dir:
        src_dir = os.path.join(tmp_dir, "my_folder")
        os.makedirs(src_dir)
        dest_extract = os.path.join(tmp_dir, "extracted")
        os.makedirs(dest_extract)
        zip_file = os.path.join(tmp_dir, "archive.zip")

        # Tạo file mẫu
        with open(os.path.join(src_dir, "file1.txt"), "w") as f:
            f.write("Content of file 1")
        sub_dir = os.path.join(src_dir, "sub")
        os.makedirs(sub_dir)
        with open(os.path.join(sub_dir, "file2.txt"), "w") as f:
            f.write("Content of nested file 2")

        # Test nén zip
        create_res = rust_bridge.create_zip_from_dir_rust(src_dir, zip_file)
        assert create_res is not None
        assert create_res["files_compressed"] == 2
        assert os.path.isfile(zip_file)
        assert create_res["zip_size"] > 0

        # Test giải nén zip
        extracted_count = rust_bridge.extract_zip_safe_rust(zip_file, dest_extract)
        assert extracted_count == 2

        with open(os.path.join(dest_extract, "my_folder", "file1.txt"), "r") as f:
            assert f.read() == "Content of file 1"
        with open(os.path.join(dest_extract, "my_folder", "sub", "file2.txt"), "r") as f:
            assert f.read() == "Content of nested file 2"


def test_api_zip_and_unzip_endpoints(monkeypatch):
    import nas_api_server

    with tempfile.TemporaryDirectory() as tmp_dir:
        public_dir = os.path.join(tmp_dir, "webdav_root")
        os.makedirs(public_dir)

        monkeypatch.setattr(nas_api_server, "_get_webdav_public_dir", lambda: public_dir)
        monkeypatch.setattr(nas_api_server, "get_webdav_root", lambda: public_dir)
        monkeypatch.setattr(nas_api_server, "WEBDAV_FILE_ROOT", public_dir)
        monkeypatch.setattr(nas_api_server, "_ip_in_whitelist", lambda ip: True)

        folder_to_zip = os.path.join(public_dir, "my_docs")
        os.makedirs(folder_to_zip)
        with open(os.path.join(folder_to_zip, "doc.txt"), "w") as f:
            f.write("Important Document")

        client = nas_api_server.app.test_client()

        # 1. Test POST /api/zip
        resp = client.post("/api/zip", json={"path": "/webdav/my_docs"})
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["result"] == "ok"
        assert data["files_compressed"] == 1
        zip_path = os.path.join(public_dir, "my_docs.zip")
        assert os.path.isfile(zip_path)

        # Xoa thu muc goc de chuan bi test unzip
        import shutil
        shutil.rmtree(folder_to_zip)
        assert not os.path.exists(folder_to_zip)

        # 2. Test POST /api/unzip
        unzip_resp = client.post("/api/unzip", json={"path": "/webdav/my_docs.zip"})
        assert unzip_resp.status_code == 200
        unzip_data = unzip_resp.get_json()
        assert unzip_data["result"] == "ok"
        assert unzip_data["extracted"] >= 1
        assert os.path.isfile(os.path.join(folder_to_zip, "doc.txt"))
