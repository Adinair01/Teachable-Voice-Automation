import test from 'node:test';
import assert from 'node:assert/strict';
import { parseOpenApp, resolveApp } from '../src/services/openApp.js';

const apps = [{ label: 'Gmail', package: 'com.google.android.gm' }, { label: 'Settings', package: 'com.android.settings' },
  { label: 'Google Maps', package: 'com.google.android.apps.maps' }];

test('parses the common ways of asking to open an app', () => {
  for (const [t, want] of [['open Gmail', 'gmail'], ['Open the Gmail app.', 'gmail'], ['please launch Google Maps', 'google maps'],
    ['start up gmail application', 'gmail']]) assert.equal(parseOpenApp(t), want, t);
  assert.equal(parseOpenApp('calculate 5 plus 3'), null);
});
test('resolves by exact label only', () => {
  assert.equal(resolveApp('gmail', apps).package, 'com.google.android.gm');
  assert.equal(resolveApp('google maps', apps).package, 'com.google.android.apps.maps');
  // must not shadow taught flows such as "open Bluetooth settings"
  assert.equal(resolveApp(parseOpenApp('open Bluetooth settings'), apps), null);
  assert.equal(resolveApp('maps', apps), null);
});
test('ambiguous labels do not resolve', () => {
  assert.equal(resolveApp('gmail', [...apps, { label: 'GMail', package: 'x.y' }]), null);
});
