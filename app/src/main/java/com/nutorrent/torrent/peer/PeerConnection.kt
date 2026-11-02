package com.nutorrent.torrent.peer

import com.nutorrent.core.model.BlockRequest
import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.model.PeerStateInfo
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface PeerConnectionListener {
    fun onHandshakeCompleted(peer: PeerConnection)
    fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield)
    fun onHave(peer: PeerConnection, pieceIndex: Int)
    fun onAllowedFast(peer: PeerConnection, pieceIndex: Int) {}
    fun onBlockRejected(peer: PeerConnection, request: BlockRequest) {}
    fun onChokeStateChanged(peer: PeerConnection, isChokingUs: Boolean) {}
    fun onChokeStateChanged(peer: PeerConnection, isChokingUs: Boolean, cancelledRequests: List<BlockRequest>) {
        onChokeStateChanged(peer, isChokingUs)
    }
    fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray)
    fun onDisconnected(peer: PeerConnection, error: Throwable?) {}
    fun onDisconnected(peer: PeerConnection, error: Throwable?, cancelledRequests: List<BlockRequest>) {
        onDisconnected(peer, error)
    }
    fun onExtensionHandshake(peer: PeerConnection, handshake: ExtensionHandshake) {}
    fun onMetadataMessage(peer: PeerConnection, message: MetadataMessage) {}
    fun onPexMessage(peer: PeerConnection, payload: ByteArray) {}
    fun onUploadRequest(peer: PeerConnection, pieceIndex: Int, begin: Int, length: Int) {}
}

