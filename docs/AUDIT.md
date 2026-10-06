# Kept CLOCK IN release-readiness audit

**Audited:** 2026-10-06 10:56 UTC  
**Target:** CLOCK IN Solana Mobile hackathon submission by 2026-10-09 06:59 UTC  
**Remote baseline:** `fcbafe5526b5826ffa05d89cfa49212aeaf79a96` on `origin/main`  
**Live product:** https://kept-seeker.vercel.app  
**Subjective submission readiness:** **68%**

## Decision

**Hold the final submission, continue shipping.** The core product is real and substantially implemented, but the submission still fails several hard delivery gates: no signed release APK, no public APK URL, the repository is private, physical-phone execution has not been recorded, and the demo video, deck, and Align submission are absent.

The build-agent credit reset is not a coding blocker. The remaining engineering work can be completed here. Release signing, phone interaction, profile completion, and final submission require the owner's existing credentials or direct actions.

The pull-request Vercel preview is currently red even though both the working tree and a fresh detached worktree pass `npm ci && npm run build`. Vercel's build log is private and no Vercel credential is available here, so that provider-specific failure must be inspected before merge.

## What is verified

| Area | Result | Evidence |
|---|---|---|
| Android unit tests | Pass, 43/43 | `:app:testDebugUnitTest` |
| Android debug build | Pass | `:app:assembleDebug` |
| Android release build | Pass, unsigned | `:app:assembleRelease` |
| Devnet phone-test APK | Built and signature-verified | `artifacts/kept-devnet-debug.apk` |
| Engine tests | Pass, 10/10 | `engine/npm test` |
| Web tests | Pass, 4/4 | `web/npm test` |
| Web production build | Pass | `web/npm run build` |
| Web dependency audit | Pass, zero known vulnerabilities | `npm audit --audit-level=high` |
| Live site | Root, `/judges`, and `/proof` return HTTP 200 | live HTTP checks |
| Live proof | Two devnet bank events visible | `/proof` |
| Git history secret scan | No common key/token patterns in 175 reachable blobs | local full-history scan |
| Vault program tests | Not rerun in this environment | Rust/Solana toolchain unavailable here; repository documents 11 prior simulator tests |

## Findings

### Critical submission blockers

1. **No signed release APK or public download**
   - `assembleRelease` produces `app-release-unsigned.apk`.
   - The `/judges` page previously had no APK link, and likely APK paths returned 404.
   - The website pins release certificate fingerprint `14:95:A4:39:6E:61:04:6A:9D:EA:D1:17:DF:E6:1C:03:B7:24:DB:12:2F:82:72:D4:3A:62:47:0A:E3:FB:EB:C2`.
   - The matching keystore is not present on this machine. Do not create a replacement until the existing key is confirmed lost.

2. **Repository is private**
   - GitHub reports `jenzylove/kept` as private.
   - The event requires accessible source.

3. **Physical-device requirement is unproven**
   - Emulator and MWA reference-wallet evidence exists.
   - No current proof shows this checkout running on the user's Android phone or a Seeker.

4. **Submission media and form are absent**
   - No final video of three minutes or less.
   - No final pitch deck.
   - No verified Align builder profile or submitted entry.

### High priority product proof

5. **Vercel preview failure needs its private log**
   - PR #1's Vercel preview reports a failed deployment.
   - A fresh detached worktree passes `npm ci` and the production build with all relevant environment variables unset.
   - GitHub resolves the commit author to `jenzylove`, so author mapping is not the apparent cause.
   - Inspect the Vercel log before merging or deploying to production.

6. **Final network path is not locked**
   - Devnet program and bank proofs are verified in project evidence.
   - Mainnet deployment of the same program ID is not verified from this checkout.
   - A devnet release is sufficient for the submission if labeled clearly. Mainnet should remain a stretch goal unless the existing program authority and funding are available.

7. **Jupiter and SKR paths are implemented but lack live app evidence**
   - Jupiter quote, transaction signing, and app UI exist.
   - SKR swap-and-stake logic and tests exist.
   - No verified app-generated Jupiter buy/sell or live SKR bank transaction is recorded in the current evidence.

### Fixed during this audit

