import sys, re

with open('nas_api_server.py', 'r', encoding='utf-8') as f:
    text = f.read()

start = text.find('def generate_fast_index():')
if start == -1:
    print('start not found')
    sys.exit(1)
    
end = text.find('def api_hash_batch():', start)
if end == -1:
    print('end not found')
    sys.exit(1)

# Find the @app.route preceeding api_hash_batch
end = text.rfind('@app.route', start, end)

new_code = '''CACHE_FILE = "/tmp/nas_fast_index_cache.json"

def generate_fast_index(force=False):
    import time
    if not force and os.path.exists(CACHE_FILE):
        if time.time() - os.path.getmtime(CACHE_FILE) < 86400: # 24 hours
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk: break
                    yield chunk
            return

    base_dir = get_webdav_root()
    media_exts = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".mp4", ".mkv", ".mov", ".avi"}
    base_len = len(base_dir)

    try:
        cache_f = open(CACHE_FILE, "w", encoding="utf-8")
    except Exception:
        cache_f = None

    yield '{"files":['
    if cache_f: cache_f.write('{"files":[')

    total = 0
    first = True
    for root, dirs, files in os.walk(base_dir):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d != '#recycle']
        for name in files:
            if name.startswith('.'): continue
            ext = os.path.splitext(name)[1].lower()
            if ext not in media_exts: continue
            full_path = os.path.join(root, name)
            try:
                st = os.stat(full_path)
                rel_path = full_path[base_len:]
                if not rel_path.startswith("/"): rel_path = "/" + rel_path
                
                s = json.dumps({"name": name, "path": "/webdav" + rel_path, "size": st.st_size, "mtime": int(st.st_mtime * 1000)})
                if not first:
                    yield ','
                    if cache_f: cache_f.write(',')
                first = False
                
                yield s
                if cache_f: cache_f.write(s)
                total += 1
            except Exception: pass

    tail = '], "total": %d}' % total
    yield tail
    if cache_f:
        cache_f.write(tail)
        cache_f.close()

@app.route("/api/disk/fast_index")
@requires_auth
def api_fast_index():
    force = request.args.get("force", "0") == "1"
    return Response(generate_fast_index(force), mimetype='application/json')

'''

with open('nas_api_server.py', 'w', encoding='utf-8') as f:
    f.write(text[:start] + new_code + text[end:])
    
print("Patched successfully")
