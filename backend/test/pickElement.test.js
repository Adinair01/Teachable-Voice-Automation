import test from 'node:test';
import assert from 'node:assert/strict';

process.env.MONGO_URL = 'x';
process.env.GROQ_API_KEY = 'test';
const { pickElement, pickElementInput } = await import('../src/prompts/pickElement.js');
const { groqJson, resetCooldowns } = await import('../src/lib/groq.js');
test.beforeEach(() => resetCooldowns());
const { z } = await import('zod');

const fakeFetch = (content) => async () => ({ ok: true, json: async () => ({ choices: [{ message: { content } }] }) });
const input = pickElementInput.parse({ intent: 'tap Bluetooth', nodes: [{ i: 3, text: 'Bluetooth' }, { i: 5, text: 'Wi-Fi' }] });

test('returns the chosen index when valid', async () => {
  const r = await pickElement(input, { fetchImpl: fakeFetch('{"index":3,"reason":"match"}') });
  assert.equal(r.index, 3);
});
test('rejects an index the model invented', async () => {
  const r = await pickElement(input, { fetchImpl: fakeFetch('{"index":99}') });
  assert.equal(r.index, null);
});
test('malformed model output throws (surfaced, not swallowed)', async () => {
  await assert.rejects(pickElement(input, { fetchImpl: fakeFetch('{"index":"three"}') }));
});

test('matchFlow prompt treats slot-only differences as the same task', async () => {
  const { matchFlow } = await import('../src/prompts/matchFlow.js');
  let sent;
  const spy = async (_u, opts) => { sent = JSON.parse(opts.body); return { ok: true, json: async () => ({ choices: [{ message: { content: '{"flowId":"abc123","slots":{"setting":"Display"}}' } }] }) }; };
  const r = await matchFlow('take me to display settings', [{ _id: 'abc123', description: 'open Bluetooth settings', slots: [] }], { fetchImpl: spy });
  assert.match(sent.messages[0].content, /differ only in slot values are the SAME task/);
  assert.equal(r.flowId, 'abc123');
  const bad = await matchFlow('x', [{ _id: 'abc123', description: 'd', slots: [] }], { fetchImpl: async () => ({ ok: true, json: async () => ({ choices: [{ message: { content: '{"flowId":"zzz","slots":{}}' } }] }) }) });
  assert.equal(bad.flowId, null); // ids the model invents are rejected
});

test('a throttled groq call is answered by the next model in the chain', async () => {
  const calls = [];
  const fetchImpl = async () => {
    calls.push(1);
    if (calls.length === 1) return { ok: false, status: 429, headers: { get: () => '0' } };
    return { ok: true, json: async () => ({ choices: [{ message: { content: '{"index":1}' } }] }) };
  };
  const out = await groqJson({ system: 's', user: 'u', schema: z.object({ index: z.number() }), fetchImpl });
  assert.equal(calls.length, 2);
  assert.equal(out.index, 1);
});

test('only the item slot can be clarified: a search term is never answered with "Which pizza?"', async () => {
  const { matchFlow } = await import('../src/prompts/matchFlow.js');
  const search = { _id: 'f1', description: 'search google for burger', slots: [{ name: 'query', example: 'burger' }] };
  const order = { _id: 'f2', description: 'order a margherita', slots: [{ name: 'item', example: 'margherita' }] };
  const reply = (flowId, slot) => fakeFetch(JSON.stringify({ flowId, slots: {}, intentKey: 'k', clarify: { slot, question: 'Which pizza?' } }));
  assert.equal((await matchFlow('look up pizza on Google', [search, order], { fetchImpl: reply('f1', 'query') })).clarify, null);
  assert.equal((await matchFlow('order pizza', [search, order], { fetchImpl: reply('f2', 'item') })).clarify.question, 'Which pizza?');
});
