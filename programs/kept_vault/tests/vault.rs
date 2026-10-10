//! Program tests (LiteSVM). Build first: `cargo build-sbf --manifest-path programs/kept_vault/Cargo.toml --arch v0`,
//! then `cargo test -p kept_vault`.
mod common;
use common::*;

#[test]
fn init_rejects_a_cooldown_under_one_minute_and_a_bad_share() {
    let mut svm = new_svm();
    let owner = Keypair::new();
    let banker = Keypair::new();
    svm.airdrop(&owner.pubkey(), SOL).unwrap();
    fails_with(send(&mut svm, init_ix(&owner.pubkey(), &banker.pubkey(), 10, 5000), &owner), "BadCooldown");
    fails_with(send(&mut svm, init_ix(&owner.pubkey(), &banker.pubkey(), COOLDOWN, 0), &owner), "BadShare");
    fails_with(send(&mut svm, init_ix(&owner.pubkey(), &banker.pubkey(), COOLDOWN, 10_001), &owner), "BadShare");
}

#[test]
fn bank_moves_sol_into_the_vault_and_logs_it() {
    let mut e = env();
    let before = e.svm.get_balance(&e.vault).unwrap();
    let meta = must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &e.owner.pubkey(), SOL / 10), &e.banker));
    assert_eq!(e.svm.get_balance(&e.vault).unwrap() - before, SOL / 10);
    let v = read_vault(&e.svm, &e.vault);
    assert_eq!(v.total_banked, SOL / 10);
    assert_eq!(v.bank_count, 1);
    assert!(meta.logs.iter().any(|l| l.starts_with("Program data:")), "Banked event emitted");
}

#[test]
fn only_the_banker_can_bank() {
    let mut e = env();
    let stranger = Keypair::new();
    e.svm.airdrop(&stranger.pubkey(), SOL).unwrap();
    fails_with(send(&mut e.svm, bank_ix(&stranger.pubkey(), &e.owner.pubkey(), 1000), &stranger), "NotBanker");
}

#[test]
fn withdraw_before_unlock_fails_and_after_unlock_pays_the_requested_address() {
    let mut e = env();
    let owner = e.owner.pubkey();
    must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &owner, SOL), &e.banker));
    let other = Keypair::new().pubkey();
    must(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, SOL / 2, &owner), &e.owner));

    fails_with(send(&mut e.svm, execute_sol_ix(&owner, &owner), &e.owner), "StillCooling");
    set_time(&mut e.svm, NOW + COOLDOWN - 1);
    fails_with(send(&mut e.svm, execute_sol_ix(&owner, &owner), &e.owner), "StillCooling");
    set_time(&mut e.svm, NOW + COOLDOWN);
    fails_with(send(&mut e.svm, execute_sol_ix(&owner, &other), &e.owner), "BadDestination");

    let before = e.svm.get_balance(&owner).unwrap();
    must(send(&mut e.svm, execute_sol_ix(&owner, &owner), &e.owner));
    let after = e.svm.get_balance(&owner).unwrap();
    assert_eq!(after - before, SOL / 2 - FEE);
    assert_eq!(read_vault(&e.svm, &e.vault).withdraw_amount, 0);
}

#[test]
fn cancel_clears_a_pending_withdrawal() {
    let mut e = env();
    let owner = e.owner.pubkey();
    must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &owner, SOL), &e.banker));
    must(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, SOL / 2, &owner), &e.owner));
    fails_with(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, 1, &owner), &e.owner), "WithdrawPending");
    must(send(&mut e.svm, cancel_ix(&owner, &owner), &e.owner));
    set_time(&mut e.svm, NOW + COOLDOWN + 10);
    fails_with(send(&mut e.svm, execute_sol_ix(&owner, &owner), &e.owner), "NothingPending");
}

