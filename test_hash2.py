import hashlib, os
def check_hash():
    file5 = '/sharedfolders/Data/Từ Album của SM-G998U1/Camera/2025-02/201f13853f91375248216180740c4dab.png'
    h5 = hashlib.md5(file5.encode('utf-8')).hexdigest()
    p5 = '/srv/dev-disk-by-label-data/New folder/.thumbs/{}.jpg'.format(h5)

    file6 = '/sharedfolders/Data/New folder/Từ Album của SM-G998U1/Camera/2025-02/201f13853f91375248216180740c4dab.png'
    h6 = hashlib.md5(file6.encode('utf-8')).hexdigest()
    p6 = '/srv/dev-disk-by-label-data/New folder/.thumbs/{}.jpg'.format(h6)

    print("Path5 (/sharedfolders/Data/Từ Album...): {} - Exists? {}".format(h5, os.path.exists(p5)))
    print("Path6 (/sharedfolders/Data/New folder/...): {} - Exists? {}".format(h6, os.path.exists(p6)))

if __name__ == "__main__":
    check_hash()
