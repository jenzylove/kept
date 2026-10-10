//! The SKR path, against the real SKR staking program (dumped from mainnet into tests/fixtures).
//! Regenerate fixtures with `node scripts/dump-skr-fixtures.mjs`.
mod common;
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use common::*;

const SKR_PROGRAM: &str = "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ";
const SKR_UNSTAKE_PERIOD: i64 = 172_800;
const STAKE_DISC: [u8; 8] = [206, 176, 202, 18, 200, 209, 179, 108];
const WITHDRAW_DISC: [u8; 8] = [183, 18, 70, 156, 148, 109, 161, 34];

struct Skr {
    program: Pubkey,
    config: Pubkey,
    pool: Pubkey,
    stake_vault: Pubkey,
    mint: Pubkey,
    event_authority: Pubkey,
}

fn load_skr(svm: &mut LiteSVM) -> Skr {
    let program = pk(SKR_PROGRAM);
    svm.add_program(program, include_bytes!("fixtures/skr_staking.so")).unwrap();
    let manifest: serde_json::Value = serde_json::from_str(include_str!("fixtures/skr-accounts.json")).unwrap();
    let mut found = std::collections::HashMap::new();
    for a in manifest["accounts"].as_array().unwrap() {
        let key = pk(a["pubkey"].as_str().unwrap());
        svm.set_account(
            key,
            Account {
                lamports: a["lamports"].as_u64().unwrap(),
                data: B64.decode(a["data"].as_str().unwrap()).unwrap(),
                owner: pk(a["owner"].as_str().unwrap()),
                executable: false,
                rent_epoch: 0,
            },
        )
        .unwrap();
        found.insert(a["name"].as_str().unwrap().to_string(), key);
    }
    Skr {
        program,
        config: found["stake_config"],
        pool: found["guardian_pool"],
        stake_vault: found["stake_vault"],
        mint: found["mint"],
        event_authority: Pubkey::find_program_address(&[b"__event_authority"], &program).0,
    }
}

/// A 165 byte SPL Token account: mint, owner, amount, no delegate, state = initialized.
fn token_account(svm: &mut LiteSVM, address: Pubkey, mint: Pubkey, owner: Pubkey, amount: u64) {
    let mut data = vec![0u8; 165];
    data[0..32].copy_from_slice(mint.as_ref());
    data[32..64].copy_from_slice(owner.as_ref());
    data[64..72].copy_from_slice(&amount.to_le_bytes());
    data[108] = 1;
    let lamports = svm.minimum_balance_for_rent_exemption(data.len());
    svm.set_account(address, Account { lamports, data, owner: token_program(), executable: false, rent_epoch: 0 }).unwrap();
}

fn token_balance(svm: &LiteSVM, address: &Pubkey) -> u64 {
    u64::from_le_bytes(svm.get_account(address).unwrap().data[64..72].try_into().unwrap())
}

fn ata(wallet: &Pubkey, mint: &Pubkey) -> Pubkey {
    let ata_program = pk("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL");
    Pubkey::find_program_address(&[wallet.as_ref(), token_program().as_ref(), mint.as_ref()], &ata_program).0
}

fn user_stake_pda(skr: &Skr, user: &Pubkey) -> Pubkey {
    Pubkey::find_program_address(&[b"user_stake", skr.config.as_ref(), user.as_ref(), skr.pool.as_ref()], &skr.program).0
}

/// UserStake: disc(8) bump(1) config(32) user(32) pool(32) shares(u128) ... unstaking_amount(u64) unstake_timestamp(i64)
fn read_shares(svm: &LiteSVM, user_stake: &Pubkey) -> u128 {
    let d = svm.get_account(user_stake).unwrap().data;
    u128::from_le_bytes(d[105..121].try_into().unwrap())
}

