import { NextRequest, NextResponse } from 'next/server';
import { allowed, clientIp } from '@/lib/ratelimit';

// Bulk, pre parsed wallet history for the Mirror: up to 100 transactions per request instead of one
// request per transaction. The response is trimmed to what the profit engine reads.
export const runtime = 'nodejs';
export const maxDuration = 30;

const ADDRESS = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;
const SIGNATURE = /^[1-9A-HJ-NP-Za-km-z]{64,90}$/;

type Change = { userAccount?: string; mint: string; rawTokenAmount: { tokenAmount: string; decimals: number } };
type Account = { account: string; nativeBalanceChange: number; tokenBalanceChanges?: Change[] };
type Enhanced = { signature: string; slot: number; timestamp: number; fee: number; feePayer: string; transactionError: unknown; accountData?: Account[] };

export async function GET(req: NextRequest) {
  if (!allowed(clientIp(req), 60, 1)) return NextResponse.json({ error: 'Slow down' }, { status: 429 });
  const q = req.nextUrl.searchParams;
  const address = q.get('address') ?? '';
  const before = q.get('before') ?? undefined;
  const cluster = q.get('c') === 'mainnet' ? 'mainnet' : 'devnet';
  if (!ADDRESS.test(address)) return NextResponse.json({ error: 'Not a Solana address' }, { status: 400 });
  if (before && !SIGNATURE.test(before)) return NextResponse.json({ error: 'Bad cursor' }, { status: 400 });
  const key = process.env.HELIUS_API_KEY;
  if (!key) return NextResponse.json({ error: 'Not configured' }, { status: 500 });

  const host = cluster === 'mainnet' ? 'api.helius.xyz' : 'api-devnet.helius.xyz';
  const url = new URL(`https://${host}/v0/addresses/${address}/transactions`);
  url.searchParams.set('api-key', key);
  url.searchParams.set('limit', '100');
  url.searchParams.set('commitment', 'confirmed');
  if (before) url.searchParams.set('before', before);

  const upstream = await fetch(url, { cache: 'no-store' });
  if (upstream.status === 429) return NextResponse.json({ error: 'Busy, try again in a moment' }, { status: 429 });
  if (!upstream.ok) return NextResponse.json({ error: `Upstream ${upstream.status}` }, { status: 502 });
  const list = (await upstream.json()) as Enhanced[];

  const trimmed = list.map((h) => ({
    signature: h.signature,
    slot: h.slot,
    timestamp: h.timestamp,
    fee: h.fee,
    feePayer: h.feePayer,
    transactionError: h.transactionError ?? null,
    accountData: (h.accountData ?? [])
      .map((a) => ({
        account: a.account,
        nativeBalanceChange: a.account === address ? a.nativeBalanceChange : 0,
        tokenBalanceChanges: (a.tokenBalanceChanges ?? []).filter((t) => t.userAccount === address),
      }))
      .filter((a) => a.account === address || a.tokenBalanceChanges.length > 0),
  }));
  return NextResponse.json(trimmed, { headers: { 'cache-control': 'private, max-age=30' } });
}
