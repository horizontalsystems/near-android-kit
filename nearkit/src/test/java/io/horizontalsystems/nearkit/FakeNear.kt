package io.horizontalsystems.nearkit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import io.horizontalsystems.nearkit.database.MainDatabase
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.network.FastNearProvider
import io.horizontalsystems.nearkit.network.RpcProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.net.URL

/**
 * Two RPC nodes and the FastNEAR APIs played from inside OkHttp, so send and sync logic runs
 * without a network. Answers are queued per RPC method or FastNEAR path and consumed in order,
 * whichever node is asked; an [IOException] answer is thrown as a transport failure and an
 * [Http] answer is returned with its status code.
 */
internal class FakeNear {
    private val answers = mutableMapOf<String, ArrayDeque<Any>>()

    /** Requests nothing was queued for; a test should end with none. */
    val unexpected = mutableListOf<String>()

    /** Every request as (key, body), in order. */
    val requests = mutableListOf<Pair<String, String>>()

    class Http(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    /** [key] is an RPC method or a FastNEAR path such as `/v0/transactions`. */
    fun answer(key: String, vararg answer: Any) {
        answers.getOrPut(key) { ArrayDeque() }.addAll(answer)
    }

    private val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val isRpc = request.url.host.startsWith("rpc")
        val requestBody = request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() } ?: ""
        val key = if (isRpc) {
            JsonParser.parseString(requestBody).asJsonObject["method"].asString
        } else {
            request.url.encodedPath
        }
        synchronized(requests) { requests += key to requestBody }
        val answer = synchronized(answers) { answers[key]?.removeFirstOrNull() }
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
        val body = when (answer) {
            null -> {
                unexpected += key
                """{"error":"unexpected"}"""
            }
            is IOException -> throw answer
            is Http -> {
                builder.code(answer.code).message("HTTP ${answer.code}")
                answer.headers.forEach { (name, value) -> builder.header(name, value) }
                answer.body
            }
            else -> if (isRpc) """{"jsonrpc":"2.0","id":"nearkit",$answer}""" else answer.toString()
        }
        builder.body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    val rpcProvider: RpcProvider = RpcProvider.create(listOf(URL("https://rpc1.test/"), URL("https://rpc2.test/")), client)
    val fastNearProvider: FastNearProvider = FastNearProvider.create(URL("https://api.fastnear.test/"), URL("https://tx.fastnear.test/"), client)

    companion object {
        fun result(json: String) = "\"result\":$json"

        /** A nearcore handler error, as `send_tx` and `EXPERIMENTAL_tx_status` return them. */
        fun error(cause: String, data: String = "null") =
            "\"error\":{\"name\":\"HANDLER_ERROR\",\"cause\":{\"name\":\"$cause\"},\"code\":-32000,\"message\":\"Server error\",\"data\":$data}"

        fun storage(): Storage = Storage(
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MainDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        )
    }
}