#[test]
fn the_banker_and_strangers_cannot_withdraw() {
    let mut e = env();
    let owner = e.owner.pubkey();
    let banker = e.banker.pubkey();
    must(send(&mut e.svm, bank_ix(&banker, &owner, SOL), &e.banker));
    let stranger = Keypair::new();
    e.svm.airdrop(&stranger.pubkey(), SOL).unwrap();
    fails(send(&mut e.svm, request_ix(&banker, &owner, SOL_ASSET, 1000, &banker), &e.banker));
    fails(send(&mut e.svm, request_ix(&stranger.pubkey(), &owner, SOL_ASSET, 1000, &stranger.pubkey()), &stranger));
    assert_eq!(read_vault(&e.svm, &e.vault).withdraw_amount, 0);
}

#[test]
fn withdrawals_can_only_go_to_the_owner_or_the_trading_wallet() {
    let mut e = env();
    let owner = e.owner.pubkey();
    let dest = Keypair::new().pubkey();
    fails_with(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, 1000, &dest), &e.owner), "BadDestination");
    must(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, 1000, &e.banker.pubkey()), &e.owner));
}

#[test]
fn a_withdrawal_cannot_dig_into_the_rent_the_vault_needs() {
    let mut e = env();
    let owner = e.owner.pubkey();
    must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &owner, SOL / 100), &e.banker));
    must(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, SOL, &owner), &e.owner));
    set_time(&mut e.svm, NOW + COOLDOWN + 1);
    fails_with(send(&mut e.svm, execute_sol_ix(&owner, &owner), &e.owner), "NotEnough");
}

#[test]
fn lowering_the_cooldown_cannot_skip_the_wait_and_raising_it_is_instant() {
    let mut e = env();
    let owner = e.owner.pubkey();
    let banker = e.banker.pubkey();
    must(send(&mut e.svm, bank_ix(&banker, &owner, SOL), &e.banker));

    // tilt: drop the cooldown to one minute and immediately ask for money
    must(send(&mut e.svm, rules_ix(&owner, &owner, 60, 5000, &banker), &e.owner));
    let v = read_vault(&e.svm, &e.vault);
    assert_eq!(v.cooldown_secs, COOLDOWN, "cooldown unchanged right away");
    assert_eq!(v.pending_cooldown_secs, 60);

    must(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, SOL / 2, &owner), &e.owner));
    assert_eq!(read_vault(&e.svm, &e.vault).withdraw_unlock_at, NOW + COOLDOWN, "still waits the old cooldown");
    must(send(&mut e.svm, cancel_ix(&owner, &owner), &e.owner));

    // after the old cooldown has passed, the shorter one applies
    set_time(&mut e.svm, NOW + COOLDOWN + 1);
    must(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, SOL / 2, &owner), &e.owner));
    let v = read_vault(&e.svm, &e.vault);
    assert_eq!(v.cooldown_secs, 60);
    assert_eq!(v.withdraw_unlock_at, NOW + COOLDOWN + 1 + 60);
    must(send(&mut e.svm, cancel_ix(&owner, &owner), &e.owner));

    // raising is immediate
    must(send(&mut e.svm, rules_ix(&owner, &owner, 7200, 5000, &banker), &e.owner));
    assert_eq!(read_vault(&e.svm, &e.vault).cooldown_secs, 7200);
}

#[test]
fn only_the_owner_can_change_rules() {
    let mut e = env();
    let owner = e.owner.pubkey();
    let banker = e.banker.pubkey();
    fails(send(&mut e.svm, rules_ix(&banker, &owner, 60, 5000, &banker), &e.banker));
    assert_eq!(read_vault(&e.svm, &e.vault).banker, banker);
}

// ---- Attack tests: every check a framework would have generated, tried by hand. ----

/// Put arbitrary bytes at an address, owned by whichever program we say.
fn plant(svm: &mut LiteSVM, address: Pubkey, owner_program: Pubkey, data: Vec<u8>, lamports: u64) {
    svm.set_account(address, Account { lamports, data, owner: owner_program, executable: false, rent_epoch: 0 }).unwrap();
}

