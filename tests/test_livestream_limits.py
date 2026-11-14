# -*- coding: utf-8 -*-
"""Regression cho SPEC_LIVESTREAM_LIMITS (approved 2026-10-08).

Bao phu, khong import full server module (nang 700KB + khoi dong thread):
  - Loader clamp: social 1-16 / live 1-8 qua exec khoi ham doc lap.
  - 429 structured: live MAX_STREAMS + social MAX_DOWNLOADS (AST).
  - PATCH admin-only: requires_admin chan LAN-bypass/IP-cache (AST).
  - Queue server-side: cap 20 / TTL 24h / persist HDD / promote (AST).
  - Watcher ton trong cap dong (AST).

Chay: python3 -m pytest tests/test_livestream_limits.py -q
"""

import ast
import os
import unittest
from pathlib import Path


SERVER_PATH = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"
SOURCE = SERVER_PATH.read_text(encoding="utf-8")
TREE = ast.parse(SOURCE)


def _get_func(name):
    return next(
        n for n in TREE.body
        if isinstance(n, ast.FunctionDef) and n.name == name
    )


def _func_src(name):
    try:
        return ast.unparse(_get_func(name))
    except Exception:
        return ""


def _exec_loader(env=None):
    """Exec khoi loader (_cfg_limit/_cfg_int/getters/recommended) voi env gia."""
    names = ["_cfg_limit", "_cfg_int",
             "_get_social_max_concurrent", "_get_livestream_max_concurrent",
             "_get_livestream_max_hours", "_get_social_max_filesize",
             "_recommended_caps_by_ram"]
    nodes = [n for n in TREE.body
             if isinstance(n, ast.FunctionDef) and n.name in names]
    fake_os = type("O", (), {})()
    fake_os.environ = dict(env or {})
    fake_os.path = os.path
    import json as _json

    class _FakePsutil(object):
        def __init__(self, total):
            self._total = total

        def virtual_memory(self):
            class _M(object):
                pass
            m = _M()
            m.total = self._total
            return m

    ns = {"os": fake_os, "json": _json, "psutil": None,
          "_NAS_LIMITS_CONF_PATH": "/nonexistent",
          "_NAS_LIMITS_CFG": {}, "_NAS_LIMITS_RUNTIME": {}}
    for node in nodes:
        exec(compile(ast.Module([node]), "<%s>" % node.name, "exec"), ns)
    return ns


class TestLoaderClamp(unittest.TestCase):
    def test_defaults_giu_2(self):
        ns = _exec_loader({})
        self.assertEqual(ns["_get_social_max_concurrent"](), 2)
        self.assertEqual(ns["_get_livestream_max_concurrent"](), 2)
        self.assertEqual(ns["_get_livestream_max_hours"](), 12)

    def test_social_clamp_1_16(self):
        self.assertEqual(_exec_loader(
            {"NAS_SOCIAL_MAX_CONCURRENT": "99"})["_get_social_max_concurrent"](), 16)
        self.assertEqual(_exec_loader(
            {"NAS_SOCIAL_MAX_CONCURRENT": "0"})["_get_social_max_concurrent"](), 1)
        self.assertEqual(_exec_loader(
            {"NAS_SOCIAL_MAX_CONCURRENT": "abc"})["_get_social_max_concurrent"](), 2)

    def test_live_clamp_1_8(self):
        self.assertEqual(_exec_loader(
            {"NAS_LIVESTREAM_MAX_CONCURRENT": "99"})["_get_livestream_max_concurrent"](), 8)
        self.assertEqual(_exec_loader(
            {"NAS_LIVESTREAM_MAX_CONCURRENT": "0"})["_get_livestream_max_concurrent"](), 1)

    def test_recommended_theo_ram(self):
        import json as _json
        nodes = [n for n in TREE.body
                 if isinstance(n, ast.FunctionDef)
                 and n.name == "_recommended_caps_by_ram"]
        for total, exp in ((1024 * 1024 * 1024, (2, 2)),
                           (2 * 1024 * 1024 * 1024, (3, 4)),
                           (8 * 1024 * 1024 * 1024, (4, 8))):
            class _M(object):
                pass
            m = _M()
            m.total = total

            class _P(object):
                @staticmethod
                def virtual_memory():
                    return m
            ns = {"psutil": _P()}
            exec(compile(ast.Module(nodes), "<rec>", "exec"), ns)
            _mb, rl, rs = ns["_recommended_caps_by_ram"]()
            self.assertEqual((rl, rs), exp)


