package io.horizontalsystems.nearkit.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.math.BigInteger
import java.net.URL

class FtBalanceInfo(val contractId: String, val balance: BigInteger)

class AccountTxRow(
    val hash: String,
    val blockHeight: Long,
    /** Nanoseconds since the Unix epoch. */
    val blockTimestampNanos: Long,
)

class AccountTxPage(val rows: List<AccountTxRow>, val resumeToken: String?, val totalCount: Long?)

/**
 * FastNEAR indexer APIs. They fill the gaps of the NEAR RPC, which has no per-account history and
 * no token list: https://docs.fastnear.com. Results are indexer data, not consensus state, so
 * the kit uses them for discovery and history and reads balances that move money from the RPC.
 */
class FastNearProvider private constructor(
    private val api: Api,
    private val txApi: TxApi,
) {

    private interface Api {
        @GET("v1/account/{accountId}/ft")
        suspend fun ftBalances(@Path("accountId") accountId: String): JsonObject

        @GET("v0/public_key/{publicKey}")
        suspend fun accountIds(@Path("publicKey") publicKey: String): JsonObject
    }

    private interface TxApi {
        @POST("v0/account")
        suspend fun account(@Body body: JsonObject): JsonObject

        @POST("v0/transactions")
        suspend fun transactions(@Body body: JsonObject): JsonObject
    }

    suspend fun ftBalances(accountId: String): List<FtBalanceInfo> {
        val tokens = api.ftBalances(accountId).get("tokens")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw InvalidResponse("ft: missing tokens")
        return tokens.mapNotNull { element ->
            val token = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val contractId = token.optString("contract_id") ?: return@mapNotNull null
            // the indexer reports an empty balance for contracts it could not read
            val balance = token.optBigInteger("balance") ?: return@mapNotNull null
            FtBalanceInfo(contractId, balance)
        }
    }

    /** Accounts the indexer believes hold [publicKey] as a full-access key. Verify before trusting. */
    suspend fun accountIds(publicKey: String): List<String> {
        val ids = api.accountIds(publicKey).get("account_ids")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw InvalidResponse("public_key: missing account_ids")
        return ids.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
    }

    /**
     * One page of transactions touching [accountId]: signed by it, received by it, or naming it in
     * an event log (token transfers). [fromBlockHeight] is inclusive.
     */
    suspend fun accountTransactions(
        accountId: String,
        fromBlockHeight: Long? = null,
        newestFirst: Boolean,
        resumeToken: String? = null,
        limit: Int = PAGE_LIMIT,
    ): AccountTxPage {
        val body = JsonObject().apply {
            addProperty("account_id", accountId)
            addProperty("desc", newestFirst)
            addProperty("limit", limit)
            fromBlockHeight?.let { addProperty("from_tx_block_height", it) }
            resumeToken?.let { addProperty("resume_token", it) }
        }
        val result = txApi.account(body)
        val rows = result.get("account_txs")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw InvalidResponse("account: missing account_txs")
        return AccountTxPage(
            rows = rows.mapNotNull { element ->
                val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                AccountTxRow(
                    hash = row.optString("transaction_hash") ?: return@mapNotNull null,
                    blockHeight = row.optLong("tx_block_height") ?: return@mapNotNull null,
                    blockTimestampNanos = row.optLong("tx_block_timestamp") ?: 0,
                )
            },
            // the API returns a token even on the last page; an empty page ends the walk
            resumeToken = result.optString("resume_token")?.takeIf { rows.size() > 0 },
            totalCount = result.optLong("txs_count"),
        )
    }

    /** Full transactions with receipts and outcomes, at most [MAX_HASHES_PER_REQUEST] per call. */
    suspend fun transactions(hashes: List<String>): List<JsonObject> {
        require(hashes.size <= MAX_HASHES_PER_REQUEST) { "At most $MAX_HASHES_PER_REQUEST hashes per request" }
        if (hashes.isEmpty()) return emptyList()
        val body = JsonObject().apply { add("tx_hashes", JsonArray().apply { hashes.forEach { add(it) } }) }
        val list = txApi.transactions(body).get("transactions")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw InvalidResponse("transactions: missing transactions")
        return list.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
    }

    companion object {
        const val PAGE_LIMIT = 200
        const val MAX_HASHES_PER_REQUEST = 20

        fun create(apiUrl: URL, txApiUrl: URL, apiKey: String? = null, baseClient: OkHttpClient = ApiClient.build()): FastNearProvider {
            val client = if (apiKey.isNullOrBlank()) baseClient else baseClient.newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $apiKey").build())
                }
                .build()

            fun retrofit(url: URL) = Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return FastNearProvider(
                retrofit(apiUrl).create(Api::class.java),
                retrofit(txApiUrl).create(TxApi::class.java),
            )
        }
    }
}
