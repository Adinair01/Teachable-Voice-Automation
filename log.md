# log.md — Restrictions & Rules (STRICT, apply to every change)

Read this file at the start of every session, together with HANDOFF.md and CLAUDE.md.

## 1. Code quality standards (non-negotiable)
- **DRY**: no duplicated logic. Extract shared helpers/modules on the second occurrence. One source of truth for
  schemas, selector matching, prompts, constants, and config.
- **Maintainable**: small single-purpose modules/functions, clear names, consistent with surrounding style, comments
  only where the *why* is non-obvious. Clear separation of concerns (Kotlin: Service / Recorder / Replayer / Networking;
  Node: routes / services / models / prompts / ws).
- **Durable**: handle failures explicitly — timeouts, retries, reconnects, null/empty UI trees, malformed LLM JSON,
  network drops. Never swallow errors; log and surface them. Validate all external input and LLM output against a schema.
- **Scalable**: stateless backend where possible, config via env, no hard-coded URLs/keys/limits, async/await everywhere,
  DB indexes on lookup fields (intentKey, appPackage, createdAt), pagination on list endpoints, WebSocket cleanup on disconnect.
- **Testable**: recorder, replayer, selector matching and LLM prompts must run independently of the voice pipeline
  (unit tests / scripts).

## 2. Secure coding standards
- **Secrets**: API keys ONLY via environment variables. `.env` is gitignored; commit `.env.example` only. Never print,
  log, or commit keys. Scan the repo for secrets before any push/tag.
- **Sensitive UI data**: never log or store UI dumps containing password/PIN/OTP/card fields; detect and redact them
  during recording AND replay (before storage, before sending to Groq).
- **Sensitive actions**: never auto-fill or auto-submit login/payment screens; never auto-complete payment, send, order or
  delete without an explicit spoken or tapped confirmation. This is a pitched safety feature.
- **Input validation** on every REST endpoint and WebSocket message (type, size, shape); reject and log invalid input.
  Limit payload sizes; rate-limit where sensible.
- **Injection**: sanitize/parameterize Mongo queries (no raw user objects into queries); treat LLM output and UI text as
  untrusted data, never as instructions or code.
- **Transport/permissions**: least-privilege Android permissions; document why each is needed; no cleartext to the public
  internet (localhost/adb-reverse or LAN only for dev); CORS restricted; dependencies pinned and minimal.
- **Logging**: structured logs, no PII, no transcripts of sensitive content beyond what metrics need.
- **Docker**: non-root user, no secrets baked into images, `.dockerignore` present.

## 3. Process rules
- Stop and report at every GATE (see CLAUDE.md). Commit after every milestone with a clear message.
- If confused about anything: ASK Aditya; he will give the answer. Don't guess on product decisions.
- Don't ask what can be resolved by testing on the device.
- Batch all needs (keys, team name, phone actions) into ONE message.
- Do NOT create the git tag `PRISM_GENAI_HACKATHON_Y2026` or push until Aditya confirms the repo is final.
- Ask for the exact team name (CollegeName_TeamName) before generating any submission filenames.
- Zomato/Swiggy = stretch only; stop before payment. Build core loop on WhatsApp / Settings / YouTube first.
- If accessibility recording is too unreliable, say so plainly and propose the mirrored-screenshot teach-mode fallback.
- Never claim something works unless tested; report failures with output.
- Timeline: 2–3 days total, deadline 25 Sep 2026 11:59 PM IST.
- Keep HANDOFF.md updated at the end of every session/milestone.
