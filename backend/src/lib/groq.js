import { config } from './config.js';
import { logger } from './logger.js';

const URL = 'https://api.groq.com/openai/v1/chat/completions';

/**
 * One Groq JSON-mode call, validated against a zod schema. Every LLM feature goes through here
 * so retries, timeouts and output validation live in one place. LLM output is untrusted data.
 */
export async function groqJson({ system, user, schema, timeoutMs = 15000, fetchImpl = fetch }) {
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
    logger.error({ status: res.status }, 'groq request failed');
    throw new Error(`groq http ${res.status}`);
  }
  const body = await res.json();
  const content = body.choices?.[0]?.message?.content;
  return schema.parse(JSON.parse(content));
}
