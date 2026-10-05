import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { decodeBanked, base58 } from '../lib/decode.ts';

const fixture = JSON.parse(readFileSync(new URL('./banked-devnet.json', import.meta.url)));

test('decodes the real Banked event our devnet smoke test emitted', () => {
  const e = decodeBanked(fixture.line.slice('Program data: '.length));
  assert.ok(e, 'event decoded');
  assert.equal(e.vault, '5cMv9srSUHZ1cehPKZVtHUmb3h9k5jXtVFmR7UCom6tb');
  assert.equal(e.amountLamports, 50_000_000n);
  assert.equal(e.profitLamports, 100_000_000n);
  assert.equal(e.totalBanked, 50_000_000n);
  assert.equal(e.bankCount, 1n);
  // the smoke test tied this bank to a made-up "sell" whose 64 bytes are 0..63
  assert.equal(e.sellSignature, base58(Uint8Array.from({ length: 64 }, (_, i) => i)));
});

test('ignores other events and short data', () => {
  assert.equal(decodeBanked(Buffer.alloc(200).toString('base64')), null);
  assert.equal(decodeBanked(Buffer.alloc(10).toString('base64')), null);
});

test('base58 round trips a known address', () => {
  // 32 zero bytes is the system program
  assert.equal(base58(new Uint8Array(32)), '11111111111111111111111111111111');
});
