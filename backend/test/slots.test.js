import test from 'node:test';
import assert from 'node:assert/strict';
import { normaliseSlots, withDefaults } from '../src/prompts/slots.js';

test('quantity words become digits, address labels lose "my"/"the", empty slots are dropped', () => {
  assert.deepEqual(normaliseSlots({ item: 'Margherita', quantity: 'two' }), { item: 'Margherita', quantity: '2' });
  assert.deepEqual(normaliseSlots({ quantity: 3 }), { quantity: '3' });
  assert.deepEqual(normaliseSlots({ quantity: '2 pizzas' }), { quantity: '2' });
  assert.deepEqual(normaliseSlots({ address: 'my Work' }), { address: 'Work' });
  assert.deepEqual(normaliseSlots({ quantity: 'some', address: '' }), {});
});

test('unmentioned slots fall back to the taught example, optional ones stay unset', () => {
  const flowSlots = [{ name: 'item', example: 'Margherita' }, { name: 'restaurant', example: "Domino's" }, { name: 'address', example: 'home' }];
  assert.deepEqual(withDefaults({ item: 'Farmhouse' }, flowSlots, ['address', 'quantity']), { item: 'Farmhouse', restaurant: "Domino's" });
});

test('"null"-like values from the model count as not said, so the taught default applies', () => {
  const flowSlots = [{ name: 'item', example: 'Margherita' }, { name: 'restaurant', example: "Domino's" }];
  const got = withDefaults(normaliseSlots({ item: 'margherita pizza', restaurant: 'null', quantity: 'None', address: null }), flowSlots, ['address', 'quantity']);
  assert.deepEqual(got, { item: 'margherita pizza', restaurant: "Domino's" });
});
