"""Computer worker checkpoints and encodes paths without touching NAS data directly."""
import pytest
import hashlib
import io
import json

from scripts.nas_computer_worker import ApiError, Worker


@pytest.mark.parametrize("version,content,valid", [("v", b"abc", True), ("old", b"abc", False), ("v", b"a", False)])
def test_hash_publishes_only_a_complete_versioned_stream(monkeypatch, version, content, valid):
    worker = Worker("http://nas", "http://webdav", "u", "p", "duplicates", "")
    published = []
    info = {"file_id": "id", "size": 3, "version": "v"}
    worker.api = lambda route, body: info if route.endswith("/file") else published.append(body)
    def download(req, timeout):
        assert req.full_url == "http://nas/api/duplicates/content"
        assert json.loads(req.data.decode()) == {"path": "abc", "version": "v"}
        stream = io.BytesIO(content)
        stream.headers = {"X-NAS-Version": version, "X-NAS-File-ID": "id", "Content-Length": "3"}
        return stream
    monkeypatch.setattr("urllib.request.urlopen", download)
    if valid:
        worker._duplicate_hash({"path": "/webdav/abc"})
        assert published[0]["sha256"] == hashlib.sha256(content).hexdigest()
    else:
        with pytest.raises(ApiError):
            worker._duplicate_hash({"path": "/webdav/abc"})
        assert published == []


def test_webdav_paths_are_encoded_per_segment():
    worker = Worker("http://nas:5050", "http://nas:8822/webdav/", "user", "secret", "duplicates", "ffmpeg")
    assert worker._remote_file_url("/webdav/Ảnh mới/a+b%.jpg") == (
        "http://nas:8822/webdav/%E1%BA%A2nh%20m%E1%BB%9Bi/a%2Bb%25.jpg")


def test_checkpoint_renews_the_lease_and_keeps_catalogue_revision_in_cursor():
    worker = Worker("http://nas:5050", "http://nas:8822/webdav/", "user", "secret", "thumbnail", "ffmpeg")
    worker.lease = {"owner": "client:pc", "token": "opaque"}
    worker.revision = 17
    worker.cursor = "17\nfolder/a.jpg"
    worker.completed = 25
    worker.total = 80
    requests = []
    worker.api = lambda path, body: requests.append((path, body)) or {"accepted": True}
    worker.checkpoint()
    assert requests[0][0] == "/api/work/checkpoint"
    assert requests[0][1]["cursor"] == "17\nfolder/a.jpg"
    assert requests[0][1]["completed"] == 25
    assert requests[0][1]["total"] == 80


def test_failed_file_stays_retryable_and_never_marks_the_job_complete():
    worker = Worker("http://nas:5050", "http://nas:8822/webdav/", "user", "secret", "duplicates", "ffmpeg")
    calls = []

    def api(path, body=None):
        calls.append((path, body))
        if path == "/api/work/status":
            return {"index_ready": True, "catalogue_revision": 17, "jobs": []}
        if path == "/api/work/claim":
            return {"owner": "client:pc", "token": "opaque", "cursor": "", "completed": 0,
                    "total": 1, "revision": 2, "catalogue_revision": 17}
        if path == "/api/work/files":
            return {"catalogue_revision": 17, "total": 1, "eof": True, "next_cursor": "a.jpg",
                    "files": [{"path": "/webdav/a.jpg", "name": "a.jpg", "size": 10, "mtime": 20}]}
        if path == "/api/work/checkpoint":
            return {"accepted": True}
        return {}

    worker.api = api
    worker._duplicate_hash = lambda _item: (_ for _ in ()).throw(RuntimeError("read failed"))
    with pytest.raises(ApiError, match="item_failed"):
        worker.run_claimed_job()

    checkpoints = [body for path, body in calls if path == "/api/work/checkpoint"]
    assert checkpoints[-1]["state"] == "retry"
    assert checkpoints[-1]["completed"] == 0
    assert checkpoints[-1]["cursor"] == "equal-size:17\n"
    assert all(body["state"] != "complete" for body in checkpoints)


@pytest.mark.parametrize("saved_revision,expected_cursor", [(16, ""), (17, "b.jpg")])
def test_resume_uses_path_only_for_the_current_catalogue(saved_revision, expected_cursor):
    worker = Worker("http://nas", "http://nas/webdav/", "user", "secret", "duplicates", "ffmpeg")
    def api(path, body=None):
        if path == "/api/work/status":
            return {"index_ready": True, "catalogue_revision": 17, "jobs": []}
        if path == "/api/work/claim":
            return {"owner": "pc", "token": "opaque", "cursor": "equal-size:%s\nb.jpg" % saved_revision,
                    "completed": 1, "total": 2, "catalogue_revision": 17}
        if path == "/api/work/files":
            assert body["cursor"] == expected_cursor
            names = ["a.jpg", "b.jpg"] if not expected_cursor else ["c.jpg"]
            return {"catalogue_revision": 17, "total": 2, "eof": True,
                    "files": [{"path": "/webdav/" + name} for name in names]}
        return {}

    worker.api = api
    worker._duplicate_hash = lambda item: None
    assert worker.run_claimed_job()
    assert worker.completed == 2
