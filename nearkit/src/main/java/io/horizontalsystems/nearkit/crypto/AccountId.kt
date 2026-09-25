package io.horizontalsystems.nearkit.crypto

/**
 * NEAR account id rules (https://nomicon.io/DataStructures/Account): 2-64 characters, lowercase
 * alphanumeric parts separated by `.`, `-` or `_`, with no leading, trailing or doubled separators.
 */
object AccountId {
    private val VALID = Regex("^(([a-z\\d]+[-_])*[a-z\\d]+\\.)*([a-z\\d]+[-_])*[a-z\\d]+$")
    private val NEAR_IMPLICIT = Regex("^[0-9a-f]{64}$")
    private val ETH_IMPLICIT = Regex("^0x[0-9a-f]{40}$")
    private val DETERMINISTIC = Regex("^0s[0-9a-f]{40}$")

    enum class Type { Named, NearImplicit, EthImplicit, Deterministic }

    fun isValid(accountId: String): Boolean =
        accountId.length in 2..64 && VALID.matches(accountId)

    /** Null for an invalid id. */
    fun type(accountId: String): Type? = when {
        !isValid(accountId) -> null
        NEAR_IMPLICIT.matches(accountId) -> Type.NearImplicit
        ETH_IMPLICIT.matches(accountId) -> Type.EthImplicit
        DETERMINISTIC.matches(accountId) -> Type.Deterministic
        else -> Type.Named
    }

    /**
     * Implicit accounts come into existence when they first receive NEAR. A named account must be
     * created before anything can be sent to it, so a transfer to a missing named account fails.
     */
    fun isImplicit(accountId: String): Boolean = when (type(accountId)) {
        Type.NearImplicit, Type.EthImplicit -> true
        else -> false
    }

    /** Throws [IllegalArgumentException] when [accountId] is not a valid account id. */
    fun validate(accountId: String) {
        require(isValid(accountId)) { "Invalid NEAR account id: $accountId" }
    }
}
