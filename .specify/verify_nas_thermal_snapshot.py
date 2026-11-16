"""Read-only SMART identity/parser check plus synthetic thermal-cache sequence."""
import ast
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import threading
from types import SimpleNamespace


def module(nodes):
    return ast.Module(body=nodes, type_ignores=[]) if sys.version_info >= (3, 8) else ast.Module(body=nodes)


def main():
    source = Path("backend/nas_api_server.py")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    assert digest == sys.argv[1].lower(), "Unexpected source snapshot"
    tree = ast.parse(source.read_text(encoding="utf-8"))
    names = {"_HDD_TEMP_CACHE", "_HDD_TEMP_CACHE_TTL", "_HDD_TEMP_REFRESH_LOCK",
             "_cache_hdd_temp", "get_hdd_temp", "_hdd_maintenance_allowed"}
    nodes = [node for node in tree.body if
             (isinstance(node, ast.FunctionDef) and node.name in names) or
             (isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id in names for target in node.targets))]
    now = [0]
    ns = {"time": SimpleNamespace(monotonic=lambda: now[0]), "threading": threading}
    exec(compile(module(nodes), str(source), "exec"), ns)
    samples = iter([40, 40, 41, 42, 40, None, 39, 40])
    def sample():
        temp = next(samples)
        return ns["_cache_hdd_temp"]("%d°C" % temp) if temp is not None else "--°C"
    ns["_get_hdd_temp_uncached"] = sample
    results = []
    for expected in [False, True, True, False, False, False, False, True]:
        actual = ns["_hdd_maintenance_allowed"]()
        assert actual is expected
        assert ns["_hdd_maintenance_allowed"]() is expected
        results.append(actual)
        now[0] += 61
    print("THERMAL_SEQUENCE " + str(results))
    report = subprocess.check_output(["smartctl", "-i", "-A", "/dev/sda"]).decode("utf-8", errors="ignore")
    serial = re.search(r"^Serial Number:\s*(\S+)", report, re.M | re.I)
    identity = next(node for node in tree.body if isinstance(node, ast.Assign) and
                    any(isinstance(target, ast.Name) and target.id == "TARGET_HDD_SERIAL_HINTS" for target in node.targets))
    parser = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "_get_hdd_temp_uncached")
    parser_ns = {"_target_hdd_device_path": lambda: "/dev/sda", "_validate_disk_path": lambda disk: disk == "/dev/sda",
                 "run_cmd": lambda *args, **kwargs: report, "_cache_hdd_temp": lambda value: value}
    exec(compile(module([identity, parser]), str(source), "exec"), parser_ns)
    matched = bool(serial and serial.group(1).upper() in parser_ns["TARGET_HDD_SERIAL_HINTS"])
    print("serial_matches_config=" + str(matched))
    for field in ("Device Model", "Model Family"):
        match = re.search(r"^" + field + r":\s*(.+)$", report, re.M | re.I)
        print(field + "=" + (match.group(1).strip() if match else "unreported"))
    temperature = parser_ns["_get_hdd_temp_uncached"]()
    print("temperature=" + temperature)
    assert matched and temperature != "--°C", "Drive identity or temperature unavailable"


if __name__ == "__main__":
    main()
