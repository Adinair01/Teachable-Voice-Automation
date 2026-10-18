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

const NUMBER_WORDS = { one: 1, a: 1, an: 1, single: 1, two: 2, couple: 2, pair: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10 };

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
    out.quantity = NUMBER_WORDS[q] ? String(NUMBER_WORDS[q]) : (q.match(/\d+/)?.[0] ?? '');
  }
  if (out.address != null) out.address = String(out.address).trim().replace(/^(my|the)\s+/i, '');
  for (const k of Object.keys(out)) if (UNSET.test(String(out[k]))) delete out[k];
  return out;
}
