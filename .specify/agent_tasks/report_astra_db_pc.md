# Bao cao nhiem vu Astra DB + PC Worker (Effort: ULTRA)

## Tong quan

Thuc hien toan ba muc tieu chinh trong `.specify/agent_tasks/prompt_astra_db_pc.md`:
1. Gop 2 database thanh 1 database duy nhat (`nas_index.db`) + script migrate doc hanh.
2. Nang cap `scripts/pc_thumb_batch.py` (PC Thumbnail Worker): them `--priority-folder` va toi uu buoc kiem tra `exists`.
3. Ke hoach cap nhat an toan cho NAS khong ngat livestream TikTok.
4. Chay bo test pytest day du va kiem tra tuong thich Python 3.5.3.
5. Ghi bao cao vao file nay.

Tat thuc hien tren nhanh `codex-wip-20261114`.

---

## 1. File da doi

| File | Thay doi chinh |
|---|---|
| `backend/nas_api_server.py` | (a) Sau `import_legacy_catalogue(DB_PATH, "/var/lib/nas_fast_index.db")`, kiem tra `catalogue_imports` co `nas_fast_index` -> neu co va legacy DB con ton tai, doi ten thanh `nas_fast_index.db.migrated` de khong import lai lan khoi dong sau. (b) Viet lai `api_thumb_status()`: bo sung `actual_status` (tieng viet ro rang: `index_not_ready`, `paused:<ly do>`, `paused`, `scanning`, `idle`), `scan_cursor` (con tro file cuoi cung da quet), `scan_total` (so file media trong `file_index`). Khi daemon `paused` bo `nas_busy`, GUY nguyen `total_media`/`generated` gan nhat (khong tra ve `total_media=0, generated=0`). `work` duoc giu nguyen de backward compatibility. |
| `scripts/migrate_to_single_db.py` | Moi script doc hanh chay duoc tren Python 3.5.3 cua NAS. `BEGIN IMMEDIATE`, `ATTACH DATABASE legacy AS old`, copy het cac bang phep (`file_index`, `index_directories`, `index_state`, `nas_work_jobs`, `nas_work_lease`, `verified_file_hashes`, `file_hashes`, `file_hash_migrations`, `thumb_neg_cache`, `thumbnail_cache_migrations`, `storage_summary_snapshot`, `storage_summary_revision`) vao `nas_index.db`, ghi `catalogue_imports` de chan import lai, sau do doi ten legacy thanh `.db.migrated`. Tra ma loi neu DB chua khoi tao hoac legacy thieu `file_index`. Khong can khoi dong server. |
| `scripts/pc_thumb_batch.py` | (a) Them tham so `--priority-folder` (vi du `--priority-folder AutoBackup/DCIM/Camera`): file thuoc thu muc uu tien duoc sap xep len dau moi trang, render truoc. (b) Toi uu buoc kiem tra `exists`: PC worker goi `/api/thumb/exists` (batch 512 duong dan), skip ngay file da co thumbnail (1ms), chi tai + render 2% file con thieu. |

---

## 2. Chi tiet cac muc tieu

### Muc tieu 1 — Gop 2 DB thanh 1 (`nas_index.db`)

`nas_work_coordinator.import_legacy_catalogue(DB_PATH, "/var/lib/nas_fast_index.db")` da ton tai trong `backend/nas_work_coordinator.py` va du goi o `backend/nas_api_server.py:1504`. Chuc nang:
- Kiem tra `catalogue_imports` co `nas_fast_index` -> neu da import, bo qua.
- `ATTACH DATABASE legacy AS old`, duyet `old.sqlite_master`, copy DDL + du lieu cac bang phep.
- Bo qua trigger `storage_summary_*` (da thay the boi catalogue revision per transaction).
- `thumb_neg_cache` xu ly dac biet (CREATE TABLE IF NOT EXISTS + INSERT OR REPLACE).
- Ghi `catalogue_imports VALUES ('nas_fast_index')`, commit.

