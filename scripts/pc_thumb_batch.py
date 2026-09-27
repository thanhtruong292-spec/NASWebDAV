#!/usr/bin/env python3
"""Batch thumbnail generator chay tren PC — may tinh render thay NAS.

Liet ke file qua WebDAV PROPFIND, voi moi file media chua co thumbnail:
  1. HEAD /api/thumb?path=... — 200 = co roi, bo qua (khong tai, khong render).
  2. Tai file goc ve temp (Range gioi han 200MB).
  3. Render local: Pillow (anh) / ffmpeg -ss -vframes 1 (video), max 320px.
  4. POST /api/thumb/upload — NAS ghi .thumbs/<hash>.jpg, daemon skip.

Dung:
    pip install requests pillow
    python scripts/pc_thumb_batch.py --host http://192.168.100.254:8822 \
        --user daica --pass <mat-khau> [--workers 4] [--dry-run]

Yeu cau: ffmpeg trong PATH (chi can cho video; anh dung Pillow).
"""
import argparse
import concurrent.futures as cf
import io
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET

try:
    import requests
except ImportError:
    sys.exit("Thieu 'requests' — chay: pip install requests pillow")

try:
    from PIL import Image
    HAVE_PIL = True
except ImportError:
    HAVE_PIL = False

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".heif"}
VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".mpg", ".mpeg", ".wmv", ".flv", ".ts", ".m4v"}
THUMB_SIZE = 320
MAX_BYTES = 200 * 1024 * 1024

_stats = {"total": 0, "skipped": 0, "done": 0, "failed": 0}
_lock = threading.Lock()


def _log(*a):
    with _lock:
        print(*a, flush=True)


def _bump(k):
    with _lock:
        _stats[k] += 1


def propfind(session, base, path, depth=1):
    """Tra ve [(href, is_dir)] duoi path WebDAV (Depth: 1)."""
    url = base.rstrip("/") + ("/" + path.strip("/") if path.strip("/") else "")
    try:
        r = session.request("PROPFIND", url, headers={"Depth": str(depth)}, timeout=30)
    except Exception as e:
        _log(f"[WARN] PROPFIND loi {path}: {e}")
        return []
    if r.status_code not in (200, 207):
        return []
    out = []
    try:
        root = ET.fromstring(r.content)
    except Exception:
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
    """BFS toan bo cay WebDAV, yield webdav-path cua file media."""
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


def has_thumb(session, api, path):
    try:
        r = session.head(api + "/api/thumb",
                         params={"path": path}, timeout=20,
                         allow_redirects=True)
        return r.status_code == 200
    except Exception:
        return False


def render_image(data):
    if not HAVE_PIL:
        return None
    try:
        img = Image.open(io.BytesIO(data))
        img.thumbnail((THUMB_SIZE, THUMB_SIZE), Image.LANCZOS)
        if img.mode in ("RGBA", "P"):
            img = img.convert("RGB")
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=80)
        return buf.getvalue()
    except Exception:
        return None


def render_video(tmp_path):
    try:
        out = tmp_path + ".thumb.jpg"
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
             "-ss", "5", "-i", tmp_path,
             "-vframes", "1", "-vf", f"scale={THUMB_SIZE}:-1",
             "-q:v", "4", out],
            timeout=120, check=False,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if os.path.isfile(out) and os.path.getsize(out) > 1200:
            with open(out, "rb") as f:
                return f.read()
    except Exception:
        pass
    return None


def process_one(session, base, api, path, dry_run):
    _bump("total")
    if has_thumb(session, api, path):
        _bump("skipped")
        return
    ext = os.path.splitext(path)[1].lower()
    is_video = ext in VIDEO_EXTS
    if is_video and not _have_ffmpeg():
        _bump("failed")
        _log(f"[SKIP] {path} — can ffmpeg de render video")
        return
    if not is_video and not HAVE_PIL:
        _bump("failed")
        _log(f"[SKIP] {path} — can Pillow (pip install pillow)")
        return
    # Tai file goc (gioi han MAX_BYTES).
    try:
        url = base.rstrip("/") + "/webdav/" + urllib.parse.quote(path.lstrip("/"))
        r = session.get(url, timeout=120, stream=True)
        if r.status_code != 200:
            _bump("failed")
            return
        with tempfile.NamedTemporaryFile(delete=False,
                                         suffix=os.path.splitext(path)[1]) as tf:
            tmp = tf.name
            total = 0
            for chunk in r.iter_content(131072):
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_BYTES:
                    break
                tf.write(chunk)
        if total > MAX_BYTES:
            os.unlink(tmp)
            _bump("failed")
            _log(f"[SKIP] {path} — qua lon (>{MAX_BYTES // 1048576}MB)")
            return
    except Exception as e:
        _bump("failed")
        _log(f"[FAIL] tai {path}: {e}")
        return
    try:
        if is_video:
            blob = render_video(tmp)
        else:
            with open(tmp, "rb") as f:
                blob = render_image(f.read())
    finally:
        try:
            os.unlink(tmp)
        except OSError:
            pass
    if not blob:
        _bump("failed")
        _log(f"[FAIL] render {path}")
        return
    if dry_run:
        _bump("done")
        _log(f"[DRY] {path} ({len(blob)//1024}KB)")
        return
    try:
        r = session.post(api + "/api/thumb/upload",
                         data={"path": path},
                         files={"thumb": ("thumb.jpg", blob, "image/jpeg")},
                         timeout=60)
        if r.status_code == 200:
            _bump("done")
        else:
            _bump("failed")
            _log(f"[FAIL] upload {path}: HTTP {r.status_code}")
    except Exception as e:
        _bump("failed")
        _log(f"[FAIL] upload {path}: {e}")
    n = _stats["total"]
    if n % 25 == 0:
        _log(f"... {n} file | xong={_stats['done']} bo-qua={_stats['skipped']} loi={_stats['failed']}")


def _have_ffmpeg():
    try:
        subprocess.run(["ffmpeg", "-version"], timeout=10,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False


def main():
    ap = argparse.ArgumentParser(description="Batch thumbnails tren PC thay NAS")
    ap.add_argument("--host", required=True,
                    help="VD: http://192.168.100.254:8822")
    ap.add_argument("--user", required=True)
    ap.add_argument("--pass", dest="password", required=True)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--dry-run", action="store_true",
                    help="Chi liet ke + render, khong upload")
    args = ap.parse_args()

    session = requests.Session()
    session.auth = (args.user, args.password)
    base = args.host.rstrip("/")
    api = base  # cung host:port, endpoint /api/...

    _log(f"Liet ke file media tren {base} ...")
    files = list(walk(session, base + "/webdav"))
    _log(f"Tim thay {len(files)} file media.")
    if not files:
        return
    if not HAVE_PIL:
        _log("CANH BAO: thieu Pillow — anh se bi bo qua (pip install pillow).")
    if not _have_ffmpeg():
        _log("CANH BAO: khong thay ffmpeg — video se bi bo qua.")
    t0 = time.time()
    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = [ex.submit(process_one, session, base, api, p, args.dry_run)
                for p in files]
        for f in cf.as_completed(futs):
            f.result()
    dt = time.time() - t0
    _log(f"XONG trong {dt:.0f}s: tong={_stats['total']} hoan-thanh={_stats['done']} "
         f"bo-qua-da-co={_stats['skipped']} loi={_stats['failed']}")


if __name__ == "__main__":
    main()
