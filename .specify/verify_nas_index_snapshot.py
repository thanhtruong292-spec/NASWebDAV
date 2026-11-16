"""Run existing small index regressions on NAS without installing pytest."""
import ast
import logging
import mimetypes
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import urllib.parse

from flask import Flask, jsonify, request
from backend import nas_work_coordinator


def main():
    source = Path("backend/nas_api_server.py").resolve()
    cases = (
        "test_search_refresh_ignores_only_its_own_interactive_idle_gate",
        "test_nas_busy_refresh_marks_previous_catalogue_unready",
        "test_index_pass_pauses_and_releases_lease_when_nas_becomes_busy",
    )
    tree = ast.parse(Path("tests/test_incremental_file_index.py").read_text(encoding="utf-8"))
    nodes = [node for node in tree.body if isinstance(node, ast.FunctionDef)
             and node.name in ("engine",) + cases]
    assert len(nodes) == 4
    for node in nodes:
        node.decorator_list = []
    module = ast.Module(body=nodes, type_ignores=[]) if sys.version_info >= (3, 8) else ast.Module(body=nodes)
    class CompatibleAst:
        FunctionDef = ast.FunctionDef
        parse = staticmethod(ast.parse)
        @staticmethod
        def Module(body, type_ignores=None):
            return ast.Module(body=body, type_ignores=[]) if sys.version_info >= (3, 8) else ast.Module(body=body)
    namespace = dict(__name__=__name__, ast=CompatibleAst, SOURCE=source, Flask=Flask, jsonify=jsonify,
                     request=request, sqlite3=sqlite3, os=os, time=time,
                     mimetypes=mimetypes, threading=threading, logging=logging,
                     urllib=urllib, nas_work_coordinator=nas_work_coordinator)
    exec(compile(module, "NAS index regressions", "exec"), namespace)
    for name in cases:
        with tempfile.TemporaryDirectory() as scratch:
            fixture = namespace["engine"](Path(scratch))
            namespace[name](fixture)
            assert not fixture[0]["_index_scan_lock"].locked()
        print("PASS " + name)


if __name__ == "__main__":
    main()
