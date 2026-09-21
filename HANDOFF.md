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

## Day 3 progress (2026-09-20, in progress)
DONE & tested:
- Backend: SttStream (Deepgram Nova 3 live) + Aura TTS (mp3), Session orchestrator (audio->STT->matchFlow (paraphrase+slots, one Groq call)->replay|teach; teach_done->generalise->save; run_result->RunLog replay+match), sensitive-step detection, dashboard at /dashboard (+/api/metrics/runs). Scripted end-to-end (`npm run smoke`): teach -> learned -> paraphrase "look up pizza on Google" matched w/ new slot -> replay -> TTS 23KB audio. `npm test` = 8 passing (smoke moved to scripts/ — it used to get picked up by node --test and pollute Mongo!).
- Android: BackendClient (OkHttp WS, auto-reconnect), PttRecorder (16k PCM), AudioPlayer, VoiceService (foreground mic service; command loop; teach start/finish; replay w/ confirmation gate; run_result), Overlay (accessibility overlay: hold-to-talk, TEACH MODE indicator, Done, Yes/No), MainActivity (permissions, enable-accessibility, start service, backend URL, typed command, flows list, live log). Debug `say` cmd feeds a typed utterance into the same pipeline. ConfirmText unit-tested (fail-safe: unclear = NO).
- Teach mode verified on device through typed commands: teach_start -> recorder -> `done` -> server saved flow.
FINDINGS / LIMITS:
- **Chrome omnibox suggestion taps emit NO TYPE_VIEW_CLICKED** (even with a real finger tap) -> recorder can't capture them. Standard views (Settings) record correctly (launch + tap with expect = next window). So taught demos use Settings; Chrome flows are hand-authored (tools/flows). Mention in limitations; possible mitigation = mirrored-screenshot teach mode or inferring submit.
- `adb reverse` gets dropped on USB reconnect -> phone now uses LAN URL http://192.168.1.10:3100 (Mac and phone on same Wi-Fi; set in app + saved). IP may change (DHCP) -> update in app.
- USB link to the Realme is unreliable: it fully dropped at ~20:20; user must replug (data cable, File transfer mode, accept prompt).
- Chrome Google suggestions are personalised/non-deterministic ("pizza near me" not always offered) -> hand chrome flows rely on contains-slot + LLM fallback.
- Slot-bound taps drop `expect` in generalise() (screen after tap depends on slot).
UPDATE (20:38): REAL MIC STT CONFIRMED (user's voice "Open gmail" transcribed). Taught flow "open Bluetooth settings" (recorded via real touch: launch settings + tap "{{setting}} Off") replayed for "take me to the Display settings" (paraphrase matched after matchFlow prompt fix; replay succeeded after SelectorMatcher fix: className fallback only when no id/text/desc). Phone-side teach state now cancels on WS reconnect. Metrics currently polluted by test runs (learn 1/3, replay 1/2, match 1/2) -> reset Mongo runlogs before final measurement (`db.runlogs.deleteMany({})`).
Gotchas: after reinstall the a11y service may need `settings put secure enabled_accessibility_services <svc>` + wait ~5s (do NOT put empty string: "Bad arguments"). Agent and user testing simultaneously on the phone collide (stray "Sorry, I didn't catch that" = overlay mic touched).
PENDING for Day 3 gate: taught flow #1 ("open Bluetooth settings", slot page=Bluetooth) recorded via real touch, then replay "open Display settings"; 2 more taught flows (e.g. toggle Bluetooth, search settings); real-mic STT test (needs user to hold overlay mic); confirmation flow test with a sensitive step; popup-dismissal; paraphrase accuracy eval set; metrics from real RunLogs; README; Docker fresh-verify; demo video; PPT; team name.

## Overlay UI/UX upgrade (2026-09-20, tested on device)
- Floating panel is now DRAGGABLE over any app (drag from the mic button or the status text), snaps to the nearest side
  edge on release, and persists x/y in SharedPreferences ("tva_overlay") — verified across an a11y-service restart.
- Mic button has two modes, distinguished by a 220 ms hold delay: press-and-HOLD (red, white ring, "Release to send",
  records while held — unchanged behaviour) and DOUBLE-TAP within 450 ms (green + pulse, "Tap to send", hands-free until
  the next single tap, 60 s safety cap in VoiceService). Movement past touchSlop before the hold delay turns the gesture
  into a drag instead, so dragging never records and tapping never drags.
- CHOSE tap-to-stop over silence/endpoint detection for hands-free: the Deepgram stream is only finalised on `audio_end`
  from the phone, so auto-stop would need new VAD/endpointing plumbing on both ends and would risk cutting the user off
  mid-sentence during the demo. A second tap is deterministic and already on the path we test. Revisit post-hackathon.
- Teach mode: backend now says exactly "Do whatever you want to do on the screen so I can learn from it."
  (TEACH_PROMPT in ws/session.js); the overlay turns amber with an orange ring + "● TEACH MODE" header.
- Backend also sends `teach_failed` so the phone leaves "Saving…" when a demo yields no usable steps.
- AppState.listening (Boolean) replaced by AppState.listen (ListenMode IDLE/HOLD/HANDS_FREE) — one source of truth.
- Tested on the Realme via adb (swipe = drag, long-press = hold, two taps = hands-free, single tap = stop, typed
  command -> teach mode): all states rendered correctly, screenshots in the session scratchpad.
- NOTE: these tests added ~3 failed `learn` RunLogs ("play some jazz on the radio") — reset runlogs before final metrics.

## Taught flows + measured run (2026-09-21, ~01:00-01:45 IST)
THREE TAUGHT FLOWS (all recorded through real touch events on the device, all replayed with NEW slot values):
1. "open Bluetooth settings" (Settings) -> replays "take me to the Display settings" / "show me the Display settings" /
   "go to the Wi-Fi settings".
2. "calculate 5 plus 3" (com.coloros.calculator) -> replays "what is 9 plus 4" (=13), "add 2 and 7", "compute 6 plus 1".
3. "open the stopwatch in the clock app" (com.coloros.alarmclock) -> replays "open the timer", "switch to the world
   clock", "show me the stopwatch" (verified visually that the right tab is actually selected).

BUGS FOUND WHILE TEACHING (all fixed + unit-tested):
- generalise kept the demonstrated resource-id on slot-bound taps (digit_5), and resourceId outranks text on replay,
  so "7 plus 2" would have tapped 5 again. Slot-bound taps now drop the id.
- generalise tokenised only text OR contentDesc; the leftover literal ("Stopwatch") could win on replay. Both now.
- Replayer matched a tab's non-clickable caption and reported SUCCESS while nothing happened (clock opened on the
  wrong tab). Taps now prefer clickable nodes (SelectorMatcher.findTarget), falling back to the full tree.
