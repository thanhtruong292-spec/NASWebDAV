import os, re

dir_path = r'app\src\main\java\com\nas\naswebdav'

for root, dirs, files in os.walk(dir_path):
    for file in files:
        if file.endswith('.kt'):
            path = os.path.join(root, file)
            with open(path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # Fix throw _
            if 'throw _' in content or 'catch (_: kotlinx.coroutines.CancellationException)' in content:
                content = content.replace('catch (_: kotlinx.coroutines.CancellationException) { throw _ } catch (_: Exception)', 'catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception)')
                with open(path, 'w', encoding='utf-8') as f:
                    f.write(content)
                print(f"Fixed throw _ in {path}")
