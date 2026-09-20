import pino from 'pino';
import { config } from './config.js';

// Never log secrets or raw UI dumps; redact by path as a safety net.
export const logger = pino({
  level: config.LOG_LEVEL,
  redact: ['*.apiKey', '*.password', 'req.headers.authorization'],
});
