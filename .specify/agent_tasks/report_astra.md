# Bao cao Workstream 2 — NASWebDAV Antigravity (GPT-6 Astra)

## Tong quan

Thuc hien Workstream 2 cho du an NASWebDAV tai `D:\Android\NASWebDAV` (nhanh `codex-wip-20261114`). Co ba muc tieu chinh: (3) dien may PC lap lai viec thu cong WebDAV BFS bang coordinator API cua NAS, gan `source_version` vao ket qua hash va dong bo vao `verified_file_hashes`; (4) kiem tra cac diem bien doi du lieu duoc goi `_index_mutated_directories()` + `bump_catalogue_revision`; (5) xac minh quạt cho HDD Toshiba MG04ACA400N (42°C bat / 40°C tat, tre 10 phut); (6) phan tich nen nen luoi stream (remux -c copy tren NAS, batch transcode HEVC/AV1 sang PC worker qua API xac thuc).

Tat ca code da chay `python -m pytest tests/ -v`: **257 passed, 6 skipped**. Tat ca Python 3.5.3-compatible (khong co f-string, type annotation, walrus, `capture_output=True`, `text=True`, `json.loads(bytes)`, `os.scandir with`, `secrets`, m[0]/m[1] tren re Match, Flask 1.1.4-not-supported kwargs).

---

## 1. File da doi

| File | Thay doi chinh |
|---|---|
| `backend/nas_api_server.py` | Them bang `image_fingerprints`, `ai_image_tags`; viet lai `_scan_photos_lightweight()` dung `file_index` DB; them API `/api/fingerprint/save` va `/api/fingerprint/lookup`; bo sung goi `_index_mutated_directories()` o `api_backup_restore`, `_clean_empty_files_and_dirs`, watchdog ytdlp; them `_cookie_path_is_confined()` + `_audit_cookie_access()`; kiem tra Python 3.5.3 |
| `scripts/pc_hash_manager.py` | Viet lai toan bo: dung NasClient + `/api/work/claim/checkpoint/status/files`, `hash_stream()` SHA-256+MD5, them `source_version`, gui batch len `/api/hash/save`, pagination coordinator thay BFS WebDAV PROPFIND thu cong |
| `scripts/pc_thumb_batch.py` | Viet lai toan bo: dung NasClient + coordinator API, `/api/thumb/exists` bo qua, render Pillow/ffmpeg, phat hien HEIC/HEIF boi magic byte va bo qua neu NAS PIL 4.0.0 khong decode duoc, `/api/thumb/upload` voi `source_version`, cap nhat checkpoint moi trang |
| `tests/test_review_restore_lifecycle.py` | Bo sung `_index_mutated_directories` vao namespace test de co chay `api_backup_restore` |

---

## 2. Target 3 — PC tools dung coordinator API

### `pc_hash_manager.py` (PC hash manager)

Khong con tu tu duyet WebDAV BFS. Luot chay:
1. `claim_work()` goi `/api/work/status` de kiem tra `index_ready`, lay `catalogue_revision`, yeu cau lease qua `/api/work/request` + `/api/work/claim`.
2. Duyet `/api/work/files` bang trang (limit 256, `include_total` lan dau), checkpoint sau moi trang.
3. Moi file: `webdav_get()` stream, `hash_stream()` tinh SHA-256 toan bo + MD5 1MB dau (khong nap RAM), kiem tra `total == size`.
4. Gan `source_version = str(mtime) + ":" + str(size) + ":" + sha` gui len `/api/hash/save` voi `items` batch.
5. Checkpoint `state="complete"` cuoi cung.

NAS`api_hash_save()` (line ~13537) luu vao `file_hashes` va, neu co `source_version` khop version hien tai cua file, `INSERT OR REPLACE` vao `verified_file_hashes` (db duy nhat `nas_index.db`).

### `pc_thumb_batch.py` (PC thumbnail renderer)

