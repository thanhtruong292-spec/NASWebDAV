# -*- coding: utf-8 -*-
"""Regression tests for the 2026-10-26 3-Axis Audit, Security Hardening & Strict Python 3.5.3 Compatibility."""

import ast
import builtins
import io
import os
import tokenize
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SERVER_PATH = REPO_ROOT / "backend" / "nas_api_server.py"
SOURCE = SERVER_PATH.read_text(encoding="utf-8")
TREE = ast.parse(SOURCE)


def _get_func(name):
    return next(
        n for n in TREE.body
        if isinstance(n, ast.FunctionDef) and n.name == name
    )


def _func_src(name):
    return ast.unparse(_get_func(name))


def _find_undefined_names(src):
    tree = ast.parse(src)
    builtin_names = set(dir(builtins)) | {"__file__", "__name__", "__doc__", "__package__", "__loader__", "__spec__"}

    def collect_targets(target, out):
        if isinstance(target, ast.Name):
            out.add(target.id)
        elif isinstance(target, (ast.Tuple, ast.List)):
            for elt in target.elts:
                collect_targets(elt, out)
        elif isinstance(target, ast.Starred):
            collect_targets(target.value, out)

    def collect_scope_defs(nodes):
        defs = set()
        for node in nodes:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                defs.add(node.name)
            elif isinstance(node, ast.Assign):
                for t in node.targets:
                    collect_targets(t, defs)
            elif isinstance(node, ast.AugAssign):
                collect_targets(node.target, defs)
            elif isinstance(node, ast.For):
                collect_targets(node.target, defs)
                defs |= collect_scope_defs(node.body)
                defs |= collect_scope_defs(node.orelse)
            elif isinstance(node, ast.With):
                for item in node.items:
                    if item.optional_vars:
                        collect_targets(item.optional_vars, defs)
                defs |= collect_scope_defs(node.body)
            elif isinstance(node, (ast.If, ast.While)):
                defs |= collect_scope_defs(node.body)
                defs |= collect_scope_defs(node.orelse)
            elif isinstance(node, ast.Try):
                defs |= collect_scope_defs(node.body)
                defs |= collect_scope_defs(node.orelse)
                defs |= collect_scope_defs(node.finalbody)
                for h in node.handlers:
                    if h.name:
                        defs.add(h.name)
                    defs |= collect_scope_defs(h.body)
            elif isinstance(node, ast.Import):
                for a in node.names:
                    defs.add(a.asname or a.name.split(".")[0])
            elif isinstance(node, ast.ImportFrom):
                for a in node.names:
                    defs.add(a.asname or a.name)
            elif isinstance(node, (ast.Global, ast.Nonlocal)):
                for n in node.names:
                    defs.add(n)
        return defs

    module_defs = builtin_names | collect_scope_defs(tree.body)
    undefined = []

    def walk_expr(node, visible):
        if node is None:
            return
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
            if node.id not in visible:
                undefined.append((node.lineno, node.id))
            return
        if isinstance(node, (ast.ListComp, ast.SetComp, ast.GeneratorExp, ast.DictComp)):
            comp_vis = set(visible)
            for gen in node.generators:
                walk_expr(gen.iter, comp_vis)
                collect_targets(gen.target, comp_vis)
                for ifc in gen.ifs:
                    walk_expr(ifc, comp_vis)
            if isinstance(node, ast.DictComp):
                walk_expr(node.key, comp_vis)
                walk_expr(node.value, comp_vis)
            else:
                walk_expr(node.elt, comp_vis)
            return
        if isinstance(node, ast.Lambda):
            lam_vis = set(visible)
            for a in node.args.args + node.args.kwonlyargs:
                lam_vis.add(a.arg)
            if node.args.vararg:
                lam_vis.add(node.args.vararg.arg)
            if node.args.kwarg:
                lam_vis.add(node.args.kwarg.arg)
            for d in node.args.defaults + [x for x in node.args.kw_defaults if x]:
                walk_expr(d, visible)
            walk_expr(node.body, lam_vis)
            return
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            for dec in node.decorator_list:
                walk_expr(dec, visible)
            for d in node.args.defaults + [x for x in node.args.kw_defaults if x]:
                walk_expr(d, visible)
            fn_vis = set(visible) | collect_scope_defs(node.body)
            for a in node.args.args + node.args.kwonlyargs:
                fn_vis.add(a.arg)
            if node.args.vararg:
                fn_vis.add(node.args.vararg.arg)
            if node.args.kwarg:
                fn_vis.add(node.args.kwarg.arg)
            for stmt in node.body:
                walk_expr(stmt, fn_vis)
            return
        if isinstance(node, ast.ClassDef):
            for dec in node.decorator_list:
                walk_expr(dec, visible)
            for b in node.bases:
                walk_expr(b, visible)
            cls_vis = set(visible) | collect_scope_defs(node.body)
            for stmt in node.body:
                walk_expr(stmt, cls_vis)
            return
        for child in ast.iter_child_nodes(node):
            walk_expr(child, visible)

    for stmt in tree.body:
        walk_expr(stmt, module_defs)
    return undefined


