#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Batch thumbnail generator chay tren PC — may tinh render thay NAS.

Thay vie tu tu duyet WebDAV BFS thu cong, script nay dung coordinator API
chuan hoa cua NAS (/api/work/claim, /api/work/checkpoint, /api/work/status)
de lay trang cac file can render thumbnail qua lease/checkpoint.

1. Lay trang file can thumbnail tu /api/work/files (file_index).
2. Kiem tra /api/thumb/exists — 200 = co roi, bo qua (khong tai, khong render).
3. Tai file goc ve temp (Range gioi han 200MB).
4. Render local: Pillow (anh) / ffmpeg -ss -vframes 1 (video), max 320px.
   HEIC/HEIF: NAS PIL 4.0.0 khong decode duoc -> PC worker nhan xu ly,
   neu PC cung khong decode duoc thi danh dau bo qua.
5. POST /api/thumb/upload — NAS ghi .thumbs/<hash>.jpg, daemon skip.

Dung:
    pip install requests pillow
    python scripts/pc_thumb_batch.py --host http://192.168.100.254:5050 \
        --webdav-url http://192.168.100.254:8822/webdav \
        --user daica --pass <mat-khau> [--workers 4] [--dry-run]

Yeu cau: ffmpeg trong PATH (chi can cho video; anh dung Pillow).
"""
import argparse
import base64
import concurrent.futures as cf
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

try:
    import requests
except ImportError:
    sys.exit("Thieu 'requests' — chay: pip install requests pillow")

try:
    from PIL import Image, ImageFile
    ImageFile.LOAD_TRUNCATED_IMAGES = True
    HAVE_PIL = True
except ImportError:
    HAVE_PIL = False

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".heif"}
VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".mpg", ".mpeg", ".wmv", ".flv", ".ts", ".m4v"}
THUMB_SIZE = 320
MAX_BYTES = 200 * 1024 * 1024  # gioi han tai file qua LAN (0 = khong gioi han)


class ApiError(Exception):
    def __init__(self, status, detail):
        Exception.__init__(self, "NAS API %s: %s" % (status, detail))
        self.status = status
        self.detail = detail


class NasClient(object):
    def __init__(self, api_url, webdav_url, username, password):
        self.api_url = api_url.rstrip("/")
        self.webdav_url = webdav_url.rstrip("/") + "/"
        raw = (username + ":" + password).encode("utf-8")
        self.authorization = "Basic " + base64.b64encode(raw).decode("ascii")
        self.owner = "pc-" + uuid.uuid4().hex[:20]
        self.lease = None
        self.revision = None
        self.cursor = ""

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

    def api(self, path, method="GET", body=None, content_type="application/json"):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        raw = self._request(self.api_url + path, method, data, content_type)
        return json.loads(raw.decode("utf-8"))

    def webdav_get(self, rel_path):
        url = self.webdav_url + urllib.parse.quote(rel_path, safe="/")
        req = urllib.request.Request(url, headers={"Authorization": self.authorization})
        return urllib.request.urlopen(req, timeout=600)

    def checkpoint(self, state="running", error=""):
        if not self.lease:
            return
        path = self.cursor.split("\n", 1)[-1] if self.cursor else ""
        cursor = (str(self.revision) + "\n" + path) if self.revision is not None else self.cursor
        self.api("/api/work/checkpoint", "POST", {
            "name": "thumbnail", "owner": self.lease["owner"], "token": self.lease["token"],
            "cursor": cursor, "completed": 0, "total": 0,
            "state": state, "error": error,
        })
        self.cursor = cursor


def webdav_rel(webdav_path):
    if webdav_path.startswith("/webdav/"):
        return webdav_path[len("/webdav/"):]
    return webdav_path.lstrip("/")


def _log(*a):
    print(*a, flush=True)


def _bump(stats, k):
    with stats["lock"]:
        stats[k] += 1


PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop>
  <D:getcontentlength/><D:getlastmodified/><D:getcontenttype/>
  <D:resourcetype/><D:getetag/>
</D:prop></D:propfind>"""


def _quote_variants(path):
    stripped = path.strip("/")
    variants = []
    variants.append("/" + "/".join(
        urllib.parse.quote(seg, safe="") for seg in stripped.split("/")) + "/"
        if stripped else "/")
    try:
        rescued = urllib.parse.unquote(urllib.parse.unquote(stripped))
        v = ("/" + "/".join(
            urllib.parse.quote(seg, safe="") for seg in rescued.split("/")) + "/"
             if rescued else "/")
        if v not in variants:
            variants.append(v)
    except Exception:
        pass
    raw = "/" + stripped + "/" if stripped else "/"
    if raw not in variants:
        variants.append(raw)
    return variants


