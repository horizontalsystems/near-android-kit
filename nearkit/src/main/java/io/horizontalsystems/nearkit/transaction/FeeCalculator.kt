package io.horizontalsystems.nearkit.transaction

import com.google.gson.JsonObject
import io.horizontalsystems.nearkit.network.getAsJsonObjectOrNull
import io.horizontalsystems.nearkit.network.optBigInteger
import io.horizontalsystems.nearkit.network.optLong
import java.math.BigInteger

/**
 * What a transaction costs, from the runtime fee config (`EXPERIMENTAL_protocol_config`).
 *
 * Converting the transaction burns the "send" costs and executing its receipt the "execution"
 * costs; a function call also buys the gas attached to it. Gas is bought up front at no less
 * than `min_gas_purchase_price` and burnt at the block gas price, and the difference comes back
 * as a refund. So the balance a send needs is well above what it finally costs.
 */
class FeeCalculator(private val config: Config = Config.DEFAULT) {

    /**
     * @property fee yoctoNEAR the sender ends up paying: gas burnt at the current gas price plus
     *   the account creation charge. For function calls it assumes all attached gas is used.
     * @property requiredBalance yoctoNEAR (besides deposits) the account must hold for the node
     *   to accept the transaction. Use it to compute the maximum sendable amount.
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
     * Total gas for [actions]. A transfer to a NEAR-implicit or ETH-implicit account always pays
     * for creating the account and its key, because at conversion time the runtime cannot know
     * whether the account exists.
     */
    fun gas(actions: List<Action>, receiverIsImplicit: Boolean): Long {
        var gas = config.actionReceipt.total
        for (action in actions) {
            gas += when (action) {
                is Action.Transfer -> config.transfer.total +
                    if (receiverIsImplicit) config.createAccount.total + config.addFullAccessKey.total else 0
                is Action.FunctionCall -> {
                    val bytes = (action.methodName.toByteArray().size + action.args.size).toLong()
                    config.functionCall.total + config.functionCallPerByte.total * bytes + action.gas.toLong()
                }
                else -> config.otherAction.total
            }
        }
        return gas
    }

    /** [createsAccount]: the receiver does not exist and a transfer will create it. */
    fun estimate(actions: List<Action>, receiverIsImplicit: Boolean, createsAccount: Boolean, gasPrice: BigInteger): Estimate {
        val gas = BigInteger.valueOf(gas(actions, receiverIsImplicit))
        val charge = if (createsAccount) config.accountCreationCharge else BigInteger.ZERO
        return Estimate(
            gas = gas.toLong(),
            fee = gas.multiply(gasPrice).add(charge),
            requiredBalance = gas.multiply(gasPrice.max(config.minGasPurchasePrice)).add(charge),
        )
    }

    private val Pair<Long, Long>.total: Long get() = first + second
}
