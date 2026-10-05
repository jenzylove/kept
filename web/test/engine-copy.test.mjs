import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// The web app ships a copy of the shared profit engine so it deploys on its own.
// Run `cp engine/src/engine.js web/lib/engine.js` after changing the original.
test('web/lib/engine.js is identical to engine/src/engine.js', () => {
  const a = readFileSync(new URL('../lib/engine.js', import.meta.url), 'utf8');
  const b = readFileSync(new URL('../../engine/src/engine.js', import.meta.url), 'utf8');
  assert.equal(a, b);
});
