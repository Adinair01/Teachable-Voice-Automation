import test from 'node:test';
import assert from 'node:assert/strict';
import { rate } from '../src/routes/metrics.js';

test('a replay the user declined is neither a success nor a failure', () => {
  assert.deepEqual(rate({ total: 5, ok: 4, declined: 1 }), { total: 4, successes: 4, rate: 1, declined: 1 });
});
test('no runs yet means no rate, not a division by zero', () => {
  assert.deepEqual(rate({ total: 0, ok: 0 }), { total: 0, successes: 0, rate: null });
});
