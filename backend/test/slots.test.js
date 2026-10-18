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

test('politeness and filler words are stripped from slot values, the value itself is kept', () => {
  const { restaurant, item, address } = normaliseSlots({
    restaurant: 'dominos na', item: 'margherita pizza please', address: 'just work now',
  });
  assert.equal(restaurant, 'dominos');
  assert.equal(item, 'margherita pizza');
  assert.equal(address, 'work');
});

test('a value that is only filler, or legitimately contains such a word, is handled sensibly', () => {
  assert.deepEqual(normaliseSlots({ a: 'please' }), { a: 'please' }); // nothing left to strip to: kept as-is
  assert.equal(normaliseSlots({ item: 'naan bread' }).item, 'naan bread'); // "naan" is not "na"
  assert.equal(normaliseSlots({ item: 'okay dokey pizza' }).item, 'dokey pizza');
});
