# Spike: Kept core assumptions

Date: 2026-10-04. Network: Solana mainnet. Scripts lived outside the repo (throwaway).

## Question

Can Kept (1) work out cost basis and profit from real wallet history, (2) see a sell within about a second, (3) bank part of the proceeds from a key held on the device with no human tap, and (4) stake banked SKR in the vault's name?

## 1. Cost basis and profit from history: PASS

- Ran the mirror over the last 250 transactions of an active pump.fun trader (`GUQPSxhbssHyiACNRD6aKJhXUAmDGViuhwcyXvRr7Jvw`) on the public RPC.
- Output: 241 single-token swaps, 113 sells, 113 of 113 matched to a cost basis, 54 profitable. Won 5.2644 SOL on winning sells, lost 4.4025 SOL on losing sells.
- Finding: mainnet carries version 1 transactions now. `@solana/web3.js` v1 `getParsedTransaction` throws on them. Use raw `getTransaction` with `maxSupportedTransactionVersion: 1` and `jsonParsed`.
- Limit: transactions that move two non-SOL tokens at once are skipped (about 4% here).
- Limit: the free public RPC took several minutes for 250 transactions. The app needs an indexed history source to make the mirror feel instant.

## 2. Sell detection: PASS

- `logsSubscribe` (mentions, confirmed) on the public websocket, against a busy program: 104 events, median lag about 963 ms from block time (block time has 1 second resolution).

## 3. The reflex: PASS

- Watcher subscribed to the burner trading wallet `BZGKFmveLa8XSAjSAdb6u5EWZV7gKLWLekm8jUEVaZp8`, key held locally.
- Jupiter round trip SOL → USDC → SOL for 0.01 SOL.
- Sell: [`29L62xCL...`](https://solscan.io/tx/29L62xCLsWwbEu5P9qZe26Krba1MT2LzwrsBcV8nmQukr3DA3iAcsSrorERdCxf8QYPVSQGRfsTQrqkLsSrybLMd), slot 453393443.
- Bank (50% of proceeds, signed by the watcher, no tap): [`4T4ELre6...`](https://solscan.io/tx/4T4ELre6LfofQzqSQSYeE5ckMjb2XzTBLYAXYViL4hwkwqsxNED4HCHmax2wqMDSj5RtCgnB5sUAvkNHxPbNMgNS), slot 453393448.
- **5 slots behind the sell, 1,966 ms from notification to confirmed bank.** The buy was correctly ignored ("not a sell").

## 4. SKR staking in the vault's name: PASS by interface, live call in build

- SKR staking program `SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ` (65 of 80 recent SKR mint transactions). Its Anchor IDL is published on chain.
- `stake`: only `payer` signs. `user` (the stake owner) can be any account, so the vault PDA can own the stake. User stake PDA seeds: `["user_stake", stake_config, user, guardian_pool]`.
- `unstake` needs `user` to sign and `withdraw` pays `user_token_account`: the Kept program signs for its vault PDA, so only Kept (and its cooldown) can take staked SKR out.

## Verdict

All four assumptions hold. Build on them. Open item: an indexed history source for a fast mirror.