fn read_unstaking(svm: &LiteSVM, user_stake: &Pubkey) -> (u64, i64) {
    let d = svm.get_account(user_stake).unwrap().data;
    let n = d.len();
    (u64::from_le_bytes(d[n - 16..n - 8].try_into().unwrap()), i64::from_le_bytes(d[n - 8..n].try_into().unwrap()))
}

/// SKR `stake`: only `payer` signs; `user` (the stake owner) is the vault PDA.
fn skr_stake_ix(skr: &Skr, payer: &Pubkey, user: &Pubkey, user_token: &Pubkey, amount: u64) -> Instruction {
    let mut data = STAKE_DISC.to_vec();
    data.extend_from_slice(&amount.to_le_bytes());
    Instruction {
        program_id: skr.program,
        accounts: vec![
            AccountMeta::new(user_stake_pda(skr, user), false),
            AccountMeta::new(skr.config, false),
            AccountMeta::new(skr.pool, false),
            AccountMeta::new(*payer, true),
            AccountMeta::new_readonly(*user, false),
            AccountMeta::new(*user_token, false),
            AccountMeta::new(skr.stake_vault, false),
            AccountMeta::new_readonly(skr.mint, false),
            AccountMeta::new_readonly(token_program(), false),
            AccountMeta::new_readonly(system_program(), false),
            AccountMeta::new_readonly(skr.event_authority, false),
            AccountMeta::new_readonly(skr.program, false),
        ],
        data,
    }
}

/// SKR `withdraw` (after SKR's own unstake period): anyone can call it; tokens go to the vault's SKR account.
fn skr_withdraw_ix(skr: &Skr, user: &Pubkey, user_token: &Pubkey) -> Instruction {
    Instruction {
        program_id: skr.program,
        accounts: vec![
            AccountMeta::new(user_stake_pda(skr, user), false),
            AccountMeta::new(skr.config, false),
            AccountMeta::new(*user, false),
            AccountMeta::new(skr.stake_vault, false),
            AccountMeta::new(*user_token, false),
            AccountMeta::new_readonly(token_program(), false),
            AccountMeta::new_readonly(skr.event_authority, false),
            AccountMeta::new_readonly(skr.program, false),
        ],
        data: WITHDRAW_DISC.to_vec(),
    }
}

fn request_staked_ix(owner: &Pubkey, shares: u64) -> Instruction {
    request_ix(owner, owner, STAKED_SKR_ASSET, shares, owner)
}

fn unstake_ix(skr: &Skr, vault_owner: &Pubkey) -> Instruction {
    let vault = vault_pda(vault_owner);
    ix("execute_unstake_skr", &[], vec![
        AccountMeta::new(vault, false),
        AccountMeta::new(user_stake_pda(skr, &vault), false),
        AccountMeta::new(skr.config, false),
        AccountMeta::new(skr.pool, false),
        AccountMeta::new_readonly(skr.stake_vault, false),
        AccountMeta::new_readonly(skr.mint, false),
        AccountMeta::new_readonly(skr.event_authority, false),
        AccountMeta::new_readonly(skr.program, false),
    ])
}

fn release_ix(owner: &Pubkey, vault_skr: &Pubkey, owner_skr: &Pubkey, amount: u64) -> Instruction {
    release_ix_for(owner, &vault_pda(owner), vault_skr, owner_skr, amount)
}

fn release_ix_for(signer: &Pubkey, vault: &Pubkey, vault_skr: &Pubkey, owner_skr: &Pubkey, amount: u64) -> Instruction {
    ix("release_skr", &amount.to_le_bytes(), vec![
        AccountMeta::new_readonly(*signer, true),
        AccountMeta::new(*vault, false),
        AccountMeta::new(*vault_skr, false),
        AccountMeta::new(*owner_skr, false),
        AccountMeta::new_readonly(token_program(), false),
    ])
}

struct Env {
    svm: LiteSVM,
    skr: Skr,
    owner: Keypair,
    banker: Keypair,
    vault: Pubkey,
    banker_skr: Pubkey,
    vault_skr: Pubkey,
    owner_skr: Pubkey,
}

