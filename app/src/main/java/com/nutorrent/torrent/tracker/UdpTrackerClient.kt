package com.nutorrent.torrent.tracker

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.random.Random

/**
 * BEP 15 UDP Tracker Protocol implementation.
 * Performs two-phase connect and announce over DatagramSocket.
 */
class UdpTrackerClient(
    private val socketTimeoutMs: Int = 2500
) : TrackerClient {

    companion object {
        const val PROTOCOL_ID = 0x41727101980L
        const val ACTION_CONNECT = 0
        const val ACTION_ANNOUNCE = 1
        const val ACTION_SCRAPE = 2
        const val ACTION_ERROR = 3
    }

    override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse = withContext(Dispatchers.IO) {
        val uri = try {
            URI(request.trackerUrl)
        } catch (e: Exception) {
            throw AppError.TrackerFailure(request.trackerUrl, "Invalid tracker URL: ${e.message}")
        }

        val host = uri.host ?: throw AppError.TrackerFailure(request.trackerUrl, "Missing host in tracker URL")
        val port = if (uri.port != -1) uri.port else 80

        Lg.d(LogCategory.TRACKER, "[TRACKER] UDP Announcing to $host:$port")

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = socketTimeoutMs

            val addresses = try {
                InetAddress.getAllByName(host)
            } catch (e: Exception) {
                throw AppError.TrackerFailure(request.trackerUrl, "Host not found: ${e.message}")
            }
            // Prefer IPv4 address for BitTorrent UDP trackers to avoid IPv6 dropping on cellular/broadband
            val serverAddress = addresses.firstOrNull { it is java.net.Inet4Address }
                ?: addresses.firstOrNull()
                ?: throw AppError.TrackerFailure(request.trackerUrl, "No IP address for host: $host")

            // Step 1: Connect Phase with retransmission
            val connectTxId = Random.nextInt()
            val connectReqBuffer = ByteBuffer.allocate(16).apply {
                order(ByteOrder.BIG_ENDIAN)
                putLong(PROTOCOL_ID)
                putInt(ACTION_CONNECT)
                putInt(connectTxId)
            }

            val connectReqPacket = DatagramPacket(
                connectReqBuffer.array(),
                connectReqBuffer.capacity(),
                serverAddress,
                port
            )

            val connectRespBytes = ByteArray(64)
            var connectRespPacket: DatagramPacket? = null
            for (attempt in 0..1) {
                try {
                    socket.send(connectReqPacket)
                    val p = DatagramPacket(connectRespBytes, connectRespBytes.size)
                    socket.receive(p)
                    connectRespPacket = p
                    break
                } catch (e: SocketTimeoutException) {
                    if (attempt == 1) throw e
                }
            }
            val safeConnectPacket = connectRespPacket ?: throw AppError.TrackerTimeout(request.trackerUrl, SocketTimeoutException())

            if (safeConnectPacket.length < 16) {
                throw AppError.TrackerFailure(request.trackerUrl, "Connect response too short: ${safeConnectPacket.length} bytes")
            }

            val connectRespBuf = ByteBuffer.wrap(connectRespBytes, 0, safeConnectPacket.length).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
            val connectRespAction = connectRespBuf.getInt()
            val connectRespTxId = connectRespBuf.getInt()

            if (connectRespTxId != connectTxId) {
                throw AppError.TrackerFailure(request.trackerUrl, "Connect transaction ID mismatch")
            }

            if (connectRespAction == ACTION_ERROR) {
                val errorMsg = String(connectRespBytes, 8, safeConnectPacket.length - 8, Charsets.UTF_8)
                throw AppError.TrackerFailure(request.trackerUrl, "Tracker error: $errorMsg")
            }

            if (connectRespAction != ACTION_CONNECT) {
                throw AppError.TrackerFailure(request.trackerUrl, "Expected connect action (0), got $connectRespAction")
            }

            val connectionId = connectRespBuf.getLong()

            // Step 2: Announce Phase with retransmission
            val announceTxId = Random.nextInt()
            val announceReqBuf = ByteBuffer.allocate(98).apply {
                order(ByteOrder.BIG_ENDIAN)
                putLong(connectionId)
                putInt(ACTION_ANNOUNCE)
                putInt(announceTxId)
                put(request.infoHash)
                put(request.peerId)
                putLong(request.downloaded)
                putLong(request.left)
                putLong(request.uploaded)
                val eventCode = when (request.event) {
                    TrackerEvent.REGULAR -> 0
                    TrackerEvent.COMPLETED -> 1
                    TrackerEvent.STARTED -> 2
                    TrackerEvent.STOPPED -> 3
                }
                putInt(eventCode)
                putInt(0) // IP address (0 = default)
                putInt(Random.nextInt()) // key
                putInt(200) // num_want (explicitly request up to 200 peers)
                putShort(request.port.toShort())
            }

            val announceReqPacket = DatagramPacket(
                announceReqBuf.array(),
                announceReqBuf.capacity(),
                serverAddress,
                port
            )

            val announceRespBytes = ByteArray(4096)
            var announceRespPacket: DatagramPacket? = null
            for (attempt in 0..1) {
                try {
                    socket.send(announceReqPacket)
                    val p = DatagramPacket(announceRespBytes, announceRespBytes.size)
                    socket.receive(p)
                    announceRespPacket = p
                    break
                } catch (e: SocketTimeoutException) {
                    if (attempt == 1) throw e
                }
            }
            val safeAnnouncePacket = announceRespPacket ?: throw AppError.TrackerTimeout(request.trackerUrl, SocketTimeoutException())

            if (safeAnnouncePacket.length < 20) {
                throw AppError.TrackerFailure(request.trackerUrl, "Announce response too short: ${safeAnnouncePacket.length} bytes")
            }

            val announceRespBuf = ByteBuffer.wrap(announceRespBytes, 0, announceRespPacket.length).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
            val announceRespAction = announceRespBuf.getInt()
            val announceRespTxId = announceRespBuf.getInt()

            if (announceRespTxId != announceTxId) {
                throw AppError.TrackerFailure(request.trackerUrl, "Announce transaction ID mismatch")
            }

            if (announceRespAction == ACTION_ERROR) {
                val errorMsg = String(announceRespBytes, 8, announceRespPacket.length - 8, Charsets.UTF_8)
                throw AppError.TrackerFailure(request.trackerUrl, "Tracker error: $errorMsg")
            }

            if (announceRespAction != ACTION_ANNOUNCE) {
                throw AppError.TrackerFailure(request.trackerUrl, "Expected announce action (1), got $announceRespAction")
            }

            val interval = announceRespBuf.getInt()
            val leechers = announceRespBuf.getInt()
            val seeders = announceRespBuf.getInt()

            val peers = mutableListOf<PeerEndpoint>()
            while (announceRespBuf.remaining() >= 6) {
                val ip0 = announceRespBuf.get().toInt() and 0xFF
                val ip1 = announceRespBuf.get().toInt() and 0xFF
                val ip2 = announceRespBuf.get().toInt() and 0xFF
                val ip3 = announceRespBuf.get().toInt() and 0xFF
                val peerPort = announceRespBuf.getShort().toInt() and 0xFFFF
                if (peerPort > 0) {
                    peers.add(PeerEndpoint("$ip0.$ip1.$ip2.$ip3", peerPort))
                }
            }

            Lg.i(LogCategory.TRACKER, "[TRACKER] UDP response: peers=${peers.size} interval=${interval}s")

            TrackerAnnounceResponse(
                intervalSeconds = interval,
                minIntervalSeconds = null,
                seeders = seeders,
                leechers = leechers,
                peers = peers
            )
        } catch (e: AppError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AppError.TrackerTimeout(request.trackerUrl, e)
        } catch (e: UnknownHostException) {
            throw AppError.TrackerFailure(request.trackerUrl, "Host not found: ${e.message}")
        } catch (e: Exception) {
            throw AppError.TrackerFailure(request.trackerUrl, e.message ?: "UDP tracker error")
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {}
        }
    }
}
