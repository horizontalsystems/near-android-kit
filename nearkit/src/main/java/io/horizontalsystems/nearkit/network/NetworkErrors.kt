package io.horizontalsystems.nearkit.network

import java.io.IOException
import java.net.UnknownHostException

internal object NetworkErrors {

    /**
     * True for failures that come from the device's connectivity rather than from the chain or
     * the kit: DNS not ready after the app resumes, a dropped socket, a timeout. These clear
     * themselves within seconds and are not worth showing as a sync error right away.
     */
    fun isTransient(error: Throwable): Boolean {
        val cause = (error as? NoEndpointAvailable)?.cause ?: error
        return cause is IOException
    }

    /** DNS failure: the network is usually just not up yet. */
    fun isDnsFailure(error: Throwable): Boolean = error is UnknownHostException
}
