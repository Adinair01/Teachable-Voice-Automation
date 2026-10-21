import { isSensitiveStep, looksLikeCredentialField } from './sensitive.js';
import { wordsToNumber } from '../prompts/slots.js';

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** "Corn & Cheese" and "corn and cheese" must compare equal; mirrors RowContext.norm on the Android side. */
const amp = (s) => s.replace(/&/g, ' and ');
const norm = (s) => amp(String(s).toLowerCase()).replace(/[^a-z0-9]/g, '');
// Only whole words of the text may be taken for a slot's longest word: "house" inside "farmhouse" is not the slot.
const EDGE_L = '(?<![a-z0-9])';
const EDGE_R = '(?![a-z0-9])';

/**
 * Case- and punctuation-insensitive pattern for a spoken slot value: "Domino's" matches a typed "dominos" and
 * "Margherita pizza" matches the caption "Margherita Pizza". Falls back to the value's longest word
 * ("Margherita pizza" spoken, only "margherita" typed into the search box).
 */
function slotPatterns(example) {
  // "&" spells out, so a spoken "corn and cheese pizza" still matches the menu's "Corn & Cheese Pizza".
  const loose = (s) => amp(s).replace(/[^a-z0-9]/gi, '').split('').map(esc).join('[^a-z0-9]?');
  const words = example.split(/\s+/).filter((w) => w.replace(/[^a-z0-9]/gi, '').length >= 4).sort((a, b) => b.length - a.length);
  // Only the single longest word: the distinctive part ("Margherita"), never a generic one ("pizza" is in "Domino's Pizza").
  // An exact occurrence must sit on word boundaries: "Margherita pizza" inside "Margherita pizzas" is a near miss
  // ('partial'), and templating it in place would leave the demo's "s" behind ("{{item}}s").
  const out = loose(example) ? [
    { re: new RegExp(EDGE_L + loose(example) + EDGE_R, 'i'), how: 'full' },
    { re: new RegExp(loose(example), 'i'), how: 'partial' },
  ] : [];
  if (words.length > 1 && loose(words[0])) out.push({ re: new RegExp(EDGE_L + loose(words[0]) + EDGE_R, 'i'), how: 'word' });
  return out;
}

/** Edit distance, for a recogniser's spelling of a dish ("Margarita") against the menu's ("Margherita"). */
function distance(a, b) {
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)]);
  for (let j = 1; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  }
  return d[a.length][b.length];
}

/** Two long words that differ by a letter or two are the same name spelled two ways. Short words never are. */
const sameName = (a, b) => a.length >= 6 && b.length >= 6 && distance(a, b) <= 2;

/**
 * Where the slot value shows up in `text`: { text, how } with the occurrence replaced by {{slot}}, or null.
 * how = full (the whole value, on word boundaries), partial (the value inside a longer word), word (its longest word),
 * prefix (typed the first letters), fuzzy (spelled differently).
 * Only 'full' leaves the surrounding words in place; the caller decides what the weaker kinds mean for its step.
 */
function locate(text, example, slot) {
  if (!text || !example) return null;
  for (const { re, how } of slotPatterns(example)) if (re.test(text)) return { text: text.replace(re, `{{${slot}}}`), how };
  // Partial typing: the user typed the first few letters of the value into a search box, then tapped a suggestion
  // ("mar" -> Margherita, "dom" -> Domino's). If the whole typed text is a >=3-char prefix of the value (or one of its
  // words), it IS that slot — swap the entire value for {{slot}} so replay types the FULL value the command asked for.
  const t = norm(text);
  if (t.length >= 3) {
    const candidates = [example, ...example.split(/\s+/)].map(norm).filter(Boolean);
    if (candidates.some((c) => c.length > t.length && c.startsWith(t))) return { text: `{{${slot}}}`, how: 'prefix' };
  }
  const said = example.split(/\s+/).map(norm).filter(Boolean);
  const shown = String(text).split(/\s+/).map(norm).filter(Boolean);
  if (sameName(t, norm(example)) || shown.some((w) => said.some((x) => sameName(w, x)))) return { text: `{{${slot}}}`, how: 'fuzzy' };
  return null;
}

