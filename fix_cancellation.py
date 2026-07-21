import os, re

dir_path = r'app\src\main\java\com\nas\naswebdav'
count = 0

for root, dirs, files in os.walk(dir_path):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # check if it's already fixed
            if 'kotlinx.coroutines.CancellationException' in content:
                continue
            
            # replace `catch (e: Exception) {` with `catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {`
            new_content = re.sub(r'catch\s*\(\s*([a-zA-Z0-9_]+)\s*:\s*Exception\s*\)\s*\{', r'catch (\1: kotlinx.coroutines.CancellationException) { throw \1 } catch (\1: Exception) {', content)
            
            if new_content != content:
                with open(path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                print(f'Updated {path}')
                count += 1

print(f'Done. Updated {count} files.')