- Slot tokens were resolved in selector.text only, never in contentDesc.
- Intent/match prompts: the model answers {"num1": 5} and zod rejected the whole extraction -> shared slotMap with
  z.coerce.string(), plus shared SLOT_RULES ("one value per slot, not a phrase") in src/prompts/slots.js.
- groqJson now retries once on 429/5xx honouring Retry-After (the Groq free tier throttles bursts of eval calls).

SENSITIVE-STEP GATE — verified on device, both branches, using tools/flows/sensitive_clear.json (a harmless flow whose
"Clear" tap is marked sensitive; POST it to /api/flows to re-create it):
- "clear the calculator" -> replay pauses, TTS "Confirm? About to tap Clear.", overlay shows Yes/No.
- No  -> nothing tapped (display still showed 72), run stops. Yes -> the step runs and the display clears.
- A declined run is NOT a failed replay any more: RunLog.declined, phone says "Stopped", backend says "Okay, I stopped
  before that step.", metrics exclude declined runs from the replay denominator and no match row is written.
- NOTE: the *detection* of sensitive words is unit-tested (services/sensitive.js); the device test used an explicitly
  flagged harmless step because every genuinely sensitive button on this phone is destructive.

PARAPHRASE EVAL: `npm run eval:paraphrase` (backend/scripts/paraphrase-eval.mjs) runs a labelled set (12 utterances,
incl. 2 negative controls that must NOT match) through the real matcher against the flows in Mongo.
- Before the prompt fix: 11/12 ("go to the Wi-Fi settings" did not match the Bluetooth-taught flow -> teach mode).
- After adding "a slot value may be one never seen before (Bluetooth -> Wi-Fi, Display, Sound)": 12/12 match and slots,
  and the Wi-Fi paraphrase now replays on the phone too.
- Run it in the container (Mongo is not published to the host):
  docker compose run --rm -v "$PWD/backend/scripts:/app/scripts" backend npm run eval:paraphrase
- A no-match while flows exist is now logged as a FAILED match row, so the metric cannot flatter itself (caveat: a
  genuinely new task also lands there — the eval set is all paraphrases, so it is right for the measured run).

METRICS after the measured run (runlogs+flows were reset first): learn 3/3 = 100%, replay 10/10 = 100%,
paraphrase 10/11 = 90.9% (the one miss is the pre-fix Wi-Fi attempt; re-measure after a reset before submission).

PHONE HOUSEKEEPING: a blind coordinate tap on the home screen opened the Camera and its 1s night-exposure pill (no
photo taken, timer setting verified Off) — never tap coordinates that were not read from a fresh dump/screenshot of the
CURRENT screen. A test alarm (23:00 "test") that I created was deleted again; the user's own alarms were untouched.

## Session end 2026-09-21 ~02:00 IST — TTS confirmed audible, README written
- **Spoken replies verified on the device, not assumed**: backend `say()` -> Deepgram Aura mp3 -> binary WS frame ->
  `voice/AudioPlayer` (MediaPlayer, media stream). Proof: Android logged
  `SystemUi--Volume: onMultiMediaStateChange ... packageName=com.tva.teachable` with the audio HAL waking at the same
  moment. **The phone's media volume is 0** (speaker: 0) — every test tonight was silent because of that. I raised it
  to 8 for the check and put it back to 0 as found. TURN MEDIA VOLUME UP before recording the demo video.
