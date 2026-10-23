#!/usr/bin/env bash
# Usage: tools/preflight.sh [--fix]
# Demo-morning check. Runs tools/up.sh (Docker, phone, adb reverse, accessibility service, voice assistant), then
# checks everything else a live demo needs and prints OK / WARN / FAIL for each. With --fix it also switches location
# and Do Not Disturb on and sets the media volume. Read-only otherwise: it never taps anything inside an app.
set -u
cd "$(dirname "$0")/.."
FIX=${1:-}
ok() { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
warn() { printf '  \033[33mWARN\033[0m %s\n' "$1"; WARNS=$((WARNS + 1)); }
bad() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILS=$((FAILS + 1)); }
WARNS=0; FAILS=0

echo "== Connection (tools/up.sh)"
tools/up.sh || { echo "Fix the FAIL above, then run this again."; exit 1; }

echo "== Backend services"
code=$(curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST localhost:3100/api/llm/intent -H 'Content-Type: application/json' -d '{"utterance":"order a margherita pizza"}')
[ "$code" = 200 ] && ok "Groq answering (intent test)" || bad "Groq not answering (HTTP $code). tools/groq-watch.sh --since 10m"
tts=$(docker compose exec -T backend node --input-type=module -e '
const r = await fetch("https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=mp3", { method: "POST",
  headers: { Authorization: `Token ${process.env.DEEPGRAM_API_KEY}`, "Content-Type": "application/json" }, body: JSON.stringify({ text: "ok" }) });
console.log(r.status);' 2>/dev/null | tr -d '\r')
[ "$tts" = 200 ] && ok "Deepgram voice answering" || bad "Deepgram voice not answering (HTTP $tts)"
flows=$(curl -s localhost:3100/api/flows | python3 -c 'import json,sys; d=json.load(sys.stdin); d=d.get("flows",d) if isinstance(d,dict) else d; print(len(d)); [print("        - " + f.get("description","")) for f in d]')
echo "$flows" | head -1 | grep -qv '^0$' && ok "saved flows: $(echo "$flows" | head -1)" || warn "no flows saved yet"
echo "$flows" | tail -n +2

echo "== Phone settings"
loc=$(adb shell settings get secure location_mode | tr -d '\r')
if [ "$loc" = 0 ] && [ "$FIX" = --fix ]; then adb shell settings put secure location_mode 3; loc=$(adb shell settings get secure location_mode | tr -d '\r'); fi
[ "$loc" != 0 ] && ok "location on" || warn "location OFF (Zomato needs it). Run with --fix, or turn it on in quick settings."
[ "$FIX" = --fix ] && adb shell cmd notification set_dnd priority >/dev/null 2>&1
zen=$(adb shell settings get global zen_mode | tr -d '\r')
[ "$zen" != 0 ] && ok "Do Not Disturb on (zen_mode=$zen)" || warn "Do Not Disturb OFF: a notification could pop up mid-demo. Run with --fix."
[ "$FIX" = --fix ] && adb shell cmd media_session volume --stream 3 --set 12 >/dev/null 2>&1
vol=$(adb shell cmd media_session volume --stream 3 --get 2>/dev/null | grep -oE 'volume is [0-9]+' | grep -oE '[0-9]+')
[ "${vol:-0}" -ge 8 ] && ok "media volume $vol/16" || warn "media volume ${vol:-?}/16: the assistant may be too quiet. Run with --fix."
batt=$(adb shell dumpsys battery | grep -m1 'level' | grep -oE '[0-9]+')
[ "${batt:-0}" -ge 40 ] && ok "battery $batt%" || warn "battery ${batt:-?}%: charge it"
stay=$(adb shell settings get global stay_on_while_plugged_in | tr -d '\r')
[ "$stay" != 0 ] && ok "screen stays on while plugged in" || { adb shell svc power stayon usb; ok "screen set to stay on while plugged in"; }

echo "== Screen and app state"
if adb shell dumpsys window | grep -q "isKeyguardShowing=true"; then
  bad "phone is LOCKED: unlock it (nothing can be read or tapped behind the lock screen)"
else ok "phone unlocked"; fi
adb shell input keyevent KEYCODE_HOME; sleep 1
focus=$(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r')
case "$focus" in *launcher*|*Launcher*) ok "home screen in front, no dialog open" ;; *) warn "something is open over the home screen: $focus" ;; esac
case "$(tools/a11y.sh status 2>/dev/null)" in *teaching=false*) ok "not in teach mode" ;; *) warn "teach mode is on: say cancel" ;; esac
# Best effort, read-only: open Zomato's home and look for a cart bar. It cannot see carts at other restaurants.
adb shell monkey -p com.application.zomato -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
tools/a11y.sh dump >/dev/null 2>&1
cart=$(adb shell run-as com.tva.teachable cat files/dump.json 2>/dev/null | python3 -c '
import json,re,sys
caps=[(n["text"] or n["desc"]).strip() for n in json.load(sys.stdin)]
print("; ".join(sorted({c for c in caps if re.search(r"\b\d+ items? added\b|^view cart$", c, re.I)})))' 2>/dev/null)
[ -z "$cart" ] && ok "Zomato: no cart bar on the home screen (best effort: open the cart icon to be sure)" || warn "Zomato shows a cart: $cart. Empty it before the demo."
adb shell input keyevent KEYCODE_HOME

echo "== Screen recording (scrcpy on the Mac; on-phone screenrecord is blocked on this phone)"
if command -v scrcpy >/dev/null; then
  tmp=$(mktemp -t tva_rec).mp4
  scrcpy --no-window --no-control --record="$tmp" --time-limit=2 >/dev/null 2>&1
  [ -s "$tmp" ] && ok "scrcpy can record ($(du -h "$tmp" | cut -f1) test file)" || bad "scrcpy could not record"
  rm -f "$tmp"
  echo "       Record the demo with:  scrcpy --record=\"demo-\$(date +%H%M).mp4\" --audio-source=playback"
else bad "scrcpy not installed: brew install scrcpy"; fi

echo
echo "Done: $FAILS fail, $WARNS warn. Keep tools/keep-connected.sh running in another terminal during the demo."
[ "$FAILS" = 0 ]
