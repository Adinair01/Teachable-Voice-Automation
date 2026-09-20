import { isSensitiveStep, looksLikeCredentialField } from './sensitive.js';

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Replace case-insensitive occurrences of `example` in `text` with {{slot}}. Returns null if absent. */
function tokenise(text, example, slot) {
  if (!text || !example) return null;
  const re = new RegExp(esc(example), 'i');
  return re.test(text) ? text.replace(re, `{{${slot}}}`) : null;
}

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
    const step = { ...raw, selector: sel, slotRef: null, value: raw.value ?? '', sensitive: false, expect: raw.expect ?? {} };
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
  return {
    intentKey,
    description: command,
    appPackage,
    slots: slotEntries.map(([name, example]) => ({ name, example })),
    steps: out,
    slotsFound: out.some((s) => s.slotRef),
  };
}
