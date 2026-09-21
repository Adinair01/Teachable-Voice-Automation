// Built-in "open <app>": no demonstration needed, and none is possible (the recorder ignores launcher taps).
const OPEN = /^\s*(?:please\s+)?(?:open|launch|start)(?:\s+up)?\s+(?:the\s+)?(.+?)(?:\s+(?:app|application))?\s*[.!?]?\s*$/i;

const norm = (s) => String(s ?? '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();

/** "open the Gmail app" -> "gmail"; null when the utterance is not an open-app command. */
export function parseOpenApp(text) {
  const m = OPEN.exec(text ?? '');
  return m ? norm(m[1]) || null : null;
}

/**
 * Exact (normalised) label match only. Fuzzy/contains matching would let "open Bluetooth settings" hit the Settings
 * app and shadow a taught flow; an unmatched name falls through to the normal flow matcher instead.
 */
export function resolveApp(name, apps) {
  if (!name) return null;
  const hits = apps.filter((a) => norm(a.label) === name);
  return hits.length === 1 ? hits[0] : null;
}
