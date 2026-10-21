import WebSocket from 'ws';
import { config } from './config.js';
import { logger } from './logger.js';

const STT_BASE = 'wss://api.deepgram.com/v1/listen?model=nova-3&encoding=linear16&sample_rate=16000&channels=1&smart_format=true&punctuate=true';
const MAX_WORDS = 200;

/**
 * Nova-3 keyterm prompting biases recognition toward these terms ("Farmhouse" was heard as "farmer's", "Zomato" as
 * "Tomato"). The terms come from the caller — the session builds them from the user's own taught flows
 * (services/keyterms.js) — so a dish taught live gets the same boost as any other.
 */
export const sttUrl = (keyterms = []) => STT_BASE + keyterms.map((t) => `&keyterm=${encodeURIComponent(t)}`).join('');
const TTS_URL = 'https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=mp3';

/**
 * One push-to-talk utterance streamed to Deepgram Nova 3. finish() resolves with what was heard: the transcript, the
 * recogniser's confidence and per-word timings and confidences — kept so a mishearing can be found afterwards.
 */
export class SttStream {
  constructor({ keyterms = [] } = {}) {
    if (!config.DEEPGRAM_API_KEY) throw new Error('DEEPGRAM_API_KEY not configured');
    this.parts = [];
    this.confidences = [];
    this.words = [];
    this.pending = [];   // audio that arrives before the socket opens
    this.open = false;
    this.done = new Promise((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
    this.ws = new WebSocket(sttUrl(keyterms), { headers: { Authorization: `Token ${config.DEEPGRAM_API_KEY}` } });
    this.ws.on('open', () => { this.open = true; this.pending.forEach((b) => this.ws.send(b)); this.pending = []; if (this.closing) this.#close(); });
    this.ws.on('message', (data) => {
      try {
        const m = JSON.parse(data.toString());
        const alt = m.channel?.alternatives?.[0];
        if (m.type === 'Results' && m.is_final && alt?.transcript) this.#keep(alt);
      } catch (e) { logger.warn({ err: e.message }, 'stt: bad message'); }
    });
    this.ws.on('close', () => this.resolve(this.heard()));
    this.ws.on('error', (err) => { logger.error({ err: err.message }, 'stt error'); this.reject(err); });
  }

  #keep(alt) {
    this.parts.push(alt.transcript);
    if (typeof alt.confidence === 'number') this.confidences.push(alt.confidence);
    for (const w of alt.words ?? []) {
      if (this.words.length < MAX_WORDS) this.words.push({ w: String(w.punctuated_word ?? w.word ?? ''), s: w.start, e: w.end, c: w.confidence });
    }
  }

  /** { text, confidence (mean over final segments, null if none), words } */
  heard() {
    const c = this.confidences;
    return { text: this.parts.join(' ').trim(), confidence: c.length ? c.reduce((a, b) => a + b, 0) / c.length : null, words: this.words };
  }

  write(chunk) { this.open ? this.ws.send(chunk) : this.pending.push(chunk); }

  finish() {
    this.closing = true;
    if (this.open) this.#close();
    setTimeout(() => this.ws.terminate(), 8000).unref(); // never hang the session on a stuck socket
    return this.done;
  }

  #close() { try { this.ws.send(JSON.stringify({ type: 'CloseStream' })); } catch { /* socket already gone */ } }
}

/** Deepgram Aura TTS -> mp3 Buffer. Callers treat failure as non-fatal (text is still delivered). */
export async function synthesize(text, fetchImpl = fetch) {
  if (!config.DEEPGRAM_API_KEY) throw new Error('DEEPGRAM_API_KEY not configured');
  const res = await fetchImpl(TTS_URL, {
    method: 'POST',
    headers: { Authorization: `Token ${config.DEEPGRAM_API_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: text.slice(0, 500) }),
    signal: AbortSignal.timeout(15000),
  });
  if (!res.ok) throw new Error(`deepgram tts http ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}