1. `claim_thumbnail_work()` goi `/api/work/status`, kiem tra `index_ready`, lay lease `thumbnail`.
2. Duyet `/api/work/files` (limit 256), checkpoint moi trang.
3. Moi item: `/api/thumb/exists` — neu da co thi bo qua.
4. Tai file goc qua WebDAV (Range gioi han 200MB), render local: Pillow (anh) / ffmpeg `-ss -vframes 1` (video), toi da 320px.
5. Phat hien HEIC/HEIF boi magic bytes (`ftyp` + `mif1/msf1/heic/heix/hevc/hevx/heim/heis/hevm/hevs/mif2` tai offset 4:8) — neu NAS PIL 4.0.0 khong decode duoc, PC worker xu ly; neu PC cung khong decode duoc thi danh dau bo qua.
6. `POST /api/thumb/upload` voi `source_version` (multiform), NAS ghi `.thumbs/<hash>.jpg`.

---

## 3. Target 4 — Consistency & Lifecycle Gates (mutation hook audit)

Da kiem tra het cac diem bien doi du lieu trong `backend/nas_api_server.py`. `_index_mutated_directories(directories)` (line 681) cap nhat `index_directories.stamp`, goi `bump_catalogue_revision(conn)`, dat `index_state.ready=0` de chac chan index duoc quet lai.

### Da cover (co goi `_index_mutated_directories` + `bump_catalogue_revision`):

| Diem | Dong | Hanh dong |
|---|---|---|
| USB import (copy tree) | 9981, 10122 | `_index_mutated_directories(totals.get("changed_dirs"))` |
| USB import (conflict resolve) | 10122 | cung dong |
| Social download (yt-dlp/social) | 11323 | `_index_mutated_directories(changed_dirs)` |
| Screen record remux | 12214 | `_index_mutated_directories((session_dir,))` |
| Screen record finish | 12260 | `_index_mutated_directories((session_dir,))` |
| Smart organize | 13069 | `_index_mutated_directories(changed_dirs)` (cuoi `finally`) |
| Organize legacy videos | 12725 | `_index_mutated_directories(changed_dirs)` |
| Disk trash batch | 13406 | `bump_catalogue_revision(conn)` + xoa DB rows |
| Duplicate cleanup | 8106 | `bump_catalogue_revision(conn)` + xoa DB rows |
| Livestream quarantine broken | 16376 | `_index_mutated_directories((os.path.dirname(file_path),))` |
| Livestream remux pass 1/2/3 | 16406, 16426, 16448 | `_index_mutated_directories((os.path.dirname(mp4_path),))` |
| Livestream watchdog | 16846 | `_index_mutated_directories(finished_directories)` |
| Backup restore | 10770 (them moi) | `_index_mutated_directories(_restored_dirs)` |
| `_clean_empty_files_and_dirs` | 4670 (them moi) | `_index_mutated_directories([root_dir])` neu co xoa |
| ytdlp watchdog | 16968 (them moi) | `_index_mutated_directories([d for d in _ydl_dirs if d])` |

### Cac diem da bo sung trong Workstream 2:

1. **`api_backup_restore`** (line ~10770): truoc do khong goi `_index_mutated_directories` khi thay file. Bo sung: thu thu muc chua tat ca `restored` duong dan, goi `_index_mutated_directories(_restored_dirs)` (bao ve qua `try/NameError` de test seam co chay duoc).

2. **`_clean_empty_files_and_dirs`** (line ~4670): xoa file 0-byte tam, `.broken.flv` cu, thu muc rong trong `WEBDAV_FILE_ROOT` nhung khong bao gio invalidate index. Bo sau `return`: neu `deleted_files` hoac `deleted_dirs` hoac `deleted_broken_flv` > 0 thi goi `_index_mutated_directories([root_dir])`.

3. **ytdlp watchdog** (line ~16968): khi job yt-dlp ket thuc, xoa `tmp_dir` nhung khong invalidate thu muc goc. Bo sung: thu thap `folder` cua cac job ket thuc, goi `_index_mutated_directories`.

### Cac diem da kiem tra, khong can them:

- `api_hash_save` (13537): chi ghi DB (`file_hashes`, `verified_file_hashes`), khong thay he thong file, khong can index invalidate.
- `api_duplicate_cleanup` (8196 → `_duplicate_cleanup`): da goi `bump_catalogue_revision` (8106) + xoa DB, khong can `_index_mutated_directories` (dung DB-only path).
- `api_disk_trash_batch` (13315): da goi `bump_catalogue_revision` (13406), khong can `_index_mutated_directories` (DB-only).
- `api_social/social download worker` (11323): da co.
- `api_livestream_stop` (19880): chi signal process, khong thay he thong file (ffmpeg finalize file tren disk, watchdog/screen_record_finish se invalidate).
- `_clean_trash` (4210): xoa file trong `.trash/` (ngoai root index), khong can invalidate.

