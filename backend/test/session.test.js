import test from 'node:test';
import assert from 'node:assert/strict';

process.env.MONGO_URL = 'x';
process.env.LOG_LEVEL = 'silent';
const { Session } = await import('../src/ws/session.js');
const { calculatorDemo, margheritaDemo } = await import('./fixtures/demos.js');

/**
 * The real Session, end to end, with the outside world faked: flows in memory, a scripted matcher/extractor instead of
 * Groq, and a clock. Every assertion is on what the session SENDS the phone or SAYS, which is what a judge sees.
 */
function harness({ flows = [], match = () => ({ flowId: null, slots: {} }), extract = () => ({ intentKey: 'k', slots: {} }), lastRun = null } = {}) {
  const sent = [];
  const logs = [];
  let clock = 1_000_000;
  const store = [...flows];
  const deps = {
    loadFlows: async () => store,
    findFlow: async (id) => store.find((f) => String(f._id) === String(id)) ?? null,
    createFlow: async (doc) => { const f = { ...doc, _id: `f${store.length + 1}` }; store.push(f); return f; },
    updateFlowSteps: async () => {},
    log: async (doc) => { logs.push(doc); },
    lastRun: async () => lastRun,
    pastPhrasings: async () => ({}),
    matchFlow: async (text, fl) => match(text, fl),
    extractIntent: async (text) => extract(text),
    synthesize: async () => Buffer.alloc(0),
    stt: () => ({ write() {}, finish: async () => ({ text: '', confidence: null, words: [] }) }),
    now: () => clock,
  };
  const s = new Session({ readyState: 1, send: (m) => { if (typeof m === 'string') sent.push(JSON.parse(m)); } }, deps);
  return {
    s, sent, logs, store, deps: s.deps,
    tick: (ms) => { clock += ms; },
    said: () => sent.filter((m) => m.type === 'say').map((m) => m.text),
    replays: () => sent.filter((m) => m.type === 'replay'),
    clear: () => { sent.length = 0; },
  };
}

const teach = async (h, demo) => {
  h.deps.matchFlow = async () => ({ flowId: null, slots: {} }); // an untaught command
  h.deps.extractIntent = async () => ({ intentKey: demo.intentKey, slots: demo.slots });
  await h.s.handleCommand(demo.command);
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'teach_done', command: demo.command, intentKey: demo.intentKey, slots: h.s.teaching.slots, steps: demo.steps })));
  return h.store.at(-1);
};

// ---- Part 0: the whole chain, teach -> replay, through the session ----

test('calculator: taught "five plus two" (words), then 7+9, 100+1 and 5+2 reach the phone with THOSE values', async () => {
  const h = harness();
  const flow = await teach(h, calculatorDemo);
  assert.match(h.said().at(-1), /^Learned: /);
  for (const [model, want] of [[{ num1: 'seven', num2: 'nine' }, { num1: '7', num2: '9' }], [{ num1: '100', num2: '1' }, { num1: '100', num2: '1' }], [{ num1: '5', num2: '2' }, { num1: '5', num2: '2' }]]) {
    h.clear();
    h.deps.matchFlow = async () => ({ flowId: flow._id, slots: model, clarify: null });
    await h.s.handleCommand('calculate');
    assert.deepEqual(h.replays()[0].slots, want);
  }
});

test('zomato: taught Margherita, "order a Farmhouse" replays Farmhouse; the taught dish appears in no step it sends', async () => {
  const h = harness();
  const flow = await teach(h, margheritaDemo);
  h.deps.matchFlow = async () => ({ flowId: flow._id, slots: { item: 'Farmhouse pizza', restaurant: "Domino's" }, clarify: null });
  await h.s.handleCommand("order a Farmhouse pizza from Domino's on Zomato");
  const r = h.replays()[0];
  assert.equal(r.slots.item, 'Farmhouse pizza');
  assert.ok(!/margherita/i.test(JSON.stringify(r.flow.steps)), 'no Margherita literal in the flow the phone runs');
});

