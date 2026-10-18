import test from 'node:test';
import assert from 'node:assert/strict';

process.env.MONGO_URL = 'x';
process.env.GROQ_API_KEY = 'test';
process.env.GROQ_MODEL = 'model-a';
process.env.GROQ_FALLBACK_MODELS = 'model-b,model-c';
const { groqJson } = await import('../src/lib/groq.js');
const { modelChain } = await import('../src/lib/config.js');
const { z } = await import('zod');

const schema = z.object({ index: z.number() });
const ok = (content) => ({ ok: true, json: async () => ({ choices: [{ message: { content } }] }) });
const fail = (status, retryAfter = '0') => ({ ok: false, status, headers: { get: () => retryAfter } });

/** Records the model asked on each call and replies from a scripted list of responses. */
function script(...responses) {
  const models = [];
  const fetchImpl = async (_url, opts) => {
    models.push(JSON.parse(opts.body).model);
    const r = responses[models.length - 1];
    if (!r) throw new Error('unexpected extra groq call');
    return typeof r === 'function' ? r() : r;
  };
  return { models, fetchImpl };
}

test('the chain is primary first, de-duplicated, blanks dropped', () => {
  assert.deepEqual(modelChain('a', 'b, c'), ['a', 'b', 'c']);
  assert.deepEqual(modelChain('a', 'a, ,b'), ['a', 'b']); // a repeated fallback must not double the work
  assert.deepEqual(modelChain('a', ''), ['a']);
});

test('a rate-limited model hands over to the next model at once, no waiting', async () => {
  const { models, fetchImpl } = script(fail(429, '30'), ok('{"index":3}'));
  const started = Date.now();
  const out = await groqJson({ system: 's', user: 'u', schema, fetchImpl });
  assert.equal(out.index, 3);
  assert.deepEqual(models, ['model-a', 'model-b']);
  assert.ok(Date.now() - started < 1000, 'must not sit out Retry-After while another model is free');
});

test('a model that answers unusably is replaced by the next one', async () => {
  const { models, fetchImpl } = script(ok('not json at all'), ok('{"index":"three"}'), ok('{"index":7}'));
  const out = await groqJson({ system: 's', user: 'u', schema, fetchImpl });
  assert.equal(out.index, 7);
  assert.deepEqual(models, ['model-a', 'model-b', 'model-c']);
});

test('output the caller rejects as invented goes to the next model', async () => {
  const { models, fetchImpl } = script(ok('{"index":99}'), ok('{"index":3}'));
  const out = await groqJson({
    system: 's', user: 'u', schema, fetchImpl,
    validate: (o) => [3, 5].includes(o.index),
  });
  assert.equal(out.index, 3);
  assert.deepEqual(models, ['model-a', 'model-b']);
});

test('a decommissioned or 5xx model is skipped, not fatal', async () => {
  const { models, fetchImpl } = script(fail(404), fail(503), ok('{"index":1}'));
  assert.equal((await groqJson({ system: 's', user: 'u', schema, fetchImpl })).index, 1);
  assert.deepEqual(models, ['model-a', 'model-b', 'model-c']);
});

test('a timeout or network drop also hands over', async () => {
  const { models, fetchImpl } = script(() => { throw new Error('TimeoutError'); }, ok('{"index":2}'));
  assert.equal((await groqJson({ system: 's', user: 'u', schema, fetchImpl })).index, 2);
  assert.deepEqual(models, ['model-a', 'model-b']);
});

test('a bad API key stops the chain immediately (same answer from every model)', async () => {
  const { models, fetchImpl } = script(fail(401));
  await assert.rejects(groqJson({ system: 's', user: 'u', schema, fetchImpl }), /401/);
  assert.deepEqual(models, ['model-a']);
});

test('when every model is throttled the chain is retried once after Retry-After', async () => {
  const { models, fetchImpl } = script(
    fail(429), fail(429), fail(429),
    fail(429), fail(429), ok('{"index":4}'),
  );
  const out = await groqJson({ system: 's', user: 'u', schema, fetchImpl });
  assert.equal(out.index, 4);
  assert.equal(models.length, 6, 'one full second pass over the chain, then stop');
});

test('an exhausted chain surfaces the error instead of swallowing it', async () => {
  const { fetchImpl } = script(ok('bad'), ok('bad'), ok('bad'));
  await assert.rejects(groqJson({ system: 's', user: 'u', schema, fetchImpl, retries: 0 }));
});

test('when every model invents, the last answer is handed back for the caller to reject', async () => {
  // Graceful degradation matters: pickElement must still be able to say index=null ("ask, do not tap")
  // and matchFlow flowId=null ("not learned yet") instead of the pipeline throwing.
  const { models, fetchImpl } = script(ok('{"index":99}'), ok('{"index":98}'), ok('{"index":97}'));
  const out = await groqJson({ system: 's', user: 'u', schema, fetchImpl, validate: () => false });
  assert.equal(out.index, 97);
  assert.equal(models.length, 3);
});