class PeerConnection(
    val endpoint: PeerEndpoint,
    val infoHash: ByteArray,
    val ourPeerId: ByteArray,
    val totalPieces: Int,
    val ourBitfield: PeerBitfield? = null,
    val listener: PeerConnectionListener,
    private val scope: CoroutineScope,
    val connectTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 30000,
    val bandwidthManager: com.nutorrent.torrent.network.BandwidthManager? = null,
    private val incomingSocket: Socket? = null,
    private val incomingHandshake: PeerHandshake? = null
) {
    val infoHashHex: String = infoHash.joinToString("") { "%02x".format(it) }
    var successfulBlocksCount: Int = 0
    var failedBlocksCount: Int = 0
    val performanceScore: Int get() = successfulBlocksCount - (failedBlocksCount * 3)

    fun isStale(): Boolean = System.currentTimeMillis() - lastActivityTime > 120_000L

    private var socket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private var readerJob: Job? = null

    var peerId: ByteArray? = null
        private set
    var isChokingUs: Boolean = true
        private set
    var chokeReceivedAt: Long = 0L
        private set
    var isInterestedInUs: Boolean = false
        private set
    var weAreChoking: Boolean = true
        private set
    var weAreInterested: Boolean = false
        private set

    val peerBitfield: PeerBitfield = PeerBitfield(totalPieces)
    val allowedFastPieces = ConcurrentHashMap.newKeySet<Int>()
    val downloadedBytes = AtomicLong(0L)
    val uploadedBytes = AtomicLong(0L)
    val pendingRequests = ConcurrentHashMap<BlockRequest, Long>()
    var lastActivityTime: Long = System.currentTimeMillis()
        private set
    var isHandshakeComplete: Boolean = false
        private set
    var peerSupportsFastExtension: Boolean = false
        private set

    val stateInfo: PeerStateInfo
        get() = PeerStateInfo(
            endpoint = endpoint,
            isConnected = isRunning.get() && socket?.isConnected == true,
            isHandshakeComplete = isHandshakeComplete,
            isChokingUs = isChokingUs,
            isInterestedInUs = isInterestedInUs,
            isWeChoking = weAreChoking,
            isWeInterested = weAreInterested,
            downloadedBytes = downloadedBytes.get(),
            uploadedBytes = uploadedBytes.get(),
            pendingRequestsCount = pendingRequests.size,
            piecesCount = peerBitfield.countPieces()
        )

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        readerJob = scope.launch(Dispatchers.IO) {
            var disconnectError: Throwable? = null
            try {
                if (incomingSocket != null) {
                    handleIncomingConnection(incomingSocket, incomingHandshake)
                } else {
                    connectAndPerformHandshake()
                }
                listener.onHandshakeCompleted(this@PeerConnection)
                sendInterested()
                readLoop()
            } catch (e: Throwable) {
                if (isActive) {
                    Lg.d(LogCategory.PEER, "Peer $endpoint disconnected: ${e.message}")
                    disconnectError = e
                }
            } finally {
                closeInternal(disconnectError)
            }
        }
    }

    var peerExtensions: Map<String, Int> = emptyMap()
        private set

    private suspend fun connectAndPerformHandshake() = withContext(Dispatchers.IO) {
        val s = Socket()
        socket = s
        // Set short 7-second timeout for handshake to avoid stalling connection pool
        s.soTimeout = 7000
        s.connect(InetSocketAddress(endpoint.ip, endpoint.port), connectTimeoutMs)
        Lg.d(LogCategory.PEER, "[PEER] connected to $endpoint")

        val out = BufferedOutputStream(s.getOutputStream())
        val inp = BufferedInputStream(s.getInputStream())
        outputStream = out
        inputStream = inp

        // Send our handshake with BEP 6, BEP 10, BEP 5
        val ourHandshake = PeerHandshake(infoHash, ourPeerId)
        PeerWireCodec.writeHandshake(out, ourHandshake)

        // Read peer handshake
        val peerHandshake = PeerWireCodec.readHandshake(inp)

        // Validate info hash matches
        if (!MessageDigest.isEqual(peerHandshake.infoHash, infoHash)) {
            throw IllegalStateException("Peer info hash mismatch")
        }
        peerId = peerHandshake.peerId
        peerSupportsFastExtension = peerHandshake.supportsFastExtension
        isHandshakeComplete = true
        chokeReceivedAt = System.currentTimeMillis()
        lastActivityTime = System.currentTimeMillis()
        s.soTimeout = readTimeoutMs
        Lg.d(LogCategory.PEER, "[HANDSHAKE] success with $endpoint (FastExtension=$peerSupportsFastExtension)")

        // If peer supports BEP 10 Extension Protocol, send our extension handshake
        if (peerHandshake.supportsExtensionProtocol) {
            val extHandshake = ExtensionHandshake()
            PeerWireCodec.writeMessage(out, PeerMessage.Extended(ExtensionHandshake.ID_HANDSHAKE, extHandshake.toBencodeBytes()))
        }

        // If we have pieces, send our bitfield
        if (ourBitfield != null && ourBitfield.countPieces() > 0) {
            PeerWireCodec.writeMessage(out, PeerMessage.Bitfield(ourBitfield.bytes))
        }
    }

    private suspend fun handleIncomingConnection(s: Socket, preReadHandshake: PeerHandshake?) = withContext(Dispatchers.IO) {
        socket = s
        s.soTimeout = 7000
        val inp = BufferedInputStream(s.getInputStream())
        val out = BufferedOutputStream(s.getOutputStream())
        inputStream = inp
        outputStream = out

        val peerHs = preReadHandshake ?: PeerWireCodec.readHandshake(inp)
        if (!MessageDigest.isEqual(peerHs.infoHash, infoHash)) {
            throw IllegalStateException("Peer info hash mismatch")
        }

        // Send our handshake back
        val ourHandshake = PeerHandshake(infoHash, ourPeerId)
        PeerWireCodec.writeHandshake(out, ourHandshake)

        peerId = peerHs.peerId
        peerSupportsFastExtension = peerHs.supportsFastExtension
        isHandshakeComplete = true
        chokeReceivedAt = System.currentTimeMillis()
        lastActivityTime = System.currentTimeMillis()
        s.soTimeout = readTimeoutMs
        Lg.d(LogCategory.PEER, "[INCOMING_HANDSHAKE] success with $endpoint (FastExtension=$peerSupportsFastExtension)")

        if (peerHs.supportsExtensionProtocol) {
            val extHandshake = ExtensionHandshake()
            PeerWireCodec.writeMessage(out, PeerMessage.Extended(ExtensionHandshake.ID_HANDSHAKE, extHandshake.toBencodeBytes()))
        }

        if (ourBitfield != null && ourBitfield.countPieces() > 0) {
            PeerWireCodec.writeMessage(out, PeerMessage.Bitfield(ourBitfield.bytes))
        }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val inp = inputStream ?: return@withContext

        while (isActive && isRunning.get()) {
            val message = try {
                PeerWireCodec.readMessage(inp)
            } catch (e: SocketTimeoutException) {
                // Read timeout: send keep-alive or check liveness
                sendKeepAlive()
                continue
            } catch (e: EOFException) {
                break
            }

            lastActivityTime = System.currentTimeMillis()

            when (message) {
                is PeerMessage.KeepAlive -> {
                    // Liveness confirmed
                }
                is PeerMessage.Choke -> {
                    isChokingUs = true
                    chokeReceivedAt = System.currentTimeMillis()
                    val chokedRequests = pendingRequests.keys.toList()
                    pendingRequests.clear()
                    listener.onChokeStateChanged(this@PeerConnection, true, chokedRequests)
                }
                is PeerMessage.Unchoke -> {
                    isChokingUs = false
                    chokeReceivedAt = 0L
                    Lg.d(LogCategory.PEER, "[UNCHOKE] received from $endpoint")
                    listener.onChokeStateChanged(this@PeerConnection, false, emptyList())
                }
                is PeerMessage.Interested -> {
                    isInterestedInUs = true
                }
                is PeerMessage.NotInterested -> {
                    isInterestedInUs = false
                }
                is PeerMessage.Have -> {
                    peerBitfield.setPiece(message.pieceIndex, true)
                    listener.onHave(this@PeerConnection, message.pieceIndex)
                }
                is PeerMessage.HaveAll -> {
                    Lg.d(LogCategory.PEER, "[HAVE_ALL] received from $endpoint")
                    peerBitfield.setAll(true)
                    listener.onBitfield(this@PeerConnection, peerBitfield)
                    sendInterested()
                }
                is PeerMessage.HaveNone -> {
                    Lg.d(LogCategory.PEER, "[HAVE_NONE] received from $endpoint")
                    peerBitfield.setAll(false)
                    listener.onBitfield(this@PeerConnection, peerBitfield)
                }
                is PeerMessage.AllowedFast -> {
                    Lg.d(LogCategory.PEER, "[ALLOWED_FAST] piece=${message.pieceIndex} from $endpoint")
                    allowedFastPieces.add(message.pieceIndex)
                    listener.onAllowedFast(this@PeerConnection, message.pieceIndex)
                }
                is PeerMessage.RejectRequest -> {
                    Lg.d(LogCategory.PEER, "[REJECT] piece=${message.index} offset=${message.begin} from $endpoint")
                    val req = BlockRequest(message.index, message.begin, message.length)
                    pendingRequests.remove(req)
                    listener.onBlockRejected(this@PeerConnection, req)
                }
                is PeerMessage.SuggestPiece -> {
                    Lg.d(LogCategory.PEER, "[SUGGEST_PIECE] piece=${message.pieceIndex} from $endpoint")
                }
                is PeerMessage.Bitfield -> {
                    // Update peer bitfield
                    System.arraycopy(
                        message.bitfield,
                        0,
                        peerBitfield.bytes,
                        0,
                        minOf(peerBitfield.bytes.size, message.bitfield.size)
                    )
                    Lg.d(LogCategory.PEER, "[BITFIELD] received from $endpoint")
                    listener.onBitfield(this@PeerConnection, peerBitfield)
                }
                is PeerMessage.Piece -> {
                    Lg.d(LogCategory.PEER, "[PIECE] received piece=${message.index} offset=${message.begin} length=${message.block.size}")
                    bandwidthManager?.acquireDownload(infoHashHex, message.block.size)
                    successfulBlocksCount++
                    downloadedBytes.addAndGet(message.block.size.toLong())
                    val req = BlockRequest(message.index, message.begin, message.block.size)
                    pendingRequests.remove(req)
                    listener.onPieceBlock(this@PeerConnection, message.index, message.begin, message.block)
                }
                is PeerMessage.Extended -> {
                    if (message.extendedId == ExtensionHandshake.ID_HANDSHAKE) {
                        val handshake = ExtensionHandshake.parse(message.payload)
                        peerExtensions = handshake.extensions
                        listener.onExtensionHandshake(this@PeerConnection, handshake)
                    } else if (message.extendedId == ExtensionHandshake.ID_UT_METADATA) {
                        val metadataMsg = MetadataMessage.parse(message.payload)
                        if (metadataMsg != null) {
                            listener.onMetadataMessage(this@PeerConnection, metadataMsg)
                        }
                    } else if (message.extendedId == ExtensionHandshake.ID_UT_PEX) {
                        listener.onPexMessage(this@PeerConnection, message.payload)
                    }
                }
                is PeerMessage.Request -> {
                    listener.onUploadRequest(this@PeerConnection, message.index, message.begin, message.length)
                }
                is PeerMessage.Cancel -> {
                    // Handled if pending upload
                }
            }
        }
    }

    @Synchronized
    fun sendRequest(request: BlockRequest): Boolean {
        if (!isRunning.get() || pendingRequests.containsKey(request)) return false
        val isAllowedFast = peerSupportsFastExtension && allowedFastPieces.contains(request.pieceIndex)
        if (isChokingUs && !isAllowedFast) return false
        val out = outputStream ?: return false

        return try {
            pendingRequests[request] = System.currentTimeMillis()
            Lg.d(LogCategory.PEER, "[REQUEST] piece=${request.pieceIndex} offset=${request.beginOffset} length=${request.length}${if (isAllowedFast) " (AllowedFast)" else ""}")
            PeerWireCodec.writeMessage(out, PeerMessage.Request(request.pieceIndex, request.beginOffset, request.length))
            true
        } catch (e: Exception) {
            pendingRequests.remove(request)
            closeInternal()
            false
        }
    }

    @Synchronized
    fun sendInterested() {
        if (!isRunning.get()) return
        val out = outputStream ?: return
        try {
            weAreInterested = true
            Lg.d(LogCategory.PEER, "[INTEREST] sent to $endpoint")
            PeerWireCodec.writeMessage(out, PeerMessage.Interested)
        } catch (e: Exception) {
            closeInternal()
        }
    }

    @Synchronized
    fun sendHave(pieceIndex: Int) {
        if (!isRunning.get()) return
        val out = outputStream ?: return
        try {
            PeerWireCodec.writeMessage(out, PeerMessage.Have(pieceIndex))
        } catch (e: Exception) {
            closeInternal()
        }
    }

    @Synchronized
    fun sendKeepAlive() {
        if (!isRunning.get()) return
        val out = outputStream ?: return
        try {
            PeerWireCodec.writeMessage(out, PeerMessage.KeepAlive)
        } catch (e: Exception) {
            closeInternal()
        }
    }

    @Synchronized
    fun sendMetadataRequest(piece: Int): Boolean {
        val peerUtMetadataId = peerExtensions["ut_metadata"] ?: return false
        val out = outputStream ?: return false
        return try {
            val payload = MetadataMessage.createRequestBytes(piece)
            PeerWireCodec.writeMessage(out, PeerMessage.Extended(peerUtMetadataId, payload))
            true
        } catch (e: Exception) {
            closeInternal()
            false
        }
    }

    @Synchronized
    fun sendMetadataData(piece: Int, totalSize: Int, data: ByteArray): Boolean {
        val peerUtMetadataId = peerExtensions["ut_metadata"] ?: return false
        val out = outputStream ?: return false
        return try {
            val payload = MetadataMessage.createDataBytes(piece, totalSize, data)
            PeerWireCodec.writeMessage(out, PeerMessage.Extended(peerUtMetadataId, payload))
            true
        } catch (e: Exception) {
            closeInternal()
            false
        }
    }

    @Synchronized
    fun sendMetadataReject(piece: Int): Boolean {
        val peerUtMetadataId = peerExtensions["ut_metadata"] ?: return false
        val out = outputStream ?: return false
        return try {
            val payload = MetadataMessage.createRejectBytes(piece)
            PeerWireCodec.writeMessage(out, PeerMessage.Extended(peerUtMetadataId, payload))
            true
        } catch (e: Exception) {
            closeInternal()
            false
        }
    }

    @Synchronized
    fun sendPex(payload: ByteArray): Boolean {
        val peerPexId = peerExtensions["ut_pex"] ?: return false
        val out = outputStream ?: return false
        return try {
            PeerWireCodec.writeMessage(out, PeerMessage.Extended(peerPexId, payload))
            true
        } catch (e: Exception) {
            closeInternal()
            false
        }
    }

    @Synchronized
    fun sendPiece(index: Int, begin: Int, block: ByteArray): Boolean {
        if (!isRunning.get() || weAreChoking) return false
        val out = outputStream ?: return false
        return try {
            PeerWireCodec.writeMessage(out, PeerMessage.Piece(index, begin, block))
            uploadedBytes.addAndGet(block.size.toLong())
            true
        } catch (e: Exception) {
            closeInternal()
            false
        }
    }

    @Synchronized
    fun sendChoke(choke: Boolean): Boolean {
        if (!isRunning.get()) return false
        val out = outputStream ?: return false
        return try {
            weAreChoking = choke
            val msg = if (choke) PeerMessage.Choke else PeerMessage.Unchoke
            PeerWireCodec.writeMessage(out, msg)
            true
        } catch (e: Exception) {
            closeInternal()
            false
        }
    }

    fun disconnect() {
        closeInternal()
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal(error: Throwable? = null) {
        if (isRunning.compareAndSet(true, false)) {
            val unfulfilled = pendingRequests.keys.toList()
            readerJob?.cancel()
            try {
                inputStream?.close()
                outputStream?.close()
                socket?.close()
            } catch (e: Exception) {
                // Ignore socket close exception
            }
            pendingRequests.clear()
            listener.onDisconnected(this@PeerConnection, error, unfulfilled)
        }
    }
}
