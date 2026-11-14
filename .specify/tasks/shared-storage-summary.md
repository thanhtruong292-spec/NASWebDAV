# Shared statistics code changes (not deployed)

- Replaced the candidate's independent os.walk storage scan with indexed SQL
  totals, type breakdown and top10; response shape remains compatible.
- Added a SQLite aggregate snapshot, reusable across process restarts and index
  scan timestamps. Expired snapshots return immediately and coalesce one daemon
  refresh. Index data remains the source; byte totals describe the indexed
  snapshot, not an exact live filesystem quota or continuously growing files.
- MainMenuScreen's three summary calls no longer force invalidation. Dedicated
  explicit refresh controls remain available. This does not integrate unrelated
  content-analysis/duplicate/hash jobs into the filename index.
- Replaced obsolete filesystem-walk storage tests with real SQLite regression
  coverage: counts/types/largest, persistent reuse, partial coverage, updated
  rows, stale snapshot immediate return and one background rebuild.
- Focused pytest passes. Android compileDebugKotlin passes.
- NAS Python3.5 candidate compilation passes. Isolated real-data database:
  454138files,827dirs; full SQL aggregate13.93s vs initial33.32s CASE experiment;
  durable snapshot read5.30ms. These are NAS-side measurements with index-refresh
  callback stubbed for the aggregation probe, not end-to-end app latency.
- Final candidate: /opt/nas_api_server.py.shared-summary-candidate-20261114-v4
  SHA256 c555d0391221a5775a943dd9e4348d8447755b72a0d8c2f99028cac9fc3e05dd.
- Local reproducible change: .specify/patches/shared-storage-summary.py.
- Full backend pytest remains blocked by missing local backend source after
  Kaspersky deletion. No clean AV verdict claimed. No deployment/restart,
  original source restoration or security setting change performed.
- Before activation, seed the aggregate snapshot with the prepared index DB so
  first use does not pay the initial aggregation cost. Preserve user decision
  to keep recordings and withhold deployment.
