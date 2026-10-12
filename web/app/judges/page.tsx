import Link from 'next/link';
import { Mark } from '@/components/Mark';
import { APK_URL, VAULT_PROGRAM } from '@/lib/config';

export const metadata = { title: 'Judges: five minutes, no keys' };

export default function Judges() {
  return (
    <>
      <div className="wrap">
        <header className="top">
          <Link className="mark" href="/"><Mark /> Kept</Link>
          <nav><Link href="/proof">Proof</Link><Link href="/judges">Judges</Link><Link href="/limits">Plain terms</Link></nav>
        </header>
      </div>
      <main className="wrap" style={{ padding: '56px 24px 96px' }}>
        <div className="narrow">
          <div className="label" style={{ marginBottom: 20 }}>Judges</div>
          <h1 style={{ fontSize: 'clamp(40px, 6.5vw, 76px)' }}>Five minutes. <em>No keys.</em></h1>
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
                <h3>Check a wallet, yours or anyone’s.</h3>
                <p>Open the <Link href="/">front page</Link>, paste any Solana wallet, press Read. You get how many of its sales won, what it made overall, how much it gave back, and what would be in a vault with Kept on. Each win links to its transaction.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Check the chain, not our word.</h3>
                <p>The <Link href="/proof">proof page</Link> reads the vault program’s own records on mainnet and on the test network. Every win kept links to its transaction and to the sale that caused it. Program: <a className="mono" href={`https://solscan.io/account/${VAULT_PROGRAM}`} target="_blank" rel="noreferrer">{VAULT_PROGRAM.slice(0, 4)}…{VAULT_PROGRAM.slice(-4)}</a>.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Install the app.</h3>
                <p>Download the APK. Any Android phone works. A Seeker is not required. You need a Mobile Wallet Adapter wallet such as Phantom or Solflare for the wallet that owns your vault.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Open a vault and watch it work.</h3>
                <p>Connect your wallet, let Kept make the trading wallet, pick your share and your wait, open the vault. Fund the trading wallet with a few cents and buy a token from the Trade tab. This is real money on mainnet, so keep it small. When you later sell for more than you paid, a receipt appears within seconds, with the transaction as proof. That needs the price to rise, so it may not happen inside five minutes. The proof page shows runs where it did.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Getting money out takes a wait.</h3>
                <p>Once something is in the vault, ask to take it out. The app shows a countdown. Cancel it, or wait. The program refuses an early withdrawal, and a shorter wait only applies after the current one ends.</p>
              </div>
            </div>
          </div>
        </div>
      </main>
    </>
  );
}
