#!/usr/bin/env bash
# Usage: tools/up.sh
# Brings everything up for a session on the phone over USB, and says what is not ready:
#   backend + Mongo (Docker) -> phone found -> USB port forward -> accessibility service -> voice assistant started.
# Safe to run again at any time, e.g. after the cable was replugged (that drops adb's port forward).
set -u
cd "$(dirname "$0")/.."
PORT=${HOST_PORT:-3100}
APP=com.tva.teachable
ok() { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }

echo "1. Backend (Docker)"
docker info >/dev/null 2>&1 || { open -a Docker 2>/dev/null; echo "     starting Docker Desktop..."; for _ in $(seq 60); do docker info >/dev/null 2>&1 && break; sleep 2; done; }
docker info >/dev/null 2>&1 || bad "Docker is not running. Open Docker Desktop, then run this again."
docker compose up -d >/dev/null 2>&1 || bad "docker compose up failed. Run: docker compose up -d   to see why."
for _ in $(seq 30); do curl -s -m 2 -o /dev/null "http://localhost:$PORT/api/flows" && break; sleep 1; done
curl -s -m 2 -o /dev/null "http://localhost:$PORT/api/flows" || bad "backend is not answering on port $PORT. Run: docker compose logs backend"
ok "backend answering on http://localhost:$PORT"

echo "2. Phone"
adb start-server >/dev/null 2>&1
state=$(adb get-state 2>/dev/null)
[ "$state" = device ] || bad "no phone. Plug it in with a data cable, unlock it, and accept 'Allow USB debugging'."
ok "phone connected ($(adb shell getprop ro.product.model | tr -d '\r'))"
adb reverse tcp:$PORT tcp:$PORT >/dev/null || bad "could not forward port $PORT to the phone"
ok "phone -> laptop link (adb reverse tcp:$PORT)"

echo "3. App"
adb shell settings get secure enabled_accessibility_services | grep -q "$APP" \
  || bad "accessibility service is off. On the phone: Settings > Accessibility > Teachable Voice > On. Then run this again."
ok "accessibility service on"
adb shell input keyevent KEYCODE_WAKEUP
# Start the voice assistant (the app's "Start voice assistant" button), retrying: right after an install or a reboot
# the accessibility service, which presses it, takes a few seconds to come back.
for _ in $(seq 8); do
  case "$(tools/a11y.sh status 2>/dev/null)" in *voice=true*) break;; esac
  adb shell am start -n $APP/.MainActivity >/dev/null 2>&1; sleep 2
  adb shell am broadcast -a com.tva.DEBUG --es cmd tap --es id $APP:id/startVoice >/dev/null 2>&1; sleep 2
done
adb shell cmd media_session volume --stream 3 --set 11 >/dev/null 2>&1
# Ask the app itself (it retries the connection every 15 s, so give it up to 20 s).
for _ in $(seq 10); do
  status=$(tools/a11y.sh status 2>/dev/null | tr -d '\r')
  case "$status" in *connected=true*) break;; esac; sleep 2
done
case "$status" in
  *connected=true*) ok "voice assistant running and connected" ;;
  *voice=false*) echo "  ?    voice assistant not started. On the phone, open Teachable Voice and tap 'Start voice assistant'." ;;
  *) echo "  ?    voice assistant not connected yet ($status). Run this script again in a few seconds." ;;
esac
[ "$(adb shell settings get secure location_mode | tr -d '\r')" = 0 ] && echo "  note location is OFF: Zomato needs it for delivery."
adb shell input keyevent KEYCODE_HOME
echo "Ready. Hold the blue button on the phone and speak."
