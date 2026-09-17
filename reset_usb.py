import json

f = '/etc/nas/state/usb_import_state.json'
with open(f, 'r') as fp:
    d = json.load(fp)

d['seen_devices'] = []
d['status'] = 'idle'
d['active_id'] = ''

with open(f, 'w') as fp:
    json.dump(d, fp)
