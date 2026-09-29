# HANDOFF.md — Session history & current state

**Usage:** New session → user says "Review the handoff" → read HANDOFF.md, log.md (rules), CLAUDE.md (spec), then continue
from "Next steps". At the end of every session/milestone → update this file (state, decisions, next steps) and commit.

## Project
Teachable Voice Automation (Samsung PRISM Gen AI Hackathon, Theme 3). Full spec: CLAUDE.md. Rules: log.md.
Dir: /Users/adityanair/Teachable voice Automation. Owner: Aditya Nair (Node dev, no Android experience).
Deadline: **30 Sep 2026 11:59 PM IST** (moved from 25 Sep as of the 2026-09-28 session; see that session's notes).

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

## Team name (received) + deliverables built (2026-09-21 late – 2026-09-22)
- Team name: **SRM University, Kattankulathur, Chennai_Ciphera's** → filenames use
  `SRM_University_Kattankulathur_Chennai_Ciphera_s` (spaces/commas/apostrophe stripped for filesystem/zip safety).
- Docker fresh-verify done (`down -v` + `up -d --build`) against the README's own commands: `/health` ok, `/dashboard`
  up, 15→18 unit tests pass as features were added, container user non-root.
- Clean metrics run done (flows+runlogs reset, 3 flows taught on the real phone, 9 paraphrased replays):
  **learn 3/3, replay 9/9, paraphrase 9/9 (all 100%)**; offline eval 13/13 match + slots. Screenshotted from `/dashboard`.
- `submission/` holds `SRM_University_Kattankulathur_Chennai_Ciphera_s_Demo_Video.mp4` and `..._Presentation.pptx`.
- Presentation: **11 slides**, rendered and eyeballed with LibreOffice (`brew install --cask libreoffice`, headless
  `--convert-to pdf`) + PyMuPDF — one real overflow found and fixed (Architecture slide backend-card heading wrapped
  into its body text; shortened it). Not yet checked again after the still-pending re-record of slides 7/8 photos
  (see below) — reopen and eyeball after that.
- Repo-wide secrets scan done: working tree clean, `backend/.env` gitignored, `.env.example` has empty values.
- **History was rewritten** (`git filter-branch`, backup bundle was scratchpad-only, now gone with the session) to:
  (a) purge the real Deepgram/Groq keys that commit `49d20eb`'s `.env.example` had originally, (b) set every commit's
  author+committer to `Aditya Nair <adityanair5002@gmail.com>`, (c) strip all `Co-Authored-By: Claude…`/"Generated with
  Claude Code" trailers from commit messages. Verified after rewrite: 0 key hits across all history, 0 Claude/Anthropic
  mentions in commit messages (one incidental "CLAUDE.md" filename reference remains in a commit subject — the spec
  file is still named `CLAUDE.md` in the repo; flagged, not changed, awaiting word from Aditya).
- **Standing rule from Aditya (2026-09-21/22): no commit or PR from here on may carry any Claude/Anthropic attribution.
  Every commit's author+committer must be `Aditya Nair <adityanair5002@gmail.com>`.** Saved to Claude's persistent
  memory (`commit-attribution` + `secrets-in-history` memory files) so it survives a session restart.
- **Both API keys ROTATED by Aditya on 2026-09-22** (new Deepgram + Groq keys placed in `backend/.env`). Verified
  after rotation: backend restarted, `/health` ok, Deepgram TTS synthesis succeeded (`deepgram tts ok <bytes>`), Groq
  paraphrase eval passed 13/13 (one run hit `groq http 429` — free-tier burst throttling, not an auth failure; retried
  and passed clean).
- Built-in **"open <app>"** feature (see below) shipped, tested on device, video+deck updated to include it.

## Bug report from Aditya (2026-09-21/22): "open Gmail" always fails, teaching it also fails
Root cause found and explained to Aditya: opening an app was never a built-in command, and demonstrating it can't work
either — the Recorder ignores taps on the home screen/launcher by design (`launched` flag only fires on the first
non-launcher package), and `#learn` in `backend/src/ws/session.js` rejects a flow with zero non-launch steps. So "open
Gmail" always fell into teach mode, and the demo (just opening the app) always produced 0 actionable steps.
**Fix shipped:**
- New `backend/src/services/openApp.js`: `parseOpenApp(text)` regex-parses "open/launch/start (the) X (app)"; `resolveApp(name, apps)` matches EXACT normalised label only (deliberately not fuzzy — "open Bluetooth settings" must not resolve to the Settings app and shadow the taught flow; ambiguous/duplicate labels resolve to null too). Unit-tested (`test/openApp.test.js`).
- `Session` in `backend/src/ws/session.js`: new `apps` message type (`{type:"apps", apps:[{label,package}]}`) stores the phone's installed-app list on the session; `handleCommand` tries `resolveApp` BEFORE the flow matcher and sends back `{type:"launch_app", package, label}` + speaks "Opening X." on a hit. An unmatched name falls through to the normal matcher → teach mode, unchanged.
- Android: new `InstalledApps.kt` (`queryIntentActivities` for `ACTION_MAIN`/`CATEGORY_LAUNCHER`, deduped by package) sent as the `apps` message right after every successful WS connect (`VoiceService.onConnection`); new `"launch_app"` case in `onServer` calls `TvaAccessibilityService.launch(pkg)` (existing method, already used by the Replayer's `LAUNCH` step).
- Teach-failure message when a demo only opened an app is now distinct: "I only saw an app being opened. To open an app, just say open and its name. Teach me a task inside the app instead." (was the generic "I didn't see any actions.").
- **Verified on device** with typed commands (same code path as speech, STT is upstream of `handleCommand`): "open Gmail" → Gmail opened, "open the Chrome app" → Chrome opened, "launch Camera" → Camera opened, "open Zomato" (not installed) → teach mode as expected, "open Bluetooth settings" → the TAUGHT FLOW still won (Settings app did not shadow it), replay succeeded. Mic/STT path itself not re-tested after this change until the live-recording session (see below) — do that first if anything seems off.

## Demo video — status: NEEDS A LIVE RE-RECORD (Aditya's explicit priority #2, in progress)
- First cut (`ead8aeb`): `adb shell screenrecord` returns "Permission denied" on every writable path tried
  (`/sdcard/*`, `/sdcard/Movies/*`, `/data/local/tmp/*`) on this Realme (Android 15) — NOT the Android 14+
  Accessibility-recording restriction, just this device/shell refusing to write. Built instead from ~1.8 fps
  `adb exec-out screencap` screenshots + macOS `say` narration + PIL captions, spliced with ffmpeg. Silent (no real
  phone TTS audio). Covered: teach "calculate 5 plus 3" → demo → replay "what is 9 plus 4"=13 → sensitive-step
  "clear the calculator" declined then confirmed.
- Added an "open any app" scene (`8628887`) the same screenshot way: had to re-record once because the first take
  showed Aditya's real Gmail inbox (sender names/subjects) and the Settings account screen (name+photo) on camera —
  deleted those frames, redid with Calculator+Clock only, nothing personal in the final cut. Final cut 155s (2m35s),
  under the 5 min cap.
- **Aditya wants this replaced with a REAL screen recording + his real voice**, not screenshots/synthetic narration.
- Investigated and solved the capture problem: `brew install scrcpy` (v4.1) — mirrors+records over the existing adb
  connection, doesn't need on-device storage write. Verified working: `scrcpy --no-window --no-control --record=...
  --audio-source=playback` captured real video (572×1280) AND the phone's audio output (so the assistant's spoken TTS
  replies will be audible, not just the video). Mac's built-in mic also tested and picks up voice fine
  (`ffmpeg -f avfoundation`) for Aditya's spoken commands.
- **PENDING — needs Aditya live at the phone**, not yet done: unlock phone, screen stays on, raise media volume (0 by
  default — was the cause of an earlier "silent" scare, see the 2026-09-21 02:00 entry), start scrcpy recording, do a
  real teach ("calculate 5 plus 3" or similar) + real replay with a new slot + the sensitive-step confirm/decline by
  voice, then re-add a short "open any app" beat and title/closing cards. Avoid Gmail/any screen with personal data
  again. After this is done: re-render and re-eyeball the deck (photos on slide 7 come from the OLD screenshot video's
  frames and should be swapped for real frames from the new recording).

## SCOPE UPDATE 2026-09-23 — evaluation spec received (see CLAUDE.md "EVALUATION SPEC")
Judges teach a NEW flow live and run T1–T14 on it; Zomato food ordering is now the primary target. Everything built
so far is the foundation (nothing thrown away); the Settings/Calculator/Clock flows become secondary proof.
The old screenshot demo video is superseded: new video = one unedited scrcpy recording, (a) teach (b) exact replay
(c) paraphrase (d) changed slot (e) asks a question when stuck.

### T11 credential boundary — BUILT + unit-tested, NOT yet device-tested (2026-09-23)
- `core/ScreenGuard.kt` (pure Kotlin): `screen(nodes)` flags PASSWORD (any isPassword node), PAYMENT (known UPI/wallet
  packages, gateway SDK resource ids razorpay/juspay/payu/…, card-number/CVV/expiry inputs, payment-page titles, or ≥3
  distinct payment methods listed), OTP (input labelled/id'd otp/PIN, or 4–8 digit boxes next to an OTP caption), LOGIN
  (login/sign-in caption + phone/email input). `action(nodes,node)` / `actionText()` flag taps that commit money/order
  (pay, place order, proceed to pay, buy now, swipe to pay, checkout…), incl. the target's subtree text.
- Replayer: screen check before every non-launch step and on the final screen; action check on the resolved target
  (also LLM-fallback picks) and on the step's selector BEFORE the confirmation gate (payment hands over, never asks).
  `RunResult.handover`; a PAYMENT handover with earlier steps done = success (reaching payment is the goal of T2).
- Recorder: records nothing (taps or typing) while the current screen is a boundary — an OTP field is not isPassword.
- Backend: `run_result.handover {kind, reason}` validated, stored on RunLog.handover, spoken via
  `services/handover.js` ("You're at the payment step. Your turn: …"). Match row counts handover as a correct match.
- Deliberate: the cart screen with a "Place Order" button is NOT a screen boundary (T6 address pick happens there);
  only tapping the commit button is. Thresholds need tuning against Zomato's real tree.
- Tests: Kotlin 26 pass (ScreenGuardTest 11, ReplayerGuardTest 4 new), backend 18 pass, debug APK builds.

### Zomato readability check (2026-09-23 ~12:35 IST) — GO so far (read-only screens)
- Zomato (com.application.zomato) installed, logged in, Home+Work saved. Domino's Pizza IS a Zomato storefront here
  (4.6★, 45–50 min). Margherita Pizza ₹114 and Farmhouse Pizza ₹279 both found via in-restaurant search.
- Trees readable: home 327 nodes, search 204, restaurant menu 250-320. Mixed View + Jetpack Compose. Labels are mostly
  in contentDesc (dish_name, dish_final_price, restaurant title) — matching must use desc as much as text.
- Stable ids: search_edit_text / edittext (search fields), dish_name, text_view_title="ADD" + button_add per dish,
  dish_customisation="customisable", fabButton "Menu", in-restaurant search "Search in Domino's Pizza".
- PROBLEMS SEEN: (1) every dish's ADD has the SAME id/text -> recorder's "tap ADD" would hit the first ADD on replay;
  T4 needs a row-anchored tap ("ADD in the row whose dish_name = {{item}}"). (2) Coach-mark tooltips and the theme
  picker open as SEPARATE windows: rootInActiveWindow then returns only the popup (5 nodes) — T7 must detect a tiny
  foreground window and dismiss it. (3) Tapping the "Domino's Pizza" search suggestion lands on a results list, a
  second tap on the card opens the restaurant. (4) Home dump shows the saved address text — keep dumps out of logs/video.
- Debug `tap` now uses the Replayer's target rule (skip inputs, prefer clickable) — it used to hit the search box.
- Cart walk-through done with Aditya's OK (Margherita added, cart/address/payment page inspected, cart emptied after;
  nothing ordered):
  * CRITICAL FIX: Zomato hides its action buttons ("Add item ₹114", checkout) from non-tool accessibility services
    (Android 14+ accessibilityDataSensitive). `android:isAccessibilityTool="true"` in accessibility_config.xml makes
    them visible. Without it the recorder could not capture them AND the guard could not see pay buttons. README
    limitation: Play policy restricts that flag to genuine a11y tools; an OEM system service qualifies.
  * Item sheet ("customisable"): Crust/Size radio groups with defaults preselected, stepper (button_remove /
    text_view_title=qty / button_add), confirm button id/button "Add item ₹<price>". Then an upsell sheet
    ("Unlock 20% OFF… Continue") may appear — an interstitial the replay must pass.
  * Cart (MenuResCartActivity): toolbarButton "25-30 mins to Home | <address>" = address switcher; row stepper with
    qty in text_view_title (read back 1 -> 2, total updated); tv_payment_title "PAY USING"; tv_action_text "Place
    Order" inside cv_checkout_container. Also "Renew Gold … ADD ₹1" upsell — replay must NEVER tap an ADD outside the
    target item's row. Tree mixes cart + menu nodes underneath.
  * Address sheet: "Select an address", saved entries = label (Home/Hotel/…) + address + phone, grouped under
    DELIVERS TO / DOES NOT DELIVER TO. Contains PII — keep out of logs/video. NO "Work" address exists on the account
    (asked Aditya); one deliverable Home only.
  * Payment page = PaymentsOptionsActivityV5: RECOMMENDED/CARDS/WALLETS/PAY LATER sections. ScreenGuard -> PAYMENT.
- T11 DEVICE-VERIFIED: tools/flows/t11_payment_probe.json (unflagged tap on the payment page) -> handed over, nothing
  tapped; t11_commit_probe.json (unflagged tap on "PAY USING" by id only) -> handed over by the live-node check.
  Dry-run `tools/a11y.sh check --es text|id …` (never taps): "Place Order" label, cv_checkout_container and
  genericCartButton all -> PAYMENT. Item ADD / stepper + -> allowed. Cart itself -> no boundary. Login/OTP not
  device-tested (would need logging out) — unit-tested only.
- New debug cmds: `guard` (ScreenGuard on live screen), `check` (guard verdict for a tap target, dry run).

### T4/T5/T6 machinery — BUILT, unit-tested, row anchoring dry-run verified on Zomato (2026-09-23 afternoon)
- Work address added by Aditya (label "Work", in Domino's range). NOTE: Zomato's global location is now Work, so the
  cart defaults to Work — test T6 both ways ("deliver to home" as well as "to work").
- Row anchors (T4): Recorder sends `context` (row captions) for taps on controls that repeat on screen; generalise
  turns the caption naming a slot value into `selector.near` ("{{item}}"). Replay (`SelectorMatcher.target`, shared
  with the debug channel) only taps a same-kind control in a row naming near/slot value — never "the first ADD", no
  LLM fallback; scrolls (largest scrollable) up to 3x. Verified dry-run on the real Farmhouse search: near
  "Farmhouse Pizza" -> its ADD, the two "Double Cheeseburst - Farmhouse" rows -> their own ADDs, Pepperoni -> no target.
  NOTE: Zomato's ADD label is in contentDesc, not text.
- New step types `set_quantity` (T5: − n + stepper in the item's row, taps and RE-READS until count == slot, 1..20)
  and `choose_address` (T6: opens "… mins to Home"/"Deliver to" header, taps the saved label, verifies header says
  "to <label>"; unknown label -> closes picker, "there's no saved address called X"). generalise adds them plus
  optional slots quantity(1)/address(home) to any command matching order|buy|purchase|deliver|get me, inserted before
  the first sensitive step (else at the end). Skipped on replay when the slot is empty.
- Slot matching is case/punctuation-insensitive ("dominos" typed binds "Domino's") with a longest-word fallback
  ("Margherita pizza" spoken, "margherita" typed). SLOT_RULES reserves quantity/address/item/restaurant names;
  `normaliseSlots` turns "two" -> "2", "my work" -> "work". Learn reply is now "Learned: <command>."
- Interstitials: a recorded step whose target never shows is skipped when the NEXT step's target is already there
  (the "Unlock 20% OFF… Continue" upsell). Tree now merges all windows of the foreground app (tooltips are separate
  windows).
- Tests: Kotlin 37 pass (RowContextTest 6, ReplayerSlotsTest 5 new), backend 21 pass.

## Next steps (in order — 2026-09-23 scope)
1. PHONE (needs Aditya): connect USB + `adb reverse tcp:3100 tcp:3100`; install Zomato, log in, saved Home + Work.
2. Dump Zomato's tree (`tools/a11y.sh show`) on home, search, restaurant menu, item customisation sheet, cart, address
   sheet, payment page. GO/NO-GO within ~2h: if mostly unlabeled/empty -> STOP and report (pivot: Domino's app).
   Confirm Domino's is a storefront on Zomato in our area; else pick the closest real substitute.
3. Device-test T11 on the real payment/OTP/login screens (incl. a flow NOT flagged sensitive); tune ScreenGuard.
4. T1–T6 live on the phone (new slot types: item picked from menu/search, quantity stepper, saved address).
5. Tier 2 (T7, T10, T12, T13, T14), then Tier 3 only if time.
6. Deliverables: new unedited video, README/deck (5 named architecture parts + diagrams, target apps, limitations).
7. Repo push + tag: WAIT for Aditya's explicit go-ahead (unchanged).

## Session 2026-09-28 — deadline moved to 30 Sept 2026 11:59 PM IST. RULE THIS SESSION: nothing in this log is marked
done/verified unless watched live on the real phone this session; unit tests alone are marked CODED, NOT DEVICE-VERIFIED.

**Goal for the session:** live-verify T1 (fresh teach) through T6, then Tier 2. **Result: T1 is still not verified —
zero flows were successfully taught this session (every attempt failed to save).** Found and fixed three real bugs
along the way; the session ended blocked on the phone dropping off adb (see below), not by choice.

BUGS FOUND + FIXED (root-caused from live Mongo/RunLog/logcat evidence, not guessed):
1. **Restaurant-slot templating bug** (`backend/src/services/generalise.js`): a tap whose OWN caption carries the slot
   value (e.g. tapping a search result named after the restaurant) was ALSO getting a `near` row-anchor templated from
   the same text, which requires an EXACT text match on replay ("{{restaurant}} Pizza" -> "zulu's pizza Pizza") and
   silently returns zero candidates for any restaurant whose label doesn't literally end in that exact resolved
   string — the substring-fallback that would have handled it never runs because `near` routes matching through the
   strict row-anchor path first. Fixed: skip templating `near` when the tap's own text/desc already carried the slot.
   CODED + unit-verified (new regression test in `generalise.test.js`); **not device-verified** (this specific
   scenario — substituting a different restaurant into an existing flow — is not actually one of T1-T6; found because
   Aditya naturally tried a different restaurant against the old pre-existing flow before it was deleted).
2. **Mic/status overlay physically occludes app content during teach mode** (`Overlay.kt`): the panel's default
   resting spot (~62% down screen, edge-hugging) sits directly on top of the primary-action zone most apps use
   (confirmed visually: it covered Zomato's View Cart bar, ADD buttons, and the "Add more items" row in screenshots
   taken mid-session). Since it's a `TYPE_ACCESSIBILITY_OVERLAY` window with no NOT_TOUCHABLE flag, any tap landing in
   its bounds is consumed by the panel and never reaches the teach-mode touch-catcher underneath. Fixed: panel now
   docks to a thin strip just under the status bar while `teaching` is true, and restores the user's own dragged
   position when teaching ends. **Visually confirmed on-device** (screenshotted mid-teach, panel sits clear of
   content); not yet confirmed to be sufficient on its own since bug 3 (below) was masking it in every test run.
3. **Root cause of "only the first tap ever gets recorded, then nothing"** (`TouchCatcher.kt`), the dominant bug this
   session — reproduced identically across three independent attempts, different apps each time (Zomato, then a
   Bluetooth-settings attempt that captured only the app-launch, then a Wi-Fi attempt that captured nothing at all).
   `setTouchable(v, true)` (which lets the invisible full-screen touch-catcher start seeing touches again after
   re-delivering a gesture to the real app) is ONLY called from `GestureResultCallback.onCompleted`/`onCancelled`. On
   this device (Realme RMX3771 / ColorOS, Android 15) that callback can simply never fire, which leaves the overlay
   permanently non-touchable for the rest of the teach session — every tap after the first passes straight through to
   the app and is never recorded, with no error, no crash, nothing in the log. Fixed: added a token-guarded 1200ms
   fallback that force-restores touchability even if the callback never comes. **CODED, NOT YET DEVICE-VERIFIED** —
   the one live retest attempted after this fix was inconclusive (see below) and then the phone dropped off adb
   entirely. Confirmed by reading `ActionExecutor.kt` that **replay is unaffected**: it uses
   `performAction(ACTION_CLICK)`, not gesture dispatch, so this bug class is isolated to teach-mode recording only.
4. Added diagnostic logging (`backend/src/ws/session.js`, `logger.warn` on a failed `#learn`) that dumps the raw
   command, extracted slots, and raw recorded steps — this is what made bugs 1 and 3 findable at all; before this, a
   failed teach left literally no trace beyond a bare RunLog row. Recommend keeping this permanently.

OTHER FINDINGS (not code bugs, workflow/UX gaps worth fixing before the judged run):
- Aditya's natural workflow (double-tap for hands-free, then narrate what he's about to do WHILE also describing it
  verbally, before physically doing it) repeatedly produced long garbled trigger commands ("So we go here in Zomato.
  Then we search... Ask user where exactly do he need to order it from...") that got extracted as garbage slots, and
  "done"-equivalent utterances that didn't literally start with done/finished/etc never matched `DONE` regex in
  `session.js`, so `teach_finish` never fired and stray new teach cycles chained instead. Not touched yet. Likely
  worth: (a) coaching to use a short clean trigger + hold-to-talk or typed "done" during testing, (b) longer-term,
  loosening `DONE` matching and/or giving live "N steps recorded" feedback during teach mode so a stuck/confused
  session is visible before "done" is said — good candidate to fold into the T13/T10 work.
- Deleted the stale pre-existing flow "Order a Margherita pizza from Domino's on Zomato" (Mongo id
  `6ab382cec6b891111bfc89dd`, created 2026-09-23, predates today's fixes) with Aditya's explicit go-ahead, specifically
  so T1 could be taught genuinely live rather than matching an old flow as a restaurant-slot paraphrase.
- Twice this session the phone was found sitting on Zomato's REAL checkout page (once with a stray "1 item"/₹196
  Margherita in cart, once showing "Place Order ₹249.74" with Paytm UPI selected) as a side effect of failed/aborted
  demo attempts. Flagged to Aditya both times; nothing was tapped by the assistant. Aditya said he'd clear the cart
  himself — **not independently re-confirmed empty**, check before the next live session.
- Phone battery was at 1-5% (fluctuating) for this entire session despite being "on charge" — climbed only 1% in over
  an hour. **Session ended with the phone gone from `adb devices` entirely** (suspected battery death, last seen at
  5%, last `ws client disconnected` in backend logs at the same time). Get it on a charger that actually charges it
  (verify with the phone's own battery %, not just "plugged in") before the next session, or nothing below can be
  live-verified.

STATUS TABLE (separated per the session rule):
- T1 (fresh live teach): **NOT VERIFIED.** Blocked on the phone dying mid-retest of bug 3's fix.
- T2-T6, T7/T10/T12/T13/T14: **NOT STARTED** this session (never got past T1).
- generalise.js self-labelled-tap fix: CODED, unit-verified, not device-verified.
- Overlay top-docking during teach: visually confirmed on-device, not yet proven sufficient end-to-end.
- TouchCatcher touchability-timeout fix: CODED, NOT device-verified.
- Failed-learn diagnostic logging: CODED, working as observed (produced the logs quoted above).

## Session 2026-09-28 (later) — live results on the real phone (Realme, Zomato, Domino's, Work address)
Rule kept: VERIFIED = watched live this session. CODED = unit/matcher tested only.

VERIFIED live:
- Recording fix: a typed trigger + two real taps in Settings + "done" -> backend log shows launch + both taps (the
  TouchCatcher 1200 ms fallback works; last session only the first tap was ever captured).
- **T1**: typed "Order a Margherita pizza from Domino's on Zomato", demonstrated from a cold-started Zomato (search
  Dominos -> restaurant -> in-restaurant search Margherita -> ADD -> "Add item" -> Continue to cart, stopped there),
  phone said "Learned", flow saved with 12 steps and slots item/restaurant/quantity/address. HAND EDIT: one stray demo
  step (my mistaken tap on an upsell item, resource `recommend_root_layout`) was deleted from Mongo afterwards.
- **T2** exact replay: from an empty cart, unattended, cold start -> cart with Margherita x1 (Rs114), overlay "Your turn",
  RunLog success + handover payment, 12/12 steps, no LLM fallback, ~1 min. Place Order never touched.
- **T4** "Order a Farmhouse pizza ..." -> cart holds Farmhouse Pizza (not Margherita), 12/12, handover payment.
- **T5** "Order two Margherita pizzas ..." -> cart row stepper = 2, Rs228, 12/12, handover payment.
- T11 payment boundary re-observed at the real cart each run (handover, no tap past the cart). Login/OTP still unit-only.
- **T13** typed "Order pizza" -> "Which pizza?" (nothing tapped); "never mind" -> "Okay, I cancelled that." (read from the
  app's live log; audio not heard). 
- **T14 fail case** typed "Did the last run succeed?" after a real failure -> "No. The last run of ... stopped at step 12,
  choosing the delivery address: the app didn't switch to your "home" address, it may be outside the delivery area."
  **T14 pass case** (after a real handover run): "The last run of ... reached the payment screen and handed over to you. That is where it stopped."
- **T6 work**: "... deliver to work" -> header "to Work", Margherita x1, handover payment, 12/12 (WEAK: Work was already selected).
- **T13 follow-up**: "Order pizza" -> "Which pizza?", then "margherita" -> replay ran (RunLog utterance "margherita", 12/12,
  handover payment, cart Margherita x1).

LEGITIMATE FAILURE, NOT A BUG — T6 home: "... and deliver to home" stopped at step 12 (choose_address) with a specific
reason. The address sheet (read-only look, nothing selected) shows Work under DELIVERS TO and Home (939 km) + Hotel
(275 km) under DOES NOT DELIVER TO, so Domino's cannot deliver to Home from this account. Do NOT force Home to work.
Consequence: Work is already Zomato's selected address, so a "deliver to work" run passes without switching anything;
this account has no second deliverable address, so the picker's switching path (choose_address opening the sheet and
tapping a saved label) is CODED, NOT verified. T6-work has since been run (see above).

- **T10 stuck handling** (Replayer.stuck + core/ScreenSummary): a step whose target never shows now ends with ONE specific
  question naming the missing control and up to 4 short labels the screen shows (password/input fields, long digit runs and
  long strings are dropped so no PII is spoken or stored); a screen of another app says "I'm not in the app any more ...
  take me back?"; LLM picks are capped at 2 per run; the run stops at the first failed step (no loops), worst case ~15 s
  per step. VERIFIED on the phone with tools/flows/t10_stuck_probe.json (launch our own app, tap a control that does not
  exist): ~12 s, spoken-line text "I can't find "Add item". This screen shows "2. START VOICE ASSISTANT", "SAVE BACKEND
  URL", ...  What should I do?", no tap made (llm:false). Wrong-app wording, the pick cap and PII filtering are unit-tested
  only (Kotlin: 50 tests pass, 4 new in ReplayerStuckTest). Not tested: a real logged-out/foreign-language Zomato.

- **T7 (pre-existing cart) — bugs found live, then fixed (CODED, only partly re-verified):** with Margherita x1 already in the
  cart, replaying "Order a Margherita pizza ..." (a) ADDED THE WRONG DISH ("Double Cheese Margherita Pizza", Rs199) because
  the exact row had a stepper instead of ADD and the loose row match fell through to a look-alike -> RowContext.pick now
  refuses a neighbour when a caption exactly naming the item is on screen; Replayer.alreadyInCart then asks "\"X\" is
  already in your cart, N of it. Add another, or leave it as it is?" (b) an LLM fallback pick tapped an unrelated "friends"
  tag and the run still reported success ("order ready for review" was claimed without checking the screen) ->
  LLM picks must now share a word with the step's target or be a plain dismiss control (Close/Skip/Got it/...), and an
  ordering flow that ends without cart evidence ("pay using / place order / bill details ...") is a FAILURE with a
  specific message. (c) Zomato's MENU stepper keeps its count in contentDesc (text is empty) - Steppers now read both.
  (d) CartState (cart bar "View Cart / N item", "replace cart?" prompt) feeds the question wording; replace/clear/remove
  are never chosen by a guess. Kotlin 56 tests pass. LIVE STATE AFTER THOSE RUNS: the user's cart contains the stray
  Double Cheese Margherita (+ Margherita x1) and Zomato was left on a "Dishes loved by friends" sheet -> user must
  check/empty the cart. The three fixes above have NOT yet been re-run live.

CODED, not run on the phone: T12 ("Book a cab" -> matcher returns no flow; reply is now "I haven't learned that yet. Show
me once: do it on the screen, then say done." and teach mode opens; there is no cancel once teach mode is open), the T13
follow-up reply path, T10, T7 beyond the upsell-interstitial skip. NOT STARTED: T8/T9 (no second app taught), Tier 3.

Also done this session: compact teach panel (label + Done only, parked mid-left, mic hidden while teaching) because the
old panel covered Zomato's in-restaurant Search; new services/runStatus.js + tests; matchFlow now returns `clarify` for
a generic item; 27 backend tests pass; Kotlin 56 tests pass.
Commits (this session, all authored Aditya Nair, no Claude attribution): 506b231 (TouchCatcher/overlay/generalise +
failed-learn logging), 244298b (T12/T13/T14 + clarify + compact panel), ca7bdc2 (handoff T1/T2/T4/T5/T13/T14 +
deadline fix), 8af2e51 (handoff T6-work/T14-pass/T13-follow-up), 4fb9a31 (T10 stuck handling), fb54434 (T7 look-alike/
plausible-LLM/cart-evidence/contentDesc stepper).
Gotchas: `adb shell input tap` on anything that removes/changes cart contents is denied by the auto-mode classifier —
the USER empties the cart between runs. Groq free tier 429s on bursts (sleep between matcher calls). CLAUDE.md deadline
line was corrected to 30 Sep this session.

## Next steps (immediate, in order — as of 2026-09-29, deadline 30 Sep 23:59 IST, ~1 day left)
0. **IMMEDIATE — live cart is dirty:** after the last T7 run the phone was left on a Zomato "Dishes loved by friends"
   sheet with the stray **Double Cheese Margherita (+ Margherita x1)** in cart. Nothing tapped by the assistant since.
   User must close the sheet + empty the cart before any further live run.
1. **Re-verify the T7/plausible-LLM/cart-evidence fixes live** (all CODED, unit-tested, NOT re-run on the phone): (a) a
   plain T2 replay from an empty cart still reaches payment (regression check on the new stricter end-of-run + LLM
   plausibility gates); (b) stage Margherita x1 in cart via one normal replay, then ask for Margherita again -> expect the
   "already in your cart, N of it. Add another, or leave it as it is?" question, nothing added. That question = the 3-pt
   T7 result. Autonomous popup-dismissal is NOT built (only the plausible-dismiss LLM path + cart-state question).
2. T12 live (say "Book a cab" -> "I haven't learned that yet ..."); T10 on a real stuck screen if one can be staged
   without logging out (e.g. launch the flow while parked on a non-Zomato screen -> "not in the app any more" path).
3. T8/T9 (second app) — GO/NO-GO decision with the user. Amazon estimated 6-8h and may be a11y-hostile; a
   Settings/Calc/Clock flow already generalises but T8 says "e-commerce". Do NOT start until T7/T10/deliverables solid.
4. Deliverables: unedited scrcpy recording in the required order (teach / exact replay / paraphrase / changed-slot /
   asks-a-question-when-stuck), README/deck (5 named architecture parts + diagrams, target-apps declaration, limitations
   incl. Zomato findings, the Home-not-deliverable address finding, the isAccessibilityTool flag, GROQ_MODEL deviation),
   fresh secrets scan of history (49d20eb no longer in repo; earlier rewrite + key rotation recorded above; re-scan
   before any push).
5. Repo push + tag `PRISM_GENAI_HACKATHON_Y2026`: WAIT for the user's explicit go-ahead.

## Session 2026-09-29 (live) — full-autonomy pass, ~31h to deadline. VERIFIED = watched live on the phone this session.
Setup this session: phone back on adb (battery 63% and actually charging), backend+mongo containers healthy, `adb reverse
tcp:3100 tcp:3100` re-set (app URL is http://localhost:3100, so it rides the USB tunnel — survives even airplane mode).
VoiceService was NOT running on connect (only the a11y service was) → started it by tapping our own `startVoice` button via
the debug channel (NOTE: debug `tap`/`check` match resourceId/text EXACTLY, so pass the FULL id
`com.tva.teachable:id/startVoice`, not the short name). WS then connected. Live harness for this session: send commands with
`adb shell am broadcast -a com.tva.DEBUG --es cmd say --es text '<utterance>'` (same pipeline as speech), read the spoken
reply from `adb logcat -s TVA:I | grep assistant`.

VERIFIED live this session:
- **T12** (unknown intent): typed "Book a cab" → "I haven't learned that yet. Show me once: do it on the screen, then say
  done." and teach mode opened (never forced into a flow). Backend logged the expected `learn failed` (slots item=cab,
  actionable 0) when the empty demo was ended. **UX GAP FOUND:** saying "done" with no recorded steps does NOT cleanly cancel
  teach mode — it replies "I couldn't find "cab" in what you did. Let's try again." and stays in teach mode. The only exit is
  a WS reconnect (verified: tapping SAVE BACKEND URL forces stopService+restart → teach state cleared). A judge who says
  "Book a cab" and then declines to teach is stuck in teach mode. **FIXED + VERIFIED LIVE this session** (commit a954539):
  backend now handles cancel/never-mind/forget-it/no while teaching → sends `teach_cancel`, phone stops the recorder and
  clears teach state; wording on an empty/failed demo no longer implies auto-retry. Live: "Book a cab" → offer to teach →
  "never mind" → "Okay, I've stopped. Nothing was saved." → next command ("Did the last run succeed?") answered normally
  (not swallowed by teach mode). Required an APK rebuild+reinstall (a11y service survived `-r`; VoiceService restarted).
- **T10 real wrong-app trigger** (new `tools/flows/t10_wrongapp_probe.json`: appPackage=Zomato, single tap step, NO launch
  step): parked on Settings, ran it → step 0 ok=false, **llm=false (nothing tapped)**, err = "I'm not in the app any more,
  this screen belongs to settings. Can you take me back to it so I can continue?", ~11s (< the 30s bar), no loop. A genuine
  foreground-mismatch trigger, stronger than last session's synthetic missing-control probe (which stays valid too).

STILL BLOCKED ON ADITYA (physical / real-account, per standing rules):
- **Priority 1 — T7 re-verification (the 3 coded-but-unverified fixes):** needs (a) the cart emptied (last session left a
  stray Double Cheese Margherita + Margherita x1 on a "Dishes loved by friends" sheet) and (b) explicit clearance to run
  order replays that add to cart (memory: ask-before-ordering). Once cleared: plain T2 from empty cart (regression on the new
  stricter end-of-run + LLM-plausibility gates) → then stage Margherita x1 and re-order Margherita → expect "…already in your
  cart, N of it. Add another, or leave it as it is?", nothing added.
- **Priority 3 extra — airplane-mode network-stuck T10:** would also need order-replay clearance; the wrong-app trigger above
  already gives a strong real T10, so this is optional.

DELIVERABLES progress this session (phone-independent, done without waiting):
- **README (5a + part of 5b) DONE** (commit 8332c6d): new "Target apps" declaration (Zomato primary; Settings/Clock/
  Calculator secondary; explicit "nothing is hard-coded per app"); a "The five parts" subsection naming
  speech-to-intent / UI-tree capture / generalisation / slot extraction / replay as the eval spec requires; "Known
  limitations" expanded with the real Zomato findings — isAccessibilityTool flag, Home-not-deliverable delivery range,
  per-row ADD anchoring + contentDesc labels + the T7 look-alike/false-success bugs, the OEM gesture-callback workaround,
  and the scrcpy/on-device-recording-blocked note.
- STILL TODO on deliverables: **deck** (submission/*_Presentation.pptx) needs the same Target-apps/five-parts/limitations
  text and its slide-7/8 photos swapped for real frames from the NEW video (so partly blocked on the video); **demo
  video** = the big remaining item, one unedited scrcpy take, needs Aditya live at the phone; **fresh secrets scan** of
  history before any push (49d20eb already purged in the earlier rewrite; re-scan to be safe).

REBUILD STATE: APK rebuilt+reinstalled this session (has the T12 cancel fix); backend image rebuilt (`up -d --build`) so
the running container has the session.js changes. 27 backend tests pass. Android tests not re-run after the one-line
VoiceService change (pure message-handler addition, no logic touched). Phone currently: VoiceService running, WS connected,
foreground left on our MainActivity. adb reverse tcp:3100 set. Media stream still MUTED — unmute before recording.


- Live demo-video recording session with Aditya (blocks final video + possibly deck slide 7 photos).
- GitHub repo URL — Aditya has not created it / not shared it yet; no remote configured in this repo.
- `CLAUDE.md` filename still contains "CLAUDE" — flagged to Aditya, no decision yet on renaming.
- Phone quirks to remember for the live session: screen locks after a bit (must stay unlocked/awake for recording);
  media volume defaults to 0; a stray Airplane-mode toggle happened once mid-session (cause unknown, just check the
  status bar); typed "yes"/"no" are NEW commands not confirmation answers — the sensitive-step gate must be answered
  with the overlay Yes/No buttons or spoken "yes"/"no" while `awaitingConfirmSpeech` is true (i.e. right after the
  "Confirm?" prompt, not via the debug `say` broadcast channel).
