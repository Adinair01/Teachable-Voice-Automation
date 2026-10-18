import test from 'node:test';
import assert from 'node:assert/strict';
import { normaliseSlots, replaySlots, wordsToNumber, fitToTaught, askFor } from '../src/prompts/slots.js';

const OPTIONAL = ['quantity', 'address'];

// Slot definitions exactly as stored for the two flows taught on 2026-10-19.
const calculator = [{ name: 'num1', example: '5' }, { name: 'num2', example: '2' }];
const zomato = [
  { name: 'item', example: 'Farmhouse pizza' }, { name: 'restaurant', example: "Domino's" },
  { name: 'quantity', example: '1' }, { name: 'address', example: 'home' },
];

/** The session's path, end to end: model output -> normaliseSlots -> replaySlots. */
const sent = (raw, flowSlots) => replaySlots(normaliseSlots(raw), flowSlots, OPTIONAL);

// ---- 1. calculator: taught 5 + 2 ----

test('calculator: the NEW numbers are replayed, in digits, whichever model in the ladder answered', () => {
  // Raw outputs observed live for "Calculate seven plus nine": qwen -> digits, gpt-oss-120b -> words.
  assert.deepEqual(sent({ num1: '7', num2: '9' }, calculator).slots, { num1: '7', num2: '9' });
  assert.deepEqual(sent({ num1: 'seven', num2: 'nine' }, calculator).slots, { num1: '7', num2: '9' });
});

test('calculator: multi-word numbers become digits ("one hundred" plus "one")', () => {
  // gpt-oss-20b and gpt-oss-120b both returned these words for "calculate one hundred plus one".
  assert.deepEqual(sent({ num1: 'one hundred', num2: 'one' }, calculator).slots, { num1: '100', num2: '1' });
  assert.deepEqual(sent({ num1: 'twelve', num2: 'thirty' }, calculator).slots, { num1: '12', num2: '30' });
});

test('calculator: the original wording still replays 5 + 2 exactly', () => {
  assert.deepEqual(sent({ num1: '5', num2: '2' }, calculator).slots, { num1: '5', num2: '2' });
  assert.deepEqual(sent({ num1: 'five', num2: 'two' }, calculator).slots, { num1: '5', num2: '2' });
});

test('calculator: a missing number is asked for, never filled with the taught one', () => {
  const r = sent({ num1: '7' }, calculator);
  assert.equal(r.ask?.name, 'num2');
  assert.equal(askFor(r.ask), 'Which second number should I use? Last time it was 2.');
});

// ---- 2. Zomato: taught a Farmhouse pizza from Domino's ----

test("zomato: a different dish is replayed, never the taught Farmhouse", () => {
  const r = sent({ item: 'Margherita pizza', restaurant: "Domino's", quantity: 'null', address: 'null' }, zomato);
  assert.equal(r.slots.item, 'Margherita pizza');
  assert.equal(r.slots.restaurant, "Domino's");
  assert.equal(r.slots.quantity, undefined, "no quantity said: the app's own is kept");
});

test('zomato: no restaurant named -> the taught one (T3: "get me a margherita" means from Domino\'s)', () => {
  assert.deepEqual(sent({ item: 'margherita' }, zomato).slots, { item: 'margherita', restaurant: "Domino's" });
});

test('zomato: no dish named -> it ASKS which, it does not quietly order Farmhouse again', () => {
  const r = sent({ restaurant: "Domino's" }, zomato);
  assert.equal(r.ask?.name, 'item');
  assert.equal(askFor(r.ask), 'Which item should I use? Last time it was Farmhouse pizza.');
  assert.equal(r.slots.item, undefined);
});

test('zomato: a dish whose name contains a number word is never turned into a number', () => {
  // The item was taught with words, so it is left exactly as said.
  assert.equal(sent({ item: 'Seven Cheese Pizza' }, zomato).slots.item, 'Seven Cheese Pizza');
  assert.equal(sent({ item: 'one' }, zomato).slots.item, 'one');
});

// ---- 3. a different app: the fix is in the shared path, not per use case ----

test('clock timer: a numeric slot is fitted the same way ("ten" minutes -> 10)', () => {
  const timer = [{ name: 'minutes', example: '5' }];
  assert.deepEqual(sent({ minutes: 'ten' }, timer).slots, { minutes: '10' });
  assert.deepEqual(sent({ minutes: 'twenty five' }, timer).slots, { minutes: '25' });
});

test('settings: a word slot replays the new value as said', () => {
  const settings = [{ name: 'section', example: 'Battery' }];
  assert.deepEqual(sent({ section: 'Wi-Fi' }, settings).slots, { section: 'Wi-Fi' });
  assert.equal(sent({}, settings).ask?.name, 'section', 'nothing named: ask which, not reopen Battery');
});

// ---- the pieces ----

test('wordsToNumber reads spoken numbers and refuses anything that is not wholly one', () => {
  const cases = { seven: 7, twelve: 12, 'twenty-five': 25, 'one hundred and one': 101, 'five hundred': 500,
    'two thousand five hundred': 2500, 'one lakh': 100000, '5 hundred': 500, '42': 42, '3.5': 3.5, zero: 0 };
  for (const [w, n] of Object.entries(cases)) assert.equal(wordsToNumber(w), n, w);
  for (const w of ['pizza', 'seven cheese', '', 'twelve pizzas', 'Farmhouse']) assert.equal(wordsToNumber(w), null, w);
});

test('fitToTaught only touches slots taught as numbers', () => {
  assert.deepEqual(fitToTaught({ a: 'seven', b: 'seven' }, [{ name: 'a', example: '1' }, { name: 'b', example: 'x' }]), { a: '7', b: 'seven' });
});

test('quantity words beyond ten now work too (previously "twelve" was silently dropped)', () => {
  assert.equal(normaliseSlots({ quantity: 'twelve' }).quantity, '12');
  assert.equal(normaliseSlots({ quantity: 'two' }).quantity, '2');
});
