package io.horizontalsystems.nearkit.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

internal object ApiClient {

    private val logger = Logger.getLogger("NearKit")

    const val USER_AGENT = "near-android-kit/1.0 (HorizontalSystems; +https://github.com/horizontalsystems/near-android-kit)"

    fun build(userAgent: String = USER_AGENT): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor { message -> logger.info(message) }
            .setLevel(HttpLoggingInterceptor.Level.BASIC)

        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                )
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
