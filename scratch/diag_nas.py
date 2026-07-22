import subprocess

def run_remote(cmd):
    ssh_cmd = ['ssh', '-o', 'HostKeyAlgorithms=+ssh-rsa', '-o', 'PubkeyAcceptedKeyTypes=+ssh-rsa', 'root@192.168.100.254', cmd]
    res = subprocess.run(ssh_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return res.stdout.decode('utf-8', errors='ignore')

print('=== 1. HDD TEMPERATURE & SMART STATS ===')
print(run_remote('smartctl -A /dev/sda | grep -i -E "temperature|airflow|load_cycle|power_on"'))

print('=== 2. FAN STATUS ===')
print('Fan duty cycle:', run_remote('cat /sys/class/pwm/pwmchip0/pwm0/duty_cycle 2>/dev/null'))
print('API status:', run_remote('curl -s http://127.0.0.1:5050/api/status'))

print('=== 3. DISK I/O BY PROCESS (pidstat) ===')
print(run_remote('pidstat -d 1 2'))

print('=== 4. OPEN FILES ON MOUNTED DISKS ===')
print(run_remote('lsof /srv/dev-disk-by-* 2>/dev/null | head -n 30'))

print('=== 5. TOP CPU & MEMORY PROCESSES ===')
print(run_remote('ps aux --sort=-%cpu | head -n 15'))
