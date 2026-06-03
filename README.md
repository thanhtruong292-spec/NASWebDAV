# NASWebDAV

NASWebDAV is an Android app + NAS automation stack for WebDAV, streaming, backup, and status monitoring.

## Build

- Requires Java 17.
- Build debug APK: `cmd /c .\gradlew.bat assembleDebug`.
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Local Android SDK mirror

This repo uses a workspace-local Android SDK mirror at `.android-sdk/`. The mirror and `local.properties` are ignored so local SDK files never enter git.

- Active SDK path: `sdk.dir=D\:\\Android\\NASWebDAV\\.android-sdk`
- If you already have an Android SDK elsewhere, point `sdk.dir` there instead.

### Reproduce on another machine

1. Install Android SDK Command-line Tools, Platform Tools, one platform, and matching build-tools.
2. Copy `local.properties.example` to `local.properties`.
3. Update `sdk.dir` for your machine.
4. Run `cmd /c .\gradlew.bat assembleDebug` once to verify the toolchain.

## Notes

- Keep `.android-sdk/` local-only. Do not commit it.
- If you move the SDK, update `local.properties` before building again.

## Reverse proxy auth

- If NAS API sits behind nginx or another proxy, configure TRUSTED_PROXY_CIDRS, TRUSTED_PROXY_IPS, or TRUSTED_PROXIES in /etc/nas/auth.conf or environment variables.
- The proxy must forward X-Forwarded-For (and optionally X-Real-IP) so the server can resolve the real client IP.
- Do not whitelist the proxy subnet directly unless it is explicitly marked trusted.
