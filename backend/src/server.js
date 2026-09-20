import http from 'node:http';
import cors from 'cors';
import express from 'express';
import mongoose from 'mongoose';
import { ZodError } from 'zod';
import { config } from './lib/config.js';
import { logger } from './lib/logger.js';
import { flowsRouter } from './routes/flows.js';
import { llmRouter } from './routes/llm.js';
import { metricsRouter } from './routes/metrics.js';
import { attachGateway } from './ws/gateway.js';

const app = express();
app.use(cors({ origin: false })); // phone app is not a browser; dashboard is same-origin
app.use(express.json({ limit: '256kb' }));

app.get('/health', (_req, res) => res.json({ ok: true, db: mongoose.connection.readyState === 1 }));
app.use('/api/flows', flowsRouter);
app.use('/api/metrics', metricsRouter);
app.use('/api/llm', llmRouter);

// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  if (err instanceof ZodError) return res.status(400).json({ error: 'validation failed', details: err.flatten() });
  logger.error({ err: err.message }, 'unhandled error');
  res.status(500).json({ error: 'internal error' });
});

const server = http.createServer(app);
attachGateway(server);

await mongoose.connect(config.MONGO_URL);
logger.info('mongo connected');
server.listen(config.PORT, () => logger.info({ port: config.PORT }, 'backend listening'));

const shutdown = async () => { server.close(); await mongoose.disconnect(); process.exit(0); };
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
