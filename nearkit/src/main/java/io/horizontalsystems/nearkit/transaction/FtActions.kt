package io.horizontalsystems.nearkit.transaction

import com.google.gson.JsonObject
import io.horizontalsystems.nearkit.crypto.AccountId
import io.horizontalsystems.nearkit.network.InvalidResponse
import io.horizontalsystems.nearkit.network.RpcProvider
import io.horizontalsystems.nearkit.network.optBigInteger
import java.math.BigInteger

/** NEP-141 transfer building (https://nomicon.io/Standards/Tokens/FungibleToken/Core) with NEP-145 storage registration. */
object FtActions {

    /** Gas attached to token calls; the NEP-141 reference contracts need well under this. */
    val GAS: BigInteger = BigInteger.valueOf(30_000_000_000_000L)

    /**
     * Gas a token call is expected to burn, for the fee shown before sending. USDC burns about
     * 2.4 Tgas for `ft_transfer` and less for `storage_deposit`; this leaves room for heavier
     * contracts while staying far below [GAS].
     */
    const val EXPECTED_GAS: Long = 5_000_000_000_000L

    /**
     * The most yoctoNEAR (0.1 NEAR) the kit attaches as a storage deposit. Real tokens ask for
     * 0.00125 to about 0.0125 NEAR; the amount comes from the token contract (or the RPC node), so
     * a scam token could otherwise ask for the whole balance.
     */
    val MAX_STORAGE_DEPOSIT: BigInteger = BigInteger.TEN.pow(23)

    /**
     * NEAR [accountId] must deposit with the token contract before it can hold the token, or null
     * when it is already registered. A token transfer to an unregistered account fails.
     *
     * Throws [TransactionSender.SendError.StorageDepositTooHigh] above [MAX_STORAGE_DEPOSIT].
     */
    suspend fun storageDepositRequired(rpcProvider: RpcProvider, contractId: String, accountId: String): BigInteger? {
        val balance = rpcProvider.callFunctionJson(contractId, "storage_balance_of", accountIdArgs(accountId))
        if (!balance.isJsonNull) return null
        val bounds = rpcProvider.callFunctionJson(contractId, "storage_balance_bounds") as? JsonObject
            ?: throw InvalidResponse("storage_balance_bounds: not an object")
        val min = bounds.optBigInteger("min") ?: throw InvalidResponse("storage_balance_bounds: missing min")
        return checkStorageDeposit(min)
    }

    internal fun checkStorageDeposit(deposit: BigInteger): BigInteger {
        if (deposit.signum() < 0) throw InvalidResponse("storage_balance_bounds: negative min")
        if (deposit > MAX_STORAGE_DEPOSIT) throw TransactionSender.SendError.StorageDepositTooHigh(deposit)
        return deposit
    }

    /** `storage_deposit` for [receiverId] when [storageDeposit] is set, then `ft_transfer`. Sent to the token contract. */
    fun transfer(receiverId: String, amount: BigInteger, memo: String?, storageDeposit: BigInteger?): List<Action> {
        AccountId.validate(receiverId)
        val actions = mutableListOf<Action>()
        if (storageDeposit != null) {
            val args = JsonObject().apply {
                addProperty("account_id", receiverId)
                addProperty("registration_only", true)
            }
            actions += Action.FunctionCall("storage_deposit", args.toString().toByteArray(), GAS, storageDeposit)
        }
        val args = JsonObject().apply {
            addProperty("receiver_id", receiverId)
            addProperty("amount", amount.toString())
            memo?.let { addProperty("memo", it) }
        }
        // NEP-141 requires exactly one yoctoNEAR on ft_transfer, as proof of a full-access key
        actions += Action.FunctionCall("ft_transfer", args.toString().toByteArray(), GAS, BigInteger.ONE)
        return actions
    }

    internal fun accountIdArgs(accountId: String): String {
        AccountId.validate(accountId)
        return JsonObject().apply { addProperty("account_id", accountId) }.toString()
    }
}
