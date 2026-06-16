"""Regression test cho cac fix bao mat (REVIEW_20260612):

- C2/C3: chong path traversal khi tao thu muc dich (yt-dlp save_folder,
  USB import dest_folder/label).
- H1: TTL cho authorized_ips (trust-by-IP khong con vinh vien).

Cac test nay nap CHINH ham trong nas_api_server.py (xem conftest.py), nen neu
ai do noi long lai viec validate path/TTL thi test se do.
"""
import datetime
import os
import sqlite3


# --------------------------------------------------------------------------
# C3: _sanitize_dest_folder - vo hieu hoa traversal trong ten thu muc USB import
# --------------------------------------------------------------------------
class TestSanitizeDestFolder:
    def test_dot_only_names_fall_back_to_default(self, helpers):
        san = helpers["_sanitize_dest_folder"]
        for evil in ["..", ".", "...", "....", "    ", ""]:
            assert san(evil) == "USB Import", "Ten %r phai bi ep ve mac dinh" % evil

    def test_slashes_are_collapsed_so_no_traversal_component(self, helpers):
        san = helpers["_sanitize_dest_folder"]
        # Slash bi thay '_' -> ket qua la 1 thanh phan duy nhat, khong the di chuyen thu muc
        assert "/" not in san("../../etc")
        assert "\\" not in san("..\\..\\windows")
        assert san("../../etc") == ".._.._etc"

    def test_normal_names_preserved(self, helpers):
        san = helpers["_sanitize_dest_folder"]
        assert san("Movies") == "Movies"
        assert san("  Backup 2026  ") == "Backup 2026"
        assert san("Phim_Bo.HD") == "Phim_Bo.HD"


# --------------------------------------------------------------------------
# C2: _validate_file_path - bat buoc nam duoi WEBDAV_FILE_ROOT
# --------------------------------------------------------------------------
class TestValidateFilePath:
    def test_paths_inside_root_are_valid(self, helpers, tmp_path):
        helpers["WEBDAV_FILE_ROOT"] = str(tmp_path)
        valid = helpers["_validate_file_path"]
        assert valid(str(tmp_path)) is True
        assert valid(os.path.join(str(tmp_path), "Downloads", "social")) is True
        assert valid(os.path.join(str(tmp_path), "a", "b", "c")) is True

    def test_traversal_outside_root_is_rejected(self, helpers, tmp_path):
        root = tmp_path / "webdav"
        root.mkdir()
        helpers["WEBDAV_FILE_ROOT"] = str(root)
        valid = helpers["_validate_file_path"]
        assert valid(os.path.join(str(root), "..", "etc")) is False
        assert valid(os.path.join(str(root), "..", "..", "etc", "x")) is False
        assert valid(str(tmp_path)) is False  # thu muc cha cua root

    def test_sibling_prefix_is_not_confused_with_root(self, helpers, tmp_path):
        # Loi kinh dien: startswith(base) khong co separator -> '/root' khop '/root-evil'
        root = tmp_path / "data"
        root.mkdir()
        sibling = tmp_path / "data-evil"
        sibling.mkdir()
        helpers["WEBDAV_FILE_ROOT"] = str(root)
        valid = helpers["_validate_file_path"]
        assert valid(str(sibling)) is False

    def test_empty_path_is_rejected(self, helpers, tmp_path):
        helpers["WEBDAV_FILE_ROOT"] = str(tmp_path)
        valid = helpers["_validate_file_path"]
        assert valid("") is False
        assert valid(None) is False


# --------------------------------------------------------------------------
# Archive extraction safety (zip/tar) - dung chung helper voi USB import
# --------------------------------------------------------------------------
class TestArchiveMemberSafety:
    def test_unsafe_members_rejected(self, helpers):
        is_safe = helpers["_archive_member_is_safe"]
        assert is_safe("../escape.txt") is False
        assert is_safe("/abs/path.txt") is False
        assert is_safe("a/../../b") is False
        assert is_safe("") is False

    def test_safe_members_accepted(self, helpers):
        is_safe = helpers["_archive_member_is_safe"]
        assert is_safe("folder/file.txt") is True
        assert is_safe("./folder/./file.txt") is True

    def test_member_within_dest(self, helpers, tmp_path):
        within = helpers["_archive_member_within_dest"]
        dest = str(tmp_path / "out")
        os.makedirs(dest, exist_ok=True)
        assert within(dest, "good/file.txt") is True
        assert within(dest, "../../../etc/passwd") is False


