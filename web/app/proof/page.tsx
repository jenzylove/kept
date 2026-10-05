import Link from 'next/link';
import { Mark } from '@/components/Mark';
import { CLUSTER, VAULT_PROGRAM, solscan } from '@/lib/config';
import { recentBanks, type BankedEvent } from '@/lib/events';

export const revalidate = 20;
export const metadata = { title: 'Proof: every bank, read from the chain' };

const sol = (l: bigint, d = 4) => (Number(l) / 1e9).toFixed(d);
const short = (s: string) => `${s.slice(0, 4)}…${s.slice(-4)}`;
const ago = (t: number | null) => {
  if (!t) return '';
  const d = Math.max(0, Math.floor(Date.now() / 1000) - t);
  if (d < 60) return 'just now';
  if (d < 3600) return `${Math.floor(d / 60)} min ago`;
  if (d < 86400) return `${Math.floor(d / 3600)} h ago`;
  return `${Math.floor(d / 86400)} d ago`;
};

export default async function Proof() {
  let banks: BankedEvent[] = [];
  let failed = false;
  try { banks = await recentBanks(40); } catch { failed = true; }
  const total = banks.reduce((s, b) => s + b.amountLamports, 0n);

  return (
    <>
      <div className="wrap">
        <header className="top">
          <Link className="mark" href="/"><Mark /> Kept</Link>
          <nav><Link href="/proof">Proof</Link><Link href="/judges">Judges</Link></nav>
        </header>
      </div>
      <main className="wrap" style={{ padding: '56px 24px 96px' }}>
        <div className="label" style={{ marginBottom: 20 }}>Proof · {CLUSTER}</div>
        <h1 style={{ fontSize: 'clamp(40px, 6.5vw, 76px)' }}>Every bank, read from the chain.</h1>
        <p className="dim" style={{ maxWidth: 640, margin: '24px 0 48px' }}>
          This page reads the vault program’s own events. Nothing here is typed in by hand. Each row links to the bank on Solscan and to the sell that caused it.
        </p>

        <div className="stat"><span>Program</span><a className="mono" href={solscan('account', VAULT_PROGRAM)} target="_blank" rel="noreferrer">{short(VAULT_PROGRAM)}</a></div>
        <div className="stat"><span>Network</span><span className="mono">{CLUSTER}</span></div>
        <div className="stat"><span>Banks shown</span><span className="mono">{banks.length}</span></div>
        <div className="stat"><span>Banked across them</span><span className="mono brass">{sol(total)} SOL</span></div>

        <div className="label" style={{ margin: '48px 0 6px' }}>Latest banks</div>
        {failed && <p className="loss">Could not read the chain right now. Try again in a moment.</p>}
        {!failed && banks.length === 0 && <p className="dim">No banks yet on this network.</p>}
        {banks.map((b) => (
          <div className="stat" key={b.bankSignature} style={{ alignItems: 'baseline', flexWrap: 'wrap' }}>
            <span className="mono" style={{ color: 'var(--bone)' }}>
              <span className="brass">+{sol(b.amountLamports)} SOL</span>
              <span className="dim"> from a win of {sol(b.profitLamports, 3)}</span>
            </span>
            <span className="mono dim" style={{ fontSize: 14 }}>
              {ago(b.time)} · <a href={solscan('tx', b.bankSignature)} target="_blank" rel="noreferrer">bank</a> · <a href={solscan('tx', b.sellSignature)} target="_blank" rel="noreferrer">sell</a> · vault {short(b.vault)}
            </span>
          </div>
        ))}
      </main>
    </>
  );
}
