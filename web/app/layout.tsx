import type { Metadata, Viewport } from 'next';
import { Fraunces, JetBrains_Mono } from 'next/font/google';
import './globals.css';

const display = Fraunces({ subsets: ['latin'], variable: '--font-display', axes: ['opsz'], display: 'swap' });
const mono = JetBrains_Mono({ subsets: ['latin'], variable: '--font-mono', display: 'swap' });

export const metadata: Metadata = {
  title: 'Kept: keep what you win',
  description:
    'A trading wallet with a reflex. Every time you sell for a profit, part of the win is banked into a vault that is slow to leave. Built for Solana Seeker.',
  openGraph: { title: 'Kept: keep what you win', description: 'You won more than you kept. Kept fixes that.', type: 'website' },
};

export const viewport: Viewport = { themeColor: '#0e0f0c', colorScheme: 'dark light' };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${display.variable} ${mono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
