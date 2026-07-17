import re
with open('nas_api_server.py', 'r', encoding='utf-8') as f:
    content = f.read()

pattern = r"# Primary \(HDD\)\s+try:.*?primary_ok = True\s+except Exception as e:.*?\n"
replacement = "# Primary (HDD) disabled to reduce I/O\n    primary_ok = True\n"
content, count = re.subn(pattern, replacement, content, flags=re.DOTALL)
print(f'Patched _save_tiktok_watch_state {count} times')

with open('nas_api_server.py', 'w', encoding='utf-8') as f:
    f.write(content)
