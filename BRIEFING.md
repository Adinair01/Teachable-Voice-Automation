# Round 2 briefing: Teachable Voice Automation

Untracked. Do not commit. Written 23 Oct 2026 from commit `000959c` (main). All tests green at that commit:
126 backend (`cd backend && npm test`), 190 Kotlin (`cd android && ./gradlew testDebugUnitTest`).

**How to read the references.** `kt/` = `android/app/src/main/java/com/tva/teachable/`. `js/` = `backend/src/`.
`HANDOFF:n` = line n of HANDOFF.md. "(my inference)" = no reason was recorded; it is my reading, not history.
Do not tell the panel an inference as if it were a decision we wrote down.

---

## 1. The workflow in easy words

### 30-second version
You show the phone a task once, by voice and taps: "Order a Margherita pizza from Domino's on Zomato".
We save the taps as steps, and mark which words can change (the dish, the quantity, the address).
Later you say "Get me two Farmhouse pizzas from Domino's". We work out which taught task you mean
and what changed. Then the phone repeats the taps itself, with the new values.
It stops at payment and hands the phone back. When it is stuck, it asks you a specific question.

### 2-minute version
1. **Teach.** You hold the mic button and say the task. Nothing is saved yet, so it says "Show me once"
   (`js/ws/session.js:21`). You do the task normally. A transparent layer catches each tap, reads what is under
   your finger, then passes the tap on to the app (`kt/a11y/TouchCatcher.kt:22`, `kt/a11y/TvaAccessibilityService.kt:134`).
   Each tap becomes a step: the button's id, text and description, plus the row it sits in (`kt/record/Recorder.kt:152-166`).
   Password fields are never read (`kt/a11y/UiTreeReader.kt:35-40`, `kt/record/Recorder.kt:121-130`).
2. **Store.** The backend turns the demo into a reusable flow. Plain string matching, no LLM: where the
   dish name you said appears in what you tapped or typed, it becomes `{{item}}` (`js/services/generalise.js:51`, `:89`, `:188`).
   For an ordering task it adds a quantity step and an address step before checkout (`js/services/generalise.js:274`).
   Saved in MongoDB (`js/models/Flow.js`).
3. **Say.** Push-to-talk. Audio streams to Deepgram Nova 3 (`js/lib/deepgram.js:5`). Names of saved tasks and installed
   apps are sent as keyterms so "Domino's" is heard right (`js/services/keyterms.js:12`).
4. **Understand.** One Groq call picks which saved flow you mean, or none, and pulls out the new values
   (`js/prompts/matchFlow.js:36`). It can only return an id we sent it (`js/prompts/matchFlow.js:50`).
   "Order pizza" with no dish asks "which pizza?" (`js/prompts/matchFlow.js:57`).
   Unknown task means "I haven't learned that yet. Show me once" (`js/ws/session.js:21`).
5. **Replay.** The phone runs each step on the live screen. It finds the target by id, then text, then description,
   then the row it sits in (`kt/core/Selector.kt:185`, `kt/core/RowContext.kt:59`). Each step gets 16 s at most, and the
   whole run 150 s (`kt/replay/Replayer.kt:53`).
6. **Check.** Before every step it checks the screen for payment, OTP, login or password (`kt/replay/Replayer.kt:193`,
   `kt/core/ScreenGuard.kt:35`). If it finds one, it stops and hands the phone back.
7. **Ask or hand off.** If a target is missing, it tries the LLM to pick an element, at most twice
   (`kt/replay/Replayer.kt:1031`). The pick must look like the step's control (`kt/replay/Replayer.kt:900`).
   Then it asks you a specific question, at most 4 times (`kt/replay/Replayer.kt:614`, `:1032`).
   Your answer can be a button name, "skip", "stop", or a new command (`kt/core/Answer.kt:67`).
   Free-form answers go to the LLM (`kt/replay/Replayer.kt:712`).
   Every run is logged, and "did the last run succeed?" reads that log (`js/services/runStatus.js:15`, `js/ws/session.js:200`).

---

## 2. Theme 3 rules, and where each is enforced

