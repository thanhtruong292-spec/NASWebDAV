# Coordinated duplicate cleanup and WebDAV mutations

Close the remaining keeper DELETE/MOVE race for managed WebDAV requests and
the API trash batch. Mutations hold a shared Linux flock; duplicate cleanup
holds the same lock exclusively. Normal mutations remain concurrent. Busy
requests fail transiently rather than waiting indefinitely.

Cleanup must fail closed unless the installed PHP entrypoint and guard match
a root-owned deployment manifest. The common lock must live in root-owned
0755 /run/naswebdav, with a root-owned 0644 regular file, not writable /run/lock. Prepare and test candidates only: do not
modify the running WebDAV controller or restart the NAS service without
lifting the existing deployment hold. External root/SSH filesystem changes
remain outside this managed namespace contract.

Validate both directions of PHP/Python locking on disposable NAS fixtures,
retaining the survivor, and rerun Python 3.5 and project regressions.
