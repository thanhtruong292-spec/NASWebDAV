import sys

with open(r'nas_api_server.py', 'r', encoding='utf-8') as f:
    content = f.read()

watchdog_code = '''def _deadlock_watchdog():
    """Giám sát các lock quan trọng để tự động restart nếu bị deadlock."""
    import time
    import os
    while True:
        time.sleep(60)
        
        # Test livestream lock
        ok = _livestream_lock.acquire(timeout=30.0)
        if ok:
            _livestream_lock.release()
        else:
            log.error("[Deadlock] Phát hiện kẹt _livestream_lock quá 30s! Tự khởi động lại server...")
            os.system("nohup python3 /root/nas_api_server.py > /tmp/nas_api.log 2>&1 &")
            os._exit(1)
            
        # Test tiktok watch lock
        ok = _tiktok_watch_lock.acquire(timeout=30.0)
        if ok:
            _tiktok_watch_lock.release()
        else:
            log.error("[Deadlock] Phát hiện kẹt _tiktok_watch_lock quá 30s! Tự khởi động lại server...")
            os.system("nohup python3 /root/nas_api_server.py > /tmp/nas_api.log 2>&1 &")
            os._exit(1)

'''

if '_deadlock_watchdog' not in content:
    # Insert before if __name__ == "__main__":
    content = content.replace('if __name__ == "__main__":', watchdog_code + '\nif __name__ == "__main__":')
    # Start the thread in main
    start_thread = '''    threading.Thread(target=_tiktok_live_watchdog, daemon=True, name="TikTokLiveWatchdog").start()
    log.info("[TikTokWatch] Watcher TikTok live đã khởi động trên NAS.")
    
    # Thread chong deadlock
    threading.Thread(target=_deadlock_watchdog, daemon=True, name="DeadlockWatchdog").start()
    log.info("[DeadlockWatchdog] Trình giám sát Deadlock tự động đã khởi động.")'''
    
    content = content.replace('threading.Thread(target=_tiktok_live_watchdog, daemon=True, name="TikTokLiveWatchdog").start()\n    log.info("[TikTokWatch] Watcher TikTok live đã khởi động trên NAS.")', start_thread)
    
    with open(r'nas_api_server.py', 'w', encoding='utf-8', newline='') as f:
        f.write(content)
    print('SUCCESS_DEADLOCK')
else:
    print('DEADLOCK_ALREADY_EXISTS')