class Test429Structured(unittest.TestCase):
    def test_live_429_co_code(self):
        src = _func_src("api_livestream_record")
        # ast.unparse render string bang single-quote.
        for token in ("MAX_STREAMS", "'cap'", "'active'", "'retry_after_s'",
                      "'reason'", "429"):
            self.assertIn(token, src, "live 429 thieu %s" % token)

    def test_social_429_co_code(self):
        src = _func_src("api_social_download")
        for token in ("MAX_DOWNLOADS", "'cap'", "'active'", "'retry_after_s'", "429"):
            self.assertIn(token, src, "social 429 thieu %s" % token)

    def test_social_create_giu_chuoi_cu(self):
        # Tuong thich test cu: _social_create_job van tra string "Max concurrent".
        src = _func_src("_social_create_job")
        self.assertIn("Max concurrent downloads reached", src)


class TestAdminAuth(unittest.TestCase):
    def test_requires_admin_ton_tai_va_chan_bypass(self):
        src = _func_src("requires_admin")
        self.assertTrue(src, "thieu requires_admin")
        self.assertIn("check_auth", src)
        # Docstring mo ta chinh sach nen chua tu khoa; chi kiem tra CODE
        # (bo dong docstring dau) khong goi bypass.
        try:
            node = _get_func("requires_admin")
            code_only = ast.unparse(node.body[-1])
        except Exception:
            code_only = src
        self.assertNotIn("_ip_in_whitelist", code_only)
        self.assertNotIn("_refresh_authorized_ips_cache", code_only)
        self.assertNotIn("_remember_authorized_ip", code_only)
        self.assertNotIn("_async_persist_trusted_ip", code_only)

    def test_routes_admin_limits(self):
        self.assertIn('"/api/admin/limits"', SOURCE)
        get_src = _func_src("api_admin_limits_get")
        patch_src = _func_src("api_admin_limits_patch")
        self.assertTrue(get_src and patch_src)
        self.assertIn("recommended_live_max", get_src)
        self.assertIn("recommended_social_max", get_src)
        self.assertIn("over_recommended", patch_src)
        # Decorator dung: GET auth thuong, PATCH admin-only.
        for node in TREE.body:
            if isinstance(node, ast.FunctionDef) and node.name == "api_admin_limits_get":
                decs = [ast.unparse(d) for d in node.decorator_list]
                self.assertTrue(any("requires_auth" in d for d in decs))
            if isinstance(node, ast.FunctionDef) and node.name == "api_admin_limits_patch":
                decs = [ast.unparse(d) for d in node.decorator_list]
                self.assertTrue(any("requires_admin" in d for d in decs))


class TestQueue(unittest.TestCase):
    def test_cap_ttl(self):
        self.assertIn("_LIVESTREAM_QUEUE_MAX = 20", SOURCE)
        self.assertIn("_LIVESTREAM_QUEUE_TTL_S = 86400", SOURCE)

    def test_persist_hdd(self):
        src = _func_src("_livestream_queue_file")
        self.assertTrue(src)
        self.assertTrue("livestream_queue.json" in src)
        self.assertTrue("_get_hdd_config_dir" in src or ".naswebdav" in src)

    def test_routes_va_promote(self):
        for name in ("api_livestream_queue_add", "api_livestream_queue_list",
                     "api_livestream_queue_del", "_livestream_queue_try_promote",
                     "_livestream_queue_promoter"):
            self.assertTrue(_func_src(name), "thieu %s" % name)
        self.assertIn("QUEUE_FULL", SOURCE)
        promo = _func_src("_livestream_queue_try_promote")
        self.assertIn("api_livestream_record", promo)


class TestWatcherDynamic(unittest.TestCase):
    def test_watcher_dung_cap_dong(self):
        src = _func_src("_tiktok_live_watchdog")
        self.assertIn("_get_livestream_max_concurrent", src)


if __name__ == "__main__":
    unittest.main()
