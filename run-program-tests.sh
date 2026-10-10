#!/bin/bash
# Build the program (SBPF v0) and run the LiteSVM tests. Run inside WSL or Linux with the Solana toolchain.
set -e
cd "$(dirname "$0")"
cargo build-sbf --manifest-path programs/kept_vault/Cargo.toml --arch v0 2>&1 | grep -E "^error|-->|Finished" || true
ls -la target/deploy/kept_vault.so
cargo test -p kept_vault 2>&1 | grep -E "^(test |error)|panicked|test result|-->|expected|tx failed" | grep -v "0 passed"
