// Kept profit engine. Pure functions, no network.
// Amounts are integers: lamports for SOL, raw base units for tokens (BigInt).

export const WSOL = 'So11111111111111111111111111111111111111112';

const big = (v) => BigInt(v ?? 0);

/**
 * What one transaction did to `owner`'s balances.
 * Returns { sol, tokens: { [mint]: { raw, decimals } } } where `sol` excludes the network fee
 * (the fee is a cost of using Solana, not part of a trade's price) and wrapped SOL counts as SOL.
 */
export function deltas(tx, owner) {
  const keys = tx.transaction.message.accountKeys.map((k) => (typeof k === 'string' ? k : k.pubkey));
  const i = keys.indexOf(owner);
  let sol = i >= 0 ? big(tx.meta.postBalances[i]) - big(tx.meta.preBalances[i]) : 0n;
  if (i === 0) sol += big(tx.meta.fee);

  const tokens = {};
  const add = (b, sign) => {
    if (b.owner !== owner) return;
    const t = (tokens[b.mint] ||= { raw: 0n, decimals: b.uiTokenAmount.decimals });
    t.raw += sign * big(b.uiTokenAmount.amount);
  };
  (tx.meta.preTokenBalances || []).forEach((b) => add(b, -1n));
  (tx.meta.postTokenBalances || []).forEach((b) => add(b, 1n));

  if (tokens[WSOL]) {
    sol += tokens[WSOL].raw;
    delete tokens[WSOL];
  }
  for (const m of Object.keys(tokens)) if (tokens[m].raw === 0n) delete tokens[m];
  return { sol, tokens };
}

/**
 * Classify a transaction for the trading wallet.
 * buy: SOL out, exactly one token in. sell: exactly one token out, SOL in.
 * Anything else (transfers, multi-token routes, airdrops) is 'other' and never banked.
 */
export function classify(d) {
  const mints = Object.keys(d.tokens);
  if (mints.length !== 1) return { kind: 'other' };
  const mint = mints[0];
  const raw = d.tokens[mint].raw;
  if (raw > 0n && d.sol < 0n) return { kind: 'buy', mint, raw, lamports: -d.sol };
  if (raw < 0n && d.sol > 0n) return { kind: 'sell', mint, raw: -raw, lamports: d.sol };
  return { kind: 'other' };
}

/** Average-cost ledger. `positions` is { [mint]: { raw, cost } } and is not mutated. */
export function apply(positions, c) {
  const next = { ...positions };
  if (c.kind === 'buy') {
    const p = next[c.mint] || { raw: 0n, cost: 0n };
    next[c.mint] = { raw: p.raw + c.raw, cost: p.cost + c.lamports };
    return { positions: next, event: { kind: 'buy', mint: c.mint, raw: c.raw, lamports: c.lamports } };
  }
  if (c.kind === 'sell') {
    const p = next[c.mint];
    if (!p || p.raw <= 0n) {
      // Sold something we never saw bought (airdrop, transfer in): no basis, never banked.
      return { positions: next, event: { kind: 'sell', mint: c.mint, raw: c.raw, proceeds: c.lamports, basis: null, profit: null } };
    }
    const soldRaw = c.raw > p.raw ? p.raw : c.raw;
    const basis = (p.cost * soldRaw) / p.raw;
    next[c.mint] = { raw: p.raw - soldRaw, cost: p.cost - basis };
    return {
      positions: next,
      event: { kind: 'sell', mint: c.mint, raw: c.raw, proceeds: c.lamports, basis, profit: c.lamports - basis },
    };
  }
  return { positions: next, event: { kind: 'other' } };
}

/** How much of a profit to bank, in lamports. `bps` is basis points (5000 = 50%). */
export function bankAmount(profit, bps) {
  if (profit === null || profit <= 0n) return 0n;
  return (profit * BigInt(bps)) / 10000n;
}

/**
 * Mirror: replay a wallet's history (oldest first) and summarise.
 * keptAtBps: what the vault would hold if Kept had banked that share of every win
 * (a plain sum; it ignores how banking would have changed later trade sizes).
 */
export function mirror(txs, owner, keptAtBps = 5000) {
  let positions = {};
  const wins = [];
  const losses = [];
  let unpriced = 0;
  let swaps = 0;
  for (const tx of txs) {
    if (!tx || !tx.meta || tx.meta.err) continue;
    const c = classify(deltas(tx, owner));
    if (c.kind === 'other') continue;
    swaps++;
    const r = apply(positions, c);
    positions = r.positions;
    const e = r.event;
    if (e.kind !== 'sell') continue;
    const sig = tx.transaction.signatures[0];
    if (e.profit === null) unpriced++;
    else if (e.profit > 0n) wins.push({ sig, mint: e.mint, profit: e.profit, time: tx.blockTime });
    else losses.push({ sig, mint: e.mint, profit: e.profit, time: tx.blockTime });
  }
  const sum = (a) => a.reduce((s, x) => s + x.profit, 0n);
  const won = sum(wins);
  return {
    swaps,
    sells: wins.length + losses.length + unpriced,
    wins,
    losses,
    unpriced,
    won,
    givenBack: -sum(losses),
    wouldHaveKept: wins.reduce((s, w) => s + bankAmount(w.profit, keptAtBps), 0n),
  };
}
