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


def test_backup_multi_device_sessions_and_queueing(client, monkeypatch):
    import nas_api_server
    monkeypatch.setattr("nas_api_server.check_auth", lambda u, p: True)
    with nas_api_server._BACKUP_SESSIONS_LOCK:
        nas_api_server._BACKUP_SESSIONS.clear()

    auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}

    # May 1 ket noi -> duoc cap slot, pace = 0
    r1 = client.post(
        "/api/backup/session/heartbeat",
        data=json.dumps({
            "device_id": "dev-1",
            "device_name": "Galaxy S23",
            "backup_mode": "image",
            "status": "running",
            "processed": 10,
            "total": 100,
            "speed_bps": 1024000,
            "freed_bytes": 5000000,
        }),
        headers=auth_headers,
        content_type="application/json",
    )
    assert r1.status_code == 200
    b1 = json.loads(r1.data.decode("utf-8"))
    assert b1["slot_granted"] is True
    assert b1["queue_position"] == 0
    assert b1["active_count"] == 1
    assert b1["recommended_pace_ms"] == 0

    # May 2 ket noi -> duoc cap slot thu 2, pace = 250ms de chia bang thong
    r2 = client.post(
        "/api/backup/session/heartbeat",
        data=json.dumps({
            "device_id": "dev-2",
            "device_name": "Pixel 8",
            "backup_mode": "video",
            "status": "running",
            "processed": 2,
            "total": 20,
            "speed_bps": 2048000,
        }),
        headers=auth_headers,
        content_type="application/json",
    )
    assert r2.status_code == 200
    b2 = json.loads(r2.data.decode("utf-8"))
    assert b2["slot_granted"] is True
    assert b2["active_count"] == 2
    assert b2["recommended_pace_ms"] == 250

    # May 3 ket noi -> xep hang vi tri #1 de tranh tac nghen NAS RK3328
    r3 = client.post(
        "/api/backup/session/heartbeat",
        data=json.dumps({
            "device_id": "dev-3",
            "device_name": "Xiaomi 14",
            "backup_mode": "both",
            "status": "running",
        }),
        headers=auth_headers,
        content_type="application/json",
    )
    assert r3.status_code == 200
    b3 = json.loads(r3.data.decode("utf-8"))
    assert b3["slot_granted"] is False
    assert b3["queue_position"] == 1
    assert b3["recommended_pace_ms"] == 2500

    # GET /api/backup/sessions liet ke du 3 may va tong toc do 2 may dang chay
    rs = client.get("/api/backup/sessions", headers=auth_headers)
    assert rs.status_code == 200
    bs = json.loads(rs.data.decode("utf-8"))
    assert bs["active_count"] == 2
    assert len(bs["sessions"]) == 3
    assert bs["combined_speed_bps"] == 1024000 + 2048000

    # May 1 finish -> may 3 duoc len slot ngay lap tuc
    rf = client.post(
        "/api/backup/session/finish",
        data=json.dumps({"device_id": "dev-1"}),
        headers=auth_headers,
        content_type="application/json",
    )
    assert rf.status_code == 200
    rs2 = client.get("/api/backup/sessions", headers=auth_headers)
    bs2 = json.loads(rs2.data.decode("utf-8"))
    by_id = {s["device_id"]: s for s in bs2["sessions"]}
    assert "dev-1" not in by_id
    assert by_id["dev-3"]["slot_granted"] is True
    assert by_id["dev-3"]["queue_position"] == 0


