// The signature picture: the wallet's profit (jagged, gives wins back) over the same trades as what
// was kept (a staircase that steps up on every win and never steps down). Values are cumulative SOL.
export function Staircase({ wallet, kept, height = 180 }: { wallet: number[]; kept: number[]; height?: number }) {
  const W = 800;
  const H = height;
  const n = Math.max(wallet.length, kept.length);
  if (n < 2) return null;
  const hi = Math.max(...wallet, ...kept, 0);
  const lo = Math.min(...wallet, 0);
  const span = hi - lo || 1;
  const x = (i: number) => (W * i) / (n - 1);
  const y = (v: number) => H - ((v - lo) / span) * H;

  const jag = wallet.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  let steps = '';
  kept.forEach((v, i) => {
    if (i === 0) steps = `M${x(0).toFixed(1)},${y(v).toFixed(1)}`;
    else steps += ` L${x(i).toFixed(1)},${y(kept[i - 1]).toFixed(1)} L${x(i).toFixed(1)},${y(v).toFixed(1)}`;
  });

  return (
    <svg className="stair" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label="Wallet profit over time against what was kept">
      <line x1="0" x2={W} y1={y(0)} y2={y(0)} stroke="var(--line)" strokeWidth="1" vectorEffect="non-scaling-stroke" />
      <path d={jag} fill="none" stroke="var(--loss)" strokeOpacity="0.85" strokeWidth="1.6" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
      <path className="kept" pathLength={1} d={steps} fill="none" stroke="var(--brass)" strokeWidth="2.6" strokeLinejoin="miter" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}
