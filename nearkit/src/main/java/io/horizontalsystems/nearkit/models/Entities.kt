package io.horizontalsystems.nearkit.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.horizontalsystems.nearkit.network.AccountView
import java.math.BigInteger

/** On-chain state of the kit's account. A single row; [exists] is false until the account receives NEAR. */
@Entity
data class AccountState(
    @PrimaryKey val id: Int = 1,
    val exists: Boolean,
    /** Liquid balance, yoctoNEAR. */
    val amount: BigInteger,
    /** Validator stake locked on the account itself (delegation to pools is not included). */
    val locked: BigInteger,
    val storageUsage: Long,
    val hasContract: Boolean,
) {
    /**
     * Balance held for storage staking. Accounts using at most 770 bytes need none (NEP-448 zero
     * balance accounts); above that the whole usage is staked, and locked stake counts towards it.
     */
    val storageStaked: BigInteger
        get() = if (storageUsage <= ZERO_BALANCE_ACCOUNT_STORAGE_LIMIT) BigInteger.ZERO
        else BigInteger.valueOf(storageUsage).multiply(STORAGE_PRICE_PER_BYTE)

    /** What transfers can spend: liquid balance minus the storage stake not covered by [locked]. */
    val available: BigInteger
        get() = amount.subtract((storageStaked - locked).max(BigInteger.ZERO)).max(BigInteger.ZERO)

    companion object {
        const val ZERO_BALANCE_ACCOUNT_STORAGE_LIMIT = 770L
        /** 10^19 yoctoNEAR (0.00001 NEAR) per byte; unchanged since genesis. */
        val STORAGE_PRICE_PER_BYTE: BigInteger = BigInteger.TEN.pow(19)

        val EMPTY = AccountState(exists = false, amount = BigInteger.ZERO, locked = BigInteger.ZERO, storageUsage = 0, hasContract = false)

        internal fun of(view: AccountView?): AccountState = if (view == null) {
            EMPTY
        } else {
            AccountState(
                exists = true,
                amount = view.amount,
                locked = view.locked,
                storageUsage = view.storageUsage,
                hasContract = view.hasContract,
            )
        }
    }
}

/** A key on an account. Function-call keys can only call the contract methods they were made for. */
data class AccessKeyInfo(
    /** In `ed25519:<base58>` form. */
    val publicKey: String,
    val isFullAccess: Boolean,
)

/** Latest final block seen and the gas price in it. */
@Entity
data class ChainState(
    @PrimaryKey val id: Int = 1,
    val blockHeight: Long,
    val gasPrice: BigInteger,
)

/** A NEP-141 token balance of the kit's account. */
@Entity
data class FtBalance(
    @PrimaryKey val contractId: String,
    /** In the token's smallest unit; divide by 10^decimals from [FtMetadata]. */
    val balance: BigInteger,
)

/** `ft_metadata` of a token contract, cached because it never changes in practice. */
@Entity
data class FtMetadata(
    @PrimaryKey val contractId: String,
    val name: String,
    val symbol: String,
    val decimals: Int,
    val spec: String?,
)

@Entity
data class TransactionSyncState(
    @PrimaryKey val id: Int = 1,
    /** Highest block height of an indexed transaction already stored. */
    val lastSyncedBlockHeight: Long,
    val initialSyncDone: Boolean,
    /** Where the walk back through older history continues; null once it reached the first transaction. */
    val backfillResumeToken: String?,
)
