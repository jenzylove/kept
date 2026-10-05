// Per instance token bucket, shared by the API routes. Serverless instances each keep their own,
// which is fine for abuse control; it is not a billing guarantee.
const buckets = new Map<string, { tokens: number; at: number }>();

export function allowed(ip: string, perMinute: number, cost = 1): boolean {
  const now = Date.now();
  const b = buckets.get(ip) ?? { tokens: perMinute, at: now };
  b.tokens = Math.min(perMinute, b.tokens + ((now - b.at) / 60_000) * perMinute);
  b.at = now;
  if (b.tokens < cost) { buckets.set(ip, b); return false; }
  b.tokens -= cost;
  buckets.set(ip, b);
  if (buckets.size > 5_000) for (const k of buckets.keys()) { buckets.delete(k); if (buckets.size < 2_500) break; }
  return true;
}

export const clientIp = (req: Request) => req.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ?? 'unknown';
