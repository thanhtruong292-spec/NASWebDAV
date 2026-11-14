# Duplicate coordinator validation and deployment hold

Completed in code:
- Shared persistent native-version SHA256 cache on NAS and Room schema 20.
- Leased, version-bound file descriptor stream; phone computes SHA256 once.
- Physical file IDs exclude aliases from verified groups/counts.
- Bounded pages, indexed account/fileId lookup, no repeated full-file hashing.
- Candidate counts exclude trash consistently.
- Cross-process cleanup serialization; read leases reject busy/unsupported files.
- Fsynced recovery mapping precedes rename. Failed revalidation restores original.
- Request auth snapshot/tagging and account-scoped cache deletion.
- Failed hash registration invalidates published verified state.

Local Python focused regression: 10 passed. Includes metadata/hash binding, unsafe
paths, invalid JSON, busy-file rejection before move, crash journal preservation,
lease-break rollback and unstarted empty-response descriptor closure. Kernel lease
operations are mocked in Windows cleanup tests; this is not Linux acceptance.
Python 3.5 grammar parsed successfully. Full pytest first failed collection on
the placeholder. After Claude restored byte-identical Git HEAD, full pytest ran:
47 failed, 136 passed, 13 skipped. That restored backend lacks pending NAS fixes
and is rejected by deployment preflight. No full backend acceptance claim.

NAS runtime harness updated for two-process opposite-keeper cleanup, physical
aliases, stale ctime versions, active writer and lease-break rollback, crash
recovery mapping. Not run: PC-to-NAS SSH times out. No service restart/deployment,
no user file cleanup. Final Android validation: 118 tests, zero failures/errors; assembleDebug passed.
APK installation and actual network measurements not done.

Unresolved acceptance boundary: ordinary WebDAV/other filesystem namespace
DELETE/rename does not take the coordinator lock. A read lease is not namespace
locking. Do not claim absolute last-copy protection against those concurrent
operations, or enable production cleanup before that boundary is integrated and
validated on the NAS. User's keep-recording / no-deployment hold remains active.

Kernel semantics consulted: https://man7.org/linux/man-pages/man2/F_SETLEASE.2const.html
and Linux v4.4 fs/locks.c lease_setup. No project source was uploaded externally.

Final transport check: PC Tailscale is stuck NoState; SSH 100.90.135.102:22
connect timeout. Restart-Service was refused by Windows service permissions
(Cannot open Tailscale service); no service was restarted. Local Claude backup
for backend contains only the 206-byte placeholder, not the reviewed NAS base.
Do not reconstruct the reviewed full source by guessing against old HEAD.