/**
 * Replace the slot value in `text` with {{slot}}. Returns null if absent. A text that only PARTLY matched the value
 * becomes {{slot}} as a whole, so no fragment of the demonstrated value can survive into a replay: "farmhouse" typed
 * for a spoken "farm house pizza" used to be stored as "farm{{item}}", which types "farmMargherita" for a Margherita.
 * [whole]: a typed value is one search query, so anything but an exact occurrence of the value is the value itself.
 */
function tokenise(text, example, slot, { whole = false } = {}) {
  const hit = locate(text, example, slot);
  if (!hit) return null;
  return hit.how === 'full' || (hit.how === 'word' && !whole) ? hit.text : `{{${slot}}}`; // partial/prefix/fuzzy: the whole text
}

/** Does `text` name the value (in any of its spoken forms)? For callers that must not learn a value as a literal. */
export const mentions = (text, value) => !!value && forms(String(value)).some((f) => locate(String(text ?? ''), f, 'x'));

/**
 * A tapped caption with the slot in it, for replay to find by "contains, fewest other words". Words BEFORE the value
 * are the control's own wording and stay ("Search in {{restaurant}}"); words AFTER it are the rest of that one
 * thing's name and go ("Domino's Pizza" -> "{{restaurant}}", which otherwise resolved to "Pizza Hut Pizza"). A
 * weaker match (inside a word, a prefix, a respelling) cannot be cut cleanly, so the caption becomes the slot.
 */
function captionTemplate(text, example, slot) {
  const hit = locate(text, example, slot);
  if (!hit) return null;
  const token = `{{${slot}}}`;
  if (hit.how !== 'full' && hit.how !== 'word') return token;
  return hit.text.slice(0, hit.text.indexOf(token) + token.length).trim();
}

/**
 * How many of the value's words a caption has. A product is rarely said the way a listing writes it: "S25 ultra
 * 12GB256GB phone" was said for "Samsung Galaxy S25 Ultra 5G AI Smartphone (…, 12GB RAM, 256GB Storage)" (Amazon, live),
 * which shares "S25" and "Ultra" — enough to know which row the demo's "Add to cart" belonged to.
 */
const MIN_SHARED_WORDS = 2;
const wordsOf = (s) => amp(String(s).toLowerCase()).split(/[^a-z0-9]+/).filter((w) => w.length >= 2);
function sharedWords(caption, value) {
  const have = new Set(wordsOf(caption));
  return [...new Set(wordsOf(value))].filter((w) => have.has(w)).length;
}

/** More words than this around the value, and a row caption describes one product, not the kind of row. */
const NEAR_EXTRA_WORDS = 2;

/**
 * The row anchor kept for a tap. A short caption keeps its wording ("{{item}} Pizza"); a long one is a product title
 * — "Sponsored Ad - Samsung Galaxy {{item}} 5G AI Smartphone (Titanium Silverblue, 12GB…" (Amazon, seen live) — whose
 * other words belong to that one listing, so the anchor is the value alone and replay finds the row naming it.
 */
function anchorOf(near, slot) {
  const extra = near.replace(`{{${slot}}}`, ' ').split(/\s+/).filter((w) => /[a-z0-9]/i.test(w)).length;
  return extra > NEAR_EXTRA_WORDS ? `{{${slot}}}` : near;
}

/**
 * The forms a spoken value can take in a demonstration: as said, and as digits when it is a number ("five" was spoken,
 * the key pressed was "5"). Which form a model writes depends on which model answered, so both are looked for.
 */
function forms(example) {
  const n = /^\d+$/.test(String(example).trim()) ? null : wordsToNumber(example);
  return n != null && Number.isInteger(n) ? [example, String(n)] : [example];
}

/**
 * A caption that names an ACTION rather than a thing ("ADD", "+") is repeated once per row, so it identifies no
 * single control and the tap has to be aimed by its row instead. Mirrors SelectorMatcher's rule on the Android side.
 */
const ACTION_CAPTION = /^(add(\s+item)?|add to (cart|bag|basket)|\+|buy( now)?|order( now)?|select|choose|get|go)$/i;

