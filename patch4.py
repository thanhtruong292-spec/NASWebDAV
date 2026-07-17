with open('nas_api_server.py', 'r', encoding='utf-8') as f:
    lines = f.readlines()

new_lines = []
for line in lines:
    if 'log.warning("[TikTokWatch] KhA' in line and 'primary_ok = True' in ''.join(new_lines[-2:]):
        continue
    new_lines.append(line)

with open('nas_api_server.py', 'w', encoding='utf-8') as f:
    f.writelines(new_lines)
