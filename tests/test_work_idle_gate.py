"""Exercise real Flask request stamping and the NAS-idle gate for lease claims."""
import ast
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
from types import SimpleNamespace

from flask import Flask, jsonify, request

from backend import nas_work_coordinator


SOURCE = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"


def _source_nodes(*names):
    tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
    wanted = set(names)
    nodes = []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in wanted:
            node.decorator_list = []
            nodes.append(node)
        elif isinstance(node, ast.Assign) and any(
                isinstance(target, ast.Name) and target.id in wanted for target in node.targets):
            nodes.append(node)
    assert len(nodes) == len(wanted)
    return nodes


def test_real_request_stamp_does_not_make_work_claim_look_like_user_activity(tmp_path):
    db_path = str(tmp_path / "catalogue.sqlite")
    root = str(tmp_path / "webdav")
    os.mkdir(root)
    with sqlite3.connect(db_path) as conn:
        conn.execute("CREATE TABLE index_state(id INTEGER PRIMARY KEY,root TEXT,generation INTEGER,ready INTEGER,scanned_at REAL,catalogue_revision INTEGER)")
        conn.execute("INSERT INTO index_state VALUES(1,?,1,1,?,11)", (root, time.time()))
    nas_work_coordinator.initialize(db_path)
    nas_work_coordinator.request_job(db_path, "duplicates", root, priority=10)

    namespace = {
        "request": request, "jsonify": jsonify, "time": time, "threading": threading,
        "os": SimpleNamespace(path=os.path, getloadavg=lambda: (0.0,)), "sqlite3": sqlite3,
        "_LAST_USER_HIT": {"time": time.time() - 120},
        "_background_heavy_gate_cache": {"time": 0, "allowed": False},
        "_get_heavy_gate_cache_s": lambda: 0,
        "_get_user_idle_s": lambda: 90,
        "_get_ram_block_pct": lambda: 78,
        "_get_load_block": lambda: 2.5,
        "_usb_import_running": False,
        "_livestream_lock": threading.Lock(),
        "_livestream_jobs": {},
        "_hdd_maintenance_allowed": lambda: True,
        "_heavy_background_processes": lambda: [],
        "psutil": SimpleNamespace(virtual_memory=lambda: SimpleNamespace(percent=10)),
        "_re_module": re,
        "nas_work_coordinator": nas_work_coordinator,
        "INDEX_DB_PATH": db_path,
        "FILE_INDEX_FRESHNESS_SECONDS": 60,
        "_cfg_sqlite_timeout": lambda _name, default: default,
        "_get_webdav_public_dir": lambda: root,
        "_index_scan_lock": threading.Lock(),
        "_refresh_file_index_for_search": lambda: None,
    }
    exec(compile(ast.Module(body=_source_nodes(
        "_NON_INTERACTIVE_HIT_PREFIXES", "_stamp_user_hit",
        "_background_heavy_work_allowed", "api_nas_work_claim",
        "_DUP_SCAN_LOCK", "_DUP_SCAN_STATE", "_DUP_AUTO_WORKER_LOCK"), type_ignores=[]),
        str(SOURCE), "exec"), namespace)
    app = Flask(__name__)
    app.before_request(namespace["_stamp_user_hit"])
    app.add_url_rule("/api/work/claim", view_func=namespace["api_nas_work_claim"], methods=["POST"])
    client = app.test_client()

    response = client.post("/api/work/claim", json={"name": "duplicates", "owner": "client:test"})
    assert response.status_code == 200
    assert response.json["token"]
    assert response.json["revision"] != response.json["catalogue_revision"]
    assert response.json["catalogue_revision"] == 11
    assert namespace["_background_heavy_work_allowed"]() is True

    client.get("/api/user-facing")
    assert time.time() - namespace["_LAST_USER_HIT"]["time"] < 2
    assert namespace["_background_heavy_work_allowed"]() is False


def test_idle_and_background_work_routes_are_excluded_but_browse_is_not():
    namespace = {"request": request, "time": time, "_LAST_USER_HIT": {"time": 0}}
    exec(compile(ast.Module(body=_source_nodes(
        "_NON_INTERACTIVE_HIT_PREFIXES", "_stamp_user_hit"), type_ignores=[]),
        str(SOURCE), "exec"), namespace)
    app = Flask(__name__)
    app.before_request(namespace["_stamp_user_hit"])
    app.add_url_rule("/api/work/request", "work", lambda: "ok")
    app.add_url_rule("/api/system/idle", "idle", lambda: "ok")
    app.add_url_rule("/api/duplicates/hash", "hash", lambda: "ok")
    app.add_url_rule("/api/duplicates/cleanup", "cleanup", lambda: "ok")
    app.add_url_rule("/api/thumb/activity", "thumb_activity", lambda: "ok")
    app.add_url_rule("/api/disk/fast_index", "fast_index", lambda: "ok")
    app.add_url_rule("/api/browser", "browser", lambda: "ok")
    client = app.test_client()

    for path in ("/api/work/request", "/api/system/idle", "/api/duplicates/hash",
                 "/api/duplicates/cleanup", "/api/thumb/activity", "/api/disk/fast_index"):
        client.get(path)
        assert namespace["_LAST_USER_HIT"]["time"] == 0
    client.get("/api/browser")
    assert namespace["_LAST_USER_HIT"]["time"] > 0

