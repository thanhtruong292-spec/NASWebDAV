import hashlib, os, psutil
def get_webdav_root():
    best_total = 0
    best_path = "/sharedfolders/Data"
    try:
        for p in psutil.disk_partitions(all=False):
            if "/srv/dev-disk" in p.mountpoint or "/mnt/" in p.mountpoint or "/sharedfolders" in p.mountpoint:
                u = psutil.disk_usage(p.mountpoint)
                if u.total > best_total:
                    best_total = u.total
                    best_path = p.mountpoint
                    if os.path.exists(os.path.join(p.mountpoint, "New folder")):
                        best_path = os.path.join(p.mountpoint, "New folder")
    except Exception as e:
        print("Error:", e)
    return best_path.rstrip('/')

def _get_thumb_path(base_dir, real_path):
    safe_hash = hashlib.md5(real_path.encode('utf-8')).hexdigest()
    return os.path.join(base_dir, ".thumbs", safe_hash + ".jpg")

def test():
    base_dir = get_webdav_root()
    webdav_path = "/webdav/Facebook/0238.jpg"
    local_rel = webdav_path.replace("/webdav", "", 1)
    real_path = base_dir + local_rel
    thumb_path = _get_thumb_path(base_dir, real_path)
    
    print("base_dir:", base_dir)
    print("real_path:", real_path)
    print("thumb_path:", thumb_path)
    print("Exists?", os.path.exists(thumb_path))

if __name__ == "__main__":
    test()
