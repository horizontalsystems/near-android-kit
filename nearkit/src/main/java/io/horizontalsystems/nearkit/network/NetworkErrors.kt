package io.horizontalsystems.nearkit.network

import retrofit2.HttpException
import java.io.IOException
import java.net.UnknownHostException

internal object NetworkErrors {

    /**
     * True for failures that come from the device's connectivity or a briefly overloaded service
     * rather than from the chain or the kit: DNS not ready after the app resumes, a dropped
     * socket, a timeout, a rate limit or a 5xx. These clear themselves and are not worth showing
     * as a sync error right away.
     */
    fun isTransient(error: Throwable): Boolean {
        val cause = (error as? NoEndpointAvailable)?.cause ?: error
        return cause is IOException || isRateLimited(cause) || (cause is HttpException && cause.code() in 500..599)
    }

    fun isRateLimited(error: Throwable): Boolean = error is HttpException && error.code() == 429

    /** Seconds the server asked to wait before the next request, when it said so. */
    fun retryAfterSeconds(error: Throwable): Long? =
        (error as? HttpException)?.response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()

    /** DNS failure: the network is usually just not up yet. */
    fun isDnsFailure(error: Throwable): Boolean = error is UnknownHostException
}
