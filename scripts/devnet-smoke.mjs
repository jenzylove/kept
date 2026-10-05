// Devnet smoke test: init vault, bank, request a withdrawal, try to execute it early (must fail).
// Keys live outside the repo. Usage: node scripts/devnet-smoke.mjs [step]
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import anchor from '@coral-xyz/anchor';
import { Connection, Keypair, PublicKey, SystemProgram } from '@solana/web3.js';

const { AnchorProvider, Program, BN, Wallet } = anchor;
const KEYS = 'C:/Users/LENOVO/ratchet-launch/keys';
const RPC = process.env.DEVNET_RPC || 'https://api.devnet.solana.com';
const idl = JSON.parse(readFileSync(new URL('../programs/kept_vault/idl.json', import.meta.url)));

function key(name) {
  mkdirSync(KEYS, { recursive: true });
  const p = `${KEYS}/${name}.json`;
  if (existsSync(p)) return Keypair.fromSecretKey(Uint8Array.from(JSON.parse(readFileSync(p))));
  const k = Keypair.generate();
  writeFileSync(p, JSON.stringify([...k.secretKey]));
  return k;
}

const owner = key('devnet-owner');
const banker = key('devnet-banker');
const conn = new Connection(RPC, 'confirmed');
const prog = (kp) => new Program(idl, new AnchorProvider(conn, new Wallet(kp), { commitment: 'confirmed' }));
const vault = PublicKey.findProgramAddressSync([Buffer.from('vault'), owner.publicKey.toBuffer()], new PublicKey(idl.address))[0];

if (process.argv[2] === 'keys') {
  console.log('owner ', owner.publicKey.toBase58());
  console.log('banker', banker.publicKey.toBase58());
  process.exit(0);
}

const link = (s) => `https://explorer.solana.com/tx/${s}?cluster=devnet`;
const out = {};

const ownerP = prog(owner);
const bankerP = prog(banker);

if (!(await conn.getAccountInfo(vault))) {
  out.init = await ownerP.methods.initVault(banker.publicKey, new BN(60), 5000).accounts({ owner: owner.publicKey }).rpc();
  console.log('init   ', link(out.init));
} else console.log('vault already exists', vault.toBase58());

out.bank = await bankerP.methods
  .bank(new BN(50_000_000), new BN(100_000_000), Array.from({ length: 64 }, (_, i) => i))
  .accounts({ banker: banker.publicKey, vault })
  .rpc();
console.log('bank   ', link(out.bank));

const v = await ownerP.account.vault.fetch(vault);
console.log('vault  ', vault.toBase58(), 'banked', v.totalBanked.toString(), 'count', v.bankCount.toString());

if (v.withdrawAmount.toString() === '0') {
  out.request = await ownerP.methods
    .requestWithdraw({ sol: {} }, new BN(10_000_000), owner.publicKey)
    .accounts({ owner: owner.publicKey, vault })
    .rpc();
  console.log('request', link(out.request));
}

try {
  out.early = await ownerP.methods.executeWithdrawSol().accounts({ vault, to: owner.publicKey }).rpc();
  console.log('UNEXPECTED: early withdraw succeeded', out.early);
  process.exitCode = 1;
} catch (e) {
  const logs = e.logs || e.transactionLogs || [];
  const line = logs.find((l) => l.includes('Error Code')) || String(e.message).slice(0, 200);
  out.earlyRefused = line;
  console.log('early withdraw refused:', line);
}
writeFileSync(new URL('../docs/spikes/devnet-smoke-output.json', import.meta.url), JSON.stringify({ vault: vault.toBase58(), ...out }, null, 2));
