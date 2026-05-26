import sys

with open(r'nas_api_server.py', 'r', encoding='utf-8') as f:
    content = f.read()

old_code = '''def api_tiktok_live_watch_get():
    with _tiktok_watch_lock:
        _load_tiktok_watch_state()
        changed = False
        for user in _tiktok_watch_state.get("users", []):
            if user.get("status") == "recording":
                jid = user.get("job_id", "")
                active = False
                if jid:
                    with _livestream_lock:
                        info = _livestream_jobs.get(jid)
                        if info and info.get("status") == "recording":
                            try:
                                os.kill(info.get("pid"), 0)
                                active = True
                            except Exception:
                                active = False'''

new_code = '''def api_tiktok_live_watch_get():
    # Pre-fetch livestream info to avoid nested locks (deadlock prevention)
    active_livestreams = {}
    with _livestream_lock:
        for k, v in _livestream_jobs.items():
            active_livestreams[k] = (v.get("status"), v.get("pid"))

    with _tiktok_watch_lock:
        _load_tiktok_watch_state()
        changed = False
        for user in _tiktok_watch_state.get("users", []):
            if user.get("status") == "recording":
                jid = user.get("job_id", "")
                active = False
                if jid:
                    l_status, l_pid = active_livestreams.get(jid, (None, None))
                    if l_status == "recording" and l_pid is not None:
                        try:
                            os.kill(l_pid, 0)
                            active = True
                        except Exception:
                            active = False'''

if old_code in content:
    content = content.replace(old_code, new_code)
    with open(r'nas_api_server.py', 'w', encoding='utf-8', newline='') as f:
        f.write(content)
    print('SUCCESS_FIX_LOCK')
else:
    print('OLD CODE NOT FOUND')