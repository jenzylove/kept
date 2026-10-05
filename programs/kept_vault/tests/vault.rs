//! Program tests (LiteSVM). Build first: `anchor build --arch v0`, then `cargo test -p kept_vault`.
use {
    anchor_lang::{
        prelude::Pubkey,
        solana_program::{clock::Clock, instruction::Instruction, system_program},
        AccountDeserialize, InstructionData, ToAccountMetas,
    },
    kept_vault::{accounts, instruction, Asset, Vault},
    litesvm::{
        types::{FailedTransactionMetadata, TransactionMetadata},
        LiteSVM,
    },
    solana_keypair::Keypair,
    solana_message::{Message, VersionedMessage},
    solana_signer::Signer,
    solana_transaction::versioned::VersionedTransaction,
};

const SOL: u64 = 1_000_000_000;
const NOW: i64 = 1_800_000_000;
const COOLDOWN: i64 = 3600;
const FEE: u64 = 5000;

type Res = Result<TransactionMetadata, FailedTransactionMetadata>;

struct Env {
    svm: LiteSVM,
    owner: Keypair,
    banker: Keypair,
    vault: Pubkey,
}

fn vault_pda(owner: &Pubkey) -> Pubkey {
    Pubkey::find_program_address(&[b"vault", owner.as_ref()], &kept_vault::id()).0
}

fn new_svm() -> LiteSVM {
    let mut svm = LiteSVM::new();
    let bytes = include_bytes!(concat!(env!("CARGO_TARGET_TMPDIR"), "/../deploy/kept_vault.so"));
    svm.add_program(kept_vault::id(), bytes).unwrap();
    set_time(&mut svm, NOW);
    svm
}

fn set_time(svm: &mut LiteSVM, unix: i64) {
    let mut c = svm.get_sysvar::<Clock>();
    c.unix_timestamp = unix;
    svm.set_sysvar::<Clock>(&c);
}

fn send(svm: &mut LiteSVM, ix: Instruction, payer: &Keypair) -> Res {
    svm.expire_blockhash();
    let msg = Message::new_with_blockhash(&[ix], Some(&payer.pubkey()), &svm.latest_blockhash());
    let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(msg), &[payer]).unwrap();
    svm.send_transaction(tx)
}

fn must(r: Res) -> TransactionMetadata {
    match r {
        Ok(m) => m,
        Err(e) => panic!("tx failed: {:?}\n{}", e.err, e.meta.logs.join("\n")),
    }
}

fn fails_with(r: Res, code: &str) {
    match r {
        Ok(_) => panic!("expected failure with {code}, but the transaction succeeded"),
        Err(e) => assert!(
            e.meta.logs.iter().any(|l| l.contains(&format!("Error Code: {code}"))),
            "expected {code}, got {:?}\n{}",
            e.err,
            e.meta.logs.join("\n")
        ),
    }
}

fn fails(r: Res) {
    assert!(r.is_err(), "expected the transaction to fail");
}

fn init_ix(owner: &Pubkey, banker: &Pubkey, cooldown: i64, bps: u16) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::InitVault { banker: *banker, cooldown_secs: cooldown, bank_bps: bps }.data(),
        accounts::InitVault { owner: *owner, vault: vault_pda(owner), system_program: system_program::ID }.to_account_metas(None),
    )
}

fn bank_ix(banker: &Pubkey, vault_owner: &Pubkey, amount: u64) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::Bank { amount, profit: amount * 2, sell_sig: [7u8; 64] }.data(),
        accounts::Bank { banker: *banker, vault: vault_pda(vault_owner), system_program: system_program::ID }.to_account_metas(None),
    )
}

fn request_ix(signer: &Pubkey, vault_owner: &Pubkey, asset: Asset, amount: u64, to: &Pubkey) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::RequestWithdraw { asset, amount, to: *to }.data(),
        accounts::OwnerOnly { owner: *signer, vault: vault_pda(vault_owner) }.to_account_metas(None),
    )
}

