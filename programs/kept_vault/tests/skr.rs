//! The SKR path, against the real SKR staking program (dumped from mainnet into tests/fixtures).
//! Regenerate fixtures with `node scripts/dump-skr-fixtures.mjs`.
use {
    anchor_lang::{
        prelude::Pubkey,
        solana_program::{
            clock::Clock,
            instruction::{AccountMeta, Instruction},
            program_pack::Pack,
            system_program,
        },
        AccountDeserialize, InstructionData, ToAccountMetas,
    },
    anchor_spl::{
        associated_token::get_associated_token_address,
        token::spl_token::{self, state::AccountState},
    },
    base64::{engine::general_purpose::STANDARD as B64, Engine},
    kept_vault::{accounts, instruction, Asset, Vault},
    litesvm::{
        types::{FailedTransactionMetadata, TransactionMetadata},
        LiteSVM,
    },
    solana_account::Account,
    solana_keypair::Keypair,
    solana_message::{Message, VersionedMessage},
    solana_signer::Signer,
    solana_transaction::versioned::VersionedTransaction,
    std::str::FromStr,
};

const SKR_PROGRAM: &str = "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ";
const NOW: i64 = 1_800_000_000;
const COOLDOWN: i64 = 3600;
const SKR_UNSTAKE_PERIOD: i64 = 172_800;
const STAKE_DISC: [u8; 8] = [206, 176, 202, 18, 200, 209, 179, 108];
const WITHDRAW_DISC: [u8; 8] = [183, 18, 70, 156, 148, 109, 161, 34];

type Res = Result<TransactionMetadata, FailedTransactionMetadata>;

fn pk(s: &str) -> Pubkey {
    Pubkey::from_str(s).unwrap()
}

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

fn set_time(svm: &mut LiteSVM, unix: i64) {
    let mut c = svm.get_sysvar::<Clock>();
    c.unix_timestamp = unix;
    svm.set_sysvar::<Clock>(&c);
}

fn send(svm: &mut LiteSVM, ixs: &[Instruction], payer: &Keypair) -> Res {
    svm.expire_blockhash();
    let msg = Message::new_with_blockhash(ixs, Some(&payer.pubkey()), &svm.latest_blockhash());
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
        Ok(_) => panic!("expected {code}, but the transaction succeeded"),
        Err(e) => assert!(
            e.meta.logs.iter().any(|l| l.contains(&format!("Error Code: {code}"))),
            "expected {code}, got {:?}\n{}",
            e.err,
            e.meta.logs.join("\n")
        ),
    }
}

fn token_account(svm: &mut LiteSVM, address: Pubkey, mint: Pubkey, owner: Pubkey, amount: u64) {
    let mut data = vec![0u8; spl_token::state::Account::LEN];
    spl_token::state::Account::pack(
        spl_token::state::Account { mint, owner, amount, state: AccountState::Initialized, ..Default::default() },
        &mut data,
    )
    .unwrap();
    let lamports = svm.minimum_balance_for_rent_exemption(data.len());
    svm.set_account(address, Account { lamports, data, owner: spl_token::id(), executable: false, rent_epoch: 0 }).unwrap();
}

fn token_balance(svm: &LiteSVM, address: &Pubkey) -> u64 {
    spl_token::state::Account::unpack(&svm.get_account(address).unwrap().data).unwrap().amount
}

fn vault_pda(owner: &Pubkey) -> Pubkey {
    Pubkey::find_program_address(&[b"vault", owner.as_ref()], &kept_vault::id()).0
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

fn read_vault(svm: &LiteSVM, vault: &Pubkey) -> Vault {
    let a = svm.get_account(vault).unwrap();
    let mut data: &[u8] = &a.data;
    Vault::try_deserialize(&mut data).unwrap()
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
            AccountMeta::new_readonly(spl_token::id(), false),
            AccountMeta::new_readonly(system_program::ID, false),
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
            AccountMeta::new_readonly(spl_token::id(), false),
            AccountMeta::new_readonly(skr.event_authority, false),
            AccountMeta::new_readonly(skr.program, false),
        ],
        data: WITHDRAW_DISC.to_vec(),
    }
}

