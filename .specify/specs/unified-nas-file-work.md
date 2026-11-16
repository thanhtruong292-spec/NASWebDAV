# Unified NAS file data and background work

## Problem

NASWebDAV's file features do not share one complete view of file metadata or
background progress. Search and storage statistics use the persistent NAS
`file_index`, while duplicate scans still have a separate enumeration path,
image fingerprints are stored in Android Room, thumbnail scans walk NAS roots
again, and thumbnail progress/request state is volatile. The indexer and
thumbnail generator check NAS load independently, so both can perform heavy
work at the same time. Android `requiresDeviceIdle` only describes the phone.

This makes progress hard to trust, repeats storage work, and spends CPU, RAM
and disk activity that the RK3328 NAS needs to serve active users.

## Goal

Make the NAS the single authority for file identity, metadata, derived-result
versions and resumable progress for file-related features. Use the existing
NAS SQLite catalogue and authenticated APIs. Android Room and any desktop
worker may cache data, but cannot own a competing inventory or report a job
complete when the NAS has not committed its result.

## User-visible behavior

The NAS service and maintenance workers use one durable SQLite database at
`/var/lib/nas_api/nas_index.db` for system history, authentication, inventory,
derived metadata and job leases. Upgrade the existing catalogue transactionally
before workers start. Preserve prior rows and revisions, retain the old file
for recovery, and never import it again after the migration commits. An
unrecognized or conflicting schema must stop the upgrade without replacing
system data. Android and desktop access shared records through authenticated
APIs. Files, thumbnails and recorded media remain ordinary storage objects.

### Integration gaps to verify before calling the app unified

- Android fingerprint writes still target Room. Recent media still depends on
  locally browsed folders. Directory refresh can replace derived metadata.
  Confirm identical results from an empty phone cache and from a second device.
- Folder-based photo tagging and Smart Organize still enumerate the filesystem.
  Preview and execution need a shared candidate list with source-version checks
  before moves. Compare counts and paths before and after undo.
- Thumbnail lookup and upload still identify artifacts primarily by path.
  Overwrite the source at the same path during a PC job and verify that stale
  previews cannot be published. Verify that NAS-unsupported media remains
  eligible for a capable PC.
- Verify changes from WebDAV and SMB, including in-place edits, with no Android
  search to repair metadata. Exercise USB import, recording completion, rename,
  trash and restore through their normal entry points.
- The standalone PC hash manager and legacy hash API use a separate discovery
  flow and path conventions. Verify that one versioned hash is reusable by
  both the backup and duplicate workflows.
- Compare durable job status with legacy process-state JSON and per-feature
  progress. Check that switching devices resumes the same job and that an
  incomplete run is visible as incomplete.

1. Search, file totals, type totals, duplicate candidates, fingerprints and
   thumbnail status refer to the same app-visible NAS namespace and catalogue
   revision. Resolve WebDAV/SMB aliases to one file identity so a file is
   not scanned or counted twice.
2. NAS-side enumeration is incremental and reused. A feature must not start an
   independent whole-tree walk when the catalogue can answer it.
3. At most one NAS-intensive maintenance batch runs at a time. It yields to
   active users, livestream/USB work, high memory/load and other existing NAS
   activity gates. These checks execute on NAS; Android idle constraints only
   govern whether the phone may submit or perform client work.
4. Each job records durable state and a bounded checkpoint in the existing NAS
   catalogue database. Restarting the service resumes incomplete work without
   claiming it completed or starting an unbounded duplicate pass.
5. Thumbnail image files remain file artifacts. Their source file identity,
   version, generation status, retry state and job progress are shared and
   durable. Android thumbnail disk/Room caches are rebuildable mirrors.
6. Fingerprints and verified content hashes are associated with the current
   NAS file version. Stale results are rejected; exact-hash deletion rules
   remain version-bound and fail closed.
7. A connected computer may claim bounded CPU-heavy work through an
   authenticated API, download only the assigned NAS file, and submit a result
   with its captured file version. The NAS validates the version and commits
   the result. The computer never opens NAS SQLite or enumerates a second
   catalogue. If no helper is connected, NAS/phone behavior remains correct.
8. Android screens show server job state, root epoch, catalogue revision and
   completeness. Room
   data is presented as a cache with freshness information, not an independent
   source of truth. A truncated response, interrupted stream or stale account
   cannot be reported as complete.
