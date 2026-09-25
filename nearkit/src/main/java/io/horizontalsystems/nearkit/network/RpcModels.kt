package io.horizontalsystems.nearkit.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigInteger

/**
 * nearcore returned a structured error. [name] is the cause name (UNKNOWN_ACCOUNT,
 * INVALID_TRANSACTION, TIMEOUT_ERROR…) or, without a cause, the top-level error name.
 * [data] is the error payload, e.g. `{"TxExecutionError":{"InvalidTxError":"Expired"}}`.
 */
class RpcError(val name: String, val data: JsonElement?, message: String?) : Exception("$name${message?.let { ": $it" } ?: ""}") {
    /** Every node would answer the same, so there is no point asking the next endpoint. */
    val isDeterministic: Boolean get() = name !in RETRYABLE

    companion object {
        private val RETRYABLE = setOf("TIMEOUT_ERROR", "INTERNAL_ERROR", "NO_SYNCED_BLOCKS", "NOT_SYNCED_YET", "UNAVAILABLE_SHARD", "SERVER_ERROR")
    }
}

/** Every endpoint failed, or the last one returned an unusable response. */
class NoEndpointAvailable(cause: Throwable?) : Exception("No NEAR endpoint responded", cause)

/** A response did not have the shape the kit expects. Treated as untrusted input. */
class InvalidResponse(message: String) : Exception(message)

class AccountView(
    /** Liquid balance in yoctoNEAR. */
    val amount: BigInteger,
    /** Balance locked by validator staking (not by delegation to a pool). */
    val locked: BigInteger,
    val storageUsage: Long,
    val codeHash: String,
    val blockHeight: Long,
) {
    val hasContract: Boolean get() = codeHash != EMPTY_CODE_HASH

    companion object {
        const val EMPTY_CODE_HASH = "11111111111111111111111111111111"
    }
}

class AccessKeyView(
    val nonce: BigInteger,
    val isFullAccess: Boolean,
    /** Recent block hash from the same response; usable as the transaction's block hash. */
    val blockHash: String,
    val blockHeight: Long,
)

class BlockHeader(
    val height: Long,
    val hash: String,
    /** Nanoseconds since the Unix epoch. */
    val timestampNanos: Long,
    val gasPrice: BigInteger,
)

/** Result of EXPERIMENTAL_tx_status or of send_tx when it waited for execution. */
class TxStatus(
    /** NONE, INCLUDED, EXECUTED_OPTIMISTIC, INCLUDED_FINAL, EXECUTED or FINAL. */
    val finalExecutionStatus: String,
    /** Full response (`transaction`, `transaction_outcome`, `receipts_outcome`, `receipts`, `status`); absent before execution. */
    val raw: JsonObject?,
) {
    val isExecuted: Boolean get() = raw?.has("status") == true && finalExecutionStatus in EXECUTED_STATES

    companion object {
        private val EXECUTED_STATES = setOf("EXECUTED_OPTIMISTIC", "EXECUTED", "FINAL")
    }
}
