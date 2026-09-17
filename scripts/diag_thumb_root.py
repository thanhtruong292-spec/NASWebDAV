import os, sys, json

# Mirror server's logic
def get_webdav_root():
    candidates = [
        "/srv/dev-disk-by-label-data/New folder",
        "/sharedfolders/Data/New folder",
        "/var/www/webdav/public",
        "/srv/dev-disk-by-label-data",
        "/sharedfolders/Data",
    ]
    for p in candidates:
        if os.path.isdir(p):
            return os.path.realpath(p)
    return candidates[0]

root = get_webdav_root()
print("Root:", root, "exists:", os.path.isdir(root))

# SMB roots from _resolve_webdav_request_path
smb_roots = ["/srv/dev-disk-by-label-data", "/sharedfolders/Data", "/var/www/webdav/public"]
seen = set()
total = 0
sample = []
for r in [root] + smb_roots:
    if not os.path.isdir(r):
        continue
    for dp, dns, fns in os.walk(r, topdown=True):
        dns[:] = [d for d in dns if d not in (".thumbnails",".trash",".nas_meta",".git",".recycle","@eaDir","#recycle")]
        for f in fns:
            if f.startswith("."):
                continue
            ext = os.path.splitext(f)[1].lower()
            if ext not in {".jpg",".jpeg",".png",".webp",".heic",".heif",".bmp",".gif",
                           ".mp4",".mkv",".avi",".mov",".mpg",".mpeg",".wmv",".flv",".ts",".m4v"}:
                continue
            full = os.path.join(dp, f)
            try:
                real = os.path.realpath(full)
            except OSError:
                continue
            if real in seen:
                continue
            seen.add(real)
            total += 1
            if len(sample) < 3:
                sample.append(real)
    if total > 10000:
        break

print("Total media found:", total)
print("Samples:")
for s in sample:
    print(" -", s)