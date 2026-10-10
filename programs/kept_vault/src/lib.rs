//! Kept vault: money in is instant, money out waits out a cooldown the owner chose.
//!
//! - The owner (main wallet, Seed Vault on Seeker) creates the vault and is the only one who can
//!   ask to take money out.
//! - The banker (the trading wallet's key, held on the phone) can only put money in.
//! - Lowering the cooldown only takes effect after the current cooldown, so it can't be skipped.
//! - Banked SKR is staked with the SKR staking program in the vault's name; only this program can
//!   unstake it, and only through the same cooldown.
//!
//! Written without a framework so the deployed program is small. The instruction and account
//! byte layouts, the events and the error numbers are the ones in `idl.json`, so clients built
//! against that file work unchanged. Every check a framework would generate is spelled out here:
//! who signed, who owns each account, and that the vault address is the one derived for its owner.

#![cfg_attr(target_os = "solana", no_std)]

use pinocchio::{
    cpi::{invoke_signed, Seed, Signer},
    error::ProgramError,
    instruction::{InstructionAccount, InstructionView},
    sysvars::{clock::Clock, Sysvar},
    AccountView, Address, ProgramResult,
};
use pinocchio_system::instructions::{Allocate, Assign, CreateAccount, Transfer};

pub const ID: Address = Address::from_str_const("EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK");

pub const VAULT_SEED: &[u8] = b"vault";
pub const MIN_COOLDOWN: i64 = 60; // one minute floor (lets a demo show the full cycle)
pub const MAX_COOLDOWN: i64 = 30 * 24 * 60 * 60; // 30 days
pub const SKR_STAKING: Address = Address::from_str_const("SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ");
pub const SKR_MINT: Address = Address::from_str_const("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3");
pub const TOKEN_PROGRAM: Address = Address::from_str_const("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
const SYSTEM_PROGRAM: Address = Address::from_str_const("11111111111111111111111111111111");
const SKR_UNSTAKE_DISC: [u8; 8] = [90, 95, 107, 42, 205, 124, 50, 225];

// Instruction selectors: sha256("global:<name>")[..8], as in idl.json.
const IX_INIT_VAULT: [u8; 8] = [77, 79, 85, 150, 33, 217, 52, 106];
const IX_BANK: [u8; 8] = [52, 72, 158, 163, 169, 184, 2, 6];
const IX_REQUEST_WITHDRAW: [u8; 8] = [137, 95, 187, 96, 250, 138, 31, 182];
const IX_CANCEL_WITHDRAW: [u8; 8] = [112, 53, 226, 58, 158, 30, 37, 168];
const IX_EXECUTE_WITHDRAW_SOL: [u8; 8] = [131, 194, 1, 209, 239, 229, 0, 218];
const IX_EXECUTE_UNSTAKE_SKR: [u8; 8] = [162, 31, 91, 12, 62, 41, 91, 198];
const IX_RELEASE_SKR: [u8; 8] = [148, 56, 88, 16, 3, 191, 136, 249];
const IX_SET_RULES: [u8; 8] = [66, 148, 196, 43, 232, 210, 174, 169];

// Account and event tags: sha256("account:Vault")[..8] and sha256("event:<Name>")[..8].
const ACCOUNT_VAULT: [u8; 8] = [211, 8, 232, 43, 2, 152, 117, 119];
const EV_VAULT_OPENED: [u8; 8] = [198, 250, 195, 25, 26, 107, 197, 16];
const EV_BANKED: [u8; 8] = [197, 50, 53, 56, 15, 127, 170, 67];
const EV_WITHDRAW_REQUESTED: [u8; 8] = [114, 16, 240, 206, 93, 128, 151, 39];
const EV_WITHDRAW_CANCELLED: [u8; 8] = [162, 153, 181, 47, 154, 132, 183, 117];
const EV_WITHDRAW_EXECUTED: [u8; 8] = [132, 197, 103, 54, 18, 141, 117, 0];
const EV_RULES_CHANGED: [u8; 8] = [246, 127, 52, 2, 91, 27, 175, 157];

/// Errors, numbered as in idl.json (6000 + position).
#[derive(Clone, Copy)]
#[repr(u32)]
pub enum KeptError {
    BadCooldown = 6000,
    BadShare,
    ZeroAmount,
    NotBanker,
    NotOwner,
    WithdrawPending,
    NothingPending,
    StillCooling,
    BadDestination,
    NotEnough,
    Overflow,
    /// The account passed as the vault is not this owner's vault.
    BadVault,
    /// A token account is not the SKR account it has to be.
    BadTokenAccount,
}

impl KeptError {
    fn name(self) -> &'static str {
        match self {
            KeptError::BadCooldown => "Error Code: BadCooldown.",
            KeptError::BadShare => "Error Code: BadShare.",
            KeptError::ZeroAmount => "Error Code: ZeroAmount.",
            KeptError::NotBanker => "Error Code: NotBanker.",
            KeptError::NotOwner => "Error Code: NotOwner.",
            KeptError::WithdrawPending => "Error Code: WithdrawPending.",
            KeptError::NothingPending => "Error Code: NothingPending.",
            KeptError::StillCooling => "Error Code: StillCooling.",
            KeptError::BadDestination => "Error Code: BadDestination.",
            KeptError::NotEnough => "Error Code: NotEnough.",
            KeptError::Overflow => "Error Code: Overflow.",
            KeptError::BadVault => "Error Code: BadVault.",
            KeptError::BadTokenAccount => "Error Code: BadTokenAccount.",
        }
    }
}

