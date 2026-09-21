# Teachable Voice Automation

**Samsung PRISM Gen AI Hackathon — Theme 3.** An Android assistant that learns to operate **any** app by watching you
do the task once, then repeats it by voice with new values.

> You: *"order me a burger"* → nothing learned yet → the phone says **"Do whatever you want to do on the screen so I
> can learn from it"**, watches you do it, and saves the steps as a template.
> Later: *"order me a pizza"* → it replays the same steps with the new value.

It works through Android's **AccessibilityService**, so the target app needs no SDK, no API and no cooperation — which
is exactly where per-app voice integrations (Bixby included) stop. Production shape: the same architecture as an OEM
system service.

---

## How it works

```
 phone                                     backend (Docker)
 ─────                                     ────────────────
 mic ──16 kHz PCM──► WebSocket ─────────►  Deepgram Nova 3  (speech → text)
                                              │
                                              ▼
                                           Groq (JSON mode)
                                            ├─ intent + slots
                                            ├─ match a saved flow (paraphrase tolerant)
                                            └─ pick an element from a UI dump (replay fallback)
                                              │
 AccessibilityService                         ▼
  ├─ Recorder  (your taps/typing → steps) ──► generalise → Mongo (Flow)
  └─ Replayer  (steps → live UI tree)     ◄── flow + new slot values
        │                                     │
        └─ RunLog per step ──────────────────►│ metrics dashboard
                                              ▼
 speaker ◄──mp3──── WebSocket ◄──────────  Deepgram Aura  (text → speech)
```

**Teaching** records *selectors* (resource-id, text, content-description, class), never screen coordinates, so a
replay survives a different screen size, a scrolled list or a moved button. Values you spoke ("burger") are detected in
what you typed or tapped and become slots (`{{item}}`).

**Replaying** reads the live UI tree per step, waits for the target (~6 s, one retry), verifies the resulting screen,
and — if the selector still misses — sends a compact UI dump to the LLM to pick the element (this also gets past
unexpected popups). **Anything sensitive** (pay / send / order / delete / submit …) pauses and asks for a spoken or
tapped "yes" first. It never auto-fills or auto-submits a login or payment screen.

---

## Repository layout

```
android/    Kotlin app (AccessibilityService, recorder, replayer, mic, floating overlay)
backend/    Node + Express + MongoDB: WebSocket session, STT/TTS, Groq prompts, REST, dashboard
tools/      dev helpers over adb (UI dump, tap/type by selector, replay a hand-written flow)
docker-compose.yml
```

---

## 1. Run the backend