#[test]
fn a_bank_needs_the_bankers_signature_not_just_their_address() {
    let mut e = env();
    let thief = funded(&mut e.svm);
    // the real banker is named but does not sign; the thief pays the fee
    let mut ix = bank_ix(&e.banker.pubkey(), &e.owner.pubkey(), 1000);
    ix.accounts[0] = AccountMeta::new(e.banker.pubkey(), false);
    fails(send(&mut e.svm, ix, &thief));
    assert_eq!(read_vault(&e.svm, &e.vault).bank_count, 0);
}

#[test]
fn an_owner_action_needs_the_owners_signature_not_just_their_address() {
    let mut e = env();
    let owner = e.owner.pubkey();
    must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &owner, SOL), &e.banker));
    let thief = funded(&mut e.svm);
    for mut ix in [
        request_ix(&owner, &owner, SOL_ASSET, 1000, &owner),
        rules_ix(&owner, &owner, 60, 5000, &thief.pubkey()),
        cancel_ix(&owner, &owner),
    ] {
        ix.accounts[0] = AccountMeta::new_readonly(owner, false);
        fails(send(&mut e.svm, ix, &thief));
    }
    let v = read_vault(&e.svm, &e.vault);
    assert_eq!(v.withdraw_amount, 0);
    assert_eq!(v.banker, e.banker.pubkey());
}

#[test]
fn a_thief_cannot_sign_as_owner_against_someone_elses_vault() {
    let mut e = env();
    let owner = e.owner.pubkey();
    must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &owner, SOL), &e.banker));
    let thief = funded(&mut e.svm);
    let mut ix = request_ix(&thief.pubkey(), &thief.pubkey(), SOL_ASSET, SOL / 2, &thief.pubkey());
    ix.accounts[1] = AccountMeta::new(e.vault, false);
    fails_with(send(&mut e.svm, ix, &thief), "NotOwner");
    assert_eq!(read_vault(&e.svm, &e.vault).withdraw_amount, 0);
}

#[test]
fn a_fake_vault_owned_by_another_program_is_rejected() {
    let mut e = env();
    let thief = funded(&mut e.svm);
    // the real vault's bytes, renamed to the thief, in an account our program does not own
    let mut data = e.svm.get_account(&e.vault).unwrap().data.clone();
    data[8..40].copy_from_slice(thief.pubkey().as_ref());
    data[40..72].copy_from_slice(thief.pubkey().as_ref());
    let fake = Pubkey::new_unique();
    plant(&mut e.svm, fake, system_program(), data, 10 * SOL);
    fails_with(send(&mut e.svm, bank_ix_to(&thief.pubkey(), &fake, 1000), &thief), "BadVault");
    let mut ix = execute_sol_ix(&thief.pubkey(), &thief.pubkey());
    ix.accounts[0] = AccountMeta::new(fake, false);
    fails_with(send(&mut e.svm, ix, &thief), "BadVault");
}

#[test]
fn program_owned_bytes_at_the_wrong_address_are_not_a_vault() {
    let mut e = env();
    let owner = e.owner.pubkey();
    must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &owner, SOL), &e.banker));
    let thief = funded(&mut e.svm);
    // A user cannot create program owned bytes, but prove the address check anyway: owner renamed
    // to the thief, with an already unlocked withdrawal to the thief.
    let mut data = e.svm.get_account(&e.vault).unwrap().data.clone();
    data[8..40].copy_from_slice(thief.pubkey().as_ref());
    data[124..132].copy_from_slice(&(SOL / 2).to_le_bytes());
    data[132..164].copy_from_slice(thief.pubkey().as_ref());
    data[164..172].copy_from_slice(&1i64.to_le_bytes());
    let fake = Pubkey::new_unique();
    plant(&mut e.svm, fake, program_id(), data, 10 * SOL);
    let mut ix = execute_sol_ix(&thief.pubkey(), &thief.pubkey());
    ix.accounts[0] = AccountMeta::new(fake, false);
    fails_with(send(&mut e.svm, ix, &thief), "BadVault");
}

