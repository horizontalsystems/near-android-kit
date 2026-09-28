package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.nearkit.crypto.Base58
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.network.RpcError
import io.horizontalsystems.nearkit.network.RpcProvider
import io.horizontalsystems.nearkit.sync.TransactionConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.util.Base64
import io.horizontalsystems.nearkit.transaction.Transaction as UnsignedTransaction

/**
 * Builds, signs and submits transactions, recording each one locally as pending so the wallet
 * shows it immediately. Sends are serialized: concurrent sends would read the same access key
 * nonce and one of them would be rejected with InvalidNonce.
 *
 * Public only so apps can catch [SendError].
 */
class TransactionSender internal constructor(
    private val accountId: String,
    private val rpcProvider: RpcProvider,
    private val storage: Storage,
    /** Gets each record saved as pending, which no sync reports until it changes. */
    private val onPendingSaved: (Transaction) -> Unit = {},
) {
    private val sendMutex = Mutex()

    /** Builds and signs without submitting. The nonce and block hash come from the chain. */
    internal suspend fun sign(signer: Signer, receiverId: String, actions: List<Action>): SignedTransaction =
        signWithHeight(signer, receiverId, actions).first

    private suspend fun signWithHeight(signer: Signer, receiverId: String, actions: List<Action>): Pair<SignedTransaction, Long> {
        val accessKey = rpcProvider.viewAccessKey(accountId, signer.publicKey.toString())
            ?: throw SendError.AccessKeyNotFound()
        if (!accessKey.isFullAccess) throw SendError.NotFullAccessKey()

        val tx = UnsignedTransaction(
            signerId = accountId,
            publicKey = signer.publicKey,
            nonce = accessKey.nonce.inc(),
            receiverId = receiverId,
            blockHash = Base58.decode(accessKey.blockHash),
            actions = actions,
        )
        return signer.sign(tx) to accessKey.blockHeight
    }

    internal suspend fun send(signer: Signer, receiverId: String, actions: List<Action>): Transaction = sendMutex.withLock {
        val (signed, height) = signWithHeight(signer, receiverId, actions)
        submit(signed, referenceHeight = height)
    }

    /**
     * Submits an already signed transaction (e.g. one signed for a dApp). [referenceHeight] is the
     * height of its block hash when known; it bounds how long the record may stay pending.
     *
     * Only a rejection for a transaction no node knows is reported as [SendError.Rejected]. Any
     * other failure may come after a node already accepted it (a dropped connection, or failover
     * resending it to a node that then refuses the used nonce), so the record is kept as pending
     * to resolve or expire, instead of inviting the user to send again.
     */
    internal suspend fun submit(signed: SignedTransaction, referenceHeight: Long?): Transaction {
        // the current height when the block hash's is unknown: a later expiry only keeps the record pending longer
        val height = referenceHeight ?: storage.getChainState()?.blockHeight ?: rpcProvider.latestBlock().height
        val (pending, tags) = TransactionConverter.pending(
            signed.transaction,
            signed.hash,
            expiresAfterHeight = height + TRANSACTION_VALIDITY_BLOCKS,
            nowSeconds = System.currentTimeMillis() / 1000,
        )
        val savePending = {
            storage.saveTransactions(listOf(pending to tags))
            onPendingSaved(pending)
        }

        val error = try {
            rpcProvider.sendTransaction(Base64.getEncoder().encodeToString(signed.encode()), waitUntil = "INCLUDED")
            null
        } catch (e: CancellationException) {
            // cancelled mid-request: the transaction may be out
            withContext(NonCancellable) { savePending() }
            throw e
        } catch (e: Exception) {
            e
        }

        if (error is RpcError && error.name == RpcProvider.INVALID_TRANSACTION) {
            val rejected = SendError.Rejected(error.data?.toString() ?: error.message ?: error.name)
            when (isKnown(signed)) {
                false -> throw rejected
                // an earlier attempt got it on chain; this is a refusal of the duplicate
                true -> {
                    savePending()
                    return pending
                }
                // cannot tell: keep the record, which expires to failed if the rejection was real
                null -> {
                    savePending()
                    throw rejected
                }
            }
        }

        savePending()
        if (error != null) throw error
        return pending
    }

    /** Whether a node knows the transaction; null when that cannot be told right now. */
    private suspend fun isKnown(signed: SignedTransaction): Boolean? = try {
        rpcProvider.transactionStatus(signed.hash, signed.transaction.signerId) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    sealed class SendError(message: String? = null) : Exception(message) {
        /** The account does not exist yet, or the kit's key is not one of its access keys. */
        class AccessKeyNotFound : SendError("Access key not found on the account")

        /** Function-call keys cannot attach deposits or call arbitrary contracts. */
        class NotFullAccessKey : SendError("Access key is not a full-access key")

        /** Transfers to a named account that does not exist fail on chain and are refused up front. */
        class ReceiverNotFound(val receiverId: String) : SendError("Account $receiverId does not exist")

        /** The node refused the transaction, e.g. `{"TxExecutionError":{"InvalidTxError":{"NotEnoughBalance":…}}}`. */
        class Rejected(val details: String) : SendError(details)

        /** The token contract asks for more than [FtActions.MAX_STORAGE_DEPOSIT] to register the receiver. */
        class StorageDepositTooHigh(val deposit: BigInteger) : SendError("Token storage deposit $deposit yoctoNEAR exceeds the limit")

        /** The storage deposit the token contract asks for is not the one the user confirmed. */
        class StorageDepositChanged(val confirmed: BigInteger?, val required: BigInteger?) :
            SendError("Token storage deposit changed from $confirmed to $required yoctoNEAR")
    }

    internal companion object {
        /** Mainnet and testnet `transaction_validity_period`: a block hash older than this is rejected. */
        const val TRANSACTION_VALIDITY_BLOCKS = 86_400L
    }
}
