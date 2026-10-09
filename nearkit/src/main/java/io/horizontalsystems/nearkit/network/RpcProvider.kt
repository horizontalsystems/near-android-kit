package io.horizontalsystems.nearkit.network

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.horizontalsystems.nearkit.models.AccessKeyInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.math.BigInteger
import java.net.URL
import java.util.Base64
import java.util.logging.Logger

/**
 * nearcore JSON-RPC client with endpoint failover. Transport failures and retryable node errors
 * move to the next URL; a deterministic error (unknown account, invalid transaction) is thrown
 * to the caller as [RpcError] without failover, because every node would answer the same.
 */
class RpcProvider private constructor(private val endpoints: List<Endpoint>) {

    private class Endpoint(val url: URL, val api: RpcApi)

    private interface RpcApi {
        @POST("./")
        suspend fun call(@Body body: JsonObject): JsonObject
    }

    private val logger = Logger.getLogger("NearKit")

    @Volatile
    private var preferredIndex = 0

    suspend fun call(method: String, params: JsonElement): JsonElement {
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", "nearkit")
            addProperty("method", method)
            add("params", params)
        }

        var lastError: Throwable? = null
        for (pass in 0 until PASSES) {
            if (pass > 0) {
                // Every host failed to resolve: the device's network is not up yet (typical right
                // after the app resumes). Give it a moment instead of failing the whole sync.
                if (lastError?.let { NetworkErrors.isDnsFailure(it) } != true) break
                delay(DNS_RETRY_DELAY_MS)
            }
            lastError = callOnce(method, request) { return it }
        }
        throw NoEndpointAvailable(lastError)
    }

    /** Tries every endpoint once; invokes [onResult] with the first good result, else returns the last error. */
    private suspend inline fun callOnce(method: String, request: JsonObject, onResult: (JsonElement) -> Unit): Throwable? {
        var lastError: Throwable? = null
        for (attempt in endpoints.indices) {
            val index = (preferredIndex + attempt) % endpoints.size
            val endpoint = endpoints[index]
            try {
                val response = endpoint.api.call(request)
                response.getAsJsonObjectOrNull("error")?.let { throw parseError(it) }
                val result = response.get("result")?.takeUnless { it.isJsonNull }
                    ?: throw InvalidResponse("$method: missing result")
                preferredIndex = index
                onResult(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcError) {
                if (e.isDeterministic) throw e
                logger.info("$method failed on ${endpoint.url}: ${e.message}")
                lastError = e
            } catch (e: Throwable) {
                logger.info("$method failed on ${endpoint.url}: ${e.message}")
                lastError = e
            }
        }
        return lastError
    }

    private fun parseError(error: JsonObject): RpcError {
        val cause = error.getAsJsonObjectOrNull("cause")
        val name = cause?.optString("name") ?: error.optString("name") ?: "UNKNOWN"
        val data = error.get("data")
        val message = data?.takeIf { it.isJsonPrimitive }?.asString ?: error.optString("message")
        return RpcError(name, data, message)
    }

    private suspend fun query(params: JsonObject): JsonObject {
        params.addProperty("finality", "final")
        val result = call("query", params).asJsonObject
        // Older nodes report some view failures inside the result instead of as an error
        result.optString("error")?.let { message ->
            val name = when {
                message.contains("access key") && message.contains("does not exist") -> UNKNOWN_ACCESS_KEY
                message.contains("does not exist") -> UNKNOWN_ACCOUNT
                else -> "QUERY_ERROR"
            }
            throw RpcError(name, null, message)
        }
        return result
    }

    /** Null when the account does not exist (never funded, or deleted). */
    suspend fun viewAccount(accountId: String): AccountView? {
        val result = try {
            query(JsonObject().apply {
                addProperty("request_type", "view_account")
                addProperty("account_id", accountId)
            })
        } catch (e: RpcError) {
            if (e.name == UNKNOWN_ACCOUNT) return null
            throw e
        }
        return AccountView(
            amount = result.requireBigInteger("amount"),
            locked = result.requireBigInteger("locked"),
            storageUsage = result.requireLong("storage_usage"),
            codeHash = result.requireString("code_hash"),
            blockHeight = result.requireLong("block_height"),
        )
    }

    /** Null when the key is not on the account, or the account does not exist. */
    suspend fun viewAccessKey(accountId: String, publicKey: String): AccessKeyView? {
        val result = try {
            query(JsonObject().apply {
                addProperty("request_type", "view_access_key")
                addProperty("account_id", accountId)
                addProperty("public_key", publicKey)
            })
        } catch (e: RpcError) {
            if (e.name == UNKNOWN_ACCESS_KEY || e.name == UNKNOWN_ACCOUNT) return null
            throw e
        }
        val permission = result.get("permission")
        return AccessKeyView(
            nonce = result.requireBigInteger("nonce"),
            isFullAccess = permission?.isJsonPrimitive == true && permission.asString == "FullAccess",
            blockHash = result.requireString("block_hash"),
            blockHeight = result.requireLong("block_height"),
        )
    }

    /** Every key on the account; empty when the account does not exist. */
    suspend fun viewAccessKeyList(accountId: String): List<AccessKeyInfo> {
        val result = try {
            query(JsonObject().apply {
                addProperty("request_type", "view_access_key_list")
                addProperty("account_id", accountId)
            })
        } catch (e: RpcError) {
            if (e.name == UNKNOWN_ACCOUNT) return emptyList()
            throw e
        }
        val keys = result.getAsJsonArray("keys") ?: throw InvalidResponse("view_access_key_list: missing keys")
        return keys.map { element ->
            val key = element.asJsonObject
            val permission = key.getAsJsonObjectOrNull("access_key")?.get("permission")
            AccessKeyInfo(
                publicKey = key.requireString("public_key"),
                isFullAccess = permission?.isJsonPrimitive == true && permission.asString == "FullAccess",
            )
        }
    }

    /** Calls a view method. Returns the raw bytes the contract returned (usually JSON). */
    suspend fun callFunction(contractId: String, methodName: String, argsJson: String = "{}"): ByteArray {
        val result = query(JsonObject().apply {
            addProperty("request_type", "call_function")
            addProperty("account_id", contractId)
            addProperty("method_name", methodName)
            addProperty("args_base64", Base64.getEncoder().encodeToString(argsJson.toByteArray()))
        })
        val array = result.getAsJsonArray("result") ?: throw InvalidResponse("call_function: missing result")
        return ByteArray(array.size()) { array[it].asInt.toByte() }
    }

    suspend fun callFunctionJson(contractId: String, methodName: String, argsJson: String = "{}"): JsonElement {
        val bytes = callFunction(contractId, methodName, argsJson)
        return try {
            JsonParser.parseString(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw InvalidResponse("$contractId.$methodName returned non-JSON")
        }
    }

    suspend fun latestBlock(): BlockHeader {
        val result = call("block", JsonObject().apply { addProperty("finality", "final") }).asJsonObject
        val header = result.getAsJsonObjectOrNull("header") ?: throw InvalidResponse("block: missing header")
        return BlockHeader(
            height = header.requireLong("height"),
            hash = header.requireString("hash"),
            timestampNanos = header.requireString("timestamp_nanosec").toLong(),
            gasPrice = header.requireBigInteger("gas_price"),
        )
    }

    suspend fun protocolConfig(): JsonObject =
        call("EXPERIMENTAL_protocol_config", JsonObject().apply { addProperty("finality", "final") }).asJsonObject

    /**
     * Submits a signed transaction. With [waitUntil] = INCLUDED the node answers once the
     * transaction is in a block; invalid transactions (bad nonce, not enough balance, expired)
     * are rejected with [RpcError] name INVALID_TRANSACTION.
     */
    suspend fun sendTransaction(signedTxBase64: String, waitUntil: String = "INCLUDED"): TxStatus {
        val result = call("send_tx", JsonObject().apply {
            addProperty("signed_tx_base64", signedTxBase64)
            addProperty("wait_until", waitUntil)
        }).asJsonObject
        return TxStatus(result.optString("final_execution_status") ?: waitUntil, result.takeIf { it.has("transaction") })
    }

    /** Null when no node knows the transaction (not yet seen, or dropped). */
    suspend fun transactionStatus(hash: String, senderAccountId: String): TxStatus? {
        val result = try {
            call("EXPERIMENTAL_tx_status", JsonObject().apply {
                addProperty("tx_hash", hash)
                addProperty("sender_account_id", senderAccountId)
                addProperty("wait_until", "NONE")
            }).asJsonObject
        } catch (e: RpcError) {
            if (e.name == UNKNOWN_TRANSACTION) return null
            throw e
        }
        return TxStatus(result.optString("final_execution_status") ?: "NONE", result.takeIf { it.has("transaction") })
    }

    companion object {
        const val UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT"
        const val UNKNOWN_ACCESS_KEY = "UNKNOWN_ACCESS_KEY"
        const val UNKNOWN_TRANSACTION = "UNKNOWN_TRANSACTION"
        const val INVALID_TRANSACTION = "INVALID_TRANSACTION"

        private const val PASSES = 2
        private const val DNS_RETRY_DELAY_MS = 1500L

        fun create(urls: List<URL>, client: OkHttpClient = ApiClient.build()): RpcProvider {
            require(urls.isNotEmpty()) { "At least one RPC URL is required" }
            val endpoints = urls.map { url ->
                val retrofit = Retrofit.Builder()
                    .baseUrl(url.toString().let { if (it.endsWith("/")) it else "$it/" })
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                Endpoint(url, retrofit.create(RpcApi::class.java))
            }
            return RpcProvider(endpoints)
        }
    }
}

internal fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.optString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.requireString(name: String): String =
    optString(name) ?: throw InvalidResponse("missing field '$name'")

internal fun JsonObject.optLong(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { p ->
        if (p.asJsonPrimitive.isNumber) p.asLong else p.asString.toLongOrNull()
    }

internal fun JsonObject.requireLong(name: String): Long =
    optLong(name) ?: throw InvalidResponse("missing field '$name'")

/** Yocto amounts arrive as decimal strings; u128 does not fit a JSON number. */
internal fun JsonObject.optBigInteger(name: String): BigInteger? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString?.toBigIntegerOrNull()

internal fun JsonObject.requireBigInteger(name: String): BigInteger =
    optBigInteger(name) ?: throw InvalidResponse("missing field '$name'")

internal fun JsonObject.optBoolean(name: String): Boolean =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

internal val JsonElement?.isNullOrJsonNull: Boolean get() = this == null || this is JsonNull
