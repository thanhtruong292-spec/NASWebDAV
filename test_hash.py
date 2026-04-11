import hashlib, os
def check_hash():
    file1 = '/srv/dev-disk-by-label-data/New folder/Từ Album của SM-G998U1/Camera/2025-02/201f13853f91375248216180740c4dab.png'
    h1 = hashlib.md5(file1.encode('utf-8')).hexdigest()
    p1 = '/srv/dev-disk-by-label-data/New folder/.thumbs/{}.jpg'.format(h1)

    file2 = '/srv/dev-disk-by-label-data/Từ Album của SM-G998U1/Camera/2025-02/201f13853f91375248216180740c4dab.png'
    h2 = hashlib.md5(file2.encode('utf-8')).hexdigest()
    p2 = '/srv/dev-disk-by-label-data/New folder/.thumbs/{}.jpg'.format(h2)

    file3 = '/New folder/Từ Album của SM-G998U1/Camera/2025-02/201f13853f91375248216180740c4dab.png'
    h3 = hashlib.md5(file3.encode('utf-8')).hexdigest()
    p3 = '/srv/dev-disk-by-label-data/New folder/.thumbs/{}.jpg'.format(h3)

    file4 = '/Từ Album của SM-G998U1/Camera/2025-02/201f13853f91375248216180740c4dab.png'
    h4 = hashlib.md5(file4.encode('utf-8')).hexdigest()
    p4 = '/srv/dev-disk-by-label-data/New folder/.thumbs/{}.jpg'.format(h4)

    print("Path1 (With New folder): {} - Exists? {}".format(h1, os.path.exists(p1)))
    print("Path2 (Without New folder): {} - Exists? {}".format(h2, os.path.exists(p2)))
    print("Path3 (Just New folder): {} - Exists? {}".format(h3, os.path.exists(p3)))
    print("Path4 (No drive root): {} - Exists? {}".format(h4, os.path.exists(p4)))

    # Quét thu muc de tim thuc te
    print("Tìm trong thu muc 5 file hien co:")
    for root, dirs, files in os.walk('/srv/dev-disk-by-label-data/New folder/.thumbs'):
        for name in files[:5]:
            print(name)
        break

if __name__ == "__main__":
    check_hash()
