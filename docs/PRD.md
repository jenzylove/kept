# Kept: product requirements

Hackathon: CLOCK IN (Solana Mobile × RadiantsDAO). Deadline **2026-10-12 23:59 UTC** (extended from Oct 9).
Status: locked 2026-10-04. Spike passed (see `docs/spikes/core-assumptions.md`).

## Changes since this was locked

Kept here so the record is honest about what moved.

- The program is written without a framework, not with Anchor, so that deploying it to mainnet cost 0.23 SOL instead of about 1 SOL.
- The Mirror grew from "what you won and gave back" into a check of any wallet: how often it wins, what it made overall, how much it gives back. SKR holders look back 90 days and keep a watch list.
- The monthly card and "connect yours" in the Mirror were not built.
- The mainnet proofs in section 9: the program is live, trading from a phone is proven, and a win was kept by itself on 2026-10-12. A refused early withdrawal and a staked SKR account have so far only been run on the test network.
- Not published to the dApp Store, which is only needed to claim a prize.

## 1. One line

A trading wallet with a reflex. Every time you sell for a profit, part of the win is banked automatically into a vault that is slow to leave, so wins pile up instead of being traded back to zero.

## 2. Reframe

Traders don't have a picking problem, they have a keeping problem. Every tool helps you pick. Nothing helps you keep.

## 3. Problem, with evidence

