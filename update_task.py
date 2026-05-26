import sys

with open(r'C:\Users\Truong\.gemini\antigravity-ide\brain\e0a1fa0c-9fa7-4b21-8f71-b3d6b7e7a871\artifacts\task.md', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('[ ] 5. Xác nhận và gỡ lỗi:', '[x] 5. Xác nhận và gỡ lỗi:')

with open(r'C:\Users\Truong\.gemini\antigravity-ide\brain\e0a1fa0c-9fa7-4b21-8f71-b3d6b7e7a871\artifacts\task.md', 'w', encoding='utf-8', newline='') as f:
    f.write(content)