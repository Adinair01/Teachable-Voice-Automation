import { Router } from 'express';
import { RunLog } from '../models/RunLog.js';

export const metricsRouter = Router();

const rate = (rows) => ({
  total: rows.total,
  successes: rows.ok,
  rate: rows.total ? +(rows.ok / rows.total).toFixed(4) : null,
});

// Single aggregation shared by learn / replay / match so the three metrics can't drift apart.
export async function computeMetrics() {
  const agg = await RunLog.aggregate([
    { $group: { _id: '$kind', total: { $sum: 1 }, ok: { $sum: { $cond: ['$success', 1, 0] } } } },
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
