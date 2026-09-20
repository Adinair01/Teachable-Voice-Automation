import { WebSocketServer } from 'ws';
import { logger } from '../lib/logger.js';

const MAX_PAYLOAD = 1024 * 1024; // 1 MB per message

// Day 1: handshake only. Audio->STT->LLM pipeline is wired on Day 2/3.
export function attachGateway(server) {
  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: MAX_PAYLOAD });
  wss.on('connection', (socket) => {
    logger.info('ws client connected');
    socket.send(JSON.stringify({ type: 'ready' }));
    socket.on('message', (data, isBinary) => {
      if (isBinary) return; // audio frames handled later
      let msg;
      try { msg = JSON.parse(data.toString()); } catch { return socket.send(JSON.stringify({ type: 'error', error: 'invalid json' })); }
      if (msg.type === 'ping') socket.send(JSON.stringify({ type: 'pong' }));
    });
    socket.on('close', () => logger.info('ws client disconnected'));
    socket.on('error', (err) => logger.error({ err: err.message }, 'ws error'));
  });
  return wss;
}