8. **The clean checkout did not compile**
   - Root cause: `.gitignore` ignored every `keys/` directory, including `app.kept.keys.TradingKey` and its tests.
   - Fixed by restoring encrypted trading-key storage and five regression tests, and narrowing `.gitignore`.

9. **Android build script only worked on the former Windows machine**
   - It hardcoded `/c/Users/LENOVO/dev-tools/env.sh` and global `gradle`.
   - Fixed with an optional environment file, common SDK discovery, the checked-in Gradle wrapper, and executable bits.

10. **Cluster build flag was inconsistent**
   - `-Pkept.cluster=devnet` changed the websocket but not `BuildConfig.CLUSTER`.
   - Fixed and verified for both mainnet and devnet generated constants.

11. **Clean release defaults could bypass the protected proxy**
    - The APK defaulted to public RPC and no bulk-history endpoint.
    - Fixed with explicit cluster-safe RPC and history proxy URLs.

12. **Web dependency advisories**
    - PostCSS and Sharp advisories were present.
    - Fixed with safe transitive overrides. The production build passes and `npm audit` reports zero vulnerabilities.

13. **Judge path had no APK call to action**
    - Added a Download APK button, source link, and proof link.
    - The APK URL will remain unavailable until the release asset is published.

14. **Hardware-security copy was broader than the implementation proves**
    - Android Keystore is guaranteed by the code. Hardware backing varies by device.
    - App and web copy now state the narrower, verifiable claim.

## Requirement matrix

| Requirement | Status | Next proof |
|---|---|---|
| Mobile-first Android app | Implemented and emulator-tested | Record on physical Android |
| Functional APK | Debug devnet APK verified | Sign release APK and test install |
| Solana Mobile Stack | Implemented | Include MWA flow in video |
| Mobile Wallet Adapter | Implemented and previously exercised with reference wallet | Run with Phantom or Solflare on phone |
| Meaningful Solana interaction | Devnet vault and bank evidence exists | Reproduce from current phone build |
| Direct APK URL | Missing | Publish `kept.apk` as a release asset |
| Public source repository | Missing | Make repository public after final secret scan |
| Demo video, max 3 minutes | Missing | Record the core loop and proof |
| Pitch deck | Missing | Export a short product-led deck |
| Align profile and submission | Unverified | Complete and capture confirmation |
| Meaningful SKR integration | Implemented and unit-tested | Live SKR bank transaction if feasible |
| dApp Store | Post-win obligation, not a pre-submission blocker | Prepare listing after submission |

## Priority plan

### Today, October 6

1. Land the clean-checkout fixes and rerun all gates.
2. Install the devnet test APK on the user's Android phone.
3. Test: launch, connect MWA wallet, create trading wallet, create vault, fund, bank, request withdrawal, cancel, and restart catch-up.
4. Locate the existing release keystore and its `local.properties` settings. Share only the file location first, not passwords in chat.
5. Confirm whether the existing Solana program authority still exists. Do not recreate it.

### October 7

1. Fix every phone-test failure.
2. Sign a devnet release APK with the existing certificate.
3. Publish the APK and verify direct download, install, package ID, version, and certificate fingerprint.
4. Make the repository public after a final history scan.
5. Record the three-minute demo around one clear story: profitable sell, automatic bank, on-chain receipt, delayed withdrawal.

### October 8

1. Produce the short deck from the verified product evidence.
2. Run the submission checklist from a fresh phone/browser session.
3. Complete the Align profile and submit by 18:00 UTC, preserving roughly 13 hours of buffer.
4. Attempt mainnet Jupiter and SKR proof only after the core submission is safe.

## Owner input required

- Confirm whether the previous Windows workspace still contains `android/local.properties` and the release `.jks` or `.keystore` file.
- Install and test `artifacts/kept-devnet-debug.apk` on the available Android phone.
- Provide access to the Align account when final submission is ready.
- Confirm before making the private repository public or publishing a GitHub release.

## Not verified

- No physical Android or Seeker was connected to this machine.
- No wallet transaction was sent during this audit.
- Mainnet program deployment was not confirmed.
- Existing program and release signing keys were not found here.
- The current official dates API could not be refreshed in this environment. The deadline above comes from the saved official event snapshot dated 2026-09-29.
