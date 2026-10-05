# Build log

| Date | Commit | What now works |
|---|---|---|
| 2026-10-04 | first | Spike passed on mainnet: profit from history, 1 s detection, reflex bank 5 slots after a sell with no tap, SKR stake can be owned by a PDA. PRD and plan written. |
| 2026-10-05 | next | Vault program builds (Anchor 1.2, `--arch v0`; v3 is Anchor's default and the test simulator can't load it). 10 LiteSVM tests pass: bank, cooldown, owner-only, rent floor, delayed cooldown decrease. Engine: 15 real transactions match Helius's parser. Note: litesvm's Node binding aborts the process after a failed tx, so program tests are Rust. |
