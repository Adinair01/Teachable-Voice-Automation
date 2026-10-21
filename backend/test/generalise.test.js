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
  // A tapped caption is the NAME of the thing tapped, so it becomes the slot as a whole (see the {{restaurant}} test).
  assert.equal(flow.steps[2].selector.text, '{{query}}');
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
  // The demo's package expectation is dropped; what replaces it is "the page we opened names the page asked for".
  assert.deepEqual(f.steps[1].expect, { textContains: '{{page}}' });
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
  assert.equal(card.selector.text, '{{restaurant}}');
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
  assert.equal(card.selector.text, '{{restaurant}}');
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

/** A demo done straight after a search shows ONE of the control, which used to learn no slot at all. */
test('a control that was alone on the demo screen is still anchored to its row when its caption names an action', () => {
  const add = (over) => generalise({
    command: "order a margherita pizza from Domino's", intentKey: 'order_food',
    slots: { item: 'Margherita pizza' },
    steps: [
      { type: 'launch', value: 'com.application.zomato' },
      { type: 'tap', selector: { resourceId: 'z:id/text_view_title', contentDesc: 'ADD' },
        context: ['Margherita Pizza', '₹109', 'ADD'], ...over },
    ],
  }).steps[1];
  for (const candidates of [1, 7]) {
    const s = add({ candidates });
    assert.equal(s.selector.near, '{{item}}', `candidates=${candidates} must still aim at the row`);
    assert.equal(s.slotRef, 'item');
    assert.equal(s.selector.resourceId, '', 'the demonstrated row id must not outrank the anchor');
  }
});

test('a uniquely and distinctively captioned control is NOT anchored, even if its row mentions the item', () => {
  // "View Cart" names itself. Anchoring it would make the step fail whenever the cart bar stops echoing the dish.
  const f = generalise({
    command: 'order a margherita pizza', intentKey: 'order_food', slots: { item: 'Margherita pizza' },
    steps: [
      { type: 'launch', value: 'z' },
      { type: 'tap', selector: { resourceId: 'z:id/cart', text: 'View Cart' }, candidates: 1,
        context: ['View Cart', '1 item', 'Margherita Pizza'] },
    ],
  });
  assert.equal(f.steps[1].selector.near ?? '', '');
  assert.equal(f.steps[1].slotRef, null);
});

test('a slot-bound tap verifies that the screen it opened names the value asked for', () => {
  const f = generalise({
    command: "order a margherita pizza from Domino's", intentKey: 'order_food',
    slots: { item: 'Margherita pizza', restaurant: "Domino's" },
    steps: [
      { type: 'launch', value: 'z' },
      { type: 'tap', selector: { text: "Domino's Pizza" }, candidates: 1,
        screen: ["Domino's Pizza", '30-35 mins', 'Search in Domino\'s Pizza'] },
      // a screen that named nothing we asked for leaves the expectation alone
      { type: 'tap', selector: { text: 'Veg' }, candidates: 1, screen: ['Filters', 'Sort by'] },
    ],
  });
  assert.equal(f.steps[1].expect.textContains, '{{restaurant}}', 'stored as the bare token, not the demo wording');
  assert.equal(f.steps[2].expect.textContains ?? '', '');
});

// ---- Part 0: no value from the demonstration may survive into a replay as a literal or a fragment ----

const typeStep = (spoken, typed, slot = 'item') => generalise({ command: `order ${spoken}`, intentKey: 'k', slots: { [slot]: spoken },
  steps: [{ type: 'launch', value: 'p' }, { type: 'type', selector: { resourceId: 'x:id/edittext' }, value: typed }] });

test('a typed value that only partly matches the spoken one becomes the WHOLE slot, never slot plus leftovers', () => {
  // Before: "{{item}} pizza" (replays "Farmhouse pizza pizza"), "{{item}}s", and "farm{{item}}" ("farmMargherita").
  assert.equal(typeStep('Margherita pizzas', 'Margherita pizza').steps[1].value, '{{item}}');
  assert.equal(typeStep('Margherita pizza', 'Margherita pizzas').steps[1].value, '{{item}}');
  assert.equal(typeStep('Farm house pizza', 'farmhouse').steps[1].value, '{{item}}');
  // An exact occurrence inside a longer query keeps the query's own words: "pizza near me" is a template.
  assert.equal(typeStep('pizza', 'pizza near me', 'query').steps[1].value, '{{query}} near me');
});

