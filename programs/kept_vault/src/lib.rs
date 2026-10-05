//! Kept vault: money in is instant, money out waits out a cooldown the owner chose.
//!
//! - The owner (main wallet, Seed Vault on Seeker) creates the vault and is the only one who can
//!   ask to take money out.
//! - The banker (the trading wallet's key, held on the phone) can only put money in.
//! - Lowering the cooldown only takes effect after the current cooldown, so it can't be skipped.
//! - Banked SKR is staked with the SKR staking program in the vault's name; only this program can
//!   unstake it, and only through the same cooldown.

use anchor_lang::prelude::*;
use anchor_lang::solana_program::{
    instruction::{AccountMeta, Instruction},
    program::invoke_signed,
};
use anchor_lang::system_program;
use anchor_spl::token::{self, Token, TokenAccount, Transfer};

declare_id!("EWSuJmQAHsp5nHsgJzFuaeJxPvJQ9TpkQxN96TDhyFGK");

pub const VAULT_SEED: &[u8] = b"vault";
pub const MIN_COOLDOWN: i64 = 60; // one minute floor (lets a demo show the full cycle)
pub const MAX_COOLDOWN: i64 = 30 * 24 * 60 * 60; // 30 days
pub const SKR_STAKING: Pubkey = pubkey!("SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ");
pub const SKR_MINT: Pubkey = pubkey!("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3");
const SKR_UNSTAKE_DISC: [u8; 8] = [90, 95, 107, 42, 205, 124, 50, 225];

#[program]
pub mod kept_vault {
    use super::*;

    pub fn init_vault(ctx: Context<InitVault>, banker: Pubkey, cooldown_secs: i64, bank_bps: u16) -> Result<()> {
        check_cooldown(cooldown_secs)?;
        require!(bank_bps > 0 && bank_bps <= 10_000, KeptError::BadShare);
        let v = &mut ctx.accounts.vault;
        v.owner = ctx.accounts.owner.key();
        v.banker = banker;
        v.bump = ctx.bumps.vault;
        v.cooldown_secs = cooldown_secs;
        v.bank_bps = bank_bps;
        emit!(VaultOpened { vault: v.key(), owner: v.owner, banker, cooldown_secs, bank_bps });
        Ok(())
    }

    /// Banker moves SOL from the trading wallet into the vault. `sell_sig` ties the bank to the sell.
    pub fn bank(ctx: Context<Bank>, amount: u64, profit: u64, sell_sig: [u8; 64]) -> Result<()> {
        require!(amount > 0, KeptError::ZeroAmount);
        system_program::transfer(
            CpiContext::new(
                ctx.accounts.system_program.key(),
                system_program::Transfer {
                    from: ctx.accounts.banker.to_account_info(),
                    to: ctx.accounts.vault.to_account_info(),
                },
            ),
            amount,
        )?;
        let v = &mut ctx.accounts.vault;
        v.total_banked = v.total_banked.checked_add(amount).ok_or(KeptError::Overflow)?;
        v.bank_count += 1;
        emit!(Banked { vault: v.key(), amount, profit, sell_sig, total_banked: v.total_banked, bank_count: v.bank_count });
        Ok(())
    }

    /// Owner asks to take money out. Nothing moves until the cooldown has passed.
    pub fn request_withdraw(ctx: Context<OwnerOnly>, asset: Asset, amount: u64, to: Pubkey) -> Result<()> {
        require!(amount > 0, KeptError::ZeroAmount);
        let now = Clock::get()?.unix_timestamp;
        let v = &mut ctx.accounts.vault;
        v.settle_cooldown(now);
        require!(v.withdraw_amount == 0, KeptError::WithdrawPending);
        require!(to == v.owner || to == v.banker, KeptError::BadDestination);
        v.withdraw_asset = asset;
        v.withdraw_amount = amount;
        v.withdraw_to = to;
        v.withdraw_unlock_at = now + v.cooldown_secs;
        emit!(WithdrawRequested { vault: v.key(), asset, amount, to, unlock_at: v.withdraw_unlock_at });
        Ok(())
    }

