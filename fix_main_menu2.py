import sys

with open(r'app\src\main\java\com\nas\naswebdav\ui\screens\MainMenuScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('viewModel.fetchTikTokLiveWatchState(mContext)', 'viewModel.fetchTikTokLiveWatch(mContext)')

with open(r'app\src\main\java\com\nas\naswebdav\ui\screens\MainMenuScreen.kt', 'w', encoding='utf-8', newline='') as f:
    f.write(content)