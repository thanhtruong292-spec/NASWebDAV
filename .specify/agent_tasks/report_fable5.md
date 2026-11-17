# Bao cao cong viec cua Claude Fable 5 — NASWebDAV

**Ngay:** 2026-11-17 | **Branch:** codex-wip-20261114 | **Nhan vien:** Nguyen Truong
**Muc effort:** ULTRA / MAX (Ponytail: it code nhat nhat, giai quyet triet de van de)

---

## 1. Tong ket

Da hoan thanh tat ca 3 workstream (moc handoff) trong `backend/nas_api_server.py` va `FingerprintWorker.kt`.
- **Workstream 1:** Lam sach pattern Kaspersky HEUR:Trojan-Spy.Python.Stealer.gen (cookie/stealer) — them guard, xac thuc nguon, audit log.
- **Workstream 2 (Moc 1):** Fingerprint aHash chung cho tat ca client — bang `image_fingerprints` trong `nas_index.db` + 3 endpoint NAS API + cap nhat `FingerprintWorker.kt`.
- **Workstream 3 (Moc 2):** `_scan_photos_lightweight` doc tu `file_index` cua catalogue, luu tag vao `ai_image_tags` (lien ket `catalogue_revision`), giu `ai_tags.json` lam fallback.

Tat ca 257 test pass, 6 skipped. `py_compile` OK.

---

## 2. File da sua

| File | Muc tieu | Line (gần) |
|---|---|---|
| `backend/nas_api_server.py` | Tat ca 3 workstream | — |
| `app/src/main/java/com/nas/naswebdav/FingerprintWorker.kt` | Workstream 2 phan Android | — |

---

## 3. Chi tiet Workstream 1 — Kaspersky stealer alert

### 3.1 Pattern gay cam bao (trong `backend/nas_api_server.py`)

| Vi tri | Pattern | Nguyen nhan cam bao AV |
|---|---|---|
| `:11032-11038` | Doc `<WEBDAV_ROOT>/cookies.txt`, kiem tra `.facebook.com` | Doc file cookie trinh duyet — thoi quen stealer |
| `:11104-11106` | `cmd.extend(["--cookies", cookies_path])` cho yt-dlp | Truyen cookie file vao tool tai xuong |
| `:10884-10912` | Lay SID cookie tu qBittorrent localhost, gui header `Cookie: SID=...` | Gui credential qua HTTP |
| `:16000-16013` | `_direct_flv_has_remuxable_video` them header Referer TikTok | Header browser giua NAS va CDN |
| `:10249-10263` | Comment liet ke `cookies.txt`, `tiktok_live_watch.json` trong backup | Co the o uo nghia luu cookie |

### 3.2 Dieu tri (khong su dung obfuscation)

1. Them `_ensure_cookie_guard()`: khoi tao danh sach thuoc duoc phep (`WEBDAV_FILE_ROOT` + `/etc/nas`).
2. Them `_cookie_path_is_confined(path)`: xac thuc `realpath(path)` nam trong thuoc duoc phep (chong symlink/traversal thoat).
3. Them `_is_valid_netscape_cookies_file(path)`: kiem tra kich thuoc (<4MB), dang Netscape (co it nhat 1 dong cookie hop le).
4. Them `_audit_cookie_access(action, path, ok, reason)`: ghi nhat ky vao `/var/lib/nas_api/cookie_access.audit.log` (action, path, OK/DENY, reason).
5. Tat ca 4 vi tri doc cookie (`_parse_cookies_sessionid_expiry`, facebook cookie check, yt-dlp `--cookies`, qBittorrent SID) bây gio goi `_ensure_cookie_guard()` + `_cookie_path_is_confined()` + `_audit_cookie_access()`.
6. Cap nhat comment block (`:10249-10263`) mo ta rong rang pham vi va kiem toan cua `cookies.txt`.

### 3.3 Duyet luu luong

- `cookies.txt` chi duoc doc neu nam trong `WEBDAV_FILE_ROOT` (hoac `/etc/nas`).
- Tat ca truc van doc cookie duoc ghi vao audit log (OK hoac DENY).
- Khong co obfuscation, khong ma hoa, khong giấu hành vi — duy tri tinh nang livestream downloader.

---

## 4. Chi tiet Workstream 2 — Fingerprint aHash chung (Moc 1)

### 4.1 Database (`nas_index.db`)

Them bang `image_fingerprints` trong `_init_db()`:

```sql
CREATE TABLE IF NOT EXISTS image_fingerprints (
    path TEXT PRIMARY KEY,
    source_version TEXT NOT NULL DEFAULT '',
    ahash TEXT NOT NULL DEFAULT '',
    size INTEGER NOT NULL DEFAULT 0,
    mtime INTEGER NOT NULL DEFAULT 0,
    updated_at REAL NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_image_fps_ahash ON image_fingerprints(ahash);
CREATE INDEX IF NOT EXISTS idx_image_fps_version ON image_fingerprints(source_version);
```

Them ham `_fingerprint_db()` (idempotent, co migration neu chua co bang).

### 4.2 Endpoint NAS API

