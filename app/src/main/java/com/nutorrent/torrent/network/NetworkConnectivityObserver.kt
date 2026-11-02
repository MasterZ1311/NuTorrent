package com.nutorrent.torrent.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkStatus(
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val isMetered: Boolean
) {
    companion object {
        val DISCONNECTED = NetworkStatus(
            isConnected = false,
            isWifi = false,
            isCellular = false,
            isMetered = false
        )
    }
}

class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkStatus = MutableStateFlow(getCurrentStatus())
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start(onStatusChanged: ((NetworkStatus) -> Unit)? = null) {
        val cm = connectivityManager ?: return
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateStatus(cm, network, onStatusChanged)
            }

            override fun onLost(network: Network) {
                val status = getCurrentStatus()
                _networkStatus.value = status
                onStatusChanged?.invoke(status)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val isInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

                val status = NetworkStatus(
                    isConnected = isInternet,
                    isWifi = isWifi,
                    isCellular = isCellular,
                    isMetered = isMetered
                )
                _networkStatus.value = status
                onStatusChanged?.invoke(status)
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            // Some environments or unit tests may not support network callbacks
        }
    }

    fun stop() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore unregister errors
            }
        }
        networkCallback = null
    }

    private fun updateStatus(
        cm: ConnectivityManager,
        network: Network,
        onStatusChanged: ((NetworkStatus) -> Unit)?
    ) {
        val caps = cm.getNetworkCapabilities(network)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

        val status = NetworkStatus(
            isConnected = isInternet,
            isWifi = isWifi,
            isCellular = isCellular,
            isMetered = isMetered
        )
        _networkStatus.value = status
        onStatusChanged?.invoke(status)
    }

    fun getCurrentStatus(): NetworkStatus {
        val cm = connectivityManager ?: return NetworkStatus.DISCONNECTED
        val activeNetwork = cm.activeNetwork ?: return NetworkStatus.DISCONNECTED
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkStatus.DISCONNECTED

        return NetworkStatus(
            isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }
}
