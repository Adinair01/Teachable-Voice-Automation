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

T7 RE-VERIFICATION (Aditya cleared order replays + said cart was empty):
- **T7 fix #1 (no look-alike / no duplicate) VERIFIED LIVE.** Replayed "Order a Margherita pizza from Domino's on Zomato";
  at the add step the flow found a Margherita already in the cart and stopped 7/12 with: "\"Margherita pizza\" is already
  in your cart, 1 of it. Should I add another one, or leave it as it is?" — it did NOT add a duplicate or a look-alike, did
  NOT report false success, did NOT loop. This is a Replayer-side stop (RunLog kind=replay success=false; no hanging pending
  to answer). **T7 fix #3 (contentDesc stepper) also confirmed** — it read the cart count "1 of it" correctly.
- **T7 fix #2 (empty-cart happy-path regression / false-success gate) — in progress.** First empty-cart attempt exposed a
  NEW real bug (below); fixed; needs one more empty cart to confirm the full happy path reaches payment.
- **NEW BUG FOUND + FIXED (commit ca5f7ae): post-add upsell hid the quantity stepper.** From a truly empty cart the exact
  replay added the Margherita fine (10/12) but stopped at step 11 (set_quantity) with "couldn't find the quantity control
  for Margherita pizza" — Zomato had dropped an "Add items worth ₹85 or more… Continue" upsell ON TOP of the cart, hiding
  the stepper. (This upsell is non-deterministic — last session it didn't appear here, so T2 passed 12/12 then.) Confirmed
  by hand: `check` said the Continue button is ScreenGuard-"allowed"; tapping it revealed the cart with the Margherita
  stepper (count 1). Fix: `Replayer.passInterstitial()` taps a benign Continue/Skip/Got-it control (regex `INTERSTITIAL`)
  ONLY when ScreenGuard clears it (so a real "Continue to pay" is still handed over), bounded to 2 passes; wired into
  `quantity()` and `choose_address()` before they give up. Kotlin tests: ReplayerSlotsTest 6→8 (upsell-hides-stepper,
  upsell-hides-address-header), all pass; APK rebuilt+reinstalled.
- **UPSELL FIX + T5 VERIFIED LIVE END-TO-END (fresh empty cart, Aditya's OK, "T5 only").** Ran "Order two Margherita pizzas
  from Domino's on Zomato": raw RunLog = success:true, handover:payment, **12/12 steps, all clean (no LLM fallback)**. Phone
  spoke "You're at the payment step. Your turn: I don't touch payments, so please review and pay yourself." set_quantity
  passed the upsell and stepped to 2 (its contract only returns ok when count==want), reached the "PAY USING" screen, handed
  over — T11 boundary held. This single run closes T7: fix #1 (no look-alike, verified earlier), fix #2 (false-success gate —
  success:true only because real cart/payment evidence was reached, subsuming the T2 qty=1 regression), fix #3 (contentDesc
  stepper), AND the new upsell fix. **T7 = DONE. T5 = DONE (re-confirmed). T11 = re-confirmed live.**
- LIVE STATE AFTER THIS RUN: cart holds **Margherita x2**, phone parked on the Zomato payment screen (handed over, NOTHING
  ordered). User to clear the cart before the next live run / the video.
- **ENV FINDING (matters for the judged run): device location must be ON.** It was off (location_mode=0) at session start, so
  Zomato cold-started onto a "Device location not enabled / Select a saved address" interstitial and the flow stopped at
  step 2 unable to find the search bar — the assistant asked a correct specific question ("I can't find \"Search…\". This
  screen shows Device location not enabled, Enable, Select a saved address…"), tapped nothing, didn't loop (good stuck
  behaviour, but it blocks a clean order run). Enabled it (`cmd location set-location-enabled true`, mode now 3); Zomato then
  showed its normal home with the search bar and kept the "Work" address (GPS did not hijack it). LEFT ON (needed for Zomato).

DELIVERABLES progress this session (phone-independent, done without waiting):
- **README (5a + part of 5b) DONE** (commit 8332c6d): new "Target apps" declaration (Zomato primary; Settings/Clock/
  Calculator secondary; explicit "nothing is hard-coded per app"); a "The five parts" subsection naming
  speech-to-intent / UI-tree capture / generalisation / slot extraction / replay as the eval spec requires; "Known
  limitations" expanded with the real Zomato findings — isAccessibilityTool flag, Home-not-deliverable delivery range,
  per-row ADD anchoring + contentDesc labels + the T7 look-alike/false-success bugs, the OEM gesture-callback workaround,
  and the scrcpy/on-device-recording-blocked note.
- **Fresh secrets scan (5d) DONE — CLEAN.** Scanned ALL history for key-shaped strings (OpenAI sk-, Groq gsk_, Deepgram
  Token/40-hex, Google AIza, Slack xox…): zero hits. `backend/.env` was never committed and is gitignored; every historical
  version of `backend/.env.example` has empty key values (only non-secret match: an old `GROQ_MODEL=llama-3.3-70b-versatile`
  model name). The earlier history rewrite holds. Safe to push whenever Aditya gives the go-ahead.