fn env() -> Env {
    let mut svm = LiteSVM::new();
    svm.add_program(program_id(), include_bytes!(concat!(env!("CARGO_TARGET_TMPDIR"), "/../deploy/kept_vault.so"))).unwrap();
    let skr = load_skr(&mut svm);
    set_time(&mut svm, NOW);
    let owner = Keypair::new();
    let banker = Keypair::new();
    svm.airdrop(&owner.pubkey(), 10_000_000_000).unwrap();
    svm.airdrop(&banker.pubkey(), 10_000_000_000).unwrap();
    must(send(&mut svm, init_ix(&owner.pubkey(), &banker.pubkey(), COOLDOWN, 5000), &owner));
    let vault = vault_pda(&owner.pubkey());
    let ata = |wallet: &Pubkey| ata(wallet, &skr.mint);
    let (banker_skr, vault_skr, owner_skr) = (ata(&banker.pubkey()), ata(&vault), ata(&owner.pubkey()));
    token_account(&mut svm, banker_skr, skr.mint, banker.pubkey(), 5_000_000);
    token_account(&mut svm, vault_skr, skr.mint, vault, 0);
    token_account(&mut svm, owner_skr, skr.mint, owner.pubkey(), 0);
    Env { svm, skr, owner, banker, vault, banker_skr, vault_skr, owner_skr }
}

#[test]
fn skr_staked_in_the_vaults_name_only_comes_out_through_the_vaults_cooldown() {
    let mut e = env();
    let (owner, banker) = (e.owner.pubkey(), e.banker.pubkey());
    let stake_account = user_stake_pda(&e.skr, &e.vault);

    // 1. The banker (trading wallet) stakes 2 SKR; the stake belongs to the vault PDA.
    let stake = skr_stake_ix(&e.skr, &banker, &e.vault, &e.banker_skr, 2_000_000);
    must(send(&mut e.svm, stake, &e.banker));
    assert_eq!(token_balance(&e.svm, &e.banker_skr), 3_000_000, "SKR left the trading wallet");
    let shares = read_shares(&e.svm, &stake_account);
    assert!(shares > 0, "the vault owns shares");
    println!("shares for 2 SKR: {shares}");

    // 2. Nobody can unstake directly: the stake's owner is the vault PDA, which only the program can sign for.
    let mut data = vec![90, 95, 107, 42, 205, 124, 50, 225];
    data.extend_from_slice(&shares.to_le_bytes());
    let direct = Instruction {
        program_id: e.skr.program,
        accounts: vec![
            AccountMeta::new(stake_account, false),
            AccountMeta::new(e.skr.config, false),
            AccountMeta::new(e.skr.pool, false),
            AccountMeta::new_readonly(e.vault, false), // `user` should sign: nobody can sign for a PDA
            AccountMeta::new_readonly(e.skr.stake_vault, false),
            AccountMeta::new_readonly(e.skr.mint, false),
            AccountMeta::new_readonly(e.skr.event_authority, false),
            AccountMeta::new_readonly(e.skr.program, false),
        ],
        data,
    };
    assert!(send(&mut e.svm, direct, &e.owner).is_err(), "direct unstake must fail");
    assert_eq!(read_shares(&e.svm, &stake_account), shares, "shares untouched");

    // 3. Through the vault: ask, wait the vault's cooldown, then unstake.
    let want = u64::try_from(shares).expect("shares fit in u64 for this stake");
    must(send(&mut e.svm, request_staked_ix(&owner, want), &e.owner));
    fails_with(send(&mut e.svm, unstake_ix(&e.skr, &owner), &e.owner), "StillCooling");
    set_time(&mut e.svm, NOW + COOLDOWN);
    must(send(&mut e.svm, unstake_ix(&e.skr, &owner), &e.owner));
    assert_eq!(read_shares(&e.svm, &stake_account), 0, "all shares unstaked");
    let (unstaking, _) = read_unstaking(&e.svm, &stake_account);
    assert!(unstaking > 0, "SKR is now in the SKR program's own unstake period: {unstaking}");
    assert!(read_vault(&e.svm, &e.vault).skr_unstake_approved);

    // 4. Releasing before SKR's own period ends moves nothing (no tokens are in the vault yet).
    assert_eq!(token_balance(&e.svm, &e.vault_skr), 0);

    // 5. After SKR's own period, anyone completes the withdrawal into the VAULT's token account...
    set_time(&mut e.svm, NOW + COOLDOWN + SKR_UNSTAKE_PERIOD + 1);
    let stranger = Keypair::new();
    e.svm.airdrop(&stranger.pubkey(), 1_000_000_000).unwrap();
    must(send(&mut e.svm, skr_withdraw_ix(&e.skr, &e.vault, &e.vault_skr), &stranger));
    let landed = token_balance(&e.svm, &e.vault_skr);
    // the SKR program converts through shares and rounds down: at most one base unit (0.000001 SKR) is lost
    assert!(landed + 1 >= 2_000_000 && landed <= 2_000_000, "the vault received its SKR back: {landed}");

    // 6. ...and only the owner can release it to themselves.
    let thief = Keypair::new();
    e.svm.airdrop(&thief.pubkey(), 1_000_000_000).unwrap();
    assert!(send(&mut e.svm, release_ix(&thief.pubkey(), &e.vault_skr, &e.owner_skr, landed), &thief).is_err());
    must(send(&mut e.svm, release_ix(&owner, &e.vault_skr, &e.owner_skr, landed), &e.owner));
    assert_eq!(token_balance(&e.svm, &e.owner_skr), landed);
    assert!(!read_vault(&e.svm, &e.vault).skr_unstake_approved, "approval is single use");
}

