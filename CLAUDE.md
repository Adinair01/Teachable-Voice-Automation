# Teachable Voice Automation — Samsung PRISM Gen AI Hackathon (Theme 3)

> Session start: read HANDOFF.md (state/next steps) and log.md (strict rules: DRY, maintainable, durable, scalable, secure). Session end: update HANDOFF.md.

Owner: Aditya Nair (Node/Express/Mongo dev, zero Android experience).
Deadline: 25 Sep 2026, 11:59 PM IST. Realistic build window: 2–3 days (compressed plan below).

## Product
Android app that learns to operate ANY app by watching the user demonstrate once, then replays by voice with new
parameters ("order me a burger" taught -> "order me a pizza" replayed). Works via AccessibilityService (no target-app
cooperation). Pitch: fallback layer under Bixby (which needs per-app developer support); the architecture is exactly
what Samsung could ship as an OEM system service.

## Architecture
**Part A — Android (Kotlin, the submission artifact)**
- AccessibilityService: read UI tree (resource-id, text, content-desc, class, bounds, clickable); perform click/type/scroll/back.
- Mic button + foreground service: push-to-talk, stream audio over WebSocket, play TTS replies.
- Flow Recorder (teach mode): accessibility events -> steps (selector + action + typed value), sent to backend per action.
- Flow Replayer: receive flow, execute each step vs live UI tree, wait for target (~6s timeout), verify screen,
  pause for spoken/tapped "confirm" before any sensitive step (payment, send, order, delete).
- UI: mic button, live transcript/status log, teach-mode indicator, learned-flows list, settings (backend URL).
- Permissions flow: explanation screen -> Settings > Accessibility (cannot be enabled programmatically) + RECORD_AUDIO.

**Part B — Backend (Node + Express + MongoDB, Docker)**
- WebSocket: receive audio -> Deepgram Nova 3 STT.
- Groq (Llama 3.x JSON mode): (1) intent+slot extraction, (2) generalise demo into parameterised flow,
  (3) fallback "pick element from UI dump", (4) paraphrase matching against saved flow descriptions.
- Mongo: flows { intentKey, description, appPackage, slots[{name,example}], steps[...], createdAt } + RunLog per replay.
- TTS: Deepgram Aura (fallback: any free TTS) streamed over the same WebSocket.
- REST: list flows, delete flow, get metrics. Metrics dashboard (served HTML): learn success rate, replay success rate,
  paraphrase match accuracy — from RunLog data. EXPLICIT theme requirement.

## Flow schema
{ intentKey, description, appPackage, slots:[{name,example}],
  steps:[{ type:"launch"|"tap"|"type"|"scroll"|"back"|"wait",
           selector:{resourceId,text,contentDesc,className}, slotRef:"item"|null,
           value:"literal"|"{{item}}", sensitive:false, expect:{package,textContains} }], createdAt }

## Replay logic
- Per step: read UI tree, find target by priority resourceId > text > contentDesc > className+position; if slotRef, match current slot value.
- Poll w/ timeout, retry once, then send compact UI dump + step intent + slots to Groq to pick element (also detects/dismisses popups/permission dialogs).
- Sensitive step: pause, speak "Confirm?", wait for spoken/tapped yes. NEVER auto-complete payment/send.
- Log every run (per-step + total) to Mongo.

## Slot detection while teaching
User gives original command with slot (e.g. item=burger). If a typed value / tapped element text matches slot value
(case-insens.) in the demo, store value as {{item}}.

## Demo apps
Core loop on deterministic apps first: WhatsApp (send message X), Settings (toggle Wi-Fi/BT), YouTube/browser (search X).
Zomato/Swiggy = stretch only, stop before payment with spoken confirmation (pitch it as a safety feature). Record backup demo video early.

## Submission (all graded)
1. Public/shared GitHub repo: Android app + backend.
2. README: build/install APK, enable Accessibility Service, run backend, Dockerfile + docker-compose.yml (`docker compose up`), API keys via `.env.example` (never commit real keys).
3. Demo video <=5 min: teach-mode trigger, manual demo, 2nd command w/ different slot replays, confirmation-before-sensitive-action.
4. Presentation (PPT/PDF): problem, solution, architecture, why Accessibility beats app-specific integrations, metrics numbers, limitations, "production = OEM system service".
5. Final: commit all, then `git tag -a PRISM_GENAI_HACKATHON_Y2026 -m "PRISM Gen AI Hackathon Y2026 Final Submission"` and `git push origin PRISM_GENAI_HACKATHON_Y2026`. DO NOT run until the user confirms repo is final.
6. File naming: CollegeName_TeamName — ASK user for exact team-name string before generating any submission filenames.

## Code quality bar
- Kotlin: idiomatic, null-safe, coroutines; never block main thread on UI-tree reads/network; separate Service / Recorder / Replayer / Networking.
- Node: async/await, validate every endpoint, dotenv + .env.example, structured logging, errors surfaced not swallowed.
- Security: never log/store UI dumps with password fields — redact password/PIN inputs in recording and replay. Never auto-fill/submit login or payment screens without explicit spoken confirmation. Keys only via env; .env gitignored.
- Small testable modules (recorder, replayer, prompts, selector matching independently testable).

## Plan (compressed to ~3 days; gates still apply — stop and report at each)
- Day 1 GATE: Android project + AccessibilityService reads tree & performs tap/type/scroll on WhatsApp + Settings (flag any empty tree); backend skeleton (Express+Mongo+Docker).
- Day 2 GATE: Recorder captures real demo w/ selectors; Replayer runs hand-written flow 3x incl. LLM fallback; voice pipeline (mic->STT->Groq->Mongo->replay/"show me once"->TTS) wired.
- Day 3 GATE: teach end-to-end, 3 flows learned & replayed w/ new slots, paraphrase matching, metrics dashboard from real RunLogs; confirmation hardening, README, Docker verified, demo video, PPT, secrets cleaned, ready for tag (wait for go-ahead).

## Rules
- Don't ask what can be resolved by testing on the device; stop at each GATE.
- Batch needs (API keys, team name, phone actions) into one message.
- If accessibility recording is too unreliable, say so plainly and propose fallback (click-on-mirrored-screenshot teach mode).
- Commit after every milestone with clear messages. On any confusion, ask the user.
