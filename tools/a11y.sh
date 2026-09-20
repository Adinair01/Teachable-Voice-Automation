#!/usr/bin/env bash
# Dev helper for the debug command channel. Usage: tools/a11y.sh dump|show|tap|type|scroll|back [--es key val ...]
# Retries because some phones drop the adb link intermittently.
set -u
retry() { for _ in 1 2 3 4 5; do adb wait-for-device && "$@" && return 0; sleep 1; done; return 1; }
cmd=${1:?cmd}; shift || true
case $cmd in
  show)
    retry adb shell am broadcast -a com.tva.DEBUG --es cmd dump >/dev/null; sleep 1.5
    retry adb shell run-as com.tva.teachable cat files/dump.json | python3 -c "
import json,sys
d=json.load(sys.stdin); print(len(d),'nodes, pkg',d[0]['pkg'] if d else None)
for n in d:
  if n['text'] or n['desc']: print(' ',n['id'].split('/')[-1][:28],'|',n['text'][:30],'|',n['desc'][:24],'| click' if n['click'] else '|','scroll' if n['scroll'] else '','edit' if n['edit'] else '')";;
  *) # adb shell re-splits args on spaces, so shell-escape every value ("Aeroplane mode" must stay one arg).
     esc=$(printf ' %q' "$@")
     retry adb shell run-as com.tva.teachable rm -f files/result.txt
     retry adb shell "am broadcast -a com.tva.DEBUG --es cmd $cmd$esc" >/dev/null; sleep 1.5
     retry adb shell run-as com.tva.teachable cat files/result.txt; echo;;
esac
