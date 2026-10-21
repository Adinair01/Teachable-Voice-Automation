#!/usr/bin/env bash
# Usage: tools/keep-connected.sh        (leave it running in its own terminal; Ctrl-C to stop)
# Every time the phone's USB link drops and comes back — a loose cable, a USB hub or adapter resetting — adb forgets
# its port forward, and the app shows "Not connected" until it is set again. This waits for each reconnect and sets it
# again at once (the app then reconnects by itself within ~15 s), printing a line each time so drops are visible.
set -u
PORT=${HOST_PORT:-3100}
while true; do
  adb wait-for-device
  if adb reverse tcp:$PORT tcp:$PORT >/dev/null 2>&1; then
    echo "$(date +%H:%M:%S)  phone connected - forward tcp:$PORT set"
  fi
  # Watch until the link drops (or the forward disappears for any other reason).
  while [ "$(adb get-state 2>/dev/null)" = device ] && adb reverse --list 2>/dev/null | grep -q "tcp:$PORT"; do sleep 2; done
  echo "$(date +%H:%M:%S)  phone link lost - waiting for it to come back"
done
