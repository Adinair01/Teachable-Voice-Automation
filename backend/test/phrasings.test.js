import test from 'node:test';
import assert from 'node:assert/strict';

process.env.MONGO_URL = 'x';
process.env.GROQ_API_KEY = 'test';
const { pickPhrasings } = await import('../src/services/phrasings.js');

const flows = [
  { _id: 'f1', description: "Order a Margherita pizza from Domino's on Zomato" },
  { _id: 'f2', description: 'Add five and three' },
];
const run = (flowId, utterance) => ({ flowId, utterance });

test('keeps the newest distinct wordings per flow, capped at three', () => {
  const out = pickPhrasings([
    run('f1', 'get me a margherita from dominos'),
    run('f1', 'margherita pizza from dominos please'),
    run('f1', 'I want a margherita from dominos'),
    run('f1', 'order a margherita, dominos'), // 4th: dropped, the cap is 3
    run('f2', 'what is 9 plus 4'),
  ], flows);
  assert.equal(out.f1.length, 3);
  assert.equal(out.f1[0], 'get me a margherita from dominos', 'newest first');
  assert.deepEqual(out.f2, ['what is 9 plus 4']);
});

test('drops near-duplicates and anything that just repeats the taught description', () => {
  const out = pickPhrasings([
    run('f1', 'Get me a margherita from dominos'),
    run('f1', 'get me a  margherita from dominos!'), // same once punctuation/case are ignored
    run('f1', "Order a Margherita pizza from Domino's on Zomato"), // the description itself adds nothing
  ], flows);
  assert.deepEqual(out.f1, ['Get me a margherita from dominos']);
});

test('runs for a flow that no longer exists, and empty utterances, are ignored', () => {
  const out = pickPhrasings([run('gone', 'something'), run('f1', ''), run('f1', '   ')], flows);
  assert.deepEqual(out, {});
});