    pub fn cancel_withdraw(ctx: Context<OwnerOnly>) -> Result<()> {
        let v = &mut ctx.accounts.vault;
        require!(v.withdraw_amount > 0, KeptError::NothingPending);
        emit!(WithdrawCancelled { vault: v.key(), amount: v.withdraw_amount });
        v.clear_withdraw();
        Ok(())
    }

    /// After the cooldown, anyone can execute a SOL withdrawal; it can only pay the requested address.
    pub fn execute_withdraw_sol(ctx: Context<ExecuteSol>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let v = &ctx.accounts.vault;
        require!(v.withdraw_amount > 0 && v.withdraw_asset == Asset::Sol, KeptError::NothingPending);
        require!(now >= v.withdraw_unlock_at, KeptError::StillCooling);
        require_keys_eq!(ctx.accounts.to.key(), v.withdraw_to, KeptError::BadDestination);

        let amount = v.withdraw_amount;
        let vault_info = ctx.accounts.vault.to_account_info();
        let rent_floor = Rent::get()?.minimum_balance(vault_info.data_len());
        let available = vault_info.lamports().saturating_sub(rent_floor);
        require!(amount <= available, KeptError::NotEnough);
        **vault_info.try_borrow_mut_lamports()? -= amount;
        **ctx.accounts.to.to_account_info().try_borrow_mut_lamports()? += amount;

        let v = &mut ctx.accounts.vault;
        v.total_withdrawn = v.total_withdrawn.checked_add(amount).ok_or(KeptError::Overflow)?;
        emit!(WithdrawExecuted { vault: v.key(), asset: Asset::Sol, amount, to: v.withdraw_to });
        v.clear_withdraw();
        Ok(())
    }

    /// After the cooldown, unstake the vault's SKR shares (the SKR program then runs its own unstake
    /// period). The tokens that come back can be released to the owner with `release_skr`.
    pub fn execute_unstake_skr(ctx: Context<ExecuteUnstakeSkr>) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let v = &ctx.accounts.vault;
        require!(v.withdraw_amount > 0 && v.withdraw_asset == Asset::StakedSkr, KeptError::NothingPending);
        require!(now >= v.withdraw_unlock_at, KeptError::StillCooling);
        let shares = v.withdraw_amount as u128;

        let mut data = SKR_UNSTAKE_DISC.to_vec();
        data.extend_from_slice(&shares.to_le_bytes());
        let ix = Instruction {
            program_id: SKR_STAKING,
            accounts: vec![
                AccountMeta::new(ctx.accounts.user_stake.key(), false),
                AccountMeta::new(ctx.accounts.stake_config.key(), false),
                AccountMeta::new(ctx.accounts.guardian_pool.key(), false),
                AccountMeta::new_readonly(ctx.accounts.vault.key(), true),
                AccountMeta::new_readonly(ctx.accounts.stake_vault.key(), false),
                AccountMeta::new_readonly(ctx.accounts.mint.key(), false),
                AccountMeta::new_readonly(ctx.accounts.event_authority.key(), false),
                AccountMeta::new_readonly(SKR_STAKING, false),
            ],
            data,
        };
        let owner = v.owner;
        let seeds: &[&[u8]] = &[VAULT_SEED, owner.as_ref(), &[v.bump]];
        invoke_signed(
            &ix,
            &[
                ctx.accounts.user_stake.to_account_info(),
                ctx.accounts.stake_config.to_account_info(),
                ctx.accounts.guardian_pool.to_account_info(),
                ctx.accounts.vault.to_account_info(),
                ctx.accounts.stake_vault.to_account_info(),
                ctx.accounts.mint.to_account_info(),
                ctx.accounts.event_authority.to_account_info(),
                ctx.accounts.skr_program.to_account_info(),
            ],
            &[seeds],
        )?;

