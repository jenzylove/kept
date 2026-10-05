// Dumps the real SKR staking program and the accounts a stake/unstake needs from mainnet,
// so the vault's SKR path is tested against the real program in the simulator.
// Usage: HELIUS_API_KEY=... node scripts/dump-skr-fixtures.mjs
import { mkdirSync, writeFileSync } from 'node:fs';

const RPC = `https://mainnet.helius-rpc.com/?api-key=${process.env.HELIUS_API_KEY}`;
const SKR = 'SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ';
const MINT = 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3';
const OUT = new URL('../programs/kept_vault/tests/fixtures/', import.meta.url);
mkdirSync(OUT, { recursive: true });

const rpc = async (method, params) => {
  const j = await (await fetch(RPC, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) })).json();
  if (j.error) throw new Error(JSON.stringify(j.error));
  return j.result;
};
const acct = async (addr) => (await rpc('getAccountInfo', [addr, { encoding: 'base64' }])).value;

// StakeConfig PDA and its fields
const ALPHA = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
function b58(bytes) {
  let n = 0n; for (const b of bytes) n = (n << 8n) | BigInt(b);
  let s = ''; while (n > 0n) { s = ALPHA[Number(n % 58n)] + s; n /= 58n; }
  for (const b of bytes) { if (b === 0) s = '1' + s; else break; }
  return s;
}
const cfgDisc = [238, 151, 43, 3, 11, 151, 63, 176];
const poolDisc = [133, 238, 255, 214, 215, 11, 189, 23];
const bs58 = (arr) => { let n = 0n; for (const b of arr) n = (n << 8n) | BigInt(b); let s = ''; while (n > 0n) { s = ALPHA[Number(n % 58n)] + s; n /= 58n; } return s; };

const cfgs = await rpc('getProgramAccounts', [SKR, { encoding: 'base64', filters: [{ memcmp: { offset: 0, bytes: bs58(cfgDisc) } }] }]);
if (cfgs.length !== 1) throw new Error(`expected 1 stake config, got ${cfgs.length}`);
const cfg = cfgs[0];
const cfgData = Buffer.from(cfg.account.data[0], 'base64');
// layout: disc(8) bump(1) authority(32) mint(32) stake_vault(32) min_stake(u64) cooldown(u64) ...
const stakeVault = b58(cfgData.subarray(8 + 1 + 32 + 32, 8 + 1 + 32 + 32 + 32));
const minStake = cfgData.readBigUInt64LE(8 + 1 + 32 + 32 + 32);
const cooldown = cfgData.readBigUInt64LE(8 + 1 + 32 + 32 + 32 + 8);

const pools = await rpc('getProgramAccounts', [SKR, { encoding: 'base64', filters: [{ memcmp: { offset: 0, bytes: bs58(poolDisc) } }] }]);
// Use the guardian pool with the most stake: the one real users pick.
pools.sort((a, b) => Buffer.from(b.account.data[0], 'base64').length - Buffer.from(a.account.data[0], 'base64').length);
const pool = pools[0];

const list = [
  { name: 'stake_config', pubkey: cfg.pubkey, ...cfg.account },
  { name: 'guardian_pool', pubkey: pool.pubkey, ...pool.account },
  { name: 'stake_vault', pubkey: stakeVault, ...(await acct(stakeVault)) },
  { name: 'mint', pubkey: MINT, ...(await acct(MINT)) },
];
const manifest = list.map((a) => ({ name: a.name, pubkey: a.pubkey, owner: a.owner, lamports: a.lamports, data: a.data[0] }));
writeFileSync(new URL('skr-accounts.json', OUT), JSON.stringify({ minStake: minStake.toString(), cooldownSeconds: cooldown.toString(), accounts: manifest }, null, 1));

// the program itself
const prog = await acct(SKR);
const progdata = b58(Buffer.from(prog.data[0], 'base64').subarray(4, 36));
const pd = await acct(progdata);
const so = Buffer.from(pd.data[0], 'base64').subarray(45); // 4 + 8 + 1 + 32 byte programdata header
writeFileSync(new URL('skr_staking.so', OUT), so);
console.log({ stakeVault, minStake: minStake.toString(), cooldownSeconds: cooldown.toString(), pools: pools.length, guardianPool: pool.pubkey, programBytes: so.length });
