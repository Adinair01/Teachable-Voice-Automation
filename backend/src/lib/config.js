import 'dotenv/config';
import { z } from 'zod';

const schema = z.object({
  PORT: z.coerce.number().default(3000),
  MONGO_URL: z.string().min(1),
  DEEPGRAM_API_KEY: z.string().default(''),
  GROQ_API_KEY: z.string().default(''),
  GROQ_MODEL: z.string().default('openai/gpt-oss-20b'),
  // Tried in order after GROQ_MODEL when it errors, times out or answers with something unusable. GPT models only
  // (2026-10-22, Groq developer plan): the larger GPT-OSS is the backup for the smaller one.
  GROQ_FALLBACK_MODELS: z.string().default('openai/gpt-oss-120b'),
  // Choosing an element on a screen (a stuck step, or what the user meant by an answer) is the hardest judgement made,
  // so it goes to the larger model first. Measured live 2026-10-22 on the same screen and answer: 120b right 3/3,
  // 20b right 1/3 (once nothing, once a wrong icon).
  GROQ_PICK_MODELS: z.string().default('openai/gpt-oss-120b,openai/gpt-oss-20b'),
  LOG_LEVEL: z.string().default('info'),
});

const parsed = schema.safeParse(process.env);
if (!parsed.success) {
  console.error('Invalid environment:', parsed.error.flatten().fieldErrors);
  process.exit(1);
}

/** Primary first, then the fallbacks, de-duplicated and blank-free: the order groqJson walks. */
export const modelChain = (primary, fallbacks) => [
  ...new Set([primary, ...fallbacks.split(',')].map((m) => m.trim()).filter(Boolean)),
];

export const config = {
  ...parsed.data,
  GROQ_MODEL_CHAIN: modelChain(parsed.data.GROQ_MODEL, parsed.data.GROQ_FALLBACK_MODELS),
  GROQ_PICK_CHAIN: modelChain('', parsed.data.GROQ_PICK_MODELS),
};
