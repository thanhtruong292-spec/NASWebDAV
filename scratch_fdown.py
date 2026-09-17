import urllib.request, urllib.parse, re
req = urllib.request.Request(
    'https://fdown.net/download.php',
    data=urllib.parse.urlencode({'URLz': 'https://www.facebook.com/watch/?v=10153231379946729'}).encode(),
    headers={'User-Agent': 'Mozilla/5.0'}
)
try:
    html = urllib.request.urlopen(req, timeout=10).read().decode('utf-8', errors='ignore')
    hd = re.search(r'href=\"([^\"]+)\"[^>]*>Download Video in HD', html)
    sd = re.search(r'href=\"([^\"]+)\"[^>]*>Download Video in Normal', html)
    print('HD:', hd.group(1) if hd else 'None')
    print('SD:', sd.group(1) if sd else 'None')
except Exception as e:
    print('Error:', e)
