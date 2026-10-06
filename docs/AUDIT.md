# Kept CLOCK IN release-readiness audit

**Audited:** 2026-10-06 10:56 UTC  
**Target:** CLOCK IN Solana Mobile hackathon submission by 2026-10-09 06:59 UTC  
**Remote baseline:** `fcbafe5526b5826ffa05d89cfa49212aeaf79a96` on `origin/main`  
**Live product:** https://kept-seeker.vercel.app  
**Subjective submission readiness:** **68%**

## Decision

**Hold the final submission, continue shipping.** The core product is real and substantially implemented, but the submission still fails several hard delivery gates: no signed release APK, no public APK URL, the repository is private, physical-phone vault execution is not complete, and the demo video, deck, and Align submission are absent.

The build-agent credit reset is not a coding blocker. The remaining engineering work can be completed here. Release signing, phone interaction, profile completion, and final submission require the owner's existing credentials or direct actions.

The pull-request Vercel preview is currently red even though both the working tree and a fresh detached worktree pass `npm ci && npm run build`. Vercel's build log is private and no Vercel credential is available here, so that provider-specific failure must be inspected before merge.

## What is verified

| Area | Result | Evidence |
|---|---|---|
| Android unit tests | Pass, 52/52 | `:app:testDebugUnitTest` |
| Android debug build | Pass | `:app:assembleDebug` |
| Android release build | Pass, unsigned | `:app:assembleRelease` |
| Devnet phone-test APK | Built and signature-verified | SHA-256 `ce46b5e0bdf699be229016debae06999efeff606faabecb8a5629e0d0c1bbdb1` |
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

3. **Physical-device vault flow is incomplete**
   - The APK has real-phone evidence for installation, launch, MWA connection, Android-Keystore trading-wallet creation, backward-navigation failure, and wallet-association failure.
   - The faucet subsequently landed 1.0 devnet SOL. Direct simulation of the exact vault-open instruction now succeeds with `InitVault` and 12,374 compute units.
   - Vault creation remains unsigned because the wallet labels the transaction malicious. Testers must cancel until app identity and wallet-provider trust are resolved.

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

6. **Mainnet is not available**
   - The configured vault program is executable on devnet and absent on mainnet.
   - Builds now default to devnet and fail closed for mainnet unless `-Pkept.enableMainnet=true` is passed explicitly.
   - Mainnet remains blocked until the program is deployed, verified, audited, funded, and exercised end to end.

7. **Jupiter and SKR paths are implemented but unsafe to expose**
   - Jupiter's returned transaction was signed without semantic validation of payer, instructions, assets, amounts, and destinations.
   - SKR banking could stake unrelated SKR already present in the trading wallet.
   - The Trade tab and staked-SKR choice are now disabled. They remain release work, not product claims.

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

15. **Failed-bank retry could not distinguish safe retries from ambiguous submissions**
    - The watcher did not call the retry helper, while calling it blindly could duplicate a SOL bank after an RPC timeout or repeat part of the multi-step SKR flow.
    - Fixed by deriving the SOL transaction signature before RPC submission, retaining it when submission or confirmation is ambiguous, and automatically retrying only SOL attempts that failed before a signed transaction could have been submitted.
    - Ambiguous SOL attempts and every failed SKR attempt remain blocked for explicit chain reconciliation.
    - Regression tests cover safe restart retry, ambiguous submission suppression, and SKR non-retry.

16. **Vault setup launched a transaction that was guaranteed to fail**
   - Real-phone testing exposed a wallet simulation warning during vault creation.
   - The connected owner initially had zero devnet SOL. After the faucet landed, direct simulation succeeds.
   - Kept checks current rent and owner balance, and now simulates every main-wallet transaction before opening MWA.
   - The account-size and shortfall calculation are covered by the real devnet vault fixture.

17. **Setup navigation and wallet recovery were trapped**
   - Added on-screen and Android-system back navigation across all setup steps.
   - Added visible network labeling and a Change wallet path that clears stale MWA authorization without deleting the device trading key.
   - MWA operations now use fresh, serialized local-association sessions and reject a different authorized account.

18. **Transaction success could be reported before confirmation**
   - Owner-wallet actions and in-app Jupiter swaps ignored a false confirmation result.
   - They now simulate first, submit once, require confirmation, and show the signature with a do-not-retry warning when status is ambiguous.

19. **Money inputs used floating point**
   - Setup funding, vault withdrawal, home funding, and trade amounts now use exact decimal-to-base-unit conversion.
   - Sell percentages are bounded to 0-100% and use exact integer rounding.

20. **Turning the reflex off was not persistent**
   - Refresh and reboot could turn the watcher back on after the user disabled it.
   - Background-watcher consent now persists independently from vault existence and is honored by the boot receiver.

21. **Mainnet could be selected accidentally**
   - The program account is absent on mainnet.
   - Devnet is now the default, and an explicit mainnet enable flag is required at build time.

22. **Rapid actions could overlap wallet sessions**
   - ViewModel work admission was not atomic, so two taps could launch before Compose propagated `busy`.
   - A process-wide operation gate now admits one financial action at a time, MWA calls remain mutex-serialized, and coroutine cancellation is rethrown rather than converted into an error.

23. **The watcher trusted local preferences as authorization**
   - Before starting, the watcher now reads the on-chain vault and requires its owner and banker to match the connected wallet and this device's trading key.
   - A mismatch disables the watcher instead of attempting signed banks.

24. **Financial validation existed only in UI controls**
   - Funding and withdrawal limits are now repeated in the ViewModel using live source balance and rent reserve.
   - Vault creation verifies the confirmed on-chain owner and banker before persisting readiness or starting the watcher.

### Still open before production

- Replace the watcher JSON store with crash-safe, generation-retaining atomic persistence. Corruption currently risks losing cost basis and the processing cursor.
- Add semantic validation and adversarial fixtures before restoring in-app Jupiter trading.
- Bound SKR staking to the verified post-swap delta before restoring staked-SKR banking.
- Recover the existing release key and resolve wallet-provider trust warnings.
- Complete the signed physical-phone vault, bank, withdrawal, restart, and recovery loop.

## Requirement matrix

| Requirement | Status | Next proof |
|---|---|---|
| Mobile-first Android app | Installed and launched on physical Android | Complete the vault and banking loop |
| Functional APK | Debug devnet APK verified | Sign release APK and test install |
| Solana Mobile Stack | Implemented | Include MWA flow in video |
| Mobile Wallet Adapter | Connected successfully on physical Android | Complete a signed vault transaction |
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
- Install the refreshed `artifacts/kept-devnet-debug.apk`, retest wallet reconnect and back navigation, and cancel if the wallet still labels the transaction malicious.
- Provide access to the Align account when final submission is ready.
- Confirm before making the private repository public or publishing a GitHub release.

## Not verified

- No physical Android or Seeker was connected to this machine.
- No wallet transaction was sent during this audit.
- Mainnet program deployment was checked and the configured program account is absent.
- Existing program and release signing keys were not found here.
- The current official dates API could not be refreshed in this environment. The deadline above comes from the saved official event snapshot dated 2026-09-29.
