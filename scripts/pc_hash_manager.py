#!/usr/bin/env python3
"""Quan ly file NAS qua ma hash + tim file trung lap — chay tren PC.

1. Liet ke file qua WebDAV PROPFIND (body XML explicit + trailing slash,
   giong app — nginx :8822 tra 405 neu thieu).
2. Voi moi file: HEAD /api/hash/lookup?path=... — co roi thi bo qua.
   Con lai: tai ve, tinh SHA-256 full + MD5 1MB dau (stream, khong nap RAM).
3. POST /api/hash/save theo batch 200 items.
4. Cuoi: GET /api/hash/duplicates?min_size=N — in nhom trung + tong dung
   luong lang phi, ghi ra duplicates.json.

Dung:
    python scripts/pc_hash_manager.py --host http://192.168.100.254:5050 \
        --webdav-url http://192.168.100.254:8822/webdav \
        --user <user> --pass <pass> [--workers 4] [--min-size 4096]
        [--dry-run] [--report duplicates.json]

Khac pc_thumb_batch.py: script nay hash MOI file (khong chi media) de quan
ly + tim trung; khong render thumbnail.
"""
import argparse
import concurrent.futures as cf
import hashlib
import json
import os
import sys
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET

try:
    import requests
except ImportError:
    sys.exit("Thieu 'requests' — chay: pip install requests")

PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop>
  <D:getcontentlength/><D:getlastmodified/><D:getcontenttype/>
  <D:resourcetype/><D:getetag/>
