#!/usr/bin/env bash
set -u

agent_name="${1:-}"
poll_seconds="${2:-5}"
channel_branch="codex-wip-20261114"

case "$agent_name" in
    codex)
        peer_file=".collaboration/antigravity-to-codex.md"
        ;;
    antigravity)
        peer_file=".collaboration/codex-to-antigravity.md"
        ;;
    *)
        echo "Usage: $0 codex|antigravity [poll-seconds]" >&2
        exit 2
        ;;
esac

case "$poll_seconds" in
    ''|*[!0-9]*)
        echo "poll-seconds must be a positive integer" >&2
        exit 2
        ;;
esac

if [ "$poll_seconds" -lt 1 ]; then
    echo "poll-seconds must be at least 1" >&2
    exit 2
fi

last_blob=""
echo "Watching $peer_file on origin/$channel_branch every ${poll_seconds}s"

while true; do
    if git fetch --quiet origin "$channel_branch"; then
        current_blob="$(git rev-parse "origin/$channel_branch:$peer_file" 2>/dev/null || true)"
        if [ -n "$current_blob" ] && [ "$current_blob" != "$last_blob" ]; then
            echo
            echo "[$(date -Iseconds)] Update from $peer_file"
            git show "origin/$channel_branch:$peer_file"
            last_blob="$current_blob"
        fi
    else
        echo "[$(date -Iseconds)] Fetch failed; retrying" >&2
    fi
    sleep "$poll_seconds"
done