9. Existing API response shapes and file URLs remain compatible during the
   migration; compatibility routes delegate to the common catalogue/job state.

## Scope

Include NAS file indexing and counts, storage summary, search refresh,
duplicate candidate enumeration and hash metadata, image fingerprints,
thumbnail discovery/generation, related Android workers, browser/cache
reconciliation, and computer-assisted batch work. File mutations must update
or invalidate the shared catalogue and derived results through the common
backend path.

Foreground playback, livestream recording and user-requested transfers are
not serialized as maintenance jobs, but their existing activity signals must
pause or lower the priority of maintenance work. Credentials, device settings,
system telemetry and unrelated app data are not moved into the file catalogue.

## Architecture decisions

- Keep `/var/lib/nas_fast_index.db` and `file_index` as the inventory source of
  truth. Do not replace it, copy it to clients, expose its SQLite file over the
  network or create another persistent file catalogue.
- Keep `index_generation` as the root/build epoch. Add a distinct monotonic
  `catalogue_revision` for file_index mutations, including name/path/type/size/
  mtime/mime/parent changes. Publish root, generation, revision, readiness and
  completed-scan time separately; never use a summary-cache-only revision as
  the catalogue revision.
- Define catalogue scope from paths exposed by the app's configured WebDAV/SMB
  shares. Exclude hidden/system roots and filesystem paths the app cannot
  access; normalize aliases of the same physical file before counts/jobs.
- Persist compact job checkpoints, global maintenance lease and versioned
  derived-result metadata in that same database. Do not queue one row per
  scanned file; enumerate bounded pages from `file_index` and persist cursors.
- One NAS-owned coordinator arbitrates maintenance work. Existing NAS idle,
  resource, livestream and USB gates remain authoritative and are evaluated
  before each bounded batch. A process restart expires/reclaims leases safely.
- NAS enumeration stays local and incremental because repeatedly listing
  hundreds of thousands of remote WebDAV entries would increase NAS disk and
  protocol load. Offload CPU transforms (thumbnail/fingerprint and only
  integrity-approved hash work) to a connected computer through lease/result
  APIs; all committed metadata remains NAS-owned.
- Android WorkManager schedules client activity and network availability; it
  does not claim NAS idleness. All worker callers use the same API and account
  snapshot, and Room stores only cache/mirror data.
- A file that exists only on a phone or computer is not part of the NAS file
  inventory. Its source-side backup digest may stay local; the NAS destination
  identity, verified digest and duplicate result must use the shared NAS
  catalogue and version checks.
- Move thumbnail status and scan request from process memory to the durable
  job state. Keep the existing status/control endpoints as compatible views
  over the coordinator.
- Retire duplicated process-progress persistence only after existing
  `process_state.json` and `runtime_state` data have been reconciled into the
  authoritative job state and compatibility readers are verified.
- Do not delete user files during migration or validation. Existing
  version-bound SHA-256 proof remains the only deletion proof.

## Completion criteria

- One controlled maintenance lease prevents overlapping index, thumbnail,
  fingerprint and duplicate maintenance batches across NAS threads/clients.
- Job status survives service restart and distinguishes queued, running,
  paused-by-NAS-gate, retryable error, failed and complete states.
- NAS load gates are tested independently from Android device-idle constraints.
- All file feature reads identify their NAS root/catalogue revision; stale
  Room data cannot override a newer complete server result.
- A mutation invalidates or reconciles all affected shared rows and derived
  metadata before the app reports the new state as synchronized.
- Desktop worker loss, API timeout, stale source version, wrong account/root,
  partial result and lease expiry cannot commit false completion.
- No unbounded in-memory file lists, duplicate tree walks, speculative
  thumbnail rescan loop or parallel heavy NAS scan is introduced.
- Backend remains compatible with Python 3.5.3 and Flask 1.1.4; all changes
  pass backend tests, Android tests/build, and focused live checks on owned
  fixtures. No production deployment or NAS restart is part of this spec.

## Out of scope

- Replacing SQLite, changing the NAS hardware, or moving NAS DB files onto a
  network share.
- Storing thumbnail binary data inside SQLite.
- Claiming global duplicate uniqueness against unmanaged external filesystem
  writers.
- Making a phone's idle state evidence that the NAS is idle.
- Deploying/restarting the NAS or deleting real user files as part of
  implementation verification.
