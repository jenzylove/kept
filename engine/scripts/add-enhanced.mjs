// Attaches Helius's enhanced (pre parsed) transaction to each vector, so the engine's bulk-history
// path is tested against the same real transactions as the raw path.
// Usage: HELIUS_API_KEY=... node scripts/add-enhanced.mjs
import { readFileSync, writeFileSync } from 'node:fs';

const KEY = process.env.HELIUS_API_KEY;
const path = new URL('../vectors.json', import.meta.url);
const vectors = JSON.parse(readFileSync(path));
const sigs = vectors.map((v) => v.sig);
const r = await fetch(`https://api.helius.xyz/v0/transactions?api-key=${KEY}`, {
  method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ transactions: sigs }),
});
const out = await r.json();
let n = 0;
for (const v of vectors) {
  const h = out.find((x) => x.signature === v.sig);
  if (h) { v.enhanced = h; n++; }
}
writeFileSync(path, JSON.stringify(vectors));
console.log(`attached ${n} of ${vectors.length}`);
