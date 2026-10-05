// Writes engine/mirror-expected.json: what the TypeScript engine computes over vectors.json.
// The Kotlin test checks the Android engine against it, so the two implementations can't drift.
import { readFileSync, writeFileSync } from 'node:fs';
import { mirror } from '../src/engine.js';

const vectors = JSON.parse(readFileSync(new URL('../vectors.json', import.meta.url)));
const owners = [...new Set(vectors.map((v) => v.owner))];
const out = {};
for (const owner of owners) {
  const txs = vectors.filter((v) => v.owner === owner).map((v) => v.tx).sort((a, b) => a.slot - b.slot);
  const m = mirror(txs, owner);
  out[owner] = {
    won: m.won.toString(),
    givenBack: m.givenBack.toString(),
    wins: String(m.wins.length),
    wouldHaveKept: m.wouldHaveKept.toString(),
  };
}
writeFileSync(new URL('../mirror-expected.json', import.meta.url), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
