# -*- coding: utf-8 -*-
import io
import os
import sys
import tarfile
import hashlib
import json
import pytest

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BACKEND_DIR = os.path.join(ROOT_DIR, "backend")
for p in (ROOT_DIR, BACKEND_DIR):
    if p not in sys.path:
        sys.path.insert(0, p)

from nas_api_server import app


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client


def _create_tar_bytes(files_dict):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w") as tf:
        for name, data in files_dict.items():
            ti = tarfile.TarInfo(name=name)
            ti.size = len(data)
            ti.mode = 0o644
            tf.addfile(ti, io.BytesIO(data))
    return out.getvalue()


def test_backup_batch_stream_requires_admin(client):
    tar_bytes = _create_tar_bytes({"test.txt": b"hello"})
    res = client.post("/api/backup/batch_stream", data=tar_bytes)
    assert res.status_code == 401


def test_backup_batch_stream_extracts_and_computes_sha256(client, monkeypatch, tmpdir):
    webdav_root = str(tmpdir.mkdir("webdav_public"))
    monkeypatch.setattr("nas_api_server._get_webdav_root", lambda: webdav_root)
    monkeypatch.setattr("nas_api_server.check_auth", lambda u, p: True)

    auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}

    data1 = b"IMAGE_001_SAMPLE_PAYLOAD_DATA"
    data2 = b"VIDEO_002_SECOND_PAYLOAD_FOR_TESTING"
    sha1 = hashlib.sha256(data1).hexdigest()
    sha2 = hashlib.sha256(data2).hexdigest()

    files = {
        "DCIM/Camera/IMG_001.jpg": data1,
        "Pictures/Facebook/FB_002.jpg": data2
    }
    tar_payload = _create_tar_bytes(files)

    res = client.post(
        "/api/backup/batch_stream?dest=/AutoBackup",
        data=tar_payload,
        headers=auth_headers,
        content_type="application/x-tar"
    )
    assert res.status_code == 200
    body = json.loads(res.data.decode("utf-8"))
    assert body.get("ok") is True
    assert body.get("files_extracted") == 2
    assert body.get("bytes_extracted") == len(data1) + len(data2)

    items = body.get("items", [])
    assert len(items) == 2
    paths = {it["path"]: it for it in items}
    assert "DCIM/Camera/IMG_001.jpg" in paths
    assert paths["DCIM/Camera/IMG_001.jpg"]["size"] == len(data1)
    assert paths["DCIM/Camera/IMG_001.jpg"]["sha256"] == sha1

    assert "Pictures/Facebook/FB_002.jpg" in paths
    assert paths["Pictures/Facebook/FB_002.jpg"]["size"] == len(data2)
    assert paths["Pictures/Facebook/FB_002.jpg"]["sha256"] == sha2

    # Kiem tra file that duoc ghi tren disk
    dest_file1 = os.path.join(webdav_root, "AutoBackup", "DCIM", "Camera", "IMG_001.jpg")
    assert os.path.isfile(dest_file1)
    with open(dest_file1, "rb") as f:
        assert f.read() == data1
