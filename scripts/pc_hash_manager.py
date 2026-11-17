#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Quan ly file NAS qua hash + tim file trung lap — chay tren PC.

Thay vie tu tu duyet WebDAV BFS thu cong + gui mtime=0, script nay dung
coordinator API chuan hoa cua NAS (/api/work/claim, /api/work/checkpoint,
/api/work/status) de lay trang cac file can hash qua lease/checkpoint.
Moi ket qua hash duoc gan source_version (version cua file tai thoi diem
hash) va gui len /api/hash/save, NAS luu vao bang verified_file_hashes
cung DB voi file_index (nas_index.db).

Dung:
    python scripts/pc_hash_manager.py --host http://192.168.100.254:5050 \
        --webdav-url http://192.168.100.254:8822/webdav \
        --user <user> --pass <pass> [--job hash] [--workers 4] [--min-size 4096]
        [--dry-run] [--report duplicates.json]
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

try:
    import requests
except ImportError:
    sys.exit("Thieu 'requests' — chay: pip install requests")


class ApiError(Exception):
    def __init__(self, status, detail):
        Exception.__init__(self, "NAS API %s: %s" % (status, detail))
        self.status = status
        self.detail = detail


SKIP_DIRS = {".thumbs", ".thumbnails", ".trash", ".nas_meta", ".git",
             ".recycle", "@eadir", "#recycle", ".cache"}


def _log(*a):
    print(*a, flush=True)


def webdav_rel(webdav_path):
    """Chuyen duong /webdav/... thanh relative path (fay dang cua NAS)."""
    if webdav_path.startswith("/webdav/"):
        return webdav_path[len("/webdav/"):]
    return webdav_path.lstrip("/")


class NasClient(object):
    def __init__(self, api_url, webdav_url, username, password):
        self.api_url = api_url.rstrip("/")
        self.webdav_url = webdav_url.rstrip("/") + "/"
        raw = (username + ":" + password).encode("utf-8")
        self.authorization = "Basic " + __import__("base64").b64encode(raw).decode("ascii")

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

    def webdav_get(self, rel_path, stream=False):
        url = self.webdav_url + urllib.parse.quote(rel_path, safe="/")
        req = urllib.request.Request(url, headers={"Authorization": self.authorization})
        return urllib.request.urlopen(req, timeout=600)


def claim_work(client, job_name, owner):
    status = client.api("/api/work/status")
    if not status.get("index_ready"):
        raise ApiError(423, "catalogue_not_ready")
    revision = status["catalogue_revision"]
    jobs = dict((job["name"], job) for job in status.get("jobs", []))
    prior = jobs.get(job_name) or {}
    if prior.get("state") == "complete":
        return None, revision, 0, 0, ""
    if prior.get("state") in (None, "failed"):
        client.api("/api/work/request", {"name": job_name})
    lease = client.api("/api/work/claim", {"name": job_name, "owner": owner})
    if not lease.get("token"):
        return None, revision, 0, 0, ""
    saved_cursor = lease.get("cursor", "")
    revision_str, sep, saved_path = saved_cursor.partition("\n")
    if sep and revision_str == str(revision):
        cursor = saved_cursor
        completed = lease.get("completed", 0)
        total = max(lease.get("total", 0), completed)
    else:
        cursor = ""
        completed = 0
        total = lease.get("total", 0)
    client.api("/api/work/checkpoint", {
        "name": job_name, "owner": lease["owner"], "token": lease["token"],
        "cursor": cursor, "completed": completed, "total": total,
        "state": "running", "error": "",
    })
    return lease, revision, completed, total, cursor


def hash_stream(resp, max_bytes=0):
    """Tinh SHA-256 full + MD5 1MB dau tren stream (khong nap het RAM)."""
    sha = hashlib.sha256()
    md5 = hashlib.md5()
    first = True
    total = 0
    for chunk in resp.iter_content(1024 * 1024):
        if not chunk:
            break
        total += len(chunk)
        if max_bytes > 0 and total > max_bytes:
            return None, None, total
        sha.update(chunk)
        if first:
            md5.update(chunk[:1048576])
            first = False
    return sha.hexdigest(), md5.hexdigest(), total


def process_hash(client, job_name, owner, lease, revision, item, max_bytes, dry_run, stats):
    """Hash mot file, gui ket qua len NAS voi source_version."""
    rel = webdav_rel(item["path"])
    size = item.get("size", 0)
    if max_bytes > 0 and size > max_bytes:
        with stats["lock"]:
            stats["skipped_large"] += 1
        return
    try:
        resp = client.webdav_get(rel, stream=True)
        sha, partial, total = hash_stream(resp, max_bytes)
        if not sha:
            with stats["lock"]:
                stats["failed"] += 1
            return
        if total != size:
            with stats["lock"]:
                stats["failed"] += 1
            return
    except Exception as e:
        _log("[FAIL] hash %s: %s" % (item["path"], e))
        with stats["lock"]:
            stats["failed"] += 1
        return
    version = str(item.get("mtime", 0)) + ":" + str(size) + ":" + sha
    payload = {"path": item["path"], "size": total, "mtime": item.get("mtime", 0),
               "sha256": sha, "partial": partial, "source_version": version}
    if not dry_run:
        try:
            client.api("/api/hash/save", {"items": [payload]})
        except ApiError as e:
            _log("[WARN] save hash %s: %s" % (item["path"], e.detail))
            with stats["lock"]:
                stats["failed"] += 1
            return
    with stats["lock"]:
        stats["hashed"] += 1
        if stats["hashed"] % 50 == 0:
            _log("... %s file | moi=%s loi=%s" % (
                stats["hashed"] + stats.get("cached", 0),
                stats["hashed"], stats["failed"]))