        let v = &mut ctx.accounts.vault;
        v.skr_unstake_approved = true;
        emit!(WithdrawExecuted { vault: v.key(), asset: Asset::StakedSkr, amount: v.withdraw_amount, to: v.withdraw_to });
        v.clear_withdraw();
        Ok(())
    }

    /// Release SKR sitting in the vault's token account to the owner.
    /// Allowed only for SKR that came out through an approved unstake, or a liquid-SKR withdrawal
    /// whose cooldown has passed.
    pub fn release_skr(ctx: Context<ReleaseSkr>, amount: u64) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let v = &ctx.accounts.vault;
        let liquid_ok = v.withdraw_asset == Asset::Skr && v.withdraw_amount >= amount && amount > 0 && now >= v.withdraw_unlock_at;
        require!(v.skr_unstake_approved || liquid_ok, KeptError::StillCooling);

        let owner = v.owner;
        let seeds: &[&[u8]] = &[VAULT_SEED, owner.as_ref(), &[v.bump]];
        token::transfer(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.key(),
                Transfer {
                    from: ctx.accounts.vault_skr.to_account_info(),
                    to: ctx.accounts.owner_skr.to_account_info(),
                    authority: ctx.accounts.vault.to_account_info(),
                },
                &[seeds],
            ),
            amount,
        )?;
        let v = &mut ctx.accounts.vault;
        if liquid_ok && !v.skr_unstake_approved {
            v.clear_withdraw();
        }
        v.skr_unstake_approved = false;
        emit!(WithdrawExecuted { vault: v.key(), asset: Asset::Skr, amount, to: owner });
        Ok(())
    }

    /// Owner changes rules. A longer cooldown applies now; a shorter one only after the current
    /// cooldown has passed, so it can't be used to skip the wait.
    pub fn set_rules(ctx: Context<OwnerOnly>, cooldown_secs: i64, bank_bps: u16, banker: Pubkey) -> Result<()> {
        check_cooldown(cooldown_secs)?;
        require!(bank_bps > 0 && bank_bps <= 10_000, KeptError::BadShare);
        let now = Clock::get()?.unix_timestamp;
        let v = &mut ctx.accounts.vault;
        v.settle_cooldown(now);
        if cooldown_secs >= v.cooldown_secs {
            v.cooldown_secs = cooldown_secs;
            v.pending_cooldown_secs = 0;
            v.pending_cooldown_at = 0;
        } else {
            v.pending_cooldown_secs = cooldown_secs;
            v.pending_cooldown_at = now + v.cooldown_secs;
        }
        v.bank_bps = bank_bps;
        v.banker = banker;
        emit!(RulesChanged {
            vault: v.key(),
            cooldown_secs: v.cooldown_secs,
            pending_cooldown_secs: v.pending_cooldown_secs,
            pending_cooldown_at: v.pending_cooldown_at,
            bank_bps,
            banker,
        });
        Ok(())
    }
}

fn check_cooldown(c: i64) -> Result<()> {
    require!(c >= MIN_COOLDOWN && c <= MAX_COOLDOWN, KeptError::BadCooldown);
    Ok(())
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, Debug, InitSpace, Default)]
pub enum Asset {
    #[default]
    Sol,
    Skr,
    StakedSkr,
}

#[account]
#[derive(InitSpace)]
pub struct Vault {
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
    pub withdraw_asset: Asset,
    pub withdraw_amount: u64,
    pub withdraw_to: Pubkey,
    pub withdraw_unlock_at: i64,
    pub skr_unstake_approved: bool,
}

impl Vault {
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
        self.withdraw_to = Pubkey::default();
        self.withdraw_unlock_at = 0;
    }
}

#[derive(Accounts)]
pub struct InitVault<'info> {
    #[account(mut)]
    pub owner: Signer<'info>,
    #[account(init, payer = owner, space = 8 + Vault::INIT_SPACE, seeds = [VAULT_SEED, owner.key().as_ref()], bump)]
    pub vault: Account<'info, Vault>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Bank<'info> {
    #[account(mut)]
    pub banker: Signer<'info>,
    #[account(mut, seeds = [VAULT_SEED, vault.owner.as_ref()], bump = vault.bump, has_one = banker @ KeptError::NotBanker)]
    pub vault: Account<'info, Vault>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct OwnerOnly<'info> {
    pub owner: Signer<'info>,
    #[account(mut, seeds = [VAULT_SEED, owner.key().as_ref()], bump = vault.bump, has_one = owner @ KeptError::NotOwner)]
    pub vault: Account<'info, Vault>,
}

#[derive(Accounts)]
pub struct ExecuteSol<'info> {
    #[account(mut, seeds = [VAULT_SEED, vault.owner.as_ref()], bump = vault.bump)]
    pub vault: Account<'info, Vault>,
    /// CHECK: must equal vault.withdraw_to (checked in the handler); only receives lamports.
    #[account(mut)]
    pub to: UncheckedAccount<'info>,
}

