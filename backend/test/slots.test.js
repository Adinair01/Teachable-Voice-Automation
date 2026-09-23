import test from 'node:test';
import assert from 'node:assert/strict';
import { normaliseSlots } from '../src/prompts/slots.js';

test('quantity words become digits, address labels lose "my"/"the", empty slots are dropped', () => {
  assert.deepEqual(normaliseSlots({ item: 'Margherita', quantity: 'two' }), { item: 'Margherita', quantity: '2' });
  assert.deepEqual(normaliseSlots({ quantity: 3 }), { quantity: '3' });
  assert.deepEqual(normaliseSlots({ quantity: '2 pizzas' }), { quantity: '2' });
  assert.deepEqual(normaliseSlots({ address: 'my Work' }), { address: 'Work' });
  assert.deepEqual(normaliseSlots({ quantity: 'some', address: '' }), {});
});
