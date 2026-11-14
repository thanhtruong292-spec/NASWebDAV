# Shared file statistics

Use the persistent file index for file counts, bytes, type totals and largest
files. Never start an independent filesystem walk for the dashboard. Preserve
the response shape and explicit partial/index freshness information. Reuse a
persisted aggregate for the same completed index snapshot across API restarts.
Opening/resuming the Android app must not force a rebuild. Manual refresh may
request incremental index validation. Do not deploy or stop recordings.
