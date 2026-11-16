# Kaspersky detection investigation — 2026-11-14

User screenshot identifies `HEUR:Trojan-Spy.Python.Stealer.gen`, detection at
07:15:14 and backup/deletion at 07:21:52 for local backend/nas_api_server.py.
Do not describe this as a confirmed false positive or generic file lock.

## Verified source revisions

- Deployed NAS source: `d5719f5d20aa2a1294a8d3bd4422c891627a159feed0acb84e451fdc5424f71d`.
- Incremental-index candidate: `f79b6ab6a379969a8b76c9d8f1e2ad2cf7098baadf8e3cfde55c87938ff0ca92`.
- Cookie-hardening candidate: `95cdac688cd82e89190d16f810f076a2936c12f2e4c651a51944d61d86c254ee`,
  retained separately on NAS at `/opt/nas_api_server.py.security-candidate-20261114`.
- Screenshot does not include the deleted object's hash, so exact equality
  with the deleted bytes is not independently established from the AV event.

## Findings

- Read-only comparison: index candidate adds only indexing/search/root-scope
  behavior; no new credential reads, browser-profile access or outbound traffic.
- Reviewed source has user-supplied Netscape cookies/session parsing, TikTok
  account checks and credential-bearing NAS config backup. These are possible
  heuristic inputs, not a proven explanation of the detection.
- No Windows DPAPI, browser Login Data/Local State profile harvesting, or
  keylogger implementation found in the inspected source/history.
- Concrete unsafe behavior: direct ffprobe helper manually concatenates every
  cookie without domain/path filtering. Generated ffmpeg recorder also forwards
  ttwid as a raw Cookie header to media URLs. Removed both in the separate
  hardening candidate; kept UA/Referer and domain-scoped cookie-jar authentication.
- Historical HEAD accepts an arbitrary initial URL; the current NAS source
  additionally validates initial content URLs. Do not report the HEAD exploit
  as a proven exploit of the current deployed API. Extracted media URL validation
  remains a separate boundary review item.
- Actual NAS Python 3.5.3: candidate syntax, generated recorder syntax,
  mocked ffprobe commands, and no raw media Cookie header checks passed.
- Independent read-only review confirmed exactly two cookie-hardening hunks.

## Actual Kaspersky result

Ran installed Kaspersky 21.27 `avp.com SCAN <candidate> /i0 /fa /RA:<report>`.
Report: `.specify/security-review/kaspersky-candidate-scan.txt`.
AV bases release reported `2026-11-13 22:23:00 (full)`.
At 07:34:31: processed 1, detected 1, Total OK 0,
`suspicion HEUR:Trojan-Spy.Python.Stealer.gen`.
The CLI warned that action parameters can be ignored in non-interactive mode;
the saved report lists report-only, no disinfection/deletion requested.
The candidate was never executed on Windows.

The warning remains after the concrete cookie fix. Exact heuristic cause and
false-positive classification require further evidence/vendor assessment.
No security settings were changed; no file/source was uploaded to third parties;
neither candidate was deployed. The source file in backend/ remains missing.

## User decision

User explicitly declined sending source to Kaspersky: keep source internal.
No external source upload or vendor submission is authorized. Continue internal
source review and isolated NAS checks; preserve Windows protection settings.
User also chose to retain active recordings and not deploy V3. No API restart
or source/database replacement is authorized at this point.

## Remaining

## 2026-11-15 current verification

Latest exact-source scan after deployment/performance fixes:
SHA256 f80f5beae1ad0c44be3a794eecefd4138b882558f145e625f05e7541f3460319,
processed 1, detected 1, OK 0, same HEUR:Trojan-Spy.Python.Stealer.gen verdict.
Report: security-review/kaspersky-deployed-f80-20261115.txt. CLI exit code 0
does not mean a clean verdict. The user subsequently directed proceeding after
the warning was disclosed; live backend now has this hash. No AV-clean claim.

- NAS current candidate before the new security fix was
  `392fbf4923ff93688e027f8420f124e846213928be4f704bdaa86b64b68175bd`.
- Confirmed unsafe configuration-backup permissions with 3 red tests on NAS:
  existing directory stayed 0755, archive symlinks were accepted, no exclusive
  private archive helper existed. These archives include credentials/cookies.
- Fixed the candidate using `.specify/patches/private-config-backups.py` and
  `scripts/prepare_private_backup_hardening.py`: owner-only 0700 directory,
  0600 archives from creation, tighten existing owned regular archives, reject
  symlinks/hardlinks/foreign owners, exclusive no-follow creation and fsync.
- All 3 direct NAS Python 3.5 tests passed. Independent source review confirmed
  the fix; this is a real hardening issue, not a proven cause of the AV label.
- New source SHA256:
  `70c851db90691faa3571675dd460b2687015af5008c666cea75e8d2d31b49aa5`.
  Same original PC path was used for a single exact-source verification scan.
- Actual installed Kaspersky bases `2026-11-14 20:24:00`, exact-source scan:
  processed 1, detected 1, OK 0, `HEUR:Trojan-Spy.Python.Stealer.gen`.
  See `security-review/kaspersky-fixed-backend-20261115.txt`. An earlier scan
  processed 0 because the source was missing; it is not evidence of cleanliness.
- Exported report history contains detections against older backend/worktree
  copies from 2026-10-22. Their hashes were not supplied, so historical equality
  with current source and the exact heuristic trigger remain unestablished.
- Inspected current flows: supplied Netscape cookie jars; localhost qBittorrent
  SID; explicit configured rclone backup export. No confirmed browser-profile,
  DPAPI or password harvesting found in these inspected paths. This limited
  review does not certify absence of malware or establish a false positive.
- Live NAS source remains unchanged at
  `d5719f5d20aa2a1294a8d3bd4422c891627a159feed0acb84e451fdc5424f71d`.
  No restart, actual user duplicate deletion, AV exclusion, protection change,
  source obfuscation or external upload occurred.

- Do not send proprietary backend source outside the user's machines.
- Verify Kaspersky verdict on the exact source revision before declaring the
  detection resolved. Never rename/encode/exclude the file to suppress detection.
- Security changes and directory-stamp performance/metadata fixes are integrated
  in internal NAS V3 candidate `a8bcf63a146709fb854a9fba8f416eee89496ad42c6e1fc2f9c05108e2c642d6`.
  Linux regression fixtures pass; full required pytest remains blocked by the
  missing local source. No V3 clean AV verdict or full-suite pass is claimed.
- Actual livestream verification and deployment still require a safe restart window.
