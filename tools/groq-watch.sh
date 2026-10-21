#!/usr/bin/env bash
# Usage: tools/groq-watch.sh            follow live: every Groq rate limit, model switch and failure, in words
#        tools/groq-watch.sh --since 1h show what already happened in the last hour (any docker "since" value), then stop
# A 429 from a model is a rate limit: the backend switches to the next model at once (gpt-oss-20b -> gpt-oss-120b)
# and passes over the limited one until its Retry-After ends. Only "ALL MODELS FAILED" reaches the user, as a spoken
# "Sorry, my language service is busy right now".
set -u
cd "$(dirname "$0")/.."
if [ "${1:-}" = "--since" ]; then args=(--since "${2:?how long, e.g. 1h}"); else args=(-f --since 1s); fi
docker compose logs "${args[@]}" --no-log-prefix backend 2>/dev/null | python3 -u -c '
import json, sys, time
for line in sys.stdin:
    try: j = json.loads(line)
    except ValueError: continue
    msg = j.get("msg", "")
    if "groq" not in msg: continue
    at = time.strftime("%H:%M:%S", time.localtime(j.get("time", 0) / 1000))
    why, model = j.get("why", ""), j.get("model", "")
    if msg.startswith("groq model failed"):
        label = "RATE LIMITED " if why == "http 429" else "MODEL FAILED "
        nxt = j.get("next")
        print(f"{at}  {label} {model} ({why})" + (f"  -> switching to {nxt}" if nxt else "  -> no model left to try"))
    elif msg == "groq answered on a fallback model":
        print(f"{at}  answered by fallback model {model}")
    elif msg == "groq request failed on every model":
        err = j.get("err", "")
        print(f"{at}  ALL MODELS FAILED ({err}): the user heard: my language service is busy")
    else:
        print(f"{at}  {msg}")
'
