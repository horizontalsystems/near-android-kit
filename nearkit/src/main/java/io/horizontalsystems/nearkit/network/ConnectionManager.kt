package io.horizontalsystems.nearkit.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Tracks whether the device has validated internet access. Registers on start() and
 * unregisters on stop(); a stop()/start() cycle gets a fresh callback registration.
 */
class ConnectionManager(context: Context) {

    interface Listener {
        fun onConnectionChange()
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var listener: Listener? = null

    @Volatile
    var isConnected = false
        private set

    private val lock = Any()
    private var hasValidInternet = false
    private var hasConnection = false
    private var callback: ConnectionStatusCallback? = null

    fun start() {
        synchronized(lock) {
            if (callback != null) return
            isConnected = getInitialConnectionStatus()
            val cb = ConnectionStatusCallback()
            callback = cb
            connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }
    }

    fun stop() {
        synchronized(lock) {
            val current = callback ?: return
            callback = null
            try {
                connectivityManager.unregisterNetworkCallback(current)
            } catch (e: Exception) {
                // already unregistered
            }
        }
    }

    private fun getInitialConnectionStatus(): Boolean {
        val network = connectivityManager.activeNetwork ?: run {
            hasConnection = false
            hasValidInternet = false
            return false
        }

        hasConnection = true
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        hasValidInternet = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false

        return hasValidInternet
    }

    inner class ConnectionStatusCallback : ConnectivityManager.NetworkCallback() {

        private val activeNetworks: MutableList<android.net.Network> = mutableListOf()

        override fun onLost(network: android.net.Network) {
            super.onLost(network)
            synchronized(lock) {
                if (callback !== this) return
                activeNetworks.removeAll { it == network }
                hasConnection = activeNetworks.isNotEmpty()
            }
            updatedConnectionState()
        }

        override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            synchronized(lock) {
                if (callback !== this) return
                hasValidInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            updatedConnectionState()
        }

        override fun onAvailable(network: android.net.Network) {
            super.onAvailable(network)
            synchronized(lock) {
                if (callback !== this) return
                if (activeNetworks.none { it == network }) {
                    activeNetworks.add(network)
                }
                hasConnection = activeNetworks.isNotEmpty()
            }
            updatedConnectionState()
        }
    }

    private fun updatedConnectionState() {
        val changed = synchronized(lock) {
            val oldValue = isConnected
            isConnected = hasConnection && hasValidInternet
            oldValue != isConnected
        }
        if (changed) {
            listener?.onConnectionChange()
        }
    }
}