test('a teach whose demo never used the named dish is REFUSED (it would replay the demo\'s dish forever)', async () => {
  const h = harness();
  h.deps.extractIntent = async () => ({ intentKey: 'order', slots: { item: 'paneer tikka', restaurant: "Domino's" } });
  await h.s.handleCommand("order a paneer tikka from Domino's");
  const steps = [{ type: 'launch', value: 'z' }, { type: 'type', selector: { resourceId: 'z:id/edittext' }, value: 'dominos' },
    { type: 'type', selector: { resourceId: 'z:id/edittext' }, value: 'chicken wings' }];
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'teach_done', command: 'x', intentKey: 'order', slots: h.s.teaching.slots, steps })));
  assert.equal(h.store.length, 0, 'nothing saved');
  assert.match(h.said().at(-1), /couldn't find "paneer tikka"/);
});

// ---- C: a pending question never swallows a new command ----

async function askedWhichPizza() {
  const h = harness();
  const flow = await teach(h, margheritaDemo);
  h.deps.matchFlow = async () => ({ flowId: flow._id, slots: { restaurant: "Domino's" }, clarify: { slot: 'item', question: 'Which pizza would you like?' } });
  await h.s.handleCommand('order pizza');
  assert.equal(h.said().at(-1), 'Which pizza would you like?');
  h.clear();
  return { h, flow };
}

test('C: "Which pizza?" -> "Farmhouse" is the answer: replays Farmhouse', async () => {
  const { h } = await askedWhichPizza();
  h.deps.matchFlow = async () => ({ flowId: null, slots: {} });
  await h.s.handleCommand('Farmhouse');
  assert.equal(h.replays()[0].slots.item, 'Farmhouse');
  assert.equal(h.replays()[0].slots.restaurant, "Domino's");
});

test('C: "Which pizza?" -> a full new command is a NEW command (it used to be typed into Zomato as the dish)', async () => {
  const { h } = await askedWhichPizza();
  h.deps.matchFlow = async () => ({ flowId: null, slots: {} });
  await h.s.handleCommand('book a cab to the airport');
  assert.equal(h.replays().length, 0, 'nothing replayed');
  assert.ok(h.sent.some((m) => m.type === 'teach_start'), 'an unknown task is offered for teaching (T12)');
});

test('C: a reply that restates the order for the same flow uses ITS values; one for another flow runs that flow', async () => {
  const { h, flow } = await askedWhichPizza();
  h.deps.matchFlow = async () => ({ flowId: flow._id, slots: { item: 'Farmhouse pizza', restaurant: "Domino's" } });
  await h.s.handleCommand("order a farmhouse pizza from Domino's");
  assert.equal(h.replays()[0].slots.item, 'Farmhouse pizza');

  const calc = await teach(h, calculatorDemo);
  h.deps.matchFlow = async () => ({ flowId: flow._id, slots: {}, clarify: { slot: 'item', question: 'Which pizza?' } });
  await h.s.handleCommand('order pizza');
  h.clear();
  h.deps.matchFlow = async () => ({ flowId: calc._id, slots: { num1: '3', num2: '4' } });
  await h.s.handleCommand('calculate 3 plus 4');
  assert.equal(h.replays()[0].flowId, calc._id);
  assert.deepEqual(h.replays()[0].slots, { num1: '3', num2: '4' });
});

test('C: the question expires after 60 s; a later reply is understood as a command of its own', async () => {
  const { h } = await askedWhichPizza();
  h.tick(61_000);
  h.deps.matchFlow = async () => ({ flowId: null, slots: {} });
  await h.s.handleCommand('Farmhouse');
  assert.equal(h.replays().length, 0);
});

// ---- O: consent must be the whole reply ----

