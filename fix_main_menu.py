import sys

with open(r'app\src\main\java\com\nas\naswebdav\ui\screens\MainMenuScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

old_loop = '''        while (true) {
            kotlinx.coroutines.delay(30_000L) // poll nhẹ mỗi 30 giây, không flicker
            viewModel.fetchLivestreamStatusOnly(mContext)
        }'''

new_loop = '''        while (true) {
            kotlinx.coroutines.delay(30_000L) // poll nhẹ mỗi 30 giây, không flicker
            viewModel.fetchLivestreamStatusOnly(mContext)
            viewModel.fetchTikTokLiveWatchState(mContext)
        }'''

if old_loop in content:
    content = content.replace(old_loop, new_loop)
else:
    print('OLD LOOP NOT FOUND')

with open(r'app\src\main\java\com\nas\naswebdav\ui\screens\MainMenuScreen.kt', 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('SUCCESS_UI')