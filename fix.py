import json
import os

state_file = '/srv/dev-disk-by-label-data/New folder/.nas_meta/usb_import_state.json'
if os.path.exists(state_file):
    with open(state_file, 'r', encoding='utf-8') as f:
        state = json.load(f)
    if state.get('status') == 'cancelling':
        state['status'] = 'cancelled'
        state['message'] = 'Đã huỷ'
    with open(state_file, 'w', encoding='utf-8') as f:
        json.dump(state, f)
        print("Fixed state")
