import { VAULT_PROGRAM, rpcUrl } from './config';

type Cluster = 'mainnet' | 'devnet';
import { decodeBanked, type BankedEvent } from './decode';

export type { BankedEvent };

async function rpc<T>(cluster: Cluster, method: string, params: unknown[]): Promise<T> {
  const r = await fetch(rpcUrl(cluster), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
    next: { revalidate: 20 },
  });
  const j = await r.json();
  if (j.error) throw new Error(j.error.message);
  return j.result as T;
}

type Sig = { signature: string; slot: number; err: unknown; blockTime: number | null };
type Tx = { meta?: { logMessages?: string[]; err?: unknown } | null } | null;

/** The most recent bank events the program emitted, newest first. */
export async function recentBanks(cluster: Cluster, limit = 25): Promise<BankedEvent[]> {
  const sigs = await rpc<Sig[]>(cluster, 'getSignaturesForAddress', [VAULT_PROGRAM, { limit: 100, commitment: 'confirmed' }]);
  const ok = sigs.filter((s) => !s.err).slice(0, 60);
  const txs = await Promise.all(
    ok.map((s) => rpc<Tx>(cluster, 'getTransaction', [s.signature, { encoding: 'json', maxSupportedTransactionVersion: 1, commitment: 'confirmed' }]).catch(() => null)),
  );
  const out: BankedEvent[] = [];
  txs.forEach((tx, i) => {
    for (const line of tx?.meta?.logMessages ?? []) {
      if (!line.startsWith('Program data: ')) continue;
      const e = decodeBanked(line.slice('Program data: '.length));
      if (e) out.push({ ...e, bankSignature: ok[i].signature, slot: ok[i].slot, time: ok[i].blockTime });
    }
  });
  return out.slice(0, limit);
}
