// Devnet end to end for the Android reflex. Plays a counterparty: it "sells" the trading wallet a token
// for SOL, then "buys" it back for more SOL. To the chain (and to the app) that is a real buy then a
// real profitable sell on the trading wallet. Nothing is faked: the app only ever sees balance changes.
//
// Usage: node scripts/devnet-e2e.mjs <tradingSecretBase58> [buySol=0.02] [sellSol=0.05] [stepDelaySec=10]
// Keys live outside the repo in ratchet-launch/keys.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import {
  Connection, Keypair, PublicKey, SystemProgram, Transaction, sendAndConfirmTransaction, LAMPORTS_PER_SOL,
} from '@solana/web3.js';
import {
  createMint, getAssociatedTokenAddressSync, createAssociatedTokenAccountIdempotentInstruction,
  createMintToInstruction, createTransferInstruction, TOKEN_PROGRAM_ID,
} from '@solana/spl-token';

const KEYS = 'C:/Users/LENOVO/ratchet-launch/keys';
const RPC = process.env.DEVNET_RPC || 'https://api.devnet.solana.com';
const conn = new Connection(RPC, 'confirmed');

const ALPHA = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
function b58decode(s) {
  let n = 0n; for (const c of s) n = n * 58n + BigInt(ALPHA.indexOf(c));
  const out = []; while (n > 0n) { out.unshift(Number(n & 0xffn)); n >>= 8n; }
  for (const c of s) { if (c === '1') out.unshift(0); else break; }
  return Uint8Array.from(out);
}

const [secret, buySol = '0.02', sellSol = '0.05', delay = '10'] = process.argv.slice(2);
const trading = Keypair.fromSecretKey(b58decode(secret));

const cpPath = `${KEYS}/devnet-counterparty.json`;
let counterparty;
if (existsSync(cpPath)) counterparty = Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(cpPath))));
else { counterparty = Keypair.generate(); writeFileSync(cpPath, JSON.stringify([...counterparty.secretKey])); }

const sol = (l) => (l / LAMPORTS_PER_SOL).toFixed(4);
console.log('trading     ', trading.publicKey.toBase58());
console.log('counterparty', counterparty.publicKey.toBase58(), 'balance', sol(await conn.getBalance(counterparty.publicKey)));
if ((await conn.getBalance(counterparty.publicKey)) < 0.2 * LAMPORTS_PER_SOL) {
  console.error('Fund the counterparty with ~0.3 devnet SOL first (solana transfer <address> 0.3 -u devnet).');
  process.exit(2);
}

const mint = await createMint(conn, counterparty, counterparty.publicKey, null, 6);
console.log('mint        ', mint.toBase58());
const ata = getAssociatedTokenAddressSync(mint, trading.publicKey);
const cpAta = getAssociatedTokenAddressSync(mint, counterparty.publicKey);
const amount = 1_000_000_000n; // 1000 tokens

const buyLamports = Math.round(Number(buySol) * LAMPORTS_PER_SOL);
const sellLamports = Math.round(Number(sellSol) * LAMPORTS_PER_SOL);
const link = (s) => `https://explorer.solana.com/tx/${s}?cluster=devnet`;

// BUY: the trading wallet pays SOL, receives tokens. The counterparty pays the fee, so the trading
// wallet's SOL change is exactly the price.
const buy = new Transaction().add(
  createAssociatedTokenAccountIdempotentInstruction(counterparty.publicKey, ata, trading.publicKey, mint),
  createMintToInstruction(mint, ata, counterparty.publicKey, amount),
  SystemProgram.transfer({ fromPubkey: trading.publicKey, toPubkey: counterparty.publicKey, lamports: buyLamports }),
);
const buySig = await sendAndConfirmTransaction(conn, buy, [counterparty, trading]);
console.log(`BUY   paid ${sol(buyLamports)} SOL   ${link(buySig)}`);

console.log(`waiting ${delay}s so the app records the buy...`);
await new Promise((r) => setTimeout(r, Number(delay) * 1000));

// SELL: the trading wallet sends the tokens back and receives more SOL than it paid.
const sell = new Transaction().add(
  createAssociatedTokenAccountIdempotentInstruction(counterparty.publicKey, cpAta, counterparty.publicKey, mint),
  createTransferInstruction(ata, cpAta, trading.publicKey, amount),
  SystemProgram.transfer({ fromPubkey: counterparty.publicKey, toPubkey: trading.publicKey, lamports: sellLamports }),
);
const t0 = Date.now();
const sellSig = await sendAndConfirmTransaction(conn, sell, [counterparty, trading]);
console.log(`SELL  got  ${sol(sellLamports)} SOL   ${link(sellSig)}   (profit ${sol(sellLamports - buyLamports)}, the app should bank 50%)`);
console.log('sell confirmed at', new Date(t0).toISOString());
writeFileSync(new URL('../docs/spikes/devnet-e2e-output.json', import.meta.url), JSON.stringify({
  mint: mint.toBase58(), buy: buySig, sell: sellSig, buyLamports, sellLamports, sellConfirmedAt: new Date().toISOString(),
}, null, 2));

process.exit(0); // web3.js keeps a confirmation websocket open
