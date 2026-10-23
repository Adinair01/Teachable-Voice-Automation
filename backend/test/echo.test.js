import test from 'node:test';
import assert from 'node:assert/strict';
import { stripEcho } from '../src/services/echo.js';

test('the assistant\'s own words at the start of a command are removed (all three cases seen live)', () => {
  assert.equal(stripEcho("Step 12. You asked me to stop. Order a Farmhouse pizza. From Domino's in Zomato.",
    ['I stopped at step 12: you asked me to stop.']), "Order a Farmhouse pizza. From Domino's in Zomato.");
  assert.equal(stripEcho('Done. Calculate seven minus eight.', ['Done.']), 'Calculate seven minus eight.');
  assert.equal(stripEcho('I only saw an app being opened. To open an app, just say open',
    ['I only saw an app being opened. To open an app, just say open and its name. Teach me a task inside the app instead.']), '');
});

test('the user\'s own words are kept, even short ones the assistant also uses', () => {
  const prompt = "I haven't learned that yet. Show me once: do it on the screen, then say done.";
  assert.equal(stripEcho('done', [prompt]), 'done', '"done" ends a teach and must never be eaten');
  assert.equal(stripEcho('order a margherita pizza', ['Okay, doing that now.']), 'order a margherita pizza');
  assert.equal(stripEcho('skip it', ['Tell me what to tap, say skip to leave this step out, or say stop.']), 'skip it');
});

test('the user repeating their own command, which the assistant just quoted back, is not an echo', () => {
  const recent = ['Learned: Order a s 25 ultra from Amazon.'];
  assert.equal(stripEcho('Order a s 25 ultra from Amazon', recent, ['Order a s 25 ultra from Amazon.']), 'Order a s 25 ultra from Amazon');
  // The assistant's own line picked up by the mic is still removed: it starts with "Learned", which the user never said.
  assert.equal(stripEcho('Learned order a s 25 ultra from Amazon', recent, ['Order a s 25 ultra from Amazon.']), '');
});
