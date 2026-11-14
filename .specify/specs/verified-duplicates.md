# Verified duplicate detection and persistent metadata

Persist names, logical paths, size and mtime in the shared file index/cache.
Persist complete SHA256 on NAS and Android, bound to physical device/inode,
size, nanosecond mtime and ctime version. Reuse only after version validation.
Size and partial/perceptual hashes are candidate filters, never deletion proof.

The NAS coordinator exposes authenticated /api/duplicates/file, /hash and
/cleanup endpoints. It rejects physical aliases and stale versions. A shared
process lock serializes cleanup across app clients. Kernel read leases exclude
active writers and fail closed when unsupported or breaking; each deletion preserves a
verified survivor. Quarantine, a durable recovery journal and rollback protect failed revalidation.
Hash streams originate from the version-checked leased descriptor, preventing
path/alias swaps from assigning another inode's digest. Only
confirmed deletions remove client metadata; report logical removed bytes rather
than claiming physical reclaimed space. External filesystem writers do not
participate in this lock, so continuous global uniqueness is not guaranteed.

Workers use bounded pages and cached proofs; properties display stored hashes
without recalculating. Include documents, small and empty files. No real cleanup,
service restart or deployment during this code task. Linux concurrency checks
must pass before enabling cleanup on NAS.