def test_hdd_pause_requires_two_distinct_cool_samples_and_rejects_stale():
    now = [0]
    ns = {"time": SimpleNamespace(monotonic=lambda: now[0]), "threading": threading}
    nodes = _source_nodes("_HDD_TEMP_CACHE", "_HDD_TEMP_CACHE_TTL", "_HDD_TEMP_REFRESH_LOCK",
                          "_cache_hdd_temp", "get_hdd_temp", "_hdd_maintenance_allowed")
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), "exec"), ns)
    samples = iter([40, 40, 41, 42, 40, None, 39, 40])
    def sample():
        temp = next(samples)
        return ns["_cache_hdd_temp"]("%d°C" % temp) if temp is not None else "--°C"
    ns["_get_hdd_temp_uncached"] = sample
    for expected in [False, True, True, False, False, False, False, True]:
        assert ns["_hdd_maintenance_allowed"]() is expected
        assert ns["_hdd_maintenance_allowed"]() is expected
        now[0] += 61

def test_temperature_requires_configured_drive_serial():
    report = "Serial Number: disk001\n194 Temperature_Celsius 0x0022 100 100 000 Old_age Always - 44 (Min/Max 26/48)"
    ns = {"_target_hdd_device_path": lambda: "/dev/sda", "_validate_disk_path": lambda disk: True,
          "run_cmd": lambda *args, **kwargs: report, "TARGET_HDD_SERIAL_HINTS": ("DISK001",),
          "_cache_hdd_temp": lambda value: value}
    exec(compile(ast.Module(body=_source_nodes("_get_hdd_temp_uncached"), type_ignores=[]),
                 str(SOURCE), "exec"), ns)
    assert ns["_get_hdd_temp_uncached"]() == "44°C"
    ns["TARGET_HDD_SERIAL_HINTS"] = ("OTHER",)
    assert ns["_get_hdd_temp_uncached"]() == "--°C"

def test_fan_only_stops_after_ten_continuous_cool_minutes():
    class EndSimulation(BaseException):
        pass
    ticks = iter([(0, 39), (1, 35), (500, 36), (501, 35), (1100, 35), (1101, 35)])
    current = [next(ticks)]
    applied = []
    def advance(seconds):
        try:
            current[0] = next(ticks)
        except StopIteration:
            raise EndSimulation()
    ns = dict(time=SimpleNamespace(monotonic=lambda: current[0][0], sleep=advance),
              run_cmd=lambda args: "", _load_fan_settings=lambda: {"mode":"custom"},
              _fan_temp_value=float, get_cpu_temp=lambda: 40, get_hdd_temp=lambda: current[0][1],
              FAN_DEFAULT_ON_TEMP=39, FAN_DEFAULT_OFF_TEMP=35, FAN_CPU_FORCE_ON_TEMP=75,
              FAN_HDD_FORCE_ON_TEMP=60, _cache_lock=threading.Lock(), _status_cache={},
              _fan_rpm_for_percent=lambda pct: 0, _fan_status_for_percent=str,
              _pwm_apply_level=lambda pct: applied.append((current[0][0], pct)),
              log=__import__("logging").getLogger("fan-test"))
    exec(compile(ast.Module(body=_source_nodes("_fan_change_delay", "_fan_controller_watchdog"),
                            type_ignores=[]), str(SOURCE), "exec"), ns)
    try:
        ns["_fan_controller_watchdog"]()
    except EndSimulation:
        pass
    assert applied == [(0, 30), (1101, 0)]


def test_fan_does_not_blip_when_hdd_temp_unavailable_or_in_hysteresis():
    class EndSimulation(BaseException):
        pass
    # Ticks: t=0 HDD=0 (chưa đọc được), t=1 HDD=41 (khoảng trễ 40-42), t=2 HDD=41
    ticks = iter([(0, 0.0), (1, 41.0), (2, 41.0)])
    current = [next(ticks)]
    applied = []
    def advance(seconds):
        try:
            current[0] = next(ticks)
        except StopIteration:
            raise EndSimulation()
    ns = dict(time=SimpleNamespace(monotonic=lambda: current[0][0], sleep=advance),
              run_cmd=lambda args: "", _load_fan_settings=lambda: {"mode": "custom", "on_temp": 42.0, "off_temp": 40.0},
              _fan_temp_value=float, get_cpu_temp=lambda: 45.0, get_hdd_temp=lambda: current[0][1],
              FAN_DEFAULT_ON_TEMP=42.0, FAN_DEFAULT_OFF_TEMP=40.0, FAN_CPU_FORCE_ON_TEMP=75.0,
              FAN_HDD_FORCE_ON_TEMP=60.0, _cache_lock=threading.Lock(), _status_cache={},
              _fan_rpm_for_percent=lambda pct: 0, _fan_status_for_percent=str,
              _pwm_apply_level=lambda pct: applied.append((current[0][0], pct)),
              log=__import__("logging").getLogger("fan-test"))
    exec(compile(ast.Module(body=_source_nodes("_fan_change_delay", "_fan_controller_watchdog"),
                            type_ignores=[]), str(SOURCE), "exec"), ns)
    try:
        ns["_fan_controller_watchdog"]()
    except EndSimulation:
        pass
    # Khi HDD=0 (chưa đọc được) và HDD=41 (dưới ngưỡng bật 42), quạt KHÔNG ĐƯỢC bật lên 100% hay nháy
    assert applied == [(0, 0)], "Quạt phải giữ 0% không được nháy bật khi HDD=0 hoặc ở vùng trễ 41C"