| Rule | Where it is enforced | Caveat (say this before they find it) |
|---|---|---|
| **Accessibility only: no SDKs, deep links or web fallbacks** | Reading the screen: `kt/a11y/UiTreeReader.kt`. Taps: `ACTION_CLICK` (`kt/a11y/ActionExecutor.kt:15`). Typing, scrolling and Back: `ActionExecutor.kt:18-33`. Apps are opened by their normal launcher entry (`kt/a11y/TvaAccessibilityService.kt:105`). | Opening the app by package name is a normal launch, not a deep link. The app's only permissions are internet, mic, foreground service and notifications (`android/app/src/main/AndroidManifest.xml:3-7`). |
| **No hard-coded flows** | Flows exist only in MongoDB, created by teaching (`js/services/generalise.js:188`). There is no step list in code. | Some *screen knowledge* is shaped by Zomato and Amazon: the cart-evidence words (`kt/core/ScreenGuard.kt:87-92`), plus the quantity and address helpers. See section 6. These recognise screens; they do not script taps. |
| **No credential capture** | Password text is never read (`kt/a11y/UiTreeReader.kt:40`). Password events are ignored in teaching (`kt/record/Recorder.kt:121-130`). Credential fields are dropped from flows (`js/services/generalise.js:201`). At **runtime**, payment, OTP, login and password screens are checked before every step and the phone is handed back (`kt/core/ScreenGuard.kt:35-61`, `kt/replay/Replayer.kt:193`). Payment apps are on a block list (`kt/core/ScreenGuard.kt:19`). | The check is based on English words. A login screen in Hindi would not be recognised (limitation 2). |
| **Parametrised flows** | Slot templating in `js/services/generalise.js:51-117`. Slot values at replay: `js/prompts/slots.js:150`. Quantity stepper: `kt/replay/Replayer.kt:517`. Saved address: `kt/replay/Replayer.kt:542`. Number words like "two" become 2 (`js/prompts/slots.js:78`, `:106`). | Quantity and address are only added when the command sounds like ordering (`js/services/generalise.js:145`, `:269`). |
| **Ask when stuck, never loop** | Per-step time budget and run cap (`kt/replay/Replayer.kt:53`, `:140`). At most 2 LLM picks and 4 questions per step (`:1031-1032`). A question names what is missing and what is on screen (`:614`). A guess must look like the step's control (`:900`). No destructive taps (`:385`). | The questions are fixed English templates; only free-form answers go to the LLM. |
| **Unknown intent: offer to teach** | `js/ws/session.js:245-252` | It goes straight into teach mode with "Show me once" and does not ask yes/no first. Saying "cancel" leaves teach mode. |
| **Ambiguity: ask, don't guess** | `js/prompts/matchFlow.js:57` | Only the `item` slot can be clarified. |
| **Run status ("did the last run succeed?")** | `js/services/runStatus.js:15-27`, `js/ws/session.js:200` | It reads the most recent RunLog. |

---

## 3. Architecture (redraw on the board)

```
  PHONE (Kotlin app)                                   LAPTOP / SERVER (Docker)
 ┌───────────────────────────────┐   WebSocket   ┌───────────────────────────────┐
 │ Mic button ──audio──────────────────────────▶ │ Session (js/ws/session.js)     │──▶ Deepgram Nova 3 (speech→text)
 │                                │◀──speech (mp3)─│                               │──▶ Deepgram Aura (text→speech)
 │ AccessibilityService           │               │  understand: match flow +     │──▶ Groq gpt-oss (JSON only,
 │  ├ read screen tree            │◀──flow+slots──│   slots (one LLM call)        │      validated by zod)
 │  ├ Recorder (teach taps)  ─────┼──steps──────▶ │  generalise: demo→flow        │
 │  ├ Replayer (do the steps)     │               │   (pure string work, no LLM)  │
 │  │   ├ ScreenGuard (pay/OTP/   │──pick-element▶│  REST: /api/llm/pick-element  │
 │  │   │   login → hand back)    │   (when stuck)│        /api/flows, /api/metrics│
 │  │   └ ask the user (question) │──run result──▶│  RunLog ─┐                    │
 │  └ tap / type / scroll / back  │               │  MongoDB: flows, runlogs ─────┘ → dashboard (/dashboard)
 └───────────────────────────────┘               └───────────────────────────────┘
```

Say it in one breath: **the phone sees and acts, the server listens, understands and remembers.**
The LLM is used for three things only: choosing the flow and its values, picking an element when stuck, and reading a
free-form answer. Learning the flow uses no LLM at all.

