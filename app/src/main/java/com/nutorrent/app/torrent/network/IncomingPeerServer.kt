package com.nutorrent.app.torrent.network

import com.nutorrent.app.core.util.Lg
import com.nutorrent.app.core.util.LogCategory
import com.nutorrent.app.torrent.peer.PeerHandshake
import com.nutorrent.app.torrent.peer.PeerWireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages an incoming TCP server socket for the BitTorrent protocol (BEP 3).
 * Accepts peer connections, demuxes by info_hash, and delegates to the appropriate TorrentSession.
 */
class IncomingPeerServer(
    private val scope: CoroutineScope,
    private val preferredPort: Int = 6881
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var listenJob: Job? = null

    var boundPort: Int = 0
        private set

    // infoHash hex -> (Socket, PeerHandshake) -> Boolean
    private val sessionHandlers = ConcurrentHashMap<String, (Socket, PeerHandshake) -> Boolean>()

    fun registerSession(infoHash: ByteArray, handler: (Socket, PeerHandshake) -> Boolean) {
        val hex = infoHash.joinToString("") { "%02x".format(it) }
        sessionHandlers[hex] = handler
        Lg.d(LogCategory.NETWORK, "[INCOMING_SERVER] Registered session $hex (port $boundPort)")
    }

    fun unregisterSession(infoHash: ByteArray) {
        val hex = infoHash.joinToString("") { "%02x".format(it) }
        sessionHandlers.remove(hex)
        Lg.d(LogCategory.NETWORK, "[INCOMING_SERVER] Unregistered session $hex")
    }

    @Synchronized
    fun start(portRange: IntRange = 6881..6889): Int {
        if (isRunning.get() && boundPort > 0) return boundPort

        var bound: ServerSocket? = null
        for (port in portRange) {
            try {
                bound = ServerSocket(port)
                boundPort = port
                break
            } catch (ignored: Exception) {
                // Port busy, try next
            }
        }

        if (bound == null) {
            try {
                bound = ServerSocket(0) // System-assigned port fallback
                boundPort = bound.localPort
            } catch (e: Exception) {
                Lg.e(LogCategory.NETWORK, "[INCOMING_SERVER] Failed to bind ServerSocket on any port: ${e.message}")
                return 0
            }
        }

        serverSocket = bound
        isRunning.set(true)
        Lg.i(LogCategory.NETWORK, "[INCOMING_SERVER] Listening for incoming peers on TCP port $boundPort")

        listenJob = scope.launch(Dispatchers.IO) {
            acceptLoop(bound)
        }

        return boundPort
    }

    private suspend fun CoroutineScope.acceptLoop(server: ServerSocket) {
        while (isActive && isRunning.get() && !server.isClosed) {
            try {
                val clientSocket = server.accept()
                scope.launch(Dispatchers.IO) {
                    handleIncomingClient(clientSocket)
                }
            } catch (e: Exception) {
                if (isRunning.get() && !server.isClosed) {
                    Lg.w(LogCategory.NETWORK, "[INCOMING_SERVER] Accept error: ${e.message}")
                }
                break
            }
        }
    }

    private fun handleIncomingClient(socket: Socket) {
        try {
            // Give 5 seconds for remote peer to send their 68-byte handshake
            socket.soTimeout = 5000
            val handshake = PeerWireCodec.readHandshake(socket.getInputStream())
            val hashHex = handshake.infoHash.joinToString("") { "%02x".format(it) }

            val handler = sessionHandlers[hashHex]
            if (handler != null) {
                val accepted = handler(socket, handshake)
                if (!accepted) {
                    Lg.d(LogCategory.NETWORK, "[INCOMING_SERVER] Session $hashHex declined connection from ${socket.remoteSocketAddress}")
                    socket.close()
                }
            } else {
                Lg.d(LogCategory.NETWORK, "[INCOMING_SERVER] No active session for infoHash $hashHex, dropping ${socket.remoteSocketAddress}")
                socket.close()
            }
        } catch (e: Exception) {
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    @Synchronized
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            listenJob?.cancel()
            try {
                serverSocket?.close()
            } catch (ignored: Exception) {}
            serverSocket = null
            boundPort = 0
            sessionHandlers.clear()
            Lg.i(LogCategory.NETWORK, "[INCOMING_SERVER] Stopped")
        }
    }
}
