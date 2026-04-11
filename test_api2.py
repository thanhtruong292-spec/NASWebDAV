import urllib.request, urllib.parse, base64

auth = base64.b64encode(b'admin:admin').decode('utf-8')
path = "/webdav/Từ Album của SM-G998U1/Camera/2025-02/201f13853f91375248216180740c4dab.png"
# Double encode
enc1 = urllib.parse.quote(path)
enc2 = urllib.parse.quote(enc1)

url = "http://127.0.0.1:5050/api/thumb?path=" + enc2

req = urllib.request.Request(url)
req.add_header("Authorization", "Basic " + auth)

try:
    with urllib.request.urlopen(req) as res:
        print("Status:", res.status)
        print("Size:", len(res.read()))
except Exception as e:
    if hasattr(e, 'read'):
        print("HTTP Error:", e.code, e.read().decode('utf-8'))
    else:
        print("Error:", e)