| Pain | Evidence |
|---|---|
| Most memecoin traders lose | 62.5% of pump.fun traders lost money; about 3% made more than $1,000 ([crypto.news](https://crypto.news/over-60-pump-fun-traders-saw-losses-less-than-0-01-made-over-1m/), [Cryptopolitan](https://www.cryptopolitan.com/3-percent-traders-on-pump-fun-made-over-1000/)) |
| Wins happen, then get given back | Spike: one active trader, last 250 transactions: 54 winning sells worth 5.26 SOL, 4.40 SOL lost on losing sells. Most of the wins were handed back. |
| The few who stay profitable sweep profits out | Analysis of profitable Solana memecoin wallets: they move realized profit out of the trading wallet regularly ([WalletFinder](https://www.walletfinder.ai/blog/solana-meme-coin-season-wallet-analysis)) |
| Take profit orders don't fix it | Take profit turns a coin into SOL; the SOL stays in the same wallet and funds the next buy |

## 4. User and front door

**User:** a Solana trader who trades on their phone (Seeker owners are mostly this person). Also anyone who has watched a good week vanish.

**First ten seconds:** the app opens on the **Mirror**. One field: paste any wallet, or connect yours. A few seconds later:

> Last 90 days: **you won 5.26 SOL** on 54 winning sells. You gave back 4.40 on losing ones.
> With Kept on, **2.63 SOL** would be sitting in your vault right now.

One button: **Start keeping.**

## 5. How it works

1. **Connect your main wallet** (Seed Vault on Seeker, or any MWA wallet). It owns the vault.
2. **Kept creates a trading wallet** on the phone. The key is generated on the device and encrypted with Android Keystore. It never leaves the phone and no server ever sees it. You fund it from your main wallet, then trade anywhere: in Kept (Jupiter), or import the key into Phantom, or use pump.fun.
3. **The reflex:** a foreground service watches the trading wallet. When a sell lands above its cost basis, the service signs a `bank` instruction itself and moves your chosen share of the profit (default 50%) into the vault. No tap. Proven at 5 slots (about 2 s) after the sell in the spike.
4. **The vault is an onchain program.** Money in is instant. Money out (to your main wallet or back to the trading wallet) waits out a cooldown you choose (default 24 h), and you can cancel during it. Lowering the cooldown only takes effect after the current cooldown, so it can't be skipped on tilt.
5. **The receipt:** a notification ("Banked 0.21 SOL from WIF, +0.42. Vault: 14.6 SOL"), an onchain `Banked` event per bank, and a monthly card: "Won 38. Kept 19."

## 6. SKR

- **Bank into staked SKR (SKR tier rule):** the reflex swaps the banked share to SKR and stakes it with the SKR staking program in the **vault's** name. Banked wins earn staking rewards while locked, and only the Kept program can unstake them, under the same cooldown.
- **Access tier:** holding 100 SKR in the main wallet unlocks a custom bank share and banking into staked SKR. Free: one trading wallet with a fixed 50% share into SOL. The tier is checked live from the SKR balance, in the app and again by the reflex at the moment it banks; the threshold is one constant, shown in the app.
- **Cut from the first plan, with the reason:** per coin rules and several trading wallets. Neither is needed to prove the core claim, and each would have taken time from verifying what the app already does against real chains. They are not claimed anywhere in the product.
- **Getting staked SKR out** is three steps: ask (the vault's wait), unstake (the vault tells the SKR program), then collect after SKR's own two day period and send to the owner in one signature.

## 7. Full scope (one build)

| Part | What ships |
|---|---|
| Program `kept_vault` (Anchor, mainnet) | `init_vault`, `bank`, `bank_skr_stake` bookkeeping, `request_withdraw`, `cancel_withdraw`, `execute_withdraw`, `set_rules` (with the delayed cooldown decrease), SKR `unstake` and `withdraw` through CPI signed by the vault PDA. `Banked` events. |
| Profit engine | Pure functions: parse a transaction into SOL and token deltas, keep average cost per mint, return realized profit per sell. Unit tests with shared test vectors from real transactions. Kotlin (device) and TypeScript (web), same vectors. |
| Android app (Kotlin, Jetpack Compose) | Mirror (bulk history through our proxy), onboarding (connect main wallet over MWA, create and fund the trading wallet, create the vault), reflex foreground service with local notifications, in-app swap (Jupiter), vault screen (balance, bank history, withdraw with cooldown, cancel, staked SKR and its three step exit), rules screen (share, bank as SOL or staked SKR, wait; tier gated), trading key export for Phantom behind the lock screen, settings. |
| Web (Next.js on Vercel) | Web Mirror for people without Android, a `/proof` page that lists every `Banked` event from the program live, an APK download, and the judge fast path. |
| Proof | Real mainnet banks from the burner and the demo wallets, linked from `/proof` and the README. |
| Distribution | Signed release APK, published to the Solana dApp Store. |

Nothing is descoped. Anything cut later is listed here with the reason.

## 8. Judging criteria (25% each)

| Criterion | How Kept meets it |
|---|---|
| Stickiness and PMF | It fires on every winning sell, and the vault number only goes up. Traders feel it at the moment they win. The monthly card brings them back. |
| User experience | Paste a wallet and see your own truth, then one setup. After that it runs on its own; the only touch is a notification. |
| Innovation and freshness | Keeping instead of picking. Self-custody auto-banking from a device-held key, plus an onchain cooldown on the way out. |
| Presentation and demo | The judge brings their own wallet to the Mirror. A live sell banks within seconds and shows on Solscan. |
| SKR bonus | Banked wins are staked as SKR in the vault's name, and SKR holders unlock the rule tiers. |

## 9. Claims and how each is proven

| Claim | Proof |
|---|---|
| Banks a winning sell within seconds, no tap | Mainnet bank transaction a few slots after the sell, linked on `/proof` |
| The trading key never leaves the phone | Code path: generated and encrypted on device; no network call carries it; README points to the file |
| Nobody can pull vault funds before the cooldown, including the owner | Program test: `execute_withdraw` before unlock fails; mainnet attempt that fails |
| Lowering the cooldown can't skip the wait | Program test |
| Mirror numbers are real | Computed from onchain history; each win links to its transaction |
| Staked SKR is owned by the vault | Mainnet stake account whose `user` is the vault PDA |

## 10. What Kept does not do

- No custody. Kept never holds a key; the vault is a program.
- It does not stop you moving money out of the trading wallet by hand. It is a commitment device. The vault (what you've banked) is what's protected.
- It does not pick trades, copy trade, or snipe.
- No bank on a sell it can't price (cost unknown, like a token received for free); those are shown, not banked.
- The reflex runs while the phone is on and the service is alive. If the phone is off, missed sells are caught up and banked when it comes back, and labelled as late.

## 11. Risks

| Risk | Status |
|---|---|
| Profit math on multi-token routes | Known: skipped and shown as "not priced" |
| Android kills the service | Known: foreground service plus catch-up on restart from history |
| Public RPC too slow for the Mirror | Known: needs an indexed history source (Helius key) |
| Program deploy cost on mainnet | Known: about 1.5 to 2.5 SOL rent, recoverable when the program is closed |
| SKR swap liquidity for small banks | Known: minimum bank size for SKR rule |

## 12. Sponsor checklist

Becomes `docs/SUBMISSION_CHECKLIST.md`, ticked only from the live app.

- Functional Android APK
- Public GitHub repo with source
- Demo video of the app in use
- Pitch deck
- Mobile Wallet Adapter used for the main wallet
- Works on Seeker and on any Android (emulator ok for judges)
- Published to the Solana dApp Store (required to claim a prize)
- SKR bonus: creative, meaningful SKR integration
