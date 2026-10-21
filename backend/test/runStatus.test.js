import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isStatusQuery, statusLine, describeStep, sentence, tidyCommand } from '../src/services/runStatus.js';

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

test('T14: the ways a judge actually asks are recognised (each of these used to open TEACH MODE)', () => {
  for (const q of ['Did it work?', 'did that work', 'What happened last time?', 'Did the last order go through?',
    'did my order go through', 'Has it finished?', 'Was that done?', 'where did you stop',
    'what is the status of the last run', 'Did my last order go through?'])
    assert.ok(isStatusQuery(q), q);
  for (const q of ['Order two pizzas', 'tell me a joke', 'book a cab', 'open the last tab'])
    assert.ok(!isStatusQuery(q), q);
});

test('T14 reports what was ASKED (run.utterance), resolves slot tokens, and never ends in a stale question', () => {
  const zomato = { description: "Order a Farmhouse pizza. From Domino's in Zomato.",
    steps: [{ type: 'launch' }, { type: 'tap', selector: { text: '{{restaurant}}' } }] };
  const run = { success: false, utterance: "Order a Margherita pizza from Domino's on Zomato", slots: { restaurant: 'Pizza Hut' },
    stepResults: [{ index: 0, ok: true }, { index: 1, ok: false, error: 'I can\'t find "Pizza Hut". This screen shows "A", "B". What should I do?' }] };
  const line = statusLine(run, zomato);
  assert.equal(line, 'No. The last run of "Order a Margherita pizza from Domino\'s on Zomato" stopped at step 2, tapping "Pizza Hut": I can\'t find "Pizza Hut".');
  // Without an utterance it falls back to the flow's description, tidied.
  assert.match(statusLine({ success: true }, zomato), /"Order a Farmhouse pizza From Domino's in Zomato"/);
});

test('Deepgram sentence breaks are removed from a taught command', () => {
  assert.equal(tidyCommand("Order a Farmhouse pizza. From Domino's in Zomato."), "Order a Farmhouse pizza From Domino's in Zomato");
  assert.equal(tidyCommand('Calculate 5.5 plus 2'), 'Calculate 5.5 plus 2');
});
