import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isStatusQuery, statusLine, describeStep, sentence } from '../src/services/runStatus.js';

const flow = { description: "order pizza", steps: [{ type: 'launch' }, { type: 'tap', selector: { text: 'Add item ₹114' } }] };

test('recognises status questions and ignores commands', () => {
  for (const q of ['Did the last run succeed?', 'did the last order work', 'How did that go?', 'was the previous run successful'])
    assert.ok(isStatusQuery(q), q);
  for (const q of ['Order a Margherita pizza from Domino\'s on Zomato', 'run the last flow again?x', 'open Gmail'])
    assert.ok(!isStatusQuery(q), q);
});

test('names the step it stopped at on a failure', () => {
  const run = { success: false, stepResults: [{ index: 0, ok: true }, { index: 1, ok: false, error: 'Add item ₹114 never appeared' }] };
  const line = statusLine(run, flow);
  assert.match(line, /^No\./);
  assert.match(line, /step 2, tapping "Add item ₹114"/);
  assert.match(line, /never appeared/);
});

test('success, handover, declined and empty history each get their own line', () => {
  assert.match(statusLine({ success: true }, flow), /^Yes/);
  assert.match(statusLine({ success: true, handover: 'payment' }, flow), /payment screen and handed over/);
  assert.match(statusLine({ declined: true }, flow), /stopped by you/);
  assert.match(statusLine(null, null), /haven't run anything/);
  assert.equal(describeStep(null, 4), 'step 5');
});

test('a reported reason is one finished sentence, never "stop.." or "do?."', () => {
  assert.equal(sentence('you asked me to stop'), 'you asked me to stop.');
  assert.equal(sentence("I can't find \"x\". What should I do?"), "I can't find \"x\". What should I do?");
  assert.equal(sentence('it did not work.  '), 'it did not work.');
  assert.equal(sentence('Done!'), 'Done!');
  assert.equal(sentence(), '.');
});
