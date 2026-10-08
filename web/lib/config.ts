// One place for what the site reads from the environment.
export const CLUSTER: 'mainnet' | 'devnet' = process.env.KEPT_CLUSTER === 'mainnet' ? 'mainnet' : 'devnet';
export const VAULT_PROGRAM = process.env.KEPT_PROGRAM ?? 'EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK';
export const APK_URL = process.env.NEXT_PUBLIC_APK_URL ?? 'https://github.com/jenzylove/kept/releases/latest/download/kept.apk';
export const SOLSCAN_SUFFIX = CLUSTER === 'mainnet' ? '' : '?cluster=devnet';

export function rpcUrl(cluster: 'mainnet' | 'devnet'): string {
  const key = process.env.HELIUS_API_KEY;
  if (!key) throw new Error('HELIUS_API_KEY is not set');
  return `https://${cluster === 'mainnet' ? 'mainnet' : 'devnet'}.helius-rpc.com/?api-key=${key}`;
}

export const solscan = (kind: 'tx' | 'account', id: string) => `https://solscan.io/${kind}/${id}${SOLSCAN_SUFFIX}`;