Where the line sits (my inference): screen reading and every safety check stay on the phone, so a slow or failed
network can never cause a tap on a payment screen.

---

## 4. Core technology: what, why, alternatives

| Module | What it does | Why we use it | Alternatives, and why not |
|---|---|---|---|
| **AccessibilityService** (`kt/a11y/`) | Reads every app's screen as a tree of buttons and text, and taps, types, scrolls and presses Back. | **Required by the evaluation:** "Accessibility only" (`CLAUDE.md:18`). It works on any app with no cooperation from the app. | **UIAutomator/Appium:** need a laptop or test instrumentation, not something a consumer phone runs (my inference). **Screenshot-vision agents:** slower and costlier per step, and pixels have no ids (my inference; we did not measure one). **Deep links/SDKs:** forbidden by the rules (`CLAUDE.md:18`), and need each app's support, which is the Bixby problem we pitch against (`CLAUDE.md:10-11`). |
| **Kotlin** (`android/`) | The phone app. | The project spec chose it (`CLAUDE.md:44`). An AccessibilityService must be native Android code (my inference). | Flutter/React Native would still need native code for the service (my inference). |
| **Deepgram Nova 3** (`js/lib/deepgram.js:5`) | Speech to text, streamed. Keyterms boost names like "Domino's" (`:13`). Aura is used for speech out (`:14`). | It was in the spec and the owner already had keys (`CLAUDE.md:54`, `HANDOFF:17`). The *reason* for picking Deepgram over others: no recorded reason. | **On-device STT** (Android SpeechRecognizer, Whisper on phone): no keyterm biasing for app and dish names, and quality varies by phone (my inference, not tested). **Whisper on Groq** is listed on our account (`HANDOFF:592`) but was not tried. **Latency: not measured.** |
| **Groq, gpt-oss-20b and gpt-oss-120b** (`js/lib/config.js:9-16`) | Flow matching and slot extraction (20b first). Picking elements and reading free-form answers (120b first). JSON mode, 5 s per model, falls to the next model on a 429 rate limit (`js/lib/groq.js:56`, `:89`). | It was in the spec (`CLAUDE.md:55`). Fast. Llama was removed from our account (`HANDOFF:34`). **Qwen** was tried as a fallback: 2.5x faster (median 374 vs 950 ms) but dropped a slot (31/32), and the owner removed it (`HANDOFF:1039-1040`, `:1092`). 120b for screen picks: right 3/3 vs 20b 1/3 on the same live screen (`js/lib/config.js:13-15`). | **Other LLM APIs** (OpenAI, Gemini, Claude): not tried, so there is no recorded comparison. The code calls one OpenAI-style chat endpoint (`js/lib/groq.js`), so another provider needs a new URL and key, not a rewrite (my inference). |
| **Node + Express** (`js/server.js`) | WebSocket session, REST API, dashboard. | The owner's stack (`CLAUDE.md:5`). | No recorded reason to compare others. |
| **MongoDB** (`js/models/`) | Stores flows and run logs. | It was in the spec (`CLAUDE.md:53`), and the owner's stack (`CLAUDE.md:5`). A flow's steps are a nested document, which fits Mongo well (my inference). | SQL would need a steps table (my inference). |
| **zod validation** | Checks every REST body (`js/routes/flows.js:22`), the config (`js/lib/config.js`), every LLM answer (`js/lib/groq.js:89-90`, `js/prompts/pickElement.js`), and each message from the phone (`js/ws/session.js:161`). | The project rules say to validate all external input *and LLM output* (`log.md:12`, `:27`). An LLM answer naming a flow id we never sent is rejected (`js/prompts/matchFlow.js:50`). | None considered. |
| **Pure-string generaliser** (`js/services/generalise.js`) | Turns the demo into a flow: finds the spoken values in the taps and types and replaces them with `{{slot}}`. No LLM. | Deterministic and testable. It was checked live on the real taught flows and found *not* to be overfit to Zomato (`HANDOFF:647`). The spec originally said to use Groq here (`CLAUDE.md:55`); the reason for changing that: no recorded reason (my inference: the same demo always gives the same flow, and no tokens are spent). | An LLM generaliser: non-deterministic, and harder to test. |

---

## 5. Samsung relevance: what is real and what is untested

