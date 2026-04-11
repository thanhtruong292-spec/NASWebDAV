---
description: NAS Development Guidelines (Python 3.5 / RK3328 constraints)
---

# NAS Development Guidelines (Chainedbox L1 Pro / RK3328, Python 3.5)

To ensure stability and prevent regressions on the NAS Server (`nas_api_server.py`), **all future code modifications must strictly adhere to these rules**:

## 1. Zombie Process Prevention (CRITICAL)
Always implement anti-zombie measures. The background threads running `subprocess` commands (`smartctl`, `ffmpeg`, etc.) must not leave orphaned processes that crash the NAS.
- **Signal Handling:** You must implement a `SIGCHLD` handler to reap child processes (`os.waitpid(-1, os.WNOHANG)`).
- **Graceful Shutdown:** You must implement `SIGTERM` and `SIGINT` handlers to kill all child processes within the process group before terminating.
- **Port Cleanup:** Implement a pre-bind port cleanup wrapper (`_force_free_port()`) to clear ports 5050/5051 using `fuser -k` or `kill -9` on the holding process IDs, preventing `Address already in use` from zombie processes.

## 2. Python 3.5 Compatibility
The NAS runs an outdated Python 3.5 version. Do NOT use newer Python features that cause syntax or `TypeError` crashes:

- ❌ **No `capture_output=True`**: This parameter was introduced in Python 3.7. 
  - ✅ **Instead use:** `stdout=subprocess.PIPE, stderr=subprocess.PIPE`
- ❌ **No `text=True`**: This parameter was introduced in Python 3.7.
  - ✅ **Instead use:** `.decode('utf-8', errors='ignore')` on `result.stdout`.
- ❌ **No `subprocess.run(..., timeout=...)` without expecting potential `TimeoutExpired`**: While `timeout` works in 3.5, ensure exceptions are appropriately checked if not using `capture_output`. 

## 3. Tornado and Flask Port Binding
- ❌ **No `reuse_port=True`** in `tornado.netutil.bind_sockets()`. It is NOT supported and raises exceptions on the NAS.
- ✅ **Instead Use SO_REUSEADDR manually:**
  ```python
  _sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
  _sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
  _sock.bind(('0.0.0.0', 5051))
  ```

## 4. Single Deployment Artifact
The production API service executes from: `/opt/nas_api_server.py`.
- **Systemd Service:** `nas-api.service`.
- Do not maintain duplicated API server scripts (e.g., in `/root/`) to prevent version drift. 
- Separate independent scripts (like `/root/nas_watchdog.py`) are fine, but all API server logic resides in `/opt/`.

## 5. Hardware Constraints & Polling Intervals
The NAS is a Chainedbox L1 Pro powered by a resource-constrained Rockchip RK3328 (Cortex-A53). The primary storage is a mechanical **Seagate SkyHawk 4TB (ST4000VX000)** HDD designed for 24/7 operation but highly susceptible to I/O interrupts and spin-up locks.
- **NEVER use low-interval (< 10s) polling threads**: Python threads running `psutil.process_iter()` or any system `/proc` scraping constantly will crush the RK3328's CPU throughput. Minimum interval for system polling should be `10 seconds`.
- **NEVER poll the HDD synchronously**: Tools like `sudo smartctl` or `hddtemp` aggressively wake the HDD from S2/S3 standby and stall SATA data buses. These must run isolated with an absolute minimum loop cycle of `60 seconds` (e.g., `loop_count % 30 == 0` on a 2s base thread) to prevent mechanical thrashing.
- **CPU Core Normalization**: The RK3328 has 4 cores. Linux CPU telemetry from `psutil` intrinsically reads out single-threaded limits (up to 400%). All telemetry must pass through a strict `psutil.cpu_count()` cross-division (`proc.cpu_percent() / num_cores`) and be hard-capped using `min(val, 100.0)` for accuracy.