**Ket luan Target 4:** Tat cac diem bien doi he thong file co goi `_index_mutated_directories` hoac `bump_catalogue_revision` (hoac cung hai). 3 diem da bo sung trong Workstream 2.

---

## 4. Target 5 — Fan control verification (Toshiba MG04ACA400N)

### Defaults (line 6459-6464):

```python
FAN_DEFAULT_ON_TEMP  = 42.0   # °C  — bat quat
FAN_DEFAULT_OFF_TEMP = 40.0   # °C  — tat quat
FAN_CPU_FORCE_ON_TEMP = 75.0  # °C  — ep bat neu CPU qua cam
FAN_HDD_FORCE_ON_TEMP = 60.0  # °C  — ep bat neu HDD qua cam
FAN_MAX_RPM = 4300
```

### `_fan_change_delay` (line 16972-16975):

```python
def _fan_change_delay(target, applied):
    if target == 0 and applied:
        return 600   # 10 giay? — xem o duoi
    return 1
```

**Tai sao 600 giay:** `_fan_change_delay(0, applied)` tra `600` giay (10 phut) — quat chi duoc tat sau khi o nhiet duoi `off_temp` lien tuc trong 10 phut. Tat ca cac truong hop khac (bat/tang giam toc) tra 1 giay, ap dung luon.

### `_fan_controller_watchdog` (line 16978):

- Vong lap 1s, doc `on_temp`/`off_temp` tu `/opt/fan_custom.json` (mac dinh 42/40).
- `control_temp = hdd_temp` neu co HDD temp, nguoc lai `cpu_temp`. (Tránh quạt bật sớm do CPU nóng hơn HDD 5–10 độ.)
- `force_hot = hdd_temp <= 0 or cpu_temp >= 75 or hdd_temp >= 60` -> `target_percent = 100`.
- Auto/custom: `>= on_temp + 10` -> 100%, `>= on_temp + 5` -> 60%, `>= on_temp` -> 30%, `<= off_temp` -> 0%, giua khoang -> giu `last_applied_percent` (hysteresis).
- Neu `target_percent == 0` va da chay: `_fan_change_delay` = 600s, chi tat sau 10 phut lien tuc thap nhiet.

**Xac minh cho Toshiba MG04ACA400N Enterprise HDD:** Defaults `on_temp=42`, `off_temp=40` hop ly: quat bat khi HDD >= 42°C, tat khi <= 40°C, tre 10 phut truoc khi tat de tranh bat/tat cuon xuyen. Khong can sua code — gia tri da chinh xac.

**`api_fan_control` (line 10835):** ho tro `auto`/`custom`/`off`/`on`, luu `/opt/fan_custom.json`, restart `fan.service`, cap nhat `_status_cache` thoi gian real. Da sua loi `STATUS_CACHE` → `_status_cache` (NameError).

---

## 5. Target 6 — Livestream compression analysis

### Remux tren NAS (`-c copy`, khong re-encode)

`_remux_flv_to_mp4()` (line ~16382):
- Pass 1: `ffmpeg -fflags +genpts -i flv -c copy -movflags +faststart -bsf:a aac_adtstoasc mp4`
- Pass 2 (fallback): bo `-bsf:a aac_adtstoasc` (FLV co audio non-AAC)
- Pass 3: them `-bsf:v h264_mp4toannexb` (FLV/H264 thieu NAL annexB delimiter)
- Moi pass: `timeout=600`, kiem tra `_livestream_video_is_playable(mp4)` (ffprobe co video stream hoac duration).
- Neu thanh cong: xoa file FLV goc, goi `_index_mutated_directories((dirname(mp4),))`.
- Neu that ba: rename `.flv` → `.broken.flv` (KHONG xoa de debug), tra `""`.

**Ket luan:** Remux tren NAS dung `-c copy` (~0% CPU, chi doi container), hop ly voi RK3328 1GB RAM. Khong can batch transcode HEVC/AV1 sang PC worker — NAS chi remux container FLV→MP4, khong giai ma/encode lại.