**Test phone: Realme RMX3771, Android 15 (API 35), gesture navigation, Gboard keyboard.**
To check: `adb shell getprop ro.product.model`, `getprop ro.build.version.release`, `settings get secure navigation_mode` (2 = gestures).
**We have never run it on a Galaxy.** Say this plainly.

**Works on a Galaxy by design.** Not tested, but these are standard Android APIs:
- AccessibilityService, `ACTION_CLICK`, `dispatchGesture` and global Back are all part of Android itself.
- minSdk 26 / targetSdk 35 (`android/app/build.gradle.kts:11-12`).

**One UI specifics we have NOT verified:**
- **Enabling the accessibility service.** On One UI the path is Settings > Accessibility > Installed apps. One UI may show an extra warning for sideloaded apps (Android 13+ "restricted settings"). Not tested.
- **Samsung Keyboard.** Typing uses `ACTION_SET_TEXT` on the field (`kt/a11y/ActionExecutor.kt:18`), which does not go through the keyboard. So it should be fine, but this is not tested.
- **Gesture navigation.** It is tested on Realme. Back from a gesture during teaching is detected by the window coming back (`HANDOFF:1095` region). Not tested on One UI.
- **Battery optimisation.** One UI's "Put unused apps to sleep" may kill the foreground voice service. Not tested.
- **Edge panels and Bixby key.** These could create overlay windows that change the screen tree. Not tested.
- **Samsung's own apps** (Samsung Internet, Galaxy Store, Samsung Pay): not tested. Samsung Pay is *not* on our payment-app block list (`kt/core/ScreenGuard.kt:19`). **Add it before any Galaxy test.**

**Extension ideas, marked honestly:**

| Idea | Status |
|---|---|
| Fallback layer under Bixby: teach what Bixby has no capsule for | **Roadmap.** This is the pitch (`CLAUDE.md:10-11`); nothing is built. |
| Ship as an OEM system service (pre-granted accessibility, no laptop) | **Roadmap.** Today it needs our backend on a laptop over USB (`tools/up.sh`). |
| Bixby Routines trigger ("when I get home, order…") | **Roadmap.** Not built. |
| SmartThings | **Roadmap.** Not built and not explored. |
| DeX (large screen) | **Roadmap.** Untested. Bounds-based row matching may differ on DeX. |
| Accessibility as a feature for motor-impaired users | **Built in principle.** Voice replay of a taught task works today. Not evaluated with real users. |

---

## 6. Scalability: generic vs Zomato-shaped code

Total: **3,762 lines Kotlin** (`cat $(find android/app/src/main/java -name '*.kt') | wc -l`) and **1,799 lines JS** (same for `backend/src`).

**Ordering- or Zomato-shaped (≈165 lines, about 3% of the code):**

| Code | Lines | What is app-shaped |
|---|---|---|
| `CART_EVIDENCE` / `CHECKOUT_EVIDENCE` (`kt/core/ScreenGuard.kt:87-92`) | 6 | Words that mean "at the cart": "zomato money balance", "select address at next step", "subtotal", "proceed to buy". |
| `ADDRESS_HEADER` (`kt/replay/Replayer.kt:1052`) | 1 | "Delivering to…" / "mins to…" header, Zomato-style. |
| `INTERSTITIAL` (`kt/replay/Replayer.kt:1047`) | 1 | Popup buttons that are safe to press: "got it", "not now"… Generic, but English. |
| `CartState` (`kt/core/CartState.kt`) | 30 | Item count in the cart, and the "replace cart?" prompt. |
| `quantity()` / `address()` (`kt/replay/Replayer.kt:517-574`) | 57 | Quantity stepper, and the saved-address picker. |
| `askToReachCart` / `addAnother` / `alreadyInCart` (`kt/replay/Replayer.kt:671-754`, `:889`) | 54 | Ordering-only endings. |
| `addOrderingSteps` / `ORDERING` / `IMPLIED_SLOTS` (`js/services/generalise.js:145-146`, `:274-286`) | 15 | Adds the quantity and address steps when the command says order/buy/deliver. |

To check: `grep -rn -i zomato android/app/src/main/java backend/src | wc -l` gives 22 lines, mostly comments and prompt examples.

**English-only (this is the real scaling limit, not Zomato):** answer vocabulary (`kt/core/Answer.kt`, 95 lines),
captions and popups (`kt/core/Captions.kt`, 95), every payment, OTP and login regex (`kt/core/ScreenGuard.kt`, 121),
sensitive-step words (`js/services/sensitive.js`), number words (`js/prompts/slots.js:78`), and all spoken lines in `js/ws/session.js`.

