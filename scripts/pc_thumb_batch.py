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
# Mac dinh KHONG gioi han dung luong (chay LAN/WiFi 6). Neu mang yeu thi
# truyen --max-mb N de bo qua file lon hon N MB (0 = khong gioi han).
MAX_BYTES_DEFAULT = 0

_stats = {"total": 0, "skipped": 0, "done": 0, "failed": 0}
_lock = threading.Lock()


def _log(*a):
    with _lock:
        print(*a, flush=True)


def _bump(k):
    with _lock:
        _stats[k] += 1


PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop>
  <D:getcontentlength/><D:getlastmodified/><D:getcontenttype/>
  <D:resourcetype/><D:getetag/>
</D:prop></D:propfind>"""


def _quote_variants(path):
    """Cac cach encode path de thu khi 404: ten Nhat/Trung/Han hay bi double-
    encode (%E3%80%80 con sot) hoac server cho UTF-8 tho. Tra list URL path."""
    stripped = path.strip("/")
    variants = []
    # 1. Chuan: quote UTF-8 toan path (giua /).
    variants.append("/" + "/".join(
        urllib.parse.quote(seg, safe="") for seg in stripped.split("/")) + "/"
        if stripped else "/")
    # 2. Unquote-rescue: path da bi encode 1 lan (con sot %XX) -> giai het
    # ve UTF-8 tho roi quote lai dung.
    try:
        rescued = urllib.parse.unquote(urllib.parse.unquote(stripped))
        v = ("/" + "/".join(
            urllib.parse.quote(seg, safe="") for seg in rescued.split("/")) + "/"
             if rescued else "/")
        if v not in variants:
            variants.append(v)
    except Exception:
        pass
    # 3. Tho (khong encode): mot so nginx cau hinh accepting raw UTF-8.
    raw = "/" + stripped + "/" if stripped else "/"
    if raw not in variants:
        variants.append(raw)
    return variants


def propfind(session, base, path, depth=1, debug=False):
    """Tra ve [(href, is_dir)] duoi path WebDAV (Depth: 1).
    Gui body XML explicit giong app (WebDavManager FIX H5) — nginx WebDAV
    tra 405 neu PROPFIND khong co body. URL luon co dau / cuoi giong app
    (toValidUrl ep trailing slash cho collection) — thieu la nginx tra 405.
    Ten Nhat/Trung/Han de bi 404 do double-encode -> thu nhieu bien the."""
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
            _log(f"[WARN] PROPFIND loi {url}: {e}")
            return []
        if r.status_code in (200, 207):
            break
        last_status = r.status_code
    else:
        _log(f"[WARN] PROPFIND {base + _quote_variants(path)[0]} -> HTTP {last_status} "
             f"(thu {len(_quote_variants(path))} bien the encode, can Basic auth + quyen doc)")
        return []
    out = []
    try:
        root = ET.fromstring(r.content)
    except Exception as e:
        _log(f"[WARN] PROPFIND {url} XML loi: {e}")
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


def make_session(user, password):
    """Session rieng moi worker: pool lon, keep-alive, khong tranh nhau.
    requests.Session KHONG thread-safe tren pool — dung chung la nghen."""
    from requests.adapters import HTTPAdapter
    s = requests.Session()
    s.auth = (user, password)
    ad = HTTPAdapter(pool_connections=8, pool_maxsize=16,
                     max_retries=2, pool_block=False)
    s.mount("http://", ad)
    s.mount("https://", ad)
    return s


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
        # BILINEAR nhanh gap 3-4x LANCZOS, mat thuong khong phan biet o 320px.
        # Draft mode giam them ~30% thoi gian decode JPEG.
        img.draft("RGB", (THUMB_SIZE * 2, THUMB_SIZE * 2))
        img.thumbnail((THUMB_SIZE, THUMB_SIZE), Image.BILINEAR)
        # PNG 16-bit (I;16/...) khong save JPEG truc tiep duoc -> ve L roi RGB.
        if img.mode in ("I;16", "I;16L", "I;16B", "I", "F"):
            img = img.convert("L").convert("RGB")
        elif img.mode != "RGB":
            img = img.convert("RGB")
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=75)
        return buf.getvalue()
    except Exception as e:
        _log(f"[WARN] render anh loi: {e}")
        return None


def _ffmpeg_hw_args():
    """Chon hwaccel neu co: dxva2/d3d11va (Windows), videotoolbox (macOS),
    cuda/qsv/vaapi (Linux). Khong co thi [] (CPU). Cache ket qua."""
    if hasattr(_ffmpeg_hw_args, "_cached"):
        return _ffmpeg_hw_args._cached
    args = []
    try:
        r = subprocess.run(["ffmpeg", "-hide_banner", "-hwaccels"],
                           timeout=10, stdout=subprocess.PIPE,
                           stderr=subprocess.DEVNULL, text=True)
        accels = (r.stdout or "").lower()
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
    # -ss TRUOC -i = seek nhanh (khong decode tu dau toi giay 5). Mot so file
    # (moov o cuoi, index hong) seek nhanh cho frame den -> fallback seek chinh
    # xac (-ss sau -i). hwaccel neu co.
    hw = _ffmpeg_hw_args()
    vf = f"scale={THUMB_SIZE}:-1"
    attempts = [
        (["ffmpeg", "-hide_banner", "-loglevel", "error", "-y"]
         + hw + ["-ss", "5", "-i", tmp_path,
                 "-vframes", "1", "-vf", vf, "-q:v", "4", out]),
        (["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
          "-i", tmp_path, "-ss", "5",
          "-vframes", "1", "-vf", vf, "-q:v", "4", out]),
    ]
    last_err = ""
    for cmd in attempts:
        try:
            r = subprocess.run(cmd, timeout=180, check=False,
                               stdout=subprocess.DEVNULL,
                               stderr=subprocess.PIPE, text=True)
            if os.path.isfile(out) and os.path.getsize(out) > 1200:
                with open(out, "rb") as f:
                    return f.read()
            last_err = (r.stderr or "").strip().splitlines()[-1:] or [""]
            last_err = last_err[0][:160]
        except Exception as e:
            last_err = str(e)[:160]
            continue
    if last_err:
        _log(f"[WARN] render video loi ({os.path.basename(tmp_path)}): {last_err}")
    return None


def process_one(session, base, api, path, dry_run, max_bytes=0):
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
    # Tai full file goc qua LAN (khong gioi han mac dinh). Chi gioi han khi
    # truyen --max-mb (mang yeu). base o day la webdav_base (da gom /webdav).
    try:
        url = base.rstrip("/") + "/" + urllib.parse.quote(path.lstrip("/"))
        r = session.get(url, timeout=600, stream=True)
        if r.status_code != 200:
            _bump("failed")
            return
        with tempfile.NamedTemporaryFile(delete=False,
                                         suffix=os.path.splitext(path)[1]) as tf:
            tmp = tf.name
            total = 0
            for chunk in r.iter_content(1024 * 1024):
                if not chunk:
                    break
                total += len(chunk)
                if max_bytes > 0 and total > max_bytes:
                    break
                tf.write(chunk)
        if max_bytes > 0 and total > max_bytes:
            os.unlink(tmp)
            _bump("failed")
            _log(f"[SKIP] {path} — qua lon (>{max_bytes // 1048576}MB)")
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
    ap.add_argument("--pass", dest="password", default="",
                    help="Mat khau NAS (khuyen dung: bo trong + dat NAS_PASS "
                         "de khoi lo pass trong lich su lenh).")
    ap.add_argument("--workers", type=int, default=0,
                    help="So luong mac dinh = CPU x 2 (toi da 16).")
    ap.add_argument("--dry-run", action="store_true",
                    help="Chi liet ke + render, khong upload")
    ap.add_argument("--max-mb", type=int, default=0,
                    help="Bo qua file lon hon N MB (0 = khong gioi han, mac dinh "
                         "cho LAN/WiFi 6). Chi dung khi mang yeu.")
    ap.add_argument("--webdav-url", default="",
                    help="Base WebDAV rieng (mac dinh: <host>/webdav). "
                         "VD khi API :5050 nhung WebDAV :8822.")
    ap.add_argument("--api-url", default="",
                    help="Base API rieng (mac dinh: <host>).")
    args = ap.parse_args()
    max_bytes = (args.max_mb or 0) * 1024 * 1024
    password = args.password or os.environ.get("NAS_PASS", "")
    if not password:
        sys.exit("Thieu mat khau: truyen --pass ... hoac dat bien NAS_PASS.")

    base = args.host.rstrip("/")
    webdav_base = (args.webdav_url.rstrip("/") if args.webdav_url
                   else base + "/webdav")
    api = args.api_url.rstrip("/") if args.api_url else base

    _log(f"WebDAV: {webdav_base} | API: {api}")
    # Debug root ngay: thay vi nuot im roi bao 0 file.
    _tls = threading.local()

    def _session():
        s = getattr(_tls, "s", None)
        if s is None:
            s = make_session(args.user, password)
            _tls.s = s
        return s

    _root = propfind(_session(), webdav_base, "", debug=True)
    _log(f"PROPFIND root -> {len(_root)} muc "
         f"(vd: {[h for h, _ in _root[:3]]})")
    hw = _ffmpeg_hw_args()
    _log(f"ffmpeg hwaccel: {hw if hw else 'CPU (khong thay GPU)'}")
    _log(f"Liet ke file media tren {webdav_base} ...")
    files = list(walk(_session(), webdav_base))
    _log(f"Tim thay {len(files)} file media.")
    if not files:
        return
    if not HAVE_PIL:
        _log("CANH BAO: thieu Pillow — anh se bi bo qua (pip install pillow).")
    if not _have_ffmpeg():
        _log("CANH BAO: khong thay ffmpeg — video se bi bo qua.")
    workers = args.workers or min(16, (os.cpu_count() or 4) * 2)
    _log(f"Workers: {workers} (CPU x 2).")
    t0 = time.time()

    def _run(p):
        return process_one(_session(), webdav_base, api, p,
                           args.dry_run, max_bytes)

    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = [ex.submit(_run, p) for p in files]
        for f in cf.as_completed(futs):
            f.result()
    dt = time.time() - t0
    _log(f"XONG trong {dt:.0f}s: tong={_stats['total']} hoan-thanh={_stats['done']} "
         f"bo-qua-da-co={_stats['skipped']} loi={_stats['failed']}")


if __name__ == "__main__":
    main()
