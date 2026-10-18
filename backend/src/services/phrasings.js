import { RunLog } from '../models/RunLog.js';

const MAX_PER_FLOW = 3;
const MAX_LEN = 120;
const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();

/**
 * The commands that have ALREADY run each flow successfully, newest first — how this particular user phrases the
 * task, which is not always how they phrased it while teaching. Handed to the matcher as extra wordings so a
 * paraphrase that worked once keeps working.
 *
 * Only successful runs count: feeding back a command that matched the wrong flow would teach the matcher the
 * mistake. Near-duplicates of each other and of the flow's own description are dropped, since they add nothing.
 */
export async function pastPhrasings(flows, { limit = 200 } = {}) {
  const ids = flows.map((f) => f._id).filter(Boolean);
  if (!ids.length) return {};
  const runs = await RunLog.find(
    { kind: 'replay', success: true, flowId: { $in: ids }, utterance: { $nin: [null, ''] } },
    { flowId: 1, utterance: 1 },
  ).sort({ createdAt: -1 }).limit(limit).lean();
  return pickPhrasings(runs, flows);
}

/** The selection itself, kept pure so it is testable without a database: runs must arrive newest first. */
export function pickPhrasings(runs, flows) {
  const described = new Map(flows.map((f) => [String(f._id), norm(f.description ?? '')]));
  const out = {};
  const seen = {};
  for (const r of runs) {
    const key = String(r.flowId);
    if (!described.has(key)) continue; // a run whose flow was deleted since
    const said = String(r.utterance ?? '').slice(0, MAX_LEN).trim();
    const n = norm(said);
    if (!n || n === described.get(key)) continue;
    seen[key] ??= new Set();
    if (seen[key].has(n)) continue;
    seen[key].add(n);
    out[key] ??= [];
    if (out[key].length < MAX_PER_FLOW) out[key].push(said);
  }
  return out;
}
