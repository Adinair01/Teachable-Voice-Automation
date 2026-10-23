# Teachable Voice Automation

**Samsung PRISM Gen AI Hackathon - Theme 3.** An Android assistant that learns to operate **any** app by watching you
do the task once, then repeats it by voice with new values.

> You: *"order me a burger"* → nothing learned yet → the phone says **"Do whatever you want to do on the screen so I
> can learn from it"**, watches you do it, and saves the steps as a template.
> Later: *"order me a pizza"* → it replays the same steps with the new value.

It works through Android's **AccessibilityService**, so the target app needs no SDK, no API and no cooperation — which
is exactly where per-app voice integrations (Bixby included) stop. Production shape: the same architecture as an OEM
system service.

**▶️ Demo video (full run):** https://youtu.be/Mcz7sx1gQbA — teach a flow by voice, then exact / paraphrase /
changed-slot replays, and the assistant asking a question when it needs one. A copy is also in [`submission/`](submission/).

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

### The five parts

1. **Speech-to-intent** — Deepgram Nova 3 turns speech into text; Groq (JSON mode) extracts the intent and its slots.
2. **UI-tree capture** — the AccessibilityService reads the live UI tree (resource-id, text, content-description, class,
   bounds, clickable) of whatever app is in front, with no cooperation from that app.
3. **Generalisation** — one demonstration becomes a parameterised flow: selectors + actions, with the values you spoke
   turned into slots (`{{item}}`, `{{quantity}}`, `{{address}}`).
4. **Slot extraction** — on a replay command, Groq matches it to the right saved flow (paraphrase-tolerant) and pulls the
   new slot values out of the spoken words; slots you did not mention fall back to the taught example.
5. **Replay** — each step runs against the live UI tree (find by selector → wait → verify), row-anchored where a control
   repeats, with the LLM element-picker as a fallback and a hard stop at any payment / OTP / login screen.

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

**To open an app:** say *"open Gmail"* / *"open the Clock app"*. This is built in (no teaching): the phone reports its
installed apps and the backend matches the exact app name. An unknown name falls back to teach mode.

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

## Target apps

Nothing is hard-coded per app. The recorder and replayer only ever know *selectors and actions*; the app-specific
behaviour below (row anchoring, quantity stepper, address picker, payment boundary) is generic UI-pattern handling, not
a per-app script. A judge can teach a brand-new flow on any of these — or another app entirely — live.

- **Zomato** (`com.application.zomato`) — **primary**. Food ordering end to end, taught once by demonstration:
  search a restaurant → open it → search a dish → add it → adjust quantity → choose a saved delivery address → stop at
  the payment screen and hand control back. Item / quantity / restaurant / address are slots, so the same taught flow
  replays "a Farmhouse pizza", "two Margheritas", "deliver to work", etc. Requires `android:isAccessibilityTool="true"`
  to see Zomato's commit buttons (see limitations).
- **Settings, Clock, Calculator** (system apps) — **secondary**, as generalisation proof: the identical
  teach → generalise → replay pipeline also learns non-food flows (open a Settings page, switch a Clock tab, run an
  arithmetic calculation) with slot substitution ("open Bluetooth settings" → "open the Wi-Fi settings", "calculate 5
  plus 3" → "what is 9 plus 4").
- **Android Settings** (`com.android.settings`) — **second-app proof**, taught by demonstration only: "Set my screen
  timeout to 1 minute" (Display & brightness → Auto screen off → 1 minute), to be replayed as "Keep my screen on for 5
  minutes"; "Set screen timeout to 7 minutes" (an option the app does not offer) should make the assistant ask, naming
  the options actually on screen, and continue with the one the user says. Not yet verified end to end on the phone. Labels are from a Realme phone (Android 15); One UI
  names the same setting "Screen timeout", so a Galaxy needs its own one-time demonstration.

## Known limitations

- **Zomato hides its commit buttons from ordinary accessibility services.** On Android 14+, "Add item ₹X", checkout and
  "Place Order" carry `accessibilityDataSensitive` and are invisible unless the service declares
  `android:isAccessibilityTool="true"`, which we set. Without it neither the recorder nor the payment ScreenGuard can
  see those buttons. Google Play restricts that flag to genuine accessibility tools; an OEM system service — our
  production shape — qualifies.
- **Delivery range is account- and location-specific, and the address slot is only a WEAK pass.** On the test account
  Domino's delivers to the saved *Work* address but not *Home* (out of range), so a "deliver to home" run correctly
  **stops at the address step with a specific reason** rather than forcing an undeliverable address. Because Work is
  already the account's default address and there is no second deliverable address, a "deliver to work" run reaches the
  Work address but **no actual address switch is observed** — the picker's switching path is unit-tested but not proven
  live. Stated plainly: T6 is a weak pass.
- **T7's autonomous-handling (6-point) path is demonstrated but not independently forced.** The replayer autonomously
  dismissed a real Zomato promo interstitial ("Add items worth ₹X… / Unlock 20% OFF… Continue") mid-flow and still
  reached payment; the already-in-cart trigger is handled by asking a specific question. A promo pop-up shown *on
  restaurant open* uses the same dismissal mechanism but cannot be forced on demand (Zomato decides when to show it), so
  that specific variant is not independently verified.
- **Repeated controls need row anchoring.** Every dish's "ADD" shares the same id/text and Zomato keeps the label in
  `contentDesc` (empty `text`), so a tap is anchored to the row whose caption names the requested item — never "the
  first ADD". The quantity stepper likewise reads its count from `contentDesc`. (Bugs found and fixed while testing:
  adding a look-alike dish instead of the requested one, and reporting success after an LLM fallback tapped an unrelated
  element — replays now require cart evidence and LLM picks must plausibly relate to the target.)
- **Some OEMs never fire the gesture-dispatch callback.** On the Realme test device (ColorOS, Android 15) the teach-mode
  touch-catcher relied on `GestureResultCallback` to restore touchability; this device sometimes never calls it, so only
  the first tap was recorded. Worked around with a token-guarded 1200 ms timeout that force-restores touchability.
  Replay is unaffected — it uses `performAction`, not gesture dispatch.
- **Chrome's omnibox suggestions emit no click event**, so demonstrations inside Chrome's address bar cannot be
  recorded; Chrome flows in `tools/flows/` are hand-authored. Standard app views (Zomato, Settings, Clock, Calculator,
  …) record correctly.
- Long-press, drag and pinch are not recorded — taps, typing, scrolling and back are.
- The Groq account used here no longer serves Llama 3.x, so `GROQ_MODEL` defaults to `openai/gpt-oss-20b`
  (any JSON-mode model works). Every LLM call walks a short **model ladder** (`GROQ_MODEL` then `GROQ_FALLBACK_MODELS`,
  default `openai/gpt-oss-120b`, GPT models only): a model that is rate limited, down, times out (5 s) or answers
  unusably hands the question to the next one immediately, and a rate-limited model is skipped until its `Retry-After`
  ends. A live session never waits out a rate limit; if every model fails the user hears that the service is busy.
  `tools/groq-watch.sh` shows rate limits and model switches as they happen.
- A slot the demonstration never used (e.g. "plus" in *calculate 5 plus 3*) is stored but cannot vary the replay.
- One phone, one backend: the session state lives on the socket, so a reconnect cancels a half-finished teach session.
- **On-device screen recording is blocked** on the Realme test device (`screenrecord` fails with permission denied on
  every writable path), so the demo video is captured with `scrcpy` over adb instead.
