#!/bin/bash
# Build the program (SBPF v0) and run the LiteSVM tests. Run inside WSL.
set -e
cd "$(dirname "$0")"
anchor build --arch v0 2>&1 | grep -E "^error|Finished" || true
cargo test -p kept_vault 2>&1 | grep -E "^(test |error)|panicked|test result|-->|expected" | head -60
