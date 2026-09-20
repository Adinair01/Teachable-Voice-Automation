import { config } from './config.js';
import { logger } from './logger.js';

const URL = 'https://api.groq.com/openai/v1/chat/completions';

/**
 * One Groq JSON-mode call, validated against a zod schema. Every LLM feature goes through here
 * so retries, timeouts and output validation live in one place. LLM output is untrusted data.
 */
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const RETRYABLE = (status) => status === 429 || status >= 500;

export async function groqJson({ system, user, schema, timeoutMs = 15000, fetchImpl = fetch, retries = 1 }) {
  if (!config.GROQ_API_KEY) throw new Error('GROQ_API_KEY not configured');
  const res = await fetchImpl(URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.GROQ_API_KEY}` },
    body: JSON.stringify({
      model: config.GROQ_MODEL,
      temperature: 0,
      response_format: { type: 'json_object' },
      messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
    }),
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) {
    // Rate limits and 5xx are transient: wait out the server's own Retry-After once before giving up.
    if (retries > 0 && RETRYABLE(res.status)) {
      const waitMs = Math.min(10000, (Number(res.headers?.get?.('retry-after')) || 2) * 1000);
      logger.warn({ status: res.status, waitMs }, 'groq request throttled, retrying');
      await sleep(waitMs);
      return groqJson({ system, user, schema, timeoutMs, fetchImpl, retries: retries - 1 });
    }
    logger.error({ status: res.status }, 'groq request failed');
    throw new Error(`groq http ${res.status}`);
  }
  const body = await res.json();
  const content = body.choices?.[0]?.message?.content;
  return schema.parse(JSON.parse(content));
}
