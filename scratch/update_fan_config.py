import subprocess

ssh_cmd = ['ssh', '-o', 'HostKeyAlgorithms=+ssh-rsa', '-o', 'PubkeyAcceptedKeyTypes=+ssh-rsa', 'root@192.168.100.254', 'python3 -c "import json; open(\'/opt/fan_custom.json\', \'w\').write(json.dumps({\'mode\': \'custom\', \'on_temp\': 40.0, \'off_temp\': 36.0}))" && systemctl restart nas_api.service']
res = subprocess.run(ssh_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
print('Config update return code:', res.returncode)
