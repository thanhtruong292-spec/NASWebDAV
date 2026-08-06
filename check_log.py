import os
p = '/srv/dev-disk-by-label-data/New folder/.nas_meta/livestream_logs'
if os.path.exists(p):
    files = sorted([os.path.join(p, f) for f in os.listdir(p) if f.endswith('.log')], key=os.path.getmtime, reverse=True)
    if files:
        print("Latest:", files[0])
        print(open(files[0]).read())
