import 'dotenv/config';
import { z } from 'zod';

const schema = z.object({
  PORT: z.coerce.number().default(3000),
  MONGO_URL: z.string().min(1),
  DEEPGRAM_API_KEY: z.string().default(''),
  GROQ_API_KEY: z.string().default(''),
  GROQ_MODEL: z.string().default('openai/gpt-oss-20b'),
  // Tried in order after GROQ_MODEL when it is rate limited, down or answers with something unusable.
  // Different vendors on purpose: Qwen's quota and failure modes are independent of the GPT models'.
  GROQ_FALLBACK_MODELS: z.string().default('qwen/qwen3.8-27b,openai/gpt-oss-120b'),
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
};
