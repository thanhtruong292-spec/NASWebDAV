html = open('test_tiktok.html', 'r', encoding='utf-8').read()
import re
m = re.search(r'<title[^>]*>(.*?)</title>', html, re.IGNORECASE)
if m:
    with open('out.txt','w', encoding='utf-8') as f:
        f.write(m.group(1))