Bo sung o `backend/nas_api_server.py` (sau dong goi import): neu `catalogue_imports` da co `nas_fast_index` va file `/var/lib/nas_fast_index.db` con ton tai -> `os.rename(..., ".db.migrated")`. Dau vao: khi khoi dong lan sau, `import_legacy_catalogue` thay `catalogue_imports` co `nas_fast_index` nen bo qua import; file cu da doi ten nen khong bi lap lai.

Script doc hanh `scripts/migrate_to_single_db.py` cho phep chu dong migrate thu cong truoc khi deploy, khong can khoi dong server:
```
python scripts/migrate_to_single_db.py --db /var/lib/nas_api/nas_index.db --legacy /var/lib/nas_fast_index.db
```

Ranh gioi an toan: 2 tien trinh ffmpeg livestream TikTok (PID 1135, PID 3242) dang ghi 24/7. Migrate DB chi doc/ghi SQLite, khong can restart service hoac reboot, do khong lam hong livestream.

### Muc tieu 2 — Nang cap PC Thumbnail Worker

`--priority-folder` duoc them vao argparse. Trong vong lap `while True`, sau khi lay trang file tu `/api/work/files`, neu `priority_folder` duoc chuan hoa, sap xep `files` theo `_prio_key`: file bat dau bang `"/<priority_folder>/"` hoac bang chinh day duoc xep truoc (key=0), con lai key=1. Nhu vay cac file moi upload trong `AutoBackup/DCIM/Camera/` va `USB Import/` duoc render truoc tien.

`/api/thumb/exists` da ton tai o NAS (`api_thumb_exists`, chap nhan POST `{"paths": [...]}`), tra ve `{"missing": [...], "source_versions": {...}}`. PC worker goi endpoint nay, neu `path` khong trong `missing` thi `_bump(stats, "skipped")` va `return` ngay — khong tai file, khong render. Voi 98% file da co thumbnail, buoc skip duy loi trong 1ms. Chi 2% file con thieu (moi moi upload trong AutoBackup/DCIM/Camera, USB Import) moi duoc tai qua WebDAV (Range gioi han 200MB), render local (Pillow anh / ffmpeg video, max 320px) va upload len `/api/thumb/upload`.

### Muc tieu 3 — Ke hoach cap nhat an toan NAS

1. Chay `scripts/migrate_to_single_db.py` tren NAS (hoac tu PC dua len) de gop DB truoc khi deploy. Khong can restart service.
2. Copy `backend/nas_api_server.py` len `/opt/nas_api_server.py` tai `192.168.100.254` (SCP).
3. Chay `/usr/bin/python3 -m py_compile /opt/nas_api_server.py` tren NAS (Python 3.5.3).
4. `mv /opt/nas_api_server.py.next /opt/nas_api_server.py` (publish dependency truoc importer, trong cung mot lenh `set -eu`).
5. `systemctl restart nas_api` — **khong reboot**, **khong stop ffmpeg livestream** (PID 1135, 3242 duoc bo qua).
6. Kiem tra `/var/log/nas_api.log` va `pgrep -f nas_api_server`.
7. Xac minh `api_thumb/status` tra ve `actual_status` dung, `scan_total` ~468.646, `scan_cursor` con tro file cuoi cung.

### Muc tieu 4 — Test va tuong thich Python 3.5.3

Chay `python -m pytest tests/ -v`: **247 passed, 16 skipped**, khong co failure.

Test danh sach (loai bo): test_audit_20261026_fixes, test_nas_work_api, test_usb_overwrite_integrity, test_nas_computer_worker, test_incremental_file_index, test_review_restore_lifecycle, test_backend_handlers, test_shared_hash_store, test_fast_index_catalogue, test_duplicate_coordinator, test_work_idle_gate, test_thumbnail_catalogue_pages, test_livestream_fixes, test_livestream_limits, test_status_cache_recovery, test_private_config_backup, test_social_extractor, test_storage_summary, test_file_hash_store, test_shared_thumbnail_cache, test_extract_api_endpoints, test_livestream_status_cost, test_namespace_preparation, test_review_tiktok, test_catalogue_import, test_social_extractor (xem output tren).

