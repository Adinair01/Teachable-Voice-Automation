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
  'e.g. "work", only if said), "item" for the product or dish (its full name), "restaurant" or "store" for where it comes from.';

/**
 * Slots the command did not mention fall back to the value the flow was taught with ("order margherita on zomato"
 * -> the taught restaurant), except the optional ones in [optional], where "not said" means "leave the app's own".
 */
export function withDefaults(slots, flowSlots = [], optional = []) {
  const out = { ...slots };
  for (const { name, example } of flowSlots) if (!(name in out) && example && !optional.includes(name)) out[name] = example;
  return out;
}

const NUMBER_WORDS = { one: 1, a: 1, an: 1, single: 1, two: 2, couple: 2, pair: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10 };

/** Model output -> replayable values: "two" -> "2" for quantity, "my work" / "the office" stay as spoken labels. */
export function normaliseSlots(slots = {}) {
  const out = Object.fromEntries(Object.entries(slots).filter(([, v]) => v != null && !UNSET.test(String(v))));
  if (out.quantity != null) {
    const q = String(out.quantity).trim().toLowerCase();
    out.quantity = NUMBER_WORDS[q] ? String(NUMBER_WORDS[q]) : (q.match(/\d+/)?.[0] ?? '');
  }
  if (out.address != null) out.address = String(out.address).trim().replace(/^(my|the)\s+/i, '');
  for (const k of Object.keys(out)) if (UNSET.test(String(out[k]))) delete out[k];
  return out;
}
