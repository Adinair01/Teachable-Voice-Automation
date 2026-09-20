#!/usr/bin/env bash
# Real finger tap on the centre of the first on-screen node whose text/desc equals $1 (exercises the real input path).
# Usage: tools/touch.sh "Pizza Dish"
here=$(cd "$(dirname "$0")" && pwd)
"$here/a11y.sh" show >/dev/null
adb shell run-as com.tva.teachable cat files/dump.json | python3 -c "
import json,sys,re,subprocess
want=sys.argv[1].lower()
for n in json.load(sys.stdin):
  if n['text'].lower()==want or n['desc'].lower()==want:
    l,t,r,b=map(int,re.findall(r'-?\d+',n['b']));x,y=(l+r)//2,(t+b)//2
    print('touch',want,x,y); subprocess.run(['adb','shell','input','tap',str(x),str(y)]); break
else: print('not found',want)" "$1"
