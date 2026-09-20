#!/usr/bin/env bash
# Usage: tools/replay.sh <flow-name> '<slots-json>'   e.g. tools/replay.sh chrome_search '{"query":"burger"}'
set -u
flow=${1:?flow}; slots=${2:-{\}}
here=$(cd "$(dirname "$0")" && pwd)
retry() { for _ in 1 2 3 4 5; do adb wait-for-device && "$@" && return 0; sleep 1; done; return 1; }
retry adb push "$here/flows/$flow.json" /data/local/tmp/$flow.json >/dev/null
retry adb shell run-as com.tva.teachable cp /data/local/tmp/$flow.json files/$flow.json
retry adb shell run-as com.tva.teachable rm -f files/result.txt
retry adb shell am broadcast -a com.tva.DEBUG --es cmd replay --es file "$flow.json" --es slots "$slots" >/dev/null
for _ in $(seq 1 60); do
  out=$(adb shell run-as com.tva.teachable cat files/result.txt 2>/dev/null) && [ -n "$out" ] && { echo "$out"; exit 0; }
  sleep 1
done
echo "TIMEOUT"; exit 1
