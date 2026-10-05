import { NextRequest, NextResponse } from 'next/server';
import { rpcUrl } from '@/lib/config';
import { allowed, clientIp } from '@/lib/ratelimit';

// A thin, key-protecting proxy: the Android app and the web Mirror call this instead of holding an
// RPC key. It only forwards the methods Kept needs, so it can't be used as a general RPC for the
// whole API surface.
export const runtime = 'nodejs';
export const maxDuration = 30;

const ALLOW = new Set([
  'getSignaturesForAddress',
  'getTransaction',
  'getBalance',
  'getAccountInfo',
  'getMultipleAccounts',
  'getLatestBlockhash',
  'getSignatureStatuses',
  'getTokenAccountsByOwner',
  'getTokenAccountBalance',
  'getBlockTime',
  'sendTransaction',
  'simulateTransaction',
  'getMinimumBalanceForRentExemption',
]);

const MAX_BATCH = 20;
const MAX_BODY = 200_000; // sendTransaction is the biggest legitimate body (a few KB)

const RATE_PER_MIN = 240;

export async function POST(req: NextRequest) {
  if (!allowed(clientIp(req), RATE_PER_MIN)) return NextResponse.json({ error: { code: 429, message: 'Slow down' } }, { status: 429 });

  const text = await req.text();
  if (text.length > MAX_BODY) return NextResponse.json({ error: { code: 413, message: 'Too large' } }, { status: 413 });

  let body: unknown;
  try { body = JSON.parse(text); } catch { return NextResponse.json({ error: { code: 400, message: 'Bad JSON' } }, { status: 400 }); }
  const batch = Array.isArray(body) ? body : [body];
  if (batch.length === 0 || batch.length > MAX_BATCH) return NextResponse.json({ error: { code: 400, message: 'Bad batch' } }, { status: 400 });
  for (const r of batch) {
    const m = (r as { method?: unknown })?.method;
    if (typeof m !== 'string' || !ALLOW.has(m)) {
      return NextResponse.json({ error: { code: 403, message: `Method not allowed: ${String(m)}` } }, { status: 403 });
    }
  }

  const cluster = req.nextUrl.searchParams.get('c') === 'mainnet' ? 'mainnet' : 'devnet';
  const upstream = await fetch(rpcUrl(cluster), { method: 'POST', headers: { 'content-type': 'application/json' }, body: text });
  const out = await upstream.text();
  return new NextResponse(out, { status: upstream.status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
}
