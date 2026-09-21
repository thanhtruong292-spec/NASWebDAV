#!/usr/bin/env python3
"""Push updated nas_api_server.py to NAS and restart thumbnail daemon.

Usage:
    python scripts/push_server_to_nas.py

Requires: SSH key or password auth configured to NAS (root@192.168.100.254)
"""
import subprocess
import sys
import os

NAS_HOST = os.environ.get("NAS_HOST", "192.168.100.254")
NAS_USER = os.environ.get("NAS_USER", "root")
NAS_PATH = "/opt/nas_api_server.py"
LOCAL_PATH = os.path.join(os.path.dirname(__file__), "..", "backend", "nas_api_server.py")
if not os.path.exists(LOCAL_PATH):
    LOCAL_PATH = os.path.join(os.path.dirname(__file__), "..", "nas_api_server.py")

def run(cmd, check=True):
    print(f"  > {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    if result.stdout.strip():
        print(result.stdout.strip())
    if result.stderr.strip():
        print(result.stderr.strip(), file=sys.stderr)
    if check and result.returncode != 0:
        sys.exit(result.returncode)
    return result

def main():
    if not os.path.exists(LOCAL_PATH):
        print(f"ERROR: {LOCAL_PATH} not found")
        sys.exit(1)

    print(f"1. Uploading {LOCAL_PATH} -> {NAS_USER}@{NAS_HOST}:{NAS_PATH}")
    run(["scp", LOCAL_PATH, f"{NAS_USER}@{NAS_HOST}:{NAS_PATH}"])

    print(f"2. Restarting nas_api_server daemon...")
    run(["ssh", f"{NAS_USER}@{NAS_HOST}", "systemctl restart nas_api"])

    print(f"3. Verifying daemon is up...")
    import time
    time.sleep(2)
    run(["ssh", f"{NAS_USER}@{NAS_HOST}", "pgrep -f nas_api_server | head -1"], check=False)

    print("4. Check thumbnail debug logs with:")
    print(f"   ssh {NAS_USER}@{NAS_HOST} 'grep -E \"ThumbImg|ThumbVideo|thumb_err\" /var/log/nasapi.log | tail -20'")
    print("DONE")

if __name__ == "__main__":
    main()
