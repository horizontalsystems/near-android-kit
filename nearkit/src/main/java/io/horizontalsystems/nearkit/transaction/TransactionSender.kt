package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.nearkit.crypto.Base58
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.network.NoEndpointAvailable
import io.horizontalsystems.nearkit.network.RpcError
import io.horizontalsystems.nearkit.network.RpcProvider
import io.horizontalsystems.nearkit.sync.TransactionConverter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import io.horizontalsystems.nearkit.transaction.Transaction as UnsignedTransaction

/**
 * Builds, signs and submits transactions, recording each one locally as pending so the wallet
 * shows it immediately. Sends are serialized: concurrent sends would read the same access key
 * nonce and one of them would be rejected with InvalidNonce.
 */
internal class TransactionSender(
    private val accountId: String,
    private val rpcProvider: RpcProvider,
    private val storage: Storage,
) {
    private val sendMutex = Mutex()

    /** Builds and signs without submitting. The nonce and block hash come from the chain. */
    suspend fun sign(signer: Signer, receiverId: String, actions: List<Action>): SignedTransaction =
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

    suspend fun send(signer: Signer, receiverId: String, actions: List<Action>): Transaction = sendMutex.withLock {
        val (signed, height) = signWithHeight(signer, receiverId, actions)
        submit(signed, referenceHeight = height)
    }

    /**
     * Submits an already signed transaction (e.g. one signed for a dApp). [referenceHeight] is the
     * height of its block hash when known; it bounds how long the record may stay pending.
     */
    suspend fun submit(signed: SignedTransaction, referenceHeight: Long?): Transaction {
        val height = referenceHeight ?: storage.getChainState()?.blockHeight ?: 0
        val (pending, tags) = TransactionConverter.pending(
            signed.transaction,
            signed.hash,
            expiresAfterHeight = height + TRANSACTION_VALIDITY_BLOCKS,
            nowSeconds = System.currentTimeMillis() / 1000,
        )

        try {
            rpcProvider.sendTransaction(Base64.getEncoder().encodeToString(signed.encode()), waitUntil = "INCLUDED")
        } catch (e: RpcError) {
            if (e.name == RpcProvider.INVALID_TRANSACTION) {
                throw SendError.Rejected(e.data?.toString() ?: e.message ?: e.name)
            }
            throw e
        } catch (e: NoEndpointAvailable) {
            // The transaction may still have reached a node before the connection dropped. Keep
            // it as pending so it resolves (or expires) instead of inviting a second send.
            storage.saveTransactions(listOf(pending to tags))
            throw e
        }

        storage.saveTransactions(listOf(pending to tags))
        return pending
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
    }

    companion object {
        /** Mainnet and testnet `transaction_validity_period`: a block hash older than this is rejected. */
        const val TRANSACTION_VALIDITY_BLOCKS = 86_400L
    }
}
