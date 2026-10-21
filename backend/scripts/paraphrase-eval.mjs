// Paraphrase-matching eval: runs a labelled set of utterances through the real matcher against the flows currently
// in Mongo and prints per-case results plus accuracy. Reproducible without the phone.
//   node scripts/paraphrase-eval.mjs [--json] [--flows <file.json>]
// --flows reads the taught flows from a file instead of Mongo, so the eval also runs with no database reachable
// (dump them with: docker compose exec -T mongo mongosh tva --quiet --eval 'JSON.stringify(db.flows.find().toArray())').
import { readFileSync } from 'node:fs';
import mongoose from 'mongoose';
import { config } from '../src/lib/config.js';
import { Flow } from '../src/models/Flow.js';
import { matchFlow } from '../src/prompts/matchFlow.js';
import { normaliseSlots } from '../src/prompts/slots.js';

// expect: a substring of the taught flow's description, or null when nothing should match.
// clarify: true when the command is too vague to run and the assistant must ask which one.
// Grouped by what each case is really testing, so a regression says WHAT kind of phrasing broke.
const CASES = [
  // --- the taught wording itself, and the evaluation spec's own paraphrases (T1-T3) ---
  { utterance: "Order a Margherita pizza from Domino's on Zomato", expect: 'Margherita pizza', slots: { item: 'Margherita pizza', restaurant: "Domino's" } },
  { utterance: 'Get me a margherita from dominos', expect: 'Margherita pizza', slots: { item: 'margherita' } },
  { utterance: 'I want to order margherita pizza on zomato', expect: 'Margherita pizza', slots: { item: 'margherita pizza' } },

  // --- same meaning, freely rephrased: this is what "say it a bit differently" means in practice ---
  { utterance: 'can you order me a margherita pizza from dominos', expect: 'Margherita pizza', slots: { item: 'margherita pizza' } },
  { utterance: "I'm hungry, get a margherita from domino's please", expect: 'Margherita pizza', slots: { item: 'margherita' } },
  { utterance: 'please order one margherita pizza from dominos', expect: 'Margherita pizza', slots: { item: 'margherita pizza' } },
  { utterance: 'margherita pizza from dominos', expect: 'Margherita pizza', slots: { item: 'margherita pizza' } },
  { utterance: 'from dominos order a margherita pizza', expect: 'Margherita pizza', slots: { item: 'margherita pizza' } },
  // The app is not the restaurant: "on zomato" must NOT become restaurant="Zomato", or replay searches for the app.
  { utterance: 'buy a margherita pizza on zomato', expect: 'Margherita pizza', slots: { item: 'margherita pizza' }, notSlots: ['zomato'] },
  { utterance: 'order a margherita pizza on zomato', expect: 'Margherita pizza', slots: { item: 'margherita pizza' }, notSlots: ['zomato'] },
  { utterance: 'on zomato get me a farmhouse pizza', expect: 'Margherita pizza', slots: { item: 'farmhouse pizza' }, notSlots: ['zomato'] },
  { utterance: 'book a margherita pizza from dominos', expect: 'Margherita pizza', slots: { item: 'margherita pizza' } },
  { utterance: 'I need a margherita pizza delivered from dominos', expect: 'Margherita pizza', slots: { item: 'margherita pizza' } },
  // Indian-English particles must not ride along into a slot: "dominos na" would be typed into the search box.
  { utterance: 'do order a margherita from dominos na', expect: 'Margherita pizza', slots: { item: 'margherita', restaurant: 'dominos' }, notSlots: ['dominos na'] },
  { utterance: 'margherita pizza from dominos please yaar', expect: 'Margherita pizza', slots: { item: 'margherita pizza', restaurant: 'dominos' }, notSlots: ['dominos please yaar', 'dominos please'] },

  // --- changed slots: same flow, different values (T4/T5/T6) ---
  { utterance: 'order a farmhouse pizza from dominos', expect: 'Margherita pizza', slots: { item: 'farmhouse pizza' } },
  { utterance: 'get me a corn and cheese pizza from dominos', expect: 'Margherita pizza', slots: { item: 'corn and cheese pizza' } },
  { utterance: 'order two margherita pizzas from dominos', expect: 'Margherita pizza', slots: { item: 'margherita', quantity: '2' } },
  { utterance: 'order a margherita pizza and deliver it to work', expect: 'Margherita pizza', slots: { item: 'margherita pizza', address: 'work' } },
  { utterance: 'get me three farmhouse pizzas from dominos to work', expect: 'Margherita pizza', slots: { item: 'farmhouse', quantity: '3', address: 'work' } },
  { utterance: 'order a margherita from pizza hut', expect: 'Margherita pizza', slots: { item: 'margherita', restaurant: 'pizza hut' } },

  // --- the calculator flow, so paraphrasing is not tuned to food only ---
  { utterance: 'Add five and three', expect: 'Add five', slots: { num1: '5', num2: '3' } },
  { utterance: 'what is 9 plus 4', expect: 'Add five', slots: { num1: '9', num2: '4' } },
  { utterance: 'add 12 and 7 on the calculator', expect: 'Add five', slots: { num1: '12', num2: '7' }, notSlots: ['calculator'] },
  { utterance: 'calculate 6 plus 1', expect: 'Add five', slots: { num1: '6', num2: '1' } },
  { utterance: 'can you sum 8 and 2 for me', expect: 'Add five', slots: { num1: '8', num2: '2' } },

  // --- must NOT be forced into a learned flow (T12) ---
  { utterance: 'Book a cab', expect: null },
  { utterance: 'send a message to mum on WhatsApp', expect: null },
  { utterance: 'play some music', expect: null },
  { utterance: 'what is the weather tomorrow', expect: null },

  // --- too vague to run: must ask which one, never silently guess (T13) ---
  { utterance: 'order pizza', expect: 'Margherita pizza', clarify: true },
  // Genuinely ambiguous, and BOTH safe answers are correct: a burger could be this flow with a different item
  // (then it must ask which one, T13) or a task never taught (then teach mode, T12). Neither taps anything, so the
  // eval accepts either and only fails if it silently picks a dish for the user.
  { utterance: 'get me a burger', expect: 'Margherita pizza', clarify: true, orNoMatch: true },
];

