# Claude Code cross-check, 2026-11-14

Evidence read locally from ~/.claude/projects/C--Users-Truong/*.jsonl.
No source or logs were sent outside the user's machines.

- Session b356c9c1-abbd-4efd-b6ab-53f0e87d6240, 09:49:07Z: Write replaced
  backend/nas_api_server.py with a 206-byte file ending print("placeholder").
  Confirmed local bytes. Later restore_backend.py attempts tried Git HEAD;
  this is not the reviewed pending NAS candidate. Do not deploy this file.
- Session a2f472b5-41c5-4ce4-a92e-5b46d29b5c41, 08:22:30Z: Edit changed
  VerifiedDuplicateHash.kt /api/duplicates/ to /api/hash/. The candidate NAS
  coordinator and contract tests use /api/duplicates/. Corrected the client.
- Same session, 08:24:20Z: custom encodeNonAscii encoded UTF-16 character
  numbers rather than UTF-8 bytes. Removed; java.net.URI preserves Unicode
  paths directly. Added a Vietnamese and supplementary-character regression.
- Fixed compile errors in AutoDuplicateScanWorker (undefined rootUrl) and
  InsightsDialogs (nullable URI fallback returned from String function).
  Attribution of those two errors is not asserted from the compiler alone.

Added deployment preflight rejecting incomplete/placeholder source before
any SCP or restart; regression asserts no remote command occurs.
Backend restoration requires the reviewed NAS source; SSH is currently timing
out. No service restart, real duplicate cleanup or deployment performed.

Recheck during this turn: local backend later became 756590 bytes and exactly
matches Git HEAD, SHA256 79bae173468751b5b66e56f71df7169a6911f238bc23bfc13cef9c6b7a9cd9f2.
It is no longer a placeholder, but lacks the reviewed native duplicate/content
protocol and incremental resolver. The deployment guard now rejects this stale
contract too. Full pytest was rerun after noticing the restored source.