test('the longest-word fallback only takes whole words ("house" inside "farmhouse" is not the slot)', () => {
  const f = generalise({ command: 'order farm house pizza', intentKey: 'k', slots: { item: 'Farm house pizza' },
    steps: [{ type: 'launch', value: 'p' }, { type: 'tap', selector: { text: 'Lighthouse Cafe' } }] });
  assert.equal(f.steps[1].selector.text, 'Lighthouse Cafe');
  assert.equal(f.steps[1].slotRef, null);
});

test('a recogniser spelling ("Margarita") still templates the menu spelling ("Margherita"), never stored literally', () => {
  const f = typeStep('Margarita pizza', 'Margherita');
  assert.equal(f.steps[1].value, '{{item}}');
  assert.ok(!JSON.stringify(f.steps).includes('Margherita'), 'no literal dish left in the flow');
  // ...but two genuinely different short names are not merged.
  assert.equal(typeStep('Veg Pizza', 'Egg').steps[1].value, 'Egg');
});

test('calculator: a number spoken as a word templates the digit key, and the slot is stored in its taught (digit) shape', () => {
  // Before: num1="five" never matched the key "5", which stayed a literal, so every replay pressed 5 again.
  const f = generalise({ command: 'calculate five plus two', intentKey: 'calculate', slots: { num1: 'five', num2: 'two' },
    steps: [{ type: 'launch', value: 'calc' }, { type: 'tap', selector: { resourceId: 'c:id/digit_5', text: '5' } },
      { type: 'tap', selector: { contentDesc: 'Add' } }, { type: 'tap', selector: { resourceId: 'c:id/digit_2', text: '2' } }] });
  assert.equal(f.steps[1].selector.text, '{{num1}}');
  assert.equal(f.steps[3].selector.text, '{{num2}}');
  assert.deepEqual(f.slots, [{ name: 'num1', example: '5' }, { name: 'num2', example: '2' }]);
  assert.deepEqual(f.missing, []);
});

test('a slot the command named but the demo never used is reported missing, so the flow is not saved with a literal', () => {
  // The exact shape of "taught Margherita, said Farmhouse, got Margherita": restaurant found, dish typed in a form
  // nothing could match, the flow saved anyway, and its typed step replayed the demo's dish forever.
  const f = generalise({ command: "order a paneer tikka from Domino's", intentKey: 'k', slots: { item: 'paneer tikka', restaurant: "Domino's" },
    steps: [{ type: 'launch', value: 'z' }, { type: 'type', selector: { resourceId: 'z:id/edittext' }, value: 'dominos' },
      { type: 'type', selector: { resourceId: 'z:id/edittext' }, value: 'chicken wings' }] });
  assert.deepEqual(f.missing.map((s) => s.name), ['item']);
  // Quantity and address are applied by added steps, never by the demo, so they are never "missing".
  const g = generalise({ command: 'order two dominos', intentKey: 'k', slots: { restaurant: 'dominos', quantity: '2', address: 'work' },
    steps: [{ type: 'launch', value: 'z' }, { type: 'type', selector: {}, value: 'dominos' }] });
  assert.deepEqual(g.missing, []);
});

test('restaurant suggestion: learned as {{restaurant}} with a landing check, so another restaurant resolves to a real caption', () => {
  const f = generalise({ command: "order a margherita from Domino's", intentKey: 'k', slots: { item: 'margherita', restaurant: "Domino's" },
    steps: [{ type: 'launch', value: 'z' }, { type: 'tap', selector: { text: "Domino's Pizza", className: 'android.view.View' }, candidates: 3 }] });
  assert.equal(f.steps[1].selector.text, '{{restaurant}}');
  assert.deepEqual(f.steps[1].expect, { textContains: '{{restaurant}}' });
});

