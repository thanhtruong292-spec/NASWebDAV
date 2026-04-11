import os

file_path = r'd:\Android\NASWebDAV\app\src\main\java\com\nas\naswebdav\ui\screens\LoginScreen.kt'

with open(file_path, 'rb') as f:
    content_bytes = f.read()

text = content_bytes.decode('utf-8', errors='ignore')
text = text.lstrip('\ufeff')

try:
    original_bytes = text.encode('windows-1252', errors='replace')
    original_text = original_bytes.decode('utf-8', errors='replace')
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(original_text)
    print("RESTORED!")
except Exception as e:
    print('Failed:', e)
