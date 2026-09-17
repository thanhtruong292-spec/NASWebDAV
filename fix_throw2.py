import os, re

dir_path = r'app\src\main\java\com\nas\naswebdav'

for root, dirs, files in os.walk(dir_path):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # replace `catch (_: kotlinx.coroutines.CancellationException) { throw _ }`
            # with `catch (e: kotlinx.coroutines.CancellationException) { throw e }`
            new_content = re.sub(r'catch\s*\(\s*_\s*:\s*kotlinx\.coroutines\.CancellationException\s*\)\s*\{\s*throw\s*_\s*\}', r'catch (e: kotlinx.coroutines.CancellationException) { throw e }', content)
            
            if new_content != content:
                with open(path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                print(f"Fixed regex throw _ in {path}")
