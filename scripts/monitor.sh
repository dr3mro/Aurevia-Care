#!/usr/bin/env bash
# monitor — modern two-column terminal dashboard for the swarm: rounded cards that always fill one
# screen, no scrolling. A new failed run raises an alarm: it beeps every 5 seconds and its rows
# flicker until you press any key.
# Left: in-progress runs, recent runs, failures (24h). Right: merged PRs (24h), open PRs, issues.
# Data is fetched in parallel by a background thread; the screen keeps the last good data
# while a refresh runs. Keys: q quit, r refresh now; any key silences the alarm.
# If no Agent swarm run is queued or running while TODO.md still has open tasks, it starts a wave
# (gh workflow run swarm.yml), at most once per --autostart-wait minutes. --no-autostart turns that off.
# Usage:  bash monitor [--once] [--interval SECONDS] [--recent N] [--autostart-wait MINUTES] [--no-autostart]
set -u

INTERVAL=30
ONCE=0
RECENT=100
AUTOSTART=10
while [[ $# -gt 0 ]]; do
  case "$1" in
    --once) ONCE=1; shift ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --recent) RECENT="$2"; shift 2 ;;
    --autostart-wait) AUTOSTART="$2"; shift 2 ;;
    --no-autostart) AUTOSTART=0; shift ;;
    -h|--help) echo "Usage: bash monitor [--once] [--interval SECONDS] [--recent N] [--autostart-wait MINUTES] [--no-autostart]"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

for tool in gh python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool not found." >&2
    exit 1
  fi
done
if ! gh auth status >/dev/null 2>&1; then
  echo "gh not authenticated. Run: gh auth login" >&2
  exit 1
fi

# curses needs the terminal on stdin, so the program goes through a file, not a pipe.
# curses needs the terminal on stdin, so the program goes through a file, not a pipe.
# The program lives next to this script; curses needs the terminal on stdin, so it runs directly.
exec python3 "$(dirname "$0")/monitor.py" "$INTERVAL" "$RECENT" "$ONCE" "$AUTOSTART"
