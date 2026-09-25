# near-android-kit

NEAR Protocol kit for Android, in the style of the other HorizontalSystems `*-kit-android`
libraries. It derives keys, syncs balance, tokens and history, and signs and submits
transactions without any third-party NEAR SDK.

## What it does

- **Keys.** Ed25519 at SLIP-10 `m/44'/397'/0'` from a BIP39 seed, the path near-seed-phrase
  (MyNearWallet, near-cli) and Trust Wallet use, so the same words give the same key everywhere.
  Ledger uses `44'/397'/0'/0'/1'`, a different key. `ed25519:…` secret keys import too.
- **Accounts.** The key's implicit account (64 hex characters) by default, or a named account
  (`alice.near`) the key controls. `NearKit.findAccounts` lists both, confirming each named
  account on chain.
- **Sync.** Latest block and gas price, `view_account`, token balances and history, polled over
  JSON-RPC with endpoint failover. The RPC has no per-account history or token list, so those
  come from the [FastNEAR](https://docs.fastnear.com) indexer.
- **Balance.** `availableBalance` is the balance minus storage staking. Accounts using up to
  770 bytes need none (NEP-448); above that the whole usage is staked at 10^19 yocto per byte.
- **Tokens.** NEP-141 balances, `ft_metadata`, and transfers that include the receiver's NEP-145
  storage deposit when it is not registered yet. History reads NEP-297 events and the
  plain-text logs of older contracts such as wrap.near.
- **Sending.** Transactions are Borsh-encoded, and the SHA-256 hash is signed with Ed25519.
  They are submitted with `send_tx` and tracked from pending to final. A pending send that no
  node knows once its block hash expires is marked failed.
- **Fees.** Two numbers from the runtime config:
  - `fee` is what the send finally costs.
  - `requiredBalance` is what the account must hold up front. Gas is bought at no less than
    `min_gas_purchase_price` (10× the usual gas price) and partly refunded.
  - A transfer to an implicit account always pays account-creation gas, and one that creates
    the account also pays the 0.007 NEAR creation charge.

## Usage

```kotlin
val seed = Mnemonic().toSeed(words)
val accounts = NearKit.findAccounts(NearKit.publicKey(seed), Network.MainNet)   // implicit first
val kit = NearKit.getInstance(context, NearWallet.Seed(seed, accounts.first()), Network.MainNet, walletId = "wallet-1")
kit.watchTokens(setOf("usdt.tether-token.near"))
kit.start()

kit.accountStateFlow.collect { state -> /* amount, available, storageUsage (yoctoNEAR) */ }
kit.ftBalancesFlow.collect { balances -> /* contractId, balance in the token's smallest unit */ }
kit.transactionsFlow.collect { changed -> /* new or updated Transaction records */ }

val estimate = kit.estimateNearTransfer(to)          // fee, requiredBalance
val max = kit.maxSendableNear(to)
val tx = kit.sendNear(to, NearAmount.toYocto(BigDecimal("1.5")))
val ftTx = kit.sendFt("usdt.tether-token.near", to, BigInteger("1000000"))
```

Watch-only: `NearWallet.WatchOnly("alice.near")`. Sends then throw `NearKit.WalletError.WatchOnly`.

History per asset: `kit.getTransactions(token = NearKit.TOKEN_NATIVE)` or
`kit.getTransactions(token = "usdt.tether-token.near")`, paged by the timestamp and hash of the
last item. A `Transaction` carries the account's NEAR movements (`nearTransfers`,
`nearNetChange`) and token movements (`ftTransfers`) found anywhere in its receipt tree.

For dApps and swaps: `sendTransaction(receiverId, actions)`, `signTransaction(...)`, `submit(signed)`,
and `Transaction.decode` / `SignedTransaction.decode` for Borsh payloads a dApp hands over.

## Layout

| Package | Contents |
|---|---|
| `crypto` | Base58, account id rules, Ed25519 public key |
| `borsh` | Borsh writer and reader |
| `transaction` | Actions, `Transaction`/`SignedTransaction` codec, `Signer`, `FeeCalculator`, `FtActions`, `TransactionSender` |
| `network` | JSON-RPC provider with failover, FastNEAR client, connectivity |
| `sync` | Timer, account/token syncer, history syncer, receipt-tree converter |
| `database` | Room storage, one database per wallet id |

## Tests

```
./gradlew :nearkit:testDebugUnitTest
```

Offline tests check the kit against reference output:
- Keys against `near-seed-phrase`.
- Transaction bytes, hashes and signatures against `@near-js/transactions` 2.5.1.
- The converter against real mainnet and testnet transactions from FastNEAR and the RPC.

The live tests run with `NEARKIT_INTEGRATION=true`:
- **`MainnetReadTest`:** every RPC endpoint, the token list, history and account discovery.
- **`TestnetIntegrationTest`:** creates a funded account through the testnet helper, sends NEAR to
  a new implicit account, checks that a stale nonce is refused, then wraps NEAR and sends the
  token with a storage deposit. It then finds all of it in the index.
- **`NearKitRobolectricTest`:** the whole kit with Room and the sync timer: a watch-only mainnet
  sync, and a testnet send tracked from pending to final to indexed.

The `app` module is a sample. You can restore a mnemonic or secret key, pick an account, create a
funded `.testnet` account, send NEAR or a token, and see history.

## Not yet covered

NEP-413 message signing (WalletConnect `near_signMessage`), staking pools, NEP-245 multi-tokens
(NEAR Intents balances), NFTs, delegate actions (meta transactions), and Ledger's derivation path.
