"""Durable, single-lease scheduler state stored in the NAS file catalogue DB."""

import sqlite3
import time
import uuid


_JOB_TYPES = ("index", "thumbnail", "thumbnail_on_demand", "fingerprint", "duplicates", "hash")
_STATES = ("queued", "running", "paused", "retry", "failed", "complete")


def _connect(db_path, timeout):
    return sqlite3.connect(db_path, timeout=timeout)


def initialize(db_path, timeout=5.0):
    conn = _connect(db_path, timeout)
    try:
        state_table = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='index_state'").fetchone()
        if state_table:
            columns = [row[1] for row in conn.execute("PRAGMA table_info(index_state)")]
            if "catalogue_revision" not in columns:
                conn.execute("ALTER TABLE index_state ADD COLUMN catalogue_revision INTEGER NOT NULL DEFAULT 0")
        conn.execute("""CREATE TABLE IF NOT EXISTS nas_work_jobs (
            name TEXT PRIMARY KEY,
            root TEXT NOT NULL,
            state TEXT NOT NULL,
            priority INTEGER NOT NULL DEFAULT 0,
            cursor TEXT NOT NULL DEFAULT '',
            completed INTEGER NOT NULL DEFAULT 0,
            total INTEGER NOT NULL DEFAULT 0,
            revision INTEGER NOT NULL DEFAULT 0,
            attempt INTEGER NOT NULL DEFAULT 0,
            requested_at REAL NOT NULL,
            updated_at REAL NOT NULL,
            error TEXT NOT NULL DEFAULT ''
        )""")
        conn.execute("""CREATE TABLE IF NOT EXISTS nas_work_lease (
            id INTEGER PRIMARY KEY CHECK (id=1),
            name TEXT NOT NULL,
            owner TEXT NOT NULL,
            token TEXT NOT NULL,
            expires_at REAL NOT NULL,
            updated_at REAL NOT NULL
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS nas_work_jobs_order ON nas_work_jobs(state,priority DESC,requested_at,name)")
        conn.commit()
    finally:
        conn.close()


def bump_catalogue_revision(conn):
    """Bump once per committed catalogue batch, never once per file row."""
    conn.execute("UPDATE index_state SET catalogue_revision=catalogue_revision+1 WHERE id=1")
    row = conn.execute("SELECT catalogue_revision FROM index_state WHERE id=1").fetchone()
    if conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='nas_work_jobs'").fetchone():
        now = time.time()
        conn.execute("""UPDATE nas_work_jobs SET state='queued',cursor='',completed=0,total=0,
                     revision=revision+1,requested_at=?,updated_at=?,error='catalogue_changed'
                     WHERE name IN ('thumbnail','duplicates','fingerprint','hash') AND state='complete'""",
                     (now, now))
    return row[0] if row else 0


def catalogue_revision(conn):
    row = conn.execute("SELECT catalogue_revision FROM index_state WHERE id=1").fetchone()
    return row[0] if row else 0


def _validate_job(name, root):
    if name not in _JOB_TYPES:
        raise ValueError("Unsupported NAS maintenance job")
    if not root or len(root) > 1024 or "\x00" in root:
        raise ValueError("Invalid NAS root")


def request_job(db_path, name, root, priority=0, now=None, timeout=5.0):
    _validate_job(name, root)
    if type(priority) is not int or priority < -100 or priority > 100:
        raise ValueError("Invalid job priority")
    now = time.time() if now is None else float(now)
    conn = _connect(db_path, timeout)
    try:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT root,state FROM nas_work_jobs WHERE name=?", (name,)).fetchone()
        if row is None:
            conn.execute("INSERT INTO nas_work_jobs(name,root,state,priority,requested_at,updated_at) VALUES(?,?,'queued',?,?,?)",
                         (name, root, priority, now, now))
        elif row[0] != root:
            lease = conn.execute("SELECT expires_at FROM nas_work_lease WHERE id=1 AND name=?", (name,)).fetchone()
            if lease and lease[0] <= now:
                conn.execute("DELETE FROM nas_work_lease WHERE id=1 AND name=?", (name,))
                lease = None
            if lease:
                raise ValueError("Cannot change root while the job holds the maintenance lease")
            conn.execute("""UPDATE nas_work_jobs SET root=?,state='queued',priority=?,cursor='',
                         completed=0,total=0,revision=revision+1,attempt=0,requested_at=?,updated_at=?,error=''
                         WHERE name=?""", (root, priority, now, now, name))
        elif row[1] in ("complete", "failed"):
            conn.execute("""UPDATE nas_work_jobs SET state='queued',priority=?,cursor='',completed=0,
                         total=0,revision=revision+1,attempt=0,requested_at=?,updated_at=?,error=''
                         WHERE name=?""", (priority, now, now, name))
        else:
            conn.execute("UPDATE nas_work_jobs SET priority=MAX(priority,?),updated_at=? WHERE name=?",
                         (priority, now, name))
        conn.commit()
        return get_status(db_path, name, now=now, timeout=timeout)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def claim_next(db_path, owner, allowed, lease_seconds=300, now=None, timeout=5.0, job_name=None):
    if not owner or len(owner) > 128:
        raise ValueError("Invalid NAS worker identity")
    if type(lease_seconds) is not int or lease_seconds < 30 or lease_seconds > 3600:
        raise ValueError("Invalid NAS work lease duration")
    if not allowed:
        return None
    if job_name is not None and job_name not in _JOB_TYPES:
        raise ValueError("Unsupported NAS maintenance job")
    now = time.time() if now is None else float(now)
    conn = _connect(db_path, timeout)
    try:
        conn.execute("BEGIN IMMEDIATE")
        lease = conn.execute("SELECT name,expires_at FROM nas_work_lease WHERE id=1").fetchone()
        if lease and lease[1] > now:
            conn.commit()
            return None
        if lease:
            conn.execute("UPDATE nas_work_jobs SET state=CASE WHEN name='thumbnail_on_demand' THEN 'failed' ELSE 'retry' END,error='lease_expired',updated_at=? WHERE name=? AND state='running'",
                         (now, lease[0]))
            conn.execute("DELETE FROM nas_work_lease WHERE id=1")
        query = """SELECT name,root,cursor,completed,total,revision,attempt FROM nas_work_jobs
                   WHERE state IN ('queued','paused','retry')"""
        query += " ORDER BY priority DESC,requested_at,name LIMIT 1"
        row = conn.execute(query).fetchone()
        if row is not None and job_name is not None and row[0] != job_name:
            conn.commit()
            return None
        if row is None:
            conn.commit()
            return None
        token = uuid.uuid4().hex
        conn.execute("UPDATE nas_work_jobs SET state='running',attempt=attempt+1,updated_at=?,error='' WHERE name=?",
                     (now, row[0]))
        conn.execute("INSERT OR REPLACE INTO nas_work_lease(id,name,owner,token,expires_at,updated_at) VALUES(1,?,?,?,?,?)",
                     (row[0], owner, token, now + lease_seconds, now))
        revision_row = conn.execute("SELECT catalogue_revision FROM index_state WHERE id=1").fetchone()
        conn.commit()
        return {"name": row[0], "root": row[1], "cursor": row[2], "completed": row[3],
                "total": row[4], "revision": row[5], "attempt": row[6] + 1,
                "catalogue_revision": revision_row[0] if revision_row else 0,
                "owner": owner, "token": token, "expires_at": now + lease_seconds}
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def checkpoint(db_path, name, owner, token, cursor, completed, total, state="running",
               error="", now=None, lease_seconds=300, timeout=5.0):
    if name not in _JOB_TYPES or state not in _STATES:
        raise ValueError("Invalid NAS work state")
    if not owner or not token or not isinstance(cursor, str) or len(cursor) > 2048:
        raise ValueError("Invalid NAS work checkpoint")
    if type(completed) is not int or type(total) is not int or completed < 0 or total < 0 or completed > total:
        raise ValueError("Invalid NAS work progress")
    if state == "complete" and completed != total:
        raise ValueError("Incomplete NAS work cannot be marked complete")
    if type(lease_seconds) is not int or lease_seconds < 30 or lease_seconds > 3600:
        raise ValueError("Invalid NAS work lease duration")
    if len(error) > 256:
        error = error[:256]
    now = time.time() if now is None else float(now)
    conn = _connect(db_path, timeout)
    try:
        conn.execute("BEGIN IMMEDIATE")
        lease = conn.execute("SELECT expires_at FROM nas_work_lease WHERE id=1 AND name=? AND owner=? AND token=?",
                             (name, owner, token)).fetchone()
        if lease is None or lease[0] <= now:
            conn.rollback()
            return False
        conn.execute("UPDATE nas_work_jobs SET state=?,cursor=?,completed=?,total=?,updated_at=?,error=? WHERE name=?",
                     (state, cursor, completed, total, now, error, name))
        if state == "running":
            conn.execute("UPDATE nas_work_lease SET expires_at=?,updated_at=? WHERE id=1 AND name=? AND owner=? AND token=?",
                         (now + lease_seconds, now, name, owner, token))
        else:
            conn.execute("DELETE FROM nas_work_lease WHERE id=1 AND name=? AND owner=? AND token=?",
                         (name, owner, token))
        conn.commit()
        return True
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def get_status(db_path, name=None, now=None, timeout=5.0):
    now = time.time() if now is None else float(now)
    conn = _connect(db_path, timeout)
    try:
        if name is None:
            rows = conn.execute("""SELECT j.name,j.root,j.state,j.priority,j.cursor,j.completed,j.total,
                                  j.revision,j.attempt,j.requested_at,j.updated_at,j.error,
                                  CASE WHEN l.expires_at>? THEN l.owner ELSE '' END,
                                  CASE WHEN l.expires_at>? THEN l.expires_at ELSE 0 END
                                  FROM nas_work_jobs j LEFT JOIN nas_work_lease l ON l.id=1 AND l.name=j.name
                                  ORDER BY j.priority DESC,j.requested_at,j.name""", (now, now)).fetchall()
        else:
            rows = conn.execute("""SELECT j.name,j.root,j.state,j.priority,j.cursor,j.completed,j.total,
                                  j.revision,j.attempt,j.requested_at,j.updated_at,j.error,
                                  CASE WHEN l.expires_at>? THEN l.owner ELSE '' END,
                                  CASE WHEN l.expires_at>? THEN l.expires_at ELSE 0 END
                                  FROM nas_work_jobs j LEFT JOIN nas_work_lease l ON l.id=1 AND l.name=j.name
                                  WHERE j.name=?""", (now, now, name)).fetchall()
        keys = ("name", "root", "state", "priority", "cursor", "completed", "total", "revision",
                "attempt", "requested_at", "updated_at", "error", "owner", "lease_expires_at")
        return [dict(zip(keys, row)) for row in rows]
    finally:
        conn.close()


def owns_lease(db_path, name, owner, token, now=None, timeout=5.0):
    if name not in _JOB_TYPES or not owner or not token:
        return False
    now = time.time() if now is None else float(now)
    conn = _connect(db_path, timeout)
    try:
        row = conn.execute("SELECT 1 FROM nas_work_lease WHERE id=1 AND name=? AND owner=? AND token=? AND expires_at>?",
                           (name, owner, token, now)).fetchone()
        return row is not None
    finally:
        conn.close()