#[test]
fn a_vault_cannot_be_opened_twice_or_at_someone_elses_address() {
    let mut e = env();
    let owner = e.owner.pubkey();
    let thief = funded(&mut e.svm);
    // again by the owner: would reset the banker and any pending withdrawal
    fails(send(&mut e.svm, init_ix(&owner, &thief.pubkey(), 60, 5000), &e.owner));
    assert_eq!(read_vault(&e.svm, &e.vault).banker, e.banker.pubkey());
    // by a thief, aimed at the victim's vault address
    let mut ix = init_ix(&thief.pubkey(), &thief.pubkey(), 60, 5000);
    ix.accounts[1] = AccountMeta::new(e.vault, false);
    fails_with(send(&mut e.svm, ix, &thief), "BadVault");
}

#[test]
fn sending_lamports_to_a_vault_address_first_cannot_block_its_owner() {
    let mut svm = new_svm();
    let owner = funded(&mut svm);
    let banker = Keypair::new();
    let griefer = funded(&mut svm);
    let vault = vault_pda(&owner.pubkey());
    let transfer = Instruction {
        program_id: system_program(),
        accounts: vec![AccountMeta::new(griefer.pubkey(), true), AccountMeta::new(vault, false)],
        data: [2u32.to_le_bytes().to_vec(), 1000u64.to_le_bytes().to_vec()].concat(),
    };
    must(send(&mut svm, transfer, &griefer));
    must(send(&mut svm, init_ix(&owner.pubkey(), &banker.pubkey(), COOLDOWN, 5000), &owner));
    let v = read_vault(&svm, &vault);
    assert_eq!(v.owner, owner.pubkey());
    assert_eq!(v.banker, banker.pubkey());
    let a = svm.get_account(&vault).unwrap();
    assert_eq!(a.owner, program_id());
    assert!(a.lamports >= svm.minimum_balance_for_rent_exemption(VAULT_LEN));
}

#[test]
fn wrong_length_or_unknown_instructions_are_rejected() {
    let mut e = env();
    let owner = e.owner.pubkey();
    // a trailing byte on a known instruction
    let mut ix = cancel_ix(&owner, &owner);
    ix.data.push(0);
    fails(send(&mut e.svm, ix, &e.owner));
    // missing bytes
    let mut ix = bank_ix(&e.banker.pubkey(), &owner, 1000);
    ix.data.truncate(20);
    fails(send(&mut e.svm, ix, &e.banker));
    // an unknown selector, and an asset number that does not exist
    fails(send(&mut e.svm, ix_raw(vec![1, 2, 3, 4, 5, 6, 7, 8], vec![AccountMeta::new(e.vault, false)]), &e.owner));
    fails(send(&mut e.svm, request_ix(&owner, &owner, 9, 1000, &owner), &e.owner));
    // too few accounts
    let mut ix = bank_ix(&e.banker.pubkey(), &owner, 1000);
    ix.accounts.truncate(1);
    fails(send(&mut e.svm, ix, &e.banker));
}

#[test]
fn a_bank_cannot_be_routed_through_a_fake_system_program() {
    let mut e = env();
    let mut ix = bank_ix(&e.banker.pubkey(), &e.owner.pubkey(), 1000);
    ix.accounts[2] = AccountMeta::new_readonly(Pubkey::new_unique(), false);
    fails(send(&mut e.svm, ix, &e.banker));
    assert_eq!(read_vault(&e.svm, &e.vault).bank_count, 0);
}

