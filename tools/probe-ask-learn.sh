#!/usr/bin/env bash
# Live regression for "ask, act on the answer, learn from it": runs on the phone, in Settings (no cart, no money).
#
#   tools/probe-ask-learn.sh        needs: phone on adb, backend + mongo up (docker compose up), accessibility service on
#
# It plants a flow whose 2nd and 3rd steps name controls that do not exist (like the stale steps of a real taught flow),
# then checks, in order: it ASKS -> the answer is ACTED ON in the same run -> the flow is NOT changed until you say yes ->
# yes SAVES it -> the next run goes through with NO questions. The probe flow and its logs are always removed again.
set -u
cd "$(dirname "$0")/.." || exit 1
KEY=zz_probe_ask_learn
fail=0
say()  { ./tools/a11y.sh say --es text "$1" >/dev/null 2>&1; }
log()  { adb logcat -d 2>&1 | grep " TVA " | sed 's/^[0-9-]* \([0-9:.]*\) *[0-9]* *[0-9]* \(.\) TVA *: /\1 /'; }
wait_for() { for _ in $(seq 1 "$2"); do log | grep -qE "$1" && return 0; sleep 1; done; return 1; }
mongo() { docker compose exec -T mongo mongosh tva --quiet --eval "$1" 2>&1 | tail -n +1; }
check() { # name, command...
  local name=$1; shift
  if "$@"; then echo "  PASS  $name"; else echo "  FAIL  $name"; fail=1; fi
}
steps_of() { mongo "db.flows.findOne({intentKey:'$KEY'}).steps.map(s => s.type+':'+(s.selector.text||s.selector.contentDesc||'')+':'+s.optional).join(' | ')"; }

cleanup() {
  mongo "const f = db.flows.findOne({intentKey:'$KEY'}); if (f) { db.runlogs.deleteMany({flowId:f._id}); db.flows.deleteOne({_id:f._id}); }" >/dev/null
  echo "(probe flow and its run logs removed)"
}
trap cleanup EXIT
cleanup >/dev/null

adb get-state >/dev/null 2>&1 || { echo "no phone on adb"; exit 2; }
adb reverse tcp:3100 tcp:3100 >/dev/null
if ! log | grep -q "connected"; then
  adb shell monkey -p com.tva.teachable -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
  ./tools/a11y.sh tap --es id com.tva.teachable:id/startVoice >/dev/null 2>&1; sleep 6
fi

mongo "const b={resourceId:'',text:'',contentDesc:'',className:'',near:''};
const st=(type,sel,x={})=>Object.assign({type,selector:Object.assign({},b,sel),slotRef:null,value:'',sensitive:false,expect:{},optional:false},x);
db.flows.insertOne({intentKey:'$KEY',description:'Open the settings promo tour',appPackage:'com.android.settings',slots:[],
  steps:[st('launch',{},{value:'com.android.settings'}),st('tap',{text:'Legacy promo banner'}),st('tap',{text:'Got it'})],createdAt:new Date()})" >/dev/null
BEFORE=$(steps_of)

echo "1. ask"
adb shell input keyevent 3 >/dev/null 2>&1; sleep 2; adb logcat -c
say "open the settings promo tour"
check "asks a specific question when a taught step is missing" wait_for "assistant:.*I can't find \"Legacy promo banner\".*What should I do" 60
check "the question says how far it got and how to answer" bash -c "$(declare -f log); log | grep -q 'got through 1 of 3 steps.*say skip.*say stop'"

echo "2. act on the answer, in the same run"
say "tap Apps"      # below the fold on Settings: it must scroll for it
check "asks again about the NEXT stale step (so the first answer worked)" wait_for "assistant:.*I can't find \"Got it\".*Apps" 60
say "skip it"
check "finishes the run" wait_for "replay Done: 3/3" 40
check "offers the correction in ONE spoken message, as a question" wait_for "assistant:.*Should I update the flow.*Say yes to save it, or no" 20

echo "3. nothing is rewritten without a yes"
check "stored flow is unchanged while the offer is pending" test "$(steps_of)" = "$BEFORE"

echo "4. a yes saves it"
say "yes"
check "confirms the save" wait_for "assistant:.*updated the flow" 20
AFTER=$(steps_of)
check "step 2 now taps Apps" bash -c "[[ '$AFTER' == *'tap:Apps:false'* ]]"
check "step 3 is now optional" bash -c "[[ '$AFTER' == *'tap:Got it:true'* ]]"

echo "5. the next run needs no questions"
adb shell input keyevent 3 >/dev/null 2>&1; sleep 2; adb logcat -c
say "open the settings promo tour"
check "runs through" wait_for "replay Done: 3/3" 90
check "asked nothing" bash -c "$(declare -f log); ! log | grep -q 'What should I do'"

echo
[ $fail -eq 0 ] && echo "ALL PASSED" || echo "SOMETHING FAILED (see above)"
exit $fail
