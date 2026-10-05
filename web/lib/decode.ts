import { createHash } from 'node:crypto';

// Decodes the program's own `Banked` events, so /proof shows what the chain says, not what we say.

const BANKED_DISC = createHash('sha256').update('event:Banked').digest().subarray(0, 8);
const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

export function base58(bytes: Uint8Array): string {
  let n = 0n;
  for (const b of bytes) n = (n << 8n) | BigInt(b);
  let s = '';
  while (n > 0n) { s = ALPHABET[Number(n % 58n)] + s; n /= 58n; }
  for (const b of bytes) { if (b === 0) s = '1' + s; else break; }
  return s;
}

export type BankedEvent = {
  vault: string;
  amountLamports: bigint;
  profitLamports: bigint;
  sellSignature: string;
  totalBanked: bigint;
  bankCount: bigint;
  bankSignature: string;
  slot: number;
  time: number | null;
};

/** Decode one `Program data:` log line into a Banked event, or null if it's another event. */
export function decodeBanked(b64: string): Omit<BankedEvent, 'bankSignature' | 'slot' | 'time'> | null {
  const buf = Buffer.from(b64, 'base64');
  if (buf.length < 8 + 32 + 8 + 8 + 64 + 8 + 8) return null;
  if (!buf.subarray(0, 8).equals(BANKED_DISC)) return null;
  let o = 8;
  const vault = base58(buf.subarray(o, o + 32)); o += 32;
  const amountLamports = buf.readBigUInt64LE(o); o += 8;
  const profitLamports = buf.readBigUInt64LE(o); o += 8;
  const sellSignature = base58(buf.subarray(o, o + 64)); o += 64;
  const totalBanked = buf.readBigUInt64LE(o); o += 8;
  const bankCount = buf.readBigUInt64LE(o);
  return { vault, amountLamports, profitLamports, sellSignature, totalBanked, bankCount };
}

