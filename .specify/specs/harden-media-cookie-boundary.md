# Harden media URL cookie boundary

Signed media URLs (TikTok CDN FLV/m3u8, direct recorder output) must never receive
browser cookies or session tokens. Media requests use Referer-only headers plus a
domain-scoped cookie-jar authentication path; raw `Cookie:` header construction is
removed from generated ffmpeg/ffprobe command lines.

The `_direct_flv_has_remuxable_video` helper no longer reads a cookie jar or emits
`Cookie:` headers. `api_livestream_record` generates recorder commands that carry
only `Referer:` (no `Cookie:`) so session credentials cannot leak to media URLs.

User-supplied Netscape cookies remain supported for account-level API calls; they
are never forwarded to third-party media CDNs. No new credential reads, browser
profile access, or outbound traffic is introduced.

Python 3.5.3 / Flask 1.1.4 compatible: no f-strings, no walrus, no type
annotations, no `secrets` module, LF/UTF-8 without BOM.
