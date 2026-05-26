import sys

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

target = 'fun fetchLivestreamStatusOnly(context: android.content.Context) {'
if target in content:
    content = content.replace(target, 'fun WebDavViewModel.fetchLivestreamStatusOnly(context: android.content.Context) {')
    with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'w', encoding='utf-8', newline='') as f:
        f.write(content)
    print('SUCCESS')
else:
    print('NOT FOUND')