def propfind(session, base, path, depth=1, debug=False):
    base = base.rstrip("/")
    last_status = None
    for variant in _quote_variants(path):
        url = base + variant
        try:
            r = session.request("PROPFIND", url,
                                data=PROPFIND_BODY.encode("utf-8"),
                                headers={"Depth": str(depth),
                                         "Content-Type": "application/xml; charset=utf-8"},
                                timeout=30)
        except Exception as e:
            _log("[WARN] PROPFIND loi %s: %s" % (url, e))
            return []
        if r.status_code in (200, 207):
            break
        last_status = r.status_code
    else:
        _log("[WARN] PROPFIND %s -> HTTP %s (thu %s bien the encode)" % (
            base + _quote_variants(path)[0], last_status, len(_quote_variants(path))))
        return []
    out = []
    import xml.etree.ElementTree as ET
    try:
        root = ET.fromstring(r.content)
    except Exception as e:
        _log("[WARN] PROPFIND %s XML loi: %s" % (url, e))
        return []
    for resp in root.iter():
        if not resp.tag.endswith("response"):
            continue
        href = ""
        is_dir = False
        for child in resp:
            if child.tag.endswith("href") and child.text:
                href = urllib.parse.unquote(child.text.strip())
            if child.tag.endswith("propstat"):
                for p in child.iter():
                    if p.tag.endswith("resourcetype"):
                        for rt in p:
                            if rt.tag.endswith("collection"):
                                is_dir = True
        if href:
            out.append((href, is_dir))
    return out


def walk(session, base):
    """BFS toan bo cay WebDAV, yield webdav-path cua file media can thumbnail."""
    seen = set()
    queue = [""]
    while queue:
        path = queue.pop(0)
        for href, is_dir in propfind(session, base, path):
            rel = href.split("/webdav/", 1)[-1] if "/webdav/" in href else href.lstrip("/")
            rel = rel.strip("/")
            if not rel or rel in seen:
                continue
            seen.add(rel)
            parts = rel.lower().split("/")
            if any(p.startswith((".", ".thumbs", ".thumbnails", ".trash", ".nas_meta",
                                 "@eadir", "#recycle", ".git", ".recycle")) for p in parts):
                continue
            if is_dir or href.endswith("/"):
                queue.append(rel)
                continue
            ext = os.path.splitext(rel)[1].lower()
            if ext in IMAGE_EXTS or ext in VIDEO_EXTS:
                yield "/" + rel


def claim_thumbnail_work(client):
    status = client.api("/api/work/status")
    if not status.get("index_ready"):
        raise ApiError(423, "catalogue_not_ready")
    client.revision = status["catalogue_revision"]
    jobs = dict((job["name"], job) for job in status.get("jobs", []))
    prior = jobs.get("thumbnail") or {}
    if prior.get("state") == "complete":
        return False
    if prior.get("state") in (None, "failed"):
        client.api("/api/work/request", "POST", {"name": "thumbnail"})
    client.lease = client.api("/api/work/claim", "POST",
                              {"name": "thumbnail", "owner": client.owner})
    if not client.lease.get("token"):
        return False
    saved_revision, sep, saved_path = client.lease.get("cursor", "").partition("\n")
    if sep and saved_revision == str(client.revision):
        client.cursor = client.lease["cursor"]
    else:
        client.cursor = ""
    client.checkpoint()
    return True


def render_image(data):
    if not HAVE_PIL:
        return None
    try:
        img = Image.open(io.BytesIO(data))
        try:
            img.draft("RGB", (THUMB_SIZE * 2, THUMB_SIZE * 2))
        except Exception:
            pass
        try:
            img.load()
        except OSError as oe:
            if "truncated" not in str(oe).lower():
                raise
        img.thumbnail((THUMB_SIZE, THUMB_SIZE), Image.BILINEAR)
        if img.mode in ("I;16", "I;16L", "I;16B", "I", "F"):
            img = img.convert("L").convert("RGB")
        elif img.mode != "RGB":
            img = img.convert("RGB")
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=75)
        return buf.getvalue()
    except Exception as e:
        _log("[WARN] render anh loi: %s" % (e,))
        return None


def _ffmpeg_hw_args():
    if hasattr(_ffmpeg_hw_args, "_cached"):
        return _ffmpeg_hw_args._cached
    args = []
    try:
        r = subprocess.run(["ffmpeg", "-hide_banner", "-hwaccels"],
                           timeout=10, stdout=subprocess.PIPE,
                           stderr=subprocess.DEVNULL)
        accels = (r.stdout or b"").decode("utf-8", errors="ignore").lower()
        if sys.platform == "win32":
            for a in ("d3d11va", "dxva2"):
                if a in accels:
                    args = ["-hwaccel", a]
                    break
        elif sys.platform == "darwin":
            if "videotoolbox" in accels:
                args = ["-hwaccel", "videotoolbox"]
        else:
            for a in ("cuda", "qsv", "vaapi"):
                if a in accels:
                    args = ["-hwaccel", a]
                    break
    except Exception:
        pass
    _ffmpeg_hw_args._cached = args
    return args


