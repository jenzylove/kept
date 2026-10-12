# Kept: release readiness audit

**Audited:** 2026-10-12 00:30 UTC, at commit `08eab4c` on `main`, against the CLOCK IN deadline of 2026-10-12 23:59 UTC.
**Live product:** https://kept-seeker.vercel.app. **Program:** `EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK` on Solana mainnet.
This replaces the audit of 2026-10-06, which predates the program rewrite, the mainnet deploy and the redesign.

## Decision

**Ship, with caveats that are stated in the product.** Every hard submission gate that the builder controls is met: a signed APK that downloads, a public repository, a deck, and a site whose pages all load. The core promise, keeping part of a win with no tap, is proven on mainnet: on 2026-10-12 a real winning sale ([sale](https://solscan.io/tx/NFymeFebkenoToV9b3RaYwPFpkPZ4H6DsaMVYTv3wFN9tR8Aab7qveyEyALzoL31N4vsnrxAQuzfPYXw2MekBbg)) was followed 9 seconds later by the phone moving half the profit into the vault ([kept](https://solscan.io/tx/4wyr4QuEoPZeSCzdHerGCeQw2fFrUqdALPMZZ8d9sKDNzzW85iNAew2e1ch2Yv4BcGRi5MBf5nkuq3mf23erCLR5)).

## What was checked, and the result

| Check | Result |
|---|---|
| Profit engine tests | 10 of 10 pass |
| Site tests, type check, dependency audit | 5 of 5 pass, no known vulnerabilities |
| Android unit tests | 128 pass, 0 fail |
| Vault program tests | 27 pass (22 behaviour and attack, 5 against the real SKR staking program) |
| Program on mainnet matches the tested build | Yes. Both hash to `403605ad…6028a`, 44,992 bytes |
| Live pages: `/`, `/proof`, `/judges`, `/limits`, `/deck.pdf`, asset links file | All answer 200 |
| The APK on the release is the build that was tested | Yes, same hash as the local signed build |
| The RPC proxy refuses methods outside its list | Yes (`getProgramAccounts` is refused) |
| Secrets in the full history (API key, pasted keys, key arrays, key files) | None found |

## Claims against evidence

| Claim | Mechanism | Evidence | Limit |
|---|---|---|---|
| Keeps part of a win within seconds, no tap | A foreground service signs `bank` with the key on the phone | One on mainnet from a real phone ([kept](https://solscan.io/tx/4wyr4QuEoPZeSCzdHerGCeQw2fFrUqdALPMZZ8d9sKDNzzW85iNAew2e1ch2Yv4BcGRi5MBf5nkuq3mf23erCLR5), 9 seconds after the [sale](https://solscan.io/tx/NFymeFebkenoToV9b3RaYwPFpkPZ4H6DsaMVYTv3wFN9tR8Aab7qveyEyALzoL31N4vsnrxAQuzfPYXw2MekBbg)), and 14 on devnet | One mainnet run so far |
| Only the owner can take money out, after the wait | The program checks the owner signature and the unlock time | Program tests, including attacks; a devnet run | Not yet run on mainnet |
| Swaps are checked before signing | `SwapValidator` parses each transaction and refuses anything but a plain swap | Unit tests on real and hostile transactions; real buys and sells on mainnet from a phone | It cannot see inside the swap program; the simulation before sending covers that |
| The wallet check is real numbers | Computed from the transactions of the wallet itself | Each win links to its transaction; the same engine is tested against an independent parser | A plain sum. Busy wallets are read only for their most recent stretch, and the page says so |
| Works with the Seed Vault on a Seeker | Standard Mobile Wallet Adapter | Proven with Phantom on a Samsung phone | Not tested on a Seeker |

## Findings

**High: the program can be changed by one key, and nothing said so.** The mainnet program is upgradeable and its authority is a key the builder holds. Whoever holds it could replace the program. The product described the vault as protected without naming this trust. *Fixed in this pass by saying it plainly* on the plain terms page, in the README and in SECURITY.md. Freezing the program is the real fix and is left for after an outside review, because it cannot be undone.

**Medium: the judges page promised a kept win in five minutes.** A win needs the price to rise, which nobody controls. *Fixed:* the page now says what can be done in five minutes and points to the proof page for runs where a win was kept.

**Medium: token pictures were loaded from whoever made the token.** Searching a token made the phone fetch a picture from an address the maker of the token chose, which tells that host a phone looked. *Fixed:* pictures load only for verified tokens.

**Low: the reveal key dialog could be captured by a screenshot.** *Fixed:* while the key is on screen the phone refuses screenshots and screen recording.

**Low: the RPC proxy could be used by anyone to send transactions at the read rate.** *Fixed:* sending and simulating now cost far more of a caller's allowance, about 30 a minute. The proxy is still public, because the app has to reach it.

**Low: no automated test run on push.** *Fixed:* one runs for the engine, the site and the app.

## Requirements

| Requirement | Status |
|---|---|
| Functional Android APK | Met. Signed, downloadable, run on a real phone on mainnet |
| Public repository | Met |
| Pitch deck | Met |
| Demo video | Made. To be uploaded by the owner |
| Mobile Wallet Adapter | Met, proven with Phantom |
| Meaningful SKR use | Built and tested against the real staking program in the simulator. Not yet run on mainnet |
| dApp Store | Not published. Needed only to claim a prize |
| Submitted on Align | Action for the owner |

## Not verified

Nothing here is a security audit. Not checked: the Seed Vault on a Seeker, staked SKR on mainnet, a withdrawal on mainnet, behaviour after a phone restart on mainnet, and the demo film at playing speed (it was checked by stills and audio levels).
