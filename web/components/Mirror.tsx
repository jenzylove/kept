'use client';

import { useState } from 'react';
// A copy of /engine/src/engine.js (the profit engine the Android app and the tests use). A test fails if it drifts.
import { mirrorEnhanced as runMirrorEnhanced, bankAmount } from '@/lib/engine.js';
import { Staircase } from './Staircase';
import { readWallet } from '@/lib/read';

type Win = { sig: string; mint: string; profit: bigint; time: number | null };

const sol = (l: bigint, d = 2) => (Number(l) / 1e9).toFixed(d);
const short = (s: string) => `${s.slice(0, 4)}…${s.slice(-4)}`;

export function Mirror({ initial = '' }: { initial?: string }) {
  const [address, setAddress] = useState(initial);
  const [state, setState] = useState<'idle' | 'running' | 'done' | 'error'>('idle');
  const [progress, setProgress] = useState<[number, number]>([0, 0]);
  const [error, setError] = useState('');
  const [result, setResult] = useState<null | {
    scanned: number; truncated: boolean; wins: Win[]; losses: Win[]; unpriced: number;
    won: bigint; givenBack: bigint; kept: bigint; wallet: number[]; keptSeries: number[];
  }>(null);

  async function run() {
    const a = address.trim();
    if (!/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(a)) { setError("That doesn't look like a Solana address."); setState('error'); return; }
    setState('running'); setError(''); setResult(null); setProgress([0, 0]);
    try {
      const cutoff = Math.floor(Date.now() / 1000) - 90 * 86400;
      const list: any[] = [];
      let before: string | undefined;
      let truncated = false;
      paging: for (let page = 0; page < 10; page++) {
        let batch: any[] = [];
        for (let attempt = 0; ; attempt++) {
          const r = await fetch(`/api/history?c=mainnet&address=${a}${before ? `&before=${before}` : ''}`);
          if (r.status === 429 && attempt < 5) { await new Promise((ok) => setTimeout(ok, 1200 * (attempt + 1))); continue; }
          const j = await r.json();
          if (!r.ok) throw new Error(j.error || 'Could not read that wallet.');
          batch = j; break;
        }
        if (!batch.length) break;
        for (const h of batch) {
          if (h.timestamp && h.timestamp < cutoff) break paging;
          list.push(h);
        }
        setProgress([list.length, list.length]);
        if (batch.length < 100) break;
        before = batch[batch.length - 1].signature;
        if (page === 9) truncated = true;
      }
      const m = runMirrorEnhanced(list, a, 5000) as unknown as {
        wins: Win[]; losses: Win[]; sells: Win[]; unpriced: number; won: bigint; givenBack: bigint; wouldHaveKept: bigint;
      };

      let pnl = 0n, kept = 0n;
      const wallet = [0], keptSeries = [0];
      for (const s of m.sells) {
        pnl += s.profit; kept += s.profit > 0n ? bankAmount(s.profit, 5000) : 0n;
        wallet.push(Number(pnl) / 1e9); keptSeries.push(Number(kept) / 1e9);
      }
      setResult({
        scanned: list.length, truncated, wins: m.wins, losses: m.losses, unpriced: m.unpriced,
        won: m.won, givenBack: m.givenBack, kept: m.wouldHaveKept, wallet, keptSeries,
      });
      setState('done');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not read that wallet.');
      setState('error');
    }
  }

  const empty = result && result.wins.length === 0 && result.losses.length === 0;
  const read = result ? readWallet(result, result.truncated) : null;

  return (
    <div>
      <div style={{ display: 'grid', gap: 12, gridTemplateColumns: 'minmax(0,1fr) auto', alignItems: 'start' }} className="mirror-row">
        <input className="addr" value={address} onChange={(e) => setAddress(e.target.value)} placeholder="Paste any Solana wallet address"
          spellCheck={false} autoCapitalize="off" autoCorrect="off" onKeyDown={(e) => e.key === 'Enter' && run()} aria-label="Wallet address" />
        <button className="btn" onClick={run} disabled={state === 'running' || !address.trim()}>
          {state === 'running' ? 'Reading…' : 'Read this wallet'}
        </button>
      </div>
      {state === 'running' && (
        <p className="dim" style={{ fontSize: 14, marginTop: 14 }}>
          {progress[0] ? `Read ${progress[0]} transactions so far…` : 'Finding trades…'}
        </p>
      )}
      {state === 'error' && <p className="loss" style={{ marginTop: 14 }}>{error}</p>}

      {state === 'done' && result && (
        <div style={{ marginTop: 48 }}>
          <div className="label">Last 90 days</div>
          {empty || !read ? (
            <>
              <h2 style={{ marginTop: 14 }}>No winning sells <em>found.</em></h2>
              <p className="dim">We read {result.scanned} transactions and found no sale we could price in the last 90 days.</p>
            </>
          ) : (
            <>
              <h2 style={{ marginTop: 14 }}>{read.verdict[0]}<em>{read.verdict[1]}</em></h2>
              {read.partial && (
                <p className="dim" style={{ marginTop: 10 }}>
                  {result.truncated
                    ? 'This wallet trades more than we could read, so this is its most recent stretch, not all 90 days.'
                    : `${read.unpricedPercent}% of its sales could not be priced, so treat this as a partial picture.`}
                </p>
              )}
              <div className="label" style={{ marginTop: 30 }}>{read.net >= 0n ? 'Made overall' : 'Lost overall'}</div>
              <div className={`big${read.net < 0n ? ' loss' : ''}`} style={{ marginTop: 6 }}>
                {read.net >= 0n ? '+' : '−'}{sol(read.net < 0n ? -read.net : read.net)} <span className="dim" style={{ fontSize: '0.28em', letterSpacing: 0 }}>SOL</span>
              </div>
              <div style={{ margin: '32px 0 10px' }}><Staircase wallet={result.wallet} kept={result.keptSeries} /></div>
              <p className="label" style={{ display: 'flex', gap: 22, flexWrap: 'wrap' }}>
                <span><i style={{ display: 'inline-block', width: 14, height: 4, borderRadius: 4, background: 'var(--loss)', marginRight: 8, verticalAlign: 'middle' }} />What the wallet made</span>
                <span><i style={{ display: 'inline-block', width: 14, height: 4, borderRadius: 4, background: 'var(--brass)', marginRight: 8, verticalAlign: 'middle' }} />If half of each win was kept</span>
              </p>
              <div style={{ marginTop: 28 }}>
                <div className="stat"><span>Sales that won</span><span className="mono">{result.wins.length} of {result.wins.length + result.losses.length} ({read.winPercent}%)</span></div>
                <div className="stat"><span>Won on those</span><span className="mono">+{sol(result.won)} SOL</span></div>
                <div className="stat"><span>Gave back on the rest</span><span className="mono loss">−{sol(result.givenBack)} SOL</span></div>
                {read.gaveBackPercent !== null && <div className="stat"><span>Share of winnings given back</span><span className={`mono${read.gaveBackPercent > 35 ? ' loss' : ''}`}>{read.gaveBackPercent}%</span></div>}
                <div className="stat"><span>A typical win</span><span className="mono">+{sol(read.typicalWin, 3)} SOL</span></div>
                {result.losses.length > 0 && <div className="stat"><span>A typical loss</span><span className="mono">−{sol(read.typicalLoss, 3)} SOL</span></div>}
                {result.losses.length > 0 && <div className="stat"><span>Biggest single loss</span><span className="mono">−{sol(read.biggestLoss, 3)} SOL</span></div>}
                {result.unpriced > 0 && <div className="stat"><span>Sales we could not price</span><span className="mono">{result.unpriced}</span></div>}
                <div className="stat"><span>In a vault today, with Kept on</span><span className="mono brass">{sol(result.kept)} SOL</span></div>
              </div>
              <p className="dim" style={{ fontSize: 14, marginTop: 14 }}>
                Worked out from {result.scanned} of this wallet’s real transactions. It is a record of what happened, not advice, and a good record does not promise the next trade.
              </p>
              <div className="label" style={{ margin: '28px 0 6px' }}>Biggest wins, with proof</div>
              {[...result.wins].sort((p, q) => (q.profit > p.profit ? 1 : -1)).slice(0, 5).map((w) => (
                <div className="stat" key={w.sig}>
                  <a className="addr-text" href={`https://solscan.io/tx/${w.sig}`} target="_blank" rel="noreferrer">{short(w.sig)}</a>
                  <span className="mono">+{sol(w.profit, 3)} SOL</span>
                </div>
              ))}
            </>
          )}
        </div>
      )}
      <style>{`@media (max-width: 640px) { .mirror-row { grid-template-columns: 1fr !important; } }`}</style>
    </div>
  );
}
