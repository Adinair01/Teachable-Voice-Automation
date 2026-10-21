import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { part0Fixture, FIXTURE_PATH, learn, replayValues } from './fixtures/part0.js';
import { calculatorDemo, margheritaDemo, farmhouseDemo, toFarmhouse, toMargherita } from './fixtures/demos.js';

/**
 * Part 0, "replays use the taught values": teach once, replay with new values, and check that NOTHING of the taught
 * value survives anywhere the phone reads — typed text, tapped caption, row anchor, landing check. The Kotlin half
 * (android ReplayChainTest) replays the same flows and asserts what is typed and tapped.
 */
const demoValue = (flow, values) => JSON.stringify(flow.steps).match(new RegExp(values.join('|'), 'i'))?.[0] ?? null;

test('the Android fixture is what the backend learns today (regenerate: node scripts/part0-fixture.mjs)', () => {
  assert.deepEqual(JSON.parse(readFileSync(FIXTURE_PATH, 'utf8')), part0Fixture());
});

test('calculator taught 5+2 (slots extracted as WORDS): 7+9, 100+1 and 5+2 again each reach the phone as digits', () => {
  const flow = learn(calculatorDemo);
  assert.equal(demoValue(flow, ['five', 'two', '"5"', '"2"']), null, 'no taught number left as a literal in any step');
  assert.deepEqual(replayValues(flow, { num1: '7', num2: '9' }), { num1: '7', num2: '9' });
  assert.deepEqual(replayValues(flow, { num1: 'seven', num2: 'nine' }), { num1: '7', num2: '9' });
  assert.deepEqual(replayValues(flow, { num1: 'one hundred', num2: 'one' }), { num1: '100', num2: '1' });
  assert.deepEqual(replayValues(flow, { num1: '5', num2: '2' }), { num1: '5', num2: '2' });
});

test('zomato taught Margherita -> Farmhouse, and taught Farmhouse -> Margherita: the taught dish is nowhere in the flow', () => {
  for (const [demo, replay, taught, asked] of [[margheritaDemo, toFarmhouse, 'margherita', 'Farmhouse pizza'], [farmhouseDemo, toMargherita, 'farmhouse', 'Margherita pizza']]) {
    const flow = learn(demo);
    assert.equal(demoValue(flow, [taught, 'domino']), null, `no "${taught}" or restaurant literal in ${demo.command}`);
    const typedItem = flow.steps.find((s) => s.type === 'type' && s.slotRef === 'item');
    const add = flow.steps.find((s) => s.selector.contentDesc === 'ADD');
    assert.equal(typedItem.value, '{{item}}');
    assert.equal(add.selector.near, '{{item}}');
    assert.equal(replayValues(flow, replay.model).item, asked);
  }
});
