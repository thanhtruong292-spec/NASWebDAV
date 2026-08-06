import subprocess
cmd = [
    "ssh", "root@192.168.100.254",
    "sed -i 's/^account     required        pam_tally2.so/account [success=1 default=ignore] pam_succeed_if.so user = daica\\naccount     required        pam_tally2.so/' /etc/pam.d/openmediavault"
]
result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
print(result.stdout.decode())
print(result.stderr.decode())