def run_hash_job(client, job_name, owner, workers, min_size, max_mb, dry_run):
    stats = {"hashed": 0, "cached": 0, "failed": 0, "skipped_large": 0, "lock": threading.Lock()}
    max_bytes = (max_mb or 0) * 1024 * 1024
    lease, revision, completed, total, cursor = claim_work(client, job_name, owner)
    if not lease:
        _log("Khong co viec hash de lam (index san sang va job complete hoac NAS bani).")
        return
    _log("Hash job %s: revision=%s lease=%s total=%s" % (job_name, revision, lease["owner"], total))
    page_cursor = cursor
    need_total = True
    while True:
        page = client.api("/api/work/files", {
            "name": job_name, "owner": lease["owner"], "token": lease["token"],
            "cursor": page_cursor, "limit": 256, "catalogue_revision": revision,
            "include_total": need_total,
        })
        if page["catalogue_revision"] != revision:
            _log("Loi: catalogue thay doi giua chay hash, dung de tranh desync.")
            return
        if need_total:
            total = page["total"]
            need_total = False
        files = page.get("files", [])
        threads = []
        for item in files:
            size = item.get("size", 0)
            if min_size and size < min_size:
                with stats["lock"]:
                    stats["cached"] += 1
                continue
            t = threading.Thread(target=process_hash, args=(client, job_name, owner,
                                                            lease, revision, item, max_bytes, dry_run, stats))
            t.start()
            threads.append(t)
            if len(threads) >= workers:
                for t in threads:
                    t.join()
                threads = []
        for t in threads:
            t.join()
        cursor = str(revision) + "\n" + (files[-1]["path"] if files else "")
        client.api("/api/work/checkpoint", {
            "name": job_name, "owner": lease["owner"], "token": lease["token"],
            "cursor": cursor,
            "completed": min(completed + len(files), total), "total": total,
            "state": "running", "error": "",
        })
        completed = min(completed + len(files), total)
        if page.get("eof"):
            break
        page_cursor = page.get("next_cursor", page_cursor)
    error = "failed_files=%s" % stats["failed"] if stats["failed"] else ""
    final_cursor = str(revision) + "\n" + (files[-1]["path"] if files else "")
    client.api("/api/work/checkpoint", {
        "name": job_name, "owner": lease["owner"], "token": lease["token"],
        "cursor": final_cursor,
        "completed": completed, "total": total, "state": "complete", "error": error,
    })
    _log("XONG hash: tong=%s moi=%s co-san=%s loi=%s boqua_lon=%s" % (
        stats["hashed"] + stats["cached"], stats["hashed"], stats["cached"],
        stats["failed"], stats["skipped_large"]))


def main():
    ap = argparse.ArgumentParser(description="Quan ly file NAS qua hash + tim trung (coordinator API)")
    ap.add_argument("--host", required=True)
    ap.add_argument("--user", required=True)
    ap.add_argument("--pass", dest="password", required=True)
    ap.add_argument("--webdav-url", default="")
    ap.add_argument("--api-url", default="")
    ap.add_argument("--job", default="hash", choices=("hash", "duplicates", "fingerprint"))
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--min-size", type=int, default=4096,
                    help="Bo qua file nho hon N bytes khi bao trung (mac dinh 4096)")
    ap.add_argument("--max-mb", type=int, default=0,
                    help="Bo qua file lon hon N MB (0 = khong gioi han)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--report", default="duplicates.json")
    args = ap.parse_args()
    base = args.host.rstrip("/")
    api = args.api_url.rstrip("/") if args.api_url else base
    webdav_base = (args.webdav_url.rstrip("/") if args.webdav_url
                   else base + "/webdav")
    client = NasClient(api, webdav_base, args.user, args.password)
    run_hash_job(client, args.job, "pc-" + uuid.uuid4().hex[:20],
                 args.workers, args.min_size, args.max_mb, args.dry_run)
    if not args.dry_run:
        try:
            data = client.api("/api/hash/duplicates", {"min_size": args.min_size})
        except Exception:
            data = None
        if data:
            groups = data.get("groups", [])
            wasted = sum(g.get("wasted_bytes", 0) for g in groups)
            _log("TRUNG LAP: %s nhom, lang phi ~%sMB" % (len(groups), wasted // 1048576))
            with open(args.report, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=1)
            _log("Da ghi bao cao: %s" % (args.report,))


if __name__ == "__main__":
    main()
