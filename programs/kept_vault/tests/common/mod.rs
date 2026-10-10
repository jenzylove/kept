//! Shared test helpers. Instructions are assembled by hand from the byte layouts in idl.json, so
//! these tests exercise the program exactly the way a real client does.
#![allow(dead_code)]

pub use {
    litesvm::{
        types::{FailedTransactionMetadata, TransactionMetadata},
        LiteSVM,
    },
    solana_account::Account,
    solana_clock::Clock,
    solana_instruction::{AccountMeta, Instruction},
    solana_keypair::Keypair,
    solana_message::{Message, VersionedMessage},
    solana_pubkey::Pubkey,
    solana_signer::Signer,
    solana_transaction::versioned::VersionedTransaction,
};
use sha2::{Digest, Sha256};
use std::str::FromStr;

pub const SOL: u64 = 1_000_000_000;
pub const NOW: i64 = 1_800_000_000;
pub const COOLDOWN: i64 = 3600;
pub const FEE: u64 = 5000;
pub const SOL_ASSET: u8 = 0;
pub const SKR_ASSET: u8 = 1;
pub const STAKED_SKR_ASSET: u8 = 2;
pub const VAULT_LEN: usize = 173;

pub type Res = Result<TransactionMetadata, FailedTransactionMetadata>;

pub fn pk(s: &str) -> Pubkey {
    Pubkey::from_str(s).unwrap()
}

pub fn program_id() -> Pubkey {
    pk("EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK")
}

pub fn system_program() -> Pubkey {
    pk("11111111111111111111111111111111")
}

