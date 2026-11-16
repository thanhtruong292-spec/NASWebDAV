# Backup integrity validation

- [x] Identify repeated-upload failure when WebDAV has no ETag.
- [x] Check complete SHA256 across media IDs and names before uploading.
- [x] Persist local/native-version proofs and share NAS catalogue with the file index.
- [x] Block new versions when destination proof is unavailable.
- [x] Keep local sources; do not enable unsafe delete-after-backup.
- [x] Remove opportunistic SMB backup with unverified share/root mapping.
- [x] Stage USB copies, reject same inode, honor overwrite/new-only and bind resume.
- [x] Sync staging and parent directory before reporting USB commit success.
- [x] Latest Python suite: 206 passed, 6 Windows/POSIX skips; NAS POSIX fixtures were run separately.
- [x] NAS Python 3.5: 7 USB tests, 8 coordinator/catalogue tests passed on disposable fixtures.
- [x] Final Android: 126 unit tests passed; APK/test APK build and lint succeeded (.specify/backup-integrity-final-build-v3.txt).
- [x] Installed APK/test APK via ADB with -r, preserved app data; native source-digest instrumentation passed on SM-G998U1. Room 21 opened successfully through the real upgrade.
- [x] Tailscale phone runtime: supplied login retained, fresh NAS metrics/file listing loaded; cold app reopen and a 20-second background/foreground cycle returned to Online/Tailscale. This does not prove long-duration background recovery or WiFi6 LAN throughput.
- [x] Deployed backend/PHP guard/boot manifest together after the user directed proceeding; authenticated health and live fixture checks passed. Current live SHA256 f80f5beae1ad0c44be3a794eecefd4138b882558f145e625f05e7541f3460319.
- [x] Final APK 1.0.130 installed via ADB with data preserved; both native-source and live-NAS backup fixture instrumentation tests passed (11.082s).
- [x] Latest Android unit suite: 129 passed, zero skips/failures; final lint result pending in live-backup-phone-final-build-20261115.txt.
- [ ] Actual duplicate cleanup: not executed on user data.

Live backend remains d5719f5d20aa2a1294a8d3bd4422c891627a159feed0acb84e451fdc5424f71d.
Prepared NAS candidate is /opt/nas_api_server.py.backup-integrity-candidate-20261115,
Original backup-integrity SHA256 was 392fbf4923ff93688e027f8420f124e846213928be4f704bdaa86b64b68175bd.
The later private-config-backup hardening changes the candidate SHA256 to
70c851db90691faa3571675dd460b2687015af5008c666cea75e8d2d31b49aa5.
Kaspersky still flags the exact source; see tasks/kaspersky-investigation.md.
User initially chose to wait for AV verification, then directed proceeding; deployment completed with the warning explicitly disclosed.
Reviewed backend/PHP guard/manifest and rollback copies are staged at
/opt/naswebdav-release-20261115. Live service was not restarted.
Never replace the live database with a validation database. Preserve a rollback
copy of the live backend, WebDAV controller/guard and manifest before deployment.

Limit: sequential WebDAV backup is covered; concurrent clients can still race
between content lookup and publication. No atomic global digest reservation is
implemented, so perpetual global uniqueness is not claimed.
