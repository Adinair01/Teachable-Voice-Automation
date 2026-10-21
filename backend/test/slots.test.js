import test from 'node:test';
import assert from 'node:assert/strict';
import { normaliseSlots, withDefaults, wordsToNumber, quantityOf, replaySlots, askFor } from '../src/prompts/slots.js';

test('quantity words become digits, address labels lose "my"/"the", empty slots are dropped', () => {
  assert.deepEqual(normaliseSlots({ item: 'Margherita', quantity: 'two' }), { item: 'Margherita', quantity: '2' });
  assert.deepEqual(normaliseSlots({ quantity: 3 }), { quantity: '3' });
  assert.deepEqual(normaliseSlots({ quantity: '2 pizzas' }), { quantity: '2' });
  assert.deepEqual(normaliseSlots({ address: 'my Work' }), { address: 'Work' });
  // An unreadable quantity is kept as said (it used to be dropped, so the app silently ordered one): it gets asked about.
  assert.deepEqual(normaliseSlots({ quantity: 'some', address: '' }), { quantity: 'some' });
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

test('quantity: counting words multiply, "a" is one only on its own, and the noun is ignored', () => {
  // Before: "a couple" -> 3, "a pair" -> 3, "a dozen" -> 13, "a couple of" / "two pizzas" / "couple of" -> dropped.
  const cases = { two: '2', 'a couple of': '2', 'a couple': '2', 3: '3', three: '3', 'a pair': '2', 'a dozen': '12',
    'two pizzas': '2', 'couple of': '2', 'two dozen': '24', a: '1', an: '1', 'one': '1', 'twelve boxes': '12' };
  for (const [said, want] of Object.entries(cases)) assert.equal(normaliseSlots({ quantity: said }).quantity, want, said);
  assert.equal(quantityOf('a few'), null);
  assert.equal(quantityOf('some'), null);
});

test('wordsToNumber still refuses anything that is not wholly a number', () => {
  assert.equal(wordsToNumber('a pizza'), null);
  assert.equal(wordsToNumber('Seven Cheese Pizza'), null);
  assert.equal(wordsToNumber('two a'), null);
  assert.equal(wordsToNumber('a hundred'), 100);
});

test('an unreadable quantity is ASKED about, never replaced by the app default of 1', () => {
  const flowSlots = [{ name: 'item', example: 'Farmhouse pizza' }, { name: 'quantity', example: '1' }];
  const r = replaySlots(normaliseSlots({ item: 'Farmhouse pizza', quantity: 'a few' }), flowSlots, ['quantity', 'address']);
  assert.equal(r.ask?.name, 'quantity');
  assert.equal(r.slots.quantity, undefined, 'the unreadable value is not sent to the phone');
  assert.equal(askFor(r.ask), 'How many should I get? I heard "a few", but not a number.');
  // A readable one goes straight through.
  assert.equal(replaySlots(normaliseSlots({ item: 'x', quantity: 'a couple' }), flowSlots, ['quantity']).slots.quantity, '2');
});
