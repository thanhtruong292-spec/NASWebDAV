with open('nas_api_server.py', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update NAS_TMP_ROOT from WEBDAV_FILE_ROOT to /tmp/
content = content.replace('NAS_TMP_ROOT = os.path.join(WEBDAV_FILE_ROOT, ".nas_meta", "tmp")', 'NAS_TMP_ROOT = "/tmp/nas_meta_tmp"')

# 2. Disable _save_tiktok_watch_state HDD write
target = '''    # Primary (HDD)
    try:
        os.makedirs(os.path.dirname(_TIKTOK_WATCH_FILE), exist_ok=True)
        tmp = _TIKTOK_WATCH_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(payload)
        os.replace(tmp, _TIKTOK_WATCH_FILE)
        primary_ok = True
    except Exception as e:
        log.warning("[TikTokWatch] KhA'ng lu c primary (HDD): %s", e)'''

replacement = '''    # Primary (HDD) disabled to reduce I/O and disk temp. State is saved to eMMC only.
    primary_ok = True'''

if target in content:
    content = content.replace(target, replacement)
    print('Patched _save_tiktok_watch_state')
else:
    print('Failed to patch _save_tiktok_watch_state')

with open('nas_api_server.py', 'w', encoding='utf-8') as f:
    f.write(content)