**Generic (the rest):** tree reading, the recorder, the selector ladder, row matching, the replay loop, the LLM fallback,
matching, generalisation, logging.

### Runbook: add a new app

1. **Check that the screen tree is readable** (5 min). Open the app, run `tools/a11y.sh dump`, and look for
   text and ids. If it shows almost nothing, the app draws its own UI (a game, Flutter without semantics): stop here.
2. **Teach one flow by voice** (5–10 min including retries). Watch the teach panel step count.
3. **Read the saved flow** (5 min): `curl localhost:3100/api/flows`. Check that each `{{slot}}` landed where expected.
   If the teach was refused, it says what was heard and what was tapped.
4. **Replay the same values 3 times** (10–15 min). Use `tools/a11y.sh log` to watch.
5. **Replay with changed values 3 times** (10–15 min).
6. **If it shops, find the cart and checkout words** (15–30 min). Read the cart screen and see whether
   `ScreenGuard.readyToPay` recognises it. If not, add words to `CHECKOUT_EVIDENCE`: a code change with a test.
7. **Add the payment app package** to the block list if new (5 min, a code change).
8. **Paraphrase eval** (10 min): add labelled cases to `backend/scripts/paraphrase-eval.mjs` and run `npm run eval:paraphrase`.

Realistic total: **about 1 hour for a non-ordering app with no code change; 2–3 hours for a shopping app**
(estimate from the Amazon work, `HANDOFF:1095` region; not timed precisely).

**What breaks first:**
1. A screen in another language: popups, guards and answers are English regexes.
2. Apps whose controls have no text or id, like icon-only toolbars. The LLM now uses the task context for these
   (`kt/replay/Replayer.kt:937`), but this is verified live only in Settings, not on Zomato.
3. Long lists where the target needs scrolling and its row text changes (prices, "bestseller" tags).
4. Many saved flows: matching sends every flow description in one prompt (`js/prompts/matchFlow.js:36`).
   That is fine for tens of flows; past that it needs a search or embedding step first. **Not built.**

---

## 7. Panel Q&A bank (20-second answers)

**NOT BUILT** = say so. Do not dress it up.

