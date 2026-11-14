#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Push updated nas_api_server.py to NAS and restart thumbnail daemon.

Usage:
    python scripts/push_server_to_nas.py

Requires: SSH key or password auth configured to NAS (root@192.168.100.254)
Compatible with Python 3.5.3+.
"""
import subprocess
import sys
import os
import time
import ast


def validate_server_source(path):
    with open(path, "r", encoding="utf-8") as handle:
        source = handle.read()
    tree = ast.parse(source)
    functions = {node.name for node in tree.body if isinstance(node, ast.FunctionDef)}
    required = {"api_fast_search", "api_storage_summary", "requires_auth"}
    if not required.issubset(functions):
        raise ValueError("Backend is incomplete or a placeholder; refusing to upload/restart")

NAS_HOST = os.environ.get("NAS_HOST", "192.168.100.254")
NAS_USER = os.environ.get("NAS_USER", "root")
NAS_PATH = "/opt/nas_api_server.py"
LOCAL_PATH = os.path.join(os.path.dirname(__file__), "..", "backend", "nas_api_server.py")
if not os.path.exists(LOCAL_PATH):
    LOCAL_PATH = os.path.join(os.path.dirname(__file__), "..", "nas_api_server.py")


def run(cmd, check=True):
    print("  > %s" % (" ".join(cmd),))
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    out = (result.stdout or b"").decode("utf-8", errors="ignore").strip()
    err = (result.stderr or b"").decode("utf-8", errors="ignore").strip()
    if out:
        print(out)
    if err:
        sys.stderr.write(err + "\n")
    if check and result.returncode != 0:
        sys.exit(result.returncode)
    return result


def main():
    if not os.path.exists(LOCAL_PATH):
        print("ERROR: %s not found" % (LOCAL_PATH,))
        sys.exit(1)

    validate_server_source(LOCAL_PATH)

    target = "%s@%s:%s" % (NAS_USER, NAS_HOST, NAS_PATH)
    ssh_target = "%s@%s" % (NAS_USER, NAS_HOST)

    print("1. Uploading %s -> %s" % (LOCAL_PATH, target))
    run(["scp", LOCAL_PATH, target])

    print("2. Restarting nas_api_server daemon...")
    run(["ssh", ssh_target, "systemctl restart nas_api"])

    print("3. Verifying daemon is up...")
    time.sleep(2)
    run(["ssh", ssh_target, "pgrep -f nas_api_server | head -1"], check=False)

    print("4. Check thumbnail debug logs with:")
    print("   ssh %s 'grep -E \"ThumbImg|ThumbVideo|thumb_err\" /var/log/nas_api.log | tail -20'" % (ssh_target,))
    print("DONE")


if __name__ == "__main__":
    main()
