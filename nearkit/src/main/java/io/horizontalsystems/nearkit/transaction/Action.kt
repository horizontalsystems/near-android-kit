package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.nearkit.crypto.PublicKey
import java.math.BigInteger

/**
 * Transaction actions, in Borsh enum order. [tag] is the variant index nearcore uses; the order
 * is part of the wire format and must not change.
 */
sealed class Action(val tag: Int) {
    object CreateAccount : Action(0)

    class DeployContract(val code: ByteArray) : Action(1)

    class FunctionCall(
        val methodName: String,
        val args: ByteArray,
        val gas: BigInteger,
        val deposit: BigInteger,
    ) : Action(2) {
        val argsText: String get() = String(args, Charsets.UTF_8)
    }

    class Transfer(val deposit: BigInteger) : Action(3)

    class Stake(val stake: BigInteger, val publicKey: PublicKey) : Action(4)

    class AddKey(val publicKey: PublicKey, val accessKey: AccessKey) : Action(5)

    class DeleteKey(val publicKey: PublicKey) : Action(6)

    class DeleteAccount(val beneficiaryId: String) : Action(7)

    // Delegate (8), DeployGlobalContract (9), UseGlobalContract (10) and later variants are not
    // built by the kit; decoding a transaction that contains one fails with UnsupportedAction.
}

class AccessKey(val nonce: BigInteger, val permission: AccessKeyPermission)

sealed class AccessKeyPermission {
    class FunctionCall(
        /** Yocto NEAR the key may spend on gas; null for unlimited. */
        val allowance: BigInteger?,
        val receiverId: String,
        val methodNames: List<String>,
    ) : AccessKeyPermission()

    object FullAccess : AccessKeyPermission()
}