- STILL TODO on deliverables: **deck** (submission/*_Presentation.pptx, 11 slides) is currently ENTIRELY Calc/Clock-centric
  (predates the Zomato work) — needs a Zomato-PRIMARY rewrite: slide 5 to name the five parts explicitly, a Target-apps slide
  (Zomato primary / Settings-Calc-Clock secondary), metrics refreshed with the T1-T6 Zomato results, limitations updated with
  the Zomato findings, and slide 4/7 photos swapped for real Zomato frames. Best done in ONE pass AFTER the video (needs its
  real frames + narrative), so deferred — a piecemeal text patch into a Calc-centric deck would be incoherent. Use the pptx
  skill; eyeball for overflow (LibreOffice headless convert-to-pdf + PyMuPDF, as before). **demo video** = the big remaining
  item, one unedited scrcpy take, needs Aditya live at the phone (unmute media first — still muted; clear the cart first).

PRIORITY-LIST STATUS at end of this autonomous pass (deadline 30 Sep 23:59 IST):
1. T7 re-verify — DONE (3 fixes + new upsell bug, all verified live; T5 12/12 handover payment).
2. T12 — DONE (verified + cancel soft-lock fixed & verified live).
3. T10 real trigger — DONE (wrong-app path verified live; synthetic missing-control probe still valid from before).
4. T8/T9 — SKIPPED per Aditya's decision (protect deliverables).
5. Deliverables — 5a (limitations+target-apps) DONE, 5b README DONE / deck TODO, 5c video TODO (needs Aditya), 5d secrets scan DONE.
6. Push + tag — waiting on Aditya's explicit go-ahead.

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

## Session 2026-09-30 (reconstructed from git on 2026-10-18 — this file was never updated that day)
Submission was COMPLETED and PUSHED. `main` == `origin/main` == `1f9c5d7` on
https://github.com/Adinair01/Teachable-Voice-Automation ; tag `PRISM_GENAI_HACKATHON_Y2026` created AND pushed.
Work done that day (see git log): multi-word product/dish slots (`corn and cheese pizza` no longer collapses to
Margherita, num1/num2 splitting kept for calculator); Nova-3 keyterm prompting so STT stops hearing "Tomato"/"farmer's";
generalise links a partial search typing ("mar", "dom") to the item/restaurant slot so replay types the FULL requested
value (fixes changed-slot when the demo tapped a suggestion after a few letters); limitations made honest (T6 = weak
pass, no real address switch ever observed; T7's 6-pt autonomous path = demonstrated, not forced); architecture diagram
in the deck; demo video recorded; AI disclosure + demo-video link added. Commit a5b5963 DROPPED the internal working
docs from the repo, which is why CLAUDE.md / HANDOFF.md / log.md are now untracked local-only files.
`submission/` holds: Theme3 PDF, Presentation.pptx, Demo_Video.mp4, AI_Disclosure.pdf. NOTE: the four filenames are not
uniform ("SRM IST, CHENNAI_Cipheras" vs "SRM_University_Kattankulathur_Chennai_Ciphera_s" vs "LangAI3.0_..._Cipheras").

## Session 2026-10-18 — SHORTLISTED for the next round. Groq model ladder (multi-model failover)
Aditya: shortlisted; goal now is accuracy, plus "when one Groq model rate-limits, switch to another Groq model, and if
one hallucinates the other takes it up" — swapping between the GPT and Qwen models on Groq.

MODELS ACTUALLY SERVED on this key (verified live via /openai/v1/models, 2026-10-18): chat-capable = `openai/gpt-oss-20b`,
`openai/gpt-oss-120b`, `qwen/qwen3.8-27b` (+ gpt-oss-safeguard-20b, allam-2-7b, prompt-guard, whisper, orpheus).
NO Llama of any kind — still decommissioned on this account. Qwen + 120b both verified to honour `response_format:
json_object` and answer a real pick-element prompt correctly (qwen 0.17s, 120b 0.5s).

BUILT (all in the one place every LLM call already goes through — no per-prompt changes):
- `config.js`: `GROQ_FALLBACK_MODELS` (default `qwen/qwen3.8-27b,openai/gpt-oss-120b`) + exported `modelChain()`
  -> `config.GROQ_MODEL_CHAIN` = primary first, de-duplicated, blanks dropped. `GROQ_MODEL` keeps its old meaning.
- `groq.js` rewritten around a per-model `attempt()`. A model is abandoned and the NEXT model asked IMMEDIATELY on:
  429 (that model's quota), 5xx, 404 (decommissioned), 400 (JSON mode unsupported), timeout/network drop, malformed
  or non-JSON output, or output the caller rejects as invented. It does NOT sit out `Retry-After` while another model
  is free — that was the whole point. 401/403 is the ONLY fatal status (a bad key answers the same from every model).
  Only an exhausted ladder waits out `Retry-After` and runs the chain once more. Logs every swap + which model served.
- New `validate` hook so a schema-valid-but-INVENTED answer counts as a hallucination and gets a second model:
  `pickElement` rejects an index it never sent, `matchFlow` rejects a flowId it never sent.
- **Graceful degradation (important, caught by the existing tests):** when EVERY model returns a rejected-but-well-formed
  answer, groqJson hands the last one back instead of throwing, so `pickElement` can still return `index=null`
  ("ask, don't tap") and `matchFlow` `flowId=null` ("not learned yet"). A throw there would have broken T12 and turned
  a specific question into a generic error — exactly what the eval spec forbids. `index=null` / `flowId=null` are
  treated as REAL answers and stay final: no fallback model may talk us into tapping what the first correctly refused,
  or force an unknown command into an existing flow.

VERIFIED: `npm test` = **38 pass / 0 fail** (was 27; +11 new in `test/groqFallback.test.js`). Live: forcing the dead
`llama-3.3-70b-versatile` as primary -> 404 -> **Qwen answered correctly, 375 ms total**. Live matchFlow on the real
Zomato flow: "Get me a margherita from dominos" -> matched; "order two farmhouse pizzas..." -> item=farmhouse pizzas,
quantity=2; "Book a cab" -> flowId null (T12 holds). README limitation rewritten to describe the ladder.

NOT verified: Docker was NOT running this session, so the Mongo-backed `npm run smoke` and any on-device/live-phone run
were NOT re-run. The APK is untouched (backend-only change) but the backend container needs `up -d --build` before the
next live run. Nothing has been committed/pushed for this session yet at the time of writing.

### Next steps (next round)
1. Start Docker, `docker compose up -d --build`, run `npm run smoke`, then one live phone run to confirm the ladder in
   the real pipeline (watch for the "switching model"/"answered on a fallback model" log lines).
2. Accuracy work for the next round — decide WHAT the judges will test this time before optimising blind. Candidates:
   a quorum/second-opinion mode for pickElement (ask 2 models, act only on agreement) — but weigh it against the
   never-tap-blindly rule; T6 real address switch (still only a weak pass); autonomous popup dismissal (not built —
   only the plausible-dismiss LLM path + cart-state question exists); T8/T9 second app (skipped last round).
3. Re-check the submission filename inconsistency above if the next round reuses those artifacts.

## Session 2026-10-18 (later) — teach→replay audit + the wrong-row fixes
Docker: up and healthy this session (backend + mongo, 3100->3000). Phone: NEVER visible — `adb devices` empty all
session and `system_profiler SPUSBDataType` showed NO Android device on USB at all (charge-only cable or USB debugging
off), despite Aditya saying it was connected. So NOTHING was verified live this session; all results below are unit
tests (68 Kotlin / 38 backend, all green) plus real Groq/generalise calls.

AUDIT of the teach→replay pipeline (asked for before any change):
- CAPTURE keeps selector (id/text/desc/class) + typed value + row captions + expect.packageName; no coordinates,
  password fields and whole credential screens refused. THROWN AWAY: (a) the screen itself, so the backend cannot tell
  "the only ADD" from "one of twelve"; (b) row context is CONDITIONAL — `Recorder.rowContext()` returns empty unless
  the same control is already on screen twice, so a demo done right after a search records no row; (c)
  **`expect.textContains` is dead** — read by Replayer, in the Mongo model and route validation, NEVER written by
  anything, so per-step verification is app-level only.
- MATCHING: id > text > desc > class, clickable-pool first, `near` row anchor, 6s poll + one 3s retry, 3 scrolls, LLM
  picker capped at 2 and gated by `plausible()` + DESTRUCTIVE blocklist. There was NO confidence notion: `find()` ended
  in `hits.firstOrNull()`, so equal matches were resolved by tree order.
- SLOT LOGIC IS **NOT** ZOMATO-OVERFIT (checked by running the real generalise.js): pure string work — loose
  punctuation-insensitive match, longest-word fallback, >=3-char prefix rule. The Zomato shape is confined to the
  ordering machinery (CART_EVIDENCE, ADDRESS_HEADER, INTERSTITIAL, CartState), English-only, cleanly separable.
  Wart: tapping the suggestion "Domino's Pizza" learns `text:"{{restaurant}} Pizza"`, which resolves to nonsense
  ("Pizza Hut Pizza") for another restaurant and survives only via the slot-substring fallback underneath it.

NEW HARNESS `TeachOnceGeneraliseTest.kt` — teach ONE demo, replay it against 9 screens it never saw. Results: exact
replay, a dish never demonstrated, spoken shorthand ("Farmhouse" vs "Farmhouse Pizza"), a reordered menu with a
look-alike ("Double Cheese Margherita" first), a dish below the fold, an absent dish, and a single-result menu all
PASS. So generalisation is now *demonstrated*, not asserted.

THE BUG IT FOUND (one root cause, two symptoms) — commit 3f1a07c, FIXED:
`RowContext.of()` climbed while no OTHER control of the same kind was in the subtree, so with a control that does not
repeat nothing stopped the climb and **the whole screen became that control's row**. Proven directly:
`ROW TEXTS for the only ADD = [Margherita Pizza, −, 1, +, Farmhouse Pizza, ADD]` → `PICKED = Farmhouse's ADD`.
Live consequence: ask for a Margherita that is ALREADY IN THE CART (its row shows a stepper, so the only ADD belongs
to another dish) → **adds Farmhouse and reports success**. It never bit on the real phone because a live Zomato menu
has many ADDs so the climb stopped early; it bites exactly in the pre-existing-cart and searched-to-one-result cases,
i.e. T7's territory.
- Fix 1 (`RowContext.rowOf`): the climb now also stops at the scrollable list and at the root. Deliberately
  conservative — the row is still FOUND (not refused), so T7's "already in your cart, 1 of it" question still fires
  and a single search result still works (harness case 9 guards that).
- Fix 4 (`SelectorMatcher.findTarget`): hits sharing one clickable node = the same control seen twice, still acted on;
  hits spread across several clickable controls = real ambiguity → null, so the caller asks. `findTarget` gained a
  `tree` param because `nodes` is a filtered pool and structure must be read from the whole screen.
- BEHAVIOUR CHANGE (two confident taps became questions):
  before: Margherita already in cart → adds Farmhouse, success
  after : "\"Margherita pizza\" is already in your cart, 1 of it. Should I add another one, or leave it as it is?"
  before: taught on a one-result screen, replayed on a crowded menu → adds the first dish, success
  after : "I can't find \"ADD\". This screen shows \"Margherita Pizza\", \"₹109\", \"ADD\", \"Farmhouse Pizza\". What should I do?"

APK REBUILT this session with both fixes (`app/build/outputs/apk/debug/app-debug.apk`, 6.8M) but NOT installed —
no device. Nothing pushed; origin/main is still at 1f9c5d7 (local main is 3 commits ahead: a951121, 84b4700, 3f1a07c).

### Next steps
1. **Get the phone visible** (data cable, USB debugging on, accept the RSA prompt), then `adb install -r` the APK,
   `adb reverse tcp:3100 tcp:3100`, start VoiceService via the debug channel (FULL id `com.tva.teachable:id/startVoice`),
   unmute media, confirm device location is ON. Then re-run T2/T5/T7 live — the T7 pre-existing-cart case is the one
   that now takes a different path, so it is the must-retest.
2. Still UNFIXED from the audit, both needing an APK rebuild + live check: capture the row EVERY time, not only when
   the control repeats (kills the case-8 class of flow at the source); populate `expect.textContains` so the dead
   verification path becomes real.
3. Optional: promote `passInterstitial()` to a pre-step check for deterministic popup handling (T7's popup half).

## Session 2026-10-18 (phone connected) — fixes 1-4 done and VERIFIED LIVE on the device
Device: RMX3771 on USB, a11y service enabled, `adb reverse tcp:3100 tcp:3100`, backend+mongo healthy.
Commits: 3f1a07c (fixes 1+4, unit only) then 30b8007 (fix 4 corrected + fixes 2+3 + extras, verified live).
Totals: **74 Kotlin + 41 backend tests pass.** APK rebuilt and installed with everything.

**FIX 4 WAS WRONG AND LIVE TESTING CAUGHT IT.** Refusing any tap matching several clickable controls was too blunt:
the real Zomato search screen carries "Domino's Pizza" THREE times (suggestion list #27, carousel #118, a third
section #216), each under its own clickable row, so step 3 of the taught flow would have started asking instead of
proceeding — a regression, not a fix. Corrected rule: refuse only when the selector does not IDENTIFY one control —
matched on a reused resourceId/className, or on a caption that names an ACTION ("ADD", "+", "add to cart") rather
than a thing. A naming caption means the app is repeating its own content and the first hit is the one the demo used.
LESSON FOR THE LOG: never ship a matcher change on unit tests alone; Zomato repeats captions in ways no fake tree does.

LIVE EVIDENCE (read-only `check`, nothing tapped, cart untouched) on the Domino's menu with 2 ADDs (#212 Veg,
#379 Non-Veg):
- two ADDs, no anchor -> `no target` (refused)            | anchor "…- Veg" -> #212
- anchor "…- Non Veg" -> #379                             | anchor "Margherita Pizza" (not on screen) -> refused
- reused id + desc ADD, no anchor -> refused              | "Domino's Pizza" -> #31, the top suggestion (allowed)
Also verified `RowContext.rowOf` against the REAL 288-node menu tree: the single ADD #279 resolves to row #240, whose
captions are exactly one dish card ('Big Big 6in1 Pizza - Non Veg', ₹2199, …) — NOT the whole screen. Bounded both by
the scrollable list at depth 10 and by MAX_UP.

FIX 2 (always capture the row + candidates count) — VERIFIED LIVE by recording a real demo on Settings:
recorded tap on "Battery" came back with `candidates: 1`, `context: ['Battery']` (before the fix this was `[]`), and
`screen: ['Navigate up','Battery','Charging…','Battery mode',…]`. generalise then produced selector text
`{{section}}`, slotRef=section. A one-ADD Zomato demo now yields `near:{{item}}`; "View Cart" (unique + distinctive
caption) still gets NO anchor, so no regression.

FIX 3 (expect.textContains) — was DEAD code: read by the Replayer, in the Mongo model and route validation, written
by nothing. Recorder now records the following screen's captions; a slot-bound tap stores the BARE token
(`{{restaurant}}`), never the demo's wording, and `Replayer.finish` resolves slots and compares
punctuation-insensitively. Verified live in a PASSING run (below).

EXTRAS found while verifying, both real:
- `&` now normalises to `and` in `RowContext.norm` and in generalise's matcher, so the menu's "Corn & Cheese Pizza"
  matches a spoken "corn and cheese pizza". It previously could NOT (norm dropped the "&" and left "and" in the
  spoken form), which broke row anchors and screen checks for every `&` dish. This is the "corn and cheese" family of
  bug from the 2026-09-30 session, at the matching layer.
- A slot-bound tap whose target is BELOW THE FOLD now scrolls to find it (previously only row-anchored taps scrolled).
  Found by a live run: taught "Battery", asked for "Apps" -> correctly asked a specific question and tapped nothing;
  after the fix the same run succeeds.

**LIVE TEACH-ONCE END-TO-END (the headline):** recorded ONE real Settings demo ("Battery") with real touch events,
generalised it through the real backend, then replayed the same flow with values never demonstrated:
- `section=Wi-Fi` -> success, 2/2 steps, no LLM fallback, landed on com.oplus.wirelesssettings, expectation verified
- `section=Apps`  -> success after the scroll fix (below the fold), landed on the Apps page
Probe flow kept at `tools/flows/t_generalise_probe.json` (same spirit as the t10_/t11_ probes).

GOTCHA worth remembering: `adb shell input tap` at coordinates read from the a11y tree hit the SOFT KEYBOARD (typed
"e" into Zomato's search, giving "pizzae") because the IME overlays the app window and the dump does not show it.
Dismiss the keyboard before any coordinate tap. Nothing was added to the cart. Zomato's ADD carries its label in
contentDesc with EMPTY text (id `text_view_title`), so probe with `--es desc ADD`, not `--es text ADD`.

NOT DONE / still open:
- The Zomato ORDER replay (T2/T5/T7 end-to-end) was NOT re-run: it adds to cart, and the standing rule is to ask
  Aditya first. Cart currently holds 1 item from an earlier session. This is the one remaining live gap.
- Audit item 5 (promote `passInterstitial()` to a pre-step check for deterministic popup dismissal) not built.
- Wart unchanged: tapping a suggestion learns `text:"{{restaurant}} Pizza"`, nonsense for another restaurant, saved
  only by the slot-substring fallback beneath it.
- Nothing pushed; origin/main still at 1f9c5d7, local main 4 commits ahead.

## Session 2026-10-18 (cont.) — paraphrase robustness, MEASURED; one false-success bug killed (commit 12e50be)
Goal from Aditya: "if I change my phrase but the meaning is the same, it must still understand and execute", and use
what has been said before. Approach: measure first, then fix what the measurement shows.

EVAL (`backend/scripts/paraphrase-eval.mjs`, now 32 labelled cases over the two REAL taught flows — the Zomato order
and the calculator): free rephrasings, changed slots, must-not-match, too-vague. Two upgrades to the harness itself:
it now applies `normaliseSlots` exactly as session.js does (so it scores the slots the flow is really run with, not raw
model output), and takes `--flows <file.json>` so it runs with no database reachable (Mongo is not published to the
host; dump with `docker compose exec -T mongo mongosh tva --quiet --eval 'JSON.stringify(db.flows.find().toArray())'`).
**Result: 32/32 match AND slots.** Stability across 3 runs: 31 of 32 cases identical; the only mover is "get me a
burger", which flips between teach-mode (T12) and "which burger?" (T13). Both are safe and neither taps anything, so
the eval now accepts either and fails only if a dish is silently chosen — that is honest scoring, not a loosened bar.

**FALSE SUCCESS FOUND LIVE — the worst class of bug, now fixed.** Running the real taught calculator flow with
"what is 12 plus 7" reported **"Done. 6/6"** having pressed only "+ 7 =". No key is labelled "12", so step 2's target
was missing, and the `skipped: not shown this time` shortcut (meant for a demo-time upsell) saw the NEXT step on
screen and skipped the step. Fix: a step with a `slotRef` is NEVER skipped that way — an interstitial is app chrome
and is never slot-bound, whereas a missing slot target means the flow cannot do what was asked. Pinned by
`KeypadAndSkipTest`. Worth remembering: this is why "6/6 steps" in a RunLog was not proof of anything.

NEW: a multi-character slot value is entered ONE KEY AT A TIME when no single control carries the whole value (the
demo tapped the single key "5"; "12" is two keys). Digits only, and every key must be on screen BEFORE any is pressed,
so it can never half-enter a number (a half-typed number computes something nobody asked for).

SLOT LEAKS fixed (both would have been typed into a search box):
- the APP is never a slot value — "buy a margherita pizza on zomato" was extracting restaurant="Zomato"; now left
  unset so `withDefaults` supplies the taught "Domino's".
- filler/politeness words are stripped deterministically in `normaliseSlots` (`trimFiller`) as well as discouraged in
  the prompt, because "do order a margherita from dominos na" was extracting restaurant="dominos na". Guarded so
  "naan bread" survives and a value that is only filler is kept as-is.

NEW FEATURE — the system remembers how YOU phrase things (`backend/src/services/phrasings.js`): wordings that have
already replayed successfully are handed to the matcher per flow (`alsoSaidAs`, max 3, de-duplicated, successful
RunLogs only so a bad match can never be fed back). Isolated proof: "my regular please" and "do the needful" match
NOTHING on their own, and both match the Zomato flow once they are in the history. `pickPhrasings` is pure and
unit-tested; `pastPhrasings` is the thin DB wrapper.

LIVE ON THE PHONE this session (calculator flow — deliberately chosen: no cart, no money):
- taught "Add five and three" -> "what is 12 plus 7"        -> formula "12+7", result **19**, 6/6
- taught "Add five and three" -> "can you sum 45 and 68 for me" -> formula "45+68", result **113**, 6/6 (both slots
  multi-digit, both entered key by key)
Backend container rebuilt (`up -d --build`) since session.js changed and the image has no source mount.
Also: `testOptions { unitTests.isReturnDefaultValues = true }` in app/build.gradle.kts — unit tests were dying on
`android.util.Log` as soon as a logging replay path was exercised.

TOTALS: **78 Kotlin + 46 backend tests pass.** Local main is 6 commits ahead of origin/main (still unpushed).

### The one remaining gap
The **Zomato order replay end-to-end (T2/T5/T7) has still not been re-run**, because it adds to cart and the standing
rule is to ask Aditya first. Everything in it is verified in pieces (matcher refusals live via `check`, generalisation
live on Settings, paraphrase matching via the eval), but not as one run. The cart still holds 1 item from an earlier
session. This is the last thing to do before the round.

## Session 2026-10-18 (cont.) — LIVE Zomato run with Aditya's clearance (commit 746b280)
Aditya cleared clearing the cart and running the order flow. Payment was never touched.

ENVIRONMENT SURPRISE THAT CHANGES THINGS: enabling device location moved Zomato's delivery address to
**Kaverappa Layout, Kadubeesanahalli, BENGALURU**. Saved addresses now read Work 254 km, Hotel 23 km, Home 992 km —
the phone is nowhere near the Chennai "Work" address the flow was taught against. Left on the GPS location, because
restaurants there are actually deliverable; the address slot is optional so no step touches it unless asked. T6
(address switch) CANNOT be meaningfully tested from here — Work is far outside delivery range.
Cart hygiene: Zomato keeps a SEPARATE cart per restaurant and shows abandoned ones on the home feed (Peri Peri Masala
Fries at Boba bhai, Crispy Chicken Burger, Double Chicken Roll, Hot Chicken Wings, Brix and Barrells). Removed the
active one via the cart bar's "Remove"; the rest are per-restaurant leftovers. Zomato's cart bar label is in
contentDesc with EMPTY text (so probe with `--es desc`, like ADD).

FOUR MORE REAL BUGS, all found only because this ran on the phone — fixed, 84 Kotlin tests pass:
1. **An ADVERT counted as the pay bar.** Zomato's home feed carries a banner captioned "Order Now ›"; `readyToPay`
   matched any non-editable caption, so a run that never reached a cart could finish on the HOME screen and report
   the order as ready to pay — a false success. Requiring the caption to be tappable does NOT separate them (the ad
   banner is clickable, verified). It now also requires checkout trappings (bill details / item total / pay using…).
   `readyToPay` is only a success criterion — `screen()` and `action()`, the actual T11 gates, are untouched. The
   Replayer's duplicate `CART_EVIDENCE`/`cartHandover` was deleted; the guard subsumes it. Verified live:
   `guard` on the home screen now says `readyToPay=null` (it said `"Order Now ›"` before).
2. **launch() raced.** It waited for "is this package in front", already true when Zomato was open on a restaurant
   page, so the flow looked for the home search bar on the old screen and died at step 3. Now waits for the app to
   really restart (the launch clears the task, so the screen changes) and then to settle.
3. **Fix 4 was refusing Zomato's home search bar.** Home has THREE `search_edit_text` nodes (one per cached page)
   sharing one descriptive caption; because the match came from the resource id, `blind()` ignored the caption and
   refused. The caption identifies a control whichever field matched: descriptive = the app repeating itself, use the
   first; action word ("ADD") or empty = cannot aim a tap, refuse. Dead tier plumbing removed.
4. **Prices tied captions to the demo.** "Add item ₹114" never matches "Add item ₹149". Money amounts are dropped
   before comparing (digits inside a name survive, so "Big Big 6in1" is safe).

**THE LIVE RUN ITSELF — "Order a Margherita pizza from Domino's on Zomato": 7/13, stopped with a correct specific
question** ("I can't find \"Margherita pizza\". This screen shows \"Domino's Pizza\", \"1.2 km · Kadubeesanahalli\"…").
It launched Zomato, searched, found a BENGALURU Domino's and opened it, then could not reach the item.
DIAGNOSIS (verified by hand on the live screen): **the stored flow is from the OLD recorder and is overfit to the
teach-time session.** Its steps include `tap resourceId=…:id/button1` (a generic id that happened to be the menu-search
opener in the Chennai layout — it does not open the search panel here, so the typed text produced no results),
`tap text="Add item ₹114"` (a hard-coded PRICE) and `tap text="Ordered by 90+ customers"` (a transient social-proof
label). No matching improvement can rescue a demonstration that tapped a price and an advert caption.
**THE NEW MACHINERY IS PROVEN CORRECT ON THAT VERY SCREEN**: with the search results up, step 7's real selector
(`desc=ADD near="Margherita pizza"`) resolves to ADD #128, whose row is "Margherita Pizza" — correctly rejecting the
"Double Cheese Margherita Pizza" look-alike two rows down, on a city and layout never demonstrated.

### Next step (needs Aditya at the phone)
**Re-teach the Zomato flow with the current recorder.** That is the fix, not more matcher work: the new recorder
captures the row for EVERY tap, the candidate count, and the following screen's captions (so slot-bound taps verify
they opened the right thing). Teaching needs REAL finger taps — the TouchCatcher only records genuine touches, and
driving them with `adb shell input tap` is what put an "e" into Zomato's search earlier (the IME overlays the window
and is invisible in the a11y dump). Aditya demonstrating it himself is also exactly what the judges will do.
After re-teaching, re-run: exact replay -> paraphrase -> changed slot -> pre-existing-cart question.

## Session 2026-10-18 (late) — the stuck-after-Add case, and "ask → act on the answer → learn" (commit 26c6308)
Aditya ran the Zomato order himself at 21:55 IST after I stopped (RunLog 16:25Z): pizza added, then stuck at step 10.
Totals now: **121 Kotlin + 61 backend tests**. APK + backend container rebuilt and installed with all of this.

**P0 ROOT CAUSE (from the RunLog + the stored flow, not guessed):**
1. The taught flow NEVER reaches the cart. Its steps after ADD are `Add item ₹114`, `Ordered by 90+ customers` (a
   social-proof card tapped in the demo), `Got it` (a one-off dialog). Continue/View Cart was never taught; the
   auto-appended `set_quantity`/`choose_address` assume a cart that was never opened. So it also needs RE-TEACHING.
2. The question it asked was specific and good, but had NO ANSWER CHANNEL: `session.pending` existed only for the
   "which item?" clarify, so the reply ("tap Continue") hit `handleCommand`, matched no flow, and OPENED TEACH MODE.
   (Reproduced live: a 45s wait expired and the next utterance became "What are you finding exactly?" -> teach mode.)

**P1:** items 1-4 were already shipped earlier this session and are NOT redone: row-climb bound (3f1a07c), explicit
ambiguity in the deterministic matcher (3f1a07c, refined 30b8007/746b280 — `find()` has no production caller any more),
row context + candidate count always captured (30b8007), `expect.textContains` populated (30b8007). Item 5 (popup
pre-step) is NEW in 26c6308. DELIBERATE DEVIATION to approve or reverse: the generic pre-step closes only
close/skip/not now/maybe later/no thanks/got it/dismiss/later/x — NOT Continue/Proceed (they advance the flow; Continue is
literally what opens the cart on Zomato's menu bar) and NOT OK (confirms as often as it informs). quantity()/address() keep
the wider set (live evidence for the upsell). One existing test was changed because the behaviour legitimately changed
(`anUnrelatedLlmPickIsRefusedButAPopupCloseIsAllowed` now proves a popup close needs NO picker).

**P2 BUILT — ask, act, learn:**
- `StuckAsker` in the Replayer; every step that can't proceed gets a `StuckCause` (MISSING_TARGET / AMBIGUOUS / SLOT_ABSENT /
  WRONG_APP / CART_CONFLICT / REPLACE_PROMPT). Question = progress ("I've got through 8 of 13 steps") + the specific
  "I can't find X, this screen shows A, B, C" + how to answer. Ceiling 4 questions/run; silence (90s) stops; Unclear is
  re-asked, never guessed. Also asks when a flow ends SHORT OF THE CART ("what should I tap to get to your cart?").
- `AnswerParser` (closed vocabulary): tap <caption> / skip / stop / yes-try-again / unclear. "next" and bare "continue" are
  CAPTIONS, not commands. A named control is scrolled to if below the fold, then goes through ScreenGuard (payment tap ->
  handover, not tapped) and the cart-destruction check (refused even if asked). CART_CONFLICT "add another" = one tap on the +.
- Voice/typed routing: a pending question makes the mic transcript-only ("confirm" mode) and typed text the ANSWER. Typed
  text deliberately does NOT answer the sensitive-step confirmation gate.
- **CLASSIFICATION RULE (you asked me to decide and tell you):** FLOW_DEFECT only if cause==MISSING_TARGET AND the step is a
  plain tap (no slotRef, no row anchor) AND the answer is a tap or skip. Everything else is SITUATIONAL and never stored: a
  dish absent today, which of two ambiguous controls, wrong app, any cart decision. Proposed edits: tap-answer -> REPLACE
  the selector (caption-first, resourceId dropped, price stripped); skip -> mark `optional` (NOT delete); flow ran out short of
  the cart -> INSERT the tap before the quantity step.
- **Consent:** the phone never writes. Backend `services/correction.js` re-validates (refuses payment/credential/sensitive taps,
  slot-bound/anchored steps, bad indexes), speaks ONE message ("Done. 2 parts of this flow look out of date: step 2 tapped X,
  which wasn't there, and you had me tap Y; step 3 … Should I update the flow so it does that from now on? Say yes to save it,
  or no to leave it exactly as I learned it."), changes the flow ONLY on a clear yes ("yes… actually don't" saves nothing),
  and only if the flow is unchanged since the run (sha1 of its steps). Any other utterance silently drops the offer.
- New schema field `optional` (Flow model, route, Kotlin). Honoured ONLY for a plain tap with no slot — never excuses a
  missing slot target (the false-success lesson).

**VERIFIED LIVE (Settings, no cart):** `tools/probe-ask-learn.sh` — 12/12: asks; answers acted on in the same run ("tap Apps",
below the fold, scrolled to); flow unchanged while the offer is pending; yes saves (step -> Apps, "Got it" -> optional); next
run `Done 3/3` with ZERO questions. The script plants and always removes its own probe flow + run logs. It FOUND two bugs the
unit tests missed: a 45s answer wait was too short (now 90s; the timer starts before the spoken question finishes playing),
and a learned plain tap never scrolled, so the flow learned "Apps" and then asked about it every run (fixed + tested).
Mutation-checked safety: removing, in turn, the Continue exclusion, the slot-skip refusal, the payment guard on a named tap,
and the cart-destruction refusal each makes a specific test fail (one first mutated the WRONG line and "passed" — redone).

**NOT VERIFIED LIVE / NOT BUILT:**
- The Zomato flow itself has not been replayed with any of this (it adds to cart; standing rule = ask first). It is STALE and
  must be RE-TAUGHT (see below) — asking-and-learning would also repair it, one conversation per defect.
- Not asked (report-only, no answer channel): a tap that succeeded but `expect` failed ("expected screen not reached"),
  and "action failed". Quantity/address failures ARE asked (retry/stop only; skipping them is refused).
- Answers resolve deterministically on screen captions; the LLM picker is NOT used for answers (a wrong LLM guess on a user's
  explicit instruction is worse than asking again).
- `tools/replay.sh` (debug replay) has no asker, so it still reports-and-stops as before.

### WHAT TO RE-TEACH, AND WHY (needs Aditya at the phone — real finger taps, not adb)
Re-teach "Order a Margherita pizza from Domino's on Zomato" and, unlike last time: (1) do NOT tap "Ordered by 90+ customers"
or "Got it" or anything incidental; (2) after ADD / "Add item", tap **Continue / View Cart** so the demo reaches the cart;
(3) STOP at the payment screen, never past it. Why: the stored flow's tail is demo detours + no cart step, and the recorder
now captures what makes the rest generalise (row for every tap, candidate count, following-screen captions). Alternatively
replay it and answer the two questions ("tap Continue", "skip it", then "yes" to save) — that repairs the stored flow in place.

## Session 2026-10-18 (night) — the Zomato flow is REPAIRED and verified end to end (commit follows 26c6308)
Aditya: "fix it right now". Done through the new path, on the phone, with his go-ahead for the cart actions (one Margherita in
the Domino's cart, removed again afterwards; payment never touched).

**The stored Zomato flow now works from an empty cart: 13/13 steps, 0 questions, 0 LLM fallbacks, ends on the cart with "You're
at the payment step. Your turn: I don't touch payments" (RunLog success=true handover=payment, ~27s).** Step 10 was changed from
`tap "Ordered by 90+ customers"` to `tap "Continue"` — via the ask -> "tap Continue" -> offer -> spoken "yes" path, not a DB edit.
Nothing else in the flow was changed. Test pizza removed; cart left empty; probe flow absent.

THREE REAL DEFECTS the live run exposed (all fixed + tested; 127 Kotlin + 61 backend; probe script 12/12 on the final build):
1. **Typing that did not take.** First entry of "Margherita pizza" filled the box but Zomato never ran the search; a retype by
   hand worked. Step 8 then waited for results that never came (two runs died there). Now: if the step after a typing step can't
   find its target, clear the field and enter the value once more (once per step, same field, presses nothing).
2. **The cart wasn't recognised as the end.** With no delivery address chosen the cart shows "Select address at next step", not
   "PAY USING / Bill details", so a fully successful run reported "not on the cart". Added to ScreenGuard's checkout evidence
   (readyToPay is a success criterion only; the T11 gates screen()/action() are unchanged).
3. **"action failed" dead end.** A click on a node that went stale (suggestion list still refreshing) failed after 6s with no
   retry and no question. Now: look again, retry once under the same guards, and if it still can't act, ask specifically
   ("I found X but couldn't tap it. This screen shows … What should I do?").

LESSON, learned twice the hard way: **Zomato keeps the PREVIOUS page in the accessibility tree**, so a text dump of the cart still
lists the menu's captions ("Domino's Pizza", "1.2 km"). I misread cart state from dumps twice (once concluding the cart was
empty when it wasn't, once concluding the run never reached the cart when it had). Use SCREENSHOTS (`adb exec-out screencap -p`)
to judge what is really displayed. The same effect makes the spoken "This screen shows …" summary list the page UNDER the real
one — a known wart in ScreenSummary (it reads the first captions; the visible page is later in the tree). NOT FIXED.

STILL OPEN: T6 (address) can't be tested here — the GPS puts the phone in Bengaluru and the saved addresses are 23-992 km away.
The cart's "Select address at next step" is the expected end for this location. Nothing pushed (origin/main still 1f9c5d7).

## Session 2026-10-19 — "replays use the OLD taught values" (commit fb42aa6)
Aditya cleared all flows (backup: ~/Teachable-voice-flows-backup-2026-10-18.json), re-taught "Order a Farmhouse pizza from
Domino's in Zomato" and "Calculate five plus two through calculator", and reported replays reusing 5+2 / Farmhouse.

TRACE (evidence, not guesses): matchFlow re-extracts per utterance; NO cache, NO similarity shortcut, nothing re-reads the
stored flow's values. Backend sent the new values 9/9 (7/9, Margherita); the phone logged `replay slots={num1=7, num2=9}`.
ROOT CAUSES:
1. **Value shape depends on which ladder model answers.** "seven plus nine": qwen -> "7","9"; gpt-oss-120b -> "seven","nine";
   gpt-oss-20b -> "one hundred","twelve". `normaliseSlots` converted words->digits only for `quantity`, so "seven" reached the
   phone, which found no key "seven" and stopped at step 3 — exactly where the reported 7+9 run stopped (RunLog). What looked
   like "it ran 5+2" was most likely the PREVIOUS 5+2 still on the display after that stall (phone log had rotated; not proven).
   FIX: `fitToTaught` — a slot taught as a number is replayed as digits; word-taught slots untouched.
2. **The real stale-value path:** `Session.#replay` -> `withDefaults` filled EVERY missing slot from the demo. Old code, "order
   from Domino's" (no dish) -> sends item="Farmhouse pizza". FIX: `missingRequired` + `askFor` — the subject (item, numbers, any
   non-place slot) is ASKED for ("Which item should I use? Last time it was Farmhouse pizza."); only restaurant/store defaults.
`replaySlots()` in slots.js is now the one pure function between utterance and phone. Pending-answer is normalised and the
ORIGINAL command (not the bare answer) is logged, so answers aren't learned as phrasings.

NOT REPRODUCED: "added Farmhouse when asked for Margherita". All 3 models returned item="Margherita pizza" 12/12; the phone's
ADD is row-anchored to {{item}} (new test replays the real stored Farmhouse flow with Margherita -> adds Margherita). Most likely
the Farmhouse in the cart came from the TEACH demo itself (teaching performs the order for real). Not verified live: the cart
was empty when checked, and adding to it needs Aditya's OK.
Calculator "+" step is stored as `near:{{num1}}` (the display showed "5" at teach time). Verified live: resolves for 7 and 100
once num1 is on the display -> a quirk, not a cause. generalise.js untouched.

LIVE (stored calculator flow): "seven plus nine" -> 7+9=16; "one hundred plus one" -> 100+1=101; "five plus two" -> 5+2=7.
Dish-less Zomato orders now ask (matcher clarify caught both live; replaySlots is the guaranteed backstop).
Tests: 74 backend + 128 Kotlin. Same scenarios through the PREVIOUS code: 5 of 6 fail (only exact replay passed).
ENV: the phone keeps switching LOCATION OFF (found off twice) -> Zomato shows "Device location not enabled" and replays stall at
step 2. It also locked/dozed mid-session (launch then fails with "action failed"). Check both before any live run.

## Session 2026-10-19 (cont.) — pushed fb42aa6; live Zomato Margherita replay; three more fixes (local commit, see git log)
PUSHED: fb42aa6 (slot fix) -> origin/main. Submission tag PRISM_GENAI_HACKATHON_Y2026 still on 1f9c5d7. The commit after it
(scroll/launch/LLM-plausibility) is LOCAL ONLY — not pushed yet.

LIVE ZOMATO REPLAY ("Order a Margherita pizza from Domino's on Zomato" on the FARMHOUSE-taught flow):
- **The slot fix holds live:** phone logged `slots={item=Margherita pizza, restaurant=Domino's}`; screenshot shows the menu
  search filled with "Margherita pizza". Farmhouse played no part.
- **Nothing was added to the cart:** at 01:21 IST the outlet the search landed on (Domino's "New", 22.3 km, Yelahanka) had every
  ADD greyed out (closed at that hour). Step 8 tapped a disabled ADD, the add-item sheet never opened, step 9 fell to the LLM,
  step 10 ("1 item added") asked and the responder said stop. Cart verified empty by screenshot.
- **=> Live confirmation that MARGHERITA LANDS IN THE CART still needs a run during opening hours.** Unit test with the real
  stored flow already proves the row-anchored ADD picks Margherita over Farmhouse.

THREE DEFECTS FOUND BY THAT RUN (fixed + tested, 134 Kotlin; APK installed):
1. Taught SCROLL with nothing to scroll (home feed still loading) -> bare "action failed", run over. Now waits, retries once,
   carries on (next step scrolls for its own target).
2. LAUNCH that never reaches the front (phone locked) -> bare "action failed". Now a WRONG_APP question. No bare "action failed"
   remains anywhere in the Replayer.
3. **LLM fallback plausibility was dangerously loose.** It accepted a guess sharing ANY word: for "Add item ₹279" it accepted on
   "add" (every dish's ADD has it); writing the test showed "Farmhouse Pizza" accepted for "Margherita Pizza" on "pizza". Now a
   guess must contain the step's MOST distinctive word (action words, generics, bare prices ignored) — same rule as the row
   matcher. Every LLM pick is now LOGGED ("replay: llm pick for X -> "Y" (used|refused)"); before, nothing recorded what was guessed.

ENVIRONMENT NOTES (recurring, check before EVERY live run): phone LOCKS/DOZES on its own; LOCATION keeps switching OFF (found
off twice; Zomato then shows "Device location not enabled" and blocks at step 2); media volume reset to 1 after a replug (set
with `adb shell cmd media_session volume --stream 3 --set 11`); late at night many restaurants' ADD buttons are disabled.

### Next steps
1. Push the local commit (needs Aditya's OK).
2. During opening hours: rerun the Margherita replay live and confirm by screenshot that MARGHERITA (not Farmhouse) is in the
   cart, then remove it. Note the Farmhouse flow still contains demo detours (steps 10-12: "1 item added" container, coupon
   "APPLY", "Woohoo! Thanks") — expect questions there; answer "skip it" and accept the save offer, or re-teach without them.
3. Known wart still open: the spoken "This screen shows …" summary reads the page UNDER the visible one on Zomato.

UPDATE 2026-10-19: c4294e9 PUSHED (origin/main = c4294e9, nothing unpushed; tag still 1f9c5d7). Next step 1 above is done.

## Session 2026-10-21 — accuracy audit, then ALL audit rows fixed (commits d426985 backend, e0cd921 phone)
Audit first (evidence only), then Aditya approved fixing every row A–O plus N (recorder volatility). Rule held: each
fix has a test that FAILS when that fix is reverted — checked one by one (backend: new tests run on the old tree;
Kotlin: each fix's old behaviour put back, its test run, file restored). Tests 74 -> 111 backend, 134 -> 159 Kotlin.

PART 0 (stale slot values) — answer: NOT fixed everywhere before this session. fb42aa6 fixed two real paths (word vs
digit shape; withDefaults filling a missing dish). Tracing every path found FOUR more that can replay the taught value,
all fixed in d426985: (1) generalise left fragments/literals ("farm{{item}}", "{{item}}s", a respelled "Margarita",
"five" vs key "5" never templated); (2) a flow saved when a named value was never used by the demo (only "some slot
found" was required) — learn now refuses, naming it; (3) ask->learn could store "tap Margherita Pizza" as a fixed step
— refused against the run's own values; (4) teach-time slots were not normalised. The ORIGINAL Zomato symptom was never
reproduced; no saved or backed-up flow contains a literal value (scanned). The calculator one: fb42aa6's explanation.
Phone never uses a value baked into a step except where generalise failed to template (now covered).
LIVE (2026-10-21 17:17, typed command via the debug channel, not voice): "calculate 7 + 9" -> 7+9 = 16, 7/7, slots
{num1=7,num2=9}. The Margherita <-> Farmhouse swap was NOT run (adds to cart: needs Aditya's OK, and the stored flow is
stale — re-teach first).

FOUND WHILE FIXING (not in the audit):
- paraphrase-eval --flows with a mongosh dump: every id was "[object Object]", so flow choice was never measured (the
  10-18 "32/32" included). Fixed; re-measured with real ids: gpt-oss-20b primary 32/32 match + 32/32 slots; qwen
  primary 32/32 + 31/32 (dropped "three"). Qwen is 2.5x faster (median 374 vs 950 ms). RECOMMENDATION: keep GPT primary.
- matchFlow asked "Which pizza?" for "look up pizza on Google" (smoke test): only the item slot may be clarified now.
- At ~1 call / 2.5 s no model hit 429; at the eval's ~24 calls/min ~20-25% of primary calls did.

DESIGN DECISIONS TO KNOW:
- H uses the BACK KEY (a11y key-event filter, observed never consumed), not "screen looks like before": Zomato keeps the
  previous page in the tree, so screen comparison would have deleted real steps. New a11y flag canRequestFilterKeyEvents:
  the service stayed enabled after install on this phone, but whether gesture-nav Back is delivered as a key event on
  this ROM is UNVERIFIED.
- A tapped caption is templated up to the value: "Domino's Pizza" -> "{{restaurant}}", "Search in Domino's Pizza" ->
  "Search in {{restaurant}}", "burger near me" -> "{{query}}" (was "{{query}} near me"; replay picks the closest caption).
- Interruptions (APPLY, Woohoo! Thanks, Offers for you, Got it, Close…) are not recorded; dismissal ones are cleared at
  replay. A demo that needs a coupon applied will NOT replay it (deliberate).
- Groq: 5 s/model, a 429'd model is skipped for its Retry-After (next call goes straight to Qwen), no Retry-After wait in
  the session (scripts opt in with retries), "One moment." after 1.2 s, and a spoken reason if every model fails.

### Next steps (needs Aditya at the phone)
1. RE-TEACH the Zomato flow with the new recorder (real finger taps), then delete the old one. Say: "Order a Margherita
   pizza from Domino's on Zomato". Tap search -> type dominos -> tap the Domino's Pizza suggestion -> open the restaurant
   -> menu search -> type margherita -> ADD in the Margherita row -> Add item -> Continue/View cart -> STOP at the cart,
   say "done". Do NOT tap coupons/offers (they're ignored now anyway). Try one accidental tap + Back to verify H live.
2. With Aditya's OK for the cart: replay "order a Farmhouse pizza from Domino's on Zomato" and the reverse; screenshot
   the cart; remove the item.
3. Live-verify: T5 "two", T7 pre-existing cart, T10 stuck question timing (<20 s), T14 "did it work?", one VOICE command
   (keyterms + transcript stored in RunLog.heard), H Back.
4. Old Zomato flow still in Mongo (6ad5111f…, description with the stray full stop) — delete after re-teach.

## Session 2026-10-21 (cont.) — live phone fixes, all PUSHED (origin/main = b029c0c; tag still 1f9c5d7)
Commits since e0cd921: 368cc21 (teach ends at payment page; gesture Back via window return), 03dc4eb (Option B:
replay may tap EXACTLY "Proceed to pay"/"Select payment method"; every tap/type re-reads the screen and hands over on a
payment/OTP/login screen — closed a real hole), 7ab69e1 (mic cuts TTS off; backend strips the assistant's own words
from transcripts; 1-word/low-confidence never opens teach mode; text+audio sent together; "Step i/n" status),
a626ca4 (answers: verb kept, chained "…and select home", "bottom button", repeated words), d799340 ("done, thank you"
ends a run as the user's success + glad line; leftover plain steps skipped once at the cart), 57457ad + b029c0c
(core/Visibility: Android drawing order decides what is visible — screen description, answers AND step matching).
Tests: 119 backend, 178 Kotlin. Real Zomato cart dump is a fixture (android/app/src/test/resources/screens/).

LIVE FINDINGS: gesture Back reaches neither onKeyEvent nor the overlay on this ROM (Back detected by window return,
verified in Settings). Zomato menu+cart are ONE window; cart drawn at 15 over a tappable backdrop at 13 over the menu
at 4–9. Saved addresses: Work 254 km, Hotel 23 km, Home 992 km (Aditya editing Work/Home nearby — wait for his
"addresses done" before T6). CART CURRENTLY HOLDS Farmhouse ×1 AND Margherita ×1 (from old-flow runs) — Aditya to clear.

### Next steps
1. Aditya re-teaches the Zomato order by voice (to the payment page; one wrong tap + Back to check H). Then: dump the
   flow, flag volatile steps, confirm the wrong tap was dropped, delete old flow 6ad5111f.
2. Live suite in order, asking before each cart-touching run: T2, T3×2, T4 (Farmhouse = stale-slot live check), T5,
   T6 (after "addresses done"), T7, T10 (Hindi), T11, T12, T13, T14 — report pass/fail, steps, run time (<150 s cap),
   time to first spoken word, and a points table.
3. Never move the PRISM_GENAI_HACKATHON_Y2026 tag.

## Session 2026-10-21 → 22 (night) — live use with Aditya at the phone. ALL PUSHED (origin/main = 230c128; tag still 1f9c5d7)
Tests: 126 backend, 186 Kotlin, all green. Groq: developer plan (500k req/day, 250k TPM per GPT model, verified from
API headers); chain is now gpt-oss-20b -> gpt-oss-120b (Qwen removed, Aditya's choice). Backend container rebuilt.

SHIPPED THIS STRETCH (each verified live unless noted):
- Teaching: tap lag fixed with a screen snapshot + live check of the control under the finger (Zomato taps 167-252 ms,
  was 0.4-1.6 s); recorder events off the main thread; reads once per typed field. Prefetched tree reads.
  Teach panel draggable by any part (Done included) with its own saved spot. Teach ends by itself on a payment screen.
  Back after an accidental tap drops it (gesture Back detected by the window returning; verified in Settings).
  Numbers tapped key by key ("3","0") learn as one {{num1}} (calc 30÷5 taught, 45÷9=5 replayed; 75−10 taught, 90−25=65).
  A refused teach says what was heard AND what was tapped. Long product titles anchor on the item only; sale banners
  (festival/cashback/Big Billion/Prime Day…) and coupons/popups are not steps.
- Replay: Option B (Aditya): exactly "Proceed to pay"/"Select payment method" may be tapped unattended; every tap/type
  re-reads the screen and hands over on payment/OTP/login (closed a real hole). Visibility by Android drawing order
  (core/Visibility) for step matching, answers and the screen description — Zomato's cart over its menu verified on a
  live dump fixture. Leftover demo steps skipped once at the cart. Relaunch of an app already in front accepted.
  Amazon cart recognised (Subtotal / Proceed to Buy).
- Questions: echo of the assistant's own voice stripped; mic cuts TTS; text+audio sent together; "Step i/n" status;
  answers: verb kept, chained taps, positions, repeated words; "done, thank you" ends as success with the glad line;
  a NEW TASK said in answer ends the stuck run and starts it; panel "■ Stop" while a run goes.
- Connection: panel says "Not connected…" (no endless "Thinking…"), 20 s no-reply message; a 1-word/low-confidence
  command never opens teach mode.
- Tools: tools/up.sh (one command: Docker, phone, adb reverse, a11y check, voice started + status), tools/forget-flows.sh
  (backup to ~/Teachable-voice-flows-backup-*.json, then delete; --all clears run logs), tools/groq-watch.sh (rate
  limits in words), tools/keep-connected.sh (re-sets adb reverse on every USB reconnect), tools/a11y.sh status|log.

LIVE FINDINGS TO REMEMBER:
- USB: the phone is on a VIA Labs USB-C multiport adapter (USB2 hub + Ethernet); adb transport hit #38 in one day.
  Each drop removes adb reverse. Plug the phone DIRECTLY into the Mac; run tools/keep-connected.sh in a terminal.
  DON'T use "adb reconnect" on this phone: it knocked the device off USB entirely (2026-10-22 02:04) — needs a replug.
- Android's main log is 256 KB shared by all apps (set to 16 MB with adb logcat -G 16M; resets on reboot). The app's own
  log: tools/a11y.sh log.
- Zomato runs at 60 Hz regardless of teach mode (OEM); home screen stays 120 Hz in teach mode.
- The left-edge robot icon is Realme's Smart Sidebar (OplusOSEdgeFloatBar), not this app.
- Late at night restaurants are closed: Zomato replays legitimately get stuck at the restaurant step.
- Deepgram TTS has its own rate limit (hit only by a 30-command burst test).

STATE: flows saved now:
  - Order S25 ultra 12GB256GB phone from Amazon
  - Just search for s 25 ultra and search
  Both taught 01:56/01:58 IST, AFTER the Amazon fixes: no sale-banner step, cart reached. Remaining gap in the first:
  its "Add to cart" has NO row anchor, because the spoken item "S25 ultra 12GB256GB phone" is not written that way in
  any listing title (only "S25" and "Ultra" are), so replay would face several identical "Add to cart" buttons and ask.
  FIXED AFTERWARDS (ab0f05a + 230c128): teaching anchors the row when a caption shares >=2 of the item's words; replay,
  as a last resort after exact/contains, picks the row with the most shared words (>=2), a tie between different
  listings asks. This flow was taught BEFORE that fix, so it still has no anchor: delete it and re-teach. It also has a
  tap with no caption at all (step 2) — fragile; on replay it will ask rather than guess if it cannot be found. The second flow ("Just search for…") anchored
  fine (near={{query}}).
  Zomato cart may still hold Farmhouse x1 + Margherita x1 from old-flow runs (Aditya to clear).
  PHONE: knocked off USB at 02:04 by my adb reconnect test — still not visible to adb at the end of the session.
  The backend runs the latest code; the phone does NOT have the latest APK yet (built: android/app/build/outputs/apk/
  debug/app-debug.apk, includes the shared-words row match).

### Next steps
1. Replug the phone directly into the Mac, accept USB debugging, then:
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk && tools/up.sh   (+ tools/keep-connected.sh)
2. Delete the old "Order S25 ultra 12GB256GB phone" flow and re-teach Amazon from scratch, then check its Add to cart
   step has near={{item}}. Decide: should "Proceed to Buy" be tapped without
   confirmation like "Proceed to pay"? (asked, not answered)
3. Live suite during restaurant opening hours, asking before every cart-touching run: T2, T3x2, T4, T5, T6 (after
   Aditya's "addresses done"), T7, T10 (Hindi), T11, T12, T13, T14 — pass/fail, steps, run time (<150 s), first word.
4. Optional: Wi-Fi backend URL (http://<mac-LAN-ip>:3100) so the cable is not needed; or a cloud deploy (needs his OK).
5. Never move the PRISM_GENAI_HACKATHON_Y2026 tag.

## 2026-10-22 evening (demo day, ~2 h before the demo) — smarter when stuck (commit 000959c, PUSHED)
Live failure: Zomato replay stuck at the unlabelled in-restaurant search icon (id button1) with "I can't find "". This
screen shows nothing I can read", and the answer "You can search it here" was not understood. Fixed:
- LLM picker gets context (task, step n/N, steps before/after); unlabelled steps may take a clickable, safe pick.
- Visibility: never hides every readable node (blank overlay) — falls back to Android's own visibility.
- Free-form answers go to the model (before scrolling), all guards apply. LIVE: "the one with the power symbol" -> Battery.
- Popup over the cart at the end ("Dining voucher unlocked! Got it") is closed before the at-cart check.
- GROQ_PICK_MODELS: screen decisions use gpt-oss-120b first (live: 120b 3/3 vs 20b 1/3); commands stay on 20b.
NOT verified live: the unlabelled-icon guess on Zomato itself (needs a run that adds to the cart).
MY MISTAKE: a reproduction replay at ~23:00 ran all 14 steps and ADDED a Farmhouse pizza to the Zomato cart — Aditya told.
Tests: 126 backend, 190 Kotlin. Flows saved: Amazon S25 order (taught before the shared-words fix: re-teach),
"Just search for s 25 ultra", "Order margherita pizza from domino's in Zomato".

## 2026-10-23 (night before Round 2, 11:20 AM) — briefing, preflight, Part B prepared
- BRIEFING.md (untracked, do not commit): workflow, rules→code, architecture, tech choices, Samsung gaps, scalability,
  30 Q&A, measured numbers, limitations. Measured today: intent 0.44-0.99 s (median 0.70), pick-element (120b) 1.0-1.5 s
  (median 1.10); 458/488 replay steps ok, 2/488 used the LLM; APK unpacked has 0 keys/provider hosts.
- tools/preflight.sh (untracked): wraps tools/up.sh + Groq/Deepgram live check, location, DND, volume, battery, lock
  screen, front window, Zomato cart bar (best effort), scrcpy test recording. `--fix` sets location/DND/volume (the
  --fix branch NOT yet exercised). Ran clean except: location off, DND off, notification shade open.
- Screen recording: on-phone `screenrecord` FAILS on this Realme (/sdcard: permission denied; /data/local/tmp: exit 1).
  scrcpy 4.1 on the Mac WORKS: 1080x2412 video + phone audio (--audio-source=playback).
- Part B (Amazon second app by teaching only): branch `scalability-amazon` created from main 000959c, no code changes.
  BLOCKED: phone was locked; waiting on Aditya to unlock + approve the cart-touching teach and the 3 replays.
  Risk noted: existing "Just search for s 25 ultra and search" flow may be matched instead of teaching the new command.
