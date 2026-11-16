# Restore visible content after backgrounding

Observed on the connected Samsung device: after Home and a 20-second background
interval, accessibility still contains the file browser but screen captures show
only its connection badge. Changing tabs redraws the content. VPN remains up.

The later automated pixel comparison was contaminated by another app's floating
window and keyboard in its baseline capture. Treat that result as invalid, not a
confirmed rendering regression. A device probe must wait for focused NAS content
and visible navigation before collecting its baseline, and reject overlay input.

Reproduce with a device screenshot regression, distinguish retained navigation
from actual painted content, and test whether a host-view redraw restores it.
Preserve navigation, ViewModels, cached file data and connection credentials.
Do not clear data, recreate the activity or reset the connection to repaint UI.
Any fix must pass the same real-device background/resume check and backup probes.

During route revalidation, retain an already verified LAN/Tailscale badge rather
than replacing it with CheckingLan. Cancellation must restore a terminal prior
state, and a late check must not reconnect or publish success after logout or a
different login. Coalesce foreground refresh jobs and cancel them on leaving the
foreground, preserving the current verified route and browser data.