fn init_ix(owner: &Pubkey, banker: &Pubkey) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::InitVault { banker: *banker, cooldown_secs: COOLDOWN, bank_bps: 5000 }.data(),
        accounts::InitVault { owner: *owner, vault: vault_pda(owner), system_program: system_program::ID }.to_account_metas(None),
    )
}

fn request_ix(owner: &Pubkey, asset: Asset, amount: u64, to: &Pubkey) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::RequestWithdraw { asset, amount, to: *to }.data(),
        accounts::OwnerOnly { owner: *owner, vault: vault_pda(owner) }.to_account_metas(None),
    )
}

fn unstake_ix(skr: &Skr, vault_owner: &Pubkey) -> Instruction {
    let vault = vault_pda(vault_owner);
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::ExecuteUnstakeSkr {}.data(),
        accounts::ExecuteUnstakeSkr {
            vault,
            user_stake: user_stake_pda(skr, &vault),
            stake_config: skr.config,
            guardian_pool: skr.pool,
            stake_vault: skr.stake_vault,
            mint: skr.mint,
            event_authority: skr.event_authority,
            skr_program: skr.program,
        }
        .to_account_metas(None),
    )
}

fn release_ix(owner: &Pubkey, vault_skr: &Pubkey, owner_skr: &Pubkey, amount: u64) -> Instruction {
    Instruction::new_with_bytes(
        kept_vault::id(),
        &instruction::ReleaseSkr { amount }.data(),
        accounts::ReleaseSkr {
            owner: *owner,
            vault: vault_pda(owner),
            vault_skr: *vault_skr,
            owner_skr: *owner_skr,
            token_program: spl_token::id(),
        }
        .to_account_metas(None),
    )
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
    svm.add_program(kept_vault::id(), include_bytes!(concat!(env!("CARGO_TARGET_TMPDIR"), "/../deploy/kept_vault.so"))).unwrap();
    let skr = load_skr(&mut svm);
    set_time(&mut svm, NOW);
    let owner = Keypair::new();
    let banker = Keypair::new();
    svm.airdrop(&owner.pubkey(), 10_000_000_000).unwrap();
    svm.airdrop(&banker.pubkey(), 10_000_000_000).unwrap();
    must(send(&mut svm, &[init_ix(&owner.pubkey(), &banker.pubkey())], &owner));
    let vault = vault_pda(&owner.pubkey());
    let ata = |wallet: &Pubkey| get_associated_token_address(wallet, &skr.mint);
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
    must(send(&mut e.svm, &[stake], &e.banker));
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
    assert!(send(&mut e.svm, &[direct], &e.owner).is_err(), "direct unstake must fail");
    assert_eq!(read_shares(&e.svm, &stake_account), shares, "shares untouched");

    // 3. Through the vault: ask, wait the vault's cooldown, then unstake.
    let want = u64::try_from(shares).expect("shares fit in u64 for this stake");
    must(send(&mut e.svm, &[request_ix(&owner, Asset::StakedSkr, want, &owner)], &e.owner));
    fails_with(send(&mut e.svm, &[unstake_ix(&e.skr, &owner)], &e.owner), "StillCooling");
    set_time(&mut e.svm, NOW + COOLDOWN);
    must(send(&mut e.svm, &[unstake_ix(&e.skr, &owner)], &e.owner));
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
    must(send(&mut e.svm, &[skr_withdraw_ix(&e.skr, &e.vault, &e.vault_skr)], &stranger));
    let landed = token_balance(&e.svm, &e.vault_skr);
    // the SKR program converts through shares and rounds down: at most one base unit (0.000001 SKR) is lost
    assert!(landed + 1 >= 2_000_000 && landed <= 2_000_000, "the vault received its SKR back: {landed}");

    // 6. ...and only the owner can release it to themselves.
    let thief = Keypair::new();
    e.svm.airdrop(&thief.pubkey(), 1_000_000_000).unwrap();
    assert!(send(&mut e.svm, &[release_ix(&thief.pubkey(), &e.vault_skr, &e.owner_skr, landed)], &thief).is_err());
    must(send(&mut e.svm, &[release_ix(&owner, &e.vault_skr, &e.owner_skr, landed)], &e.owner));
    assert_eq!(token_balance(&e.svm, &e.owner_skr), landed);
    assert!(!read_vault(&e.svm, &e.vault).skr_unstake_approved, "approval is single use");
}
