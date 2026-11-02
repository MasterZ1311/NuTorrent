package com.nutorrent.torrent.lpd

import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Message payload according to BEP 14 (Local Service Discovery).
 */
data class LpdSearchMessage(
    val port: Int,
    val infoHashHex: String,
    val cookie: String?
) {
    fun serialize(): ByteArray {
        val payload = "BT-SEARCH * HTTP/1.1\r\n" +
                "Host: ${LpdSubsystem.MULTICAST_IP}:${LpdSubsystem.MULTICAST_PORT}\r\n" +
                "Port: $port\r\n" +
                "Infohash: ${infoHashHex.lowercase()}\r\n" +
                (if (cookie != null) "cookie: $cookie\r\n" else "") +
                "\r\n\r\n"
        return payload.toByteArray(StandardCharsets.US_ASCII)
    }

    companion object {
        fun parse(rawText: String): LpdSearchMessage? {
            val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || !lines[0].startsWith("BT-SEARCH * HTTP/1.1", ignoreCase = true)) {
                return null
            }

            var port: Int? = null
            var infoHash: String? = null
            var cookie: String? = null

            for (i in 1 until lines.size) {
                val line = lines[i]
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim()
                    val value = line.substring(colonIdx + 1).trim()
                    when {
                        key.equals("Port", ignoreCase = true) -> port = value.toIntOrNull()
                        key.equals("Infohash", ignoreCase = true) -> infoHash = value.lowercase()
                        key.equals("cookie", ignoreCase = true) -> cookie = value
                    }
                }
            }

            if (port != null && port in 1..65535 && infoHash != null && infoHash.length == 40) {
                return LpdSearchMessage(port, infoHash, cookie)
            }
            return null
        }
    }
}

/**
 * Local Peer Discovery (BEP 14) subsystem.
 * Uses UDP Multicast on 239.192.152.143:6771 to discover peers on the same local subnet.
 */
class LpdSubsystem(
    private val scope: CoroutineScope,
    val localCookie: String = UUID.randomUUID().toString().replace("-", "").take(8),
    var localListenPort: Int = 6881,
    var onPeerDiscovered: ((infoHashHex: String, endpoint: PeerEndpoint) -> Unit)? = null
) {
    companion object {
        const val MULTICAST_IP = "239.192.152.143"
        const val MULTICAST_PORT = 6771
        private const val BUFFER_SIZE = 1400
    }

    private var multicastSocket: MulticastSocket? = null
    private var listenJob: Job? = null
    private val multicastGroupAddress: InetAddress by lazy {
        InetAddress.getByName(MULTICAST_IP)
    }

    fun start() {
        if (multicastSocket?.isClosed == false) return

        listenJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MULTICAST_PORT))
                    timeToLive = 4 // Subnet scope
                    joinGroup(multicastGroupAddress)
                }
                multicastSocket = socket
                Lg.i(LogCategory.NETWORK, "LPD subsystem listening on $MULTICAST_IP:$MULTICAST_PORT")

                val buffer = ByteArray(BUFFER_SIZE)
                while (isActive && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

                        val raw = String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII)
                        val msg = LpdSearchMessage.parse(raw)
                        if (msg != null && msg.cookie != localCookie) {
                            val senderHost = packet.address.hostAddress ?: continue
                            val endpoint = PeerEndpoint(senderHost, msg.port)
                            Lg.d(LogCategory.NETWORK, "LPD discovered local peer for ${msg.infoHashHex}: $endpoint")
                            onPeerDiscovered?.invoke(msg.infoHashHex, endpoint)
                        }
                    } catch (e: IOException) {
                        if (socket.isClosed) break
                        Lg.w(LogCategory.NETWORK, "LPD receive warning: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Lg.w(LogCategory.NETWORK, "LPD socket bind failed (multicast might be restricted): ${e.message}")
            }
        }
    }

    suspend fun announce(infoHashHex: String, port: Int = localListenPort) = withContext(Dispatchers.IO) {
        val socket = multicastSocket ?: return@withContext
        try {
            val msg = LpdSearchMessage(port, infoHashHex, localCookie)
            val bytes = msg.serialize()
            val packet = DatagramPacket(bytes, bytes.size, multicastGroupAddress, MULTICAST_PORT)
            socket.send(packet)
            Lg.d(LogCategory.NETWORK, "LPD announced infoHash=$infoHashHex on port $port")
        } catch (e: Exception) {
            Lg.w(LogCategory.NETWORK, "Failed to send LPD multicast announce: ${e.message}")
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        try {
            multicastSocket?.let { s ->
                if (!s.isClosed) {
                    s.leaveGroup(multicastGroupAddress)
                    s.close()
                }
            }
        } catch (e: Exception) {
            // Ignore socket cleanup errors
        }
        multicastSocket = null
        Lg.d(LogCategory.NETWORK, "LPD subsystem stopped")
    }
}
