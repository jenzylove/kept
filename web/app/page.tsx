import Link from 'next/link';
import { Mirror } from '@/components/Mirror';
import { Mark } from '@/components/Mark';
import { GlassStairs } from '@/components/GlassStairs';
import { APK_URL } from '@/lib/config';

export default function Home() {
  return (
    <>
      <div className="wrap">
        <header className="top">
          <Link className="mark" href="/"><Mark /> Kept</Link>
          <nav>
            <Link href="/proof">Proof</Link>
            <Link href="/judges">Judges</Link>
            <Link href="/limits">Plain terms</Link>
          </nav>
        </header>
      </div>

      <main>
        <section className="wrap" style={{ padding: '56px 24px 0' }}>
          <div className="label" style={{ marginBottom: 22 }}>A trading wallet for Solana Seeker and any Android phone</div>
          <h1>You won more<br />than you <em>kept.</em></h1>
          <p className="dim" style={{ fontSize: 20, maxWidth: 600, margin: '26px 0 34px' }}>
            Kept is a trading wallet that saves part of every win for you. Each time you sell for more than you paid, a share of the profit moves into a vault that is slow to open.
          </p>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <a className="btn" href={APK_URL}>Get the app</a>
            <a className="btn ghost" href="#mirror">Check any wallet</a>
          </div>
        </section>

        <div className="bleed" style={{ marginTop: -10 }}>
          <GlassStairs lit={30} total={30} height={400} />
        </div>

        <section className="wrap" id="mirror" style={{ padding: '24px 24px 96px' }}>
          <div className="narrow">
            <h2>Check a wallet before you <em>follow it.</em></h2>
            <p className="dim" style={{ margin: '16px 0 26px' }}>
              Paste any Solana wallet: a caller, a dev, a friend, or your own. Kept reads its real trades from the last 90 days and shows how often it wins, how much it gives back, and what would be sitting in a vault today.
            </p>
            <Mirror />
          </div>
        </section>

        <hr className="rule" />

        <section className="wrap rise" style={{ padding: '96px 24px' }}>
          <h2 style={{ maxWidth: 780 }}>Traders do not have a picking problem. They have a <em>keeping</em> problem.</h2>
          <div className="steps" style={{ marginTop: 56, maxWidth: 820 }}>
            <div>
              <div>
                <h3>Trade the way you already do.</h3>
                <p>Kept makes a trading wallet on your phone. Its key is locked inside the phone and never leaves unless you choose to reveal it. Trade in Kept or in any other app.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Sell for more than you paid.</h3>
                <p>Kept watches the wallet. When a sale lands above what you paid, the phone sends your share of the profit to your vault by itself. It takes about two seconds and needs no tap.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Getting it out takes a wait.</h3>
                <p>Money goes in at once. To take it out you ask, then wait the time you chose. You can cancel while you wait. Shortening the wait only works after the current wait is over, so a weak moment cannot undo it.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Every win kept is a receipt.</h3>
                <p>Each one is recorded on Solana, tied to the sale that caused it. <Link href="/proof">Read them all on the proof page.</Link></p>
              </div>
            </div>
          </div>
        </section>

        <hr className="rule" />

        <section className="wrap rise" style={{ padding: '96px 24px' }}>
          <div className="narrow">
            <div className="label" style={{ marginBottom: 16 }}>Built on Seeker</div>
            <h2>Your vault belongs to your <em>Seed Vault.</em></h2>
            <p className="dim" style={{ marginTop: 20 }}>
              The vault is owned by your own wallet. On a Seeker, a double tap on the Seed Vault confirms anything that moves your money out. The trading wallet on the phone can put money into your vault and nothing else. It can never take money out.
            </p>
            <p className="dim" style={{ marginTop: 14 }}>
              Hold 100 SKR and you can choose your own share to keep, or have each win turned into staked SKR that earns while it waits.
            </p>
            <p style={{ marginTop: 26 }}><Link className="link" href="/limits">What Kept does and does not do, in plain terms</Link></p>
          </div>
        </section>
      </main>

      <div className="wrap">
        <hr className="rule" />
        <footer>
          <span>Built for the CLOCK IN Solana Mobile hackathon.</span>
          <span style={{ display: 'flex', gap: 20 }}>
            <a href="https://github.com/jenzylove/kept">Source</a>
            <Link href="/proof">Proof</Link>
            <Link href="/limits">Plain terms</Link>
          </span>
        </footer>
      </div>
    </>
  );
}
