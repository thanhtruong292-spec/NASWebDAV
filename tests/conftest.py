"""Test harness cho nas_api_server.py.

nas_api_server.py la mot file monolith deploy nguyen ven thanh /opt/nas_api_server.py
(systemd). No import flask/tornado/psutil va chay nhieu side-effect (tao DB, start
thread, doc config Linux) ngay khi import -> khong the `import` trong unit test tren
may dev Windows.

De van test duoc CHINH XAC source that (khong copy/duplicate logic), conftest nay nap
rieng tung ham thuan (pure) qua AST: parse file, lay cac FunctionDef can test roi exec
vao mot namespace co san cac global toi thieu. Nho vay:
  - khong can import ca monolith,
  - khong dung den flask/tornado/linux,
  - test chay tren chinh dong code production (sua o nas_api_server.py la test bat duoc).
"""
import ast
import datetime
import logging
import os
import pathlib
import re
import sqlite3
import time

import pytest

SERVER_PATH = pathlib.Path(__file__).resolve().parent.parent / "nas_api_server.py"

# Cac ham thuan can test (lien quan boundary bao mat: path traversal, TTL auth...).
_WANTED = {
    "_validate_file_path",
    "_sanitize_dest_folder",
    "_archive_member_is_safe",
    "_archive_member_target_path",
    "_archive_member_within_dest",
    "_refresh_authorized_ips_cache",
}


def _load_functions():
    src = SERVER_PATH.read_text(encoding="utf-8")
    tree = ast.parse(src)
    ns = {
        "os": os,
        "_re_module": re,
        "re": re,
        "time": time,
        "datetime": datetime,
        "sqlite3": sqlite3,
        "log": logging.getLogger("nas_test"),
        # State global ma _refresh_authorized_ips_cache dung den
        "_AUTHORIZED_IPS_CACHE": {"ts": 0.0, "ips": set()},
        "_AUTHORIZED_IPS_CACHE_TTL": 30.0,
        "_AUTHORIZED_IP_TTL_DAYS": 7.0,
    }
    found = set()
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in _WANTED:
            module = ast.Module(body=[node], type_ignores=[])
            ast.fix_missing_locations(module)
            exec(compile(module, str(SERVER_PATH), "exec"), ns)  # noqa: S102 (test loader)
            found.add(node.name)
    missing = _WANTED - found
    if missing:
        raise RuntimeError("Khong tim thay ham can test trong nas_api_server.py: %s" % sorted(missing))
    return ns


@pytest.fixture
def helpers():
    """Namespace chua cac ham that lay tu nas_api_server.py.

    Test co the set them global (vd helpers['WEBDAV_FILE_ROOT'], helpers['DB_PATH'])
    truoc khi goi ham, vi cac ham tra cuu global qua chinh namespace nay.
    """
    return _load_functions()
