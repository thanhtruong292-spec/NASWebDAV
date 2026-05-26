#!/bin/sh
python3 - <<'EOF'
import json
with open('/etc/nas/state/tiktok_live_watch.json') as f:
    d = json.load(f)
users = d.get('users', [])
print("Total users:", len(users))
print("poll_interval:", d.get('poll_interval', 60))
print("exclude_enabled:", d.get('exclude_enabled', False))
for u in users[:5]:
    print("  @%s status=%s last_check=%s job_id=%s" % (u.get('username','?'), u.get('status','?'), u.get('last_check','?'), u.get('job_id','')))
recording = [u for u in users if u.get('status') == 'recording']
print("Recording now:", len(recording))
EOF
