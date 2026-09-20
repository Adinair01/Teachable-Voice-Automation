import { z } from 'zod';
import { logger } from '../lib/logger.js';
import { SttStream, synthesize } from '../lib/deepgram.js';
import { Flow } from '../models/Flow.js';
import { RunLog } from '../models/RunLog.js';
import { extractIntent } from '../prompts/intent.js';
import { matchFlow } from '../prompts/matchFlow.js';
import { generalise } from '../services/generalise.js';
import { flowInput } from '../routes/flows.js';

/** Spoken when nothing matches and teach mode opens (exact wording is part of the demo script). */
const TEACH_PROMPT = 'Do whatever you want to do on the screen so I can learn from it.';
const DONE = /^\s*(done|i'?m done|finished|that'?s it|stop teaching)\b/i;

const teachDone = z.object({
  command: z.string().min(1).max(300),
  intentKey: z.string().min(1).max(80),
  slots: z.record(z.string().max(200)).default({}),
  steps: z.array(z.object({ type: z.string() }).passthrough()).max(200),
});
const runResult = z.object({
  flowId: z.string().min(1),
  success: z.boolean(),
  declined: z.boolean().default(false),
  utterance: z.string().max(500).default(''),
  steps: z.array(z.object({ i: z.number(), ok: z.boolean(), llm: z.boolean().default(false), err: z.string().nullable().default(null) })).max(200).default([]),
});

/** One phone connection: audio -> STT -> intent/match -> replay | teach; teach_done -> generalise -> save. */
export class Session {
  constructor(socket) { this.socket = socket; this.stt = null; this.sttMode = 'command'; this.teaching = null; }

  send(obj) { if (this.socket.readyState === 1) this.socket.send(JSON.stringify(obj)); }

  async say(text) {
    this.send({ type: 'say', text });
    try {
      const audio = await synthesize(text);
      this.send({ type: 'audio', format: 'mp3' });
      if (this.socket.readyState === 1) this.socket.send(audio);
    } catch (e) { logger.warn({ err: e.message }, 'tts failed; text only'); }
  }

  async onMessage(data, isBinary) {
    if (isBinary) return this.stt?.write(data);
    let msg;
    try { msg = JSON.parse(data.toString()); } catch { return this.send({ type: 'error', error: 'invalid json' }); }
    try { await this.#dispatch(msg); } catch (e) {
      logger.error({ err: e.message, type: msg.type }, 'message handling failed');
      this.send({ type: 'error', error: e instanceof z.ZodError ? 'invalid message' : 'internal error' });
    }
  }

  async #dispatch(msg) {
    switch (msg.type) {
      case 'ping': return this.send({ type: 'pong' });
      case 'audio_start':
        this.stt?.finish().catch(() => {});
        this.stt = new SttStream();
        this.sttMode = msg.mode === 'confirm' ? 'confirm' : 'command'; // confirm: transcript only, never a command
        return;
      case 'say_request': return this.say(z.string().min(1).max(300).parse(msg.text));
      case 'audio_end': return this.#endAudio();
      case 'command': return this.handleCommand(z.string().min(1).max(500).parse(msg.text));
      case 'teach_done': return this.#learn(teachDone.parse(msg));
      case 'teach_cancel': this.teaching = null; return this.#logLearn(false, msg.command);
      case 'run_result': return this.#logRun(runResult.parse(msg));
      default: return this.send({ type: 'error', error: 'unknown type' });
    }
  }

  async #endAudio() {
    const stt = this.stt; this.stt = null;
    if (!stt) return;
    const text = await stt.finish();
    this.send({ type: 'transcript', text });
    if (this.sttMode === 'confirm') return;
    if (text) await this.handleCommand(text); else await this.say("Sorry, I didn't catch that.");
  }

  async handleCommand(text) {
    if (this.teaching) return DONE.test(text) ? this.send({ type: 'teach_finish' }) : undefined; // only "done" is meaningful while teaching
    const flows = await Flow.find().sort({ createdAt: -1 }).limit(100).lean();
    const m = flows.length ? await matchFlow(text, flows) : { ...(await extractIntent(text)), flowId: null };
    if (m.flowId) {
      const flow = flows.find((f) => String(f._id) === m.flowId);
      this.send({ type: 'replay', flowId: m.flowId, utterance: text, flow, slots: m.slots });
      return this.say('Okay, doing that now.');
    }
    const intent = m.intentKey ? m : await extractIntent(text);
    this.teaching = { command: text, intentKey: intent.intentKey, slots: intent.slots ?? m.slots ?? {} };
    this.send({ type: 'teach_start', ...this.teaching });
    return this.say(TEACH_PROMPT);
  }

  async #learn(body) {
    this.teaching = null;
    const g = generalise(body);
    const actionable = g.steps.filter((s) => s.type !== 'launch').length;
    const ok = actionable >= 1 && (g.slots.length === 0 || g.slotsFound);
    if (!ok) {
      await this.#logLearn(false, body.command);
      this.send({ type: 'teach_failed' }); // lets the phone drop out of "Saving…" instead of hanging there
      return this.say(g.slots.length && !g.slotsFound
        ? `I couldn't find "${g.slots[0].example}" in what you did. Let's try again.`
        : "I didn't see any actions. Let's try again.");
    }
    const { slotsFound, ...doc } = g;
    const flow = await Flow.create(flowInput.parse(doc));
    await this.#logLearn(true, body.command, flow._id);
    this.send({ type: 'learned', flowId: String(flow._id), steps: g.steps.length });
    return this.say('Got it. I learned that.');
  }

  #logLearn(success, utterance, flowId) { return RunLog.create({ kind: 'learn', success, utterance, flowId }); }

  async #logRun(r) {
    const base = { flowId: r.flowId, success: r.success, utterance: r.utterance };
    await RunLog.create({ ...base, kind: 'replay', declined: r.declined, stepResults: r.steps.map((s) => ({ index: s.i, ok: s.ok, usedLlmFallback: s.llm, error: s.err })) });
    // A phrasing counts as correctly matched when the matched flow then ran to completion — or when the user
    // stopped it at a sensitive step, which says nothing about the match being wrong.
    if (r.utterance && !r.declined) await RunLog.create({ ...base, kind: 'match' });
    if (r.declined) return this.say('Okay, I stopped before that step.');
    await this.say(r.success ? 'Done.' : 'Sorry, that did not work.');
  }

  close() { this.stt?.finish().catch(() => {}); }
}
