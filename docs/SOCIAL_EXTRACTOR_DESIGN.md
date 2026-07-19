# Social Extractor Integration — Design Doc

**Date:** 2026-07-19
**Status:** Backend implemented and deployed
**Author:** Claude (auto-generated, needs human review)

---

## 1. Problem Statement

Android app (`SocialExtractorScreen.kt`) calls `LivestreamViewModel.requestSocialDownload()`
which sends POST to `/api/social/download`. But the NAS server (`nas_api_server.py`)
does **not** implement this endpoint. The Android UI is ready; the backend is missing.

**Symptom:** User taps "NAS tự tải" → Android sends request → server returns 404.

---

## 2. Current State (What Already Exists)

### Android Side (READY)

| Component | File | Status |
|-----------|------|--------|
| SocialExtractorScreen | `ui/screens/SocialExtractorScreen.kt` | DONE — two modes: WebView pipe + NAS direct |
| LivestreamViewModel.requestSocialDownload | `livestream/LivestreamViewModel.kt:372` | DONE — calls `/api/social/download` |
| StreamPipeWorker | `StreamPipeWorker.kt:170` | DONE — handles `Downloads/social/` folder |
| AppConfig.SOCIAL_DOWNLOAD_FOLDER | `NasApplication.kt:403` | DONE — `"Downloads/social/"` |

**Request contract** (what Android sends):
```json
POST /api/social/download
Content-Type: application/json
{
  "url": "https://...",
  "folder": "Downloads/social/"
}
```

**Accepted response:**
```json
{ "result": "ok", "job_id": "<uuid>", "status": "queued", "url": "https://..." }
```
Clients must then poll `GET /api/social/status/{job_id}` until `completed` or `error`.
Errors keep the common shape:
```json
{ "error": "..." }
```

### Server Side (MISSING)

| Component | File | Status |
|-----------|------|--------|
| `/api/social/download` | `nas_api_server.py` | **DONE** |
| `_find_ytdlp_bin()` | `nas_api_server.py:10595` | DONE — reusable |
| `_detect_platform()` | `nas_api_server.py:10510` | DONE — reusable |
| `_ytdlp_lock` / `_ytdlp_jobs` | `nas_api_server.py:~10495` | DONE — concurrency control |
| yt-dlp recording system | `nas_api_server.py:~10500` | DONE — pattern to follow |
| Flask + `@requires_auth` | throughout | DONE — standard pattern |

---

## 3. Design: New Endpoint

### 3.1 Architecture: Async + Polling (DECIDED)

User taps "Download" → server returns 202 + job_id immediately → Android polls status.
- Avoids HTTP timeout on ARM (slow yt-dlp for large videos)
- Better UX with progress feedback
- Reuses existing `_ytdlp_lock` + `_ytdlp_jobs` pattern from livestream system

### 3.2 Storage Strategy: Temp → WebDAV Sync (DECIDED)

Flow:
1. yt-dlp downloads to `/var/tmp/social_downloads/{job_id}/` (RAM-friendly tmpfs if available)
2. After successful download, server moves file to `<WEBDAV_FILE_ROOT>/Downloads/social/`
3. Cleanup tmp after successful move

Why: Direct HDD write can stall on ARM with concurrent I/O. Temp → sync is safer.

### 3.3 Quality: Default `best` (DECIDED)

No quality parameter in v1. Always download best available format.
- Simplifies Android UI (no format selector)
- yt-dlp auto-merges best video + best audio
- Future: can add `quality` param without breaking changes

### 3.4 POST `/api/social/download`

**Request:**
```json
{ "url": "<string>", "folder": "<string, optional>" }
```

**Response 202 (accepted):**
```json
{
  "job_id": "<uuid>",
  "status": "queued",
  "url": "<original>"
}
```

**Response 429 (busy):**
```json
{ "error": "Max concurrent downloads reached (2). Vui lòng thử lại sau." }
```

**Response 400/500:**
```json
{ "error": "<message>" }
```

### 3.5 GET `/api/social/status/{job_id}`

Poll download progress.

**Response 200:**
```json
{
  "job_id": "<uuid>",
  "status": "queued|downloading|completed|error",
  "progress": 45,
  "filename": "<filename>",
  "size": <bytes>,
  "platform": "<facebook|tiktok|youtube|other>",
  "error_reason": null,
  "started_at": <unix_ts>,
  "finished_at": <unix_ts or null>
}
```

**Response 404:** `{ "error": "Job not found" }`

---

## 4. Implementation Plan

### Step 1: Add job tracking state

After existing livestream job state (line ~10507):

```python
_social_download_jobs = {}  # {job_id: {url, platform, pid, status, progress, output_file, started_at, finished_at, error_reason, folder}}
_social_download_lock = threading.Lock()
_SOCIAL_TMP_ROOT = "/var/tmp/social_downloads"
SOCIAL_MAX_CONCURRENT = 2
SOCIAL_MAX_FILESIZE = "2G"
SOCIAL_YTDLP_TIMEOUT = 300
```

### Step 2: Add helper functions

