// Paraphrase-matching eval: runs a labelled set of utterances through the real matcher against the flows currently
// in Mongo and prints per-case results plus accuracy. Reproducible without the phone.
//   node scripts/paraphrase-eval.mjs [--json]
import mongoose from 'mongoose';
import { config } from '../src/lib/config.js';
import { Flow } from '../src/models/Flow.js';
import { matchFlow } from '../src/prompts/matchFlow.js';

// expect: a substring of the taught flow's description, or null when nothing should match.
const CASES = [
  { utterance: 'open Bluetooth settings', expect: 'Bluetooth settings' },
  { utterance: 'take me to the Display settings', expect: 'Bluetooth settings', slots: { setting: 'Display' } },
  { utterance: 'show me the Display settings', expect: 'Bluetooth settings', slots: { setting: 'Display' } },
  { utterance: 'go to the Wi-Fi settings', expect: 'Bluetooth settings', slots: { setting: 'Wi-Fi' } },
  { utterance: 'what is 9 plus 4', expect: 'calculate', slots: { num1: '9', num2: '4' } },
  { utterance: 'add 2 and 7 on the calculator', expect: 'calculate', slots: { num1: '2', num2: '7' } },
  { utterance: 'compute 6 plus 1', expect: 'calculate', slots: { num1: '6', num2: '1' } },
  { utterance: 'open the timer in the clock app', expect: 'stopwatch', slots: { feature: 'timer' } },
  { utterance: 'switch to the world clock', expect: 'stopwatch', slots: { feature: 'world clock' } },
  { utterance: 'show me the stopwatch', expect: 'stopwatch', slots: { feature: 'stopwatch' } },
  { utterance: 'order me a pizza from Zomato', expect: null },
  { utterance: 'send a message to mum on WhatsApp', expect: null },
];

const norm = (s) => String(s ?? '').toLowerCase().replace(/[^a-z0-9]/g, '');

await mongoose.connect(config.MONGO_URL);
const flows = await Flow.find().sort({ createdAt: -1 }).limit(100).lean();
if (!flows.length) { console.error('no flows in the database — teach something first'); process.exit(1); }

const results = [];
for (const c of CASES) {
  const want = c.expect === null ? null : flows.find((f) => f.description.toLowerCase().includes(c.expect.toLowerCase()));
  if (c.expect !== null && !want) { results.push({ ...c, skipped: 'no such flow taught' }); continue; }
  await new Promise((r) => setTimeout(r, 1500)); // stay under the Groq free-tier rate limit (TPM)
  const m = await matchFlow(c.utterance, flows);
  const got = m.flowId ? flows.find((f) => String(f._id) === m.flowId) : null;
  const matchOk = String(got?._id ?? null) === String(want?._id ?? null);
  // Slot NAMES are chosen by the model at teach time, so check that each expected VALUE appears among the extracted ones.
  const got_values = new Set(Object.values(m.slots ?? {}).map(norm));
  const slotOk = !c.slots || Object.values(c.slots).every((v) => got_values.has(norm(v)));
  results.push({ ...c, got: got?.description ?? null, slots: m.slots, matchOk, slotOk });
}

const scored = results.filter((r) => !r.skipped);
const matched = scored.filter((r) => r.matchOk).length;
const slotted = scored.filter((r) => r.matchOk && r.slotOk).length;
const summary = {
  cases: scored.length,
  matchAccuracy: +(matched / scored.length).toFixed(4),
  matchAndSlotAccuracy: +(slotted / scored.length).toFixed(4),
  skipped: results.filter((r) => r.skipped).length,
};

if (process.argv.includes('--json')) {
  console.log(JSON.stringify({ summary, results }, null, 2));
} else {
  for (const r of results) {
    if (r.skipped) { console.log(`- SKIP  ${r.utterance} (${r.skipped})`); continue; }
    const flag = r.matchOk ? (r.slotOk ? 'PASS ' : 'SLOT?') : 'MISS ';
    console.log(`- ${flag} ${r.utterance}\n        -> ${r.got ?? '(no match: teach mode)'} ${JSON.stringify(r.slots ?? {})}`);
  }
  console.log(`\nmatch accuracy ${matched}/${scored.length}, with correct slots ${slotted}/${scored.length}` +
    (summary.skipped ? `, ${summary.skipped} skipped` : ''));
}
await mongoose.disconnect();
