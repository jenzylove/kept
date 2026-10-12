# Security

## What is protected, and from whom

| Asset | Protected by | Not protected from |
|---|---|---|
| Money in the vault | The vault program: only the owner can ask to take money out, and only after the wait the owner chose | The owner, by design. They can withdraw after the wait |
| The trading key | An Android Keystore key that cannot be exported. The sealed file is useless off the phone | Someone holding the unlocked phone, who can reveal it in Settings after the fingerprint or PIN prompt |
| The vault's SKR | The program owns the stake. It unstakes only through the same wait | The owner, after the wait |

## What the trading key can do

The reflex signs with the trading key without asking. The program accepts a `bank` instruction only from that key and it can only move SOL into the vault. It cannot withdraw, change rules, or send money anywhere else. The key can also move the money in the trading wallet, because that wallet is the user's own.

## Who can change the program

The vault program on mainnet is upgradeable. Its upgrade authority is one key (`EzB5Svn5CmdafRdT3m7ugDe9ck2gKoaMkEcjp4gV2dFy`), held offline by the builder and kept outside this repository.

## What the server can do

The site holds an RPC key and nothing else. It forwards an allowlist of read and send methods and cannot sign anything. It never sees a key.

## Reporting

Open a private security advisory on this repository. Please do not post details publicly first.
