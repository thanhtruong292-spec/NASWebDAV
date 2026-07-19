# -*- coding: utf-8 -*-
"""Load pure Social Extractor logic from the single server artifact."""

import ast
import os
from pathlib import Path
import re
import threading
import time
import types
import uuid


def load_social_logic():
    source_path = Path(__file__).resolve().parents[1] / "nas_api_server.py"
    source = source_path.read_text(encoding="utf-8")
    start_marker = "# -- Social Extractor Logic BEGIN"
    end_marker = "# -- Social Extractor Logic END"
    start = source.index(start_marker)
    end = source.index(end_marker, start)
    block = source[start:end]

    module = types.ModuleType("_nas_social_logic")
    module.__dict__.update({
        "_re_module": re,
        "os": os,
        "threading": threading,
        "time": time,
        "uuid": uuid,
    })
    exec(compile(block, str(source_path), "exec"), module.__dict__)

    tree = ast.parse(source, filename=str(source_path))
    detector = next(
        node for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == "_detect_platform"
    )
    detector_module = ast.Module(body=[detector], type_ignores=[])
    ast.fix_missing_locations(detector_module)
    exec(compile(detector_module, str(source_path), "exec"), module.__dict__)
    return module
