import { z } from 'zod';

/**
 * Slot map as returned by the model. Values are coerced to strings because a JSON-mode model happily answers
 * {"num1": 5}; rejecting that would fail the whole extraction over a type the slot matcher does not care about.
 */
export const slotMap = z.record(z.coerce.string().max(200)).default({});

// What a model writes for "the command did not say" once coerced to a string.
const UNSET = /^\s*(null|none|undefined|n\/a|unknown|unspecified|not specified)?\s*$/i;

/** Shared wording so intent extraction and flow matching name slots the same way. */
export const SLOT_RULES =
  'Each slot holds ONE value the user could swap out. A value MAY be several words when they name one thing: an item, ' +
  'dish or product is its FULL name exactly as said — "corn and cheese pizza", "garlic bread", "double cheese margherita" ' +
  'are each ONE item; never split a single product/dish/restaurant name on "and" or on spaces. Use several slots ONLY when ' +
  'the command truly lists separate values of the same kind, e.g. two numbers to add -> {"num1":"5","num2":"3"}. ' +
  'Reserved names: "quantity" for how many (as digits, only if said), "address" for a delivery place (the saved label, ' +
  'e.g. "work", only if said), "item" for the product or dish (its full name), "restaurant" or "store" for where it comes from. ' +
  'The APP the task runs in is NEVER a slot value: "on Zomato", "on Amazon", "in the clock app", "on the calculator" ' +
  'name the app, not the restaurant, store, item or feature. If the command names only the app and not the place the ' +
  'order comes from, leave restaurant/store UNSET rather than filling it with the app name — "buy a margherita pizza ' +
  'on Zomato" has item="margherita pizza" and NO restaurant. ' +
  'A slot value NEVER carries politeness or filler words around it ("please", "kindly", "na", "yaar", "bro", "for me", ' +
  '"just", "now"): "do order a margherita from dominos na" has restaurant="dominos", not "dominos na".';

/**
 * Slots the command did not mention fall back to the value the flow was taught with ("order margherita on zomato"
 * -> the taught restaurant), except the optional ones in [optional], where "not said" means "leave the app's own".
 */
export function withDefaults(slots, flowSlots = [], optional = []) {
  const out = { ...slots };
  for (const { name, example } of flowSlots) if (!(name in out) && example && !optional.includes(name)) out[name] = example;
  return out;
}

/**
 * Politeness and filler words that ride along at the edge of a spoken slot value ("from dominos na", "margherita
 * pizza please"). The model is told to leave them out, but it is not reliable about it and a value like "dominos na"
 * is then typed into a search box, so they are stripped here as well — deterministically, where it can be tested.
 */
const FILLER = /^(?:please|pls|plz|kindly|just|now|only|na|naa|no|yaar|yar|ya|re|da|bro|bhai|sir|madam|ok|okay|thanks|thank you|for me|to me|as well|also)$/i;

/** Trims filler words from both ends of a slot value, keeping the value itself intact. */
export function trimFiller(value) {
  let words = String(value).trim().split(/\s+/).filter(Boolean);
  const strip = () => {
    let changed = true;
    while (changed && words.length > 1) {
      changed = false;
      if (FILLER.test(words[words.length - 1])) { words = words.slice(0, -1); changed = true; }
      if (words.length > 1 && FILLER.test(words[0])) { words = words.slice(1); changed = true; }
    }
  };
  strip();
  return words.join(' ');
}

const SMALL = {
  zero: 0, one: 1, a: 1, an: 1, single: 1, two: 2, couple: 2, pair: 2, three: 3, four: 4, five: 5, six: 6, seven: 7,
  eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, dozen: 12, thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16,
  seventeen: 17, eighteen: 18, nineteen: 19,
};
const TENS = { twenty: 20, thirty: 30, forty: 40, fourty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90 };
const SCALE = { thousand: 1000, lakh: 100000, lakhs: 100000, million: 1000000 };
const NUMERIC = /^\d+(\.\d+)?$/;

/**
 * A spoken number as a number: "seven" -> 7, "one hundred and one" -> 101, "twenty-five" -> 25, "5 hundred" -> 500,
 * "12" -> 12. Null unless the WHOLE value is a number, so "Seven Cheese Pizza" is never turned into 7.
 */