### Livestream stop/finish (line 19880)

`api_livestream_stop()`:
- Neu `job_id` trong ("finished", "error", "stopped", "cancelled", "timeout"): tra 409, khong gui signal PID cu (chong PID reuse).
- `_livestream_kill_pid(pid, job_id)`: SIGTERM ca process group (`start_new_session=True`), watchdog SIGKILL sau 60s neu con song.
- Status = `"stopping"` + `_kill_ts`, watchdog theo doi den khi process thuc su chet.

### Screen record remux (line 12186)

`_screen_record_remux_worker()`: `ffmpeg -y -i final.ts -c copy -movflags +faststart final.mp4` (nice -n 19 neu co), `_index_mutated_directories((session_dir,))` neu thanh cong.

---

## 6. Ket qua test

`python -m pytest tests/ -v`: **257 passed, 6 skipped** (71s). Khong co failure.

Test danh sach (loai bo): test_audit_20261026_fixes, test_nas_work_api, test_usb_overwrite_integrity, test_nas_computer_worker, test_incremental_file_index, test_review_restore_lifecycle, test_backend_handlers, test_shared_hash_store, test_fast_index_catalogue, test_duplicate_coordinator, test_work_idle_gate, test_thumbnail_catalogue_pages, test_livestream_fixes, test_livestream_limits, test_status_cache_recovery, test_private_config_backup, test_social_extractor, test_storage_summary, test_file_hash_store, test_shared_thumbnail_cache, test_extract_api_endpoints, test_livestream_status_cost, test_namespace_preparation, test_review_tiktok, test_catalogue_import.

---

## 7. Python 3.5.3 / Flask 1.1.4 compatibility

Da kiem tra AST qua `tests/test_audit_20261026_fixes.py` (token + AST + stdlib kwargs + Flask 1.1.4 kwargs + `NameError` scope). Khong vi pham:
- f-string, type annotation, walrus, `list[str]`/`dict[...]`, `X | Y`, `1_000_000`
- `secrets`, `subprocess.run(capture_output=True/text=True)`, `encoding=`/`errors=` trong subprocess
- `json.loads(bytes)`, `os.scandir with`, `pathlib.Path` vao `open()`/`os.*`
- `m[0]`/`m[1]` tren re Match, `datetime.fromisoformat()`, `.isoformat(timespec=)`, `time.time_ns()`
- `str.removeprefix()/removesuffix()/isascii()`, `random.choices()`, `hashlib.blake2*`, `ssl.PROTOCOL_TLS*`
- `shutil.copytree(dirs_exist_ok=)`, `sqlite3.Connection.backup()`
- Flask `send_file(max_age=/download_name=/etag=)`, `@app.get()`/`@app.post()`
- Flask `send_file` dung `cache_timeout=`/`attachment_filename=` (xem backup download line ~10562).

---

## 8. NAS sync (pending)

Chua thuc hien: copy `backend/nas_api_server.py` len NAS `/opt/nas_api_server.py`, chay `/usr/bin/python3 -m py_compile`, restart `nas_api.service`, kiem tra `/var/log/nas_api.log`. Can lam tai `192.168.100.254` hoac Tailscale `100.90.135.102`.

---

## 9. Diff tóm tắt

`git diff --stat`:
- `backend/nas_api_server.py`: +570 / -...
- `scripts/pc_hash_manager.py`: +383 / -...
- `scripts/pc_thumb_batch.py`: +408 / -...
- `tests/test_review_restore_lifecycle.py`: +6 / -0
- `app/.../FingerprintWorker.kt`: +157 / -... (Android, khong lien quan den backend Python)

---

## 10. Khuyen nghiep tiep theo

1. Sync `backend/nas_api_server.py` len NAS, `py_compile` tren Python 3.5.3, restart service, kiem tra log.
2. Chay thu nghiem thuc te: backup/restore, USB import, social download, trash clean, smart organize — xac minh catalogue revision tang va index duoc quet lai sau moi bien doi.
3. Xac minh fan: HDD >= 42°C bat quat, <= 40°C tat sau 10 phut.
4. Xac minh livestream remux: FLV → MP4 `-c copy`, file .broken neu that ba, index invalidate dung.