/// Log the error's name (clients and tests read it) and turn it into a program error.
fn fail(e: KeptError) -> ProgramError {
    log(e.name());
    ProgramError::Custom(e as u32)
}

fn log(message: &str) {
    #[cfg(target_os = "solana")]
    unsafe {
        pinocchio::syscalls::sol_log_(message.as_ptr(), message.len() as u64);
    }
    #[cfg(not(target_os = "solana"))]
    let _ = message;
}

/// Emit an event as a `Program data:` log line (the same form clients already decode).
fn emit(event: &[u8]) {
    #[cfg(target_os = "solana")]
    unsafe {
        let fields: [&[u8]; 1] = [event];
        pinocchio::syscalls::sol_log_data(fields.as_ptr() as *const u8, 1);
    }
    #[cfg(not(target_os = "solana"))]
    let _ = event;
}

#[derive(Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Asset {
    Sol = 0,
    Skr = 1,
    StakedSkr = 2,
}

impl Asset {
    fn from_u8(v: u8) -> Result<Self, ProgramError> {
        match v {
            0 => Ok(Asset::Sol),
            1 => Ok(Asset::Skr),
            2 => Ok(Asset::StakedSkr),
            _ => Err(ProgramError::InvalidInstructionData),
        }
    }
}

/// The vault account. On chain it is `ACCOUNT_VAULT` followed by these fields in this order,
/// little endian, 173 bytes in all.
#[derive(Clone, Copy)]
pub struct Vault {
    pub owner: Address,
    pub banker: Address,
    pub bump: u8,
    pub cooldown_secs: i64,
    pub pending_cooldown_secs: i64,
    pub pending_cooldown_at: i64,
    pub bank_bps: u16,
    pub total_banked: u64,
    pub total_withdrawn: u64,
    pub bank_count: u64,
    pub withdraw_asset: Asset,
    pub withdraw_amount: u64,
    pub withdraw_to: Address,
    pub withdraw_unlock_at: i64,
    pub skr_unstake_approved: bool,
}

impl Vault {
    pub const LEN: usize = 8 + 32 + 32 + 1 + 8 + 8 + 8 + 2 + 8 + 8 + 8 + 1 + 8 + 32 + 8 + 1;

    fn unpack(d: &[u8]) -> Result<Self, ProgramError> {
        if d.len() != Self::LEN || d[..8] != ACCOUNT_VAULT {
            return Err(fail(KeptError::BadVault));
        }
        let mut r = Reader { d, at: 8 };
        Ok(Vault {
            owner: r.address(),
            banker: r.address(),
            bump: r.u8(),
            cooldown_secs: r.i64(),
            pending_cooldown_secs: r.i64(),
            pending_cooldown_at: r.i64(),
            bank_bps: r.u16(),
            total_banked: r.u64(),
            total_withdrawn: r.u64(),
            bank_count: r.u64(),
            withdraw_asset: Asset::from_u8(r.u8()).map_err(|_| ProgramError::InvalidAccountData)?,
            withdraw_amount: r.u64(),
            withdraw_to: r.address(),
            withdraw_unlock_at: r.i64(),
            skr_unstake_approved: r.u8() != 0,
        })
    }