```python
def _social_validate_url(url):
    """Validate URL is http/https, no private IPs, no javascript: scheme."""
    # reuse _validate_ip logic

def _social_sanitize_folder(folder):
    """Ensure folder is relative, no .. or absolute paths."""
    # reject if starts with / or contains ..

def _social_start_download(job_id, url, folder):
    """Background thread: run yt-dlp, track progress, move to WebDAV."""
    # spawn subprocess.Popen with yt-dlp
    # poll progress from yt-dlp --newline -o progress
    # on complete: move from tmp to WEBDAV_FILE_ROOT/folder/
    # update _social_download_jobs[job_id]

def _social_cleanup_tmp(job_id):
    """Remove tmp dir after move or error."""
```

### Step 3: Add `/api/social/download` endpoint

Location: Insert after existing `/api/download` (line ~7693).

Logic:
1. Parse JSON body, extract `url` and optional `folder`
2. Validate URL + sanitize folder
3. Generate job_id = uuid
4. Check concurrent job count via `_social_download_lock`
5. If at limit → return 429
6. Create tmp dir, init job state, spawn `_social_start_download` thread
7. Return 202 + job_id

### Step 4: Add GET `/api/social/status/{job_id}`

Returns job state from `_social_download_jobs`. 404 if missing.

---

## 5. Security Considerations

- **Input validation:** URL must be http/https, resolve only to public IPs, and match a content URL on YouTube, TikTok, Instagram, or Facebook. Generic URLs and known redirector paths are rejected before yt-dlp starts.
- **Path traversal:** `folder` must be relative with at most two levels. The resolved destination must remain under the resolved WebDAV root, so symlinks cannot escape it.
- **Auth:** All endpoints use `@requires_auth`
- **Size limit:** Max 2GB per download (ARM RAM + disk)
- **Timeout:** yt-dlp timeout 300s (5 min)
- **No private content:** Only public URLs — this is a feature, not a scraper

---

## 6. Resource Limits

| Resource | Limit | Rationale |
|----------|-------|-----------|
| Concurrent downloads | 2 | ARM CPU/RAM constraint |
| Max file size | 2GB | Prevent disk exhaustion |
| yt-dlp timeout | 300s | Prevent zombie processes |
| Job TTL | 3600s | Cleanup stale jobs from the 60-second cron worker |
| Max folder depth | 2 levels | Prevent path traversal |

---

## 7. Testing Strategy

### Unit Tests (RED first)
1. Test URL validation (valid content URL, unsupported host/path, Unicode hostname, private DNS result)
2. Test folder sanitization plus resolved symlink containment
3. Test concurrent job limiting and collision-proof destination allocation
4. Test every worker exception path terminates and reaps yt-dlp

### Integration Tests
1. Test endpoint contract: valid POST returns 202 and status polling returns a terminal job
2. Test 429 when at max concurrency
3. Test error handling for invalid URL and worker-thread startup failure
4. Deployment smoke verifies Waitress, route registration, invalid private URL, and unknown job handling

### Manual Verification
1. From Android app: paste public YouTube link → verify file appears on NAS
2. Check yt-dlp process runs and exits cleanly
3. Verify file saved in correct WebDAV folder

---

## 8. Risk Assessment

| Risk | Mitigation |
|------|------------|
| yt-dlp not installed on NAS | `_find_ytdlp_bin()` already handles gracefully |
| ARM OOM during download | Max 2 concurrent + file size limit |
| Zombie yt-dlp process | Process-group termination plus unconditional reap in `finally` |
| Path traversal via folder param | Lexical validation plus resolved-root containment |
| Concurrent title collision | Every destination filename includes the full job UUID |
| SSRF via arbitrary extractor URL | Only supported platform content paths are accepted; DNS is checked at queue and spawn time |
| yt-dlp blocked by platform | Return clear error message to Android |

---

## 9. Decisions Log

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Concurrency model | **Async + polling** | Avoid HTTP timeout on ARM, better UX |
| Storage | **Temp → WebDAV sync** | Safer for ARM, avoid direct HDD write stalls |
| Quality | **Default `best`** | Simplify Android UI, can add param later |
| Max concurrent | 2 | ARM CPU + RAM constraint |
| Max file size | 2GB | Prevent disk exhaustion |

---

## 10. Open Questions (post-MVP)

1. Add SQLite persistence for jobs across NAS restart?
2. Add SSE/WebSocket for realtime progress (vs polling every 2s)?
3. Integrate with `_scan_photos_lightweight()` for auto-categorization?
4. Add retry queue when yt-dlp fails transiently (network blip)?

---

## 11. Implementation and Deployment Invariants

- Social Extractor logic lives inside the single production artifact, `/opt/nas_api_server.py`.
- Every `@app.route` declaration must execute before the `if __name__ == "__main__":` startup guard. Code after `main_loop.start()` is unreachable while the daemon runs and its routes will return 404.
- Deployment smoke tests must verify `OPTIONS /api/social/download` returns 200 with `POST` in the `Allow` header and `Server: waitress`.
- URL validation accepts only supported platform content paths, rejects Unicode host tricks, resolves A/AAAA records, and rejects non-public addresses both when queuing and immediately before spawning yt-dlp.
- The destination directory is checked with `realpath`/`commonpath`, and output names include the full job UUID to prevent cross-job overwrite.
- yt-dlp runs in a new process group with a 300-second deadline. Timeout and unexpected exception paths terminate the group, escalate to SIGKILL if needed, and reap the child.
- The 60-second cron worker expires all job states older than the 3600-second TTL; a worker-thread startup failure immediately transitions its queued job to `error`.

*Backend implementation verified and deployed on 2026-07-19.*