# --------------------------------------------------------------------------
# H1: TTL cho authorized_ips - IP het han bi prune khi refresh cache
# --------------------------------------------------------------------------
class TestAuthorizedIpTtl:
    def _make_db(self, path):
        conn = sqlite3.connect(path)
        cur = conn.cursor()
        cur.execute("CREATE TABLE authorized_ips (ip TEXT PRIMARY KEY, added_at DATETIME)")
        now = datetime.datetime.now()
        cur.execute("INSERT INTO authorized_ips VALUES (?, ?)", ("10.0.0.1", now - datetime.timedelta(days=1)))   # con han
        cur.execute("INSERT INTO authorized_ips VALUES (?, ?)", ("10.0.0.2", now - datetime.timedelta(days=10)))  # het han
        cur.execute("INSERT INTO authorized_ips VALUES (?, ?)", ("10.0.0.3", None))                                # khong ro tuoi
        conn.commit()
        conn.close()

    def test_expired_ip_pruned_fresh_kept(self, helpers, tmp_path):
        db = str(tmp_path / "nas.db")
        self._make_db(db)
        helpers["DB_PATH"] = db
        helpers["_AUTHORIZED_IP_TTL_DAYS"] = 7.0
        helpers["_AUTHORIZED_IPS_CACHE"] = {"ts": 0.0, "ips": set()}

        ips = helpers["_refresh_authorized_ips_cache"](force=True)
        assert "10.0.0.1" in ips        # con han -> giu
        assert "10.0.0.2" not in ips    # het han -> bi loai
        assert "10.0.0.3" in ips        # added_at NULL -> khong prune

        # Da xoa han khoi DB
        conn = sqlite3.connect(db)
        rows = {r[0] for r in conn.execute("SELECT ip FROM authorized_ips")}
        conn.close()
        assert "10.0.0.2" not in rows

    def test_ttl_disabled_keeps_everything(self, helpers, tmp_path):
        db = str(tmp_path / "nas.db")
        self._make_db(db)
        helpers["DB_PATH"] = db
        helpers["_AUTHORIZED_IP_TTL_DAYS"] = 0  # tat TTL
        helpers["_AUTHORIZED_IPS_CACHE"] = {"ts": 0.0, "ips": set()}

        ips = helpers["_refresh_authorized_ips_cache"](force=True)
        assert {"10.0.0.1", "10.0.0.2", "10.0.0.3"} <= ips


# --------------------------------------------------------------------------
# Fan custom hysteresis: moc duoi chi de tat, moc tren moi duoc bat
# --------------------------------------------------------------------------
class TestFanCustomHysteresis:
    def test_off_fan_stays_off_between_thresholds(self, helpers):
        target = helpers["_fan_target_percent"]
        assert target(control_temp=39.0, on_temp=42.0, off_temp=38.0, last_applied_percent=0) == 0
        assert target(control_temp=41.9, on_temp=42.0, off_temp=38.0, last_applied_percent=0) == 0

    def test_off_fan_starts_only_at_upper_threshold(self, helpers):
        target = helpers["_fan_target_percent"]
        assert target(control_temp=42.0, on_temp=42.0, off_temp=38.0, last_applied_percent=0) == 100
        assert target(control_temp=45.0, on_temp=42.0, off_temp=38.0, last_applied_percent=0) == 100

    def test_running_fan_keeps_running_until_lower_threshold(self, helpers):
        target = helpers["_fan_target_percent"]
        assert target(control_temp=41.0, on_temp=42.0, off_temp=38.0, last_applied_percent=100) == 100
        assert target(control_temp=38.1, on_temp=42.0, off_temp=38.0, last_applied_percent=100) == 100
        assert target(control_temp=38.0, on_temp=42.0, off_temp=38.0, last_applied_percent=100) == 0

    def test_force_hot_still_overrides_thresholds(self, helpers):
        target = helpers["_fan_target_percent"]
        assert target(control_temp=30.0, on_temp=42.0, off_temp=38.0, last_applied_percent=0, force_hot=True) == 100