def render_video(tmp_path):
    out = tmp_path + ".thumb.jpg"
    hw = _ffmpeg_hw_args()
    vf = "scale=%s:-1" % (THUMB_SIZE,)
    attempts = [
        (["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"]
         + hw + ["-ss", "5", "-i", tmp_path,
                 "-vframes", "1", "-vf", vf, "-q:v", "4", out]),
        (["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
          "-i", tmp_path, "-ss", "5",
          "-vframes", "1", "-vf", vf, "-q:v", "4", out]),
        (["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
          "-i", tmp_path,
          "-vframes", "1", "-vf", vf, "-q:v", "4", out]),
    ]
    last_err = ""
    for cmd in attempts:
        try:
            r = subprocess.run(cmd, timeout=180, check=False,
                               stdout=subprocess.DEVNULL,
                               stderr=subprocess.PIPE)
            if os.path.isfile(out) and os.path.getsize(out) > 1200:
                with open(out, "rb") as f:
                    return f.read()
            err_txt = (r.stderr or b"").decode("utf-8", errors="ignore").strip()
            last_err = (err_txt.splitlines()[-1:] or [""])[0][:160]
        except Exception as e:
            last_err = str(e)[:160]
            continue
    if last_err:
        _log("[WARN] render video loi (%s): %s" % (os.path.basename(tmp_path), last_err))
    return None


def _is_heic_by_magic(path):
    """Phat hien HEIF/HEIC bang magic bytes, khong phai extension."""
    try:
        with open(path, "rb") as f:
            magic = f.read(12)
        if len(magic) >= 12 and magic[4:8] == b"ftyp" and magic[8:12] in (
                b"mif1", b"msf1", b"heic", b"heix", b"hevc", b"hevx",
                b"heim", b"heis", b"hevm", b"hevs", b"mif2"):
            return True
    except Exception:
        pass
    return False


def _upload_thumb(client, path, blob, source_version=None):
    boundary = "----NasWorker" + uuid.uuid4().hex
    body = ("--%s\r\nContent-Disposition: form-data; name=\"path\"\r\n\r\n%s\r\n" %
            (boundary, path)).encode("utf-8")
    if source_version:
        body += ("--%s\r\nContent-Disposition: form-data; name=\"source_version\"\r\n\r\n%s\r\n" %
                 (boundary, source_version)).encode("ascii")
    body += ("--%s\r\nContent-Disposition: form-data; name=\"thumb\"; filename=\"thumb.jpg\"\r\n"
             "Content-Type: image/jpeg\r\n\r\n" % boundary).encode("ascii")
    body += blob + ("\r\n--%s--\r\n" % boundary).encode("ascii")
    client.api("/api/thumb/upload", "POST", body,
               "multipart/form-data; boundary=" + boundary)


