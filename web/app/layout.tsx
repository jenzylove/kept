import type { Metadata, Viewport } from 'next';
import { Geist, Geist_Mono, Instrument_Serif } from 'next/font/google';
import './globals.css';

const sans = Geist({ subsets: ['latin'], variable: '--font-sans', display: 'swap' });
const mono = Geist_Mono({ subsets: ['latin'], variable: '--font-mono', display: 'swap' });
const serif = Instrument_Serif({ subsets: ['latin'], weight: '400', style: 'italic', variable: '--font-serif', display: 'swap' });

export const metadata: Metadata = {
  title: 'Kept: keep what you win',
  description:
    'A trading wallet that saves part of every win. Each time you sell for more than you paid, a share of the profit moves into a vault that is slow to open. Built for Solana Seeker.',
  metadataBase: new URL('https://kept-seeker.vercel.app'),
  openGraph: {
    title: 'Kept: keep what you win',
    description: 'You won more than you kept. Kept fixes that.',
    type: 'website',
    images: [{ url: '/og.png', width: 1200, height: 630, alt: 'You won more than you kept.' }],
  },
  twitter: { card: 'summary_large_image', title: 'Kept: keep what you win', description: 'You won more than you kept. Kept fixes that.', images: ['/og.png'] },
};

export const viewport: Viewport = { themeColor: '#f6f7f9', colorScheme: 'light' };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${sans.variable} ${mono.variable} ${serif.variable}`}>
      <body>{children}</body>
    </html>
  );
}
