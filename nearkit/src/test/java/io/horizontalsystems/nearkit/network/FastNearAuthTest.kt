package io.horizontalsystems.nearkit.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class FastNearAuthTest {

    private val seen = mutableListOf<Pair<String, String?>>()
    private var rejectKey = false

    private val client = OkHttpClient.Builder()
        .addInterceptor(FastNearAuth("secret"))
        .addInterceptor { chain ->
            val request = chain.request()
            val auth = request.header("Authorization")
            seen += request.url.host to auth
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (rejectKey && auth != null) 403 else 200)
                .message("")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun get(url: String): Int = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun keyGoesToFastNearHostsOnly() {
        get("https://free.rpc.fastnear.com/")
        get("https://tx.main.fastnear.com/v0/account")
        get("https://near.drpc.org/")
        get("https://fastnear.com.evil.example/")

        assertEquals(
            listOf(
                "free.rpc.fastnear.com" to "Bearer secret",
                "tx.main.fastnear.com" to "Bearer secret",
                "near.drpc.org" to null,
                "fastnear.com.evil.example" to null,
            ),
            seen,
        )
    }

    @Test
    fun rejectedKeyIsDroppedAndTheRequestRepeatedWithoutIt() {
        rejectKey = true
        assertEquals(200, get("https://api.fastnear.com/v1/account/alice.near/ft"))
        assertEquals(200, get("https://api.fastnear.com/v1/account/alice.near/ft"))

        assertEquals(
            listOf("Bearer secret", null, null),
            seen.map { it.second },
        )
    }
}
