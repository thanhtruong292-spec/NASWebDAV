import re

path = r'app\src\main\java\com\nas\naswebdav\ui\screens\MainMenuScreen.kt'

with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Add import if missing
import_stmt = "import androidx.compose.runtime.saveable.rememberSaveable\n"
if "import androidx.compose.runtime.saveable.rememberSaveable" not in content:
    content = content.replace("import androidx.compose.runtime.remember\n", "import androidx.compose.runtime.remember\n" + import_stmt)

# Replace only for state variables that are primitives or Strings to be safe
# But since we want to fix dialog states, most of them are Boolean, String, or Int?
content = re.sub(r'remember\s*\{\s*mutableStateOf', r'rememberSaveable { mutableStateOf', content)

# But wait! There is `androidx.compose.runtime.remember { mutableStateOf(...) }`
content = re.sub(r'androidx\.compose\.runtime\.remember\s*\{\s*mutableStateOf', r'rememberSaveable { mutableStateOf', content)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Fixed C1 in MainMenuScreen.kt")
