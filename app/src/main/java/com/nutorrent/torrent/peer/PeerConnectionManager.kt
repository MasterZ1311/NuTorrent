package com.nutorrent.torrent.peer

import com.nutorrent.core.model.BlockRequest
import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.model.PeerStateInfo
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class PeerConnectionManager(
    val infoHash: ByteArray,
    val ourPeerId: ByteArray,
    val totalPieces: Int,
    val ourBitfield: PeerBitfield,
    private val scope: CoroutineScope,
    var maxConnections: Int = 60,
    val listener: PeerConnectionListener,
    val bandwidthManager: com.nutorrent.torrent.network.BandwidthManager? = null
) {
    private val mutex = Mutex()
    private val knownPeers = CopyOnWriteArrayList<PeerEndpoint>()
    private val activeConnections = ConcurrentHashMap<PeerEndpoint, PeerConnection>()
    private val pendingConnections = ConcurrentHashMap<PeerEndpoint, PeerConnection>()
    private val failedPeers = ConcurrentHashMap<PeerEndpoint, Long>()

    val activeCount: Int
        get() = activeConnections.size

    val totalKnownCount: Int
        get() = knownPeers.size

    val connectedSeedsCount: Int
        get() = activeConnections.values.count { totalPieces > 0 && it.peerBitfield.countPieces() >= totalPieces }

    fun addPeers(endpoints: List<PeerEndpoint>) {
        var addedAny = false
        for (ep in endpoints) {
            if (!knownPeers.contains(ep)) {
                knownPeers.add(ep)
                addedAny = true
            }
        }
        if (addedAny) {
            reconnectIfNeeded()
        }
    }

    fun reconnectIfNeeded() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                // Sweep stale connections (> 120s inactivity)
                val staleList = activeConnections.values.filter { it.isStale() }
                for (stale in staleList) {
                    stale.close()
                    activeConnections.remove(stale.endpoint)
                    failedPeers[stale.endpoint] = System.currentTimeMillis()
                }

                val needed = maxConnections - (activeConnections.size + pendingConnections.size)
                if (needed <= 0) return@withLock

                val now = System.currentTimeMillis()
                failedPeers.entries.removeIf { now - it.value > 15_000 }

                val candidates = knownPeers.filter { ep ->
                    !activeConnections.containsKey(ep) &&
                    !pendingConnections.containsKey(ep) &&
                    !failedPeers.containsKey(ep)
                }.take(needed)

                for (ep in candidates) {
                    connectToPeer(ep)
                }
            }
        }
    }

    private fun createWrappedListener(): PeerConnectionListener = object : PeerConnectionListener {
        override fun onHandshakeCompleted(peer: PeerConnection) {
            pendingConnections.remove(peer.endpoint)
            activeConnections[peer.endpoint] = peer
            listener.onHandshakeCompleted(peer)
            if (activeConnections.size < maxConnections) {
                reconnectIfNeeded()
            }
        }

        override fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield) {
            listener.onBitfield(peer, bitfield)
        }

        override fun onHave(peer: PeerConnection, pieceIndex: Int) {
            listener.onHave(peer, pieceIndex)
        }

        override fun onAllowedFast(peer: PeerConnection, pieceIndex: Int) {
            listener.onAllowedFast(peer, pieceIndex)
        }

        override fun onBlockRejected(peer: PeerConnection, request: BlockRequest) {
            listener.onBlockRejected(peer, request)
        }

        override fun onChokeStateChanged(peer: PeerConnection, isChokingUs: Boolean, cancelledRequests: List<BlockRequest>) {
            listener.onChokeStateChanged(peer, isChokingUs, cancelledRequests)
        }

        override fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray) {
            listener.onPieceBlock(peer, pieceIndex, begin, block)
        }

        override fun onExtensionHandshake(peer: PeerConnection, handshake: ExtensionHandshake) {
            listener.onExtensionHandshake(peer, handshake)
        }

        override fun onMetadataMessage(peer: PeerConnection, message: MetadataMessage) {
            listener.onMetadataMessage(peer, message)
        }

        override fun onPexMessage(peer: PeerConnection, payload: ByteArray) {
            listener.onPexMessage(peer, payload)
        }

        override fun onUploadRequest(peer: PeerConnection, pieceIndex: Int, begin: Int, length: Int) {
            listener.onUploadRequest(peer, pieceIndex, begin, length)
        }

        override fun onDisconnected(peer: PeerConnection, error: Throwable?, cancelledRequests: List<BlockRequest>) {
            pendingConnections.remove(peer.endpoint)
            activeConnections.remove(peer.endpoint)
            if (error != null) {
                failedPeers[peer.endpoint] = System.currentTimeMillis()
            }
            listener.onDisconnected(peer, error, cancelledRequests)
            reconnectIfNeeded()
        }
    }

    private fun connectToPeer(endpoint: PeerEndpoint) {
        val conn = PeerConnection(
            endpoint = endpoint,
            infoHash = infoHash,
            ourPeerId = ourPeerId,
            totalPieces = totalPieces,
            ourBitfield = ourBitfield,
            listener = createWrappedListener(),
            scope = scope,
            connectTimeoutMs = 3000,
            bandwidthManager = bandwidthManager
        )
        pendingConnections[endpoint] = conn
        conn.start()
    }

    fun acceptIncomingPeer(socket: java.net.Socket, handshake: PeerHandshake): Boolean {
        val remoteIp = socket.inetAddress?.hostAddress ?: return false
        val remotePort = socket.port
        val endpoint = PeerEndpoint(remoteIp, remotePort)

        if (activeConnections.containsKey(endpoint) || pendingConnections.containsKey(endpoint)) {
            try { socket.close() } catch (ignored: Exception) {}
            return false
        }

        if (activeConnections.size + pendingConnections.size >= maxConnections) {
            try { socket.close() } catch (ignored: Exception) {}
            return false
        }

        if (!knownPeers.contains(endpoint)) {
            knownPeers.add(endpoint)
        }

        val conn = PeerConnection(
            endpoint = endpoint,
            infoHash = infoHash,
            ourPeerId = ourPeerId,
            totalPieces = totalPieces,
            ourBitfield = ourBitfield,
            listener = createWrappedListener(),
            scope = scope,
            connectTimeoutMs = 5000,
            bandwidthManager = bandwidthManager,
            incomingSocket = socket,
            incomingHandshake = handshake
        )
        pendingConnections[endpoint] = conn
        conn.start()
        return true
    }

    fun updateMaxConnections(newLimit: Int) {
        maxConnections = newLimit
        reconnectIfNeeded()
    }

    fun broadcastHave(pieceIndex: Int) {
        for (conn in activeConnections.values) {
            conn.sendHave(pieceIndex)
        }
    }

    fun broadcastPex(message: PexMessage) {
        val payload = message.toBencodeBytes()
        for (conn in activeConnections.values) {
            conn.sendPex(payload)
        }
    }

    fun getConnectedEndpoints(): List<PeerEndpoint> = activeConnections.keys.toList()

    fun getActiveConnections(): List<PeerConnection> = activeConnections.values.toList()

    fun getPeerStateInfos(): List<PeerStateInfo> =
        (activeConnections.values + pendingConnections.values).map { it.stateInfo }

    fun disconnectAll() {
        for (conn in activeConnections.values) {
            conn.disconnect()
        }
        for (conn in pendingConnections.values) {
            conn.disconnect()
        }
        activeConnections.clear()
        pendingConnections.clear()
    }
}
