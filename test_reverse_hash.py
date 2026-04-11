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
    
    print("Starting hash sweeper...")
    for root, dirs, files in os.walk(base):
        for name in files:
            count += 1
            real_path = os.path.join(root, name)
            
            variants = [
                real_path,
                real_path.replace('/srv/dev-disk-by-label-data/New folder', '/srv/dev-disk-by-label-data'),
                real_path.replace('/srv/dev-disk-by-label-data/New folder', '/sharedfolders/Data'),
                real_path.replace('/srv/dev-disk-by-label-data/New folder', '/sharedfolders/Data/New folder'),
                real_path.replace('/srv/dev-disk-by-label-data/New folder', ''),
                name,
                '/webdav' + real_path.replace('/srv/dev-disk-by-label-data/New folder', '')
            ]
            
            for v in variants:
                try:
                    h = hashlib.md5(v.encode('utf-8')).hexdigest()
                    if h in target_hashes:
                        print("BINGO!!! Hash {} matches string: '{}'".format(h, v))
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