test('O: after a correction offer, "Okay, order a margherita" is a command, not consent to rewrite the flow', async () => {
  const h = harness();
  const flow = await teach(h, margheritaDemo);
  let updated = false;
  h.deps.updateFlowSteps = async () => { updated = true; };
  h.s.pendingCorrection = { flowId: flow._id, offer: [{ op: 'mark_optional', step: 1 }], seen: 'x' };
  h.deps.matchFlow = async () => ({ flowId: flow._id, slots: { item: 'margherita' } });
  await h.s.handleCommand('Okay, order a margherita');
  assert.equal(updated, false);
  assert.equal(h.replays()[0].slots.item, 'margherita');
});

// ---- D: status questions ----

test('D: "Did it work?" answers from the last run (it used to open teach mode), naming what was asked', async () => {
  const flow = { _id: 'f1', description: "Order a Farmhouse pizza. From Domino's in Zomato.", steps: [{ type: 'launch' }] };
  const h = harness({ flows: [flow], lastRun: { flowId: 'f1', success: true, utterance: 'Order a Margherita pizza' } });
  await h.s.handleCommand('Did it work?');
  assert.equal(h.said().at(-1), 'Yes, the last run of "Order a Margherita pizza" finished all its steps.');
  assert.ok(!h.sent.some((m) => m.type === 'teach_start'));
});

test('D: a taught description is stored without Deepgram\'s stray full stops', async () => {
  const h = harness();
  const flow = await teach(h, { ...margheritaDemo, command: "Order a Margherita pizza. From Domino's on Zomato." });
  assert.equal(flow.description, "Order a Margherita pizza From Domino's on Zomato");
});

// ---- J + K: recognition hints from taught flows; what was heard is stored ----

test('J: after teaching, the next recording is biased toward the taught dish and restaurant (no fixed list)', async () => {
  const h = harness();
  let used;
  h.deps.stt = (opts) => { used = opts.keyterms; return { write() {}, finish: async () => ({ text: '' }) }; };
  await teach(h, margheritaDemo);
  await h.s.refreshKeyterms();
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_start' })));
  assert.deepEqual(used, ['Margherita pizza', "Domino's"]);
});

test('K: the transcript, confidence and words of a voice command are stored with its replay RunLog, plus the values', async () => {
  const h = harness();
  const flow = await teach(h, margheritaDemo);
  h.deps.matchFlow = async () => ({ flowId: flow._id, slots: { item: 'Farmhouse pizza' } });
  const heard = { text: 'order a farmhouse pizza', confidence: 0.93, words: [{ w: 'order', s: 0.1, e: 0.4, c: 0.99 }] };
  h.deps.stt = () => ({ write() {}, finish: async () => heard });
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_start' })));
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_end' })));
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'run_result', flowId: flow._id, success: true, utterance: heard.text, steps: [] })));
  const run = h.logs.find((l) => l.kind === 'replay');
  assert.deepEqual(run.heard, { ...heard, source: 'voice' });
  assert.equal(run.slots.item, 'Farmhouse pizza');
});

// ---- L: never silent while understanding, never silent when every model is throttled ----

test('L: a slow understanding step is covered by a short spoken acknowledgement; a fast one is not', async () => {
  const h = harness({ flows: [{ _id: 'f1', description: 'x', slots: [], steps: [] }] });
  h.deps.stt = () => ({ write() {}, finish: async () => ({ text: 'do the thing' }) });
  h.deps.matchFlow = () => new Promise((r) => setTimeout(() => r({ flowId: 'f1', slots: {} }), 1500));
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_start' })));
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_end' })));
  assert.deepEqual(h.said(), ['One moment.', 'Okay, doing that now.']);
  h.clear();
  h.deps.matchFlow = async () => ({ flowId: 'f1', slots: {} });
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_start' })));
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_end' })));
  assert.deepEqual(h.said(), ['Okay, doing that now.']);
});

