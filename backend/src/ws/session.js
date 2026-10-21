import { z } from 'zod';
import { logger } from '../lib/logger.js';
import { SttStream, synthesize } from '../lib/deepgram.js';
import { createHash } from 'node:crypto';
import { Flow } from '../models/Flow.js';
import { RunLog } from '../models/RunLog.js';
import { extractIntent } from '../prompts/intent.js';
import { normaliseSlots, replaySlots, askFor } from '../prompts/slots.js';
import { matchFlow } from '../prompts/matchFlow.js';
import { pastPhrasings } from '../services/phrasings.js';
import { correctionInput, plan, apply, describe, isYes, isNo } from '../services/correction.js';
import { generalise, IMPLIED_SLOTS } from '../services/generalise.js';
import { flowInput } from '../routes/flows.js';
import { parseOpenApp, resolveApp } from '../services/openApp.js';
import { HANDOVER_KINDS, handoverLine } from '../services/handover.js';
import { isStatusQuery, statusLine, sentence, tidyCommand } from '../services/runStatus.js';
import { keytermsFor } from '../services/keyterms.js';
import { stripEcho } from '../services/echo.js';

/** Spoken when nothing matches and teach mode opens (exact wording is part of the demo script). */
const TEACH_PROMPT = "I haven't learned that yet. Show me once: do it on the screen, then say done.";
/** Said when the user ends a run with "done, thank you": they are where they wanted to be. */
const GLAD = "I'm glad you got to your destination. Thank you!";
// "Thank you", "done, thanks", "that's all": the user signing off. Never a new task to learn. (A bare "done" is not this.)
const THANKS = /^\s*(?:ok(?:ay)?[\s,]+|great[\s,]+|perfect[\s,]+)?(?:(?:all |i'?m |we'?re )?done[\s,.!]+)?(?:thanks?|thank you)(?: (?:so much|a lot|very much))?[\s.!]*$|^\s*that'?s (?:it|all)(?:[\s,]+thanks?| thank you)?[\s.!]*$/i;
const CANCEL = /^\s*(cancel|never ?mind|forget it|no)\b/i;
/** A question the assistant asked is only an open question for this long; after that a reply is a new command. */
const PENDING_MS = 60_000;
/** Said when understanding takes longer than this, so a slow model never leaves the user in silence. */
const ACK_MS = 1200;
const ACK = 'One moment.';
const BUSY = "Sorry, my language service is busy right now. Please say that again in a few seconds.";
// A reply that opens like a request is a new command, not an answer to the question that was asked.
const COMMAND_START = /^\s*(?:(?:please|ok(?:ay)?|hey|so|and|now|actually|also)[\s,]+)*(?:(?:can|could|would) you\s+|i (?:want|need|would like|'d like) to\s+|let'?s\s+)?(order|book|buy|get me|open|call|send|play|set|search|find|calculate|compute|add|show|take me|navigate|turn|start|launch|text|message|email|did|what|how|where|is|are|do|does|tell)\b/i;
const MAX_ANSWER_WORDS = 6;
/** How long something the assistant said can still come back through the microphone. */
const ECHO_MS = 30_000;
/** Below this the recogniser was guessing: a new task is not opened on a guess. */
const MIN_CONFIDENCE = 0.7;
const DONE = /^\s*(done|i'?m done|finished|that'?s it|stop teaching)\b/i;

const teachDone = z.object({
  command: z.string().min(1).max(300),
  intentKey: z.string().min(1).max(80),
  slots: z.record(z.string().max(200)).default({}),
  steps: z.array(z.object({ type: z.string() }).passthrough()).max(200),
});
const appList = z.object({
  apps: z.array(z.object({ label: z.string().min(1).max(120), package: z.string().regex(/^[\w.]{1,200}$/) })).max(1000),
});
const runResult = z.object({
  flowId: z.string().min(1),
  success: z.boolean(),
  declined: z.boolean().default(false),
  // The user answered a question with "done, thank you": they got where they wanted.
  finished: z.boolean().default(false),
  handover: z.object({ kind: z.enum(HANDOVER_KINDS), reason: z.string().max(200) }).nullable().default(null),
  utterance: z.string().max(500).default(''),
  steps: z.array(z.object({ i: z.number(), ok: z.boolean(), llm: z.boolean().default(false), err: z.string().nullable().default(null) })).max(200).default([]),
  // What the user's answers to "what should I do?" imply for the flow. Offered, never applied, until they say yes.
  corrections: z.array(correctionInput).max(20).default([]),
});
const fingerprint = (steps) => createHash('sha1').update(JSON.stringify(steps)).digest('hex');

/** Everything the session reads or writes outside itself. Replaced in tests, so the whole chain runs without Mongo or Groq. */
export const liveDeps = {
  loadFlows: () => Flow.find().sort({ createdAt: -1 }).limit(100).lean(),
  findFlow: (id) => Flow.findById(id).lean().catch(() => null),
  createFlow: (doc) => Flow.create(doc),
  updateFlowSteps: (id, steps) => Flow.updateOne({ _id: id }, { $set: { steps } }, { runValidators: true }),
  log: (doc) => RunLog.create(doc),
  lastRun: () => RunLog.findOne({ kind: 'replay' }).sort({ createdAt: -1 }).lean(),
  pastPhrasings, matchFlow, extractIntent, synthesize,
  stt: (opts) => new SttStream(opts),
  now: () => Date.now(),
};

/** One phone connection: audio -> STT -> intent/match -> replay | teach; teach_done -> generalise -> save. */
export class Session {
  constructor(socket, deps = liveDeps) {
    this.socket = socket; this.deps = { ...liveDeps, ...deps };
    this.stt = null; this.sttMode = 'command'; this.teaching = null; this.apps = []; this.pending = null; this.pendingCorrection = null;
    /** The last replay sent to the phone: its flow, the values it ran with, and how the command was heard. */
    this.lastReplay = null;
    this.heard = null;
    this.keyterms = [];
    this.saidAt = 0;
    /** What the assistant said recently, to recognise it if the microphone picks it up again. */
    this.spoken = [];
    this.refreshKeyterms();
  }

  send(obj) { if (this.socket.readyState === 1) this.socket.send(JSON.stringify(obj)); }

  /** Speech-recognition hints from what THIS user taught (dishes, restaurants, app names), not a fixed vocabulary. */
  refreshKeyterms() {
    return Promise.resolve().then(() => this.deps.loadFlows())
      .then((flows) => { this.keyterms = keytermsFor(flows, this.apps); })
      .catch((e) => logger.warn({ err: e.message }, 'keyterms not refreshed'));
  }

  /**
   * The words and the voice go out TOGETHER: the text used to be shown a second before the audio (synthesis time), so
   * what was on screen and what was heard never lined up. If synthesis fails the text still goes out.
   */
  async say(text) {
    const now = this.deps.now();
    this.saidAt = now;
    this.spoken = [...this.spoken.filter((s) => now - s.at < ECHO_MS), { text, at: now }];
    let audio = null;
    try { audio = await this.deps.synthesize(text); } catch (e) { logger.warn({ err: e.message }, 'tts failed; text only'); }
    this.send({ type: 'say', text });
    if (!audio) return;
    this.send({ type: 'audio', format: 'mp3' });
    if (this.socket.readyState === 1) this.socket.send(audio);
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
        this.stt = this.deps.stt({ keyterms: this.keyterms });
        this.sttMode = msg.mode === 'confirm' ? 'confirm' : 'command'; // confirm: transcript only, never a command
        return;
      case 'say_request': return this.say(z.string().min(1).max(600).parse(msg.text));
      case 'audio_end': return this.#endAudio();
      case 'apps': this.apps = appList.parse(msg).apps; this.refreshKeyterms(); return;
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
    const heard = await stt.finish();
    // The microphone may have caught the end of the assistant's own last reply: that is not the user speaking.
    const recent = this.spoken.filter((s) => this.deps.now() - s.at < ECHO_MS).map((s) => s.text);
    const text = stripEcho(heard.text ?? '', recent);
    if (text !== (heard.text ?? '').trim()) logger.info({ heard: heard.text, kept: text }, 'removed the assistant\'s own words from a transcript');
    heard.text = text;
    this.send({ type: 'transcript', text });
    if (this.sttMode === 'confirm') return;
    if (!text) return this.say("Sorry, I didn't catch that.");
    // Understanding can take a second or more (a model may be rate limited): say something rather than nothing.
    const started = this.deps.now();
    const ack = setTimeout(() => { if (this.saidAt < started) this.say(ACK); }, ACK_MS);
    try { await this.handleCommand(text, { heard: { ...heard, source: 'voice' } }); } finally { clearTimeout(ack); }
  }

  async handleCommand(text, { heard } = {}) {
    this.heard = heard ?? { text, source: 'typed' };
    // Only "done" is meaningful while teaching; anything else would otherwise vanish without a word back.
    if (this.teaching) {
      // A clean voice exit: without this, "cancel"/"never mind" fell through to "I'm still watching" and the only way
      // out was "done" (which then failed on an empty demo). A judge who declines to teach must be able to back out.
      if (CANCEL.test(text)) { const cmd = this.teaching.command; this.teaching = null; this.send({ type: 'teach_cancel' }); await this.#logLearn(false, cmd); return this.say("Okay, I've stopped. Nothing was saved."); }
      return DONE.test(text) ? this.send({ type: 'teach_finish' }) : this.say("I'm still watching. Say done when you have finished, or cancel to stop.");
    }
    // A yes/no to "should I update the flow?" is the ONLY way a taught flow changes after a run. Anything else
    // drops the offer, so an unrelated command can never be mistaken for consent.
    if (this.pendingCorrection) {
      const offered = this.pendingCorrection; this.pendingCorrection = null;
      if (isYes(text)) return this.#applyCorrections(offered);
      if (isNo(text)) return this.say("Okay, I've left the flow exactly as it was.");
    }
    if (isStatusQuery(text)) return this.#reportLastRun();
    if (THANKS.test(text)) return this.say(GLAD);
    try {
      return await this.#understand(text);
    } catch (e) {
      // Every model failing (rate limits) used to end in a silent 'internal error' to the phone. Say what happened.
      logger.error({ err: e.message }, 'could not understand the command');
      return this.say(BUSY);
    }
  }

  async #understand(text) {
    const pending = this.pending; this.pending = null;
    const open = pending && this.deps.now() - pending.at <= PENDING_MS;
    if (open && CANCEL.test(text)) return this.say('Okay, I cancelled that.');
    const app = resolveApp(parseOpenApp(text), this.apps);
    if (app) {
      this.send({ type: 'launch_app', package: app.package, label: app.label });
      return this.say(`Opening ${app.label}.`);
    }
    const flows = await this.deps.loadFlows();
    // Commands that already worked are handed to the matcher alongside each flow's taught wording, so the way this
    // user actually speaks keeps being recognised instead of only the sentence used while teaching.
    const said = flows.length ? await this.deps.pastPhrasings(flows) : {};
    const withSaid = flows.map((f) => ({ ...f, alsoSaidAs: said[String(f._id)] ?? [] }));
    const m = flows.length ? await this.deps.matchFlow(text, withSaid) : { ...(await this.deps.extractIntent(text)), flowId: null };
    if (open) {
      // EVERY reply is understood first. It answers the question only if it does not read as a new request: a reply
      // that matches another flow, or starts like a command, used to be typed into the search box as the dish.
      const values = this.#asAnswer(pending, text, m);
      if (values) return this.#replay(pending.flow, pending.utterance, values);
    }
    if (m.flowId) {
      const flow = flows.find((f) => String(f._id) === m.flowId);
      const slots = normaliseSlots(m.slots);
      if (m.clarify) return this.#ask({ flow, slots, slot: m.clarify.slot, utterance: text }, m.clarify.question); // T13
      return this.#replay(flow, text, slots);
    }
    // No flow matched although some exist: for an eval set of paraphrases that is a miss, and the metric would
    // flatter itself if only matched-and-replayed runs were ever counted. (A genuinely new task lands here too.)
    // A recogniser's guess ("Then", confidence 0.63, seen live) must not open teach mode: ask for it again instead.
    const h = this.heard;
    if (h?.source === 'voice' && (text.trim().split(/\s+/).length < 2 || (h.confidence != null && h.confidence < MIN_CONFIDENCE))) {
      return this.say("Sorry, I didn't catch that. Hold the button and say it again.");
    }
    if (flows.length) await this.deps.log({ kind: 'match', success: false, utterance: text, heard: this.heard });
    // Slots for a new task always come from the dedicated extractor: the matcher's side-output for a non-match can
    // return a whole phrase ("5 plus 3") as one slot, which then cannot be found in the demonstration.
    const intent = await this.deps.extractIntent(text);
    // Normalised at teach time too, so the flow is learned with the same value shapes a replay will bring.
    this.teaching = { command: text, intentKey: intent.intentKey, slots: normaliseSlots(intent.slots ?? {}), heard: this.heard };
    this.send({ type: 'teach_start', command: text, intentKey: this.teaching.intentKey, slots: this.teaching.slots });
    return this.say(TEACH_PROMPT);
  }

  /** Remembers the question so the next reply can answer it, for [PENDING_MS] at most. */
  #ask(pending, question) {
    this.pending = { ...pending, at: this.deps.now() };
    return this.say(question);
  }

  /**
   * The values to run with if [text] answers the open question, else null (it is a new command). The same flow
   * matched: its values fill the gaps, the reply's own values win. Another flow: new command. Nothing matched: an
   * answer only if it is short and does not open like a request ("Farmhouse", "the farmhouse one", "three").
   */
  #asAnswer(pending, text, m) {
    const same = m.flowId && m.flowId === String(pending.flow._id);
    if (m.flowId && !same) return null;
    const own = same ? normaliseSlots(m.slots) : {};
    if (own[pending.slot] != null) return { ...pending.slots, ...own };
    const words = text.trim().split(/\s+/).length;
    if (COMMAND_START.test(text) || words > MAX_ANSWER_WORDS) return null;
    const answer = normaliseSlots({ [pending.slot]: text })[pending.slot] ?? text.trim();
    return { ...pending.slots, ...own, [pending.slot]: answer };
  }

  /**
   * The flow supplies the STRUCTURE; the values come from THIS utterance. They are put in the taught shape ("seven" ->
   * "7"), a missing subject is asked for rather than silently copied from the demonstration, and only then do the
   * remaining gaps fall back to the taught example (the place an order comes from).
   */
  #replay(flow, utterance, given) {
    const { ask, slots } = replaySlots(given, flow.slots, IMPLIED_SLOTS.map((s) => s.name));
    if (ask) return this.#ask({ flow, slots, slot: ask.name, utterance }, askFor(ask));
    this.lastReplay = { flowId: String(flow._id), slots, heard: this.heard };
    this.send({ type: 'replay', flowId: String(flow._id), utterance, flow, slots });
    return this.say('Okay, doing that now.');
  }

  async #reportLastRun() {
    const run = await this.deps.lastRun();
    const flow = run?.flowId ? await this.deps.findFlow(run.flowId) : null;
    return this.say(statusLine(run, flow));
  }

  async #learn(body) {
    const heard = this.teaching?.heard;
    this.teaching = null;
    // The description is said back ("Learned: …") and in status answers: without Deepgram's mid-command full stops.
    const g = generalise({ ...body, command: tidyCommand(body.command) });
    const actionable = g.steps.filter((s) => s.type !== 'launch').length;
    // EVERY value the command named must be one the demonstration used. One that wasn't stays a literal of the demo
    // in its step, and each later run would quietly repeat it — "order a Farmhouse" ordering the taught Margherita.
    const ok = actionable >= 1 && g.missing.length === 0;
    if (!ok) {
      // A failed learn used to leave no trace of what was actually recorded, making it undebuggable after the fact.
      logger.warn({ command: body.command, slots: g.slots, actionable, slotsFound: g.slotsFound,
        steps: body.steps.map((s) => ({ type: s.type, text: s.selector?.text, desc: s.selector?.contentDesc,
          value: s.value, context: s.context })) }, 'learn failed');
      await this.#logLearn(false, body.command, undefined, heard);
      this.send({ type: 'teach_failed' }); // lets the phone drop out of "Saving…" instead of hanging there
      return this.say(g.missing.length && actionable >= 1
        ? `I couldn't find "${g.missing[0].example}" in what you did, so I haven't saved anything. Say the command again to show me from the start.`
        : actionable === 0 && g.steps.length
          ? "I only saw an app being opened. To open an app, just say open and its name. Teach me a task inside the app instead."
          : "I didn't see any actions, so there's nothing to save. Say the command again when you want to show me.");
    }
    const { slotsFound, missing, ...doc } = g;
    const flow = await this.deps.createFlow(flowInput.parse(doc));
    await this.#logLearn(true, body.command, flow._id, heard);
    this.refreshKeyterms();
    this.send({ type: 'learned', flowId: String(flow._id), steps: g.steps.length });
    return this.say(`Learned: ${flow.description}.`);
  }

  #logLearn(success, utterance, flowId, heard) { return this.deps.log({ kind: 'learn', success, utterance, flowId, heard }); }

  async #logRun(r) {
    // The values and the transcript of the replay this result belongs to (the phone does not send them back).
    const mine = this.lastReplay?.flowId === r.flowId ? this.lastReplay : null;
    const base = { flowId: r.flowId, success: r.success, utterance: r.utterance, slots: mine?.slots, heard: mine?.heard };
    await this.deps.log({ ...base, kind: 'replay', declined: r.declined, endedByUser: r.finished, handover: r.handover?.kind, stepResults: r.steps.map((s) => ({ index: s.i, ok: s.ok, usedLlmFallback: s.llm, error: s.err })) });
    // A phrasing counts as correctly matched when the matched flow then ran to completion — or when the user
    // stopped it at a sensitive step, which says nothing about the match being wrong.
    // A handover also means the right flow ran: it got as far as the user's own credentials or payment.
    if (r.utterance && !r.declined) await this.deps.log({ ...base, kind: 'match', success: r.success || !!r.handover });
    // What happened, then (if the user's answers showed the flow is out of date) the offer to fix it — ONE spoken
    // message, because two back-to-back ones can cut each other off on the phone.
    let line;
    if (r.finished) line = GLAD;
    else if (r.handover) line = handoverLine(r.handover.kind);
    else if (r.declined) line = 'Okay, I stopped before that step.';
    else if (r.success) line = 'Done.';
    else {
      // Never a generic "something went wrong": say where it stopped and why (the phone reports a specific reason).
      const stop = r.steps.find((s) => !s.ok);
      line = stop ? `I stopped at step ${stop.i + 1}: ${sentence(stop.err ?? 'it did not work')}` : 'I could not finish that.';
    }
    const offer = await this.#offerCorrections(r);
    await this.say(offer ? `${line} ${offer}` : line);
  }

  /** Works out what to propose from the run's corrections and remembers it for the user's yes or no. */
  async #offerCorrections(r) {
    if (!r.corrections.length) return '';
    const flow = await this.deps.findFlow(r.flowId);
    if (!flow) return '';
    // The run's own values: an answer that named this request's dish must not be learned as a fixed tap.
    const planned = plan(flow.steps, r.corrections, this.lastReplay?.flowId === r.flowId ? this.lastReplay.slots : {});
    const text = describe(planned);
    if (planned.offer.length) this.pendingCorrection = { flowId: String(flow._id), offer: planned.offer, seen: fingerprint(flow.steps) };
    return text;
  }

  async #applyCorrections({ flowId, offer, seen }) {
    const flow = await this.deps.findFlow(flowId);
    if (!flow) return this.say("I can't find that flow any more, so nothing was changed.");
    // The flow may have been re-taught between the run and the yes: never apply a change meant for a different version.
    if (fingerprint(flow.steps) !== seen) return this.say('That flow has changed since, so I did not touch it.');
    const steps = apply(flow.steps, offer);
    await this.deps.updateFlowSteps(flow._id, steps);
    logger.info({ flowId, ops: offer.map((o) => `${o.op}@${o.step}`) }, 'flow updated from the user\'s corrections');
    return this.say(`Done. I've updated the flow, ${offer.length === 1 ? 'one step' : `${offer.length} steps`} changed.`);
  }

  close() { this.stt?.finish().catch(() => {}); }
}
