# NHIEM VU CHO CLAUDE FABLE 5: XU LY TRIET DE VAN DE THIET THUMBNAIL (EFFORT: ULTRA)

## 1. Boi canh thuc te da khao sat tren NAS that (192.168.100.254)
- Thu muc thumbnail tren NAS `/srv/dev-disk-by-label-data/New folder/.thumbs` thuc te dang co toi **898.563 file thumbnail** (16 GB)!
- Tong so file media trong catalogue `file_index` la **465.714 file**.
- Khao sat thuc nghiem 10.000 file media cho thay: **98.00% da co thumbnail san tren dia**! Chi co khoang ~2% (cac anh/video moi upload gan day trong AutoBackup/DCIM/...) la thuc su thieu thumbnail.
- Tuy nhien, UI Android va Dashboard bao: Da tao `85.472 / 467.572`, dang thieu hon 382.000 thumbnail va job thumbnail bi `paused (error: nas_busy)`!

## 2. Nguyen nhan goc re (Root Cause)
1. **Hieu nham ve so lieu:** Job `thumbnail` trong `nas_work_jobs` luu `completed = 85472` la **vi tri con tro duyet (cursor)** cua daemon quet nen tren tong so 467.572 file, chu KHONG PHAI la so thumbnail da tao. 85.472 file da duyet qua do 98% da co thumbnail san, worker chi check roi bo qua (`continue`). Client Android (`SmartToolsViewModel.kt:406-415`) lay `total - generated` hoac `work.total - work.completed` nen hien thi nham la dang thieu hon 382.000 thumbnail!
2. **Daemon nen bi ket vinh vien do livestream TikTok:** 2 tien trinh ffmpeg ghi livestream TikTok chay 24/7 khien gate `_set_thumbnail_auto_block("livestream", True)` lien tuc dong. Daemon quet nen tren NAS dung lai o file 85.472 de bao ve CPU/RAM 1GB cua NAS. Hau qua la cac file anh moi upload sau nay (nhu trong AutoBackup) khong bao gio duoc daemon quet toi!
3. **Thieu co che audit nhanh so thumbnail thuc te:** Khong co API nao cho client biet so thumbnail thuc te da co tren dia ma phai dua vao tien trinh quet nen cham chap.

## 3. Cac viec can thuc hien (Yeu cau Ponytail: It code nhat, giai quyet triet de)
1. **Trong `backend/nas_api_server.py`:**
   - Trong `api_thumb_status()`: Bo sung thong tin ro rang trong JSON tra ve:
     - Giu nguyen `work` cho backward compatibility.
     - Bo sung cac truong danh gia thuc te: `actual_status`: gia thich ro rang `scan_cursor`, `scan_total`, va trang thai gate.
     - Khi daemon `paused` do `nas_busy`, khong tra ve `total_media: 0, generated: 0` ma phai giu so lieu gan nhat hoac so lieu thuc tu catalogue.
   - Toi uu endpoint on-demand `/api/thumb`: Dam bao khi nguoi dung mo thu muc tren app Android, thumbnail duoc tra ve ngay neu da co tren dia; neu chua co thi render on-demand voi timeout an toan ma KHONG bi chan boi gate livestream (gate chi chan background worker chu khong chan user on-demand request).
2. **Trong Android (`app/src/main/java/com/nas/naswebdav/smarttools/SmartToolsViewModel.kt`):**
   - Cap nhat cach tinh toan trong `fetchThumbnailAudit()`: Phân biet ro giua "Tien trinh ra soat catalogue" (`scan_progress`) va trang thai thuc te cua thumbnail. Hien thi dung nghia la "Da ra soat 85.472/467.572 file" thay vi "Thieu 382.100 thumbnail".
3. **Kiem tra va dam bao 100% test pass:**
   - Chay `python -m pytest tests/ -v`.
   - Dam bao tuong thich tuyet doi Python 3.5.3 tren ARM64 Debian 9.
4. **Ghi bao cao chi tiet vao `.specify/agent_tasks/report_fable5_thumb.md`.**
