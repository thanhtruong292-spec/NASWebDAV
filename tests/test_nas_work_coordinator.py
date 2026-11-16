import os
from contextlib import closing
import sqlite3
import tempfile
import unittest

from backend import nas_work_coordinator as work


class NasWorkCoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.db_path = os.path.join(self.temp.name, "nas.sqlite")
        conn = sqlite3.connect(self.db_path)
        try:
            conn.execute("CREATE TABLE file_index(path TEXT PRIMARY KEY)")
            conn.execute("INSERT INTO file_index VALUES('existing.txt')")
            conn.execute("CREATE TABLE index_state(id INTEGER PRIMARY KEY,root TEXT,generation INTEGER,ready INTEGER)")
            conn.execute("INSERT INTO index_state VALUES(1,'/webdav',7,1)")
            conn.commit()
        finally:
            conn.close()
        work.initialize(self.db_path)

    def test_revision_change_after_final_page_requeues_work(self):
        work.request_job(self.db_path, "duplicates", "/webdav", now=1)
        lease = work.claim_next(self.db_path, "pc", True, now=2)
        with closing(sqlite3.connect(self.db_path)) as conn, conn:
            work.bump_catalogue_revision(conn)
        self.assertFalse(work.checkpoint(self.db_path, "duplicates", "pc", lease["token"],
                                         "old", 5, 5, state="complete", now=3))
        retry = work.claim_next(self.db_path, "pc", True, now=4)
        self.assertEqual((retry["cursor"], retry["completed"], retry["catalogue_revision"]), ("", 0, 1))

    def test_uses_catalogue_database_and_grants_only_one_global_lease(self):
        work.request_job(self.db_path, "index", "/webdav", priority=10, now=1)
        work.request_job(self.db_path, "thumbnail", "/webdav", priority=20, now=2)
        self.assertIsNone(work.claim_next(self.db_path, "phone", False, now=3))

        claimed = work.claim_next(self.db_path, "desktop", True, now=4)
        self.assertEqual("thumbnail", claimed["name"])
        self.assertEqual("", claimed["cursor"])
        self.assertIsNone(work.claim_next(self.db_path, "nas", True, now=5))

        conn = sqlite3.connect(self.db_path)
        try:
            tables = set(row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'"))
        finally:
            conn.close()
        self.assertTrue({"file_index", "nas_work_jobs", "nas_work_lease"}.issubset(tables))

    def test_adds_catalogue_revision_to_existing_index_state(self):
        conn = sqlite3.connect(self.db_path)
        try:
            self.assertEqual(0, work.catalogue_revision(conn))
            self.assertEqual(1, work.bump_catalogue_revision(conn))
            conn.commit()
            self.assertEqual(1, work.catalogue_revision(conn))
        finally:
            conn.close()

    def test_catalogue_change_requeues_completed_file_jobs_but_not_ondemand_work(self):
        conn = sqlite3.connect(self.db_path)
        try:
            now = 1
            for name in ("thumbnail", "duplicates", "thumbnail_on_demand"):
                conn.execute("INSERT INTO nas_work_jobs(name,root,state,completed,total,requested_at,updated_at) VALUES(?,?,'complete',1,1,?,?)",
                             (name, "/webdav", now, now))
            self.assertEqual(1, work.bump_catalogue_revision(conn))
            states = dict(conn.execute("SELECT name,state FROM nas_work_jobs"))
            self.assertEqual("queued", states["thumbnail"])
            self.assertEqual("queued", states["duplicates"])
            self.assertEqual("complete", states["thumbnail_on_demand"])
        finally:
            conn.close()

    def test_checkpoints_resume_after_expired_lease_and_reject_stale_owner(self):
        work.request_job(self.db_path, "thumbnail", "/webdav", now=1)
        first = work.claim_next(self.db_path, "desktop", True, lease_seconds=30, now=2)
        self.assertTrue(work.checkpoint(self.db_path, first["name"], first["owner"], first["token"],
                                        "folder/a.jpg", 12, 40, now=3, lease_seconds=30))
        self.assertFalse(work.checkpoint(self.db_path, first["name"], first["owner"], first["token"],
                                         "folder/b.jpg", 13, 40, now=34))

        resumed = work.claim_next(self.db_path, "nas", True, lease_seconds=30, now=34)
        self.assertEqual("folder/a.jpg", resumed["cursor"])
        self.assertEqual(12, resumed["completed"])
        self.assertEqual("nas", work.get_status(self.db_path, "thumbnail", now=34)[0]["owner"])
        self.assertFalse(work.checkpoint(self.db_path, resumed["name"], "desktop", resumed["token"],
                                         "bad", 13, 40, now=35))

    def test_completed_job_releases_lease_and_next_job_runs(self):
        work.request_job(self.db_path, "index", "/webdav", now=1)
        work.request_job(self.db_path, "fingerprint", "/webdav", now=2)
        first = work.claim_next(self.db_path, "nas", True, now=3)
        self.assertEqual("index", first["name"])
        self.assertTrue(work.checkpoint(self.db_path, "index", "nas", first["token"], "", 8, 8,
                                        state="complete", now=4))
        second = work.claim_next(self.db_path, "desktop", True, now=5)
        self.assertEqual("fingerprint", second["name"])

    def test_worker_can_claim_only_its_supported_job_without_blocking_other_work(self):
        work.request_job(self.db_path, "thumbnail", "/webdav", priority=20, now=1)
        work.request_job(self.db_path, "index", "/webdav", priority=10, now=2)
        self.assertIsNone(work.claim_next(self.db_path, "nas-index", True, now=3, job_name="index"))
        claimed = work.claim_next(self.db_path, "nas-thumb", True, now=4, job_name="thumbnail")
        self.assertEqual("thumbnail", claimed["name"])
        self.assertTrue(work.owns_lease(self.db_path, "thumbnail", "nas-thumb", claimed["token"], now=4))
        self.assertFalse(work.owns_lease(self.db_path, "thumbnail", "other", claimed["token"], now=4))
        self.assertTrue(work.checkpoint(self.db_path, "thumbnail", "nas-thumb", claimed["token"],
                                        "", 1, 1, state="complete", now=5))
        self.assertEqual("index", work.claim_next(self.db_path, "nas-index", True, now=6, job_name="index")["name"])

    def test_root_change_restarts_checkpoint_but_not_running_job(self):
        work.request_job(self.db_path, "index", "/old", now=1)
        first = work.claim_next(self.db_path, "nas", True, now=2)
        self.assertRaises(ValueError, work.request_job, self.db_path, "index", "/new", now=3)
        self.assertTrue(work.checkpoint(self.db_path, "index", "nas", first["token"], "old/path", 5, 10,
                                        state="paused", now=4))
        changed = work.request_job(self.db_path, "index", "/new", now=5)[0]
        self.assertEqual("/new", changed["root"])
        self.assertEqual("queued", changed["state"])
        self.assertEqual("", changed["cursor"])
        self.assertEqual(0, changed["completed"])

    def test_rejects_invalid_job_and_progress(self):
        self.assertRaises(ValueError, work.request_job, self.db_path, "unknown", "/webdav")
        work.request_job(self.db_path, "index", "/webdav", now=1)
        lease = work.claim_next(self.db_path, "nas", True, now=2)
        self.assertRaises(ValueError, work.checkpoint, self.db_path, "index", "nas", lease["token"],
                          "", 11, 10, now=3)
        self.assertRaises(ValueError, work.checkpoint, self.db_path, "index", "nas", lease["token"],
                          "", 1, 2, state="complete", now=3)


if __name__ == "__main__":
    unittest.main()