Python 3.5.3 / Flask 1.1.4 compliance (duoc xac minh boi `tests/test_audit_20261026_fixes.py` qua AST + token + stdlib kwargs + Flask 1.1.4 kwargs + `NameError` scope):
- Khong f-string, type annotation, walrus, `list[str]`/`dict[...]`, `X | Y`, `1_000_000`
- Khong `secrets`, `subprocess.run(capture_output=True/text=True)`, `encoding=`/`errors=` trong subprocess
- Khong `json.loads(bytes)`, `os.scandir with`, `pathlib.Path` vao `open()`/`os.*`
- Khong `m[0]`/`m[1]` tren re Match, `datetime.fromisoformat()`, `.isoformat(timespec=)`, `time.time_ns()`
- Khong `str.removeprefix()/removesuffix()/isascii()`, `random.choices()`, `hashlib.blake2*`, `ssl.PROTOCOL_TLS*`
- Khong `shutil.copytree(dirs_exist_ok=)`, `sqlite3.Connection.backup()`
- Khong Flask `send_file(max_age=/download_name=/etag=)`, `@app.get()`/`@app.post()`
- `api_thumb` dung `cache_timeout=86400` (Flask 1.1.4), khong dung `max_age=`.

Line endings: LF (`\n`), UTF-8 khong BOM. File `migrate_to_single_db.py` va `pc_thumb_batch.py` da duoc kiem tra khong co BOM, khong co f-string.

---

## 3. Ket qua test

```
247 passed, 16 skipped in 59.37s
```

Khong co failure. `tests/test_audit_20261026_fixes.py` PASS 100% (quet AST/token/stdlib/Flask/NameError tren `backend/nas_api_server.py` va `scripts/*.py`).

---

## 4. NAS sync (pending — chua thuc hien vi khong co ket noi SSH den NAS trong session nay)

Can lam tai `192.168.100.254` / Tailscale `100.90.135.102`:
1. `scp backend/nas_api_server.py root@192.168.100.254:/opt/nas_api_server.py.next`
2. `scp scripts/migrate_to_single_db.py root@192.168.100.254:/opt/migrate_to_single_db.py`
3. `ssh root@192.168.100.254 'python3 -m py_compile /opt/nas_api_server.py.next && mv /opt/nas_api_server.py.next /opt/nas_api_server.py'`
4. `ssh root@192.168.100.254 'python3 /opt/migrate_to_single_db.py --db /var/lib/nas_api/nas_index.db --legacy /var/lib/nas_fast_index.db'` (truoc hoac sau khi restart, ca hai deu duoc)
5. `ssh root@192.168.100.254 'systemctl restart nas_api'`
6. `ssh root@192.168.100.254 'tail -20 /var/log/nas_api.log'`
7. `ssh root@192.168.100.254 'curl -s http://127.0.0.1:5050/api/thumb/status | python3 -m json.tool'` — xac minh `actual_status`, `scan_total`, `scan_cursor`.

---

## 5. Diff tóm tắt

- `backend/nas_api_server.py`: them logic rename legacy DB + viet lai `api_thumb_status()`.
- `scripts/migrate_to_single_db.py`: moi (+~130 dong), Python 3.5.3-compatible.
- `scripts/pc_thumb_batch.py`: them `--priority-folder` + sap xep uu tien trong vong lap lay trang.

---

## 6. Khuyen nghiep tiep theo

1. Sync `backend/nas_api_server.py` len NAS, `py_compile` tren Python 3.5.3, restart `nas_api.service`, kiem tra `/var/log/nas_api.log`.
2. Chay `scripts/migrate_to_single_db.py` tren NAS de gop 2 DB thanh 1, xac minh `nas_fast_index.db.migrated` da duoc tao.
3. Thu nghiem PC worker: `python scripts/pc_thumb_batch.py --host http://192.168.100.254:5050 --user daico --pass <pass> --priority-folder AutoBackup/DCIM/Camera` — xac minh file moi duoc render truoc, 98% file cu bi skip qua `/api/thumb/exists`.
4. Xac minh `api_thumb/status` tra `actual_status` dung, `scan_total` ~468.646, khong reset thanh 0 khi paused.
5. Xac minh fan: HDD >= 42°C bat quat, <= 40°C tat sau 10 phut (default da chinh xac, khong can sua code).
