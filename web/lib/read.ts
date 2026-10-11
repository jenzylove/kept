// A plain read of a wallet's trading record, for deciding whether it is worth following. Every figure
// comes straight from the wallet's priced sales; there is no score. Mirrors WalletRead.kt in the app.
export type Sale = { profit: bigint };
export const LEAKY_PERCENT = 35;
export const PARTIAL_PERCENT = 25;

const VERDICTS = {
  keepsMost: ['Wins, and ', 'keeps most of it.'],
  aheadButLeaky: ['Ahead overall, but ', 'gives a lot back.'],
  givesItBack: ['Wins, then ', 'gives it all back.'],
  noWins: ['No winning sale ', 'in this window.'],
} as const;

const median = (v: bigint[]) => {
  if (!v.length) return 0n;
  const s = [...v].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2n;
};
const abs = (x: bigint) => (x < 0n ? -x : x);

export function readWallet(m: { wins: Sale[]; losses: Sale[]; unpriced: number; won: bigint; givenBack: bigint }, truncated: boolean) {
  const priced = m.wins.length + m.losses.length;
  const net = m.won - m.givenBack;
  const gaveBackPercent = m.won > 0n ? Number((m.givenBack * 100n) / m.won) : null;
  const unpricedPercent = priced + m.unpriced > 0 ? Math.floor((m.unpriced * 100) / (priced + m.unpriced)) : 0;
  const verdict =
    m.wins.length === 0 ? VERDICTS.noWins
    : net <= 0n ? VERDICTS.givesItBack
    : gaveBackPercent !== null && gaveBackPercent > LEAKY_PERCENT ? VERDICTS.aheadButLeaky
    : VERDICTS.keepsMost;
  return {
    net,
    winPercent: priced ? Math.floor((m.wins.length * 100) / priced) : 0,
    gaveBackPercent,
    typicalWin: median(m.wins.map((w) => w.profit)),
    typicalLoss: median(m.losses.map((l) => abs(l.profit))),
    biggestLoss: m.losses.reduce((b, l) => (abs(l.profit) > b ? abs(l.profit) : b), 0n),
    unpricedPercent,
    verdict,
    partial: truncated || unpricedPercent > PARTIAL_PERCENT,
  };
}