pub fn token_program() -> Pubkey {
    pk("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
}

pub fn vault_pda(owner: &Pubkey) -> Pubkey {
    Pubkey::find_program_address(&[b"vault", owner.as_ref()], &program_id()).0
}

/// sha256("<namespace>:<name>")[..8], the selector scheme in idl.json.
pub fn disc(namespace: &str, name: &str) -> [u8; 8] {
    let h = Sha256::digest(format!("{namespace}:{name}").as_bytes());
    h[..8].try_into().unwrap()
}

pub fn ix(name: &str, args: &[u8], accounts: Vec<AccountMeta>) -> Instruction {
    let mut data = disc("global", name).to_vec();
    data.extend_from_slice(args);
    Instruction { program_id: program_id(), accounts, data }
}

pub fn ix_raw(data: Vec<u8>, accounts: Vec<AccountMeta>) -> Instruction {
    Instruction { program_id: program_id(), accounts, data }
}

pub fn new_svm() -> LiteSVM {
    let mut svm = LiteSVM::new();
    let bytes = include_bytes!(concat!(env!("CARGO_TARGET_TMPDIR"), "/../deploy/kept_vault.so"));
    svm.add_program(program_id(), bytes).unwrap();
    set_time(&mut svm, NOW);
    svm
}

pub fn set_time(svm: &mut LiteSVM, unix: i64) {
    let mut c = svm.get_sysvar::<Clock>();
    c.unix_timestamp = unix;
    svm.set_sysvar::<Clock>(&c);
}

pub fn send_all(svm: &mut LiteSVM, ixs: &[Instruction], payer: &Keypair, signers: &[&Keypair]) -> Res {
    svm.expire_blockhash();
    let msg = Message::new_with_blockhash(ixs, Some(&payer.pubkey()), &svm.latest_blockhash());
    let tx = VersionedTransaction::try_new(VersionedMessage::Legacy(msg), signers).unwrap();
    svm.send_transaction(tx)
}

pub fn send(svm: &mut LiteSVM, ix: Instruction, payer: &Keypair) -> Res {
    send_all(svm, &[ix], payer, &[payer])
}

pub fn must(r: Res) -> TransactionMetadata {
    match r {
        Ok(m) => m,
        Err(e) => panic!("tx failed: {:?}\n{}", e.err, e.meta.logs.join("\n")),
    }
}

pub fn fails_with(r: Res, code: &str) {
    match r {
        Ok(_) => panic!("expected failure with {code}, but the transaction succeeded"),
        Err(e) => assert!(
            e.meta.logs.iter().any(|l| l.contains(&format!("Error Code: {code}."))),
            "expected {code}, got {:?}\n{}",
            e.err,
            e.meta.logs.join("\n")
        ),
    }
}

pub fn fails(r: Res) {
    assert!(r.is_err(), "expected the transaction to fail");
}

pub fn init_ix(owner: &Pubkey, banker: &Pubkey, cooldown: i64, bps: u16) -> Instruction {
    let mut a = banker.to_bytes().to_vec();
    a.extend_from_slice(&cooldown.to_le_bytes());
    a.extend_from_slice(&bps.to_le_bytes());
    ix("init_vault", &a, vec![
        AccountMeta::new(*owner, true),
        AccountMeta::new(vault_pda(owner), false),
        AccountMeta::new_readonly(system_program(), false),
    ])
}

pub fn bank_ix(banker: &Pubkey, vault_owner: &Pubkey, amount: u64) -> Instruction {
    bank_ix_to(banker, &vault_pda(vault_owner), amount)
}

pub fn bank_ix_to(banker: &Pubkey, vault: &Pubkey, amount: u64) -> Instruction {
    let mut a = amount.to_le_bytes().to_vec();
    a.extend_from_slice(&(amount * 2).to_le_bytes());
    a.extend_from_slice(&[7u8; 64]);
    ix("bank", &a, vec![
        AccountMeta::new(*banker, true),
        AccountMeta::new(*vault, false),
        AccountMeta::new_readonly(system_program(), false),
    ])
}

pub fn request_ix(signer: &Pubkey, vault_owner: &Pubkey, asset: u8, amount: u64, to: &Pubkey) -> Instruction {
    let mut a = vec![asset];
    a.extend_from_slice(&amount.to_le_bytes());
    a.extend_from_slice(to.as_ref());
    ix("request_withdraw", &a, vec![AccountMeta::new_readonly(*signer, true), AccountMeta::new(vault_pda(vault_owner), false)])
}

pub fn cancel_ix(signer: &Pubkey, vault_owner: &Pubkey) -> Instruction {
    ix("cancel_withdraw", &[], vec![AccountMeta::new_readonly(*signer, true), AccountMeta::new(vault_pda(vault_owner), false)])
}

pub fn execute_sol_ix(vault_owner: &Pubkey, to: &Pubkey) -> Instruction {
    ix("execute_withdraw_sol", &[], vec![AccountMeta::new(vault_pda(vault_owner), false), AccountMeta::new(*to, false)])
}

pub fn rules_ix(signer: &Pubkey, vault_owner: &Pubkey, cooldown: i64, bps: u16, banker: &Pubkey) -> Instruction {
    let mut a = cooldown.to_le_bytes().to_vec();
    a.extend_from_slice(&bps.to_le_bytes());
    a.extend_from_slice(banker.as_ref());
    ix("set_rules", &a, vec![AccountMeta::new_readonly(*signer, true), AccountMeta::new(vault_pda(vault_owner), false)])
}

/// The vault account decoded from its bytes (layout in the program's `Vault` doc comment).
#[derive(Debug)]
pub struct VaultView {
    pub owner: Pubkey,
    pub banker: Pubkey,
    pub bump: u8,
    pub cooldown_secs: i64,
    pub pending_cooldown_secs: i64,
    pub pending_cooldown_at: i64,
    pub bank_bps: u16,
    pub total_banked: u64,
    pub total_withdrawn: u64,
    pub bank_count: u64,
    pub withdraw_asset: u8,
    pub withdraw_amount: u64,
    pub withdraw_to: Pubkey,
    pub withdraw_unlock_at: i64,
    pub skr_unstake_approved: bool,
}

pub fn read_vault(svm: &LiteSVM, vault: &Pubkey) -> VaultView {
    let a = svm.get_account(vault).expect("vault exists");
    let d = &a.data;
    assert_eq!(d.len(), VAULT_LEN);
    assert_eq!(d[..8], disc("account", "Vault"));
    let key = |o: usize| Pubkey::new_from_array(d[o..o + 32].try_into().unwrap());
    let u64_at = |o: usize| u64::from_le_bytes(d[o..o + 8].try_into().unwrap());
    let i64_at = |o: usize| i64::from_le_bytes(d[o..o + 8].try_into().unwrap());
    VaultView {
        owner: key(8),
        banker: key(40),
        bump: d[72],
        cooldown_secs: i64_at(73),
        pending_cooldown_secs: i64_at(81),
        pending_cooldown_at: i64_at(89),
        bank_bps: u16::from_le_bytes(d[97..99].try_into().unwrap()),
        total_banked: u64_at(99),
        total_withdrawn: u64_at(107),
        bank_count: u64_at(115),
        withdraw_asset: d[123],
        withdraw_amount: u64_at(124),
        withdraw_to: key(132),
        withdraw_unlock_at: i64_at(164),
        skr_unstake_approved: d[172] != 0,
    }
}

pub struct Env {
    pub svm: LiteSVM,
    pub owner: Keypair,
    pub banker: Keypair,
    pub vault: Pubkey,
}

pub fn env() -> Env {
    let mut svm = new_svm();
    let owner = Keypair::new();
    let banker = Keypair::new();
    svm.airdrop(&owner.pubkey(), 10 * SOL).unwrap();
    svm.airdrop(&banker.pubkey(), 10 * SOL).unwrap();
    must(send(&mut svm, init_ix(&owner.pubkey(), &banker.pubkey(), COOLDOWN, 5000), &owner));
    let vault = vault_pda(&owner.pubkey());
    Env { svm, owner, banker, vault }
}

pub fn funded(svm: &mut LiteSVM) -> Keypair {
    let k = Keypair::new();
    svm.airdrop(&k.pubkey(), 10 * SOL).unwrap();
    k
}