- **README.md written** (graded deliverable): pitch + architecture diagram, repo layout, backend via
  `cp backend/.env.example backend/.env` + `docker compose up -d --build` + endpoint table, APK build/install with
  CLI-only Gradle, `adb reverse` vs LAN URL, first-run steps (accessibility must be enabled by hand), how to teach and
  replay (drag / hold / double-tap / confirmation), flow schema, tests + smoke + paraphrase eval + dashboard, security
  section (keys, credential redaction, confirmation gate, validation, non-root image, cleartext = dev only) and known
  limitations (Chrome omnibox, no long-press/drag capture, GROQ_MODEL deviation, unbound slots, socket-bound session).

## Final-stretch session 2026-09-21 (evening) — deliverables built
- Docker verified from scratch with the README's commands (`down -v`, `up -d --build`): /health ok, /dashboard up, metrics empty, container user = node (non-root), 15 unit tests pass.
- BUGS FOUND + FIXED while re-teaching (all committed): (1) new-task teach slots came from the matcher's side-output and could be a whole phrase ("5 plus 3") -> demo never matched -> learn failed; now always from extractIntent. (2) intent prompt let "open the stopwatch in the clock app" get NO slots (replay would tap the wrong tab and still report success); rule added: page/tab/section/number named in the command is always a slot. (3) matcher missed "open the timer" (no app name); prompt now decides by the flow's own slots + example. (4) eval compared slot NAMES the model invents; now compares values. Eval = 13/13 match, 13/13 slots.
- FINAL MEASURED METRICS (flows+runlogs reset, 3 flows taught on the phone, 9 paraphrased replays): learn 3/3, replay 9/9, paraphrase 9/9 (all 100%). Rows for the *teach-trigger* commands (a first-time task while other flows exist) were deleted from the match metric: they are new tasks, not paraphrases. Small sample, one phone.
- DEMO VIDEO: `adb screenrecord` is blocked on the Realme (permission denied everywhere), so the video is built from ~1.8 fps screenshots of a real teach -> replay -> decline -> confirm run + macOS `say` narration + PIL captions (submission/*_Demo_Video.mp4, 2m20s). Not the phone's TTS audio. Build scripts were scratchpad-only.
- DECK: submission/*_Presentation.pptx (python-pptx, 10 slides). NOT visually rendered (no LibreOffice here) — open it and eyeball overflow.
- Filenames use SRM_University_Kattankulathur_Chennai_Ciphera_s (spaces/commas/apostrophe stripped) — rename if the portal wants the literal string.
- SECRETS: working tree clean, backend/.env gitignored, BUT commit 49d20eb (initial scaffold) contains REAL Deepgram + Groq keys in backend/.env.example. Keys must be rotated and history rewritten BEFORE any push/tag. Not done — awaiting user decision. No remote is configured yet.
- Phone: a stray Airplane-mode toggle happened during the measured run (cause not pinned down; restored with `cmd connectivity airplane-mode disable`). Do not use blind coordinate taps.
- Phone-side gotcha: typed "yes"/"no" via the debug `say` channel are NEW commands, not confirmation answers — answer the gate with the overlay Yes/No buttons.

- 2026-09-21 late: built-in 'open <app>' (phone sends installed apps on connect, backend resolves exact label, sends launch_app). Verified on device with typed commands (Gmail, Chrome, Camera opened; taught 'open Bluetooth settings' still wins; unknown app -> teach mode). Cause of the user's report: opening an app was never a built-in and cannot be taught (recorder ignores launcher taps, learn needs >=1 non-launch step). Mic/STT path not re-tested after this change.
- Video now has an 'open any app' scene (Calculator + Clock; Gmail/Settings frames were dropped because they showed personal data) and the deck has a matching slide (11 slides).
- Commits/PRs: author Aditya Nair <adityanair5002@gmail.com>, NO Claude trailers. History was rewritten once (keys purged, identity fixed); backup bundle in the session scratchpad only.

## Next steps (in order)
1. **Team name** (CollegeName_TeamName) — blocks every submission filename. ASK before generating any.
2. Fresh-verify Docker from a clean state (`docker compose down -v && docker compose up -d --build`) and re-check
   `/health` + `/dashboard` against the README's exact commands.
3. Clean metrics run for the numbers that go in the PPT: reset runlogs, teach the three flows, replay the eval set,
   screenshot `/dashboard`.
4. Demo video (<=5 min): teach trigger + manual demo + second command with a different slot + confirmation before a
   sensitive step. Media volume up; overlay states are visible on screen.
5. Presentation (problem, solution, architecture, why Accessibility beats per-app integrations, metrics, limitations,
   "production = OEM system service").
6. Secrets scan, commit everything, then WAIT for Aditya's go-ahead before creating/pushing the
   `PRISM_GENAI_HACKATHON_Y2026` tag.

## Open items
- Team name (CollegeName_TeamName) — needed before submission filenames.
- Phone must be re-plugged (see above).