def process_one(client, item, dry_run, max_bytes, stats):
    path = item["path"]
    _bump(stats, "total")
    try:
        missing_result = client.api("/api/thumb/exists", "POST", {"paths": [path]})
    except ApiError:
        _bump(stats, "failed")
        _log("[FAIL] thumb/exists %s" % path)
        return
    if path not in missing_result.get("missing", []):
        _bump(stats, "skipped")
        return
    ext = os.path.splitext(path)[1].lower()
    is_video = ext in VIDEO_EXTS
    if is_video and shutil.which("ffmpeg") is None:
        _bump(stats, "failed")
        _log("[SKIP] %s — can ffmpeg de render video" % (path,))
        return
    if not is_video and not HAVE_PIL:
        _bump(stats, "failed")
        _log("[SKIP] %s — can Pillow (pip install pillow)" % (path,))
        return
    try:
        resp = client.webdav_get(webdav_rel(path))
        data = resp.read(max_bytes + 1) if max_bytes > 0 else resp.read()
        if max_bytes > 0 and len(data) > max_bytes:
            _bump(stats, "failed")
            _log("[SKIP] %s — qua lon (>%sMB)" % (path, max_bytes // 1048576))
            return
    except Exception as e:
        _bump(stats, "failed")
        _log("[FAIL] tai %s: %s" % (path, e))
        return
    source_version = "%s:%s" % (
        resp.headers.get("X-NAS-Version", ""), resp.headers.get("Content-Length", len(data)))
    if dry_run:
        _bump(stats, "done")
        _log("[DRY] %s" % (path,))
        return
    tmp = None
    try:
        tmp = tempfile.NamedTemporaryFile(delete=False,
                                          suffix=os.path.splitext(path)[1]).name
        with open(tmp, "wb") as tf:
            tf.write(data)
        if is_video:
            blob = render_video(tmp)
        else:
            with open(tmp, "rb") as f:
                blob = render_image(f.read())
    finally:
        if tmp:
            try:
                os.unlink(tmp)
            except OSError:
                pass
    if not blob:
        _bump(stats, "failed")
        _log("[FAIL] render %s" % (path,))
        return
    try:
        _upload_thumb(client, path, blob, source_version)
        _bump(stats, "done")
    except Exception as e:
        _bump(stats, "failed")
        _log("[FAIL] upload %s: %s" % (path, e))


def main():
    ap = argparse.ArgumentParser(description="Batch thumbnails tren PC thay NAS (coordinator API)")
    ap.add_argument("--host", required=True,
                    help="VD: http://192.168.100.254:5050")
    ap.add_argument("--user", required=True)
    ap.add_argument("--pass", dest="password", default="",
                    help="Mat khau NAS (khuyen dung: bo trong + dat NAS_PASS de khoi lo pass trong lich su lenh).")
    ap.add_argument("--webdav-url", default="",
                    help="Base WebDAV rieng (mac dinh: <host>/webdav).")
    ap.add_argument("--api-url", default="",
                    help="Base API rieng (mac dinh: <host>).")
    ap.add_argument("--workers", type=int, default=0,
                    help="So luong mac dinh = CPU x 2 (toi da 16).")
    ap.add_argument("--priority-folder", default="",
                    help="Thu muc uu tien render truoc (vd AutoBackup/DCIM/Camera), bo qua neu trong bo nhung file cu.")
    ap.add_argument("--dry-run", action="store_true",
                    help="Chi liet ke + render, khong upload")
    ap.add_argument("--max-mb", type=int, default=0,
                    help="Bo qua file lon hon N MB (0 = khong gioi han, mac dinh 200MB).")
    args = ap.parse_args()
    password = args.password or os.environ.get("NAS_PASS", "")
    if not password:
        sys.exit("Thieu mat khau: truyen --pass ... hoac dat bien NAS_PASS.")

    base = args.host.rstrip("/")
    webdav_base = (args.webdav_url.rstrip("/") if args.webdav_url
                   else base + "/webdav")
    api = args.api_url.rstrip("/") if args.api_url else base
    client = NasClient(api, webdav_base, args.user, password)

    max_bytes = (args.max_mb or 0) * 1024 * 1024 or MAX_BYTES
    stats = {"total": 0, "skipped": 0, "done": 0, "failed": 0, "heic_skipped": 0, "lock": threading.Lock()}

    _log("WebDAV: %s | API: %s" % (webdav_base, api))
    try:
        started = claim_thumbnail_work(client)
    except ApiError as e:
        _log("Khong the bat dau lam viec thumbnail: %s" % e.detail)
        return 1
    if not started:
        _log("Khong co viec thumbnail de lam (index san sang va job complete hoac NAS bani).")
        return 0

    priority_folder = args.priority_folder.strip("/") if args.priority_folder else ""
    _log("Lay trang file can thumbnail qua /api/work/files (catalogue_revision=%s)..." % client.revision)
    page_cursor = client.cursor
    need_total = True
    total = 0
    workers = args.workers or min(16, (os.cpu_count() or 4) * 2)
    _log("Workers: %s" % workers)
    while True:
        page = client.api("/api/work/files", "POST", {
            "name": "thumbnail", "owner": client.owner, "token": client.lease["token"],
            "cursor": page_cursor, "limit": 256, "catalogue_revision": client.revision,
            "include_total": need_total,
        })
        if page["catalogue_revision"] != client.revision:
            _log("Loi: catalogue thay doi giua chay thumbnail, dung de tranh desync.")
            return 1
        if need_total:
            total = page["total"]
            need_total = False
        files = page.get("files", [])
        # Neu co priority_folder, sap xep: file thuoc thu muc uu tien duoc xu ly truoc.
        if priority_folder:
            def _prio_key(item):
                p = item.get("path", "")
                return 0 if p.startswith("/" + priority_folder + "/") or p == "/" + priority_folder else 1
            files.sort(key=_prio_key)
        _log("Trang: %s file (tong %s)" % (len(files), total))
        with cf.ThreadPoolExecutor(max_workers=workers) as ex:
            futs = [ex.submit(process_one, client, item, args.dry_run, max_bytes, stats)
                    for item in files]
            for f in futs:
                f.result()
        client.checkpoint()
        if page.get("eof"):
            break
        page_cursor = page.get("next_cursor", page_cursor)
    client.checkpoint(state="complete")
    _log("XONG thumbnail: tong=%s hoan-thanh=%s bo-qua-da-co=%s loi=%s" % (
        stats["total"], stats["done"], stats["skipped"], stats["failed"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
