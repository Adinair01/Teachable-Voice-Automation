import test from 'node:test';
import assert from 'node:assert/strict';

process.env.MONGO_URL = 'x';
process.env.GROQ_API_KEY = 'test';
const { pickElement, pickElementInput } = await import('../src/prompts/pickElement.js');

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
