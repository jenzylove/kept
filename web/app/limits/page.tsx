import Link from 'next/link';
import { Mark } from '@/components/Mark';

export const metadata = { title: 'What Kept does and does not do' };

export default function Limits() {
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
          <div className="label" style={{ marginBottom: 20 }}>Plain terms</div>
          <h1 style={{ fontSize: 'clamp(40px, 6.5vw, 76px)' }}>What Kept does, and what it <em>does not.</em></h1>
          <p className="dim" style={{ margin: '24px 0 40px' }}>
            Kept is a trading wallet that moves part of every winning sale into a vault you own. This page says exactly where that stops.
          </p>

          <div className="steps">
            <div>
              <div>
                <h3>It keeps part of each win.</h3>
                <p>When your trading wallet sells a token for more SOL than you paid for it, Kept moves your chosen share of the profit into your vault within seconds. On a loss, nothing moves.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>It makes money slow to leave.</h3>
                <p>Money goes into the vault at once. To take it out you ask, wait out the time you chose, then collect. You can cancel while you wait. You can lengthen the wait at any time. Shortening it only takes effect after the current wait has passed.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>Only your own wallet can take money out.</h3>
                <p>The vault belongs to the wallet you connected, such as Phantom or a Seeker&apos;s Seed Vault. The trading wallet on your phone can put money in. It can never take money out. Kept never sees the key to the wallet that owns the vault.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>It does not lock your trading wallet.</h3>
                <p>The trading wallet is yours. You can move money out of it by hand whenever you like, and Kept will not stop you. The vault is the protected part.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>It does not pick trades or promise profit.</h3>
                <p>Kept gives no advice and takes no position. It only acts after you sell, and only on the profit of that sale.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>It only counts what it saw you buy.</h3>
                <p>Profit is the sale price minus what you paid. If a token reached your trading wallet some other way, such as a transfer or an airdrop, Kept has no cost for it and will not count its sale as a win.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>It needs the app to be allowed to run.</h3>
                <p>The watching happens on your phone. If the phone stops the app, a win is kept when the app next wakes and is marked as found late. If you have already moved the SOL out of the trading wallet by then, there is nothing left to keep.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>The program can still be changed by its maker.</h3>
                <p>Kept is days old, so the vault program has not been frozen yet. One key, held by the builder, can replace it. That is a trust you are placing in the builder until the program is frozen, and it is the main reason to keep amounts small for now.</p>
              </div>
            </div>
            <div>
              <div>
                <h3>It is new software.</h3>
                <p>The vault program is open source and tested, but it has not had an outside audit. Use amounts you can afford to have at risk.</p>
              </div>
            </div>
          </div>
        </div>
      </main>
    </>
  );
}
