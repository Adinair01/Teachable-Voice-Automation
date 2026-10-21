import test from 'node:test';
import assert from 'node:assert/strict';

process.env.MONGO_URL = 'x';
process.env.GROQ_API_KEY = 'test';
const { plan, apply, describe, isYes, isNo, stripPrice } = await import('../src/services/correction.js');
const { Flow } = await import('../src/models/Flow.js');

const blank = { resourceId: '', text: '', contentDesc: '', className: '', near: '' };
const tap = (sel, over = {}) => ({ type: 'tap', selector: { ...blank, ...sel }, slotRef: null, value: '', sensitive: false, expect: {}, ...over });
const fix = (c) => ({ kind: 'flow_defect', was: '"x"', now: 'tap "Continue"', ...c });

/** The tail of the REAL stored Zomato flow (steps 7-12), as read from Mongo on 2026-10-18. */
const zomatoTail = () => [
  tap({ contentDesc: 'ADD', className: 'android.view.View', near: '{{item}}' }, { slotRef: 'item' }),
  tap({ resourceId: 'com.application.zomato:id/button', text: 'Add item ₹114', className: 'android.widget.Button' }, { expect: { package: 'com.application.zomato' } }),
  tap({ text: 'Ordered by 90+ customers', className: 'android.view.ViewGroup' }, { expect: { package: 'com.application.zomato' } }),
  tap({ resourceId: 'com.application.zomato:id/positive_button', text: 'Got it', className: 'android.widget.Button' }, { expect: { package: 'com.application.zomato' } }),
  { type: 'set_quantity', selector: { ...blank, near: '{{item}}' }, slotRef: 'quantity', value: '', sensitive: false, expect: {} },
  { type: 'choose_address', selector: { ...blank }, slotRef: 'address', value: '', sensitive: false, expect: {} },
];

