package io.horizontalsystems.nearkit.sync

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.horizontalsystems.nearkit.models.FtTransfer
import io.horizontalsystems.nearkit.models.NearTransfer
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionTag
import io.horizontalsystems.nearkit.models.TxAction
import io.horizontalsystems.nearkit.network.InvalidResponse
import io.horizontalsystems.nearkit.network.getAsJsonObjectOrNull
import io.horizontalsystems.nearkit.network.optBigInteger
import io.horizontalsystems.nearkit.network.optLong
import io.horizontalsystems.nearkit.network.optString
import io.horizontalsystems.nearkit.network.requireString
import io.horizontalsystems.nearkit.transaction.Action
import java.math.BigInteger
import java.util.Base64
import io.horizontalsystems.nearkit.transaction.Transaction as UnsignedTransaction

/**
 * Turns an executed transaction into a [Transaction] record from the point of view of one account.
 *
 * Accepts both shapes the kit receives: FastNEAR `/v0/transactions` items (`execution_outcome`,
 * `receipts[].receipt` + `receipts[].execution_outcome`) and RPC `EXPERIMENTAL_tx_status`
 * results (`transaction_outcome`, `receipts_outcome[]`, `receipts[]`). Everything read here is
 * untrusted indexer or node output: malformed parts are skipped, never guessed.
 */
internal object TransactionConverter {

    private const val SYSTEM_ACCOUNT = "system"
    private const val EVENT_PREFIX = "EVENT_JSON:"
    private const val MAX_ARGS_LENGTH = 2048
    private const val LEGACY_MEMO_PREFIX = "Memo: "
    private const val ACCOUNT = "([a-z0-9._-]{2,64})"
    private val LEGACY_TRANSFER = Regex("^(Transfer|Refund) (\\d{1,40}) from $ACCOUNT to $ACCOUNT$")
    private val LEGACY_DEPOSIT = Regex("^Deposit (\\d{1,40}) NEAR to $ACCOUNT$")
    private val LEGACY_WITHDRAW = Regex("^Withdraw (\\d{1,40}) NEAR from $ACCOUNT$")

    private class Receipt(
        val id: String,
        val predecessorId: String?,
        val receiverId: String?,
        /** Actions of an action receipt; null for data receipts or when the shape is unknown. */
        val actions: JsonArray?,
        val outcome: JsonObject?,
    )

    fun convert(item: JsonObject, accountId: String, fallbackTimestampSeconds: Long): Pair<Transaction, Set<String>> {
        val tx = item.getAsJsonObjectOrNull("transaction") ?: throw InvalidResponse("missing transaction")
        val txOutcomeWrapper = item.getAsJsonObjectOrNull("execution_outcome") ?: item.getAsJsonObjectOrNull("transaction_outcome")
        val receipts = receipts(item)
        val receiptsById = receipts.associateBy { it.id }

        val txOutcome = txOutcomeWrapper?.getAsJsonObjectOrNull("outcome")
        val (status, failure) = if (item.has("execution_outcome") && hasUnexecutedReceipts(txOutcome, receipts)) {
            Transaction.Status.Pending to null
        } else {
            finalStatus(txOutcome, receiptsById)
        }

        val burnt = (listOfNotNull(txOutcome) + receipts.mapNotNull { it.outcome })
            .mapNotNull { it.optBigInteger("tokens_burnt") }
            .fold(BigInteger.ZERO, BigInteger::add)

        val nearTransfers = receipts.flatMap { receipt -> nearTransfers(receipt, accountId) }
        val ftTransfers = (listOfNotNull(txOutcome) + receipts.mapNotNull { it.outcome })
            .filter { isSuccess(it) }
            .flatMap { ftEvents(it, accountId) }

        val timestampNanos = txOutcomeWrapper?.optLong("block_timestamp")
        val transaction = Transaction(
            hash = tx.requireString("hash"),
            blockHeight = txOutcomeWrapper?.optLong("block_height"),
            timestamp = timestampNanos?.let { it / 1_000_000_000 } ?: fallbackTimestampSeconds,
            signerId = tx.requireString("signer_id"),
            receiverId = tx.requireString("receiver_id"),
            actions = (tx.get("actions") as? JsonArray)?.mapNotNull { txAction(it) } ?: emptyList(),
            nearTransfers = nearTransfers,
            ftTransfers = ftTransfers,
            fee = if (status == Transaction.Status.Pending) null else burnt,
            status = status,
            failure = failure,
            expiresAfterHeight = null,
        )
        return transaction to tags(transaction, accountId)
    }

