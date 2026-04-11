import hashlib, os

target_hashes = {
    '35481c1c27f9fe50edfedf673c07ee48',
    '5a6b7e2ec4f739a85b0f8d288edc1700',
    '139fd10ea1c860f7c87193922e7d125d',
    'b62177c4097aa645a04329a02bff4d0c',
    'cfc5cbaf344a340dd765ba0105ba8613'
}

def guess_hash():
    base = '/srv/dev-disk-by-label-data/New folder'
    count = 0
    
    print("Starting corrected hash sweeper...")
    for root, dirs, files in os.walk(base):
        for name in files:
            count += 1
            real_path = os.path.join(root, name)
            
            # Extract standard relative path (no leading slash)
            rel_pure = os.path.relpath(real_path, base)  # "Từ Album của SM-G998U1/Camera/2025-02/img.jpg"
            
            variants = [
                rel_pure,
                "New folder/" + rel_pure,
                "/srv/dev-disk-by-label-data/New folder/" + rel_pure,
                "/srv/dev-disk-by-label-data/" + rel_pure,
                "/sharedfolders/Data/" + rel_pure,
                name
            ]
            
            for v in variants:
                try:
                    h = hashlib.md5(v.encode('utf-8')).hexdigest()
                    if h in target_hashes:
                        print("BINGO! Hash {} matches EXACT string: '{}'".format(h, v))
                        target_hashes.remove(h)
                        if not target_hashes:
                            return
                except:
                    pass
                    
        if count % 10000 == 0:
            print("  ...checked {} files".format(count))
            
    print("Checked {} files total. Hashes remaining: {}".format(count, target_hashes))

if __name__ == '__main__':
    guess_hash()
