package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.nearkit.borsh.BorshReader
import io.horizontalsystems.nearkit.borsh.BorshWriter
import io.horizontalsystems.nearkit.crypto.Base58
import io.horizontalsystems.nearkit.crypto.Hashes
import io.horizontalsystems.nearkit.crypto.PublicKey
import java.math.BigInteger

/** An unsigned NEAR transaction (the V0 layout, which every node accepts). */
class Transaction(
    val signerId: String,
    val publicKey: PublicKey,
    val nonce: BigInteger,
    val receiverId: String,
    val blockHash: ByteArray,
    val actions: List<Action>,
) {
    init {
        require(blockHash.size == 32) { "Block hash must be 32 bytes" }
    }

    fun encode(): ByteArray = BorshWriter().also { writeTo(it) }.toByteArray()

    /** SHA-256 of the Borsh encoding: the value that is signed and, base58-encoded, the tx hash. */
    fun hash(): ByteArray = Hashes.sha256(encode())

    fun hashString(): String = Base58.encode(hash())

    internal fun writeTo(w: BorshWriter) {
        w.string(signerId)
        w.publicKey(publicKey)
        w.u64(nonce)
        w.string(receiverId)
        w.fixedBytes(blockHash)
        w.vec(actions) { action(it) }
    }

    class UnsupportedAction(tag: Int) : Exception("Unsupported action variant $tag")

    companion object {
        /** Decodes a Borsh-encoded V0 transaction. Throws on trailing bytes or unknown variants. */
        fun decode(data: ByteArray): Transaction {
            val r = BorshReader(data)
            val tx = read(r)
            require(r.remaining == 0) { "Trailing bytes after transaction" }
            return tx
        }

        internal fun read(r: BorshReader): Transaction = Transaction(
            signerId = r.string(),
            publicKey = r.publicKey(),
            nonce = r.u64(),
            receiverId = r.string(),
            blockHash = r.fixedBytes(32),
            actions = r.vec { action() },
        )
    }
}

/** A transaction with its Ed25519 signature, ready for `send_tx`. */
class SignedTransaction(val transaction: Transaction, val signature: ByteArray) {
    init {
        require(signature.size == 64) { "Ed25519 signature must be 64 bytes" }
    }

    val hash: String get() = transaction.hashString()

    fun encode(): ByteArray = BorshWriter().also {
        transaction.writeTo(it)
        it.u8(KEY_TYPE_ED25519)
        it.fixedBytes(signature)
    }.toByteArray()

    companion object {
        fun decode(data: ByteArray): SignedTransaction {
            val r = BorshReader(data)
            val tx = Transaction.read(r)
            val keyType = r.u8()
            require(keyType == KEY_TYPE_ED25519) { "Unsupported signature key type $keyType" }
            val signature = r.fixedBytes(64)
            require(r.remaining == 0) { "Trailing bytes after signed transaction" }
            return SignedTransaction(tx, signature)
        }
    }
}

internal const val KEY_TYPE_ED25519 = 0

internal fun BorshWriter.publicKey(key: PublicKey) {
    u8(KEY_TYPE_ED25519)
    fixedBytes(key.data)
}

internal fun BorshReader.publicKey(): PublicKey {
    val keyType = u8()
    require(keyType == KEY_TYPE_ED25519) { "Unsupported key type $keyType" }
    return PublicKey(fixedBytes(32))
}

internal fun BorshWriter.action(action: Action) {
    u8(action.tag)
    when (action) {
        Action.CreateAccount -> Unit
        is Action.DeployContract -> bytes(action.code)
        is Action.FunctionCall -> {
            string(action.methodName)
            bytes(action.args)
            u64(action.gas)
            u128(action.deposit)
        }
        is Action.Transfer -> u128(action.deposit)
        is Action.Stake -> {
            u128(action.stake)
            publicKey(action.publicKey)
        }
        is Action.AddKey -> {
            publicKey(action.publicKey)
            u64(action.accessKey.nonce)
            when (val permission = action.accessKey.permission) {
                is AccessKeyPermission.FunctionCall -> {
                    u8(0)
                    option(permission.allowance) { u128(it) }
                    string(permission.receiverId)
                    vec(permission.methodNames) { string(it) }
                }
                AccessKeyPermission.FullAccess -> u8(1)
            }
        }
        is Action.DeleteKey -> publicKey(action.publicKey)
        is Action.DeleteAccount -> string(action.beneficiaryId)
    }
}

internal fun BorshReader.action(): Action = when (val tag = u8()) {
    0 -> Action.CreateAccount
    1 -> Action.DeployContract(bytes())
    2 -> Action.FunctionCall(string(), bytes(), u64(), u128())
    3 -> Action.Transfer(u128())
    4 -> Action.Stake(u128(), publicKey())
    5 -> {
        val key = publicKey()
        val nonce = u64()
        val permission = when (val p = u8()) {
            0 -> AccessKeyPermission.FunctionCall(option { u128() }, string(), vec { string() })
            1 -> AccessKeyPermission.FullAccess
            else -> throw IllegalArgumentException("Invalid access key permission $p")
        }
        Action.AddKey(key, AccessKey(nonce, permission))
    }
    6 -> Action.DeleteKey(publicKey())
    7 -> Action.DeleteAccount(string())
    else -> throw Transaction.UnsupportedAction(tag)
}
