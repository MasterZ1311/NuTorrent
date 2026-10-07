package com.nutorrent.app.network

import com.nutorrent.app.torrent.network.NetworkStatus
import org.junit.Assert.*
import org.junit.Test

class NetworkConnectivityObserverTest {

    @Test
    fun `test network status defaults and flags`() {
        val disconnected = NetworkStatus.DISCONNECTED
        assertFalse(disconnected.isConnected)
        assertFalse(disconnected.isWifi)
        assertFalse(disconnected.isCellular)
        assertFalse(disconnected.isMetered)

        val wifi = NetworkStatus(
            isConnected = true,
            isWifi = true,
            isCellular = false,
            isMetered = false
        )
        assertTrue(wifi.isConnected)
        assertTrue(wifi.isWifi)
        assertFalse(wifi.isCellular)
        assertFalse(wifi.isMetered)

        val cellular = NetworkStatus(
            isConnected = true,
            isWifi = false,
            isCellular = true,
            isMetered = true
        )
        assertTrue(cellular.isConnected)
        assertFalse(cellular.isWifi)
        assertTrue(cellular.isCellular)
        assertTrue(cellular.isMetered)
    }
}
