# Persistent incremental file index and LAN search

## Problem

The NAS already has a SQLite index (534,000 rows measured), but every cycle
walks the entire tree and rewrites unchanged entries. Deleted paths remain.
Searching one folder currently scans the whole table; empty search replies
also trigger up to 400 WebDAV directory requests in Android.

## Required behavior

1. Keep file names, relative paths and directory scan state across restarts.
2. On the first pass, discover the existing tree. Later passes check known
   directories for changes and enumerate only new or changed directories.
3. Detect files added inside an already indexed directory, including nested
   directories whose parent has not changed. Never search only unknown paths.
   Interactive searches validate a warm index incrementally after a short
   freshness window (5 seconds); do not wait 30 minutes to discover new entries.
   First-build work stays in the background and reports incomplete coverage.
4. Remove records for deleted/renamed entries after successful directory reads.
   A permission/I/O failure must preserve previous records and mark coverage
   incomplete so an empty result is not presented as authoritative.
5. Preserve searches containing substrings, Vietnamese accents, spaces and
   literal plus/%/_ characters. Scope results to the actual selected folder.
6. Expose index readiness/completion in search responses. Android may finish
   immediately on an empty healthy completed-index result; older/unready servers
   keep the existing fallback. Cancelling a search cancels its network work.
7. Prefer an authenticated working LAN without waiting for Tailscale probes.
8. Keep NAS Python 3.5.3, Flask 1.1.4 and the existing database/endpoint paths.
9. Search covers the visible browser tree. Hidden metadata/recycle entries
   (dot-prefixed names, `@eaDir`, `#recycle`) retain the existing browser/index
   exclusion. Do not traverse filesystem roots or directory symlink cycles;
   unsafe public aliases excluded from that visible tree mark coverage incomplete.
10. In-place file growth must not leave search size/mtime permanently stale.
    Refresh metadata for the bounded result set without enumerating unchanged
    directories; unavailable or unsafe results report incomplete coverage.

## Verification

- Production-function tests with a temporary real filesystem/SQLite database:
  unchanged second pass, file added in existing nested folder, new directory,
  rename/delete, restart reuse, failed directory read and root change.
- Search tests for exact scope, substring/diacritic behavior and readiness.
- Android request tests: authoritative empty avoids BFS, old server fallback,
  encoded folder names and cancelled response cannot replace current results.
- Route tests: healthy LAN skips slow Tailscale; fallback still works.
- Compare scoped SQL results/checksums and measured before/after timings on NAS.
- Full backend suite, actual NAS Python compile, Android tests/build and device QA.

## Measured baseline (NAS, before changes)

- Original global search: cold 6,184 ms; warm 645–925 ms.
- Scoped original SQL: Livestream 652 ms; Downloads 619–626 ms.
- Read-only range-query experiment: Livestream 54–86 ms; Downloads 0.23–0.83 ms.
- The range query used the existing path index; result checksums matched.

## Out of scope

Changing substring search into prefix-only search; replacing SQLite; indexing
private NAS system directories; claiming Wi-Fi throughput without a transfer test.
