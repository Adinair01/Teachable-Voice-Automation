import { z } from 'zod';
import { isSensitiveStep, looksLikeCredentialField } from './sensitive.js';
import { mentions } from './generalise.js';

/**
 * What the user's answers to "what should I do?" imply for a stored flow.
 *
 * The phone classifies each answer (see CorrectionPolicy.kt) and the backend decides what is safe to offer. NOTHING
 * here writes anything: the offer is spoken first, and a flow is only changed after an explicit yes
 * (see Session.#applyCorrections). That is the contract: a taught flow is never rewritten silently.
 */
const selector = z.object({
  resourceId: z.string().max(200).default(''),
  text: z.string().max(300).default(''),
  contentDesc: z.string().max(300).default(''),
  className: z.string().max(200).default(''),
});

export const correctionInput = z.object({
  step: z.number().int().min(0).max(200),
  kind: z.enum(['flow_defect', 'situational']),
  op: z.enum(['replace_selector', 'mark_optional', 'insert_tap', 'none']),
  was: z.string().max(200).default(''),
  now: z.string().max(200).default(''),
  selector: selector.optional(),
});

// A price belongs to the moment something was recorded, not to the control ("Add item ₹114" is "Add item" tomorrow).
const PRICE = /(?:₹|rs\.?|inr|\$)\s*[\d,]+(?:\.\d+)?/gi;
export const stripPrice = (s = '') => s.replace(PRICE, ' ').replace(/\s+/g, ' ').trim();

const clean = (sel) => ({
  resourceId: '',
  text: stripPrice(sel?.text),
  contentDesc: stripPrice(sel?.contentDesc),
  className: sel?.className ?? '',
  near: '',
});

const canLearnFrom = (sel) => (sel.text || sel.contentDesc) !== '';

/**
 * Splits the phone's corrections into what can be offered and what is refused, judged against the flow as it is now.
 * Re-checked here rather than trusted: the phone is a client, and a stale or malformed correction must not be able to
 * rewrite a flow into something unsafe.
 */
export function plan(steps, corrections, slots = {}) {
  // A caption that names THIS request's dish or restaurant is a value, not a control: learning "tap Margherita Pizza"
  // as a plain step would tap Margherita on every later run, whatever was asked for.
  const values = Object.values(slots).filter((v) => v && String(v).trim().length >= 2);
  const offer = [];
  const refused = [];
  const seen = new Set();
  const defects = corrections.filter((c) => c.kind === 'flow_defect' && c.op !== 'none');
  for (const c of defects) {
    const key = `${c.op}:${c.step}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const at = steps[c.step];

    if (c.op === 'mark_optional') {
      if (!at || at.type !== 'tap' || at.slotRef || at.selector?.near || at.sensitive) { refused.push({ ...c, why: 'not a plain step' }); continue; }
      offer.push(c);
      continue;
    }
    // replace_selector / insert_tap both teach a NEW tap, so the new target must be one that is safe to learn.
    const sel = clean(c.selector);
    const candidate = { type: 'tap', selector: sel };
    if (!canLearnFrom(sel)) { refused.push({ ...c, why: 'nothing to recognise it by' }); continue; }
    if (looksLikeCredentialField(`${sel.text} ${sel.contentDesc}`)) { refused.push({ ...c, why: 'credential field' }); continue; }
    if (isSensitiveStep(candidate)) { refused.push({ ...c, why: 'sensitive' }); continue; }
    if (values.some((v) => mentions(`${sel.text} ${sel.contentDesc}`, v))) { refused.push({ ...c, why: 'names the requested value' }); continue; }
    if (c.op === 'replace_selector') {
      if (!at || at.type !== 'tap' || at.slotRef || at.selector?.near || at.sensitive) { refused.push({ ...c, why: 'not a plain step' }); continue; }
    } else if (c.step > steps.length) { refused.push({ ...c, why: 'out of range' }); continue; }
    offer.push({ ...c, selector: sel });
  }
  const situational = corrections.some((c) => c.kind === 'situational' && c.op === 'none' && c.now);
  return { offer, refused, situational };
}

/** The stored steps with the offered corrections applied. Pure: returns a new array, never mutates. */
export function apply(steps, offer) {
  const out = steps.map((s) => ({ ...s, selector: { ...s.selector }, expect: { ...s.expect } }));
  for (const c of offer.filter((o) => o.op !== 'insert_tap')) {
    if (c.op === 'mark_optional') out[c.step].optional = true;
    if (c.op === 'replace_selector') {
      // The old expectation described the OLD target's screen; the new tap is checked by what it leads to on replay.
      out[c.step] = { ...out[c.step], selector: { ...c.selector }, expect: {}, optional: false };
    }
  }
  // Highest index first, so earlier insertions do not shift the position of later ones.
  for (const c of offer.filter((o) => o.op === 'insert_tap').sort((a, b) => b.step - a.step)) {
    out.splice(c.step, 0, { type: 'tap', selector: { ...c.selector }, slotRef: null, value: '', sensitive: false, expect: {} });
  }
  return out;
}

/** What to say: one message covering everything, ending in a yes/no question. Step numbers are 1-based, as spoken. */
export function describe({ offer, refused, situational }) {
  const parts = [];
  for (const c of offer) {
    if (c.op === 'replace_selector') parts.push(`step ${c.step + 1} tapped ${c.was}, which wasn't there, and you had me ${c.now}`);
    else if (c.op === 'mark_optional') parts.push(`step ${c.step + 1}, ${c.was}, wasn't there and you said to skip it`);
    else parts.push(`the flow never gets to the cart, and you had me ${c.now} to get there`);
  }
  const lines = [];
  if (offer.length) {
    const head = offer.length === 1 ? 'Part of this flow looks out of date:' : `${offer.length} parts of this flow look out of date:`;
    lines.push(`${head} ${parts.join('; ')}. Should I update the flow so it does that from now on? Say yes to save it, or no to leave it exactly as I learned it.`);
  }
  if (refused.length) lines.push(`I won't remember ${refused.map((r) => r.now).join(' or ')}, so that stays as it was.`);
  if (situational) lines.push(offer.length ? "Your other answer was only for this run, so I've left that alone." : "That answer was only for this run, so I haven't changed the flow.");
  return lines.join(' ');
}

// The WHOLE reply must be a yes or a no. Matching a word anywhere made "Okay, order a margherita" a yes to rewriting
// the flow, which saved the edit and dropped the order the user had just asked for.
const tidy = (t = '') => t.toLowerCase().replace(/[^a-z' ]+/g, ' ').replace(/\s+/g, ' ').trim();
const YES_WORD = 'yes|yeah|yep|yup|sure|okay|ok|please do|do it|save it|save|update it|update|go ahead|remember it|remember that|please';
const NO_WORD = "no thanks|no|nope|nah|don't|do not|dont|never mind|nevermind|cancel|leave it|keep it|not now|stop";
const whole = (words) => new RegExp(`^(?:(?:${words})(?: |$))+$`);
const YES = whole(YES_WORD);
const NO = whole(NO_WORD);
export const isNo = (t = '') => NO.test(tidy(t));
/** A yes that also contains a no ("yes... actually don't") is not a yes: nothing is saved on an ambiguous reply. */
export const isYes = (t = '') => tidy(t) !== 'please' && YES.test(tidy(t));
