'use client';

import { useState } from 'react';
// A copy of /engine/src/engine.js (the profit engine the Android app and the tests use). A test fails if it drifts.
import { mirrorEnhanced as runMirrorEnhanced, bankAmount } from '@/lib/engine.js';
import { Staircase } from './Staircase';

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
        <p className="mono dim" style={{ fontSize: 14, marginTop: 14 }}>
          {progress[0] ? `Read ${progress[0]} transactions so far…` : 'Finding trades…'}
        </p>
      )}
      {state === 'error' && <p className="loss" style={{ marginTop: 14 }}>{error}</p>}

      {state === 'done' && result && (
        <div style={{ marginTop: 48 }}>
          <div className="label">Last 90 days</div>
          {empty ? (
            <>
              <h2 style={{ marginTop: 14 }}>No priced sells found.</h2>
              <p className="dim">We read {result.scanned} transactions and found no sell with a known cost in this window.</p>
            </>
          ) : (
            <>
              <div className="big mono" style={{ marginTop: 14, fontFamily: 'var(--display)' }}>{sol(result.won)} SOL</div>
              <p className="dim" style={{ marginTop: 8 }}>won on {result.wins.length} winning {result.wins.length === 1 ? 'sell' : 'sells'}</p>
              <div style={{ margin: '32px 0 10px' }}><Staircase wallet={result.wallet} kept={result.keptSeries} /></div>
              <p className="label" style={{ display: 'flex', gap: 22 }}>
                <span><i style={{ display: 'inline-block', width: 14, height: 3, background: 'var(--loss)', marginRight: 8, verticalAlign: 'middle' }} />Wallet profit</span>
                <span><i style={{ display: 'inline-block', width: 14, height: 3, background: 'var(--brass)', marginRight: 8, verticalAlign: 'middle' }} />Kept at 50%</span>
              </p>
              <div style={{ marginTop: 28 }}>
                <div className="stat"><span>Gave back on {result.losses.length} losing {result.losses.length === 1 ? 'sell' : 'sells'}</span><span className="mono loss">−{sol(result.givenBack)} SOL</span></div>
                <div className="stat"><span>In your vault, if Kept had been on</span><span className="mono brass">{sol(result.kept)} SOL</span></div>
                {result.unpriced > 0 && <div className="stat"><span>Sells with no known cost (not counted)</span><span className="mono">{result.unpriced}</span></div>}
              </div>
              <p className="dim" style={{ fontSize: 14, marginTop: 14 }}>
                Computed from this wallet’s real transactions over {result.scanned} reads. A plain sum: it ignores how banking would have changed your later trade sizes.
                {result.truncated && ' History was longer than we read, so older trades are not counted.'}
              </p>
              <div className="label" style={{ margin: '28px 0 6px' }}>Biggest wins, with proof</div>
              {[...result.wins].sort((p, q) => (q.profit > p.profit ? 1 : -1)).slice(0, 5).map((w) => (
                <div className="stat" key={w.sig}>
                  <a className="mono" href={`https://solscan.io/tx/${w.sig}`} target="_blank" rel="noreferrer">{short(w.sig)}</a>
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
