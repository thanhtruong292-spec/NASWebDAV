import sys
import re

with open(r'C:\Users\Truong\.gemini\antigravity-ide\brain\e0a1fa0c-9fa7-4b21-8f71-b3d6b7e7a871\artifacts\walkthrough.md', 'r', encoding='utf-8') as f:
    content = f.read()

content += '\n## Deployment Successful\n- Ứng dụng đã được cài đặt thành công lên thiết bị Android qua ADB Wifi.\n- Giao diện Livestream hiện không còn chớp nháy (flicker) trong khi kiểm tra trạng thái nền.\n- Thông báo tạo Thumbnail tự động sẽ xuất hiện trên thanh thông báo khi NAS rảnh rỗi và đang xử lý hình ảnh.'

with open(r'C:\Users\Truong\.gemini\antigravity-ide\brain\e0a1fa0c-9fa7-4b21-8f71-b3d6b7e7a871\artifacts\walkthrough.md', 'w', encoding='utf-8', newline='') as f:
    f.write(content)