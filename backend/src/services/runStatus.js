/** T14: answer "did the last run succeed?" from the latest replay RunLog — where it stopped, in words. */
const STATUS_QUERY = /\b(did|was|has|have)\b.*\b(last|previous|latest|recent)\b.*\b(run|order|replay|one|attempt|task|time)\b|\b(last|previous|latest)\s+(run|order|replay|attempt)\b.*\b(succe\w*|work\w*|fail\w*|go|went|done)\b|\bhow did (it|that|the last \w+) go\b/i;

export const isStatusQuery = (text) => STATUS_QUERY.test(text);

const STEP_VERBS = { launch: 'opening the app', tap: 'tapping', type: 'typing', scroll: 'scrolling', back: 'going back',
  wait: 'waiting', set_quantity: 'setting the quantity', choose_address: 'choosing the delivery address' };

/** Human wording for step `i` of a flow ("tapping Add item ₹114"); falls back to its number when the flow is gone. */
export function describeStep(flow, i) {
  const s = flow?.steps?.[i];
  if (!s) return `step ${i + 1}`;
  const target = s.selector?.text || s.selector?.contentDesc || '';
  return `step ${i + 1}, ${STEP_VERBS[s.type] ?? s.type}${target ? ` "${target}"` : ''}`;
}

export function statusLine(run, flow) {
  if (!run) return "I haven't run anything yet.";
  const what = flow?.description ?? run.utterance ?? 'that task';
  if (run.declined) return `The last run of "${what}" was stopped by you before a sensitive step.`;
  if (run.handover) return `The last run of "${what}" reached the ${run.handover} screen and handed over to you. That is where it stopped.`;
  if (run.success) return `Yes, the last run of "${what}" finished all its steps.`;
  const bad = run.stepResults?.find((s) => !s.ok);
  return `No. The last run of "${what}" stopped at ${bad ? describeStep(flow, bad.index) : 'the start'}${bad?.error ? `: ${bad.error}` : ''}.`;
}

/** A reported reason as a finished sentence: one that already ends in . ? or ! ("What should I do?") is left alone. */
export const sentence = (t = '') => (/[.?!]\s*$/.test(t) ? t.trim() : `${t.trim()}.`);