// ---- SKR attack tests ----

/// A vault whose owner has an approved release waiting and 3 SKR sitting in the vault's token account.
fn env_with_releasable_skr() -> Env {
    let mut e = env();
    let owner = e.owner.pubkey();
    token_account(&mut e.svm, e.vault_skr, e.skr.mint, e.vault, 3_000_000);
    // a liquid SKR withdrawal, requested and past its cooldown
    must(send(&mut e.svm, request_ix(&owner, &owner, SKR_ASSET, 3_000_000, &owner), &e.owner));
    set_time(&mut e.svm, NOW + COOLDOWN);
    e
}

#[test]
fn liquid_skr_cannot_be_released_before_the_vaults_cooldown() {
    let mut e = env();
    let owner = e.owner.pubkey();
    token_account(&mut e.svm, e.vault_skr, e.skr.mint, e.vault, 3_000_000);
    // no request at all
    fails_with(send(&mut e.svm, release_ix(&owner, &e.vault_skr, &e.owner_skr, 1_000_000), &e.owner), "StillCooling");
    must(send(&mut e.svm, request_ix(&owner, &owner, SKR_ASSET, 2_000_000, &owner), &e.owner));
    // requested, still cooling
    set_time(&mut e.svm, NOW + COOLDOWN - 1);
    fails_with(send(&mut e.svm, release_ix(&owner, &e.vault_skr, &e.owner_skr, 2_000_000), &e.owner), "StillCooling");
    set_time(&mut e.svm, NOW + COOLDOWN);
    // more than was requested
    fails_with(send(&mut e.svm, release_ix(&owner, &e.vault_skr, &e.owner_skr, 2_000_001), &e.owner), "StillCooling");
    must(send(&mut e.svm, release_ix(&owner, &e.vault_skr, &e.owner_skr, 2_000_000), &e.owner));
    assert_eq!(token_balance(&e.svm, &e.owner_skr), 2_000_000);
    assert_eq!(token_balance(&e.svm, &e.vault_skr), 1_000_000);
    // the request is used up
    fails_with(send(&mut e.svm, release_ix(&owner, &e.vault_skr, &e.owner_skr, 1_000_000), &e.owner), "StillCooling");
}

