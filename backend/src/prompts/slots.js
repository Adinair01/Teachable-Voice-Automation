import { z } from 'zod';

/**
 * Slot map as returned by the model. Values are coerced to strings because a JSON-mode model happily answers
 * {"num1": 5}; rejecting that would fail the whole extraction over a type the slot matcher does not care about.
 */
export const slotMap = z.record(z.coerce.string().max(200)).default({});

/** Shared wording so intent extraction and flow matching name slots the same way. */
export const SLOT_RULES =
  'Each slot value is ONE value the user could swap out (a number, a name, a word) — never a whole phrase; ' +
  'if the command carries several values, use several slots (e.g. {"num1":"5","num2":"3"}). ' +
  'Reserved names: "quantity" for how many (as digits, only if said), "address" for a delivery place (the saved label, ' +
  'e.g. "work", only if said), "item" for the product or dish, "restaurant" or "store" for where it comes from.';

const NUMBER_WORDS = { one: 1, a: 1, an: 1, single: 1, two: 2, couple: 2, pair: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10 };

/** Model output -> replayable values: "two" -> "2" for quantity, "my work" / "the office" stay as spoken labels. */
export function normaliseSlots(slots = {}) {
  const out = { ...slots };
  if (out.quantity != null) {
    const q = String(out.quantity).trim().toLowerCase();
    out.quantity = NUMBER_WORDS[q] ? String(NUMBER_WORDS[q]) : (q.match(/\d+/)?.[0] ?? '');
  }
  if (out.address != null) out.address = String(out.address).trim().replace(/^(my|the)\s+/i, '');
  for (const k of Object.keys(out)) if (out[k] === '') delete out[k];
  return out;
}
