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

test('slot-bound taps drop their recorded expectation', () => {
  const f = generalise({ command: 'open bluetooth settings', intentKey: 'open_settings', slots: { page: 'Bluetooth' },
    steps: [{ type: 'launch', value: 'com.android.settings' }, { type: 'tap', selector: { text: 'Bluetooth' }, expect: { package: 'com.oplus.wirelesssettings' } }] });
  assert.equal(f.steps[1].selector.text, '{{page}}');
  assert.deepEqual(f.steps[1].expect, {});
});

test('slot-bound taps drop the demonstrated element id, which would outrank the slot text on replay', () => {
  const f = generalise({ command: 'calculate 5 plus 3', intentKey: 'calculate', slots: { a: '5' },
    steps: [{ type: 'launch', value: 'com.coloros.calculator' }, { type: 'tap', selector: { resourceId: 'com.coloros.calculator:id/digit_5', text: '5' } }] });
  assert.equal(f.steps[1].selector.resourceId, '');
  assert.equal(f.steps[1].selector.text, '{{a}}');
});
test('non-slot taps keep their resource id', () => {
  const f = generalise({ command: 'open bluetooth', intentKey: 'open_settings', slots: {},
    steps: [{ type: 'launch', value: 'com.android.settings' }, { type: 'tap', selector: { resourceId: 'x:id/bt', text: 'Bluetooth' } }] });
  assert.equal(f.steps[1].selector.resourceId, 'x:id/bt');
});

test('a slot value is tokenised in text AND content-desc, so no literal from the demo survives', () => {
  const f = generalise({ command: 'open the stopwatch', intentKey: 'open_feature', slots: { feature: 'stopwatch' },
    steps: [{ type: 'launch', value: 'com.coloros.alarmclock' },
      { type: 'tap', selector: { text: 'Stopwatch', contentDesc: 'Stopwatch' } }] });
  assert.equal(f.steps[1].selector.text, '{{feature}}');
  assert.equal(f.steps[1].selector.contentDesc, '{{feature}}');
});

// The Zomato demo as the recorder sends it: search typed without the apostrophe, one identical ADD per dish row.
test('ordering demo: loose slot matching, row-anchored ADD, implied quantity + address steps before checkout', () => {
  const tap = (selector, extra = {}) => ({ type: 'tap', selector, ...extra });
  const f = generalise({
    command: "Order a Margherita pizza from Domino's on Zomato",
    intentKey: 'order_food',
    slots: { item: 'Margherita pizza', restaurant: "Domino's" },
    steps: [
      { type: 'launch', value: 'com.application.zomato' },
      { type: 'type', selector: { resourceId: 'z:id/edittext' }, value: 'dominos' },
      tap({ text: "Domino's Pizza" }),
      { type: 'type', selector: { resourceId: 'z:id/edittext' }, value: 'margherita' },
      tap({ resourceId: 'z:id/text_view_title', text: 'ADD' }, { context: ['Margherita Pizza', 'Classic delight with 100% real mozzarella', '₹114', 'ADD'] }),
      tap({ resourceId: 'z:id/button', text: 'Add item ₹114' }),
      tap({ resourceId: 'z:id/tv_action_text', text: 'Place Order' }),
    ],
  });
  const [, search, card, dish, add, , ...rest] = f.steps;
  assert.equal(search.value, '{{restaurant}}');
  assert.equal(card.selector.text, '{{restaurant}} Pizza');
  assert.equal(dish.value, '{{item}}');
  assert.equal(add.selector.near, '{{item}}');
  assert.equal(add.slotRef, 'item');
  assert.equal(add.selector.resourceId, '');
  assert.equal(add.context, undefined, 'recording-only context is not stored');
  assert.deepEqual(rest.map((s) => s.type), ['set_quantity', 'choose_address', 'tap'], 'inserted before the sensitive checkout tap');
  assert.equal(rest[0].selector.near, '{{item}}');
  assert.ok(rest[2].sensitive);
  assert.deepEqual(f.slots.map((s) => s.name), ['item', 'restaurant', 'quantity', 'address']);
});

test('a self-labelled tap (own caption carries the slot) is not also anchored to a near template', () => {
  // Real device recordings attach row-sibling context (rating, delivery time) to every tap, including one whose own
  // caption already varies with the slot. Templating `near` from that same context ("{{restaurant}} Pizza") forces
  // an exact-text row match on replay that only the ORIGINAL restaurant's label can ever satisfy: a different
  // restaurant's card ("Zulu's Pizza - Fresh Fanatics") never equals the resolved template, so the row lookup comes
  // back empty and the substring-contains fallback (which would have found it) never runs.
  const tap = (selector, extra = {}) => ({ type: 'tap', selector, ...extra });
  const f = generalise({
    command: "Order a Margherita pizza from Domino's on Zomato",
    intentKey: 'order_food',
    slots: { restaurant: "Domino's" },
    steps: [
      { type: 'launch', value: 'com.application.zomato' },
      { type: 'type', selector: { resourceId: 'z:id/edittext' }, value: 'dominos' },
      tap({ text: "Domino's Pizza" }, { context: ["Domino's Pizza", '4.5 (5.8K+)', '30-35 mins'] }),
    ],
  });
  const [, , card] = f.steps;
  assert.equal(card.selector.text, "{{restaurant}} Pizza");
  assert.ok(!card.selector.near, 'own-caption slot must not also be templated into near');
  assert.equal(card.slotRef, 'restaurant');
});

test('non-ordering flows get no implied slots', () => {
  const f = generalise({ command: 'open bluetooth settings', intentKey: 'open_settings', slots: { page: 'Bluetooth' },
    steps: [{ type: 'launch', value: 'com.android.settings' }, { type: 'tap', selector: { text: 'Bluetooth' } }] });
  assert.deepEqual(f.slots.map((s) => s.name), ['page']);
  assert.ok(!f.steps.some((s) => s.type === 'set_quantity'));
});

test('partial typing into a search box still becomes the item slot (typed "mar" -> {{item}})', () => {
  const f = generalise({ command: 'order margherita pizza from dominos', intentKey: 'order_food',
    slots: { item: 'margherita pizza', restaurant: "Domino's" },
    steps: [
      { type: 'launch', value: 'com.application.zomato' },
      { type: 'type', selector: { resourceId: 'x:id/edittext' }, value: 'dom' },   // partial restaurant
      { type: 'tap', selector: { text: "Domino's Pizza" } },
      { type: 'type', selector: { resourceId: 'x:id/edittext' }, value: 'mar' },   // partial item
    ] });
  const dom = f.steps.find((s) => s.type === 'type' && s.slotRef === 'restaurant');
  const mar = f.steps.find((s) => s.type === 'type' && s.slotRef === 'item');
  assert.ok(dom && dom.value === '{{restaurant}}', 'partial "dom" -> {{restaurant}}');
  assert.ok(mar && mar.value === '{{item}}', 'partial "mar" -> {{item}}');
});
