import WebSocket from 'ws';
import { config } from './config.js';
import { logger } from './logger.js';

// Domain words that generic STT mangles ("Farmhouse" -> "farmer's", "Zomato" -> "Tomato", "Domino's" -> "dominoes").
// Nova-3 keyterm prompting biases recognition toward these exact terms. This is a transcription-accuracy hint, NOT
// flow logic or a hard-coded flow — it just helps the recogniser hear the app/menu vocabulary a food assistant uses.
const KEYTERMS = [
  'Zomato', "Domino's", 'Margherita', 'Farmhouse', 'pizza', 'garlic bread', 'paneer', 'Amazon', 'Myntra',
  'earbuds', 'Bluetooth', 'Calculator', 'stopwatch',
];
const STT_URL = 'wss://api.deepgram.com/v1/listen?model=nova-3&encoding=linear16&sample_rate=16000&channels=1&smart_format=true&punctuate=true'
  + KEYTERMS.map((t) => `&keyterm=${encodeURIComponent(t)}`).join('');
const TTS_URL = 'https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=mp3';

/** One push-to-talk utterance streamed to Deepgram Nova 3. finish() resolves with the final transcript. */
export class SttStream {
  constructor() {
    if (!config.DEEPGRAM_API_KEY) throw new Error('DEEPGRAM_API_KEY not configured');
    this.parts = [];
    this.pending = [];   // audio that arrives before the socket opens
    this.open = false;
    this.done = new Promise((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
    this.ws = new WebSocket(STT_URL, { headers: { Authorization: `Token ${config.DEEPGRAM_API_KEY}` } });
    this.ws.on('open', () => { this.open = true; this.pending.forEach((b) => this.ws.send(b)); this.pending = []; if (this.closing) this.#close(); });
    this.ws.on('message', (data) => {
      try {
        const m = JSON.parse(data.toString());
        const text = m.channel?.alternatives?.[0]?.transcript;
        if (m.type === 'Results' && m.is_final && text) this.parts.push(text);
      } catch (e) { logger.warn({ err: e.message }, 'stt: bad message'); }
    });
    this.ws.on('close', () => this.resolve(this.parts.join(' ').trim()));
    this.ws.on('error', (err) => { logger.error({ err: err.message }, 'stt error'); this.reject(err); });
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
