import Link from 'next/link';
import { Mark } from '@/components/Mark';
import { APK_URL, CLUSTER, VAULT_PROGRAM, solscan } from '@/lib/config';

export const metadata = { title: 'Judges: five minutes, no keys' };

export default function Judges() {
  return (
    <>
      <div className="wrap">
        <header className="top">
          <Link className="mark" href="/"><Mark /> Kept</Link>
          <nav><Link href="/proof">Proof</Link><Link href="/judges">Judges</Link></nav>
        </header>
      </div>
      <main className="wrap" style={{ padding: '56px 24px 96px' }}>
        <div className="narrow">
          <div className="label" style={{ marginBottom: 20 }}>Judges</div>
          <h1 style={{ fontSize: 'clamp(40px, 6.5vw, 76px)' }}>Five minutes. No keys.</h1>
          <p className="dim" style={{ margin: '24px 0 40px' }}>
            Everything below works without an account. The first step needs only a browser. The rest needs an Android phone or emulator.
          </p>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 40 }}>
            <a className="btn" href={APK_URL}>Download APK</a>
            <a className="btn ghost" href="https://github.com/jenzylove/kept">Source</a>
            <Link className="btn ghost" href="/proof">Live proof</Link>
          </div>

          <div className="steps">
            <div>
              <div>
                <h3>See the problem in your own numbers.</h3>
                <p>Open the <Link href="/" style={{ color: 'var(--brass)' }}>front page</Link>, paste any Solana wallet, press Read. You get what it won, what it gave back, and what would be in a vault if every win had been banked. Each win links to its transaction.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Check the chain, not our word.</h3>
                <p>The <Link href="/proof" style={{ color: 'var(--brass)' }}>proof page</Link> reads the vault program’s own events on {CLUSTER}. Every bank links to its transaction and to the sell that caused it. Program: <a className="mono" href={solscan('account', VAULT_PROGRAM)} target="_blank" rel="noreferrer">{VAULT_PROGRAM.slice(0, 4)}…{VAULT_PROGRAM.slice(-4)}</a>.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Install the app.</h3>
                <p>Download the APK, or find Kept in the Solana dApp Store. Any Android phone works. A Seeker is not required. You need a Mobile Wallet Adapter wallet such as Phantom or Solflare for the wallet that owns your vault.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Open a vault and watch it work.</h3>
                <p>Connect your wallet, let Kept make the trading wallet, pick your share and your wait, open the vault. Fund the trading wallet with a few cents, buy a token from the Trade tab, and sell it above what you paid. A receipt appears within seconds, with the transaction as proof.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Try to get the money out early.</h3>
                <p>Ask to take money out. The app shows a countdown. Cancel it, or wait. The program refuses an early withdrawal with a clear error, and a shorter wait only applies after the current one ends.</p>
              </div>
            </div>
          </div>
        </div>
      </main>
    </>
  );
}
