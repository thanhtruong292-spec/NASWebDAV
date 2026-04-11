import os

folder = r'd:\Android\NASWebDAV\app\src\main\java\com\nas\naswebdav'
out_path = r'C:\Users\Truong\.gemini\antigravity\artifacts\mangled_scan.md'
os.makedirs(os.path.dirname(out_path), exist_ok=True)

with open(out_path, 'w', encoding='utf-8') as out:
    out.write('# Mangled Strings Scan Results\n\n')
    
    for root, dirs, files in os.walk(folder):
        for file in files:
            if file.endswith('.kt'):
                path = os.path.join(root, file)
                with open(path, 'r', encoding='utf-8', errors='ignore') as f:
                    lines = f.readlines()
                
                bad_lines = []
                for i, line in enumerate(lines):
                    # Check for typical ISO-8859-1 double encoding characters:
                    # 'đ' gets double encoded to 'Ä‘' (C3 84 C2 91)
                    # 'ã' gets double encoded to 'Ã£'
                    # 'á' gets double encoded to 'Ã¡'
                    # Or the replacement character U+FFFD 
                    if 'Ä' in line or 'Ã' in line or '\ufffd' in line:
                        bad_lines.append(f'Line {i+1}: `{line.strip()}`')
                
                if bad_lines:
                    out.write(f'## {file}\n')
                    for bl in bad_lines:
                        out.write(f'- {bl}\n')
                    out.write('\n')

print('Scan complete!')
