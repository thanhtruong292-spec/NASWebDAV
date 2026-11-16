# Backup duplicate prevention and truthful overwrite

Auto backup must match complete SHA256, not perceptual similarity or source ID.
Reuse persistent source/native-version and remote/native-version proofs; scope
remote candidates to the current account and WebDAV origin/root. Check exact
content and the stable destination before any upload. Distinguish MATCH,
DIFFERENT and UNVERIFIABLE: missing ETag/coordinator proof must never cause a
new version. Keep the existing preserve-old-content/versioning policy; auto
backup has no overwrite setting. Persist the digest actually verified, not a
second unconstrained source read. Retain local source when version-bound atomic
deletion is unavailable; never newly enable unsafe deletion after fixing proof.

USB copy_mode=overwrite must replace existing destination content. Stage into a
same-directory private temporary file and atomically commit only on complete,
uncancelled copy of a stable source. Failure preserves the original destination.
Reject same-inode aliases. New-only/rename must not overwrite concurrent files.
Bind resume checkpoint to the same destination, device and copy mode. Coordinate
the final namespace commit with duplicate cleanup. Test disposable files only;
existing NAS deployment/real-cleanup hold remains in effect.

Auto backup stages and verifies through the same authenticated WebDAV namespace;
remove the inherited opportunistic SMB path with an unverified share/root mapping.
