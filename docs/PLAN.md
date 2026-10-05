# Kept: build plan

**Rule:** a box is ticked only when the proof in its row exists (a test name, a transaction link, a screenshot path, a URL). No proof, no tick. Re-read this file at every phase boundary. Anything that changes scope is written into `docs/PRD.md` first.

Deadline: **2026-10-09 06:59 UTC**. Target: submit by 2026-10-08 18:00 UTC (13 h buffer).

## Phase 0. Spike (Oct 4) ✅

| # | Task | Proof | Done |
|---|---|---|---|
| 0.1 | Cost basis and profit from real history | `docs/spikes/core-assumptions.md` §1 (113/113 sells priced) | [x] |
| 0.2 | Sell detection about 1 s | same, §2 (median 963 ms) | [x] |
| 0.3 | Reflex banks with no tap | bank tx `4T4ELre6...`, 5 slots after sell | [x] |
| 0.4 | SKR stake can belong to a PDA | same, §4 (onchain IDL) | [x] |

## Phase 1. Vault program (Oct 5)

| # | Task | Proof | Done |
|---|---|---|---|
| 1.1 | Anchor workspace `programs/kept_vault`, builds in WSL | `anchor build --arch v0` finished; program id `EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK`; IDL at `programs/kept_vault/idl.json` | [x] |
| 1.2 | `init_vault`, `bank` (banker signs, emits `Banked`) | tests `bank_moves_sol_into_the_vault_and_logs_it`, `init_rejects_a_cooldown_under_one_minute_and_a_bad_share` | [x] |
| 1.3 | `request_withdraw` / `cancel_withdraw` / `execute_withdraw_sol` with cooldown | tests `withdraw_before_unlock_fails_and_after_unlock_pays_the_requested_address`, `cancel_clears_a_pending_withdrawal`, `a_withdrawal_cannot_dig_into_the_rent_the_vault_needs` | [x] |
| 1.4 | Only owner can withdraw; banker can only deposit | tests `the_banker_and_strangers_cannot_withdraw`, `only_the_banker_can_bank`, `withdrawals_can_only_go_to_the_owner_or_the_trading_wallet`, `only_the_owner_can_change_rules` | [x] |
| 1.5 | `set_rules`: cooldown decrease delayed, increase instant | test `lowering_the_cooldown_cannot_skip_the_wait_and_raising_it_is_instant` | [x] |
| 1.6 | SKR: stake owned by vault PDA; `unstake`/`withdraw` CPI only after cooldown | test `skr_staked_in_the_vaults_name_only_comes_out_through_the_vaults_cooldown` (real mainnet SKR program + accounts in the simulator): trading wallet stakes 2 SKR into the vault's name; a direct unstake fails; unstake through the vault refused before cooldown, works after; SKR's own 2 day period; vault gets 1,999,999 of 2,000,000 back (SKR's share math rounds down 1 base unit); only the owner can release | [x] |
| 1.7 | Deploy to devnet first (mainnet last, after the app works) | [`EWSuJmQA...`](https://explorer.solana.com/address/EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK?cluster=devnet), deploy tx `5JwN31nB...` | [x] |
| 1.8 | Devnet: init vault, one bank, one failed early withdraw | [init](https://explorer.solana.com/tx/5rDnmHuerUJVkuHqwYH3HL2TskFcgQewCbknjQwUDkj4aNFMdYgBhiRDjppNu9gBxm5oVnmwdnBXnGjgoHSepnwt?cluster=devnet), [bank](https://explorer.solana.com/tx/Kszcag49DMrqBce58m8wqgZQQTg2VkpxyAknmr2nQisfT9ohzuWBdJDDb9wNzENbnsA115siu8VqTwfy5twJAq3?cluster=devnet), [request](https://explorer.solana.com/tx/61mhvKtxr3iJ8Uwu8DeY2RwSNQXRaNxg55aNR9ukfCiLuKW6UAJdd8TjgHXC97EgNsCq5HD2b8ha3KgiWFjtq8KA?cluster=devnet); early execute refused with `StillCooling` (6007), output in `docs/spikes/devnet-smoke-output.json` | [x] |

## Phase 2. Profit engine (Oct 5)

| # | Task | Proof | Done |
|---|---|---|---|
| 2.1 | Test vectors: 10+ real transactions with expected deltas from an independent parser (Helius) | `engine/vectors.json` (15 vectors: legacy and v0 transactions) | [x] |
| 2.2 | TypeScript engine passes vectors | `cd engine && npm test`: 7 pass, 0 fail | [x] |
| 2.3 | Kotlin engine passes the same vectors | `gradle :app:testDebugUnitTest`: `EngineTest` 8 of 8 on the shared `engine/vectors.json`, plus a check against `mirror-expected.json` produced by the TypeScript engine | [x] |
| 2.4 | One mirror win hand-checked against Solscan | note + link in BUILD_LOG | [ ] |

## Phase 3. Android app (Oct 5 to 7)

| # | Task | Proof | Done |
|---|---|---|---|
| 3.1 | Compose app shell, builds a debug APK, runs on emulator | 13 MB debug APK built; running on a Pixel 7 emulator (API 35); screenshots `ratchet-launch/shots/app-1-front.png`, `app-2-connect.png` | [x] |
| 3.2 | Mirror screen: paste wallet → real numbers, each win links to its tx | in the app on the emulator: the trading wallet read from devnet history shows the real 0.03 SOL win; `app-11-mirror.png`. A busy mainnet wallet is still to be read in the app (the web Mirror already reads one in 14 s) | [x] |
| 3.3 | Connect main wallet over MWA (emulator with a test wallet app) | Solana Mobile's reference wallet (`fakewallet`, tag v2.0.8) on the emulator: Kept launched it over a real MWA association, the wallet showed its authorize screen and returned its address `5gePUjsV...`; then real `sign_and_send` calls: a 0.1 SOL transfer to the trading wallet and the vault creation both signed by the wallet and sent to devnet; `app-mwa-1.png`, `app-mwa-5-send.png`, `app-mwa-7-vault.png`. Seed Vault on a Seeker is not tested (no device); a real phone wallet app is next | [x] |
| 3.4 | Trading wallet: generated on device, encrypted with Keystore, export for Phantom | created in the app (`6DM4VxoB...`), key sealed with an Android Keystore AES key (`TradingKeyTest` 5 of 5), reveal dialog returns the key (debug build skips the lock screen check); `app-3b-funded.png` | [x] |
| 3.5 | Create vault from the app (main wallet signs) | devnet, debug wallet: [InitVault](https://explorer.solana.com/tx/4xNvty53dxvfCQVEPcW2QanzMhsk6tjbGiMQMY5DJZUmSFQDz1UAyNnvwTe88bLSMDX1NsGQuoiUcaD5yRxbsxEB?cluster=devnet) sent by the app; `app-5-home.png`. Real Mobile Wallet Adapter wallet still to be tested | [x] |
| 3.6 | Fund trading wallet from main wallet | devnet: [0.1 SOL transfer](https://explorer.solana.com/tx/53fjrrUkpP9YoVm7YDHf2eWPNz7KhWFCzCaQofqy1W5kqQXtEn1S6sR5mueWHXQYNfHfBvMD5JUD5sBtKwbbj2im?cluster=devnet) sent from the app; `app-3b-funded.png` | [x] |
| 3.7 | Reflex foreground service: detects sell, banks profit share, local notification | devnet, emulator: real [buy](https://explorer.solana.com/tx/2ZaRzSeMH8ff9zHcp3vC9CiD3mmjQc6B5zxTmjcTDgZdnxMkXn3Z69FgQ7BzrtwuXhkhGLWvK2jfsc82m4yTkivf?cluster=devnet) then profitable [sell](https://explorer.solana.com/tx/2RBcok8YSjjy4w3ShwXZB7ntpaz4x5nm6cM6ZLrJLyJjCh3KvQ9rY5czSPCkb5nwF4fu5snbeQvPdxmcJAoNvZZj?cluster=devnet) on the trading wallet; the app banked 0.015 SOL (50% of 0.03 profit) with no tap: [bank](https://explorer.solana.com/tx/51c68eGZZkARzo2vDJjaFg3EKYHbHNV2a71D1ge5VVT4kibEGvi3sBuaXVB75e2o3xcU1WdpEzdT8bfh4nkm4ADn?cluster=devnet) landed 11 slots (about 2 s) after the sell; receipt `app-6-banked.png`. To repeat on mainnet in phase 7 | [x] |
| 3.8 | Catch-up after restart: missed sells banked and labelled late | test + log | [ ] |
| 3.9 | In-app swap (Jupiter) | buy + sell tx links from the app | [ ] |
| 3.10 | Vault screen: balance, bank history, withdraw request, countdown, cancel | on the emulator, devnet: vault 0.0165 SOL after the bank; request 0.005 SOL, countdown (`app-8-pending.png`), unlock, take out (vault 0.0115, `app-10-withdrawn.png`); a second request cancelled in the app | [x] |
| 3.11 | SKR tier: live SKR balance check, rules unlocked; bank into staked SKR | stake tx where user = vault PDA | [ ] |
| 3.12 | Monthly card (share image) | image file | [ ] |

## Phase 4. Web (Oct 6 to 7)

| # | Task | Proof | Done |
|---|---|---|---|
| 4.1 | Next.js site on Vercel, web Mirror | https://kept-seeker.vercel.app/ : a real busy wallet read in 14.3 s (123 wins, 12.77 SOL won, 28.05 SOL given back); screenshot `ratchet-launch/shots/web-mirror-dark.png` | [x] |
| 4.2 | `/proof`: live `Banked` events from the program | https://kept-seeker.vercel.app/proof shows the real devnet bank (0.05 SOL); decoder tested against that event (`web/test/decode.test.mjs`). Switches to mainnet with `KEPT_CLUSTER` after the mainnet deploy | [x] |
| 4.3 | APK download + judge fast path | live URL | [ ] |

## Phase 5. Design (Oct 7)

| # | Task | Proof | Done |
|---|---|---|---|
| 5.1 | Own visual identity, applied to app and web | screenshots dark + light, shown to user | [ ] |
| 5.2 | User approves look | user message | [ ] |

## Phase 6. Audit and approval (Oct 7 to 8)

| # | Task | Proof | Done |
|---|---|---|---|
| 6.1 | Release readiness audit on repo + live app | `docs/AUDIT.md` | [ ] |
| 6.2 | Real findings fixed with proof | per-finding test or tx | [ ] |
| 6.3 | Git history scanned for secrets | scan output | [ ] |
| 6.4 | User approves the live product | user message | [ ] |

## Phase 7. Ship (Oct 8)

| # | Task | Proof | Done |
|---|---|---|---|
| 7.1 | Signed release APK | file + sha256 | [ ] |
| 7.2 | Published to Solana dApp Store (submitted) | portal screenshot | [ ] |
| 7.3 | README (fast path, real vs not, proof links, scope and limits) | repo | [ ] |
| 7.4 | Demo video | file + link | [ ] |
| 7.5 | Pitch deck | link | [ ] |
| 7.6 | Repo public, metadata, license | URL | [ ] |
| 7.7 | `docs/SUBMISSION_CHECKLIST.md` all ticked from live app | file | [ ] |
| 7.8 | Submitted on the CLOCK IN portal | confirmation screenshot | [ ] |

## Risks

| Risk | Type |
|---|---|
| Profit math edge cases | known, vectors cover them |
| Android kills the service | known, foreground service + catch-up |
| Mirror speed on public RPC | known, needs Helius key |
| MWA on emulator (no Seed Vault) | known, use a test wallet app; Seeker for the video if available |
| Mainnet deploy cost (about 1.5 to 2.5 SOL rent, recoverable) | known, needs funds |
| dApp Store review time | known, submit early on Oct 8 |
