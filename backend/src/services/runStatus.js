/** T14: answer "did the last run succeed?" from the latest replay RunLog — where it stopped, in words. */
// Each alternative is one way people ask after the last thing the assistant did. Missing one is not harmless: an
// unrecognised "did it work?" goes to the flow matcher, matches nothing, and opens TEACH MODE.
const STATUS_QUERY = new RegExp([
  String.raw`\b(did|was|has|have)\b.*\b(last|previous|latest|recent)\b.*\b(run|order|replay|one|attempt|task|time)\b`,
  String.raw`\b(last|previous|latest)\s+(run|order|replay|attempt|task)\b.*\b(succe\w*|work\w*|fail\w*|go|went|done|through|placed)\b`,
  String.raw`\bhow did (it|that|this|the last \w+) go\b`,
  String.raw`^\s*(so\s+)?(did|has|was)\s+(it|that|this)\s+(work|worked|go through|succeed|succeeded|finish|finished|done|complete|completed|go ok|go okay|go well|fail|failed)\b`,
  String.raw`\bwhat happened (last time|just now|there|with (the|my|that) (last )?(order|run|task))\b`,
  String.raw`\b(did|has|is|was)\s+(my|the)\s+(last\s+)?order\s+(go through|gone through|placed|been placed|succeed|work|done|complete)\b`,
  String.raw`\b(status|result)\s+of\s+(the\s+|my\s+)?(last|previous|latest)\s+(run|order|task|attempt)\b`,
  String.raw`\bwhere did (it|you) stop\b`,
].join('|'), 'i');

export const isStatusQuery = (text) => STATUS_QUERY.test(text);

/**
 * A command as Deepgram punctuated it, made fit to say back: smart_format cuts one spoken command into sentences
 * ("Order a Farmhouse pizza. From Domino's in Zomato."), which then became the flow's description verbatim.
 */
export const tidyCommand = (t = '') => String(t).replace(/[.!?]+\s+(?=\S)/g, ' ').replace(/[\s.!?]+$/, '').trim();

const STEP_VERBS = { launch: 'opening the app', tap: 'tapping', type: 'typing', scroll: 'scrolling', back: 'going back',
  wait: 'waiting', set_quantity: 'setting the quantity', choose_address: 'choosing the delivery address' };

/** Human wording for step `i` of a flow ("tapping Add item ₹114"); falls back to its number when the flow is gone. */
export function describeStep(flow, i) {
  const s = flow?.steps?.[i];
  if (!s) return `step ${i + 1}`;
  const target = s.selector?.text || s.selector?.contentDesc || '';
  return `step ${i + 1}, ${STEP_VERBS[s.type] ?? s.type}${target ? ` "${target}"` : ''}`;
}

/** Slot tokens in a stored selector put back to the values of THAT run ("tapping "{{restaurant}}"" -> "Pizza Hut"). */
const fill = (text, slots = {}) => String(text).replace(/\{\{(\w+)\}\}/g, (m, k) => slots[k] ?? m);

/**
 * What went wrong, as a statement: the questions the run asked at the time ("What should I do?") and the screen
 * listing are not part of a status answer.
 */
const reason = (err = '') => String(err).split(/(?<=[.?!])\s+/)
  .filter((s) => !/\?\s*$/.test(s) && !/^this screen shows/i.test(s)).join(' ').trim();

export function statusLine(run, flow) {
  if (!run) return "I haven't run anything yet.";
  // What was ASKED for, not what the flow was taught with: a Margherita run of the Farmhouse-taught flow is a Margherita run.
  const what = tidyCommand(run.utterance || flow?.description || '') || 'that task';
  if (run.declined) return `The last run of "${what}" was stopped by you before a sensitive step.`;
  if (run.handover) return `The last run of "${what}" reached the ${run.handover} screen and handed over to you. That is where it stopped.`;
  if (run.success) return `Yes, the last run of "${what}" finished all its steps.`;
  const bad = run.stepResults?.find((s) => !s.ok);
  const why = reason(bad?.error);
  return `No. The last run of "${what}" stopped at ${bad ? fill(describeStep(flow, bad.index), run.slots) : 'the start'}${why ? `: ${why.replace(/[.!]+$/, '')}` : ''}.`;
}

/** A reported reason as a finished sentence: one that already ends in . ? or ! ("What should I do?") is left alone. */
export const sentence = (t = '') => (/[.?!]\s*$/.test(t) ? t.trim() : `${t.trim()}.`);