1. **What happens if Groq is down?** Each call tries gpt-oss-20b, then 120b, 5 s each (`js/lib/groq.js:89`). If both fail, it says "my language service is busy, please say that again" (`js/ws/session.js:32`). It never guesses a tap. There is no second provider. **NOT BUILT: multi-provider failover.**
2. **Single provider, isn't that a risk?** Yes. Within Groq a rate-limited model is skipped until its Retry-After ends (`js/lib/groq.js:56`). Adding a second OpenAI-style provider is a URL and key in one file, but **it is not built.**
3. **Deepgram down?** Voice input stops. Typing a command in the app's text box still works (`kt/MainActivity.kt:39-48`). **NOT BUILT: on-device speech fallback.**
4. **Latency?** Measured today: intent extraction 0.44–0.99 s (median 0.70 s), screen pick 1.0–1.5 s (median 1.10 s), each over 5 calls. A teach-time tap is captured in 167–252 ms (`HANDOFF:1095`). End-to-end time per step is **not recorded**; RunLog has no timings.
5. **Cost?** **Not measured.** We are on Groq's developer plan: 500k requests a day and 250k tokens a minute per model, read from API headers (`HANDOFF:1091`). A normal replay makes one LLM call; a stuck step makes up to 2 more.
6. **Rate limits?** A 429 sends the call to the other model at once, with no waiting (`js/lib/groq.js:56`, `:97`). Measured on the free plan: at about 24 calls a minute, 20–25% of primary calls hit 429; at one call every 2.5 s, none did (`HANDOFF:1042`). Deepgram TTS has its own limit, hit once in a 30-command burst test (`HANDOFF:1125`).
7. **What stops it paying?** A screen check before every step. A payment, OTP, login or password screen stops the run and hands the phone back (`kt/replay/Replayer.kt:193`). It does not matter what was taught. Payment-app packages are blocked (`kt/core/ScreenGuard.kt:19`). An LLM pick on pay, order or delete is refused (`js/prompts/pickElement.js` system prompt, `kt/replay/Replayer.kt:385`).
8. **What if the checker misses a payment screen?** It is regex-based, in English. A missed screen is possible in another language. Any step marked sensitive (pay, order, send, delete: `js/services/sensitive.js:2`) needs an explicit yes, and the default is no (`kt/replay/Replayer.kt:42`, `:215`).
9. **Privacy of RunLog?** It stores what was said, the recogniser's words and confidence, and the slot values (`js/models/RunLog.js:16-22`). It stores no screen dumps and no passwords. **NOT BUILT: retention limit or TTL, encryption at rest, user delete.**
10. **Secrets in the APK?** None. Keys live only in the backend `.env`, which is gitignored (`.gitignore:3`). The APK talks only to our backend. Checked by unpacking the APK and searching for keys and Groq/Deepgram hosts: 0 matches.
11. **Is the backend authenticated?** **NOT BUILT.** It is reached over `adb reverse` on localhost today, so it is not exposed. A real deployment needs device auth and TLS.
12. **Flow versioning?** **NOT BUILT.** A correction overwrites the steps in place (`js/ws/session.js:89`), and only after the user says yes. There is no history or rollback.
13. **Duplicate cart adds?** If the dish already has a quantity stepper, it asks "already in your cart, add another or leave it?" (`kt/replay/Replayer.kt:889`). A "replace cart?" prompt from another restaurant is never auto-accepted (`kt/core/CartState.kt:23`). Real incident: my own test run added a pizza; it was found by reading the cart.
14. **Observability?** Every learn, match and replay goes to RunLog, with per-step ok and whether the LLM was used. There is a dashboard at `/dashboard`, a phone activity log (`tools/a11y.sh log`) and a Groq rate-limit watcher (`tools/groq-watch.sh`). **NOT BUILT:** step timings, alerts, tracing.
15. **How do you test?** 126 backend and 190 Kotlin unit tests, using fake screens built from real dumps. Plus a 32-case paraphrase eval run against real taught flows: 32/32 match and slots (`HANDOFF:1039`, `npm run eval:paraphrase`). Plus a live smoke script (`npm run smoke`).
16. **Isn't 32/32 overfitted?** It is a small set, written by us, over two flows. It shows the matcher works on rephrasings and changed values. It is not a benchmark.
17. **Your dashboard says 50% replay success. Why?** That counts every run since development started, including runs from before fixes and runs at 2 AM when restaurants were closed: replay 34/64, learn 17/34, paraphrase 34/86 (`curl localhost:3100/api/metrics`). Honest number, not cleaned. Per step it is better: 458 of 488 logged replay steps succeeded.
18. **How often does the LLM decide a tap?** 2 of 488 logged replay steps used the LLM fallback. Almost all taps are deterministic selector matches.
19. **What stops an infinite loop?** At most 2 LLM picks and 4 questions per step (`kt/replay/Replayer.kt:1031-1032`), 16 s per step and 150 s per run (`:53`).
20. **What if the app's UI changes?** The selector falls back from id to text to description to row (`kt/core/Selector.kt:185`), then to the LLM, then to a question. If you name a different button, it offers to update the flow (`js/services/correction.js:47`).
21. **Why not screenshots and a vision model?** No recorded reason (my inference): the tree gives exact text and ids for free; pixels need a large model per step, and can't tell a disabled button from an enabled one.
22. **Why not UIAutomator or Appium?** Those are test tools that run from a computer. AccessibilityService is what a shipping phone feature uses, and the rules require it (`CLAUDE.md:18`).
23. **Why not deep links?** They are forbidden by the rules, and each app would need to publish them: the same problem as Bixby capsules.
24. **Can a bad LLM answer cause damage?** Every LLM answer is schema-checked by zod. An element index must exist in what we sent. A pick must look like the step's control or be a safe close button (`kt/replay/Replayer.kt:900`). Destructive and payment picks are refused (`:385`).
25. **Concurrency, many users?** **NOT BUILT/TESTED.** Each phone has its own WebSocket session (`js/ws/gateway.js`), and the backend is stateless apart from Mongo. It is not load-tested.
26. **Does it work offline?** No. Speech and understanding need the backend and the internet. Safety checks run on the phone, so a network drop mid-run cannot cause a payment tap.
27. **Other languages?** **NOT BUILT.** Speech and every rule are English (section 6).
28. **What about incoming calls while teaching?** Taps in the dialer or call screen are not recorded (`kt/record/Recorder.kt:27`). Notification-shade taps are ignored, because the system UI package is excluded (`kt/record/Recorder.kt:30`, `:144`). Popups like "Got it" are not saved as steps (`kt/record/Recorder.kt:154`).
29. **Why Mongo, Node?** It is the owner's stack, and flows are nested documents. No deeper recorded reason; say so.
30. **What would Samsung need to ship this?** Run the service as a system app, so no laptop and no manual accessibility toggle. Move the backend to a Samsung cloud, or on-device models. Add multi-language guards. Add the auth, retention and versioning listed above as NOT BUILT.