</D:prop></D:propfind>"""

SKIP_DIRS = {".thumbs", ".thumbnails", ".trash", ".nas_meta", ".git",
             ".recycle", "@eadir", "#recycle", ".cache"}

_stats = {"total": 0, "cached": 0, "hashed": 0, "failed": 0}
_lock = threading.Lock()
_pending = []
_pending_lock = threading.Lock()


def _log(*a):
    with _lock:
        print(*a, flush=True)


def _bump(k):
    with _lock:
        _stats[k] += 1


def propfind(session, base, path, depth=1):
    stripped = path.strip("/")
    url = base.rstrip("/") + ("/" + stripped + "/" if stripped else "/")
    try:
        r = session.request("PROPFIND", url,
                            data=PROPFIND_BODY.encode("utf-8"),
                            headers={"Depth": str(depth),
                                     "Content-Type": "application/xml; charset=utf-8"},
                            timeout=30)
    except Exception as e:
        _log(f"[WARN] PROPFIND loi {url}: {e}")
        return []
    if r.status_code not in (200, 207):
        _log(f"[WARN] PROPFIND {url} -> HTTP {r.status_code}")
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
        href, is_dir, size, mtime = "", False, 0, 0
        for child in resp:
            if child.tag.endswith("href") and child.text:
                href = urllib.parse.unquote(child.text.strip())
            if child.tag.endswith("propstat"):
                for p in child.iter():
                    if p.tag.endswith("resourcetype"):
                        for rt in p:
                            if rt.tag.endswith("collection"):
                                is_dir = True
                    elif p.tag.endswith("getcontentlength") and p.text:
                        try:
                            size = int(p.text.strip())
                        except ValueError:
                            pass
                    elif p.tag.endswith("getlastmodified") and p.text:
                        mtime = p.text.strip()
        if href:
            out.append((href, is_dir, size, mtime))
    return out


def walk(session, base):
    """BFS toan bo cay, yield (webdav-path, size)."""
    seen = set()
    queue = [""]
    while queue:
        path = queue.pop(0)
        for href, is_dir, size, _mtime in propfind(session, base, path):
            rel = href.split("/webdav/", 1)[-1] if "/webdav/" in href else href.lstrip("/")
            rel = rel.strip("/")
            if not rel or rel in seen:
                continue
            seen.add(rel)
            parts = rel.lower().split("/")
            if any(p.startswith(".") or p in SKIP_DIRS for p in parts):
                continue
            if is_dir or href.endswith("/"):
                queue.append(rel)
                continue
            yield "/" + rel


def lookup(session, api, path):
    try:
        r = session.get(api + "/api/hash/lookup", params={"path": path}, timeout=20)
        if r.status_code == 200:
            d = r.json()
            if d.get("found"):
                return d
    except Exception:
        pass
    return None


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


def flush_pending(session, api, dry_run):
    with _pending_lock:
        batch = _pending[:200]
        del _pending[:200]
    if not batch:
        return
    if dry_run:
        return
    try:
        r = session.post(api + "/api/hash/save", json={"items": batch}, timeout=60)
        if r.status_code != 200:
            _log(f"[WARN] save batch HTTP {r.status_code}")
    except Exception as e:
        _log(f"[WARN] save batch loi: {e}")


def process_one(session, webdav_base, api, path, dry_run, max_bytes):
    _bump("total")
    known = lookup(session, api, path)
    if known:
        _bump("cached")
        return
    url = webdav_base.rstrip("/") + "/" + urllib.parse.quote(path.lstrip("/"))
    try:
        r = session.get(url, timeout=600, stream=True)
        if r.status_code != 200:
            _bump("failed")
            return
        sha, partial, total = hash_stream(r, max_bytes)
        if not sha:
            _bump("failed")
            if max_bytes > 0:
                _log(f"[SKIP] {path} — qua lon")
            return
    except Exception as e:
        _bump("failed")
        _log(f"[FAIL] hash {path}: {e}")
        return
    _bump("hashed")
    with _pending_lock:
        _pending.append({"path": path, "size": total, "mtime": 0,
                         "sha256": sha, "partial": partial})
        need_flush = len(_pending) >= 200
    if need_flush:
        flush_pending(session, api, dry_run)
    if _stats["total"] % 50 == 0:
        _log(f"... {_stats['total']} file | moi={_stats['hashed']} "
             f"co-san={_stats['cached']} loi={_stats['failed']}")


def report_duplicates(session, api, min_size, report_path):
    try:
        r = session.get(api + "/api/hash/duplicates",
                        params={"min_size": min_size}, timeout=120)
        data = r.json()
    except Exception as e:
        _log(f"[WARN] lay duplicates loi: {e}")
        return
    groups = data.get("groups", [])
    wasted = sum(g.get("wasted_bytes", 0) for g in groups)
    _log(f"TRUNG LAP: {len(groups)} nhom, lang phi ~{wasted // 1048576}MB")
    for g in groups[:20]:
        _log(f"  - {g['count']} file x {g['files'][0].split('/')[-1]} "
             f"({g['wasted_bytes'] // 1024}KB) sha={g['sha256'][:12]}...")
        for p in g["files"][:5]:
            _log(f"      {p}")
    if report_path:
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        _log(f"Da ghi bao cao: {report_path}")


def main():
    ap = argparse.ArgumentParser(description="Quan ly file NAS qua hash + tim trung")
    ap.add_argument("--host", required=True)
    ap.add_argument("--user", required=True)
    ap.add_argument("--pass", dest="password", required=True)
    ap.add_argument("--webdav-url", default="")
    ap.add_argument("--api-url", default="")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--min-size", type=int, default=4096,
                    help="Bo qua file nho hon N bytes khi bao trung (mac dinh 4096)")
    ap.add_argument("--max-mb", type=int, default=0,
                    help="Bo qua file lon hon N MB (0 = khong gioi han)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--report", default="duplicates.json")
    args = ap.parse_args()
    max_bytes = (args.max_mb or 0) * 1024 * 1024

    session = requests.Session()
    session.auth = (args.user, args.password)
    base = args.host.rstrip("/")
    webdav_base = (args.webdav_url.rstrip("/") if args.webdav_url
                   else base + "/webdav")
    api = args.api_url.rstrip("/") if args.api_url else base

    _log(f"WebDAV: {webdav_base} | API: {api}")
    root = propfind(session, webdav_base, "")
    _log(f"PROPFIND root -> {len(root)} muc")
    files = list(walk(session, webdav_base))
    _log(f"Tim thay {len(files)} file.")
    if not files:
        return
    t0 = time.time()
    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = [ex.submit(process_one, session, webdav_base, api, p,
                          args.dry_run, max_bytes) for p in files]
        for f in cf.as_completed(futs):
            f.result()
    flush_pending(session, api, args.dry_run)
    dt = time.time() - t0
    _log(f"XONG trong {dt:.0f}s: tong={_stats['total']} moi={_stats['hashed']} "
         f"co-san={_stats['cached']} loi={_stats['failed']}")
    if not args.dry_run:
        report_duplicates(session, api, args.min_size, args.report)


if __name__ == "__main__":
    main()
