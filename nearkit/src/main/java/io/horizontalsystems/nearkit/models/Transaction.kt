package io.horizontalsystems.nearkit.models

import androidx.room.Entity
import androidx.room.Index
import java.math.BigInteger

/**
 * A transaction touching the kit's account. NEAR executes a transaction as a tree of receipts,
 * possibly across blocks and shards; [nearTransfers] and [ftTransfers] are the value movements
 * of the kit's account found anywhere in that tree, not just the top-level actions.
 */
@Entity(indices = [Index("timestamp")], primaryKeys = ["hash"])
data class Transaction(
    val hash: String,
    /** Block the transaction was included in; null while pending or when only known from the RPC. */
    val blockHeight: Long?,
    /** Unix seconds. For pending transactions this is the submission time. */
    val timestamp: Long,
    val signerId: String,
    val receiverId: String,
    val actions: List<TxAction>,
    val nearTransfers: List<NearTransfer>,
    val ftTransfers: List<FtTransfer>,
    /** yoctoNEAR burnt as gas by the whole receipt tree, paid by [signerId]. Null while pending. */
    val fee: BigInteger?,
    val status: Status,
    /** Failure detail from the outcome (e.g. `{"ActionError":…}`), or `expired`. */
    val failure: String?,
    /** Pending only: once the chain passes this height the transaction can no longer be included. */
    val expiresAfterHeight: Long?,
) {
    enum class Status { Pending, Success, Failed }

    val isPending: Boolean get() = status == Status.Pending
    val isSuccess: Boolean get() = status == Status.Success
    val isFailed: Boolean get() = status == Status.Failed

    fun isSigner(accountId: String): Boolean = signerId == accountId

    /** Net native NEAR change of [accountId] from successful movements, fee excluded. */
    fun nearNetChange(accountId: String): BigInteger = nearTransfers.filter { it.success }.fold(BigInteger.ZERO) { acc, t ->
        when (accountId) {
            t.to -> if (t.from == accountId) acc else acc + t.amount
            t.from -> acc - t.amount
            else -> acc
        }
    }

    /**
     * The NEAR [accountId] gained or lost as a payment: [nearNetChange] without the NEAR that only
     * accompanies a call. That is the 1 yoctoNEAR contracts demand as proof of a full-access key
     * (NEP-141 `ft_*`, NEP-245, intents), the storage deposit that registers a receiver next to a
     * token transfer, and the part of that deposit the contract sends back. It decides whether a
     * transaction belongs to NEAR's history and the NEAR amount shown for it.
     */
    fun nearMoved(accountId: String): BigInteger {
        val sendsToken = signerId == accountId && actions.any { isFtMethod(it.methodName) }
        val accompanying = { t: NearTransfer ->
            t.kind == NearTransfer.Kind.FunctionCallDeposit && t.from == accountId &&
                (t.amount == BigInteger.ONE || isFtMethod(t.methodName) || (sendsToken && t.methodName == STORAGE_DEPOSIT))
        }

        val successful = nearTransfers.filter { it.success }
        // what each contract may still send back of the registration deposits it got
        val refundable = mutableMapOf<String, BigInteger>()
        successful.filter { accompanying(it) && it.methodName == STORAGE_DEPOSIT }.forEach { t ->
            refundable[t.to] = (refundable[t.to] ?: BigInteger.ZERO) + t.amount
        }

        return successful.fold(BigInteger.ZERO) { acc, t ->
            val refund = t.to == accountId && t.kind == NearTransfer.Kind.Transfer &&
                (refundable[t.from] ?: BigInteger.ZERO) >= t.amount
            when {
                accompanying(t) -> acc
                refund -> {
                    refundable[t.from] = refundable.getValue(t.from) - t.amount
                    acc
                }
                t.to == accountId -> if (t.from == accountId) acc else acc + t.amount
                t.from == accountId -> acc - t.amount
                else -> acc
            }
        }
    }
}

private const val STORAGE_DEPOSIT = "storage_deposit"

private fun isFtMethod(methodName: String?) = methodName != null && methodName.startsWith("ft_")

/** A top-level action, flattened for storage and display. */
data class TxAction(
    /** CreateAccount, DeployContract, FunctionCall, Transfer, Stake, AddKey, DeleteKey, DeleteAccount, Delegate… */
    val type: String,
    val deposit: BigInteger? = null,
    val methodName: String? = null,
    /** FunctionCall arguments as text when they are UTF-8 (normally JSON), truncated. */
    val args: String? = null,
    val gas: BigInteger? = null,
    val publicKey: String? = null,
    val beneficiaryId: String? = null,
    val stake: BigInteger? = null,
)

/**
 * NEAR attached to a receipt: a Transfer, or the deposit of a FunctionCall (storage_deposit, the
 * 1 yocto of ft_transfer, wrap.near near_deposit). Gas refunds from `system` are not included;
 * they are already netted out of [Transaction.fee].
 */
data class NearTransfer(
    val from: String,
    val to: String,
    val amount: BigInteger,
    val kind: Kind,
    /** False when the receipt failed; its deposit went back to the sender. */
    val success: Boolean,
    /** The method a [Kind.FunctionCallDeposit] was attached to. */
    val methodName: String? = null,
) {
    enum class Kind { Transfer, FunctionCallDeposit }
}

/** A NEP-141 movement from an `EVENT_JSON` log of a successful receipt, or predicted for a pending send. */
data class FtTransfer(
    val contractId: String,
    /** Null for a mint. */
    val from: String?,
    /** Null for a burn. */
    val to: String?,
    val amount: BigInteger,
    val memo: String?,
)

/** Which histories a transaction belongs to: [TOKEN_NATIVE] or a token contract id. */
@Entity(primaryKeys = ["hash", "token"], indices = [Index("token")])
data class TransactionTag(
    val hash: String,
    val token: String,
) {
    companion object {
        const val TOKEN_NATIVE = "native"
    }
}
