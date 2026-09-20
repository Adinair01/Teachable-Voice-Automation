# HANDOFF.md — Session history & current state

**Usage:** New session → user says "Review the handoff" → read HANDOFF.md, log.md (rules), CLAUDE.md (spec), then continue
from "Next steps". At the end of every session/milestone → update this file (state, decisions, next steps) and commit.

## Project
Teachable Voice Automation (Samsung PRISM Gen AI Hackathon, Theme 3). Full spec: CLAUDE.md. Rules: log.md.
Dir: /Users/adityanair/Teachable voice Automation. Owner: Aditya Nair (Node dev, no Android experience).
Deadline: 25 Sep 2026 11:59 PM IST (aiming for 2–3 day build).

## Environment (as of 2026-09-20)
- macOS, node v26, npm 11, Docker 29, JDK 17, git.
- Installed via brew: android-commandlinetools, android-platform-tools (adb), gradle 9.7.
- ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ; SDK platform 35 + build-tools 35.0.0 (34 also present).
  Export ANDROID_HOME and PATH (cmdline-tools/latest/bin) in each shell.
- No Android Studio (CLI-only builds via Gradle). Test device: physical Android phone via USB (not yet connected).
- User has Deepgram + Groq keys; will place in backend/.env himself. Mongo runs in Docker.

## Decisions
- CLI-only Android toolchain; physical phone; phone→backend via `adb reverse tcp:3000 tcp:3000` (settings screen also allows LAN URL).
- Plan compressed to 3 days with gates (CLAUDE.md).

## Log
- 2026-09-20: Wrote CLAUDE.md, .gitignore, git init + first commit. Installed Android CLI toolchain. Created log.md and HANDOFF.md.

## Status (Day 1 GATE reached, 2026-09-20)
- Android: tap/type/scroll/back PROVEN on Settings (tap Bluetooth, scroll, type "wifi" in search, back) and Chrome (tap "Images", type "pizza" into search field, scroll). Chrome web content exposes 575-700 nodes (usable). No empty trees found. Tapping/typing uses own tree walk (findByText unreliable in web content).
- YouTube: tree readable but app shows "There was a problem signing in" dialog (not signed in) — poor demo candidate; use Settings + Chrome as core demos.
- Realme quirk: adb link drops intermittently -> tools/a11y.sh retries with wait-for-device. Screen must be unlocked; `svc power stayon usb` set.
- Backend skeleton in backend/ (Express, Mongoose models Flow/RunLog, /api/flows list+delete, /api/metrics, /ws handshake, zod config/validation, pino, Dockerfile, docker-compose.yml, .env.example). `docker compose up -d --build` verified. Host port 3100 (3000 is used by an unrelated Langfuse container); phone uses `adb reverse tcp:3100 tcp:3100` -> http://localhost:3100.
- Not yet: Deepgram/Groq wiring, recorder, replayer, WebSocket client in app, mic, TTS, dashboard HTML.

## Day 2 GATE (2026-09-20) — reached
- Groq: `llama-3.3-70b-versatile` is NOT served on this key anymore (no Llama in the account model list). Using `openai/gpt-oss-20b` (JSON mode OK, ~0.8s). Change via GROQ_MODEL. Deviation from "Llama 3.x" — mention in pitch/README.
- Backend: src/lib/groq.js (single validated JSON call), prompts/pickElement.js + intent.js, routes /api/llm/pick-element, /api/llm/intent, POST/GET /api/flows. `npm test` (node --test) = 3 passing.
- Android: core/Flow.kt (schema + slot resolve), Replayer (poll 6s, retry once 3s, LLM fallback, expect verification, sensitive-step gate = DenyAll by default), Recorder (events->steps, debounced typing, redacts passwords, expect = next window pkg), BackendElementPicker. Unit tests SelectorMatcherTest pass (`./gradlew testDebugUnitTest`).
- Launch uses CLEAR_TASK (deterministic fresh start). Slot tokens allowed in selector text ("{{query}} near me"); slot-bound taps fall back to contains(slotValue); taps never target editable nodes.
- Hand-written flows in tools/flows/*.json; run `tools/replay.sh <flow> '{"query":"pizza"}'`. Results: settings_bluetooth 3/3 (Bluetooth verified toggled), chrome_search pizza/burger/sushi/tacos/noodles all OK, chrome_images pizza/burger/cats 3/3, test_fallback (wrong selector -> LLM picks element) 3/3 with llm=true.
- Recorder: captured real selectors from real input events (not coordinates) but only tap-events tested; typing capture + window-change `expect` need a proper test in Day 3.
- GOTCHAS: (1) `adb shell` re-splits args on spaces — tools/a11y.sh now escapes; (2) adb reverse mapping is lost on USB reconnect -> rerun `adb reverse tcp:3100 tcp:3100` (LLM fallback silently fails without it; now logged as TVA pick-element failed); (3) a blind `input tap` toggled Airplane mode once (restored) — never blind-tap coordinates; (4) cleartext HTTP allowed (dev, localhost/LAN) — production would be TLS.
- Stopped relationsai-voice container per user (other relationsai containers still running; port 3000 = langfuse).

## Next steps (Day 3)
1. Android WebSocket client (OkHttp), mic push-to-talk + foreground service, audio streaming; backend Deepgram STT + Aura TTS over /ws; TTS playback.
2. Backend orchestration: transcript -> intent -> Mongo lookup (+ paraphrase matching via Groq) -> replay msg or "show me once" -> teach mode; flow generalisation (slot detect) + save; RunLog writes; metrics dashboard HTML.
3. Sensitive-step voice/tap confirmation; popup dismissal via fallback; settings screen (backend URL) + learned flows list + teach indicator UI.
4. README, Docker verify, demo video, PPT; ask team name; wait for go-ahead before tag.

## Open items
- Team name (CollegeName_TeamName) — needed before submission filenames.
