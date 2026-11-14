"""Run isolated production-index checks on the NAS's Python and filesystem."""
import ast
import json
import logging
import mimetypes
import os
import sqlite3
import sys
import tempfile
import threading
import time
import urllib.parse
import unicodedata

from flask import Flask, jsonify, request


def run(source, storage):
    with open(source, "r", encoding="utf-8") as handle:
        code = handle.read()
    compile(code, source, "exec")
    names = {
        "_remove_diacritics_py", "_init_index_db", "_scan_file_index",
        "_scan_file_index_pass", "_refresh_file_index_for_search",
        "_background_file_index_refresh", "_index_storage_boundary",
        "_index_resolve_directory", "_index_directory_stamp",
        "_index_readiness", "api_fast_search",
    }
    nodes = [node for node in ast.parse(code).body
             if isinstance(node, ast.FunctionDef) and node.name in names]
    app = Flask(__name__)
    with tempfile.TemporaryDirectory(prefix="NAS-Index-Smoke-", dir=storage) as work:
        root = os.path.join(work, "public")
        media = os.path.join(work, "Media")
        nested = os.path.join(media, "nested")
        os.makedirs(root)
        os.makedirs(nested)
        os.symlink(media, os.path.join(root, "Downloads"))
        filename = "Anh moi+%_.txt"
        with open(os.path.join(nested, filename), "w") as handle:
            handle.write("sample")
        ns = dict(sqlite3=sqlite3, os=os, time=time, mimetypes=mimetypes,
                  threading=threading, unicodedata=unicodedata, urllib=urllib,
                  _index_lock=threading.Lock(), _index_scan_lock=threading.Lock(),
                  INDEX_DB_PATH=os.path.join(work, "index.sqlite"),
                  _cfg_sqlite_timeout=lambda name, default: default,
                  log=logging.getLogger("index-smoke"), app=app,
                  request=request, jsonify=jsonify,
                  _get_webdav_port=lambda: 8822,
                  _get_webdav_host_default=lambda: "192.168.100.254",
                  _get_webdav_public_dir=lambda: root)
        module = ast.Module(body=nodes)
        if hasattr(module, "type_ignores"):
            module.type_ignores = []
        exec(compile(module, source, "exec"), ns)
        ns["_init_index_db"]()
        first = ns["_scan_file_index"](root)
        assert first["index_ready"], first
        resolver = ns["_index_resolve_directory"]
        def unexpected_resolution(path, public_root, boundary):
            assert os.path.islink(path), "Unchanged normal directory repeated expensive boundary resolution"
            return resolver(path, public_root, boundary)
        ns["_index_resolve_directory"] = unexpected_resolution
        try:
            second = ns["_scan_file_index"](root)
        finally:
            ns["_index_resolve_directory"] = resolver
        assert second["enumerated_dirs"] == 0, second
        client = app.test_client()

        def search(query):
            response = client.get("/api/search", query_string={"q": query, "root": "Downloads"})
            assert response.status_code == 200, response.data
            return json.loads(response.data.decode("utf-8"))

        found = search("moi+%_")
        assert len(found["results"]) == 1, found
        assert "/Downloads/nested/" in found["results"][0]["path"], found
        growing = os.path.join(nested, filename)
        parent_stamp = ns["_index_directory_stamp"](nested)
        old_file_stat = os.stat(growing)
        with open(growing, "ab") as handle:
            handle.write(b"-grown-in-place")
        os.utime(growing, (old_file_stat.st_atime, old_file_stat.st_mtime + 2))
        assert ns["_index_directory_stamp"](nested) == parent_stamp
        assert ns["_scan_file_index"](root)["enumerated_dirs"] == 0
        refreshed = search("moi+%_")["results"][0]
        assert refreshed["contentLength"] == os.path.getsize(growing), "In-place file growth returned stale size"
        assert refreshed["lastModified"] == int(os.stat(growing).st_mtime * 1000), "In-place file growth returned stale mtime"
        with sqlite3.connect(ns["INDEX_DB_PATH"]) as conn:
            stored = conn.execute("SELECT size,mtime FROM file_index WHERE path=?", ("Downloads/nested/" + filename,)).fetchone()
        conn.close()
        assert stored == (refreshed["contentLength"], refreshed["lastModified"]), "Fresh metadata was not persisted"
        added = os.path.join(nested, "added.txt")
        with open(added, "w") as handle:
            handle.write("new")
        assert ns["_scan_file_index"](root)["enumerated_dirs"] == 1
        assert len(search("added")["results"]) == 1
        os.unlink(added)
        ns["_scan_file_index"](root)
        assert search("added")["results"] == []
        ns["_init_index_db"]()
        assert ns["_scan_file_index"](root)["enumerated_dirs"] == 0

        # An external symlink ancestor can change the resolved scope while
        # the public alias still reaches exactly the same target inode.
        allowed = os.path.join(work, "Allowed")
        same_media = os.path.join(allowed, "Media")
        os.makedirs(same_media)
        with open(os.path.join(same_media, "snapshot.txt"), "w") as handle:
            handle.write("public snapshot")
        current = os.path.join(work, "current")
        os.symlink(allowed, current)
        external_alias = os.path.join(root, "External")
        os.symlink(os.path.join(current, "Media"), external_alias)
        assert ns["_scan_file_index"](root)["index_ready"]
        old_target_stamp = ns["_index_directory_stamp"](external_alias)
        old_root_stamp = ns["_index_directory_stamp"](root)
        hidden = os.path.join(work, ".private")
        os.rename(allowed, hidden)
        os.unlink(current)
        os.symlink(hidden, current)
        assert ns["_index_directory_stamp"](external_alias) == old_target_stamp
        assert ns["_index_directory_stamp"](root) == old_root_stamp
        retargeted = ns["_scan_file_index"](root)
        assert not retargeted["index_ready"], "Same-inode alias bypassed hidden-target validation"
        os.unlink(external_alias)
        os.unlink(current)
        assert ns["_scan_file_index"](root)["index_ready"]

        os.symlink(root, os.path.join(nested, "cycle"))
        cycle = ns["_scan_file_index"](root)
        assert not cycle["index_ready"], cycle
        assert len(search("moi+%_")["results"]) == 1
        os.unlink(os.path.join(nested, "cycle"))
        ns["_scan_file_index"](root)
        with tempfile.TemporaryDirectory(prefix="nas-index-private-") as private:
            with open(os.path.join(private, "private-secret.txt"), "w") as handle:
                handle.write("private")
            os.symlink(private, os.path.join(root, "outside"))
            assert not ns["_scan_file_index"](root)["index_ready"]
            assert search("private-secret")["results"] == []
            os.unlink(os.path.join(root, "outside"))
            assert ns["_scan_file_index"](root)["index_ready"]
            alias = os.path.join(root, "Downloads")
            old_identity = ns["_index_directory_stamp"](alias)
            os.unlink(alias)
            os.symlink(private, alias)
            assert ns["_index_directory_stamp"](alias) != old_identity
            # Keep the known alias pending while its parent is already current,
            # so this specifically exercises identity-first revalidation.
            conn = sqlite3.connect(ns["INDEX_DB_PATH"])
            conn.execute("UPDATE index_directories SET stamp=? WHERE path=''",
                         (ns["_index_directory_stamp"](root),))
            conn.commit()
            conn.close()
            original_listdir = os.listdir
            def no_private_enumeration(path):
                if os.path.realpath(path) == private or os.path.realpath(path).startswith(private + os.sep):
                    raise AssertionError("Retargeted private alias was enumerated")
                return original_listdir(path)
            os.listdir = no_private_enumeration
            try:
                assert not ns["_scan_file_index"](root)["index_ready"]
            finally:
                os.listdir = original_listdir
            assert search("private-secret")["results"] == []
        print(json.dumps({"python": sys.version.split()[0], "first": first,
                          "unchanged": second, "alias_add_delete_restart_cycle_boundary": "passed",
                          "unchanged_resolution_skipped": "passed", "retargeted_private_alias_denied": "passed",
                          "same_inode_ancestor_retarget_denied": "passed",
                          "in_place_growth_metadata_refreshed": "passed", "fresh_metadata_persisted": "passed"}))


if __name__ == "__main__":
    run(sys.argv[1], sys.argv[2])