def test_manual_to_auto_discards_old_sample_before_publishing_mode():
    state = {"mode": "on"}
    cache = {"ts": 1, "attempt": 1, "value": "35°C", "paused": False, "cool": 2}
    saves = []
    ns = dict(request=SimpleNamespace(json={"mode": "auto"}), jsonify=lambda value: value,
              _load_fan_settings=lambda: dict(state), _HDD_TEMP_CACHE=cache,
              _HDD_TEMP_REFRESH_LOCK=threading.Lock(), _cache_lock=threading.Lock(),
              _status_cache={}, FAN_DEFAULT_ON_TEMP=42, FAN_DEFAULT_OFF_TEMP=40,
              run_cmd=lambda args: None)
    def save(settings):
        saves.append(cache["ts"])
        state.update(settings)
    ns["_save_fan_settings"] = save
    exec(compile(ast.Module(body=_source_nodes("api_fan_control"), type_ignores=[]),
                 str(SOURCE), "exec"), ns)
    assert ns["api_fan_control"]()["mode"] == "auto"
    assert saves == [None] and cache["paused"] and cache["cool"] == 0
    cache["ts"] = 7
    assert ns["api_fan_control"]()["mode"] == "auto"
    assert saves == [None, 7]


def test_switch_from_manual_on_keeps_fan_running_for_cooldown():
    class End(BaseException):
        pass
    ticks = iter([(0, "on"), (1, "auto"), (600, "auto"), (601, "auto")])
    tick = [next(ticks)]
    outputs = []
    def advance(seconds):
        try:
            tick[0] = next(ticks)
        except StopIteration:
            raise End()
    ns = dict(time=SimpleNamespace(monotonic=lambda: tick[0][0], sleep=advance),
              _load_fan_settings=lambda: {"mode":tick[0][1]}, get_hdd_temp=lambda: 39,
              get_cpu_temp=lambda: 38, _fan_temp_value=float, _cache_lock=threading.Lock(),
              _status_cache={}, FAN_DEFAULT_ON_TEMP=42, FAN_DEFAULT_OFF_TEMP=40,
              FAN_CPU_FORCE_ON_TEMP=75, FAN_HDD_FORCE_ON_TEMP=60, run_cmd=lambda args: None,
              _fan_rpm_for_percent=int, _fan_status_for_percent=str,
              _pwm_apply_on=lambda **kwargs: outputs.append((tick[0][0], 100)),
              _pwm_apply_level=lambda value: outputs.append((tick[0][0], value)),
              log=__import__("logging").getLogger("fan-test"))
    exec(compile(ast.Module(body=_source_nodes("_fan_change_delay", "_fan_controller_watchdog"),
                            type_ignores=[]), str(SOURCE), "exec"), ns)
    try:
        ns["_fan_controller_watchdog"]()
    except End:
        pass
    assert outputs == [(0, 100), (601, 0)]


def test_concurrent_hdd_reader_reuses_inflight_sample():
    ns = {"time": time, "threading": threading}
    names = ("_HDD_TEMP_CACHE", "_HDD_TEMP_CACHE_TTL", "_HDD_TEMP_REFRESH_LOCK",
             "_cache_hdd_temp", "get_hdd_temp")
    exec(compile(ast.Module(body=_source_nodes(*names), type_ignores=[]), str(SOURCE), "exec"), ns)
    entered = threading.Event()
    release = threading.Event()
    probes = []
    def probe():
        probes.append(True)
        entered.set()
        assert release.wait(3)
        return ns["_cache_hdd_temp"]("36°C")
    ns["_get_hdd_temp_uncached"] = probe
    reader = threading.Thread(target=ns["get_hdd_temp"])
    reader.start()
    try:
        assert entered.wait(2)
        timer = threading.Timer(0.15, release.set)
        timer.start()
        assert ns["get_hdd_temp"]() == "36°C"
        assert len(probes) == 1
    finally:
        release.set()
        reader.join(3)
