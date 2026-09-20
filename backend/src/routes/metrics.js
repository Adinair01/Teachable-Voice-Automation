import { Router } from 'express';
import { RunLog } from '../models/RunLog.js';

export const metricsRouter = Router();

// Declined replays (the user said no to a sensitive step) leave the denominator: they are not failed automations.
export const rate = ({ total, ok, declined = 0 }) => {
  const attempted = total - declined;
  return { total: attempted, successes: ok, rate: attempted ? +(ok / attempted).toFixed(4) : null, ...(declined ? { declined } : {}) };
};

// Single aggregation shared by learn / replay / match so the three metrics can't drift apart.
export async function computeMetrics() {
  const agg = await RunLog.aggregate([
    { $group: { _id: '$kind', total: { $sum: 1 }, ok: { $sum: { $cond: ['$success', 1, 0] } }, declined: { $sum: { $cond: ['$declined', 1, 0] } } } },
  ]);
  const by = Object.fromEntries(agg.map((a) => [a._id, a]));
  const empty = { total: 0, ok: 0 };
  return {
    learnSuccess: rate(by.learn ?? empty),
    replaySuccess: rate(by.replay ?? empty),
    paraphraseAccuracy: rate(by.match ?? empty),
  };
}

metricsRouter.get('/', async (_req, res, next) => {
  try { res.json(await computeMetrics()); } catch (e) { next(e); }
});

metricsRouter.get('/runs', async (_req, res, next) => {
  try { res.json({ runs: await RunLog.find().sort({ createdAt: -1 }).limit(30).select('kind success utterance createdAt').lean() }); }
  catch (e) { next(e); }
});
