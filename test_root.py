import sys
sys.path.append('/opt')
try:
    import nas_api_server
    print("ROOT:", nas_api_server.get_webdav_root())
except Exception as e:
    print("Error:", e)
