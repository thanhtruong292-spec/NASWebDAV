# Unified NAS file data and background work — implementation tasks

## 0. Coordination and baseline
- [ ] Keep duplicate catalogue/fast-index changes owned by the parallel agent
      until they report completion and freeze the API/schema contract.
- [ ] Record its final API routes, DB columns, source hash, tests and deployment
      boundary here before integration; do not edit overlapping backend/worker
      files concurrently.
- [ ] Map every file-related screen, worker, endpoint, DB table/cache and
      mutation caller against `unified-nas-file-work.md`.
- [ ] Use one fail-closed configured public-root resolver for index, trash,
      thumbnail, duplicate and summary paths; remove guessed partition fallback
      from file-catalogue operations.

## 1. NAS workload coordinator
- [ ] Reuse `/var/lib/nas_fast_index.db`; add only compact durable job,
      checkpoint and single maintenance-lease state needed by the contract.
- [ ] Add authenticated status/request/claim/heartbeat/result endpoints with
      bounded batches, account/root binding, root epoch + catalogue revision,
      lease expiry and idempotent writes.
- [ ] Run existing NAS resource and activity gates before each batch; serialize
      index/thumbnail/fingerprint/duplicate maintenance and yield promptly.
- [ ] Reconcile `process_state.json` and `runtime_state` progress without
      dropping existing state; make one durable source authoritative.
- [ ] Add Python 3.5-compatible tests for lease races, restart recovery,
      resource gates, stale account/root, idempotent results and partial batches.

## 2. Thumbnail lifecycle
- [ ] Replace independent full-tree discovery with bounded work derived from
      the shared file index; keep filesystem traversal incremental where index
      readiness requires it.
- [ ] Persist request, cursor, source version, result, retries and last-progress
      timestamp; resume from checkpoint after NAS restart.
- [ ] Preserve thumbnail files and compatibility `/api/thumb/status` and
      `/api/thumb/control` response behavior while reading/writing coordinator
      state.
- [ ] Add tests for missing/stale thumbnail, pause/resume, gate closure,
      restart, failed media, version change and no duplicate generation.

## 3. Shared derived metadata and client APIs
- [ ] After duplicate-agent contract freezes, route image fingerprint reads and
      writes through version-checked NAS APIs and shared catalogue metadata.
- [ ] Verify all duplicate-scan failures retry from the shared catalogue; no
      WebDAV BFS fallback may start a second full NAS enumeration.
- [ ] Treat Room catalogue batches as staging/cache until EOF, total, protocol,
      root epoch, catalogue revision and root checks pass; publish the completed
      local mirror receipt only after the final check.
- [ ] Keep Room fingerprints/hash rows as scoped rebuildable cache; invalidate
      by NAS root epoch/catalogue revision/file version and never claim server
      completion from a local-only write.
- [ ] Route browser, statistics, duplicate, backup and thumbnail progress UI
      through shared server readiness/root-epoch/revision/job state; preserve
      offline cache labeling and legacy-server fallback.
- [ ] Audit upload, move, rename, delete, trash, restore, USB and cleanup paths;
      reconcile/invalidate file_index, summary, fingerprints, hashes and
      thumbnail status in the common mutation boundary. Trace Android callers
      through WebDavManager, FileBrowserViewModel, BatchOperationWorker,
      OfflineSyncWorker, NasDocumentProvider and AutoBackupWorker, plus NAS
      WebDAV/PHP, USB import and duplicate cleanup entrypoints.
- [ ] Replace the legacy fast-index JSON cache deletion in trash with direct
      shared-catalogue reconciliation and the canonical revision update.
- [ ] Add Android API/Room tests for cache invalidation, account switches,
      stale results, partial responses and server/client revision mismatch.

## 4. Computer-assisted worker
- [ ] Add an opt-in bounded desktop worker that authenticates, claims one
      leased item/batch, reads assigned files only, and submits version-bound
      results through NAS APIs.
- [ ] Bound concurrency, memory, bytes in flight and retry rate; stop promptly
      when the NAS gate closes or lease expires.
- [ ] Verify helper disconnect/reconnect, malformed result, stale version,
      path traversal, root change and duplicate claim using disposable fixtures.

## 5. Converge and acceptance
- [ ] Remove/disable obsolete independent walks and authoritative client-only
      paths only after all callers have migrated and regression tests pass.
- [ ] Run full backend suite (`python -m pytest tests/ -v`), Android unit tests,
      assemble and lint; preserve unrelated WIP and report skips/failures.
- [ ] Verify NAS Python 3.5.3 syntax and inspect measured CPU/RAM/disk/scan
      behavior on safe fixtures; do not deploy/restart NAS in this task.
- [ ] Compare each completion criterion in `.specify/specs/unified-nas-file-work.md`
      and document anything still blocked by the parallel agent's contract.
