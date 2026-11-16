# Duplicate inventory from the persistent NAS catalogue

Both manual and automatic duplicate scans must enumerate the same public-root
SQLite file_index used by search/storage statistics. Retire the separate media-
only os.walk, 24-hour JSON cache, 100000-entry limit and unreported truncation.
Stream a consistent SQLite read snapshot in bounded batches, including ordinary
non-media files. Preserve the existing /api/disk/fast_index route and row fields.
Publish a versioned completeness contract with total and generation; never mark
an unfinished or interrupted stream complete. A busy/unbuilt catalogue must
cause a bounded retry before any automatic cleanup, not a second filesystem walk.

The Android workers must retain the /webdav/ URL root, encode raw path segments
once, validate that each URL remains inside the captured NAS origin/root, and
scope stored metadata to the captured account. Read and honor protocol/readiness/
truncation fields; EOF and partial metadata are never success. Keep native SHA256
and version-bound cleanup as the only deletion proof. Test synthetic or owned
UUID fixtures only; do not start wholesale deletion of user files while validating.

Do not replace the live database or remove validated hash caches. Properties,
search, backup and duplicate candidate metadata reuse the persistent NAS index.
The phone Room cache remains a client mirror rather than the source of truth.

Both Android duplicate workers must request and claim the NAS maintenance lease
before inventory/hash work. NAS busy/stale/unsupported responses retry before
reading inventory. Preserve the returned owner/token, checkpoint bounded batches
and heartbeat every 30 seconds, and stop further proof/cleanup work if ownership
is lost. Release as retry on interruption; complete only after complete inventory
with the claimed catalogue revision and all candidate work has finished. Native
version-bound content proof remains required for deletion. An expired lease never
authorizes cleanup. Verify busy/lost-lease behavior with synthetic worker tests.
