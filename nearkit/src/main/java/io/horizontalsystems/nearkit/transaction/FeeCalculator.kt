package io.horizontalsystems.nearkit.transaction

import com.google.gson.JsonObject
import io.horizontalsystems.nearkit.crypto.AccountId
import io.horizontalsystems.nearkit.network.getAsJsonObjectOrNull
import io.horizontalsystems.nearkit.network.optBigInteger
import io.horizontalsystems.nearkit.network.optLong
import java.math.BigInteger

/**
 * What a transaction costs, from the runtime fee config (`EXPERIMENTAL_protocol_config`).
 *
 * Converting the transaction burns the "send" costs and executing its receipt the "execution"
 * costs; a function call also buys the gas attached to it. Execution gas is bought up front at
 * no less than `min_gas_purchase_price` and burnt at the block gas price, and the difference
 * comes back as a refund. So the balance a send needs is well above what it finally costs.
 */
class FeeCalculator(private val config: Config = Config.DEFAULT) {

    /**
     * @property fee yoctoNEAR the sender ends up paying: gas burnt at the current gas price plus
     *   what the account creation charge adds on top of the burnt creation gas. For function calls it assumes all attached gas is used,
     *   unless the estimate was given the gas a call is expected to use.
     * @property requiredBalance yoctoNEAR (besides deposits) the account must hold for the node
     *   to accept the transaction, with the send part priced at [SEND_GAS_PRICE_MARGIN] times the
     *   gas price. Use it to compute the maximum sendable amount.
     */
    class Estimate(val gas: Long, val fee: BigInteger, val requiredBalance: BigInteger)

    /** (send, execution) gas per item, at the not-sender-is-receiver price, never the lower one. */
    class Config(
        val actionReceipt: Pair<Long, Long>,
        val transfer: Pair<Long, Long>,
        val createAccount: Pair<Long, Long>,
        val addFullAccessKey: Pair<Long, Long>,
        val functionCall: Pair<Long, Long>,
        val functionCallPerByte: Pair<Long, Long>,
        /** Every other action is small next to the receipt cost; this bounds them. */
        val otherAction: Pair<Long, Long>,
        /** yoctoNEAR charged when a transfer creates an account. */
        val accountCreationCharge: BigInteger,
        /** yoctoNEAR per gas at which gas is bought, whatever the block gas price. */
        val minGasPurchasePrice: BigInteger,
    ) {
        companion object {
            /** Mainnet values, protocol version 86 (September 2026). */
            val DEFAULT = Config(
                actionReceipt = 108_059_500_000L to 108_059_500_000L,
                transfer = 115_123_062_500L to 115_123_062_500L,
                createAccount = 500_000_000_000L to 7_200_000_000_000L,
                addFullAccessKey = 101_765_125_000L to 101_765_125_000L,
                functionCall = 200_000_000_000L to 780_000_000_000L,
                functionCallPerByte = 47_683_715L to 2_235_934L,
                otherAction = 200_000_000_000L to 200_000_000_000L,
                accountCreationCharge = BigInteger("7000000000000000000000"),
                minGasPurchasePrice = BigInteger.valueOf(1_000_000_000L),
            )

            /** Reads the config from `EXPERIMENTAL_protocol_config`; null when the shape is unexpected. */
            fun fromProtocolConfig(protocolConfig: JsonObject): Config? {
                val runtime = protocolConfig.getAsJsonObjectOrNull("runtime_config") ?: return null
                val tc = runtime.getAsJsonObjectOrNull("transaction_costs") ?: return null
                val ac = tc.getAsJsonObjectOrNull("action_creation_config") ?: return null
                fun pair(obj: JsonObject?): Pair<Long, Long>? {
                    obj ?: return null
                    val send = obj.optLong("send_not_sir") ?: return null
                    val execution = obj.optLong("execution") ?: return null
                    return send to execution
                }
                return Config(
                    actionReceipt = pair(tc.getAsJsonObjectOrNull("action_receipt_creation_config")) ?: return null,
                    transfer = pair(ac.getAsJsonObjectOrNull("transfer_cost")) ?: return null,
                    createAccount = pair(ac.getAsJsonObjectOrNull("create_account_cost")) ?: return null,
                    addFullAccessKey = pair(ac.getAsJsonObjectOrNull("add_key_cost")?.getAsJsonObjectOrNull("full_access_cost")) ?: return null,
                    functionCall = pair(ac.getAsJsonObjectOrNull("function_call_cost")) ?: return null,
                    functionCallPerByte = pair(ac.getAsJsonObjectOrNull("function_call_cost_per_byte")) ?: return null,
                    otherAction = DEFAULT.otherAction,
                    // older protocol versions have neither field: no charge, and gas bought at the block price
                    accountCreationCharge = runtime.optBigInteger("account_creation_charge") ?: BigInteger.ZERO,
                    minGasPurchasePrice = runtime.optBigInteger("min_gas_purchase_price") ?: BigInteger.ZERO,
                )
            }
        }
    }

