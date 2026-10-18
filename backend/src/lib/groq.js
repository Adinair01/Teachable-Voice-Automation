import { config } from './config.js';
import { logger } from './logger.js';

const URL = 'https://api.groq.com/openai/v1/chat/completions';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// A bad key is the same answer from every model, so it is the only reason to stop trying the others.
// Everything else is per-model: 429 (that model's quota), 5xx, 404 (decommissioned on this key), 400 (JSON mode).
const FATAL = (status) => status === 401 || status === 403;
const retryAfterMs = (res) => Math.min(10000, (Number(res.headers?.get?.('retry-after')) || 2) * 1000);

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
 * Models are tried in order (config.GROQ_MODEL_CHAIN: gpt-oss -> qwen -> gpt-oss-120b). A model that is rate
 * limited, down, decommissioned, or that answers with something unusable hands the question to the next one
 * immediately — no waiting while another model is free. Only when the whole chain is exhausted do we wait out
 * the server's own Retry-After and run the chain once more.
 */
export async function groqJson({
  system, user, schema, timeoutMs = 15000, fetchImpl = fetch,
  models = config.GROQ_MODEL_CHAIN, validate, retries = 1,
}) {
  if (!config.GROQ_API_KEY) throw new Error('GROQ_API_KEY not configured');
  let last;
  let rejected;
  let waitMs = 0;
  for (const [i, model] of models.entries()) {
    const r = await attempt({ model, system, user, schema, timeoutMs, fetchImpl, validate });
    if ('value' in r) {
      if (i > 0) logger.info({ model, after: models.slice(0, i) }, 'groq answered on a fallback model');
      return r.value;
    }
    last = r.err;
    rejected = r.rejected ?? rejected;
    waitMs = Math.max(waitMs, r.waitMs ?? 0);
    if (r.fatal) break;
    const next = models[i + 1];
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