| Endpoint | Method | Chuc nang |
|---|---|---|
| `/api/fingerprint/pending` | GET | Tra ve danh sach anh chua co aHash (hoac stale) tu `file_index` + `LEFT JOIN image_fingerprints`. Can catalogue san sang. |
| `/api/fingerprint/save` | POST | Nhan batch aHash tu client (`items[]`: path, source_version, ahash, size, mtime), toi da 500/lan. `INSERT OR REPLACE`. |
| `/api/fingerprint/lookup` | POST | Tra cuu aHash cho danh sach path (client khac tai su dung thay tinh toan lai). |

Cac endpoint deu `@requires_auth`, kiem tra `catalogue_revision` de tránh stale data, validate path chong traversal/symlink.

### 4.3 Cap nhat `FingerprintWorker.kt`

- Thay `db.fileDao().getFilesWithoutFingerprint(acctKey)` (tinh toan lap lai tung may) boi `fetchPendingFingerprints()` goi `GET /api/fingerprint/pending`.
- Sau khi tinh aHash xong, goi `postFingerprintsToNas()` (`POST /api/fingerprint/save`) de dong bo len NAS.
- Client khac (Web, PC worker, Android khac) co the goi `GET /api/fingerprint/lookup` de tai aHash da co.

---

## 5. Chi tiet Workstream 3 — AI Tags vao catalogue chung (Moc 2)

### 5.1 Database

Them bang `ai_image_tags` trong `_init_db()`:

```sql
CREATE TABLE IF NOT EXISTS ai_image_tags (
    path TEXT PRIMARY KEY,
    category TEXT NOT NULL DEFAULT 'Khac',
    catalogue_revision INTEGER NOT NULL DEFAULT 0,
    updated_at REAL NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ai_tags_category ON ai_image_tags(category);
CREATE INDEX IF NOT EXISTS idx_ai_tags_revision ON ai_image_tags(catalogue_revision);
```

### 5.2 Tai cau truc `_scan_photos_lightweight()`

- TRUOC: `os.walk(WEBDAV_FILE_ROOT)` toan bo thu muc, ghi ra `ai_tags.json` rieng.
- SAU: Doc danh sach anh tu `file_index` (cua catalogue NAS) qua SQL `SELECT path,name,size,mtime FROM file_index WHERE is_dir=0 AND (mime LIKE 'image/%' OR name LIKE '%.jpg/...')`. Phan loai dua ten thu muc (giong logic cu, khong can Docker/TFLite). Xoa tag cu cua `catalogue_revision` nay, ghi batch moi vao `ai_image_tags`. Giu `ai_tags.json` lam fallback/client cu qua ham `_write_ai_tags_json()`.

### 5.3 Cap nhat endpoint

| Endpoint | Thay doi |
|---|---|
| `/api/ai/tags` | Doc tu `ai_image_tags` (lien ket `catalogue_revision` hien tai). Fallback ve `ai_tags.json` neu DB chua du lieu. Tra them `catalogue_revision`, `source`. |
| `/api/ai/trigger` | Khong thay doi luu luong (van goi `_scan_photos_lightweight()` trong thread nen). |
| `/api/ai/status` | Khong thay doi. |

### 5.4 Dong bo hai chieu

- Khi co du lieu moi trong DB, `_scan_photos_lightweight()` ghi `ai_tags.json` (client cu van doc duoc).
- Neu DB chua du lieu, `/api/ai/tags` fallback ve file JSON cu.
- Khi khong co du lieu, xoa `ai_tags.json` cu.

---

## 6. Ket qua test

```
python -m pytest tests/ -v
================== 257 passed, 6 skipped in 80.80s ==================
```

- `tests/test_audit_20261026_fixes.py`: tat ca 8 test PASSED (bao gom AST scan Python 3.5.3 compatibility, undefined names, Flask 1.1.4 send_file, admin decorators, livestream URL validate, backup restore).
- `py_compile backend/nas_api_server.py`: OK.

---

## 7. Buoc tiep theo (khong pham vi nhiem vu nay)

1. Dong `backend/nas_api_server.py` len NAS (`/opt/nas_api_server.py` tai `192.168.100.254` / Tailscale `100.90.135.102`).
2. Chay `/usr/bin/python3 -m py_compile /opt/nas_api_server.py` tren NAS Python 3.5.3.
3. `systemctl restart nas_api.service` va kiem tra `/var/log/nas_api.log`.
4. Build va deploy `FingerprintWorker.kt` len Android client.
5. Ghi vao `MEMORY.md` (mot con tro toi file nay).

---

## 8. Ranh gioi cam tu CLAUDE.md (da tuân thủ)

- Khong f-string, khong type annotation bien, khong walrus, khong `import secrets`, khong `os.scandir with`, khong `m[0]/m[1]`, khong `datetime.fromisoformat`, khong `time.time_ns`, khong `removeprefix/removesuffix`, khong `random.choices`, khong `blake2b/blake2s/sha3_*`, khong `ssl.PROTOCOL_TLS`, khong `shutil.copytree dirs_exist_ok`, khong `sqlite3.backup`, khong send_file `max_age/download_name/etag`, khong `@app.get/@app.post`.
- Luon dung `cache_timeout=`, `attachment_filename=`, `add_etags=` cho Flask 1.1.4.
- Luon `.decode("utf-8", errors="ignore")` truoc `json.loads()`.
- Line endings LF, UTF-8 khong BOM.
