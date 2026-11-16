#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Use this computer for NAS thumbnail generation or full-file duplicate hashes.

Set NAS_API_URL, NAS_WEBDAV_URL, NAS_USER and NAS_PASSWORD. Thumbnail jobs also
need ffmpeg on PATH. The NAS remains the source of truth; work and checkpoints
are read and written through its authenticated API.
"""
import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class ApiError(Exception):
    def __init__(self, status, detail):
        Exception.__init__(self, "NAS API %s: %s" % (status, detail))
        self.status = status


class Worker(object):
    def __init__(self, api_url, webdav_url, username, password, job, ffmpeg):
        self.api_url = api_url.rstrip("/")
        self.webdav_url = webdav_url.rstrip("/") + "/"
        self.job = job
        self.ffmpeg = ffmpeg
        raw = (username + ":" + password).encode("utf-8")
        self.authorization = "Basic " + base64.b64encode(raw).decode("ascii")
        self.owner = "pc-" + uuid.uuid4().hex[:20]
        self.lease = None
        self.revision = None
        self.cursor = ""
        self.completed = 0
        self.total = 0
        self.errors = 0

    def _request(self, url, method="GET", data=None, content_type="application/json", timeout=60):
        headers = {"Authorization": self.authorization}
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            detail = error.read(2048).decode("utf-8", errors="replace")
            raise ApiError(error.code, detail)

    def api(self, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        raw = self._request(self.api_url + path, "GET" if body is None else "POST", data)
        return json.loads(raw.decode("utf-8"))

    def cursor_revision(self):
        # The older duplicate cursor counted every file, including unique sizes.
        prefix = "equal-size:" if self.job == "duplicates" else ""
        return prefix + str(self.revision)

    def checkpoint(self, state="running", error=""):
        if not self.lease:
            return
        path = self.cursor.split("\n", 1)[-1] if self.cursor else ""
        cursor = (self.cursor_revision() + "\n" + path) if self.revision is not None else self.cursor
        self.api("/api/work/checkpoint", {
            "name": self.job, "owner": self.lease["owner"], "token": self.lease["token"],
            "cursor": cursor, "completed": self.completed, "total": self.total,
            "state": state, "error": error,
        })
        self.cursor = cursor

    def _remote_file_url(self, webdav_path):
        if not webdav_path.startswith("/webdav/"):
            raise ValueError("NAS returned a path outside /webdav")
        rel = webdav_path[8:]
        return self.webdav_url + urllib.parse.quote(rel, safe="/")

    def _download(self, webdav_path, path, heartbeat=False):
        req = urllib.request.Request(self._remote_file_url(webdav_path), headers={"Authorization": self.authorization})
        last_heartbeat = time.monotonic()
        with urllib.request.urlopen(req, timeout=45) as response, open(path, "wb") as output:
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                output.write(block)
                if heartbeat and time.monotonic() - last_heartbeat >= 5:
                    self.checkpoint()
                    last_heartbeat = time.monotonic()

    def _thumbnail(self, item, source_version=None):
        with tempfile.TemporaryDirectory(prefix="nas-thumb-") as temp:
            source = os.path.join(temp, "source.bin")
            output = os.path.join(temp, "thumbnail.jpg")
            self._download(item["path"], source, heartbeat=True)
            extension = os.path.splitext(item["name"])[1].lower()
            command = [self.ffmpeg, "-v", "error", "-y"]
            if extension in (".mp4", ".mkv", ".avi", ".mov", ".mpg", ".mpeg", ".wmv", ".flv", ".ts", ".m4v"):
                command.extend(["-ss", "1"])
            command.extend(["-i", source, "-frames:v", "1", "-vf",
                            "scale=480:480:force_original_aspect_ratio=decrease",
                            "-q:v", "2", output])
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
            if result.returncode or not os.path.isfile(output) or os.path.getsize(output) <= 0:
                raise RuntimeError("ffmpeg could not create a JPEG")
            with open(output, "rb") as image:
                payload = image.read(5 * 1024 * 1024 + 1)
            if len(payload) > 5 * 1024 * 1024:
                raise RuntimeError("JPEG exceeds NAS upload limit")
            boundary = "----NasWorker" + uuid.uuid4().hex
            body = ("--%s\r\nContent-Disposition: form-data; name=\"path\"\r\n\r\n%s\r\n" %
                    (boundary, item["path"])).encode("utf-8")
            if source_version:
                body += ("--%s\r\nContent-Disposition: form-data; name=\"source_version\"\r\n\r\n%s\r\n" %
                         (boundary, source_version)).encode("ascii")
            body += ("--%s\r\nContent-Disposition: form-data; name=\"thumb\"; filename=\"thumb.jpg\"\r\n"
                     "Content-Type: image/jpeg\r\n\r\n" % boundary).encode("ascii")
            body += payload + ("\r\n--%s--\r\n" % boundary).encode("ascii")
            self._request(self.api_url + "/api/thumb/upload", "POST", body,
                          "multipart/form-data; boundary=" + boundary)

    def _duplicate_hash(self, item):
        relative = item["path"][8:]
        info = self.api("/api/duplicates/file", {"path": relative})
        if info.get("sha256"):
            return
        hasher = hashlib.sha256()
        req = urllib.request.Request(self.api_url + "/api/duplicates/content",
                                     data=json.dumps({"path": relative, "version": info["version"]}).encode("utf-8"),
                                     headers={"Authorization": self.authorization, "Content-Type": "application/json"})
        last_heartbeat = time.monotonic()
        bytes_read = 0
        size = 0
        with urllib.request.urlopen(req, timeout=45) as response:
            if (response.headers.get("X-NAS-Version") != info["version"] or
                    response.headers.get("X-NAS-File-ID") != info["file_id"] or
                    response.headers.get("Content-Length") != str(info["size"])):
                raise ApiError(409, "source_version_changed")
            while True:
                block = response.read(4 * 1024 * 1024)
                if not block:
                    break
                hasher.update(block)
                size += len(block)
                bytes_read += len(block)
                if bytes_read >= 64 * 1024 * 1024:
                    self.checkpoint()
                    bytes_read = 0
                if time.monotonic() - last_heartbeat >= 5:
                    self.checkpoint()
                    last_heartbeat = time.monotonic()
        if size != info["size"]:
            raise ApiError(409, "incomplete_verification_stream")
        self.api("/api/duplicates/hash", {
            "path": relative, "version": info["version"], "sha256": hasher.hexdigest(),
        })

    def run_claimed_job(self):
        status = self.api("/api/work/status")
        if not status.get("index_ready"):
            raise ApiError(423, "catalogue_not_ready")
        self.revision = status["catalogue_revision"]
        jobs = dict((job["name"], job) for job in status.get("jobs", []))
        prior = jobs.get(self.job) or {}
        if prior.get("state") == "complete":
            return False
        if prior.get("state") in (None, "failed"):
            self.api("/api/work/request", {"name": self.job})
        self.lease = self.api("/api/work/claim", {"name": self.job, "owner": self.owner})
        if not self.lease.get("token"):
            return False
        saved_revision, separator, saved_path = self.lease.get("cursor", "").partition("\n")
        if separator and saved_revision == self.cursor_revision():
            self.cursor = self.lease["cursor"]
            self.completed = self.lease.get("completed", 0)
            self.total = max(self.lease.get("total", 0), self.completed)
        else:
            self.cursor = ""
            self.completed = 0
            self.total = self.lease.get("total", 0)
        page_cursor = saved_path if self.cursor else ""
        self.checkpoint()
        need_total = True
        while True:
            page = self.api("/api/work/files", {
                "name": self.job, "owner": self.lease["owner"], "token": self.lease["token"],
                "cursor": page_cursor, "limit": 256, "catalogue_revision": self.revision,
                "include_total": need_total,
            })
            if page["catalogue_revision"] != self.revision:
                raise ApiError(409, "catalogue_changed")
            if need_total:
                self.total = page["total"]
                need_total = False
            if self.job == "thumbnail":
                missing_result = self.api("/api/thumb/exists", {"paths": [item["path"] for item in page["files"]]})
                missing = set(missing_result["missing"])
            else:
                missing = None
            for item in page["files"]:
                item_error = None
                try:
                    if self.job == "thumbnail":
                        if item["path"] in missing:
                            self._thumbnail(item, missing_result.get("source_versions", {}).get(item["path"]))
                    else:
                        self._duplicate_hash(item)
                except ApiError as error:
                    if error.status == 423:
                        self.lease = None  # NAS has checkpointed and released this job.
                        raise
                    if error.status in (401, 403):
                        raise
                    self.errors += 1
                    item_error = error
                except Exception as error:
                    self.errors += 1
                    item_error = error
                if item_error is not None:
                    self.checkpoint("retry", "item_failed: %s" % item["path"][:160])
                    self.lease = None
                    raise ApiError(409, "item_failed: %s" % item["path"][:160])
                self.completed += 1
                relative = item["path"][8:]
                self.cursor = self.cursor_revision() + "\n" + relative
                if self.completed % 25 == 0 or self.completed == self.total:
                    self.checkpoint()
            if page["eof"]:
                break
            page_cursor = page["next_cursor"]
        if self.completed != self.total:
            self.checkpoint("retry", "catalogue_count_mismatch")
            raise ApiError(409, "catalogue_count_mismatch")
        error = "failed_files=%s" % self.errors if self.errors else ""
        self.checkpoint("complete", error)
        self.lease = None
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--job", choices=("thumbnail", "duplicates"), required=True)
    parser.add_argument("--watch", action="store_true", help="keep checking for queued work")
    parser.add_argument("--poll-seconds", type=int, default=30)
    args = parser.parse_args()
    api_url = os.environ.get("NAS_API_URL", "").strip()
    webdav_url = os.environ.get("NAS_WEBDAV_URL", "").strip()
    username = os.environ.get("NAS_USER", "").strip()
    password = os.environ.get("NAS_PASSWORD", "")
    if not all((api_url, webdav_url, username, password)):
        parser.error("Set NAS_API_URL, NAS_WEBDAV_URL, NAS_USER and NAS_PASSWORD")
    ffmpeg = os.environ.get("FFMPEG_EXE", "ffmpeg")
    if args.job == "thumbnail" and shutil.which(ffmpeg) is None:
        parser.error("ffmpeg was not found; set FFMPEG_EXE or add it to PATH")
    while True:
        worker = Worker(api_url, webdav_url, username, password, args.job, ffmpeg)
        try:
            completed = worker.run_claimed_job()
            if completed:
                print("%s job complete: %s/%s items, %s item errors" %
                      (args.job, worker.completed, worker.total, worker.errors))
                if not args.watch:
                    return 0
            elif not args.watch:
                print("No claimable work; NAS may be busy or the job is already complete.")
                return 2
        except KeyboardInterrupt:
            if worker.lease:
                try:
                    worker.checkpoint("paused", "computer_worker_stopped")
                except Exception:
                    pass
            return 130
        except ApiError as error:
            if worker.lease:
                try:
                    worker.checkpoint("retry", str(error))
                    worker.lease = None
                except Exception:
                    pass
            print(str(error))
            if error.status not in (409, 423) or not args.watch:
                return 1
        except Exception as error:
            if worker.lease:
                try:
                    worker.checkpoint("retry", str(error))
                except Exception:
                    pass
            print("NAS computer worker error: %s" % error)
            if not args.watch:
                return 1
        if not args.watch:
            return 0
        time.sleep(max(5, args.poll_seconds))


if __name__ == "__main__":
    raise SystemExit(main())
