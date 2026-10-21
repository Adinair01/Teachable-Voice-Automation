// Writes the flows the backend learns from the Part 0 demonstrations, and the values each later command replays
// with, to the Android test resources. The Kotlin replay tests run THESE flows, so they test what the backend really
// produces; test/part0Chain.test.js fails if this file is out of date. Run: node scripts/part0-fixture.mjs
import { writeFileSync } from 'node:fs';
import { part0Fixture, FIXTURE_PATH } from '../test/fixtures/part0.js';

writeFileSync(FIXTURE_PATH, `${JSON.stringify(part0Fixture(), null, 2)}\n`);
console.log(`wrote ${FIXTURE_PATH.pathname}`);