class TestAudit20261026Fixes(unittest.TestCase):
    def test_python353_no_36plus_syntax_or_stdlib_across_server_and_scripts(self):
        targets = [
            SERVER_PATH,
            REPO_ROOT / "backend" / "rust_bridge.py",
            REPO_ROOT / "backend" / "nas_work_coordinator.py",
            REPO_ROOT / "check_log.py"
        ] + sorted((REPO_ROOT / "scripts").glob("*.py"))
        forbidden_ast = ("JoinedStr", "FormattedValue", "AnnAssign", "NamedExpr", "AsyncGeneratorExp", "Match")
        forbidden_modules = {"secrets", "dataclasses", "contextvars", "zoneinfo", "graphlib", "tomllib"}
        forbidden_attrs = {
            "fromisoformat", "fromisocalendar", "monotonic_ns", "time_ns", "perf_counter_ns",
            "process_time_ns", "thread_time_ns", "get_native_id", "PROTOCOL_TLS_SERVER",
            "PROTOCOL_TLS_CLIENT", "PROTOCOL_TLS", "TLSVersion", "PathLike", "fspath",
            "removeprefix", "removesuffix", "isascii", "bit_count", "choices", "randbytes",
            "token_hex", "token_urlsafe", "token_bytes", "blake2b", "blake2s", "sha3_256",
            "sha3_512", "nullcontext", "register_at_fork", "waitstatus_to_exitcode",
        }
        forbidden_kwargs = {
            "dirs_exist_ok", "timespec", "max_age", "download_name", "etag",
            "missing_ok", "capture_output",
        }

        for path in targets:
            raw_bytes = path.read_bytes()
            self.assertFalse(raw_bytes.startswith(b"\xef\xbb\xbf"), "%s must not start with UTF-8 BOM" % path.name)
            txt = raw_bytes.decode("utf-8")
            tree = ast.parse(txt, filename=str(path))

            # 1. No numeric underscores (PEP 515 Python 3.6+)
            for tok in tokenize.tokenize(io.BytesIO(raw_bytes).readline):
                if tok.type == tokenize.NUMBER:
                    self.assertNotIn("_", tok.string, "%s line %d: numeric underscore %s not allowed in Python 3.5.3" % (path.name, tok.start[0], tok.string))

            for node in ast.walk(tree):
                tname = type(node).__name__
                self.assertNotIn(tname, forbidden_ast, "%s line %d: AST %s is Python 3.6+ only" % (path.name, getattr(node, "lineno", 0), tname))

                # 2. No 'with os.scandir(...)' (Python 3.6+)
                if isinstance(node, ast.With):
                    for item in node.items:
                        if isinstance(item.context_expr, ast.Call):
                            fstr = ast.unparse(item.context_expr.func)
                            self.assertNotIn("scandir", fstr, "%s line %d: with os.scandir() is Python 3.6+ only" % (path.name, node.lineno))

                # 3. No Python 3.6+ module imports
                if isinstance(node, ast.Import):
                    for a in node.names:
                        self.assertNotIn(a.name.split(".")[0], forbidden_modules, "%s line %d: import %s is Python 3.6+ only" % (path.name, node.lineno, a.name))
                elif isinstance(node, ast.ImportFrom) and node.module:
                    self.assertNotIn(node.module.split(".")[0], forbidden_modules, "%s line %d: from %s import is Python 3.6+ only" % (path.name, node.lineno, node.module))

                # 4. No Python 3.6+ attributes/methods
                if isinstance(node, ast.Attribute):
                    self.assertNotIn(node.attr, forbidden_attrs, "%s line %d: .%s is Python 3.6+ only" % (path.name, node.lineno, node.attr))

                # 5. No Python 3.6+/3.7+ subprocess or Flask 2.0+ kwargs
                if isinstance(node, ast.Call):
                    fn_str = ast.unparse(node.func)
                    kw_names = {k.arg for k in node.keywords if k.arg}
                    for bad_kw in forbidden_kwargs:
                        self.assertNotIn(bad_kw, kw_names, "%s line %d: %s(%s=...) is not supported on Python 3.5.3 / Flask 1.1.4" % (path.name, node.lineno, fn_str, bad_kw))
                    if fn_str in ("Popen", "check_output", "check_call", "run") or fn_str.endswith((".run", ".Popen", ".check_output", ".check_call", ".call")):
                        for sp_kw in ("capture_output", "text", "encoding", "errors"):
                            self.assertNotIn(sp_kw, kw_names, "%s line %d: %s(%s=...) is Python 3.6/3.7+ only" % (path.name, node.lineno, fn_str, sp_kw))

    def test_no_undefined_names_in_server_or_embedded_loop_script(self):
        self.assertEqual(_find_undefined_names(SOURCE), [], "nas_api_server.py must have zero undefined names")
        loop_scripts = [
            node.value.value for node in ast.walk(TREE)
            if isinstance(node, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == "loop_script" for t in node.targets)
            and isinstance(node.value, ast.Constant)
        ]
        self.assertEqual(len(loop_scripts), 1)
        self.assertEqual(_find_undefined_names(loop_scripts[0]), [], "embedded loop_script must have zero undefined names")

    def test_flask11_send_file_cache_timeout_in_api_thumb(self):
        src = _func_src("api_thumb")
        self.assertIn("cache_timeout=86400", src, "api_thumb must use cache_timeout=86400 for Flask 1.1.4")
        self.assertNotIn("max_age=", src, "api_thumb must not use Flask 2.0+ max_age kwarg")

    def test_scan_broken_mp4_does_not_quarantine_on_ffprobe_exception(self):
        src = _func_src("api_scan_broken_mp4")
        self.assertIn("[BrokenScan] Skip %s (ffprobe error: %s)", src)

    def test_validate_ip_and_cidr_strict(self):
        nodes = [n for n in TREE.body if isinstance(n, ast.FunctionDef) and n.name in ("_validate_ip", "_validate_cidr")]
        import re as _re
        ns = {
            "_re_module": _re,
            "_RE_SAFE_IP": _re.compile(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"),
            "_RE_SAFE_CIDR": _re.compile(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}/\d{1,2}$"),
        }
        for node in nodes:
            exec(compile(ast.Module([node], type_ignores=[]), "<%s>" % node.name, "exec"), ns)
        validate_ip = ns["_validate_ip"]
        validate_cidr = ns["_validate_cidr"]

        self.assertTrue(validate_ip("192.168.1.50"))
        self.assertTrue(validate_ip("100.127.84.101"))
        self.assertFalse(validate_ip("999.999.999.999"))
        self.assertFalse(validate_ip("0.0.0.0"))
        self.assertFalse(validate_ip("255.255.255.255"))

        self.assertTrue(validate_cidr("192.168.1.0/24"))
        self.assertFalse(validate_cidr("0.0.0.0/0"))
        self.assertFalse(validate_cidr("10.0.0.0/4"))

    def test_admin_decorators_on_sensitive_endpoints(self):
        admin_funcs = (
            "api_reboot",
            "api_suspend",
            "api_shutdown",
            "api_docker_control",
            "api_docker_power_post",
            "api_approve_ip",
            "api_cron_trash_clean",
            "api_lan_whitelist_add",
            "api_lan_whitelist_remove",
        )
        for name in admin_funcs:
            node = _get_func(name)
            decs = [ast.unparse(d) for d in node.decorator_list]
            self.assertTrue(
                any("requires_admin" in d for d in decs),
                "%s must require @requires_admin, got %s" % (name, decs),
            )

    def test_livestream_queue_and_record_validate_url(self):
        q_src = _func_src("api_livestream_queue_add")
        r_src = _func_src("api_livestream_record")
        self.assertIn("_social_validate_url", q_src)
        self.assertIn("_social_validate_url", r_src)

    def test_backup_restore_blocks_code_overwrite_on_uploaded_tar(self):
        src = _func_src("api_backup_restore")
        self.assertIn("cleanup_after and dest in", src)
        self.assertIn("/opt/nas_api_server.py", src)


if __name__ == "__main__":
    unittest.main()