    fn pack(&self, d: &mut [u8]) {
        let mut w = Writer { d, at: 0 };
        w.bytes(&ACCOUNT_VAULT);
        w.bytes(self.owner.as_array());
        w.bytes(self.banker.as_array());
        w.bytes(&[self.bump]);
        w.bytes(&self.cooldown_secs.to_le_bytes());
        w.bytes(&self.pending_cooldown_secs.to_le_bytes());
        w.bytes(&self.pending_cooldown_at.to_le_bytes());
        w.bytes(&self.bank_bps.to_le_bytes());
        w.bytes(&self.total_banked.to_le_bytes());
        w.bytes(&self.total_withdrawn.to_le_bytes());
        w.bytes(&self.bank_count.to_le_bytes());
        w.bytes(&[self.withdraw_asset as u8]);
        w.bytes(&self.withdraw_amount.to_le_bytes());
        w.bytes(self.withdraw_to.as_array());
        w.bytes(&self.withdraw_unlock_at.to_le_bytes());
        w.bytes(&[self.skr_unstake_approved as u8]);
    }

    /// A shorter cooldown asked for earlier becomes the cooldown once its date has passed.
    fn settle_cooldown(&mut self, now: i64) {
        if self.pending_cooldown_at != 0 && now >= self.pending_cooldown_at {
            self.cooldown_secs = self.pending_cooldown_secs;
            self.pending_cooldown_secs = 0;
            self.pending_cooldown_at = 0;
        }
    }

    fn clear_withdraw(&mut self) {
        self.withdraw_asset = Asset::Sol;
        self.withdraw_amount = 0;
        self.withdraw_to = Address::new_from_array([0u8; 32]);
        self.withdraw_unlock_at = 0;
    }
}

struct Reader<'a> {
    d: &'a [u8],
    at: usize,
}

impl Reader<'_> {
    fn take<const N: usize>(&mut self) -> [u8; N] {
        let mut out = [0u8; N];
        out.copy_from_slice(&self.d[self.at..self.at + N]);
        self.at += N;
        out
    }
    fn u8(&mut self) -> u8 {
        self.take::<1>()[0]
    }
    fn u16(&mut self) -> u16 {
        u16::from_le_bytes(self.take())
    }
    fn u64(&mut self) -> u64 {
        u64::from_le_bytes(self.take())
    }
    fn i64(&mut self) -> i64 {
        i64::from_le_bytes(self.take())
    }
    fn address(&mut self) -> Address {
        Address::new_from_array(self.take())
    }
}

struct Writer<'a> {
    d: &'a mut [u8],
    at: usize,
}

impl Writer<'_> {
    fn bytes(&mut self, b: &[u8]) {
        self.d[self.at..self.at + b.len()].copy_from_slice(b);
        self.at += b.len();
    }
}

/// Instruction arguments must be exactly the expected length: no missing and no trailing bytes.
fn args<const N: usize>(data: &[u8]) -> Result<[u8; N], ProgramError> {
    if data.len() != N {
        return Err(ProgramError::InvalidInstructionData);
    }
    let mut out = [0u8; N];
    out.copy_from_slice(data);
    Ok(out)
}

fn check_cooldown(c: i64) -> ProgramResult {
    if !(MIN_COOLDOWN..=MAX_COOLDOWN).contains(&c) {
        return Err(fail(KeptError::BadCooldown));
    }
    Ok(())
}

fn check_share(bps: u16) -> ProgramResult {
    if bps == 0 || bps > 10_000 {
        return Err(fail(KeptError::BadShare));
    }
    Ok(())
}

#[cfg_attr(not(target_os = "solana"), allow(dead_code))]
const RENT_SYSVAR: Address = Address::from_str_const("SysvarRent111111111111111111111111111111111");

