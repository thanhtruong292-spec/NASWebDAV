# Shared duplicate catalogue validation

- [x] Manual and automatic duplicate inventory use the persistent NAS file_index.
- [x] Remove media-only walk, legacy JSON cache reads and silent 100000-file cap.
- [x] Require complete versioned stream before receipt, pruning or automatic cleanup.
- [x] Preserve native version-bound SHA256 deletion proof and account/root isolation.
- [x] Room 21 to 22 adds completion receipts without replacing metadata or proof caches.
- [x] Full Python: 215 passed, 6 skipped (`shared-catalogue-full-tests.txt`).
- [ ] Integrated coordinator rerun: 207 passed, 9 failed, 6 skipped (`shared-catalogue-full-tests-final.txt`); all nine fail in incremental-index fixtures missing newly added `_run_file_index_job`. Integration owner notified; previous green run does not certify the new coordinator snapshot.
- [x] NAS Python 3.5 fixture: 4 passed, including 100001 entries (`shared-catalogue-nas-tests.txt`).
- [x] Initial Android parser/migration tests and both APK builds succeeded (`shared-catalogue-android-build.txt`).
- [x] Final focused Android tests: 11 passed, including actual automatic worker success and incomplete-stream retry/no cleanup (`shared-catalogue-worker-final-v3-tests.txt`). Test-only rerun reuses main classes/APKs built by `shared-catalogue-final-build-v3.txt`; that earlier full run built both APKs but its then-unfixed worker fixtures failed. The last rerun is not a new full APK build.
- First final build produced both APKs but 2 new worker tests failed during TLS test initialization; 9 parser/migration tests passed. Fixture now initializes clients before mocking Calendar. Full rerun is `shared-catalogue-final-build-v3.txt`; do not describe the failed run as green.
- Three independent read-only reviews (standards/spec/security) found no confirmed blocker in this catalogue change. They are not live acceptance tests.
- [x] Revision-bearing stream fixture passes on PC and NAS Python3.5 (4 tests each); source packaging guard passes 4 tests including missing-module refusal.
- [ ] Deploy integrated candidate only after coordinating backend changes; NAS currently has two ffmpeg processes.
- [ ] Install/update and run owned-fixture device validation; ADB currently returns no device.

No user duplicate files were deleted during validation. Current live backend remains f80f5beae1ad0c44be3a794eecefd4138b882558f145e625f05e7541f3460319. Existing Kaspersky detection remains unresolved; these test results are not antivirus clearance.

## NAS lease integration

- [x] Both Android duplicate workers request/claim a NAS maintenance lease, heartbeat every 30 seconds, checkpoint batches and release on retry/cancellation.
- [x] Separate claimed catalogue_revision from per-job revision; compare only catalogue revisions.
- [x] Check ownership around both cleanup proof lookups and checkpoint immediately before cleanup RPC; send work_lease owner/token for server enforcement.
- [x] Full Android suite: 141 tests, zero failures/errors/skips. Both debug APKs built successfully (`shared-catalogue-lease-final-full-build.txt`, 11m29s, single Gradle worker).
- [x] New tests cover busy NAS before inventory, lost checkpoint before receipt, unequal job/catalogue revisions, lost permission after proofs preventing cleanup, and accepted cleanup carrying token.
- [x] Backend integrated suite independently rerun after recovery: 233 passed, 6 skipped in 55.13s (`shared-catalogue-frozen-full-tests.txt`). Required audit regression file passed.
- [x] Exact snapshot server SHA256 `5d55b7eada0d20765e5304cbb223f6e4bb8e5d572f88fabe8263a065e13511f0`, module SHA256 `17ca667c311e9b307674ea13b321a430815739e460a40938b32d25452f34b1b1`: Python3.5.3 compilation plus 8 coordinator and 3 stream tests passed on NAS (`shared-catalogue-frozen-nas-validation.txt`).
- [x] Android lint succeeded in 11m56s (`shared-catalogue-lease-lint.txt`); report retains existing warnings, so this is not a zero-warning claim.
- [x] ADB device returned; installed both APKs with `install -r` successfully, preserving app data.
- [ ] Device live validation: source-digest probe passed, live-backup probe failed before fixture creation (`Authenticated NAS route unavailable`). Phone keyguard is showing; NAS tail IP currently routes through wlan0/192.168.0.1 and ping fails. Awaiting unlock to inspect app/Tailscale. Log `shared-catalogue-phone-backup-tests.txt`; do not claim both probes passed.

The first lease build lost its Gradle daemon; the subsequent single-worker full build is the successful result above. Backend source disappeared during concurrent work and was recovered from the verified NAS base plus exact session patches. No cause of file removal has been established by this validation.

Recovery completed and the snapshot above is preserved at `/opt/naswebdav-validation-20261115/backend/`. It is a validation bundle, not the live service. NAS still runs the prior f80 snapshot; one recording was active at the last check. Current app APK SHA256 is `5d92445c166201aec36828e46efcc95be0772bcf22af7f3e52b9cc28c6d57425` (version `1.0.130 (d788c73 · 261115.1234)`). No production restart, user duplicate deletion or device install was performed in this validation.

Lint has zero errors, 414 warnings and 31 informational items; most warnings concern logging style, dependency versions and unused resources. This task did not update dependencies or mass-edit unrelated UI/logging code. The lease review found and fixed the separate job/catalogue revision mix-up and the gap between final file proofs and cleanup permission validation. The final client security trace was rechecked read-only; full test results above supply execution evidence.

## Final backend snapshot

The final resource-gate fixes supersede the intermediate 5d55 snapshot above. Server SHA256 is `c9f15bd2427711f19402b5a58677d31adc46f5623efd2c5996d5601392dd10a9`; coordinator SHA256 is unchanged. Full suite independently rerun: **236 passed, 6 skipped in 49.60s** (`shared-catalogue-final-full-tests.txt`). Exact snapshot preserved at `/opt/naswebdav-validation-20261115/c9f15bd2/backend/`: Python3.5.3 compilation and **14 focused checks passed** (`shared-catalogue-final-nas-validation.txt`), including demand refresh, deferred-readiness and mid-pass resource pause. This remains staged validation, not deployment.
