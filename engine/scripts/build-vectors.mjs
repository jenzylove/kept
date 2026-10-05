// Builds engine/vectors.json from real mainnet transactions.
// Input tx = raw getTransaction (jsonParsed). Expected deltas = Helius enhanced parse, an independent parser.
// Usage: HELIUS_API_KEY=... node scripts/build-vectors.mjs
import { writeFileSync } from 'node:fs';

const KEY = process.env.HELIUS_API_KEY;
const RPC = `https://mainnet.helius-rpc.com/?api-key=${KEY}`;
const WSOL = 'So11111111111111111111111111111111111111112';

const rpc = async (method, params) =>
  (await (await fetch(RPC, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) })).json()).result;

async function enhanced(sigs) {
  const r = await fetch(`https://api.helius.xyz/v0/transactions?api-key=${KEY}`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ transactions: sigs }) });
  return r.json();
}

function expectedFromHelius(h, owner) {
  let sol = 0n;
  const tokens = {};
  for (const a of h.accountData || []) {
    if (a.account === owner) sol += BigInt(a.nativeBalanceChange);
    for (const t of a.tokenBalanceChanges || []) {
      if (t.userAccount !== owner) continue;
      const raw = BigInt(t.rawTokenAmount.tokenAmount);
      if (t.mint === WSOL) { sol += raw; continue; }
      tokens[t.mint] = ((tokens[t.mint] && BigInt(tokens[t.mint])) || 0n) + raw;
    }
  }
  if (h.feePayer === owner) sol += BigInt(h.fee);
  for (const m of Object.keys(tokens)) { if (tokens[m] === 0n) delete tokens[m]; else tokens[m] = tokens[m].toString(); }
  return { sol: sol.toString(), tokens };
}

// Owners and how many recent transactions to take from each.
const SOURCES = [
  { owner: 'GUQPSxhbssHyiACNRD6aKJhXUAmDGViuhwcyXvRr7Jvw', take: 8 },
  { owner: 'BZGKFmveLa8XSAjSAdb6u5EWZV7gKLWLekm8jUEVaZp8', take: 4 }, // Kept spike burner: buy, sell, bank, funding
  { owner: '5AAmiPHPNyPouvndtxVwsAYKxNdFfZWkAK4DyriNkKra', take: 4 },
];

const vectors = [];
for (const { owner, take } of SOURCES) {
  const sigs = (await rpc('getSignaturesForAddress', [owner, { limit: take }])).filter((s) => !s.err).map((s) => s.signature);
  const hs = await enhanced(sigs);
  for (const sig of sigs) {
    const tx = await rpc('getTransaction', [sig, { encoding: 'jsonParsed', maxSupportedTransactionVersion: 1 }]);
    const h = hs.find((x) => x.signature === sig);
    if (!tx || !h) continue;
    vectors.push({ owner, sig, version: tx.version, tx, expected: expectedFromHelius(h, owner) });
  }
}
writeFileSync(new URL('../vectors.json', import.meta.url), JSON.stringify(vectors));
console.log('vectors', vectors.length, 'versions', [...new Set(vectors.map((v) => v.version))]);