test('the stuck-after-Add run: both stale steps are offered, in words, and nothing is applied yet', () => {
  const steps = zomatoTail(); // indexes 0..5 here stand for the stored flow's 7..12
  const corrections = [
    fix({ step: 2, op: 'replace_selector', was: '"Ordered by 90+ customers"', now: 'tap "Continue"', selector: { contentDesc: 'Continue' } }),
    fix({ step: 3, op: 'mark_optional', was: '"Got it"', now: 'skip it' }),
  ];
  const before = JSON.stringify(steps);
  const planned = plan(steps, corrections);
  assert.equal(planned.offer.length, 2);
  assert.equal(planned.refused.length, 0);
  assert.equal(JSON.stringify(steps), before, 'planning must not touch the stored steps');

  const said = describe(planned);
  assert.match(said, /2 parts of this flow look out of date/);
  assert.match(said, /step 3 tapped "Ordered by 90\+ customers", which wasn't there, and you had me tap "Continue"/);
  assert.match(said, /step 4, "Got it", wasn't there and you said to skip it/);
  assert.match(said, /Should I update the flow so it does that from now on\? Say yes to save it, or no to leave it exactly as I learned it\.$/);
});

test('applying them produces the corrected flow, and only those two steps change', () => {
  const steps = zomatoTail();
  const { offer } = plan(steps, [
    fix({ step: 2, op: 'replace_selector', selector: { contentDesc: 'Continue' } }),
    fix({ step: 3, op: 'mark_optional' }),
  ]);
  const out = apply(steps, offer);
  assert.deepEqual(out[2].selector, { ...blank, contentDesc: 'Continue' });
  assert.deepEqual(out[2].expect, {}, "the old expectation described the old target's screen");
  assert.equal(out[3].optional, true);
  assert.deepEqual(out[3].selector, steps[3].selector, 'skipping keeps the step; it is not deleted');
  for (const i of [0, 1, 4, 5]) assert.deepEqual(out[i], { ...steps[i], expect: { ...steps[i].expect }, selector: { ...steps[i].selector } });
  assert.equal(steps[2].selector.text, 'Ordered by 90+ customers', 'the input is never mutated');
});

test('a replacement learned from an answer never carries a price or a layout-specific id', () => {
  const steps = zomatoTail();
  const { offer } = plan(steps, [fix({ step: 1, op: 'replace_selector', selector: { resourceId: 'z:id/button1', text: 'Add item ₹149' } })]);
  assert.equal(offer[0].selector.text, 'Add item');
  assert.equal(offer[0].selector.resourceId, '');
});

test('a tap that would pay, order or delete is refused as something to remember', () => {
  const steps = zomatoTail();
  for (const caption of ['Place order', 'Pay now', 'Proceed to pay', 'Delete', 'Buy now']) {
    const p = plan(steps, [fix({ step: 2, op: 'replace_selector', now: `tap "${caption}"`, selector: { text: caption } })]);
    assert.equal(p.offer.length, 0, caption);
    assert.equal(p.refused[0].why, 'sensitive', caption);
    assert.match(describe(p), /I won't remember tap "/);
  }
});

test('credential fields are never learned', () => {
  const p = plan(zomatoTail(), [fix({ step: 2, op: 'replace_selector', selector: { contentDesc: 'Enter OTP' } })]);
  assert.equal(p.refused[0].why, 'credential field');
});

test('a step tied to a requested value is never rewritten or made optional, whatever the phone says', () => {
  const steps = zomatoTail();
  for (const op of ['replace_selector', 'mark_optional']) {
    const p = plan(steps, [fix({ step: 0, op, selector: { text: 'Continue' } })]); // step 0 is the {{item}}-anchored ADD
    assert.equal(p.offer.length, 0, op);
  }
  // ...nor a step that was flagged sensitive when it was taught.
  const sens = [tap({ text: 'Confirm' }, { sensitive: true })];
  assert.equal(plan(sens, [fix({ step: 0, op: 'mark_optional' })]).offer.length, 0);
});

test('an out-of-range or empty correction is refused, not guessed at', () => {
  const steps = zomatoTail();
  assert.equal(plan(steps, [fix({ step: 99, op: 'mark_optional' })]).offer.length, 0);
  assert.equal(plan(steps, [fix({ step: 99, op: 'insert_tap', selector: { contentDesc: 'Continue' } })]).offer.length, 0);
  assert.equal(plan(steps, [fix({ step: 2, op: 'replace_selector', selector: { className: 'android.widget.Button' } })]).offer.length, 0);
});

test('a flow that stopped short of the cart gets the missing tap inserted BEFORE the quantity step', () => {
  const steps = zomatoTail();
  const { offer } = plan(steps, [fix({ step: 4, op: 'insert_tap', now: 'tap "Continue"', selector: { contentDesc: 'Continue' } })]);
  const out = apply(steps, offer);
  assert.equal(out.length, steps.length + 1);
  assert.equal(out[4].type, 'tap');
  assert.equal(out[4].selector.contentDesc, 'Continue');
  assert.equal(out[5].type, 'set_quantity', 'the quantity step still follows it, so it runs on the cart');
  assert.match(describe({ offer, refused: [], situational: false }), /never gets to the cart, and you had me tap "Continue"/);
});

test('several insertions land where intended regardless of the order they arrive in', () => {
  const steps = [tap({ text: 'a' }), tap({ text: 'b' }), tap({ text: 'c' })];
  const mk = (step, t) => fix({ step, op: 'insert_tap', selector: { text: t } });
  const { offer } = plan(steps, [mk(1, 'X'), mk(3, 'Y')]);
  assert.deepEqual(apply(steps, offer).map((s) => s.selector.text), ['a', 'X', 'b', 'c', 'Y']);
});

test('a situational answer is acknowledged and changes nothing', () => {
  const sit = { step: 0, kind: 'situational', op: 'none', was: '"x"', now: 'tap "Veg Extravaganza"' };
  const p = plan(zomatoTail(), [sit]);
  assert.equal(p.offer.length, 0);
  assert.equal(p.situational, true);
  assert.match(describe(p), /only for this run, so I haven't changed the flow/);
  // Stopping is not a correction at all and says nothing.
  assert.equal(describe(plan(zomatoTail(), [])), '');
});

test('a mix names both: the offer, and that the other answer was only for this run', () => {
  const p = plan(zomatoTail(), [
    fix({ step: 3, op: 'mark_optional' }),
    { step: 0, kind: 'situational', op: 'none', was: '"x"', now: 'tap "Veg"' },
  ]);
  assert.match(describe(p), /1 \w+|Part of this flow looks out of date/);
  assert.match(describe(p), /Your other answer was only for this run/);
});

test('only a clear yes saves; an ambiguous or mixed reply saves nothing', () => {
  for (const t of ['yes', 'Yes please', 'yeah', 'sure, go ahead', 'okay', 'save it', 'update it', 'do it']) assert.equal(isYes(t), true, t);
  for (const t of ['no', 'nope', 'leave it', 'no thanks', 'never mind', 'cancel', 'not now']) { assert.equal(isYes(t), false, t); assert.equal(isNo(t), true, t); }
  assert.equal(isYes("yes... actually don't"), false);
  assert.equal(isYes('order me a pizza'), false);
  assert.equal(isYes(''), false);
});

test('yes/no must be the WHOLE reply: a new command that starts with "okay" or "sure" is never consent', () => {
  // Before: "Okay, order a margherita" applied the flow edit and the order was dropped.
  for (const t of ['Okay, order a margherita', 'sure, get me a farmhouse', 'yes order two pizzas', 'no order pizza instead']) {
    assert.equal(isYes(t), false, t);
    assert.equal(isNo(t), false, t);
  }
  assert.equal(isNo('thanks'), false);
  assert.equal(isYes('Yes please.'), true);
});

test('a tap the user named that names THIS request\'s dish is refused, so the flow never learns the value as a literal', () => {
  const steps = [{ type: 'launch', value: 'z' }, { type: 'tap', selector: { text: 'Ordered by 90+ customers' } }];
  const c = [{ step: 1, kind: 'flow_defect', op: 'replace_selector', was: '"Ordered by 90+ customers"', now: 'tap "Margherita Pizza"',
    selector: { text: 'Margherita Pizza', contentDesc: '', resourceId: '', className: '' } }];
  const p = plan(steps, c, { item: 'Margherita pizza', restaurant: "Domino's" });
  assert.equal(p.offer.length, 0);
  assert.equal(p.refused[0].why, 'names the requested value');
  // The same caption with no slot context is still learnable (nothing says it is a value).
  assert.equal(plan(steps, c).offer.length, 1);
});

test('prices are stripped but digits that belong to a name are not', () => {
  assert.equal(stripPrice('Add item ₹1,149.50'), 'Add item');
  assert.equal(stripPrice('Pay Rs. 249 now'), 'Pay now');
  assert.equal(stripPrice('Big Big 6in1 Pizza'), 'Big Big 6in1 Pizza');
  assert.equal(stripPrice('Ordered by 90+ customers'), 'Ordered by 90+ customers');
});

test('the stored model accepts the optional flag and defaults it off', async () => {
  const doc = new Flow({
    intentKey: 'k', description: 'd', appPackage: 'p',
    steps: [{ type: 'tap', selector: blank, optional: true }, { type: 'tap', selector: blank }],
  });
  await doc.validate();
  assert.equal(doc.steps[0].optional, true);
  assert.equal(doc.steps[1].optional, false);
});
