import Link from 'next/link';
import { Mark } from '@/components/Mark';
import { VAULT_PROGRAM } from '@/lib/config';
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

type Cluster = 'mainnet' | 'devnet';
const scan = (cluster: Cluster, kind: 'tx' | 'account', id: string) => `https://solscan.io/${kind}/${id}${cluster === 'mainnet' ? '' : '?cluster=devnet'}`;

function Rows({ banks, cluster }: { banks: BankedEvent[]; cluster: Cluster }) {
  return (
    <>
      {banks.map((b) => (
        <div className="stat" key={b.bankSignature} style={{ alignItems: 'baseline', flexWrap: 'wrap' }}>
          <span className="mono" style={{ color: 'var(--bone)' }}>
            <span className="brass">+{sol(b.amountLamports)} SOL</span>
            <span className="dim"> from a win of {sol(b.profitLamports, 3)}</span>
          </span>
          <span className="dim" style={{ fontSize: 14 }}>
            {ago(b.time)} · <a href={scan(cluster, 'tx', b.bankSignature)} target="_blank" rel="noreferrer">kept</a> · <a href={scan(cluster, 'tx', b.sellSignature)} target="_blank" rel="noreferrer">sale</a> · vault {short(b.vault)}
          </span>
        </div>
      ))}
    </>
  );
}

export default async function Proof() {
  const read = async (cluster: Cluster) => { try { return await recentBanks(cluster, 40); } catch { return null; } };
  const [main, test] = await Promise.all([read('mainnet'), read('devnet')]);
  const total = (main ?? []).reduce((s, b) => s + b.amountLamports, 0n);

  return (
    <>
      <div className="wrap">
        <header className="top">
          <Link className="mark" href="/"><Mark /> Kept</Link>
          <nav><Link href="/proof">Proof</Link><Link href="/judges">Judges</Link><Link href="/limits">Plain terms</Link></nav>
        </header>
      </div>
      <main className="wrap" style={{ padding: '56px 24px 96px' }}>
        <div className="label" style={{ marginBottom: 20 }}>Proof, live from Solana</div>
        <h1 style={{ fontSize: 'clamp(40px, 6.5vw, 76px)' }}>Every win kept, read from the <em>chain.</em></h1>
        <p className="dim" style={{ maxWidth: 640, margin: '24px 0 48px' }}>
          This page reads the vault program’s own records. Nothing here is typed in by hand. Each row links to the transfer into the vault and to the sale that caused it.
        </p>

        <h2 style={{ fontSize: 34 }}>On mainnet</h2>
        <div className="stat" style={{ marginTop: 12 }}><span>Program</span><a className="addr-text" href={scan('mainnet', 'account', VAULT_PROGRAM)} target="_blank" rel="noreferrer">{short(VAULT_PROGRAM)}</a></div>
        <div className="stat"><span>Wins kept</span><span className="mono">{main ? main.length : 'unknown'}</span></div>
        <div className="stat"><span>Kept across them</span><span className="mono brass">{sol(total)} SOL</span></div>
        {main === null && <p className="loss">Could not read mainnet right now. Try again in a moment.</p>}
        {main && main.length === 0 && (
          <p className="dim" style={{ marginTop: 18, maxWidth: 640 }}>
            No win has been kept on mainnet yet. The program is live and vaults are open, and the first winning sale from a Kept wallet will appear here by itself. Until then, the runs below are the same program on Solana’s test network.
          </p>
        )}
        {main && <Rows banks={main} cluster="mainnet" />}

        <h2 style={{ fontSize: 34, marginTop: 64 }}>Test runs on devnet</h2>
        <p className="dim" style={{ maxWidth: 640, margin: '10px 0 6px' }}>
          Real transactions on Solana’s test network, where the money has no value. Same program, same code on the phone.
        </p>
        {test === null && <p className="loss">Could not read devnet right now. Try again in a moment.</p>}
        {test && test.length === 0 && <p className="dim">None yet.</p>}
        {test && <Rows banks={test} cluster="devnet" />}
      </main>
    </>
  );
}
