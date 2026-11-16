# LAN upload buffering experiment

The phone's 64 MiB WebDAV upload measured9.4-16.6 MiB/s; NAS raw HDD read195 MB/s.
Nginx request/response temp paths are on eMMC. Test request buffering as one
variable on an isolated Nginx instance, same PHP authentication/controller,
port18822 restricted to the current phone IPv4. Keep production configuration
and processes untouched. Use only generated benchmark files with SHA256 checks.
Stop the temporary listener after testing. Do not claim improvement or ship a
configuration change without the actual-device A/B result.
