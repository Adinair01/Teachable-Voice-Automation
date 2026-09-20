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
