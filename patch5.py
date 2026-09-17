with open('nas_api_server.py', 'r', encoding='utf-8') as f:
    lines = f.readlines()

del lines[11217]

with open('nas_api_server.py', 'w', encoding='utf-8') as f:
    f.writelines(lines)
