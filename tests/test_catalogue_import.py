import sqlite3

import pytest

from backend import nas_work_coordinator as work


def test_import_preserves_wal_rows_system_history_and_restart(tmp_path):
    source = str(tmp_path / "old.db")
    target = str(tmp_path / "system.db")
    writer = sqlite3.connect(source)
    try:
        writer.execute("PRAGMA journal_mode=WAL")
        writer.executescript("CREATE TABLE file_index(path TEXT PRIMARY KEY);"
                             "CREATE INDEX file_order ON file_index(path DESC);"
                             "CREATE TABLE index_state(catalogue_revision INTEGER);"
                             "INSERT INTO index_state VALUES (42);"
                             "INSERT INTO file_index VALUES ('image.jpg');")
        work.initialize(source)
        with sqlite3.connect(target) as db:
            db.execute("CREATE TABLE system_logs(message TEXT)")
            db.execute("INSERT INTO system_logs VALUES ('keep')")
        work.import_legacy_catalogue(target, source)
        with sqlite3.connect(target) as db:
            assert db.execute("SELECT message FROM system_logs").fetchone() == ("keep",)
            assert db.execute("SELECT catalogue_revision FROM index_state").fetchone() == (42,)
            assert db.execute("SELECT path FROM file_index INDEXED BY file_order").fetchone() == ("image.jpg",)
            db.execute("DELETE FROM file_index")
        work.import_legacy_catalogue(target, source)
        with sqlite3.connect(target) as db:
            assert db.execute("SELECT count(*) FROM file_index").fetchone() == (0,)
        assert writer.execute("SELECT count(*) FROM file_index").fetchone() == (1,)
    finally:
        writer.close()


def test_failed_import_rolls_back_and_can_retry(tmp_path):
    source = str(tmp_path / "old.db")
    target = str(tmp_path / "system.db")
    with sqlite3.connect(source) as db:
        db.executescript("CREATE TABLE file_index(path TEXT); CREATE TABLE unexpected(id INTEGER);")
    with pytest.raises(ValueError, match="unexpected"):
        work.import_legacy_catalogue(target, source)
    with sqlite3.connect(target) as db:
        assert db.execute("SELECT name FROM sqlite_master").fetchall() == []
    with sqlite3.connect(source) as db:
        db.execute("DROP TABLE unexpected")
    work.import_legacy_catalogue(target, source)
    with sqlite3.connect(target) as db:
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
