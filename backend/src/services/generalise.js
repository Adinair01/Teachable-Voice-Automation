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
        const t = tokenise(sel.text, example, slot) ?? tokenise(sel.contentDesc, example, slot);
        if (t) {
          if (tokenise(sel.text, example, slot)) sel.text = t; else sel.contentDesc = t;
          step.slotRef = slot;
        }
      }
    }
    // The screen a slot-bound tap leads to depends on the slot value, so a recorded expectation would be wrong on replay.
    if (step.type === 'tap' && step.slotRef) step.expect = {};
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
