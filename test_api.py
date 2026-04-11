import urllib.request
from urllib.parse import quote
import base64

auth = base64.b64encode(b'admin:admin').decode('utf-8')
path = "/webdav/Từ Album của SM-G998U1/Camera/2025-02/201f13853f91375248216180740c4dab.png"
url = "http://127.0.0.1:5050/api/thumb?path=" + quote(path)

req = urllib.request.Request(url)
req.add_header('Authorization', 'Basic ' + auth)

try:
    with urllib.request.urlopen(req) as response:
        print("Status:", response.status)
        print("Content Type:", response.getheader('Content-Type'))
        print("Size:", len(response.read()))
except Exception as e:
    print("Error:", e)