#[derive(Accounts)]
pub struct ExecuteUnstakeSkr<'info> {
    #[account(mut, seeds = [VAULT_SEED, vault.owner.as_ref()], bump = vault.bump)]
    pub vault: Account<'info, Vault>,
    /// CHECK: SKR program validates (its PDA seeds include the vault as `user`).
    #[account(mut)]
    pub user_stake: UncheckedAccount<'info>,
    /// CHECK: validated by the SKR program.
    #[account(mut)]
    pub stake_config: UncheckedAccount<'info>,
    /// CHECK: validated by the SKR program.
    #[account(mut)]
    pub guardian_pool: UncheckedAccount<'info>,
    /// CHECK: validated by the SKR program.
    pub stake_vault: UncheckedAccount<'info>,
    /// CHECK: must be the SKR mint.
    #[account(address = SKR_MINT)]
    pub mint: UncheckedAccount<'info>,
    /// CHECK: SKR program event authority, validated by the SKR program.
    pub event_authority: UncheckedAccount<'info>,
    /// CHECK: must be the SKR staking program.
    #[account(address = SKR_STAKING)]
    pub skr_program: UncheckedAccount<'info>,
}

#[derive(Accounts)]
pub struct ReleaseSkr<'info> {
    pub owner: Signer<'info>,
    #[account(mut, seeds = [VAULT_SEED, owner.key().as_ref()], bump = vault.bump, has_one = owner @ KeptError::NotOwner)]
    pub vault: Account<'info, Vault>,
    #[account(mut, token::mint = SKR_MINT, token::authority = vault)]
    pub vault_skr: Account<'info, TokenAccount>,
    #[account(mut, token::mint = SKR_MINT, token::authority = owner)]
    pub owner_skr: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[event]
pub struct VaultOpened { pub vault: Pubkey, pub owner: Pubkey, pub banker: Pubkey, pub cooldown_secs: i64, pub bank_bps: u16 }
#[event]
pub struct Banked { pub vault: Pubkey, pub amount: u64, pub profit: u64, pub sell_sig: [u8; 64], pub total_banked: u64, pub bank_count: u64 }
#[event]
pub struct WithdrawRequested { pub vault: Pubkey, pub asset: Asset, pub amount: u64, pub to: Pubkey, pub unlock_at: i64 }
#[event]
pub struct WithdrawCancelled { pub vault: Pubkey, pub amount: u64 }
#[event]
pub struct WithdrawExecuted { pub vault: Pubkey, pub asset: Asset, pub amount: u64, pub to: Pubkey }
#[event]
pub struct RulesChanged { pub vault: Pubkey, pub cooldown_secs: i64, pub pending_cooldown_secs: i64, pub pending_cooldown_at: i64, pub bank_bps: u16, pub banker: Pubkey }

#[error_code]
pub enum KeptError {
    #[msg("Cooldown must be between 1 minute and 30 days")]
    BadCooldown,
    #[msg("Bank share must be between 0.01% and 100%")]
    BadShare,
    #[msg("Amount must be more than zero")]
    ZeroAmount,
    #[msg("Only the vault's banker can bank")]
    NotBanker,
    #[msg("Only the vault's owner can do this")]
    NotOwner,
    #[msg("A withdrawal is already pending; cancel it first")]
    WithdrawPending,
    #[msg("No withdrawal is pending")]
    NothingPending,
    #[msg("Still cooling down")]
    StillCooling,
    #[msg("Money can only go to the owner or the trading wallet")]
    BadDestination,
    #[msg("Not enough in the vault")]
    NotEnough,
    #[msg("Overflow")]
    Overflow,
}
