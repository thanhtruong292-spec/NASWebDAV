# Live deployment and current validation scope

Live backend SHA256:
f80f5beae1ad0c44be3a794eecefd4138b882558f145e625f05e7541f3460319.
Release/rollback directory: /opt/naswebdav-release-20261115.

Applied together: native version-bound duplicate coordinator, shared SHA256
catalogue, incremental file_index and durable storage summary, private config
backup permissions, atomic USB overwrite, root-owned PHP namespace guard and
boot lock manifest. Later performance fixes reserve the scan lock and refresh
directories asynchronously; idx_file_search_order avoids sorting all file rows.
API/schema changes include /api/duplicates/{capabilities,file,content,hash,find,
cleanup}, verified_file_hashes.path, and file_index search-order/size indexes.
Existing production SQLite data was migrated in place, never replaced by a
validation database. Fixture-only live cleanup and overwrite checks passed.

Measured on the actual NAS: global SQL search 5.5-15.5ms after index addition;
API search first sample 3219ms, subsequent 174/161ms for 300 results. Storage
summary cached reads around 10-12ms. These are NAS-local measurements, not
phone LAN/WiFi6 throughput. NAS catalogue reached 467750 files/827 dirs and
828 complete directory records. Probe deletion can transiently mark the next
refresh incomplete; the subsequent refresh returns to ready.

Android scope: AutoBackupWorker, BackupContentIntegrity, Room21 native hash
cache/migration, search refresh handling in FileBrowserViewModel, existing
connection URL preference and fixture probes. Compile fixes rename discarded
exception bindings that were referenced by new logging in NasApplication,
NasDocumentProvider, WebDavManager and DeviceManagementViewModel.

This phase has NOT replaced DuplicateScanWorker/AutoDuplicateScanWorker's
/api/disk/fast_index candidate enumeration with the shared catalogue. The
legacy generate_fast_index filesystem walk/media filter/100000 cap/24h JSON
cache remains a separate integration gap. No wholesale cleanup of user files
or claim of global uniqueness was made. Only owned UUID fixtures were deleted.

Kaspersky warning remains unresolved; deployment was not described as AV-clean.
No AV exclusion, protection disabling, obfuscation or external source upload.
