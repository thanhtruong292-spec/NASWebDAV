import os, hashlib

target_hashes = [
    'cfc5cbaf344a340dd765ba0105ba8613',
    '35481c1c27f9fe50edfedf673c07ee48',
    'b62177c4097aa645a04329a02bff4d0c',
    '139fd10ea1c860f7c87193922e7d125d',
    '5a6b7e2ec4f739a85b0f8d288edc1700'
]

targets = [
    '/srv/dev-disk-by-label-data/New folder/New folder (2)/Thùy Dương/464108417_4698155580409995_8499866841630751432_n100006467987793.jpg',
    '/srv/dev-disk-by-label-data/New folder/New folder (2)/Linh Nguyễn/434355428_1829221894189409_6964463279666341257_n100013048482592.jpg',
    '/srv/dev-disk-by-label-data/New folder/New folder (2)/Phạm Ngọc Anh/67643494_2156116577831852_7349281615798861824_n.jpg',
    '/srv/dev-disk-by-label-data/New folder/New folder (2)/Bích Elly/61018050_2361780097384701_2214051230156587008_n.jpg',
    '/srv/dev-disk-by-label-data/New folder/Facebook/FB_IMG_1733541149742.jpg'
]

def check_files():
    # Kiem tra 5 file thuc te
    for i, path in enumerate(targets):
        rel = os.path.relpath(path, '/srv/dev-disk-by-label-data/New folder')
        hash_str = "New folder/" + rel
        safe_hash = hashlib.md5(hash_str.encode('utf-8')).hexdigest()
        
        expected_hash = target_hashes[i]
        match = (safe_hash == expected_hash)
        
        thumb_path = '/srv/dev-disk-by-label-data/New folder/.thumbs/{}.jpg'.format(safe_hash)
        exists = os.path.exists(thumb_path)
        
        print("File {}:".format(i+1))
        print("  Match: ", match)
        print("  Exists:", exists)
        
        if not exists:
            # Let's search why it doesn't exist
            print("  Check parent dir:", os.path.exists(os.path.dirname(thumb_path)))

if __name__ == '__main__':
    check_files()