export function wordsToNumber(raw) {
  const t = String(raw ?? '').toLowerCase().replace(/[-,]/g, ' ').trim();
  if (NUMERIC.test(t)) return Number(t);
  const words = t.split(/\s+/).filter((w) => w && w !== 'and');
  if (!words.length) return null;
  let total = 0;
  let current = 0;
  for (const w of words) {
    if (w in SMALL) current += SMALL[w];
    else if (w in TENS) current += TENS[w];
    else if (/^\d+$/.test(w)) current += Number(w);
    else if (w === 'hundred') current = (current || 1) * 100;
    else if (w in SCALE) { total += (current || 1) * SCALE[w]; current = 0; }
    else return null;
  }
  return total + current;
}

/**
 * Puts each value into the SHAPE the flow was taught with. A slot taught as a number ("5") must be replayed as digits,
 * because the screen has a key or field for "7", not for "seven" — and whether the model writes "7" or "seven" depends on
 * which model in the ladder happened to answer (observed: gpt-oss-120b returned "seven", qwen "7", for the same words).
 * Values for slots taught with words are left exactly as said.
 */
export function fitToTaught(slots = {}, flowSlots = []) {
  const out = { ...slots };
  for (const { name, example } of flowSlots) {
    if (out[name] == null || !NUMERIC.test(String(example ?? '').trim()) || NUMERIC.test(String(out[name]).trim())) continue;
    const n = wordsToNumber(out[name]);
    if (n != null) out[name] = String(n);
  }
  return out;
}

// Where an order comes from: the taught one is a sensible default ("get me a margherita" -> the taught Domino's, T3).
const DEFAULTABLE = new Set(['restaurant', 'store']);

/**
 * The first slot the request depends on that the utterance did not give. Its taught value is NOT reused silently:
 * "order from Domino's" with no dish must not quietly order last time's Farmhouse again, it must ask. Only the place an
 * order comes from defaults to the taught one, and [optional] slots (quantity, address) keep the app's own value.
 */
export function missingRequired(slots = {}, flowSlots = [], optional = []) {
  return flowSlots.find(({ name }) => !(name in slots) && !optional.includes(name) && !DEFAULTABLE.has(name)) ?? null;
}

/**
 * The values a replay runs with: THIS utterance's values in the taught shape, then the taught example only for a slot
 * that may default (the place an order comes from). Returns { ask } instead when the request's subject is missing.
 * The one function between "what the user said" and "what the phone types and taps" — kept pure so it is testable.
 */
export function replaySlots(given = {}, flowSlots = [], optional = []) {
  const fitted = fitToTaught(given, flowSlots);
  const missing = missingRequired(fitted, flowSlots, optional);
  if (missing) return { ask: missing, slots: fitted };
  return { slots: withDefaults(fitted, flowSlots, optional) };
}

const LABEL = { item: 'item', num1: 'first number', num2: 'second number', query: 'search', setting: 'setting' };

/** The question for a missing slot, naming what was used last time so the user can simply say it again. */
export function askFor({ name, example }) {
  const what = LABEL[name] ?? name.replace(/[_-]+/g, ' ').replace(/(\d+)$/, ' $1').trim();
  return example ? `Which ${what} should I use? Last time it was ${example}.` : `Which ${what} should I use?`;
}

/** Model output -> replayable values: "two" -> "2" for quantity, "my work" / "the office" stay as spoken labels. */
export function normaliseSlots(slots = {}) {
  const out = Object.fromEntries(
    Object.entries(slots)
      .filter(([, v]) => v != null && !UNSET.test(String(v)))
      .map(([k, v]) => [k, trimFiller(v)])
      .filter(([, v]) => v !== ''),
  );
  if (out.quantity != null) {
    const q = String(out.quantity).trim().toLowerCase();
    const n = wordsToNumber(q);
    out.quantity = n != null && Number.isInteger(n) ? String(n) : (q.match(/\d+/)?.[0] ?? '');
  }
  if (out.address != null) out.address = String(out.address).trim().replace(/^(my|the)\s+/i, '');
  for (const k of Object.keys(out)) if (UNSET.test(String(out[k]))) delete out[k];
  return out;
}
