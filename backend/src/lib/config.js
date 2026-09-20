import 'dotenv/config';
import { z } from 'zod';

const schema = z.object({
  PORT: z.coerce.number().default(3000),
  MONGO_URL: z.string().min(1),
  DEEPGRAM_API_KEY: z.string().default(''),
  GROQ_API_KEY: z.string().default(''),
  GROQ_MODEL: z.string().default('llama-3.3-70b-versatile'),
  LOG_LEVEL: z.string().default('info'),
});

const parsed = schema.safeParse(process.env);
if (!parsed.success) {
  console.error('Invalid environment:', parsed.error.flatten().fieldErrors);
  process.exit(1);
}
export const config = parsed.data;