---

## 8. Numbers cheat-sheet (measured only)

| Number | Value | Reproduce |
|---|---|---|
| Backend unit tests | 126 pass, 0 fail | `cd backend && npm test` |
| Kotlin unit tests | 190 pass, 0 fail | `cd android && ./gradlew testDebugUnitTest` |
| Paraphrase eval | 32/32 match, 32/32 slots (gpt-oss-20b) | `docker compose run --rm -v "$PWD/backend/scripts:/app/scripts" backend npm run eval:paraphrase` (`HANDOFF:110`, `:1039`) |
| Intent extraction latency | 0.44–0.99 s, median 0.70 s (5 calls, 23 Oct) | `for i in 1 2 3 4 5; do curl -s -o /dev/null -w "%{time_total}\n" -X POST localhost:3100/api/llm/intent -H 'Content-Type: application/json' -d '{"utterance":"get me two farmhouse pizzas from dominos on zomato"}'; done` |
| Screen-pick latency (120b) | 1.0–1.5 s, median 1.10 s (5 calls, live Settings screen) | `curl -X POST localhost:3100/api/llm/pick-element …` with a dump (see section 7, answer 4) |
| 120b vs 20b on a free-form answer | 3/3 vs 1/3 | `js/lib/config.js:13-15` (measured 22 Oct) |
| Qwen vs gpt-oss-20b | median 374 vs 950 ms; 31/32 vs 32/32 slots | `HANDOFF:1039-1040` |
| Teach tap capture | 167–252 ms (was 0.4–1.6 s) | `HANDOFF:1095` |
| Replay steps succeeded | 458 / 488 | `docker compose exec -T mongo mongosh tva --quiet --eval 'const s=db.runlogs.find({kind:"replay"}).toArray().flatMap(x=>x.stepResults||[]); print(s.filter(x=>x.ok).length+"/"+s.length)'` |
| Steps decided by the LLM | 2 / 488 | same query, `usedLlmFallback` |
| Dashboard (all-time, includes development runs) | learn 17/34, replay 34/64, paraphrase 34/86 | `curl localhost:3100/api/metrics` |
| Groq plan limits | 500k requests/day, 250k TPM per model | `HANDOFF:1091` (from API response headers) |
| Secrets in APK | 0 matches | unzip the APK and `grep -rlaE 'gsk_|api\.groq\.com|api\.deepgram\.com'` |
| Code size | 3,762 Kotlin, 1,799 JS | section 6 commands |

**Not measured, and do not quote:** Deepgram latency, end-to-end time per step, cost per run.

---

## 9. Known limitations (the 8 that matter)

1. **Never run on a Samsung phone.** Everything is tested on one Realme (Android 15).
2. **English only.** Speech, answers, popup handling, and the payment, OTP and login checks are all English word lists.
   A Hindi payment screen would not be recognised by its words.
3. **Needs a laptop today.** The backend runs in Docker and the phone reaches it over USB (`adb reverse`).
   No auth, no TLS, because it is never exposed.
4. **One LLM provider.** If Groq is down, voice commands can't be understood. Nothing unsafe happens; it just says so.
5. **No flow history.** A correction overwrites the flow in place, with no undo.
6. **Run logs keep transcripts forever.** There is no retention limit or delete-my-data.
7. **Ordering knowledge is shaped by Zomato and Amazon.** Cart and checkout words, and the address and quantity
   helpers (≈165 lines). A new shopping app may need its cart words added: a code change.
8. **Real-world state breaks runs.** A closed restaurant, a logged-out app, location off, or an app update
   moving a button. The app asks a question instead of failing silently, but it cannot fix these itself.
   Zomato also needs location on.