test('L: when every model fails, the user hears why instead of nothing', async () => {
  const h = harness({ flows: [{ _id: 'f1', description: 'x', slots: [], steps: [] }] });
  h.deps.matchFlow = async () => { throw new Error('groq http 429'); };
  await h.s.handleCommand('order a pizza');
  assert.match(h.said().at(-1), /busy right now/);
});

// ---- the assistant's own voice, guesses, and sync ----

test('echo: a command that starts with the assistant\'s last line is understood without it', async () => {
  const h = harness();
  await h.s.say('I stopped at step 12: you asked me to stop.');
  let matched;
  h.deps.matchFlow = async (text) => { matched = text; return { flowId: null, slots: {} }; };
  h.deps.stt = () => ({ write() {}, finish: async () => ({ text: "Step 12. You asked me to stop. Order a Farmhouse pizza.", confidence: 0.98, words: [] }) });
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_start' })));
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_end' })));
  assert.equal(h.sent.find((m) => m.type === 'transcript').text, 'Order a Farmhouse pizza.');
});

test('a one-word or low-confidence voice command never opens teach mode (it asks to hear it again)', async () => {
  for (const heard of [{ text: 'Then', confidence: 0.63 }, { text: 'order something nice', confidence: 0.4 }]) {
    const h = harness({ flows: [{ _id: 'f1', description: 'x', slots: [], steps: [] }] });
    h.deps.stt = () => ({ write() {}, finish: async () => ({ ...heard, words: [] }) });
    await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_start' })));
    await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'audio_end' })));
    assert.ok(!h.sent.some((m) => m.type === 'teach_start'), heard.text);
    assert.match(h.said().at(-1), /didn't catch that/);
  }
});

test('sync: the spoken text is sent only once its audio is ready, immediately followed by the audio', async () => {
  const h = harness();
  const order = [];
  h.deps.synthesize = async () => { order.push('synth'); return Buffer.from('mp3'); };
  h.s.socket.send = (m) => order.push(typeof m === 'string' ? JSON.parse(m).type : 'bytes');
  await h.s.say('Okay, doing that now.');
  assert.deepEqual(order, ['synth', 'say', 'audio', 'bytes']);
});

// ---- "done, thank you" ----

test('"done, thank you" at the end of a run is answered with the glad line, and the run counts as a success', async () => {
  const h = harness();
  const flow = await teach(h, margheritaDemo);
  h.clear();
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'run_result', flowId: flow._id, success: true, finished: true, utterance: 'order a margherita', steps: [] })));
  assert.equal(h.said().at(-1), "I'm glad you got to your destination. Thank you!");
  const run = h.logs.find((l) => l.kind === 'replay');
  assert.equal(run.success, true);
  assert.equal(run.endedByUser, true);
});

test('"thank you" said on its own is a sign-off, never a task to learn; a bare "done" outside teaching is not it', async () => {
  for (const said of ['Done. Thank you.', 'thank you', 'okay thanks', "that's all, thanks"]) {
    const h = harness({ flows: [{ _id: 'f1', description: 'x', slots: [], steps: [] }] });
    await h.s.handleCommand(said);
    assert.equal(h.said().at(-1), "I'm glad you got to your destination. Thank you!", said);
    assert.ok(!h.sent.some((m) => m.type === 'teach_start'), said);
  }
});

test('a run the user ended with a new task stays silent (the new command speaks); one they stopped says so', async () => {
  const h = harness();
  const flow = await teach(h, margheritaDemo);
  h.clear();
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'run_result', flowId: flow._id, success: false, stopped: true, redirected: true, utterance: 'x', steps: [] })));
  assert.deepEqual(h.said(), []);
  assert.ok(!h.logs.some((l) => l.kind === 'match'), 'not counted as a wrong match');
  await h.s.onMessage(Buffer.from(JSON.stringify({ type: 'run_result', flowId: flow._id, success: false, stopped: true, utterance: 'x', steps: [] })));
  assert.equal(h.said().at(-1), "Okay, I've stopped.");
});