#[test]
fn anyone_can_execute_an_unlocked_withdrawal_but_it_only_pays_the_requested_address() {
    let mut e = env();
    let owner = e.owner.pubkey();
    must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &owner, SOL), &e.banker));
    must(send(&mut e.svm, request_ix(&owner, &owner, SOL_ASSET, SOL / 2, &owner), &e.owner));
    set_time(&mut e.svm, NOW + COOLDOWN);
    let stranger = funded(&mut e.svm);
    fails_with(send(&mut e.svm, execute_sol_ix(&owner, &stranger.pubkey()), &stranger), "BadDestination");
    let before = e.svm.get_balance(&owner).unwrap();
    must(send(&mut e.svm, execute_sol_ix(&owner, &owner), &stranger));
    assert_eq!(e.svm.get_balance(&owner).unwrap() - before, SOL / 2);
    // and it cannot be executed a second time
    fails_with(send(&mut e.svm, execute_sol_ix(&owner, &owner), &stranger), "NothingPending");
    assert_eq!(read_vault(&e.svm, &e.vault).total_withdrawn, SOL / 2);
}

#[test]
fn the_vault_bytes_match_the_published_layout() {
    let e = env();
    let v = read_vault(&e.svm, &e.vault);
    assert_eq!(v.owner, e.owner.pubkey());
    assert_eq!(v.banker, e.banker.pubkey());
    assert_eq!(v.cooldown_secs, COOLDOWN);
    assert_eq!(v.bank_bps, 5000);
    assert_eq!(Pubkey::create_program_address(&[b"vault", e.owner.pubkey().as_ref(), &[v.bump]], &program_id()).unwrap(), e.vault);
    assert_eq!((v.total_banked, v.total_withdrawn, v.bank_count, v.withdraw_amount), (0, 0, 0, 0));
}

/// Mainnet and devnet publish rent as a rate with a multiplier of 1 (read live on 2026-10-10:
/// 5,080 lamports per byte). The simulator's default is the older form (half the rate, times 2).
/// The vault must be created rent exempt, and keep its floor, under both.
#[test]
fn rent_is_right_under_the_live_cluster_format() {
    let mut svm = new_svm();
    let mut data = 5080u64.to_le_bytes().to_vec();
    data.extend_from_slice(&1.0f64.to_le_bytes());
    data.push(50);
    let rent_sysvar = pk("SysvarRent111111111111111111111111111111111");
    let existing = svm.get_account(&rent_sysvar).unwrap();
    svm.set_account(rent_sysvar, Account { data, ..existing }).unwrap();

    let owner = funded(&mut svm);
    let banker = funded(&mut svm);
    must(send(&mut svm, init_ix(&owner.pubkey(), &banker.pubkey(), COOLDOWN, 5000), &owner));
    let vault = vault_pda(&owner.pubkey());
    // (173 + 128) * 5080: the balance of the real vault opened from the phone on devnet
    assert_eq!(svm.get_balance(&vault).unwrap(), 1_529_080);

    must(send(&mut svm, bank_ix(&banker.pubkey(), &owner.pubkey(), SOL / 100), &banker));
    must(send(&mut svm, request_ix(&owner.pubkey(), &owner.pubkey(), SOL_ASSET, SOL / 100 + 1, &owner.pubkey()), &owner));
    set_time(&mut svm, NOW + COOLDOWN);
    fails_with(send(&mut svm, execute_sol_ix(&owner.pubkey(), &owner.pubkey()), &owner), "NotEnough");
    must(send(&mut svm, cancel_ix(&owner.pubkey(), &owner.pubkey()), &owner));
    must(send(&mut svm, request_ix(&owner.pubkey(), &owner.pubkey(), SOL_ASSET, SOL / 100, &owner.pubkey()), &owner));
    set_time(&mut svm, NOW + 2 * COOLDOWN);
    must(send(&mut svm, execute_sol_ix(&owner.pubkey(), &owner.pubkey()), &owner));
    assert_eq!(svm.get_balance(&vault).unwrap(), 1_529_080, "exactly the rent floor is left");
}
