/**
 * Deepgram Nova-3 keyterm hints, built from what THIS user has taught: the dishes, restaurants and other values their
 * flows were taught with, and the names of the apps those flows run in. A dish a judge teaches live is boosted from
 * the next command on, exactly like the ones taught before it. Nothing here is a fixed vocabulary.
 */
const MAX_TERMS = 50;
const MAX_LEN = 40;
// Values that are not words worth biasing a recogniser towards: numbers ("5", "1") and the implied defaults.
const SKIP_SLOTS = new Set(['quantity', 'address']);
const isWordy = (v) => /[a-z]/i.test(v) && v.length >= 3 && v.length <= MAX_LEN;

export function keytermsFor(flows = [], apps = []) {
  const label = new Map(apps.map((a) => [a.package, a.label]));
  const terms = [];
  const seen = new Set();
  const add = (v) => {
    const t = String(v ?? '').trim();
    const k = t.toLowerCase();
    if (!isWordy(t) || seen.has(k) || terms.length >= MAX_TERMS) return;
    seen.add(k);
    terms.push(t);
  };
  for (const f of flows) {
    for (const s of f.slots ?? []) if (!SKIP_SLOTS.has(s.name)) add(s.example);
    add(label.get(f.appPackage));
  }
  return terms;
}
