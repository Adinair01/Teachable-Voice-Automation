// End-to-end protocol smoke test against a running backend: node test/smoke.mjs [ws://localhost:3100/ws]
import WebSocket from 'ws';
const ws = new WebSocket(process.argv[2] ?? 'ws://localhost:3100/ws');
const inbox = []; let audioBytes = 0;
ws.on('message', (d, bin) => { if (bin) { audioBytes += d.length; return; } const m = JSON.parse(d); if (m.type !== 'say' && m.type !== 'audio') console.log('<-', m.type, JSON.stringify(m).slice(0, 160)); inbox.push(m); });
const wait = async (type) => { for (let i = 0; i < 200; i++) { const j = inbox.findIndex((m) => m.type === type); if (j >= 0) return inbox.splice(j, 1)[0]; await new Promise((r) => setTimeout(r, 100)); } throw new Error('timeout waiting ' + type); };
const send = (o) => { console.log('->', o.type, o.text ?? ''); ws.send(JSON.stringify(o)); };
await new Promise((r) => ws.on('open', r)); await wait('ready');

send({ type: 'command', text: 'search google for burger' });
const t = await wait('teach_start');
send({ type: 'teach_done', command: t.command, intentKey: t.intentKey, slots: t.slots, steps: [
  { type: 'launch', value: 'com.android.chrome' },
  { type: 'type', selector: { className: 'android.widget.EditText' }, value: 'burger' },
  { type: 'tap', selector: { text: 'burger near me' } } ] });
const learned = await wait('learned'); console.log('learned flow', learned.flowId);

send({ type: 'command', text: 'look up pizza on Google' });   // paraphrase + new slot
const r = await wait('replay');
console.log('matched:', r.flowId === learned.flowId, 'slots:', JSON.stringify(r.slots), 'step0 slotRef:', r.flow.steps[1].slotRef, r.flow.steps[1].value);
send({ type: 'run_result', flowId: r.flowId, success: true, utterance: 'look up pizza on Google', steps: [{ i: 0, ok: true }] });
await wait('say'); await new Promise((r) => setTimeout(r, 1500));
console.log('tts audio bytes received:', audioBytes);
ws.close();
