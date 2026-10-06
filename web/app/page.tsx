import Link from 'next/link';
import { Mirror } from '@/components/Mirror';
import { Mark } from '@/components/Mark';

export default function Home() {
  return (
    <>
      <div className="wrap">
        <header className="top">
          <Link className="mark" href="/"><Mark /> Kept</Link>
          <nav>
            <Link href="/proof">Proof</Link>
            <Link href="/judges">Judges</Link>
          </nav>
        </header>
      </div>

      <main>
        <section className="wrap" style={{ padding: '56px 24px 88px' }}>
          <div className="label" style={{ marginBottom: 28 }}>For Solana Seeker</div>
          <h1>You won more<br />than you kept.</h1>
          <p className="dim" style={{ fontSize: 20, maxWidth: 620, margin: '28px 0 56px' }}>
            Kept is a trading wallet with a reflex. Each time you sell for a profit, part of the win moves into a vault that is slow to open.
            Wins stop leaking back into the next trade.
          </p>
          <div className="narrow">
            <div className="label" style={{ marginBottom: 14 }}>Try it on any wallet. Yours, or one you are curious about.</div>
            <Mirror />
          </div>
        </section>

        <hr className="rule" />

        <section className="wrap" style={{ padding: '88px 24px' }}>
          <h2 style={{ maxWidth: 760 }}>Traders do not have a picking problem. They have a keeping problem.</h2>
          <div className="steps" style={{ marginTop: 56, maxWidth: 820 }}>
            <div>
              <div>
                <h3>Trade the way you already do.</h3>
                <p>Kept makes a trading wallet on your phone. Its key stays sealed by Android Keystore and never leaves unless you explicitly reveal it. Trade in Kept, or import the wallet into Phantom.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Sell above what you paid.</h3>
                <p>Kept watches the wallet. When a sell lands above its cost, the phone signs a transfer on its own and sends your share of the profit to your vault. About two seconds. No tap.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Getting it out takes a wait.</h3>
                <p>Money goes in instantly. To take it out you ask, then wait the time you chose. You can cancel any time. Shortening the wait only works after the current wait is over, so a bad moment cannot talk you out of it.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Every bank is a receipt.</h3>
                <p>Each bank is an event on chain, tied to the sell that caused it. <Link href="/proof" style={{ color: 'var(--brass)' }}>Read them all on the proof page.</Link></p>
              </div>
            </div>
          </div>
        </section>

        <hr className="rule" />

        <section className="wrap" style={{ padding: '88px 24px' }}>
          <div className="narrow">
            <div className="label" style={{ marginBottom: 18 }}>Built on Seeker</div>
            <h2>Your vault belongs to your Seed Vault.</h2>
            <p className="dim" style={{ marginTop: 20 }}>
              The vault is owned by your main wallet. On a Seeker, a double tap on the Seed Vault confirms anything that moves your money out. The trading key is sealed by Android Keystore. Kept’s bank instruction can only pay your vault, and the program rejects any other destination.
            </p>
          </div>
        </section>

        <hr className="rule" />

        <section className="wrap" style={{ padding: '88px 24px' }}>
          <div className="narrow">
            <div className="label" style={{ marginBottom: 18 }}>What Kept does not do</div>
            <p className="dim" style={{ fontSize: 18 }}>
              It does not stop you moving money out of the trading wallet by hand. That wallet is yours. The vault is the part that is protected.
              It does not pick trades and it does not promise profit. A win is banked only when the wallet sells above what it paid, and coins with no known cost are never banked.
            </p>
          </div>
        </section>
      </main>

      <div className="wrap">
        <footer>Built for the CLOCK IN Solana Mobile hackathon.</footer>
      </div>
    </>
  );
}