Requires Docker (and API keys for [Deepgram](https://deepgram.com) and [Groq](https://groq.com) — both have free
tiers).

```bash
cp backend/.env.example backend/.env     # then fill in DEEPGRAM_API_KEY and GROQ_API_KEY
docker compose up -d --build
curl localhost:3100/health               # {"ok":true,"db":true}
```

`.env` is gitignored and never baked into the image; only `.env.example` is committed. The host port defaults to
**3100** (override with `HOST_PORT=…`); the container listens on 3000.

| URL | What |
|---|---|
| `GET /health` | liveness + DB state |
| `GET /dashboard` | metrics dashboard (learn / replay / paraphrase rates, recent runs) |
| `GET /api/flows` · `GET/DELETE /api/flows/:id` · `POST /api/flows` | learned flows |
| `GET /api/metrics` · `GET /api/metrics/runs` | metrics as JSON |
| `POST /api/llm/intent` · `POST /api/llm/pick-element` | the LLM steps, callable on their own |
| `ws://…/ws` | the phone's session socket (audio in, transcript / flow / TTS out) |

Without Docker: `cd backend && npm install && npm start` with a MongoDB reachable at `MONGO_URL`.

## 2. Build and install the app

Needs JDK 17 and the Android SDK (platform 35 + build-tools 35). No Android Studio required.

```bash
export ANDROID_HOME=/path/to/android-sdk          # or put sdk.dir=… in android/local.properties
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. Point the phone at the backend

Over USB:

```bash
adb reverse tcp:3100 tcp:3100                     # app's default URL http://localhost:3100 then works
```

`adb reverse` is dropped whenever the cable is re-plugged. Over Wi-Fi instead, open the app and set the backend URL to
your machine's LAN address (e.g. `http://192.168.1.10:3100`) — phone and computer on the same network.

## 4. First run on the phone

1. Open **Teachable Voice** and allow the microphone.
2. Tap **1. Enable Accessibility Service** → Android Settings opens → find *Teachable Voice* → switch it on.
   Android does not allow an app to enable this itself; it is the one manual step.
3. Tap **2. Start voice assistant**. A floating mic bubble appears over every app.
4. **Turn the media volume up** — the replies are spoken through the media stream.

---

## Using it

The floating bubble can be **dragged anywhere** (it snaps to the nearest edge and remembers where you left it) and has
two ways to talk:

| Gesture | Mode |
|---|---|
| **Press and hold** | records while held, sends on release (blue → red) |
| **Double-tap** | hands-free: keep talking with your hands free, **tap once** to send (green, pulsing) |

**To teach:** say a command it does not know. The bubble turns amber ("TEACH MODE"), the assistant says *"Do whatever
you want to do on the screen so I can learn from it"* — do the task once, then say **"done"** or tap **Done teaching**.

**To replay:** say the same thing in your own words with different values. Before any sensitive step it asks
*"Confirm?"* — answer by voice or with the **Yes / No** buttons on the bubble.

Typed commands do the same thing as speech (the text box on the main screen), which is handy while developing.

---

## Flow schema

```jsonc
{
  "intentKey": "open_settings",
  "description": "open Bluetooth settings",      // the command it was taught with
  "appPackage": "com.android.settings",
  "slots": [{ "name": "setting", "example": "Bluetooth" }],
  "steps": [
    { "type": "launch", "value": "com.android.settings", "selector": {}, "slotRef": null,
      "sensitive": false, "expect": {} },
    { "type": "tap", "selector": { "text": "{{setting}} Off", "className": "android.widget.LinearLayout" },
      "slotRef": "setting", "value": "", "sensitive": false, "expect": {} }
  ]
}
```

`type`: `launch | tap | type | scroll | back | wait`. Selector priority on replay:
`resourceId > text > contentDesc > className`; taps prefer clickable nodes, and a slot-bound tap also matches
"contains the slot value". `expect` (package / text) is what the replayer verifies after the step.

---

## Tests, eval and metrics

```bash
cd backend && npm test                  # unit tests (generalise, prompts, metrics, retry)
npm run smoke                           # end-to-end protocol run against a running backend
cd android && ./gradlew testDebugUnitTest

# paraphrase-matching eval against the flows currently in Mongo (labelled set + negative controls)
docker compose run --rm -v "$PWD/backend/scripts:/app/scripts" backend npm run eval:paraphrase
```

The **dashboard** at `/dashboard` computes three numbers from real `RunLog` rows: learn success rate, replay success
rate and paraphrase match accuracy. A run the user stopped at a sensitive step is recorded as *declined* and excluded
from the replay denominator — refusing to do something dangerous is not a failed automation.

`tools/` drives the phone over adb while developing:

```bash
tools/a11y.sh show                             # dump the current screen's UI tree
tools/a11y.sh tap --es text "Bluetooth"        # tap by selector, never by coordinates
tools/replay.sh chrome_search '{"query":"pizza"}'    # replay a hand-written flow from tools/flows/
```

---

## Security

- **Keys** live only in `backend/.env` (gitignored), never logged, never in the image.
- **Password / PIN / OTP / card fields are never recorded, stored or sent to the LLM** — they are dropped while
  teaching and skipped while replaying.
- **Nothing sensitive runs unattended**: pay, send, order, delete, submit and friends require an explicit spoken or
  tapped confirmation; the default answer is no, and 30 s of silence counts as no.
- Every REST and WebSocket message is schema-validated; LLM output and on-screen text are treated as untrusted data,
  never as instructions.
- The Docker image runs as a non-root user.
- Cleartext HTTP is enabled **for local development only** (`adb reverse` / LAN). A shipped build would be TLS.

## Known limitations

- **Chrome's omnibox suggestions emit no click event**, so demonstrations inside Chrome's address bar cannot be
  recorded; Chrome flows in `tools/flows/` are hand-authored. Standard app views (Settings, Clock, Calculator, …)
  record correctly.
- Long-press, drag and pinch are not recorded — taps, typing, scrolling and back are.
- The Groq account used here no longer serves Llama 3.x, so `GROQ_MODEL` defaults to `openai/gpt-oss-20b`
  (any JSON-mode model works). The free tier throttles bursts; calls retry once on 429.
- A slot the demonstration never used (e.g. "plus" in *calculate 5 plus 3*) is stored but cannot vary the replay.
- One phone, one backend: the session state lives on the socket, so a reconnect cancels a half-finished teach session.