def test_backup_resumable_chunked_upload_resume_and_sha256(client, monkeypatch, tmpdir):
    webdav_root = str(tmpdir.mkdir("webdav_resumable"))
    monkeypatch.setattr("nas_api_server._get_webdav_root", lambda: webdav_root)
    monkeypatch.setattr("nas_api_server._get_webdav_public_dir", lambda: webdav_root)
    monkeypatch.setattr("nas_api_server.check_auth", lambda u, p: True)

    auth_headers = {"Authorization": "Basic dGVzdDp0ZXN0"}
    part1 = b"A" * 1024
    part2 = b"B" * 2048
    full_data = part1 + part2
    sha = hashlib.sha256(full_data).hexdigest()

    # 1. Status ban dau = 0
    r0 = client.get("/api/backup/resumable/status?upload_id=" + sha, headers=auth_headers)
    assert r0.status_code == 200
    assert json.loads(r0.data.decode("utf-8"))["offset"] == 0

    # 2. Gui chunk 1 (offset 0)
    r1 = client.post(
        "/api/backup/resumable/chunk?upload_id=%s&offset=0" % sha,
        data=part1,
        headers=auth_headers,
        content_type="application/octet-stream",
    )
    assert r1.status_code == 200
    assert json.loads(r1.data.decode("utf-8"))["offset"] == len(part1)

    # 3. Gia lap rot mang -> gui sai offset -> tra 409 kem expected_offset
    r_bad = client.post(
        "/api/backup/resumable/chunk?upload_id=%s&offset=0" % sha,
        data=part1,
        headers=auth_headers,
        content_type="application/octet-stream",
    )
    assert r_bad.status_code == 409
    assert json.loads(r_bad.data.decode("utf-8"))["expected_offset"] == len(part1)

    # 4. Resume tu dung offset len(part1)
    r2 = client.post(
        "/api/backup/resumable/chunk?upload_id=%s&offset=%d" % (sha, len(part1)),
        data=part2,
        headers=auth_headers,
        content_type="application/octet-stream",
    )
    assert r2.status_code == 200
    assert json.loads(r2.data.decode("utf-8"))["offset"] == len(full_data)

    # 5. Finish -> verify SHA-256 va move sang dich AutoBackup/DCIM/Screen recordings/
    r_fin = client.post(
        "/api/backup/resumable/finish",
        data=json.dumps({
            "upload_id": sha,
            "dest": "/AutoBackup/DCIM/Screen recordings",
            "filename": "Screen_Recording_1GB__id10001.mp4",
            "size": len(full_data),
            "sha256": sha,
        }),
        headers=auth_headers,
        content_type="application/json",
    )
    assert r_fin.status_code == 200
    fin_body = json.loads(r_fin.data.decode("utf-8"))
    assert fin_body["ok"] is True
    assert fin_body["sha256"] == sha

    final_file = os.path.join(webdav_root, "AutoBackup", "DCIM", "Screen recordings", "Screen_Recording_1GB__id10001.mp4")
    assert os.path.isfile(final_file)
    with open(final_file, "rb") as f:
        assert f.read() == full_data

    # 6. Thu upload lai cung filename voi noi dung khac -> BAT BUOC KHONG DOI TEN, BO QUA (SKIP)
    diff_data = b"DIFFERENT_CONTENT_FOR_SAME_FILENAME_12345"
    diff_sha = hashlib.sha256(diff_data).hexdigest()
    client.post(
        "/api/backup/resumable/chunk?upload_id=%s&offset=0" % diff_sha,
        data=diff_data,
        headers=auth_headers,
        content_type="application/octet-stream",
    )
    r_fin2 = client.post(
        "/api/backup/resumable/finish",
        data=json.dumps({
            "upload_id": diff_sha,
            "dest": "/AutoBackup/DCIM/Screen recordings",
            "filename": "Screen_Recording_1GB__id10001.mp4",
            "size": len(diff_data),
            "sha256": diff_sha,
        }),
        headers=auth_headers,
        content_type="application/json",
    )
    assert r_fin2.status_code == 200
    fin_body2 = json.loads(r_fin2.data.decode("utf-8"))
    assert fin_body2["ok"] is True
    assert fin_body2.get("status") == "ok"
    assert fin_body2.get("skipped") is True
    assert fin_body2["filename"] == "Screen_Recording_1GB__id10001.mp4"
    # Dam bao khong co bat ky file __v nao duoc tao ra
    dest_folder = os.path.join(webdav_root, "AutoBackup", "DCIM", "Screen recordings")
    file_list = os.listdir(dest_folder)
    assert len(file_list) == 1
    assert file_list[0] == "Screen_Recording_1GB__id10001.mp4"
    assert "__v" not in file_list[0]