fn cancel_ix(signer: &Pubkey, vault_owner: &Pubkey) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::CancelWithdraw {}.data(),
        accounts::OwnerOnly { owner: *signer, vault: vault_pda(vault_owner) }.to_account_metas(None),
    )
}

fn execute_sol_ix(vault_owner: &Pubkey, to: &Pubkey) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::ExecuteWithdrawSol {}.data(),
        accounts::ExecuteSol { vault: vault_pda(vault_owner), to: *to }.to_account_metas(None),
    )
}

fn rules_ix(signer: &Pubkey, vault_owner: &Pubkey, cooldown: i64, bps: u16, banker: &Pubkey) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::SetRules { cooldown_secs: cooldown, bank_bps: bps, banker: *banker }.data(),
        accounts::OwnerOnly { owner: *signer, vault: vault_pda(vault_owner) }.to_account_metas(None),
    )
}

fn read_vault(svm: &LiteSVM, vault: &Pubkey) -> Vault {
    let a = svm.get_account(vault).expect("vault exists");
    let mut data: &[u8] = &a.data;
    Vault::try_deserialize(&mut data).unwrap()
}

fn env() -> Env {
    let mut svm = new_svm();
    let owner = Keypair::new();
    let banker = Keypair::new();
    svm.airdrop(&owner.pubkey(), 10 * SOL).unwrap();
    svm.airdrop(&banker.pubkey(), 10 * SOL).unwrap();
    must(send(&mut svm, init_ix(&owner.pubkey(), &banker.pubkey(), COOLDOWN, 5000), &owner));
    let vault = vault_pda(&owner.pubkey());
    Env { svm, owner, banker, vault }
}

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
    must(send(&mut e.svm, request_ix(&owner, &owner, Asset::Sol, SOL / 2, &owner), &e.owner));

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
    must(send(&mut e.svm, request_ix(&owner, &owner, Asset::Sol, SOL / 2, &owner), &e.owner));
    fails_with(send(&mut e.svm, request_ix(&owner, &owner, Asset::Sol, 1, &owner), &e.owner), "WithdrawPending");
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
    fails(send(&mut e.svm, request_ix(&banker, &owner, Asset::Sol, 1000, &banker), &e.banker));
    fails(send(&mut e.svm, request_ix(&stranger.pubkey(), &owner, Asset::Sol, 1000, &stranger.pubkey()), &stranger));
    assert_eq!(read_vault(&e.svm, &e.vault).withdraw_amount, 0);
}

#[test]
fn withdrawals_can_only_go_to_the_owner_or_the_trading_wallet() {
    let mut e = env();
    let owner = e.owner.pubkey();
    let dest = Keypair::new().pubkey();
    fails_with(send(&mut e.svm, request_ix(&owner, &owner, Asset::Sol, 1000, &dest), &e.owner), "BadDestination");
    must(send(&mut e.svm, request_ix(&owner, &owner, Asset::Sol, 1000, &e.banker.pubkey()), &e.owner));
}

#[test]
fn a_withdrawal_cannot_dig_into_the_rent_the_vault_needs() {
    let mut e = env();
    let owner = e.owner.pubkey();
    must(send(&mut e.svm, bank_ix(&e.banker.pubkey(), &owner, SOL / 100), &e.banker));
    must(send(&mut e.svm, request_ix(&owner, &owner, Asset::Sol, SOL, &owner), &e.owner));
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

    must(send(&mut e.svm, request_ix(&owner, &owner, Asset::Sol, SOL / 2, &owner), &e.owner));
    assert_eq!(read_vault(&e.svm, &e.vault).withdraw_unlock_at, NOW + COOLDOWN, "still waits the old cooldown");
    must(send(&mut e.svm, cancel_ix(&owner, &owner), &e.owner));

    // after the old cooldown has passed, the shorter one applies
    set_time(&mut e.svm, NOW + COOLDOWN + 1);
    must(send(&mut e.svm, request_ix(&owner, &owner, Asset::Sol, SOL / 2, &owner), &e.owner));
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
