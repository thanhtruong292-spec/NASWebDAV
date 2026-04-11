import os

with open('app/src/main/java/com/nas/naswebdav/ui/screens/BrowserScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

start_idx = -1
for i, line in enumerate(lines):
    if '// Hộp thoại Hiển thị danh sách File Trùng Lặp' in line:
        start_idx = i
        break

end_idx = -1
for i in range(start_idx, len(lines)):
    if 'val displayedFiles by remember' in lines[i]:
        end_idx = i - 1
        break

while end_idx > start_idx and lines[end_idx].strip() == '':
    end_idx -= 1

print(f'Start: {start_idx}, End: {end_idx}')
dialog_code = lines[start_idx:end_idx+1]

result = '''@Composable
fun DuplicateFilesDialog(viewModel: WebDavViewModel, onDismiss: () -> Unit) {
    var selectedFilter by remember { mutableStateOf("all") }
'''

for i in range(len(dialog_code)):
    if 'AlertDialog(' in dialog_code[i]:
        for j in range(i, len(dialog_code)):
            line = dialog_code[j]
            if line.startswith('    '):
                line = line[4:]
            
            if 'viewModel.isShowingDuplicates = false' in line:
                line = line.replace('viewModel.isShowingDuplicates = false', 'onDismiss()')
            
            # remove the `    if (viewModel.isShowingDuplicates) {` ending brace
            if j == len(dialog_code) - 1 and line.strip() == '}':
                pass
            else:
                result += line
        break

result += '}\n'

with open('append.kt', 'w', encoding='utf-8') as f:
    f.write(result)
