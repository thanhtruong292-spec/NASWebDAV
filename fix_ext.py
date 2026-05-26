import sys

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('androidx.lifecycle.viewModelScope.launch', 'viewModelScope.launch')
content = content.replace('mutableListOf<LivestreamJob>()', 'mutableListOf<WebDavViewModel.LivestreamJob>()')
content = content.replace('add(LivestreamJob(', 'add(WebDavViewModel.LivestreamJob(')

with open(r'app\src\main\java\com\nas\naswebdav\WebDavViewModel.kt', 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('SUCCESS')