# Prefer the last verified configured LAN route

Measured on SM-G998U1 Wi-Fi6: saved candidate 1 to the NAS times out in about
2046 ms; saved candidate 2 to the same host/port succeeds in154 ms. Every cache
invalidation repeats the failed candidate, yielding2296-2423 ms connections.

Reprobe the last successful configured route first within its route class.
LAN always precedes Tailscale, including when Tailscale was last successful.
Never add an unsaved preferred URL or treat a preferred route as verified without
probing. Preserve fallback and cancellation. Verify with unit tests and repeat
the actual-device cold-cache probe before claiming improvement.

Persist only the successful route hint in existing encrypted preferences so
process restarts do not repeat a known failing alias. Do not rewrite credentials
or the configured URL list. Both login and background selection use the hint;
selection still filters to configured candidates and probes authentication.