const norm = (s) => String(s ?? '').toLowerCase().replace(/[^a-z0-9]/g, '');

const fromFile = process.argv[process.argv.indexOf('--flows') + 1];
const useFile = process.argv.includes('--flows');
if (!useFile) await mongoose.connect(config.MONGO_URL);
// A mongosh dump writes ids as {"$oid": "..."}: left like that, EVERY flow's id stringifies to "[object Object]", the
// matcher is shown several flows with one id, and any match scores as correct. Flatten them to their hex string.
const flows = useFile
  ? JSON.parse(readFileSync(fromFile, 'utf8')).map((f) => ({ ...f, _id: f._id?.$oid ?? f._id }))
  : await Flow.find().sort({ createdAt: -1 }).limit(100).lean();
if (!flows.length) { console.error('no flows taught yet — teach something first'); process.exit(1); }
if (new Set(flows.map((f) => String(f._id))).size !== flows.length) { console.error('flow ids are not distinct; the eval would be meaningless'); process.exit(1); }

const results = [];
for (const c of CASES) {
  const want = c.expect === null ? null : flows.find((f) => f.description.toLowerCase().includes(c.expect.toLowerCase()));
  if (c.expect !== null && !want) { results.push({ ...c, skipped: 'no such flow taught' }); continue; }
  await new Promise((r) => setTimeout(r, 1500)); // stay under the Groq free-tier rate limit (TPM)
  // A batch with nobody waiting may sit out Retry-After (the live session never does: groqJson defaults to retries 0).
  const m = await matchFlow(c.utterance, flows, { retries: 2 });
  // session.js normalises before replay, so the eval scores the slots the flow will ACTUALLY be run with.
  m.slots = normaliseSlots(m.slots);
  const got = m.flowId ? flows.find((f) => String(f._id) === m.flowId) : null;
  let matchOk = String(got?._id ?? null) === String(want?._id ?? null);
  // "either answer is safe" cases: no match is as acceptable as a match that then asks.
  if (!matchOk && c.orNoMatch && !got) matchOk = true;
  // Slot NAMES are chosen by the model at teach time, so check that each expected VALUE appears among the extracted ones.
  const got_values = new Set(Object.values(m.slots ?? {}).map(norm));
  // A multi-word value may be split across slots ("corn and cheese pizza"), so accept it inside any extracted value.
  const slotOk = !c.slots || Object.values(c.slots).every((v) =>
    got_values.has(norm(v)) || [...got_values].some((g) => g.includes(norm(v)) || norm(v).includes(g)));
  const clarifyOk = !c.clarify ? true : (!!m.clarify || (c.orNoMatch && !got));
  // Values that must NOT be extracted at all (the app name landing in a restaurant/store slot).
  const leaked = (c.notSlots ?? []).filter((bad) => [...got_values].some((g) => g === norm(bad)));
  results.push({ ...c, got: got?.description ?? null, slots: m.slots, clarify: m.clarify, matchOk, slotOk, clarifyOk, leaked });
}

const scored = results.filter((r) => !r.skipped);
const matched = scored.filter((r) => r.matchOk).length;
const slotted = scored.filter((r) => r.matchOk && r.slotOk && r.clarifyOk && !r.leaked.length).length;
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
    const flag = !r.matchOk ? 'MISS ' : !r.slotOk ? 'SLOT?' : !r.clarifyOk ? 'VAGUE' : r.leaked.length ? 'LEAK ' : 'PASS ';
    console.log(`- ${flag} ${r.utterance}\n        -> ${r.got ?? '(no match: teach mode)'} ${JSON.stringify(r.slots ?? {})}`);
  }
  console.log(`\nmatch accuracy ${matched}/${scored.length}, with correct slots ${slotted}/${scored.length}` +
    (summary.skipped ? `, ${summary.skipped} skipped` : ''));
}
if (!useFile) await mongoose.disconnect();
