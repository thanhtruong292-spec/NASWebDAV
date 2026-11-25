# -*- coding: utf-8 -*-
"""Tests for server-side SHA-256 verification (/api/duplicates/compute_hash)."""

import hashlib
import os
import sqlite3
import sys
import types
import pytest

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BACKEND_DIR = os.path.join(ROOT_DIR, "backend")
for p in (ROOT_DIR, BACKEND_DIR):
    if p not in sys.path:
        sys.path.insert(0, p)

import nas_api_server as srv


def _install_fake_fcntl(monkeypatch):
    """Allow testing Linux F_SETLEASE/F_GETLEASE flow on Windows dev hosts."""
    if "fcntl" in sys.modules:
        return
    fake = types.ModuleType("fcntl")
    fake.F_GETLEASE = 1025
    fake.F_RDLCK = 0
    fake.fcntl = lambda fd, cmd, *args: fake.F_RDLCK
    monkeypatch.setitem(sys.modules, "fcntl", fake)


@pytest.mark.parametrize("busy_on_save", [False, True])
def test_duplicate_compute_hash_computes_and_caches(tmp_path, monkeypatch, busy_on_save):
    _install_fake_fcntl(monkeypatch)
    sample = tmp_path / "photo.jpg"
    payload = b"nas-server-side-sha256-payload-2026"
    sample.write_bytes(payload)
    expected_sha = hashlib.sha256(payload).hexdigest()

    st = os.stat(str(sample))
    fake_info = {
        "path": "AutoBackup/photo.jpg",
        "physical": str(sample),
        "size": len(payload),
        "mtime_ns": getattr(st, "st_mtime_ns", int(st.st_mtime * 1e9)),
        "file_id": "%s:%s" % (st.st_dev, st.st_ino),
        "version": "a" * 64,
    }
    saved_store = {}
    opened = []
    attempts = []
    database = str(tmp_path / "proof.db")
    writer = sqlite3.connect(database)
    writer.execute("CREATE TABLE proof (digest TEXT)")
    if busy_on_save:
        writer.execute("BEGIN IMMEDIATE")
    monkeypatch.setattr(srv.time, "sleep", lambda _: writer.rollback())

    def open_lease(info):
        opened.append(info["physical"])
        return os.open(info["physical"], os.O_RDONLY)


    monkeypatch.setattr(srv, "_duplicate_file_info", lambda rel: dict(fake_info))
    monkeypatch.setattr(
        srv,
        "_duplicate_lookup",
        lambda info, require_server_provenance=False: saved_store.get(info["file_id"], ""),
    )
    monkeypatch.setattr(srv, "_duplicate_open_lease", open_lease)
    monkeypatch.setattr(srv, "rust_bridge", types.SimpleNamespace(
        compute_duplicate_sha256_fd=lambda fd: hashlib.sha256(os.read(fd, len(payload))).hexdigest()
    ))
    monkeypatch.setattr(srv, "_duplicate_stat_version", lambda _st: fake_info["version"])

    def _fake_save(path, version, sha256, provenance='client'):
        assert path == fake_info["path"]
        assert version == fake_info["version"]
        assert provenance == 'server_lease'  # fail-closed: chi duoc ghi server_lease sau xac minh lease hop le
        attempts.append(sha256)
        conn = sqlite3.connect(database, timeout=0)
        try:
            conn.execute("INSERT INTO proof VALUES (?)", (sha256,))
            conn.commit()
        finally:
            conn.close()
        saved_store[fake_info["file_id"]] = sha256
        return fake_info

    monkeypatch.setattr(srv, "_duplicate_save_hash", _fake_save)

    first = srv._duplicate_compute_hash("AutoBackup/photo.jpg", fake_info["version"])
    assert first["sha256"] == expected_sha
    assert first["cached"] is False
    assert first["version"] == fake_info["version"]

    # Second call hits verified_file_hashes cache immediately
    second = srv._duplicate_compute_hash("AutoBackup/photo.jpg", fake_info["version"])
    assert second["sha256"] == expected_sha
    assert second["cached"] is True
    assert opened == [str(sample)]
    assert attempts == [expected_sha] * (2 if busy_on_save else 1)
    writer.close()


def test_duplicate_compute_hash_rejects_version_mismatch(monkeypatch):
    monkeypatch.setattr(
        srv,
        "_duplicate_file_info",
        lambda rel: {
            "path": "AutoBackup/photo.jpg",
            "physical": "/tmp/missing",
            "size": 10,
            "file_id": "1:2",
            "version": "b" * 64,
        },
    )
    with pytest.raises(ValueError):
        srv._duplicate_compute_hash("AutoBackup/photo.jpg", "a" * 64)


@pytest.mark.parametrize("failure", ["unlocked", "lease_query_error"])
def test_duplicate_compute_hash_does_not_save_unleased_digest(tmp_path, monkeypatch, failure):
    sample = tmp_path / "fixture.bin"
    payload = b"synthetic duplicate proof"
    sample.write_bytes(payload)
    version = srv._duplicate_stat_version(os.stat(str(sample)))
    hashed_fds = []
    checked_fds = []
    saved = []
    fake = types.ModuleType("fcntl")
    fake.F_GETLEASE = 1025
    fake.F_RDLCK = 0

    def get_lease(fd, cmd):
        assert cmd == fake.F_GETLEASE
        checked_fds.append(fd)
        if failure == "lease_query_error":
            raise OSError("fixture lease query failed")
        return 2

    def hash_fd(fd):
        hashed_fds.append(fd)
        return hashlib.sha256(os.read(fd, len(payload))).hexdigest()

    fake.fcntl = get_lease
    monkeypatch.setitem(sys.modules, "fcntl", fake)
    monkeypatch.setattr(srv, "_duplicate_lookup", lambda *a, **kw: None)
    monkeypatch.setattr(srv, "_duplicate_file_info", lambda _: {"version": version})
    monkeypatch.setattr(srv, "_duplicate_open_lease", lambda _: os.open(str(sample), os.O_RDONLY))
    monkeypatch.setattr(srv, "rust_bridge", types.SimpleNamespace(compute_duplicate_sha256_fd=hash_fd))
    monkeypatch.setattr(srv, "_duplicate_save_hash", lambda *a, **kw: saved.append(a))

    with pytest.raises(OSError if failure == "lease_query_error" else ValueError):
        srv._duplicate_compute_hash("fixture.bin", version)
    assert len(hashed_fds) == 1
    assert checked_fds == hashed_fds
    assert saved == []