/// Lamports an account of `data_len` bytes must hold to stay alive.
///
/// Read from the raw rent sysvar: a rate per byte, then a multiplier stored as a float. Today's
/// clusters publish the rate with a multiplier of 1; older ones published half the rate with a
/// multiplier of 2. Both give the same answer here, and any other multiplier is honoured too.
fn rent_floor(data_len: usize) -> Result<u64, ProgramError> {
    #[cfg_attr(not(target_os = "solana"), allow(unused_mut))]
    let mut raw = [0u8; 16];
    #[cfg(target_os = "solana")]
    {
        let result = unsafe { pinocchio::syscalls::sol_get_sysvar(RENT_SYSVAR.as_array().as_ptr(), raw.as_mut_ptr(), 0, 16) };
        if result != 0 {
            return Err(ProgramError::UnsupportedSysvar);
        }
    }
    let per_byte = u64::from_le_bytes(raw[0..8].try_into().unwrap());
    let multiplier_bits = u64::from_le_bytes(raw[8..16].try_into().unwrap());
    let base = (data_len as u64)
        .checked_add(128) // per account storage overhead
        .and_then(|bytes| bytes.checked_mul(per_byte))
        .ok_or(ProgramError::ArithmeticOverflow)?;
    const ONE: u64 = 0x3FF0_0000_0000_0000; // 1.0
    const TWO: u64 = 0x4000_0000_0000_0000; // 2.0
    match multiplier_bits {
        ONE => Ok(base),
        TWO => base.checked_mul(2).ok_or(ProgramError::ArithmeticOverflow),
        other => {
            let m = f64::from_bits(other);
            if !(m.is_finite() && m > 0.0) {
                return Err(ProgramError::UnsupportedSysvar);
            }
            Ok((base as f64 * m) as u64)
        }
    }
}

fn now() -> Result<i64, ProgramError> {
    Ok(Clock::get()?.unix_timestamp)
}

/// Load a vault and prove it is genuine: owned by this program, tagged as a vault, and sitting
/// at the address derived from its own owner and bump.
fn load_vault(account: &AccountView) -> Result<Vault, ProgramError> {
    if !account.owned_by(&ID) {
        return Err(fail(KeptError::BadVault));
    }
    let vault = {
        let data = account.try_borrow()?;
        Vault::unpack(&data)?
    };
    let derived = Address::create_program_address(&[VAULT_SEED, vault.owner.as_ref(), &[vault.bump]], &ID)
        .map_err(|_| fail(KeptError::BadVault))?;
    if &derived != account.address() {
        return Err(fail(KeptError::BadVault));
    }
    Ok(vault)
}

fn store_vault(account: &mut AccountView, vault: &Vault) -> ProgramResult {
    if !account.is_writable() {
        return Err(ProgramError::Immutable);
    }
    let mut data = account.try_borrow_mut()?;
    vault.pack(&mut data);
    Ok(())
}

