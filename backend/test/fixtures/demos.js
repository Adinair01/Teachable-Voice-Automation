/**
 * Recorded demonstrations in the exact shape the phone's Recorder sends in teach_done (selector, row context,
 * candidate count), plus what each model in the ladder may extract for a later command. Shared by the backend tests
 * and scripts/part0-fixture.mjs, which hands the generalised flows to the Kotlin replay tests.
 */
const Z = 'com.application.zomato';
const C = 'com.coloros.calculator';

export const calculatorDemo = {
  command: 'Calculate five plus two through calculator',
  intentKey: 'calculate',
  // The word shape one ladder model returns: the case that used to leave the pressed keys as literals.
  slots: { num1: 'five', num2: 'two' },
  steps: [
    { type: 'launch', value: C },
    { type: 'tap', selector: { resourceId: `${C}:id/clr`, contentDesc: 'Clear', className: 'android.widget.Button' }, context: ['Clear'], candidates: 1 },
    { type: 'tap', selector: { resourceId: `${C}:id/digit_5`, text: '5', className: 'android.widget.Button' }, context: ['5'], candidates: 1 },
    { type: 'tap', selector: { resourceId: `${C}:id/op_add`, contentDesc: 'Add', className: 'android.widget.Button' }, context: ['Add'], candidates: 1 },
    { type: 'tap', selector: { resourceId: `${C}:id/digit_2`, text: '2', className: 'android.widget.Button' }, context: ['2'], candidates: 1 },
    { type: 'tap', selector: { resourceId: `${C}:id/eq`, contentDesc: 'Equals', className: 'android.widget.Button' }, context: ['Equals'], candidates: 1 },
  ],
};

/** One Zomato demonstration: search the restaurant, open it, search the dish, ADD in its row, add, go to the cart. */
export function zomatoDemo(dish, typed, price) {
  return {
    command: `Order a ${dish} pizza from Domino's on Zomato`,
    intentKey: 'order_food',
    slots: { item: `${dish} pizza`, restaurant: "Domino's" },
    steps: [
      { type: 'launch', value: Z },
      { type: 'tap', selector: { resourceId: `${Z}:id/search_bar_view_flipper`, text: 'Search', className: 'android.widget.ViewFlipper' }, candidates: 1 },
      { type: 'type', selector: { resourceId: `${Z}:id/edittext`, className: 'android.widget.EditText' }, value: 'dominos' },
      { type: 'tap', selector: { text: "Domino's Pizza", className: 'android.view.View' }, context: ["Domino's Pizza", '4.6', '45-50 mins'], candidates: 3 },
      { type: 'tap', selector: { resourceId: `${Z}:id/menu_search`, contentDesc: "Search in Domino's Pizza", className: 'android.view.ViewGroup' }, candidates: 1 },
      { type: 'type', selector: { resourceId: `${Z}:id/edittext`, className: 'android.widget.EditText' }, value: typed },
      { type: 'tap', selector: { resourceId: `${Z}:id/text_view_title`, contentDesc: 'ADD', className: 'android.view.View' },
        context: [`${dish} Pizza`, `₹${price}`, 'ADD'], candidates: 6 },
      { type: 'tap', selector: { resourceId: `${Z}:id/button`, text: 'Add item', className: 'android.widget.Button' }, candidates: 1 },
      { type: 'tap', selector: { contentDesc: 'Continue', className: 'android.view.View' }, candidates: 1 },
    ],
  };
}

export const margheritaDemo = zomatoDemo('Margherita', 'margherita', 114);
export const farmhouseDemo = zomatoDemo('Farmhouse', 'farmhouse', 279);

/** Later commands and what a model may extract for them (word shapes included, as observed across the ladder). */
export const calculatorReplays = [
  { said: 'calculate 7 + 9', model: { num1: '7', num2: '9' }, keys: ['7', '9'] },
  { said: 'what is seven plus nine', model: { num1: 'seven', num2: 'nine' }, keys: ['7', '9'] },
  { said: 'calculate 100 + 1', model: { num1: 'one hundred', num2: 'one' }, keys: ['100', '1'] },
  { said: 'calculate 5 + 2', model: { num1: '5', num2: '2' }, keys: ['5', '2'] },
];
export const toFarmhouse = { said: "order a Farmhouse pizza from Domino's on Zomato", model: { item: 'Farmhouse pizza', restaurant: "Domino's" } };
export const toMargherita = { said: "order a Margherita pizza from Domino's on Zomato", model: { item: 'Margherita pizza', restaurant: "Domino's" } };
