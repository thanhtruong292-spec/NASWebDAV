with open('app/src/main/java/com/nas/naswebdav/ui/screens/MainMenuScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if 'viewModel.thumbLastFile.substringAfterLast' in line:
        lines[i] = '                                    "Tệp: " + viewModel.thumbLastFile.substringAfterLast("/"),\n'

with open('app/src/main/java/com/nas/naswebdav/ui/screens/MainMenuScreen.kt', 'w', encoding='utf-8') as f:
    f.writelines(lines)
