import { z } from 'zod';

/**
 * Slot map as returned by the model. Values are coerced to strings because a JSON-mode model happily answers
 * {"num1": 5}; rejecting that would fail the whole extraction over a type the slot matcher does not care about.
 */
export const slotMap = z.record(z.coerce.string().max(200)).default({});

/** Shared wording so intent extraction and flow matching name slots the same way. */
export const SLOT_RULES =
  'Each slot value is ONE value the user could swap out (a number, a name, a word) — never a whole phrase; ' +
  'if the command carries several values, use several slots (e.g. {"num1":"5","num2":"3"}).';