#[test]
fn released_skr_can_only_go_to_the_owners_own_skr_account() {
    let mut e = env_with_releasable_skr();
    let owner = e.owner.pubkey();
    let thief = funded(&mut e.svm);
    let thief_skr = Pubkey::new_unique();
    token_account(&mut e.svm, thief_skr, e.skr.mint, thief.pubkey(), 0);
    // the owner is tricked into (or a UI bug sends) a release to someone else's account
    fails_with(send(&mut e.svm, release_ix(&owner, &e.vault_skr, &thief_skr, 3_000_000), &e.owner), "BadTokenAccount");
    // a thief signs as "owner" against the victim's vault
    let ix = release_ix_for(&thief.pubkey(), &e.vault, &e.vault_skr, &thief_skr, 3_000_000);
    fails_with(send(&mut e.svm, ix, &thief), "NotOwner");
    assert_eq!(token_balance(&e.svm, &e.vault_skr), 3_000_000);
}

#[test]
fn release_rejects_token_accounts_that_are_not_real_skr_accounts() {
    let mut e = env_with_releasable_skr();
    let owner = e.owner.pubkey();
    // another mint dressed up as the vault's account
    let other_mint = Pubkey::new_unique();
    let wrong_mint = Pubkey::new_unique();
    token_account(&mut e.svm, wrong_mint, other_mint, e.vault, 3_000_000);
    fails_with(send(&mut e.svm, release_ix(&owner, &wrong_mint, &e.owner_skr, 1), &e.owner), "BadTokenAccount");
    // right bytes, but not owned by the token program
    let forged = Pubkey::new_unique();
    let data = e.svm.get_account(&e.vault_skr).unwrap().data.clone();
    e.svm.set_account(forged, Account { lamports: 10_000_000, data, owner: system_program(), executable: false, rent_epoch: 0 }).unwrap();
    fails_with(send(&mut e.svm, release_ix(&owner, &forged, &e.owner_skr, 1), &e.owner), "BadTokenAccount");
    // a fake token program
    let mut ix = release_ix(&owner, &e.vault_skr, &e.owner_skr, 1);
    ix.accounts[4] = AccountMeta::new_readonly(Pubkey::new_unique(), false);
    fails(send(&mut e.svm, ix, &e.owner));
    assert_eq!(token_balance(&e.svm, &e.vault_skr), 3_000_000);
}

#[test]
fn unstake_needs_a_staked_skr_request_and_the_real_skr_program() {
    let mut e = env();
    let owner = e.owner.pubkey();
    let banker = e.banker.pubkey();
    must(send(&mut e.svm, skr_stake_ix(&e.skr, &banker, &e.vault, &e.banker_skr, 2_000_000), &e.banker));
    // no request
    fails_with(send(&mut e.svm, unstake_ix(&e.skr, &owner), &e.owner), "NothingPending");
    // a SOL request does not unlock an unstake
    must(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, 1000, &owner), &e.owner));
    set_time(&mut e.svm, NOW + COOLDOWN);
    fails_with(send(&mut e.svm, unstake_ix(&e.skr, &owner), &e.owner), "NothingPending");
    must(send(&mut e.svm, cancel_ix(&owner, &owner), &e.owner));
    // a real request, but a look-alike program in place of SKR staking
    let shares = u64::try_from(read_shares(&e.svm, &user_stake_pda(&e.skr, &e.vault))).unwrap();
    must(send(&mut e.svm, request_staked_ix(&owner, shares), &e.owner));
    set_time(&mut e.svm, NOW + 3 * COOLDOWN);
    let mut ix = unstake_ix(&e.skr, &owner);
    ix.accounts[7] = AccountMeta::new_readonly(program_id(), false);
    fails(send(&mut e.svm, ix, &e.owner));
    assert_eq!(u64::try_from(read_shares(&e.svm, &user_stake_pda(&e.skr, &e.vault))).unwrap(), shares, "shares untouched");
}
