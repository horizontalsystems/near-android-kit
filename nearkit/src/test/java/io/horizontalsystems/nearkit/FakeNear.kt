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
 * whichever node is asked; an [IOException] answer is thrown as a transport failure.
 */
internal class FakeNear {
    private val answers = mutableMapOf<String, ArrayDeque<Any>>()

    /** Requests nothing was queued for; a test should end with none. */
    val unexpected = mutableListOf<String>()

    /** [key] is an RPC method or a FastNEAR path such as `/v0/transactions`. */
    fun answer(key: String, vararg answer: Any) {
        answers.getOrPut(key) { ArrayDeque() }.addAll(answer)
    }

    private val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val isRpc = request.url.host.startsWith("rpc")
        val key = if (isRpc) {
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            JsonParser.parseString(body).asJsonObject["method"].asString
        } else {
            request.url.encodedPath
        }
        val body = when (val answer = synchronized(answers) { answers[key]?.removeFirstOrNull() }) {
            null -> {
                unexpected += key
                """{"error":"unexpected"}"""
            }
            is IOException -> throw answer
            else -> if (isRpc) """{"jsonrpc":"2.0","id":"nearkit",$answer}""" else answer.toString()
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    val rpcProvider: RpcProvider = RpcProvider.create(listOf(URL("https://rpc1.test/"), URL("https://rpc2.test/")), client)
    val fastNearProvider: FastNearProvider = FastNearProvider.create(URL("https://api.fastnear.test/"), URL("https://tx.fastnear.test/"), null, client)

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
