import test from 'node:test';
import assert from 'node:assert/strict';
import { generalise } from '../src/services/generalise.js';
import { isSensitiveStep } from '../src/services/sensitive.js';

const steps = [
  { type: 'launch', value: 'com.android.chrome' },
  { type: 'type', selector: { className: 'android.widget.EditText' }, value: 'Burger' },
  { type: 'tap', selector: { text: 'burger near me' } },
  { type: 'tap', selector: { text: 'Place order' } },
  { type: 'type', selector: { resourceId: 'x:id/password' }, value: 'hunter2' },
];
const flow = generalise({ command: 'search burger', intentKey: 'search_google', slots: { query: 'burger' }, steps });

test('typed value and tapped text become slot tokens (case-insensitive)', () => {
  assert.equal(flow.steps[1].value, '{{query}}');
  assert.equal(flow.steps[1].slotRef, 'query');
  assert.equal(flow.steps[2].selector.text, '{{query}} near me');
});
test('credential fields are dropped, never stored', () => {
  assert.ok(!JSON.stringify(flow).includes('hunter2'));
  assert.equal(flow.steps.length, 4);
});
test('sensitive taps are flagged, others are not', () => {
  assert.equal(flow.steps[3].sensitive, true);
  assert.equal(flow.steps[2].sensitive, false);
  assert.equal(isSensitiveStep({ type: 'type', selector: { text: 'send' } }), false);
});
test('appPackage comes from the launch step; slotsFound reported', () => {
  assert.equal(flow.appPackage, 'com.android.chrome');
  assert.equal(flow.slotsFound, true);
});
