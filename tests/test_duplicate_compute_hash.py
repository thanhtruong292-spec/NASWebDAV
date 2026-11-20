# -*- coding: utf-8 -*-
"""Tests for server-side SHA-256 verification (/api/duplicates/compute_hash)."""

import hashlib
import os
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


def test_duplicate_compute_hash_computes_and_caches(tmp_path, monkeypatch):
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

    monkeypatch.setattr(srv, "_duplicate_file_info", lambda rel: dict(fake_info))
    monkeypatch.setattr(srv, "_duplicate_lookup", lambda info: saved_store.get(info["file_id"], ""))
    monkeypatch.setattr(srv, "_duplicate_open_lease", lambda info: os.open(info["physical"], os.O_RDONLY))
    monkeypatch.setattr(srv, "_duplicate_stat_version", lambda _st: fake_info["version"])

    def _fake_save(path, version, sha256):
        assert path == fake_info["path"]
        assert version == fake_info["version"]
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