    /**
     * Total gas for [actions] sent to an account of [receiver] type. A transfer to an account the
     * runtime can create always pays for creating it, because at conversion time the runtime
     * cannot know whether the account exists (nearcore `transfer_send_fee`/`transfer_exec_fee`):
     * a NEAR-implicit account also gets its full access key, an ETH-implicit or deterministic one
     * does not.
     */
    fun gas(actions: List<Action>, receiver: AccountId.Type): Long = gas(actions, receiver, expectedCallGas = null).total.toLongClamped()

    private class Gas(val send: BigInteger, val execution: BigInteger) {
        val total: BigInteger get() = send + execution
    }

    private fun gas(actions: List<Action>, receiver: AccountId.Type, expectedCallGas: Long?): Gas {
        var send = BigInteger.valueOf(config.actionReceipt.first)
        var execution = BigInteger.valueOf(config.actionReceipt.second)
        fun add(cost: Pair<Long, Long>, times: Long = 1) {
            send += BigInteger.valueOf(cost.first) * BigInteger.valueOf(times)
            execution += BigInteger.valueOf(cost.second) * BigInteger.valueOf(times)
        }
        for (action in actions) {
            when (action) {
                is Action.Transfer -> {
                    add(config.transfer)
                    when (receiver) {
                        AccountId.Type.Named -> Unit
                        AccountId.Type.NearImplicit -> {
                            add(config.createAccount)
                            add(config.addFullAccessKey)
                        }
                        AccountId.Type.EthImplicit, AccountId.Type.Deterministic -> add(config.createAccount)
                    }
                }
                is Action.FunctionCall -> {
                    add(config.functionCall)
                    add(config.functionCallPerByte, (action.methodName.toByteArray().size + action.args.size).toLong())
                    // attached gas comes from a dApp too: a u64 that does not fit a Long must not wrap
                    execution += if (expectedCallGas != null) action.gas.min(BigInteger.valueOf(expectedCallGas)) else action.gas
                }
                else -> add(config.otherAction)
            }
        }
        return Gas(send, execution)
    }

    /**
     * [createsAccount]: the receiver does not exist and a transfer will create it.
     * [expectedCallGas]: gas each function call is expected to use, for the fee only. Unused
     * attached gas is refunded, but the node still requires the balance to buy all of it.
     *
     * The node takes the send part at the block gas price and buys the rest up front at no less
     * than `min_gas_purchase_price` (nearcore `calculate_tx_cost`). The required balance prices
     * the send part at [SEND_GAS_PRICE_MARGIN] times the gas price, so a transaction built on it
     * survives a rising gas price.
     *
     * The account creation charge is not part of the required balance: the node takes it out of
     * the refund for the `create_account` execution gas, bought at the purchase price (nearcore
     * `refund_unspent_gas_and_deposits`, NEP-642). The fee only adds the part of the charge the
     * burnt `create_account` execution gas does not already cover.
     */
    fun estimate(
        actions: List<Action>,
        receiver: AccountId.Type,
        createsAccount: Boolean,
        gasPrice: BigInteger,
        expectedCallGas: Long? = null,
    ): Estimate {
        val full = gas(actions, receiver, expectedCallGas = null)
        val used = gas(actions, receiver, expectedCallGas)
        val creationGasCost = BigInteger.valueOf(config.createAccount.second) * gasPrice
        val charge = if (createsAccount) (config.accountCreationCharge - creationGasCost).max(BigInteger.ZERO) else BigInteger.ZERO
        return Estimate(
            gas = full.total.toLongClamped(),
            fee = used.total * gasPrice + charge,
            requiredBalance = full.send * gasPrice * SEND_GAS_PRICE_MARGIN + full.execution * gasPrice.max(config.minGasPurchasePrice),
        )
    }

    private fun BigInteger.toLongClamped(): Long = min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()

    companion object {
        /**
         * The send part of the required balance is priced at this multiple of the block gas price.
         * The price may rise by at most 0.5% a block (nearcore `compute_next_gas_price`) between
         * the estimate and inclusion; doubling covers about 139 blocks of steady growth. The
         * execution part needs no margin while the block price stays below the purchase price.
         */
        val SEND_GAS_PRICE_MARGIN: BigInteger = BigInteger.TWO
    }
}
