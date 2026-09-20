import { WebSocketServer } from 'ws';
import { logger } from '../lib/logger.js';
import { Session } from './session.js';

const MAX_PAYLOAD = 1024 * 1024; // 1 MB per frame

export function attachGateway(server) {
  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: MAX_PAYLOAD });
  wss.on('connection', (socket) => {
    logger.info('ws client connected');
    const session = new Session(socket);
    session.send({ type: 'ready' });
    socket.on('message', (data, isBinary) => { session.onMessage(data, isBinary); });
    socket.on('close', () => { session.close(); logger.info('ws client disconnected'); });
    socket.on('error', (err) => logger.error({ err: err.message }, 'ws error'));
  });
  return wss;
}
