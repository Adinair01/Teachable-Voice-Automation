/**
 * The phone's microphone hears the assistant's own voice when the user starts talking before a reply has finished.
 * Seen live: "Step 12. You asked me to stop. Order a Farmhouse pizza…" (the start was the assistant's line), "Done.
 * Calculate seven minus eight", and a whole command that was nothing but the assistant's sentence — which then opened
 * teach mode. This removes, from the START of a transcript, what the assistant itself said in the last few seconds.
 */
const words = (s = '') => s.toLowerCase().replace(/[^a-z0-9\s]/g, ' ').split(/\s+/).filter(Boolean);
/** A run of the assistant's words this long is never the user's own: short ones ("done", "okay") might be. */
const MIN_RUN = 3;

/** Is [run] a contiguous piece of [line]? */
const within = (run, line) => {
  for (let i = 0; i + run.length <= line.length; i++) if (run.every((w, j) => line[i + j] === w)) return true;
  return false;
};

/**
 * [transcript] without its leading echo of any of [recent] (the assistant's recent lines). The longest echoed prefix
 * is removed when it is at least three words long, or when it is one of the assistant's lines in full ("Done.").
 */
export function stripEcho(transcript = '', recent = []) {
  const said = words(transcript);
  const lines = recent.map(words).filter((l) => l.length);
  for (let n = said.length; n > 0; n--) {
    const head = said.slice(0, n);
    const echoed = lines.some((l) => within(head, l) && (n >= MIN_RUN || l.length === n));
    if (echoed) return dropWords(transcript, n);
  }
  return transcript.trim();
}

/** The transcript with its first [n] words removed, keeping the rest exactly as written. */
function dropWords(text, n) {
  const re = /[A-Za-z0-9]+/g;
  let m;
  let count = 0;
  while ((m = re.exec(text))) {
    if (++count === n) return text.slice(re.lastIndex).replace(/^[\s.,!?;:'"-]+/, '').trim();
  }
  return '';
}
