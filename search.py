import sys, re
sys.stdout.reconfigure(encoding='utf-8')
with open('app/src/main/java/com/nas/naswebdav/ui/screens/MainMenuScreen.kt', encoding='utf-8') as f:
    text = f.read()
matches = re.findall(r'Text\("(.*?)"', text)
for m in set(matches):
    print(m)
