with open('nas_api_server.py', 'r', encoding='utf-8') as f:
    content = f.read()

# Revert fan
content = content.replace('FAN_DEFAULT_ON_TEMP = 39.0', 'FAN_DEFAULT_ON_TEMP = 42.0')
content = content.replace('FAN_DEFAULT_OFF_TEMP = 36.0', 'FAN_DEFAULT_OFF_TEMP = 38.0')
content = content.replace('FAN_HDD_FORCE_ON_TEMP = 42.0', 'FAN_HDD_FORCE_ON_TEMP = 45.0')

# Remove hdparm
target = '''lan_error_cycles = 0\n
    # Config HDD APM to spindown after 10m idle
    try:
        dev = _target_hdd_device_path()
        if dev:
            subprocess.run(["hdparm", "-S", "120", dev], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            log.info("[Watchdog] configured hdparm -S 120 for %s", dev)
    except Exception as e:
        log.warning("[Watchdog] APM hdparm error: %s", e)\n'''
content = content.replace(target, 'lan_error_cycles = 0\n')

with open('nas_api_server.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('Reverted successfully')
