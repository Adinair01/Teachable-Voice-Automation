import { isSensitiveStep, looksLikeCredentialField } from './sensitive.js';

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Case- and punctuation-insensitive pattern for a spoken slot value: "Domino's" matches a typed "dominos" and
 * "Margherita pizza" matches the caption "Margherita Pizza". Falls back to the value's longest word
 * ("Margherita pizza" spoken, only "margherita" typed into the search box).
 */
function slotPatterns(example) {
  const loose = (s) => s.replace(/[^a-z0-9]/gi, '').split('').map(esc).join('[^a-z0-9]?');
  const words = example.split(/\s+/).filter((w) => w.replace(/[^a-z0-9]/gi, '').length >= 4).sort((a, b) => b.length - a.length);
  // Only the single longest word: the distinctive part ("Margherita"), never a generic one ("pizza" is in "Domino's Pizza").
  return [example, ...(words.length > 1 ? words.slice(0, 1) : [])].filter((s) => loose(s)).map((s) => new RegExp(loose(s), 'i'));
}

/** Replace the first occurrence of the slot value in `text` with {{slot}}. Returns null if absent. */
function tokenise(text, example, slot) {
  if (!text || !example) return null;
  const re = slotPatterns(example).find((r) => r.test(text));
  return re ? text.replace(re, `{{${slot}}}`) : null;
}

// Ordering/buying tasks get optional quantity + delivery-address slots even when the demo never touched them, so
// "two pizzas" or "deliver to work" replays the same learned flow. Filled by the matcher, skipped on replay when empty.
const ORDERING = /\b(order|buy|purchase|deliver|get me)\b/i;
export const IMPLIED_SLOTS = [{ name: 'quantity', example: '1' }, { name: 'address', example: 'home' }];

/**
 * Turn a raw recorded demonstration into a reusable parameterised flow (deterministic slot detection):
 * if a typed value or tapped element text contains the spoken slot value (case-insensitive) it becomes {{slot}}.
 * Steps on credential-looking fields are dropped (never stored). Sensitive taps are flagged.
 */
export function generalise({ command, intentKey, slots, steps }) {
  const slotEntries = Object.entries(slots ?? {}).filter(([, v]) => v);
  const out = [];
  for (const raw of steps) {
    const sel = { resourceId: '', text: '', contentDesc: '', className: '', ...(raw.selector ?? {}) };
    if (looksLikeCredentialField(`${sel.resourceId} ${sel.contentDesc} ${sel.text}`)) continue;
    const { context = [], ...rest } = raw;
    const step = { ...rest, selector: sel, slotRef: null, value: raw.value ?? '', sensitive: false, expect: raw.expect ?? {} };
    for (const [slot, example] of slotEntries) {
      if (step.type === 'type') {
        const v = tokenise(step.value, example, slot);
        if (v) { step.value = v; step.slotRef = slot; }
      } else if (step.type === 'tap') {
        // Tokenise BOTH fields: a leftover literal in the other one would win on replay if the first match misses.
        const text = tokenise(sel.text, example, slot);
        const desc = tokenise(sel.contentDesc, example, slot);
        if (text || desc) {
          if (text) sel.text = text;
          if (desc) sel.contentDesc = desc;
          step.slotRef = slot;
        }
      }
    }
    // A control repeated once per row (every dish's ADD) is anchored to the row caption that names the slot value.
    // Only when the tap's OWN text/desc did not already carry the slot: a self-labelled tap (a search result whose
    // own caption IS the varying value) must fall through to the substring-contains slot match instead — templating
    // `near` from the same text here would require an exact match on a caption that intentionally changes per row.
    if (step.type === 'tap' && context.length && !step.slotRef) {
      for (const [slot, example] of slotEntries) {
        const near = context.map((c) => tokenise(c, example, slot)).filter(Boolean).sort((a, b) => a.length - b.length)[0];
        if (near) { sel.near = near; step.slotRef ??= slot; break; }
      }
    }
    if (step.type === 'tap' && step.slotRef) {
      // The screen a slot-bound tap leads to depends on the slot value, so a recorded expectation would be wrong on replay.
      step.expect = {};
      // A resource-id names the demonstrated element (digit_5), not the parameterised one, and outranks text on replay.
      sel.resourceId = '';
    }
    step.sensitive = isSensitiveStep(step);
    out.push(step);
  }
  const appPackage = out.find((s) => s.type === 'launch')?.value ?? 'unknown';
  const flowSlots = slotEntries.map(([name, example]) => ({ name, example }));
  const slotsFound = out.some((s) => s.slotRef); // judged on the demo alone, before the implied steps are added
  if (ORDERING.test(command)) addOrderingSteps(out, flowSlots);
  return { intentKey, description: command, appPackage, slots: flowSlots, steps: out, slotsFound };
}

/** Quantity + address adjustments go just before the first sensitive step (checkout), else at the end (the cart). */
function addOrderingSteps(steps, slots) {
  for (const s of IMPLIED_SLOTS) if (!slots.some((x) => x.name === s.name)) slots.push({ ...s });
  // The quantity stepper sits in the dish's row, so prefer the anchor naming the item over e.g. the restaurant card.
  const anchored = [...steps].reverse().filter((s) => s.type === 'tap' && s.selector.near);
  const itemNear = (anchored.find((s) => s.selector.near.includes('{{item}}')) ?? anchored[0])?.selector.near ?? '';
  const blank = { resourceId: '', text: '', contentDesc: '', className: '' };
  const extra = [
    { type: 'set_quantity', selector: { ...blank, near: itemNear }, slotRef: 'quantity', value: '', sensitive: false, expect: {} },
    { type: 'choose_address', selector: { ...blank, near: '' }, slotRef: 'address', value: '', sensitive: false, expect: {} },
  ];
  const at = steps.findIndex((s) => s.sensitive);
  steps.splice(at === -1 ? steps.length : at, 0, ...extra);
}
