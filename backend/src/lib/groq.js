import { config } from './config.js';
import { logger } from './logger.js';

const URL = 'https://api.groq.com/openai/v1/chat/completions';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// A bad key is the same answer from every model, so it is the only reason to stop trying the others.
// Everything else is per-model: 429 (that model's quota), 5xx, 404 (decommissioned on this key), 400 (JSON mode).
const FATAL = (status) => status === 401 || status === 403;
const retryAfterMs = (res) => Math.min(10000, (Number(res.headers?.get?.('retry-after')) || 2) * 1000);

// How long a rate-limited model is passed over: its own Retry-After, within sane bounds. A 429 on the GPT model sends
// every later call straight to the next model (gpt-oss-120b) until then, instead of paying a refused round trip each time.
const COOL_MIN_MS = 2000;
const COOL_MAX_MS = 60000;
const coolUntil = new Map();
/** For tests: forget every cooldown. */
export const resetCooldowns = () => coolUntil.clear();
/** Models resting after a 429 (until when), for logs and metrics. */
export const cooling = (now = Date.now()) => [...coolUntil].filter(([, t]) => t > now).map(([model, until]) => ({ model, until }));

/**
 * The chain in the order to try it NOW: models that are free, in their configured order, then the ones still cooling
 * down (soonest free first). A cooling model is still tried last rather than skipped, so a run of 429s can never leave
 * the assistant with no model at all.
 */
function ordered(models, now = Date.now()) {
  const free = models.filter((m) => !(coolUntil.get(m) > now));
  const resting = models.filter((m) => coolUntil.get(m) > now).sort((a, b) => coolUntil.get(a) - coolUntil.get(b));
  return [...free, ...resting];
}

/** Model output that parsed but cannot be trusted — malformed JSON, wrong shape, or a value the caller rejected. */
class UnusableOutput extends Error {}

/** One attempt against ONE model. Never throws for a cause another model might survive. */
async function attempt({ model, system, user, schema, timeoutMs, fetchImpl, validate }) {
  let res;
  try {
    res = await fetchImpl(URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.GROQ_API_KEY}` },
      body: JSON.stringify({
        model,
        temperature: 0,
        response_format: { type: 'json_object' },
        messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
      }),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (err) {
    return { err, why: 'unreachable' }; // timeout or network drop: another model may still answer
  }
  if (!res.ok) {
    const err = new Error(`groq http ${res.status}`);
    if (res.status === 429) coolUntil.set(model, Date.now() + Math.min(COOL_MAX_MS, Math.max(COOL_MIN_MS, retryAfterMs(res))));
    if (FATAL(res.status)) return { err, fatal: true, why: `http ${res.status}` };
    return { err, waitMs: retryAfterMs(res), why: `http ${res.status}` };
  }
  try {
    const body = await res.json();
    const out = schema.parse(JSON.parse(body.choices?.[0]?.message?.content));
    // A schema-valid answer can still be invented (an index or id we never sent): the caller is the judge.
    if (validate && !validate(out)) {
      const bad = new UnusableOutput('output rejected by caller');
      bad.out = out;
      throw bad;
    }
    return { value: out };
  } catch (err) {
    // A rejected-but-well-formed answer is kept: if every model invents, the caller still gets something
    // to turn into its own "nothing fits" / "not learned yet" answer, rather than an exception.
    if (err instanceof UnusableOutput) return { err, why: 'hallucinated', rejected: { value: err.out } };
    return { err, why: 'malformed output' };
  }
}

/**
 * One Groq JSON-mode call, validated against a zod schema. Every LLM feature goes through here so the model
 * ladder, timeouts and output validation live in one place. LLM output is untrusted data.
 *
 * Models are tried in order (config.GROQ_MODEL_CHAIN: gpt-oss-20b -> gpt-oss-120b). A model that is rate
 * limited, down, decommissioned, or that answers with something unusable hands the question to the next one
 * immediately — no waiting while another model is free. A rate-limited model is then passed over for its
 * Retry-After window (see ordered()), so the next call goes straight to a free one. Waiting out Retry-After and
 * re-running the chain happens only when a caller opts in with retries > 0; the voice session never does.
 */
export async function groqJson({
  system, user, schema, timeoutMs = 5000, fetchImpl = fetch,
  models = config.GROQ_MODEL_CHAIN, validate, retries = 0,
}) {
  if (!config.GROQ_API_KEY) throw new Error('GROQ_API_KEY not configured');
  let last;
  let rejected;
  let waitMs = 0;
  // 5 s per model (was 15 s): a person is waiting on a reply. Three models at 15 s each was up to 45 s of silence.
  // retries = 0: a live session never sits out Retry-After; it answers from whichever model is free, or says so.
  const chain = ordered(models);
  for (const [i, model] of chain.entries()) {
    const r = await attempt({ model, system, user, schema, timeoutMs, fetchImpl, validate });
    if ('value' in r) {
      if (i > 0) logger.info({ model, after: chain.slice(0, i) }, 'groq answered on a fallback model');
      return r.value;
    }
    last = r.err;
    rejected = r.rejected ?? rejected;
    waitMs = Math.max(waitMs, r.waitMs ?? 0);
    if (r.fatal) break;
    const next = chain[i + 1];
    logger.warn({ model, why: r.why, next: next ?? null }, next ? 'groq model failed, switching model' : 'groq model failed, chain exhausted');
  }
  if (retries > 0 && waitMs > 0) {
    logger.warn({ waitMs, models }, 'every groq model unavailable, waiting out Retry-After');
    await sleep(waitMs);
    try {
      return await groqJson({ system, user, schema, timeoutMs, fetchImpl, models, validate, retries: retries - 1 });
    } catch (err) {
      if (!rejected) throw err;
      last = err;
    }
  }
  if (rejected) {
    logger.warn({ models }, 'every groq model returned output the caller rejected, handing back the last one');
    return rejected.value;
  }
  logger.error({ models, err: last?.message }, 'groq request failed on every model');
  throw last ?? new Error('groq: no model configured');
}
