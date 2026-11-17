#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Chuyen doi 2 database (nas_index.db + nas_fast_index.db) thanh mot duy nhat.

Chay duoc tren Python 3.5.3 cua NAS (Chainedbox L1 Pro, Debian 9 Stretch).
Khong can khoi dong lai server. Su dung de migrate thu cong truoc khi deploy.

Cac bang duoc copy tu /var/lib/nas_fast_index.db (legacy) vao
/var/lib/nas_api/nas_index.db (canonical), sau do doi ten legacy thanh
nas_fast_index.db.migrated de khong import lai khi server khoi dong.

Usage:
    python scripts/migrate_to_single_db.py [--db /var/lib/nas_api/nas_index.db]
                                           [--legacy /var/lib/nas_fast_index.db]

Python 3.5.3 compliance: khong f-string, khong type annotation, khong walrus,
khong import secrets/dataclasses, luu decode str truoc khi json.loads (neu co),
luu str path vao open/os.*/sqlite3.
"""
import os
import sqlite3
import sys


DEFAULT_DB = "/var/lib/nas_api/nas_index.db"
DEFAULT_LEGACY = "/var/lib/nas_fast_index.db"

# Cac bang trong legacy catalogue duoc phep copy vao DB chinh.
LEGACY_TABLES = {
    "file_index",
    "index_directories",
    "index_state",
    "nas_work_jobs",
    "nas_work_lease",
    "verified_file_hashes",
    "file_hashes",
    "file_hash_migrations",
    "thumb_neg_cache",
    "thumbnail_cache_migrations",
    "storage_summary_snapshot",
    "storage_summary_revision",
}

# Table co xu ly dac biet (copy du lieu thay vi chay DDL raw).
SPECIAL_TABLES = {"thumb_neg_cache"}


def _log(*args):
    print(*args, flush=True)


def _table_exists(conn, name):
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()
    return row is not None


def migrate(db_path, legacy_path):
    if not os.path.isfile(legacy_path):
        _log("Khong tim thay legacy DB: %s (bo qua migrate)" % legacy_path)
        return 0

    if not os.path.isfile(db_path):
        _log("DB chinh chua ton tai, se khoi tao rong: %s" % db_path)
        # Neu DB chua co, ta can co index_state cu the de conn.execute khong loi.
        # Tuy nhien import_legacy_catalogue trong nas_work_coordinator y cau
        # DB chinh da du initialize. Nen neu chua co, ta thong bao loi.
        sys.stderr.write("Loi: DB chinh %s chua ton tai. Chay server it nhat mot lan de khoi tao DB truoc.\n" % db_path)
        return 2

    conn = sqlite3.connect(db_path, timeout=30, isolation_level=None)
    try:
        conn.execute("BEGIN IMMEDIATE")

        # Bat dau chuyen doi trong mot transaction duy nhat.
        conn.execute("CREATE TABLE IF NOT EXISTS catalogue_imports (source TEXT PRIMARY KEY)")
        already = conn.execute("SELECT 1 FROM catalogue_imports WHERE source='nas_fast_index'").fetchone()
        if already:
            conn.commit()
            _log("Da migrate legacy DB truoc do (catalogue_imports co nas_fast_index).")
            # Van doi ten legacy neu no con ton tai.
            if os.path.isfile(legacy_path):
                os.rename(legacy_path, legacy_path + ".migrated")
                _log("Da doi ten legacy DB thanh: %s.migrated" % legacy_path)
            return 0

        # Kiem tra legacy co bang file_index khong (ATTACH legacy DB vao main).
        conn.execute("ATTACH DATABASE ? AS old", (legacy_path,))
        try:
            schema = conn.execute(
                "SELECT type,name,tbl_name,sql FROM old.sqlite_master "
                "WHERE sql IS NOT NULL AND name NOT GLOB 'sqlite_*' "
                "ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END").fetchall()
            if not any(name == "file_index" for _, name, _, _ in schema):
                raise ValueError("Legacy catalogue lacks file_index: %s" % legacy_path)

            tables = {}
            for kind, name, table, ddl in schema:
                if kind == "trigger" and table == "file_index" and name.startswith("storage_summary_"):
                    continue
                if table not in LEGACY_TABLES or kind not in ("table", "index"):
                    raise ValueError("Unknown legacy catalogue object: " + name)
                if kind == "table":
                    tables[name] = ddl

            for name, ddl in tables.items():
                if name in SPECIAL_TABLES:
                    conn.execute("CREATE TABLE IF NOT EXISTS %s (path TEXT PRIMARY KEY, ts REAL)" % name)
                    conn.execute("INSERT OR REPLACE INTO main.%s(path,ts) SELECT path,ts FROM old.%s" % (name, name))
                    continue
                conn.execute(ddl)
                conn.execute("INSERT INTO main.%s SELECT * FROM old.%s" % (name, name))

            conn.execute("INSERT INTO catalogue_imports VALUES ('nas_fast_index')")
        finally:
            conn.execute("DETACH DATABASE old")
        conn.commit()

        _log("Da copy toan bo bang tu legacy DB vao %s" % db_path)

        # Doi ten legacy DB thanh .db.migrated de khong import lai.
        if os.path.isfile(legacy_path):
            os.rename(legacy_path, legacy_path + ".migrated")
            _log("Da doi ten legacy DB thanh: %s.migrated" % legacy_path)

        return 0
    except Exception as exc:
        try:
            conn.rollback()
        except Exception:
            pass
        sys.stderr.write("Loi migrate DB: %s\n" % exc)
        return 1
    finally:
        conn.close()


def main(argv):
    db_path = DEFAULT_DB
    legacy_path = DEFAULT_LEGACY
    i = 0
    while i < len(argv):
        if argv[i] == "--db" and i + 1 < len(argv):
            db_path = argv[i + 1]
            i += 2
        elif argv[i] == "--legacy" and i + 1 < len(argv):
            legacy_path = argv[i + 1]
            i += 2
        else:
            sys.stderr.write("Tham vo khong biet: %s\n" % argv[i])
            return 2
    return migrate(db_path, legacy_path)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