test('a caption with the value INSIDE the control\'s own wording keeps that wording: "Search in {{restaurant}}"', () => {
  const f = generalise({ command: "order from Domino's", intentKey: 'k', slots: { restaurant: "Domino's" },
    steps: [{ type: 'launch', value: 'z' }, { type: 'tap', selector: { contentDesc: "Search in Domino's Pizza" }, candidates: 1 }] });
  assert.equal(f.steps[1].selector.contentDesc, 'Search in {{restaurant}}');
});

// ---- numbers entered key by key (seen live 2026-10-22: "Calculate 30 divided by five" was refused) ----

const key = (k, extra = {}) => ({ type: 'tap', selector: { resourceId: `c:id/digit_${k}`, text: k, className: 'android.widget.Button' }, ...extra });
const op = (desc) => ({ type: 'tap', selector: { resourceId: `c:id/op`, contentDesc: desc, className: 'android.widget.Button' } });

test('a multi-digit number tapped key by key is ONE slot step: "30 divided by 5" learns {{num1}} / {{num2}}', () => {
  const f = generalise({ command: 'Calculate 30 divided by five', intentKey: 'calculate', slots: { num1: '30', num2: 'five' },
    steps: [{ type: 'launch', value: 'calc' }, key('3'), key('0'), op('Divide'), key('5'), op('Equals')] });
  assert.deepEqual(f.missing, []);
  assert.deepEqual(f.steps.map((s) => s.selector.text || s.selector.contentDesc || s.type), ['launch', '{{num1}}', 'Divide', '{{num2}}', 'Equals']);
  assert.deepEqual(f.slots, [{ name: 'num1', example: '30' }, { name: 'num2', example: '5' }]);
});

test('the same number for both slots ("12 plus 12") fills num1 then num2, not num1 twice', () => {
  const f = generalise({ command: 'calculate 12 plus 12', intentKey: 'calculate', slots: { num1: '12', num2: '12' },
    steps: [{ type: 'launch', value: 'calc' }, key('1'), key('2'), op('Add'), key('1'), key('2'), op('Equals')] });
  assert.deepEqual(f.missing, []);
  assert.deepEqual(f.steps.filter((s) => s.slotRef).map((s) => s.selector.text), ['{{num1}}', '{{num2}}']);
});

test('a key tapped by mistake before the number stays a plain step, and single keys still template as before', () => {
  const f = generalise({ command: 'calculate 30 divided by 5', intentKey: 'calculate', slots: { num1: '30', num2: '5' },
    steps: [{ type: 'launch', value: 'calc' }, key('3'), key('3'), key('0'), op('Divide'), key('5'), op('Equals')] });
  assert.deepEqual(f.missing, []);
  assert.deepEqual(f.steps.map((s) => s.selector.text || s.selector.contentDesc || s.type), ['launch', '3', '{{num1}}', 'Divide', '{{num2}}', 'Equals']);
});

test('a row anchored on a long product title keeps only the item (Amazon, seen live)', () => {
  const f = generalise({ command: 'Order a s 25 ultra from Amazon', intentKey: 'order', slots: { item: 's 25 ultra' },
    steps: [{ type: 'launch', value: 'in.amazon.mShop.android.shopping' },
      { type: 'tap', selector: { text: 'Add to cart', className: 'android.widget.Button' }, candidates: 4,
        context: ['Sponsored Ad - Samsung Galaxy S25 Ultra 5G AI Smartphone (Titanium Silverblue, 12GB RAM, 256GB Storage)', '₹1,29,999', 'Add to cart'] }] });
  assert.equal(f.steps[1].selector.near, '{{item}}');
  assert.equal(f.steps[1].slotRef, 'item');
  // A short caption keeps its own wording, as before.
  const g = generalise({ command: 'order a margherita', intentKey: 'order', slots: { item: 'margherita' },
    steps: [{ type: 'launch', value: 'z' }, { type: 'tap', selector: { contentDesc: 'ADD' }, candidates: 6, context: ['Margherita Pizza', '₹114', 'ADD'] }] });
  assert.equal(g.steps[1].selector.near, '{{item}} Pizza');
});
