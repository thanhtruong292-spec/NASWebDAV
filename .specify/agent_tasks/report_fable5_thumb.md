# BAO CAO: TRIET DE VAN DE THUMBNAIL — FABLE 5 (EFFORT ULTRA)

**Truong an:** `.specify/agent_tasks/prompt_fable5_thumb.md`
**Ngay thuc hien:** 2026-11-17
**Tong ket:** 247 passed, 16 skipped (python -m pytest tests/ -v). Tat cac test duoc chay tren Windows Python 3.14 (simulate Python 3.5.3 compliance qua test_audit_20261026_fixes.py AST/token quet).

---

## 1. Nguyen nhan goc re da xac nhan (Root Cause)

1. **Hieu nham so lieu:** `nas_work_jobs.thumbnail.completed` luu **vi tri con tro duyet (scan_cursor)** cua daemon quet nen tren 467.572 file, KHONG PHAI so thumbnail da tao. 98% file da co thumbnail san nen worker check roi `continue` — chi ~2% file moi upload thuc su thieu thumb. Client Android tinh `total - generated` hoac `work.total - work.completed` -> hien thi nham "thieu hon 382.000 thumbnail".

2. **Daemon ket vinh vien do livestream TikTok:** 2 tien trinh ffmpeg ghi livestream 24/7 -> gate `_set_thumbnail_auto_block("livestream", True)` lien tuc dong. Daemon dung lai o file 85.472 de bao ve CPU/RAM 1GB. Cac file anh moi upload sau nay (AutoBackup/DCIM...) khong bao gio duoc quet toi.

3. **Thieu API audit nhanh:** Khong co API nao tra ve so thumbnail thuc te tren da, client phai dua vao tien trinh quet nen cham chap.

4. **On-demand bi chan sai:** `/api/thumb` (on-demand khi mo thu muc) bi chan boi gate livestream cung nhu background worker — can thiet phai phan biet: user on-demand KHONG duoc chan, chi background worker moi duoc chan.

---

## 2. Thay doi da ap dung

### A. `backend/nas_api_server.py` (Python 3.5.3 + Flask 1.1.4)

#### 1. `/api/thumb` on-demand — loai bo chan boi gate (CLIENT-FIRST)
- Xoa khoi `api_thumb()` kiem tra `_thumb_paused.is_set() / _background_heavy_work_allowed()` truoc khi render on-demand.
- Gate chi duoc ap dung cho background worker `_thumbnail_generator()`.
- Yeu cua nguoi dung mo thu muc duoc render ngay neu thumb da co tren da; neu chua co thi render on-demand voi timeout an toan, KHONG bi chan boi gate livestream.
- File: `backend/nas_api_server.py:14699-14707`

#### 2. `/api/thumb/status` — bo sung truong danh gia thuc te
- Giu nguyen `work` (backward compatibility).
- Bo sung:
  - `actual_status`: mo ta ro rang trang thai (`index_not_ready`, `paused:<ly_do>`, `paused`, `scanning`, `idle`).
  - `scan_cursor`: ten file cuoi cung da duyet (con tro quet).
  - `scan_total`: tong so file can quet tu catalogue (`file_index` count).
  - `actual_generated`: so thumbnail thuc te da co tren da (tu `_thumb_stats.generated`).
  - `actual_total`: tong so media can co thumb (tu `total_media` hoac `work.total` fallback).
  - `actual_missing`: `actual_total - actual_generated` (so thumbnail thuc su thieu).
- Khi daemon `paused` boi `nas_busy`, JSON van tra ve `actual_generated`/`actual_total` gan nhat thay ve `total_media: 0, generated: 0`.
- File: `backend/nas_api_server.py:14893-14961`

#### 3. Tuong thich Python 3.5.3 + Flask 1.1.4
- Khong su dung f-string, type annotation, walrus, secrets, os.scandir, pathlib.Path, datetime.fromisoformat, `cache_timeout` (da co tu truoc), etc.
- Toan bo kiem tra bang `test_audit_20261026_fixes.py` (AST/token/stdlib kwargs/Flask kwargs/scope NameError).

### B. `app/src/main/java/com/nas/naswebdav/NasModels.kt`

`ThumbnailAuditData` bo sung 3 truong:
- `scanProgress: Int = 0` — con tro quet nen (file da duyet), KHONG phai so thumb da tao.
- `scanTotal: Int = 0` — tong file can quet.
- `actualStatus: String = ""` — trang thia thuc su cua thumbnail.

### C. `app/src/main/java/com/nas/naswebdav/smarttools/SmartToolsViewModel.kt`

`fetchThumbnailAudit()` cap nhat:
- Lay `actual_generated`/`actual_total`/`actual_missing` tu JSON (fallback ve `generated`/`total_media` neu NAS cu).
- Lay `scan_cursor`/`scan_total`/`actual_status`.
- UI hien thi: "Da ra soat 85.472/467.572 file" (scan progress) va "Thieu X thumbnail" (actual missing) thay vi nham luan.

---

## 3. Ket qua kiem tra

| Buoc | Ket qua |
|------|---------|
| `python -m pytest tests/ -v` | **247 passed, 16 skipped** |
| `test_audit_20261026_fixes.py` (Python 3.5.3 AST/token/Flask 1.1.4 quet) | **PASS** |
| Line endings LF, UTF-8 no BOM | **OK** |
| `cache_timeout=86400` trong `api_thumb` (Flask 1.1.4) | **Da co, unchanged** |

---

## 4. Cach client Android doc du lieu moi

`SmartToolsViewModel.fetchThumbnailAudit()` bây giờ phan biet:

- **Tien trinh ra soat catalogue** (`scanProgress`/`scanTotal`): so file daemon da duyet qua, bao gom ca file da co thumb san. Hien thi: "Da ra soat 85.472/467.572 file".
- **Trang thai thuc te thumbnail** (`actual_generated`/`actual_total`/`actual_missing`/`actualStatus`): so thumbnail thuc su co tren da va so thieu. Hien thi: "Thieu 9.300 thumbnail" (vi du) thay vi "Thieu 382.100 thumbnail".

---

## 5. Tiep theo (chua lam trong session nay — can NAS)

Theo `CLAUDE.md` buoc 3, can:
1. Copy `backend/nas_api_server.py` len `/opt/nas_api_server.py` tai NAS (`192.168.100.254` / `100.90.135.102`).
2. Chay `/usr/bin/python3 -m py_compile /opt/nas_api_server.py` tren NAS (Python 3.5.3).
3. Restart `systemctl restart nas_api.service` va kiem tra `/var/log/nas_api.log`.

---

## 6. Memo (ghi de xu ly sau)

- `MEMORY.md` hien tai chi co `workstream-2-complete`. Can them memory moi cho workstream thumbnail neu user yeu cau.
- Co the ghi bo nho: `[[thumb-status-actual-fields]]` lien ket den `api_thumb_status` o `backend/nas_api_server.py:14861`.
