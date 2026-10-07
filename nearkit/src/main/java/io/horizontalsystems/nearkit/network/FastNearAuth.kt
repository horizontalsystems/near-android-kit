package io.horizontalsystems.nearkit.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.logging.Logger

/**
 * Adds a FastNEAR API key to requests for FastNEAR hosts (RPC, API and transactions services
 * accept the same key) and to no other host, so a key never reaches a third-party RPC in the
 * failover list.
 *
 * FastNEAR answers 401/403 to a revoked or expired key even where the keyless tier would serve
 * the request. A rejected key is dropped for the rest of the process and the request repeated
 * without it, so a lapsed subscription degrades to keyless rate limits instead of stopping sync.
 */
internal class FastNearAuth(private val apiKey: String) : Interceptor {

    private val logger = Logger.getLogger("NearKit")

    @Volatile
    private var rejected = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (rejected || !isFastNearHost(request.url.host)) return chain.proceed(request)

        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $apiKey").build())
        if (response.code != 401 && response.code != 403) return response

        logger.warning("FastNEAR rejected the API key (HTTP ${response.code}); continuing without it")
        rejected = true
        response.close()
        return chain.proceed(request)
    }

    companion object {
        fun isFastNearHost(host: String): Boolean = host == "fastnear.com" || host.endsWith(".fastnear.com")
    }
}
