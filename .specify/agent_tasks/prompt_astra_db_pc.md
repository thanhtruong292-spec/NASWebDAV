# NHIEM VU CHO GPT-6 ASTRA: XU LY PHAT HIEN 2 DATABASE & TOI UU PC WORKER (EFFORT: ULTRA)

## 1. Boi canh thuc te da khao sat tren NAS that (192.168.100.254)
- **NAS van dang mo 2 database doc lap:**
  1. `/var/lib/nas_api/nas_index.db` (chua system_logs, runtime_state...)
  2. `/var/lib/nas_fast_index.db` (chua file_index 468.646 hang, nas_work_jobs...)
- **PC Worker chua tap trung vao cac file moi:** 98% trong so 465.714 file media da co thumbnail san trong `.thumbs`. Nhung file thuc su thieu nam chu yeu o thu muc moi `AutoBackup/DCIM/Camera/` va `USB Import/`.
- **Ranh gioi an toan:** 2 tien trinh ffmpeg livestream TikTok (PID 1135, PID 3242) dang ghi 24/7 tren NAS. Khong duoc restart service hoac reboot lam hong livestream.

## 2. Cac viec can thuc hien (Yeu cau Ponytail: It code nhat, giai quyet triet de)
1. **Xu ly 2 Database thanh 1 Database duy nhat (`nas_index.db`):**
   - Rà soat va kiem tra ham `nas_work_coordinator.import_legacy_catalogue(DB_PATH, "/var/lib/nas_fast_index.db")` trong `backend/nas_api_server.py`.
   - Dam bao khi server khoi dong tren NAS voi ban moi, neu con ton tai `/var/lib/nas_fast_index.db`, no se tu dong import sach se toan bo `file_index`, `index_directories`, `index_state`, `verified_file_hashes`, `nas_work_jobs`, `nas_work_lease` vao `/var/lib/nas_api/nas_index.db`, sau do doi ten thanh `.db.migrated` de khong import lai.
   - Tao mot script doc lap: `scripts/migrate_to_single_db.py` (chay duoc tren Python 3.5.3 cua NAS) de co the chu dong chay migrate thu cong truoc khi deploy ma khong can khoi dong lai server!
2. **Nang cap `scripts/pc_thumb_batch.py` (PC Thumbnail Worker):**
   - Them tham so `--prefix` hoac `--priority-folder` (vi du `--prefix AutoBackup/`) de PC worker co the uu tien quet va render thumbnail cho cac file moi upload truoc tien, thay vi phai duyet qua 85.000 file cu da co thumbnail.
   - Toi uu buoc kiem tra `exists`: PC worker kiem tra nhanh qua `/api/thumb/exists` de skip 98% file da co trong 1ms, chi tap trung render 2% file con thieu!
3. **Ke hoach cap nhat an toan cho NAS:**
   - Soan thao kieu huong dan va script deploy khong ngat livestream trong bao cao.
4. **Kiem tra va dam bao 100% test pass:**
   - Chay `python -m pytest tests/ -v`.
   - Dam bao tuong thich tuyet doi Python 3.5.3 tren ARM64 Debian 9.
5. **Ghi bao cao chi tiet vao `.specify/agent_tasks/report_astra_db_pc.md`.**