    /** The record shown right after the kit submits [tx], before any node reports its outcome. */
    fun pending(tx: UnsignedTransaction, hash: String, expiresAfterHeight: Long, nowSeconds: Long): Pair<Transaction, Set<String>> {
        val nearTransfers = mutableListOf<NearTransfer>()
        val ftTransfers = mutableListOf<FtTransfer>()
        for (action in tx.actions) {
            when (action) {
                is Action.Transfer -> nearTransfers += NearTransfer(tx.signerId, tx.receiverId, action.deposit, NearTransfer.Kind.Transfer, true)
                is Action.FunctionCall -> {
                    if (action.deposit.signum() > 0) {
                        nearTransfers += NearTransfer(tx.signerId, tx.receiverId, action.deposit, NearTransfer.Kind.FunctionCallDeposit, true, action.methodName)
                    }
                    if (action.methodName == "ft_transfer" || action.methodName == "ft_transfer_call") {
                        parseObject(action.argsText)?.let { args ->
                            val to = args.optString("receiver_id")
                            val amount = args.optBigInteger("amount")
                            if (to != null && amount != null) {
                                ftTransfers += FtTransfer(tx.receiverId, tx.signerId, to, amount, args.optString("memo"))
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        val transaction = Transaction(
            hash = hash,
            blockHeight = null,
            timestamp = nowSeconds,
            signerId = tx.signerId,
            receiverId = tx.receiverId,
            actions = tx.actions.map { txAction(it) },
            nearTransfers = nearTransfers,
            ftTransfers = ftTransfers,
            fee = null,
            status = Transaction.Status.Pending,
            failure = null,
            expiresAfterHeight = expiresAfterHeight,
        )
        return transaction to tags(transaction, tx.signerId)
    }

    /**
     * The histories [transaction] appears in, as other chains do it: a token's history holds its
     * transfers, and NEAR's history holds transactions that move NEAR ([Transaction.nearMoved]).
     * Gas alone does not count, or every token send would show in NEAR's history too, and neither
     * does NEAR that failed to move or came back. A signed transaction that touches no token, such
     * as adding a key, stays in NEAR's history.
     */
    fun tags(transaction: Transaction, accountId: String): Set<String> {
        val tags = mutableSetOf<String>()
        val signed = transaction.signerId == accountId
        val callsToken = signed && transaction.actions.any { it.methodName?.startsWith("ft_") == true }
        val movesNear = transaction.nearMoved(accountId).signum() != 0
        if (movesNear || (signed && !callsToken && transaction.ftTransfers.isEmpty())) tags += TransactionTag.TOKEN_NATIVE
        transaction.ftTransfers.forEach { tags += it.contractId }
        // a failed token send emits no event; keep it in that token's history anyway
        if (callsToken) tags += transaction.receiverId
        return tags
    }

    private fun receipts(item: JsonObject): List<Receipt> {
        // FastNEAR: receipts[] = { receipt: {receipt_id, predecessor_id, receiver_id, receipt}, execution_outcome }
        (item.get("receipts") as? JsonArray)?.takeIf { array -> array.any { (it as? JsonObject)?.has("execution_outcome") == true } }?.let { array ->
            return array.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val receipt = entry.getAsJsonObjectOrNull("receipt") ?: return@mapNotNull null
                val outcomeWrapper = entry.getAsJsonObjectOrNull("execution_outcome")
                Receipt(
                    id = receipt.optString("receipt_id") ?: outcomeWrapper?.optString("id") ?: return@mapNotNull null,
                    predecessorId = receipt.optString("predecessor_id"),
                    receiverId = receipt.optString("receiver_id"),
                    actions = actionsOf(receipt),
                    outcome = outcomeWrapper?.getAsJsonObjectOrNull("outcome"),
                )
            }
        }

        // RPC: receipts_outcome[] = { id, outcome }, receipts[] = { receipt_id, predecessor_id, receiver_id, receipt }
        val receiptsById = (item.get("receipts") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.associateBy { it.optString("receipt_id") }
            ?: emptyMap()
        val outcomes = (item.get("receipts_outcome") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        return outcomes.mapNotNull { wrapper ->
            val id = wrapper.optString("id") ?: return@mapNotNull null
            val receipt = receiptsById[id]
            val outcome = wrapper.getAsJsonObjectOrNull("outcome")
            Receipt(
                id = id,
                predecessorId = receipt?.optString("predecessor_id"),
                receiverId = receipt?.optString("receiver_id") ?: outcome?.optString("executor_id"),
                actions = receipt?.let { actionsOf(it) },
                outcome = outcome,
            )
        }
    }

    private fun actionsOf(receipt: JsonObject): JsonArray? =
        receipt.getAsJsonObjectOrNull("receipt")?.getAsJsonObjectOrNull("Action")?.get("actions") as? JsonArray

    /**
     * nearcore's final status: follow SuccessReceiptId from the transaction outcome until a receipt
     * ends in SuccessValue or Failure. A missing link means execution is still in progress.
     */
    private fun finalStatus(txOutcome: JsonObject?, receipts: Map<String, Receipt>): Pair<Transaction.Status, String?> {
        var outcome = txOutcome ?: return Transaction.Status.Pending to null
        repeat(receipts.size + 1) {
            val status = outcome.getAsJsonObjectOrNull("status") ?: return Transaction.Status.Pending to null
            when {
                status.has("Failure") -> return Transaction.Status.Failed to status.get("Failure").toString()
                status.has("SuccessValue") -> return Transaction.Status.Success to null
                status.has("SuccessReceiptId") -> {
                    val next = receipts[status.optString("SuccessReceiptId")]?.outcome
                        ?: return Transaction.Status.Pending to null
                    outcome = next
                }
                else -> return Transaction.Status.Pending to null
            }
        }
        return Transaction.Status.Pending to null
    }

    /**
     * FastNEAR serves a transaction from its first block on, while receipts are still executing,
     * and its final status can already be settled then: a receipt that returns a value may have
     * spawned others (a swap output, wrap.near's NEAR payout) that run in later blocks. Until
     * every receipt an outcome created is present, the transfers are incomplete.
     *
     * Only for FastNEAR items: RPC results at EXECUTED_OPTIMISTIC may leave out gas refund
     * receipts that never matter here, and would never look complete.
     */
    private fun hasUnexecutedReceipts(txOutcome: JsonObject?, receipts: List<Receipt>): Boolean {
        val present = receipts.mapTo(mutableSetOf()) { it.id }
        return (listOfNotNull(txOutcome) + receipts.mapNotNull { it.outcome })
            .flatMap { outcome -> (outcome.get("receipt_ids") as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList() }
            .any { it !in present }
    }

    private fun isSuccess(outcome: JsonObject): Boolean {
        val status = outcome.getAsJsonObjectOrNull("status") ?: return false
        return status.has("SuccessValue") || status.has("SuccessReceiptId")
    }

    private fun nearTransfers(receipt: Receipt, accountId: String): List<NearTransfer> {
        val from = receipt.predecessorId ?: return emptyList()
        val to = receipt.receiverId ?: return emptyList()
        // `system` receipts are gas and deposit refunds, already reflected in fee and success
        if (from == SYSTEM_ACCOUNT) return emptyList()
        if (from != accountId && to != accountId) return emptyList()
        val success = receipt.outcome?.let { isSuccess(it) } ?: true

        val actions = receipt.actions ?: return emptyList()
        return actions.mapNotNull { element ->
            val action = element as? JsonObject ?: return@mapNotNull null
            action.getAsJsonObjectOrNull("Transfer")?.optBigInteger("deposit")?.let { deposit ->
                return@mapNotNull NearTransfer(from, to, deposit, NearTransfer.Kind.Transfer, success)
            }
            action.getAsJsonObjectOrNull("FunctionCall")?.let { call ->
                call.optBigInteger("deposit")?.takeIf { it.signum() > 0 }?.let { deposit ->
                    return@mapNotNull NearTransfer(from, to, deposit, NearTransfer.Kind.FunctionCallDeposit, success, call.optString("method_name"))
                }
            }
            null
        }
    }

    /**
     * Token movements of one outcome that involve [accountId]: NEP-141 events (NEP-297
     * `EVENT_JSON:` logs), or, for contracts built before events existed, their plain-text logs.
     * wrap.near is one of those.
     */
    private fun ftEvents(outcome: JsonObject, accountId: String): List<FtTransfer> {
        val contractId = outcome.optString("executor_id") ?: return emptyList()
        val logs = (outcome.get("logs") as? JsonArray)
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?: return emptyList()

        val events = logs.filter { it.startsWith(EVENT_PREFIX) }.mapNotNull { parseObject(it.removePrefix(EVENT_PREFIX)) }
        val transfers = if (events.isNotEmpty()) {
            events.filter { it.optString("standard") == "nep141" }.flatMap { eventTransfers(contractId, it) }
        } else {
            legacyTransfers(contractId, logs)
        }
        return transfers.filter { it.from == accountId || it.to == accountId }
    }

    private fun eventTransfers(contractId: String, event: JsonObject): List<FtTransfer> {
        val data = event.get("data") as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val amount = entry.optBigInteger("amount") ?: return@mapNotNull null
            val memo = entry.optString("memo")
            when (event.optString("event")) {
                "ft_transfer" -> FtTransfer(contractId, entry.optString("old_owner_id"), entry.optString("new_owner_id"), amount, memo)
                "ft_mint" -> FtTransfer(contractId, null, entry.optString("owner_id"), amount, memo)
                "ft_burn" -> FtTransfer(contractId, entry.optString("owner_id"), null, amount, memo)
                else -> null
            }
        }
    }

    /**
     * Logs of the pre-event near-contract-standards (`Transfer 5 from a to b`, `Refund 5 from b
     * to a`, then optionally `Memo: …`) and of wrap.near (`Deposit 5 NEAR to a`, `Withdraw 5
     * NEAR from a`).
     */
    private fun legacyTransfers(contractId: String, logs: List<String>): List<FtTransfer> {
        val transfers = mutableListOf<FtTransfer>()
        for (log in logs) {
            LEGACY_TRANSFER.matchEntire(log)?.let { m ->
                transfers += FtTransfer(contractId, m.groupValues[3], m.groupValues[4], BigInteger(m.groupValues[2]), null)
                continue
            }
            LEGACY_DEPOSIT.matchEntire(log)?.let { m ->
                transfers += FtTransfer(contractId, null, m.groupValues[2], BigInteger(m.groupValues[1]), null)
                continue
            }
            LEGACY_WITHDRAW.matchEntire(log)?.let { m ->
                transfers += FtTransfer(contractId, m.groupValues[2], null, BigInteger(m.groupValues[1]), null)
                continue
            }
            if (log.startsWith(LEGACY_MEMO_PREFIX) && transfers.isNotEmpty()) {
                val last = transfers.removeAt(transfers.lastIndex)
                transfers += last.copy(memo = log.removePrefix(LEGACY_MEMO_PREFIX))
            }
        }
        return transfers
    }

    private fun txAction(element: JsonElement): TxAction? {
        // "CreateAccount" arrives as a bare string; every other action is a one-key object
        if (element.isJsonPrimitive) return TxAction(type = element.asString)
        val obj = element as? JsonObject ?: return null
        val (type, body) = obj.entrySet().firstOrNull() ?: return null
        val fields = body as? JsonObject ?: return TxAction(type = type)
        return TxAction(
            type = type,
            deposit = fields.optBigInteger("deposit"),
            methodName = fields.optString("method_name"),
            args = fields.optString("args")?.let { decodeArgs(it) },
            gas = fields.optBigInteger("gas"),
            publicKey = fields.optString("public_key"),
            beneficiaryId = fields.optString("beneficiary_id"),
            stake = fields.optBigInteger("stake"),
        )
    }

    private fun txAction(action: Action): TxAction = when (action) {
        Action.CreateAccount -> TxAction("CreateAccount")
        is Action.DeployContract -> TxAction("DeployContract")
        is Action.FunctionCall -> TxAction("FunctionCall", deposit = action.deposit, methodName = action.methodName, args = decodeArgs(action.args), gas = action.gas)
        is Action.Transfer -> TxAction("Transfer", deposit = action.deposit)
        is Action.Stake -> TxAction("Stake", stake = action.stake, publicKey = action.publicKey.toString())
        is Action.AddKey -> TxAction("AddKey", publicKey = action.publicKey.toString())
        is Action.DeleteKey -> TxAction("DeleteKey", publicKey = action.publicKey.toString())
        is Action.DeleteAccount -> TxAction("DeleteAccount", beneficiaryId = action.beneficiaryId)
    }

    private fun decodeArgs(base64: String): String? = try {
        decodeArgs(Base64.getDecoder().decode(base64))
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun decodeArgs(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
        val text = try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return null
        }
        return if (text.length > MAX_ARGS_LENGTH) text.substring(0, MAX_ARGS_LENGTH) else text
    }

    private fun parseObject(text: String): JsonObject? = try {
        JsonParser.parseString(text) as? JsonObject
    } catch (e: Exception) {
        null
    }
}
