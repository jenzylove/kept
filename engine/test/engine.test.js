import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { deltas, enhancedDeltas, classify, apply, bankAmount, mirror, mirrorEnhanced } from '../src/engine.js';

const vectors = JSON.parse(readFileSync(new URL('../vectors.json', import.meta.url)));

test('deltas match the Helius parser on every real vector', () => {
  assert.ok(vectors.length >= 10, 'need at least 10 vectors');
  for (const v of vectors) {
    const d = deltas(v.tx, v.owner);
    const tokens = Object.fromEntries(Object.entries(d.tokens).map(([m, t]) => [m, t.raw.toString()]));
    assert.equal(d.sol.toString(), v.expected.sol, `sol for ${v.sig}`);
    assert.deepEqual(tokens, v.expected.tokens, `tokens for ${v.sig}`);
  }
});

test('the spike sell is a sell and the spike bank is not', () => {
  const sell = vectors.find((v) => v.sig.startsWith('29L62xCL'));
  const bank = vectors.find((v) => v.sig.startsWith('4T4ELre6'));
  if (sell) assert.equal(classify(deltas(sell.tx, sell.owner)).kind, 'sell');
  if (bank) assert.equal(classify(deltas(bank.tx, bank.owner)).kind, 'other');
});

test('average cost: partial sell takes a proportional basis', () => {
  let pos = {};
  pos = apply(pos, { kind: 'buy', mint: 'M', raw: 100n, lamports: 1000n }).positions;
  pos = apply(pos, { kind: 'buy', mint: 'M', raw: 100n, lamports: 3000n }).positions; // avg 20/unit
  const r = apply(pos, { kind: 'sell', mint: 'M', raw: 50n, lamports: 1500n });
  assert.equal(r.event.basis, 1000n);
  assert.equal(r.event.profit, 500n);
  assert.deepEqual(r.positions.M, { raw: 150n, cost: 3000n });
});

test('selling more than was bought caps the basis at the known position', () => {
  const pos = apply({}, { kind: 'buy', mint: 'M', raw: 10n, lamports: 100n }).positions;
  const r = apply(pos, { kind: 'sell', mint: 'M', raw: 20n, lamports: 400n });
  assert.equal(r.event.basis, 100n);
  assert.equal(r.event.profit, 300n);
});

test('a sell with no known buy is unpriced and never banked', () => {
  const r = apply({}, { kind: 'sell', mint: 'M', raw: 5n, lamports: 50n });
  assert.equal(r.event.profit, null);
  assert.equal(bankAmount(r.event.profit, 5000), 0n);
});

test('losses and zero profit bank nothing; wins bank the share', () => {
  assert.equal(bankAmount(-10n, 5000), 0n);
  assert.equal(bankAmount(0n, 5000), 0n);
  assert.equal(bankAmount(1001n, 5000), 500n);
});

test('mirror over real history adds up', () => {
  const owner = 'GUQPSxhbssHyiACNRD6aKJhXUAmDGViuhwcyXvRr7Jvw';
  const txs = vectors.filter((v) => v.owner === owner).map((v) => v.tx).sort((a, b) => a.slot - b.slot);
  const m = mirror(txs, owner);
  assert.equal(m.won, m.wins.reduce((s, w) => s + w.profit, 0n));
  assert.ok(m.wouldHaveKept <= m.won);
});

test('mirror lists every priced sell in order, and wins plus losses equal sells', () => {
  const owner = 'GUQPSxhbssHyiACNRD6aKJhXUAmDGViuhwcyXvRr7Jvw';
  const txs = vectors.filter((v) => v.owner === owner).map((v) => v.tx).sort((a, b) => a.slot - b.slot);
  const m = mirror(txs, owner);
  assert.equal(m.sells.length, m.wins.length + m.losses.length);
  const slots = new Map(txs.map((t) => [t.transaction.signatures[0], t.slot]));
  for (let i = 1; i < m.sells.length; i++) assert.ok(slots.get(m.sells[i - 1].sig) <= slots.get(m.sells[i].sig));
});

test('the bulk (Helius enhanced) path gives the same deltas as the raw path on every real vector', () => {
  let checked = 0;
  for (const v of vectors) {
    if (!v.enhanced) continue;
    const raw = deltas(v.tx, v.owner);
    const bulk = enhancedDeltas(v.enhanced, v.owner);
    assert.equal(bulk.sol, raw.sol, `sol for ${v.sig}`);
    const norm = (t) => Object.fromEntries(Object.entries(t).map(([m, x]) => [m, x.raw.toString()]));
    assert.deepEqual(norm(bulk.tokens), norm(raw.tokens), `tokens for ${v.sig}`);
    checked++;
  }
  assert.ok(checked >= 10, `checked ${checked} vectors`);
});

test('mirrorEnhanced equals mirror over the same real history', () => {
  for (const owner of [...new Set(vectors.map((v) => v.owner))]) {
    const mine = vectors.filter((v) => v.owner === owner);
    const a = mirror(mine.map((v) => v.tx).sort((x, y) => x.slot - y.slot), owner);
    const b = mirrorEnhanced(mine.map((v) => v.enhanced), owner);
    assert.equal(b.won, a.won);
    assert.equal(b.givenBack, a.givenBack);
    assert.equal(b.wins.length, a.wins.length);
    assert.equal(b.wouldHaveKept, a.wouldHaveKept);
    assert.deepEqual(b.sells.map((x) => x.sig), a.sells.map((x) => x.sig));
  }
});
