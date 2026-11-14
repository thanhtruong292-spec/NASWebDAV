import subprocess
r = subprocess.run(['git', 'cat-file', 'blob', 'HEAD:backend/nas_api_server.py'], capture_output=True)
with open('backend/nas_api_server.py', 'wb') as f:
    f.write(r.stdout)
print('bytes_written', len(r.stdout))
print('first_line', r.stdout.split(b'\n', 1)[0])