/** Does the control's own caption say WHICH one it is? If not, replay needs selector.near to aim the tap. */
function identifies(sel) {
  const caption = (sel.text || sel.contentDesc || '').trim();
  return caption !== '' && !ACTION_CAPTION.test(caption);
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
const KEY = /^\d$/;
const keyOf = (raw) => raw.type === 'tap' && [raw.selector?.text, raw.selector?.contentDesc].map((c) => String(c ?? '').trim()).find((c) => KEY.test(c));

/**
 * A number taught on a keypad is several key taps: "30" is "3" then "0". Matched one tap at a time it could never be
 * found, so a calculator demo of "30 divided by 5" was refused (seen live). A run of consecutive single-digit key taps
 * that spells a slot's value (in digits) becomes ONE tap on that value — which the step loop then templates as the
 * slot, and which replay enters key by key. Each slot is matched once, in order, so "12 plus 12" gives num1 and num2.
 */
function joinKeyRuns(steps, slotEntries) {
  const wanted = slotEntries.map(([slot, example]) => [slot, forms(example).find((f) => /^\d{2,}$/.test(String(f).trim()))])
    .filter(([, digits]) => digits);
  if (!wanted.length) return steps;
  const used = new Set();
  const out = [];
  for (let i = 0; i < steps.length;) {
    if (!keyOf(steps[i])) { out.push(steps[i++]); continue; }
    let end = i;
    while (end < steps.length && keyOf(steps[end])) end++;
    const keys = steps.slice(i, end).map(keyOf).join('');
    // The first not-yet-used slot whose value is spelled somewhere in this run; taps around it stay as they were.
    const hit = wanted.find(([slot, digits]) => !used.has(slot) && keys.includes(digits));
    if (!hit) { out.push(...steps.slice(i, end)); i = end; continue; }
    const [slot, digits] = hit;
    used.add(slot);
    const at = i + keys.indexOf(digits);
    out.push(...steps.slice(i, at));
    const first = steps[at];
    out.push({ ...first, selector: { ...first.selector, resourceId: '', text: digits, contentDesc: '' }, context: [], candidates: 1, keyedSlot: slot });
    out.push(...steps.slice(at + digits.length, end));
    i = end;
  }
  return out;
}

export function generalise({ command, intentKey, slots, steps: recorded }) {
  const slotEntries = Object.entries(slots ?? {}).filter(([, v]) => v);
  const steps = joinKeyRuns(recorded, slotEntries);
  // The form each slot was actually demonstrated in ("5" for a spoken "five"): stored as the example, because replay
  // puts new values into the TAUGHT shape (fitToTaught) and a word-shaped example would leave "seven" unconverted.
  const shown = {};
  const find = (slot, example, fn) => {
    for (const f of forms(example)) { const v = fn(f); if (v) { shown[slot] ??= f; return v; } }
    return null;
  };
  const out = [];
  for (const raw of steps) {
    const sel = { resourceId: '', text: '', contentDesc: '', className: '', ...(raw.selector ?? {}) };
    if (looksLikeCredentialField(`${sel.resourceId} ${sel.contentDesc} ${sel.text}`)) continue;
    const { context = [], candidates = 0, screen: landedOn = [], keyedSlot, ...rest } = raw;
    const step = { ...rest, selector: sel, slotRef: null, value: raw.value ?? '', sensitive: false, expect: raw.expect ?? {} };
    // A number joined from its key taps already knows which slot it is (see joinKeyRuns).
    if (keyedSlot) { shown[keyedSlot] ??= sel.text; sel.text = `{{${keyedSlot}}}`; step.slotRef = keyedSlot; step.ownCaption = true; }
    for (const [slot, example] of keyedSlot ? [] : slotEntries) {
      if (step.type === 'type') {
        const v = find(slot, example, (f) => tokenise(step.value, f, slot, { whole: true }));
        if (v) { step.value = v; step.slotRef = slot; }
      } else if (step.type === 'tap') {
        // Tokenise BOTH fields: a leftover literal in the other one would win on replay if the first match misses.
        // See captionTemplate: "{{restaurant}} Pizza" resolved to "Pizza Hut Pizza", which exists nowhere. Replay picks
        // the control whose caption contains the resolved text with the fewest other words ("Pizza Hut" over
        // "Pizza Hut Express"; "Search in Domino's Pizza" for "Search in {{restaurant}}").
        const text = find(slot, example, (f) => captionTemplate(sel.text, f, slot));
        const desc = find(slot, example, (f) => captionTemplate(sel.contentDesc, f, slot));
        if (text || desc) {
          if (text) sel.text = text;
          if (desc) sel.contentDesc = desc;
          step.slotRef = slot;
          step.ownCaption = true;
        }
      }
    }
    // A control repeated once per row (every dish's ADD) is anchored to the row caption that names the slot value.
    // Only when the tap's OWN text/desc did not already carry the slot: a self-labelled tap (a search result whose
    // own caption IS the varying value) must fall through to the substring-contains slot match instead — templating
    // `near` from the same text here would require an exact match on a caption that intentionally changes per row.
    //
    // Anchored when the control repeated on the demo screen (as before) OR when its own caption names an action
    // rather than a thing. The second case is the demo done after a search: one result, one ADD, nothing to tell
    // rows apart later. A uniquely and distinctively captioned control ("View Cart") is left unanchored, so a row
    // that merely happens to mention the item cannot start failing its step.
    if (step.type === 'tap' && context.length && !step.slotRef && (candidates > 1 || !identifies(sel))) {
      for (const [slot, example] of slotEntries) {
        const near = find(slot, example, (f) => context.map((c) => tokenise(c, f, slot)).filter(Boolean).sort((a, b) => a.length - b.length)[0])
          ?? (context.some((c) => sharedWords(c, example) >= MIN_SHARED_WORDS) ? `{{${slot}}}` : null);
        if (near) { sel.near = anchorOf(near, slot); step.slotRef ??= slot; break; }
      }
    }
    if (step.type === 'tap' && step.slotRef) {
      // The screen a slot-bound tap leads to depends on the slot value, so a recorded expectation would be wrong on replay.
      step.expect = {};
      // A resource-id names the demonstrated element (digit_5), not the parameterised one, and outranks text on replay.
      sel.resourceId = '';
      // ...but if that screen NAMED the slot value in the demo, replay can check it named the new one too: proof the
      // tap opened the restaurant or dish actually asked for. Stored as the bare token, never with the demo's own
      // wording around it, so it resolves cleanly for any value.
      // A tap whose own caption was the value is checked even when no following screen was recorded (Compose pages
      // raise no window change): the page it opens names what was tapped, so "did we land on Pizza Hut?" is answerable.
      // A recorded screen that did NOT name the value means this tap's page doesn't, so nothing is checked then.
      const example = shown[step.slotRef] ?? slots?.[step.slotRef];
      const named = example && landedOn.some((c) => tokenise(c, example, step.slotRef));
      if (named || (step.ownCaption && !landedOn.length)) step.expect = { textContains: `{{${step.slotRef}}}` };
    }
    delete step.ownCaption;
    step.sensitive = isSensitiveStep(step);
    out.push(step);
  }
  const appPackage = out.find((s) => s.type === 'launch')?.value ?? 'unknown';
  const flowSlots = slotEntries.map(([name, example]) => ({ name, example: shown[name] ?? example }));
  const slotsFound = out.some((s) => s.slotRef); // judged on the demo alone, before the implied steps are added
  // A value the command named but the demonstration never used cannot vary on replay: its step keeps the demo's
  // literal, and every later run would quietly do the TAUGHT one again ("order a Farmhouse" -> Margherita). Quantity and
  // address are exempt: they are applied by steps added below, not by anything the user did.
  const used = new Set(out.flatMap((s) => [s.slotRef, ...[s.value, s.selector?.text, s.selector?.contentDesc, s.selector?.near]
    .flatMap((v) => [...String(v ?? '').matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]))]));
  const missing = flowSlots.filter((s) => !used.has(s.name) && !IMPLIED_SLOTS.some((x) => x.name === s.name));
  if (ORDERING.test(command)) addOrderingSteps(out, flowSlots);
  return { intentKey, description: command, appPackage, slots: flowSlots, steps: out, slotsFound, missing };
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
