import sys

kt_file = r'd:\Android\NASWebDAV\app\src\main\java\com\nas\naswebdav\ui\screens\MainMenuScreen.kt'
with open(kt_file, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace('Color(0xFF1E1E1E)', 'Color.Black')
text = text.replace('Color(0xFF2C2C2C)', 'Color(0xFF161616)')

with open(kt_file, 'w', encoding='utf-8') as f:
    f.write(text)

print("SUCCESS: Colors Replaced")