/// The two accounts every owner action takes, with the owner proven: they signed, and the vault
/// names them as owner.
fn owner_and_vault(accounts: &mut [AccountView]) -> Result<(&AccountView, &mut AccountView, Vault), ProgramError> {
    let [owner, vault_account, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let vault = load_vault(vault_account)?;
    if &vault.owner != owner.address() {
        return Err(fail(KeptError::NotOwner));
    }
    if !owner.is_signer() {
        return Err(ProgramError::MissingRequiredSignature);
    }
    Ok((owner, vault_account, vault))
}

pinocchio::program_entrypoint!(process_instruction);
pinocchio::default_allocator!();
pinocchio::nostd_panic_handler!();

pub fn process_instruction(program_id: &Address, accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    if program_id != &ID {
        return Err(ProgramError::IncorrectProgramId);
    }
    if data.len() < 8 {
        return Err(ProgramError::InvalidInstructionData);
    }
    let (selector, rest) = data.split_at(8);
    let mut tag = [0u8; 8];
    tag.copy_from_slice(selector);
    match tag {
        IX_INIT_VAULT => init_vault(accounts, rest),
        IX_BANK => bank(accounts, rest),
        IX_REQUEST_WITHDRAW => request_withdraw(accounts, rest),
        IX_CANCEL_WITHDRAW => cancel_withdraw(accounts, rest),
        IX_EXECUTE_WITHDRAW_SOL => execute_withdraw_sol(accounts, rest),
        IX_EXECUTE_UNSTAKE_SKR => execute_unstake_skr(accounts, rest),
        IX_RELEASE_SKR => release_skr(accounts, rest),
        IX_SET_RULES => set_rules(accounts, rest),
        _ => Err(ProgramError::InvalidInstructionData),
    }
}

/// Owner opens their vault. Accounts: owner (signer, writable), vault (writable), system program.
fn init_vault(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    log("Instruction: InitVault");
    let a: [u8; 42] = args(data)?;
    let banker = Address::new_from_array(a[0..32].try_into().unwrap());
    let cooldown_secs = i64::from_le_bytes(a[32..40].try_into().unwrap());
    let bank_bps = u16::from_le_bytes(a[40..42].try_into().unwrap());

    let [owner, vault_account, system_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    if !owner.is_signer() {
        return Err(ProgramError::MissingRequiredSignature);
    }
    if system_program.address() != &SYSTEM_PROGRAM {
        return Err(ProgramError::IncorrectProgramId);
    }
    check_cooldown(cooldown_secs)?;
    check_share(bank_bps)?;

    let (expected, bump) = Address::find_program_address(&[VAULT_SEED, owner.address().as_ref()], &ID);
    if &expected != vault_account.address() {
        return Err(fail(KeptError::BadVault));
    }
    // One vault per owner, created once: the address must still be an empty system account.
    if !vault_account.owned_by(&SYSTEM_PROGRAM) || !vault_account.is_data_empty() {
        return Err(ProgramError::AccountAlreadyInitialized);
    }

    let bump_seed = [bump];
    let seeds = [Seed::from(VAULT_SEED), Seed::from(owner.address().as_ref()), Seed::from(&bump_seed)];
    let signer = [Signer::from(&seeds)];
    let rent = rent_floor(Vault::LEN)?;
    if vault_account.lamports() == 0 {
        CreateAccount { from: owner, to: vault_account, lamports: rent, space: Vault::LEN as u64, owner: &ID }
            .invoke_signed(&signer)?;
    } else {
        // Someone sent lamports to the address first. Creating would fail, so top up, size and
        // claim it instead; otherwise a stranger could block an owner from ever opening a vault.
        let have = vault_account.lamports();
        if have < rent {
            Transfer { from: owner, to: vault_account, lamports: rent - have }.invoke()?;
        }
        Allocate { account: vault_account, space: Vault::LEN as u64 }.invoke_signed(&signer)?;
        Assign { account: vault_account, owner: &ID }.invoke_signed(&signer)?;
    }

    let vault = Vault {
        owner: Address::new_from_array(*owner.address().as_array()),
        banker,
        bump,
        cooldown_secs,
        pending_cooldown_secs: 0,
        pending_cooldown_at: 0,
        bank_bps,
        total_banked: 0,
        total_withdrawn: 0,
        bank_count: 0,
        withdraw_asset: Asset::Sol,
        withdraw_amount: 0,
        withdraw_to: Address::new_from_array([0u8; 32]),
        withdraw_unlock_at: 0,
        skr_unstake_approved: false,
    };
    let vault_key = *vault_account.address().as_array();
    store_vault(vault_account, &vault)?;

    let mut e = [0u8; 8 + 32 + 32 + 32 + 8 + 2];
    let mut w = Writer { d: &mut e, at: 0 };
    w.bytes(&EV_VAULT_OPENED);
    w.bytes(&vault_key);
    w.bytes(vault.owner.as_array());
    w.bytes(vault.banker.as_array());
    w.bytes(&cooldown_secs.to_le_bytes());
    w.bytes(&bank_bps.to_le_bytes());
    emit(&e);
    Ok(())
}

/// Banker moves SOL from the trading wallet into the vault. `sell_sig` ties the bank to the sell.
/// Accounts: banker (signer, writable), vault (writable), system program.
fn bank(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    log("Instruction: Bank");
    let a: [u8; 80] = args(data)?;
    let amount = u64::from_le_bytes(a[0..8].try_into().unwrap());
    let profit = u64::from_le_bytes(a[8..16].try_into().unwrap());

    let [banker, vault_account, system_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut vault = load_vault(vault_account)?;
    if &vault.banker != banker.address() {
        return Err(fail(KeptError::NotBanker));
    }
    if !banker.is_signer() {
        return Err(ProgramError::MissingRequiredSignature);
    }
    if system_program.address() != &SYSTEM_PROGRAM {
        return Err(ProgramError::IncorrectProgramId);
    }
    if amount == 0 {
        return Err(fail(KeptError::ZeroAmount));
    }

    Transfer { from: banker, to: vault_account, lamports: amount }.invoke()?;

    vault.total_banked = vault.total_banked.checked_add(amount).ok_or_else(|| fail(KeptError::Overflow))?;
    vault.bank_count = vault.bank_count.checked_add(1).ok_or_else(|| fail(KeptError::Overflow))?;
    let vault_key = *vault_account.address().as_array();
    store_vault(vault_account, &vault)?;

    let mut e = [0u8; 8 + 32 + 8 + 8 + 64 + 8 + 8];
    let mut w = Writer { d: &mut e, at: 0 };
    w.bytes(&EV_BANKED);
    w.bytes(&vault_key);
    w.bytes(&amount.to_le_bytes());
    w.bytes(&profit.to_le_bytes());
    w.bytes(&a[16..80]);
    w.bytes(&vault.total_banked.to_le_bytes());
    w.bytes(&vault.bank_count.to_le_bytes());
    emit(&e);
    Ok(())
}

/// Owner asks to take money out. Nothing moves until the cooldown has passed.
/// Accounts: owner (signer), vault (writable).
fn request_withdraw(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    log("Instruction: RequestWithdraw");
    let a: [u8; 41] = args(data)?;
    let asset = Asset::from_u8(a[0])?;
    let amount = u64::from_le_bytes(a[1..9].try_into().unwrap());
    let to = Address::new_from_array(a[9..41].try_into().unwrap());

    let (_owner, vault_account, mut vault) = owner_and_vault(accounts)?;
    if amount == 0 {
        return Err(fail(KeptError::ZeroAmount));
    }
    let now = now()?;
    vault.settle_cooldown(now);
    if vault.withdraw_amount != 0 {
        return Err(fail(KeptError::WithdrawPending));
    }
    if to != vault.owner && to != vault.banker {
        return Err(fail(KeptError::BadDestination));
    }
    vault.withdraw_asset = asset;
    vault.withdraw_amount = amount;
    vault.withdraw_to = to;
    vault.withdraw_unlock_at = now.checked_add(vault.cooldown_secs).ok_or_else(|| fail(KeptError::Overflow))?;
    let vault_key = *vault_account.address().as_array();
    store_vault(vault_account, &vault)?;

    let mut e = [0u8; 8 + 32 + 1 + 8 + 32 + 8];
    let mut w = Writer { d: &mut e, at: 0 };
    w.bytes(&EV_WITHDRAW_REQUESTED);
    w.bytes(&vault_key);
    w.bytes(&[asset as u8]);
    w.bytes(&amount.to_le_bytes());
    w.bytes(vault.withdraw_to.as_array());
    w.bytes(&vault.withdraw_unlock_at.to_le_bytes());
    emit(&e);
    Ok(())
}

/// Accounts: owner (signer), vault (writable).
fn cancel_withdraw(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    log("Instruction: CancelWithdraw");
    let _: [u8; 0] = args(data)?;
    let (_owner, vault_account, mut vault) = owner_and_vault(accounts)?;
    if vault.withdraw_amount == 0 {
        return Err(fail(KeptError::NothingPending));
    }
    let amount = vault.withdraw_amount;
    vault.clear_withdraw();
    let vault_key = *vault_account.address().as_array();
    store_vault(vault_account, &vault)?;

    let mut e = [0u8; 8 + 32 + 8];
    let mut w = Writer { d: &mut e, at: 0 };
    w.bytes(&EV_WITHDRAW_CANCELLED);
    w.bytes(&vault_key);
    w.bytes(&amount.to_le_bytes());
    emit(&e);
    Ok(())
}

fn emit_executed(vault_key: &[u8; 32], asset: Asset, amount: u64, to: &Address) {
    let mut e = [0u8; 8 + 32 + 1 + 8 + 32];
    let mut w = Writer { d: &mut e, at: 0 };
    w.bytes(&EV_WITHDRAW_EXECUTED);
    w.bytes(vault_key);
    w.bytes(&[asset as u8]);
    w.bytes(&amount.to_le_bytes());
    w.bytes(to.as_array());
    emit(&e);
}

/// After the cooldown, anyone can execute a SOL withdrawal; it can only pay the requested address.
/// Accounts: vault (writable), to (writable).
fn execute_withdraw_sol(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    log("Instruction: ExecuteWithdrawSol");
    let _: [u8; 0] = args(data)?;
    let [vault_account, to, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut vault = load_vault(vault_account)?;
    if vault.withdraw_amount == 0 || vault.withdraw_asset != Asset::Sol {
        return Err(fail(KeptError::NothingPending));
    }
    if now()? < vault.withdraw_unlock_at {
        return Err(fail(KeptError::StillCooling));
    }
    if to.address() != &vault.withdraw_to {
        return Err(fail(KeptError::BadDestination));
    }
    if !vault_account.is_writable() || !to.is_writable() {
        return Err(ProgramError::Immutable);
    }

    // The vault must stay rent exempt, so only what sits above that floor can leave.
    let amount = vault.withdraw_amount;
    let floor = rent_floor(vault_account.data_len())?;
    let available = vault_account.lamports().saturating_sub(floor);
    if amount > available {
        return Err(fail(KeptError::NotEnough));
    }
    let credited = to.lamports().checked_add(amount).ok_or_else(|| fail(KeptError::Overflow))?;
    vault_account.set_lamports(vault_account.lamports() - amount);
    to.set_lamports(credited);

    vault.total_withdrawn = vault.total_withdrawn.checked_add(amount).ok_or_else(|| fail(KeptError::Overflow))?;
    let paid_to = vault.withdraw_to;
    vault.clear_withdraw();
    let vault_key = *vault_account.address().as_array();
    store_vault(vault_account, &vault)?;
    emit_executed(&vault_key, Asset::Sol, amount, &paid_to);
    Ok(())
}

/// After the cooldown, unstake the vault's SKR shares (the SKR program then runs its own unstake
/// period). The tokens that come back can be released to the owner with `release_skr`.
/// Accounts: vault (writable), user_stake (writable), stake_config (writable), guardian_pool
/// (writable), stake_vault, mint, event_authority, skr_program.
fn execute_unstake_skr(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    log("Instruction: ExecuteUnstakeSkr");
    let _: [u8; 0] = args(data)?;
    let [vault_account, user_stake, stake_config, guardian_pool, stake_vault, mint, event_authority, skr_program, ..] =
        accounts
    else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut vault = load_vault(vault_account)?;
    if vault.withdraw_amount == 0 || vault.withdraw_asset != Asset::StakedSkr {
        return Err(fail(KeptError::NothingPending));
    }
    if now()? < vault.withdraw_unlock_at {
        return Err(fail(KeptError::StillCooling));
    }
    if mint.address() != &SKR_MINT || skr_program.address() != &SKR_STAKING {
        return Err(ProgramError::IncorrectProgramId);
    }

    let shares = vault.withdraw_amount as u128;
    let mut ix_data = [0u8; 24];
    ix_data[..8].copy_from_slice(&SKR_UNSTAKE_DISC);
    ix_data[8..].copy_from_slice(&shares.to_le_bytes());
    let metas = [
        InstructionAccount::writable(user_stake.address()),
        InstructionAccount::writable(stake_config.address()),
        InstructionAccount::writable(guardian_pool.address()),
        InstructionAccount::readonly_signer(vault_account.address()),
        InstructionAccount::readonly(stake_vault.address()),
        InstructionAccount::readonly(mint.address()),
        InstructionAccount::readonly(event_authority.address()),
        InstructionAccount::readonly(skr_program.address()),
    ];
    let instruction = InstructionView { program_id: &SKR_STAKING, accounts: &metas, data: &ix_data };
    let bump_seed = [vault.bump];
    let seeds = [Seed::from(VAULT_SEED), Seed::from(vault.owner.as_ref()), Seed::from(&bump_seed)];
    invoke_signed(
        &instruction,
        &[&*user_stake, &*stake_config, &*guardian_pool, &*vault_account, &*stake_vault, &*mint, &*event_authority, &*skr_program],
        &[Signer::from(&seeds)],
    )?;

    let amount = vault.withdraw_amount;
    let to = vault.withdraw_to;
    vault.skr_unstake_approved = true;
    vault.clear_withdraw();
    let vault_key = *vault_account.address().as_array();
    store_vault(vault_account, &vault)?;
    emit_executed(&vault_key, Asset::StakedSkr, amount, &to);
    Ok(())
}

/// A token account's mint and owner, after proving it is a real SPL Token account.
fn token_account(account: &AccountView) -> Result<(Address, Address), ProgramError> {
    if !account.owned_by(&TOKEN_PROGRAM) || account.data_len() != 165 {
        return Err(fail(KeptError::BadTokenAccount));
    }
    let data = account.try_borrow()?;
    if data[108] != 1 {
        // state: 1 = initialized (2 = frozen, 0 = uninitialized)
        return Err(fail(KeptError::BadTokenAccount));
    }
    let mut r = Reader { d: &data, at: 0 };
    Ok((r.address(), r.address()))
}

/// Release SKR sitting in the vault's token account to the owner. Allowed only for SKR that came
/// out through an approved unstake, or a liquid SKR withdrawal whose cooldown has passed.
/// Accounts: owner (signer), vault (writable), vault_skr (writable), owner_skr (writable), token program.
fn release_skr(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    log("Instruction: ReleaseSkr");
    let a: [u8; 8] = args(data)?;
    let amount = u64::from_le_bytes(a);

    let [owner, vault_account, vault_skr, owner_skr, token_program, ..] = accounts else {
        return Err(ProgramError::NotEnoughAccountKeys);
    };
    let mut vault = load_vault(vault_account)?;
    if &vault.owner != owner.address() {
        return Err(fail(KeptError::NotOwner));
    }
    if !owner.is_signer() {
        return Err(ProgramError::MissingRequiredSignature);
    }
    if token_program.address() != &TOKEN_PROGRAM {
        return Err(ProgramError::IncorrectProgramId);
    }
    let (from_mint, from_owner) = token_account(vault_skr)?;
    let (to_mint, to_owner) = token_account(owner_skr)?;
    if from_mint != SKR_MINT || &from_owner != vault_account.address() || to_mint != SKR_MINT || &to_owner != owner.address() {
        return Err(fail(KeptError::BadTokenAccount));
    }

    let liquid_ok = vault.withdraw_asset == Asset::Skr
        && amount > 0
        && vault.withdraw_amount >= amount
        && now()? >= vault.withdraw_unlock_at;
    if !(vault.skr_unstake_approved || liquid_ok) {
        return Err(fail(KeptError::StillCooling));
    }

    let bump_seed = [vault.bump];
    let seeds = [Seed::from(VAULT_SEED), Seed::from(vault.owner.as_ref()), Seed::from(&bump_seed)];
    pinocchio_token::instructions::Transfer::new(vault_skr, owner_skr, vault_account, amount)
        .invoke_signed(&[Signer::from(&seeds)])?;

    if liquid_ok && !vault.skr_unstake_approved {
        vault.clear_withdraw();
    }
    vault.skr_unstake_approved = false;
    let vault_key = *vault_account.address().as_array();
    let to = vault.owner;
    store_vault(vault_account, &vault)?;
    emit_executed(&vault_key, Asset::Skr, amount, &to);
    Ok(())
}

/// Owner changes rules. A longer cooldown applies now; a shorter one only after the current
/// cooldown has passed, so it can't be used to skip the wait.
/// Accounts: owner (signer), vault (writable).
fn set_rules(accounts: &mut [AccountView], data: &[u8]) -> ProgramResult {
    log("Instruction: SetRules");
    let a: [u8; 42] = args(data)?;
    let cooldown_secs = i64::from_le_bytes(a[0..8].try_into().unwrap());
    let bank_bps = u16::from_le_bytes(a[8..10].try_into().unwrap());
    let banker = Address::new_from_array(a[10..42].try_into().unwrap());

    let (_owner, vault_account, mut vault) = owner_and_vault(accounts)?;
    check_cooldown(cooldown_secs)?;
    check_share(bank_bps)?;
    let now = now()?;
    vault.settle_cooldown(now);
    if cooldown_secs >= vault.cooldown_secs {
        vault.cooldown_secs = cooldown_secs;
        vault.pending_cooldown_secs = 0;
        vault.pending_cooldown_at = 0;
    } else {
        vault.pending_cooldown_secs = cooldown_secs;
        vault.pending_cooldown_at = now.checked_add(vault.cooldown_secs).ok_or_else(|| fail(KeptError::Overflow))?;
    }
    vault.bank_bps = bank_bps;
    vault.banker = banker;
    let vault_key = *vault_account.address().as_array();
    store_vault(vault_account, &vault)?;

    let mut e = [0u8; 8 + 32 + 8 + 8 + 8 + 2 + 32];
    let mut w = Writer { d: &mut e, at: 0 };
    w.bytes(&EV_RULES_CHANGED);
    w.bytes(&vault_key);
    w.bytes(&vault.cooldown_secs.to_le_bytes());
    w.bytes(&vault.pending_cooldown_secs.to_le_bytes());
    w.bytes(&vault.pending_cooldown_at.to_le_bytes());
    w.bytes(&bank_bps.to_le_bytes());
    w.bytes(vault.banker.as_array());
    emit(&e);
    Ok(())
}
