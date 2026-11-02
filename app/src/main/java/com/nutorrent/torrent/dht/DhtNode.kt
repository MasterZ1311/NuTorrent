package com.nutorrent.torrent.dht

import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.torrent.bencode.BencodeElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class GetPeersResult(
    val responderId: DhtNodeId,
    val token: ByteArray?,
    val peers: List<PeerEndpoint>,
    val closestNodes: List<DhtContact>
)

class DhtNode(
    val localNodeId: DhtNodeId = DhtNodeId.generateRandom(),
    val port: Int = 0, // 0 for automatic system port
    private val scope: CoroutineScope
) {
    val routingTable = DhtRoutingTable(localNodeId)
    val transactionManager = DhtTransactionManager()
    val tokenManager = DhtTokenManager()

    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var listenerJob: Job? = null

    // Storage for peers announced to us: InfoHash hex -> (PeerEndpoint -> ExpireTime)
    private val announcedPeers = ConcurrentHashMap<String, ConcurrentHashMap<PeerEndpoint, Long>>()

    companion object {
        val BOOTSTRAP_NODES = listOf(
            PeerEndpoint("router.bittorrent.com", 6881),
            PeerEndpoint("dht.transmissionbt.com", 6881),
            PeerEndpoint("router.utorrent.com", 6881),
            PeerEndpoint("dht.libtorrent.org", 25401),
            PeerEndpoint("dht.aelitis.com", 6881)
        )
    }

    val boundPort: Int
        get() = socket?.localPort ?: port

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                socket = if (port > 0) DatagramSocket(port) else DatagramSocket()
                Lg.i(LogCategory.DHT, "DHT Node started on port ${socket?.localPort} with ID: ${localNodeId.toHex()}")
            } catch (e: Exception) {
                Lg.e(LogCategory.DHT, "Failed to bind DHT socket on port $port: ${e.message}")
                isRunning.set(false)
                return@launch
            }

            // Bootstrap on startup
            launch(Dispatchers.IO) {
                bootstrap()
            }

            receiveLoop()
        }
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(4096)
        while (isRunning.get() && socket?.isClosed == false) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)

                val payload = packet.data.copyOfRange(0, packet.length)
                val senderEndpoint = PeerEndpoint(packet.address.hostAddress ?: "", packet.port)

                val message = KrpcMessage.parse(payload) ?: continue
                handleIncomingMessage(message, senderEndpoint)
            } catch (e: SocketException) {
                // Socket closed or network change
                break
            } catch (e: Exception) {
                if (e is CancellationException) break
                Lg.w(LogCategory.DHT, "Error reading DHT packet: ${e.message}")
            }
        }
    }

    private fun handleIncomingMessage(message: KrpcMessage, sender: PeerEndpoint) {
        when (message) {
            is KrpcMessage.Response -> {
                transactionManager.onResponseReceived(message)
                val idBytes = (message.returnValues["id"] as? BencodeElement.BString)?.value
                if (idBytes != null && idBytes.size == 20) {
                    routingTable.addContact(DhtContact(DhtNodeId(idBytes), sender))
                }
            }
            is KrpcMessage.Error -> {
                transactionManager.onResponseReceived(message)
            }
            is KrpcMessage.Query -> {
                handleIncomingQuery(message, sender)
            }
        }
    }

    private fun handleIncomingQuery(query: KrpcMessage.Query, sender: PeerEndpoint) {
        val senderIdBytes = (query.arguments["id"] as? BencodeElement.BString)?.value
        if (senderIdBytes != null && senderIdBytes.size == 20) {
            routingTable.addContact(DhtContact(DhtNodeId(senderIdBytes), sender))
        }

        when (query.queryType) {
            KrpcMessage.QUERY_PING -> {
                val response = KrpcMessage.Response(
                    transactionId = query.transactionId,
                    returnValues = mapOf(
                        "id" to BencodeElement.BString(localNodeId.bytes)
                    )
                )
                sendMessage(response, sender)
            }
            KrpcMessage.QUERY_FIND_NODE -> {
                val targetBytes = (query.arguments["target"] as? BencodeElement.BString)?.value
                val target = if (targetBytes != null && targetBytes.size == 20) {
                    DhtNodeId(targetBytes)
                } else {
                    localNodeId
                }
                val closest = routingTable.findClosestNodes(target)
                val response = KrpcMessage.Response(
                    transactionId = query.transactionId,
                    returnValues = mapOf(
                        "id" to BencodeElement.BString(localNodeId.bytes),
                        "nodes" to BencodeElement.BString(DhtContact.toCompactNodes(closest))
                    )
                )
                sendMessage(response, sender)
            }
            KrpcMessage.QUERY_GET_PEERS -> {
                val infoHashBytes = (query.arguments["info_hash"] as? BencodeElement.BString)?.value
                if (infoHashBytes == null || infoHashBytes.size != 20) {
                    sendError(query.transactionId, KrpcMessage.ERROR_PROTOCOL, "Invalid info_hash", sender)
                    return
                }

                val token = tokenManager.generateToken(sender)
                val hashHex = infoHashBytes.joinToString("") { "%02x".format(it) }
                val peersMap = announcedPeers[hashHex]

                val now = System.currentTimeMillis()
                val activePeers = peersMap?.entries?.filter { it.value > now }?.map { it.key } ?: emptyList()

                val returnValues = mutableMapOf<String, BencodeElement>(
                    "id" to BencodeElement.BString(localNodeId.bytes),
                    "token" to BencodeElement.BString(token)
                )

                if (activePeers.isNotEmpty()) {
                    // Send peers in 'values' list
                    val compactList = activePeers.take(50).map { peer ->
                        val parts = peer.ip.split(".")
                        val buf = ByteBuffer.allocate(6).apply {
                            order(ByteOrder.BIG_ENDIAN)
                            for (p in parts) put((p.toInt() and 0xFF).toByte())
                            putShort(peer.port.toShort())
                        }
                        BencodeElement.BString(buf.array())
                    }
                    returnValues["values"] = BencodeElement.BList(compactList)
                } else {
                    // Send closest nodes in 'nodes'
                    val closest = routingTable.findClosestNodes(DhtNodeId(infoHashBytes))
                    returnValues["nodes"] = BencodeElement.BString(DhtContact.toCompactNodes(closest))
                }

                val response = KrpcMessage.Response(query.transactionId, returnValues)
                sendMessage(response, sender)
            }
            KrpcMessage.QUERY_ANNOUNCE_PEER -> {
                val infoHashBytes = (query.arguments["info_hash"] as? BencodeElement.BString)?.value
                val tokenBytes = (query.arguments["token"] as? BencodeElement.BString)?.value
                val portInt = (query.arguments["port"] as? BencodeElement.BInteger)?.value?.toInt()

                if (infoHashBytes == null || infoHashBytes.size != 20 || tokenBytes == null || portInt == null) {
                    sendError(query.transactionId, KrpcMessage.ERROR_PROTOCOL, "Malformed announce_peer", sender)
                    return
                }

                if (!tokenManager.validateToken(tokenBytes, sender)) {
                    sendError(query.transactionId, KrpcMessage.ERROR_PROTOCOL, "Invalid or expired token", sender)
                    return
                }

                val impliedPort = (query.arguments["implied_port"] as? BencodeElement.BInteger)?.value?.toInt() ?: 0
                val peerPort = if (impliedPort != 0) sender.port else portInt

                val hashHex = infoHashBytes.joinToString("") { "%02x".format(it) }
                val peersMap = announcedPeers.computeIfAbsent(hashHex) { ConcurrentHashMap() }
                // 30 minute peer registration lifetime
                peersMap[PeerEndpoint(sender.ip, peerPort)] = System.currentTimeMillis() + 30 * 60 * 1000L

                val response = KrpcMessage.Response(
                    transactionId = query.transactionId,
                    returnValues = mapOf("id" to BencodeElement.BString(localNodeId.bytes))
                )
                sendMessage(response, sender)
            }
            else -> {
                sendError(query.transactionId, KrpcMessage.ERROR_METHOD_UNKNOWN, "Unknown method: ${query.queryType}", sender)
            }
        }
    }

    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        Lg.d(LogCategory.DHT, "Bootstrapping DHT node...")
        kotlinx.coroutines.coroutineScope {
            BOOTSTRAP_NODES.forEach { node ->
                launch {
                    try {
                        sendFindNode(node, localNodeId)
                    } catch (e: Exception) {
                        // Ignore individual bootstrap failures
                    }
                }
            }
        }
        Lg.i(LogCategory.DHT, "DHT bootstrap completed. Known nodes: ${routingTable.totalContacts()}")
    }

    suspend fun ping(endpoint: PeerEndpoint): DhtNodeId? = withContext(Dispatchers.IO) {
        val (txId, deferred) = transactionManager.createTransaction()
        val query = KrpcMessage.Query(
            transactionId = txId,
            queryType = KrpcMessage.QUERY_PING,
            arguments = mapOf("id" to BencodeElement.BString(localNodeId.bytes))
        )
        sendMessage(query, endpoint)

        val resp = transactionManager.awaitResponse(txId, deferred) as? KrpcMessage.Response ?: return@withContext null
        val idBytes = (resp.returnValues["id"] as? BencodeElement.BString)?.value
        if (idBytes != null && idBytes.size == 20) {
            val nodeId = DhtNodeId(idBytes)
            routingTable.addContact(DhtContact(nodeId, endpoint))
            nodeId
        } else null
    }

    suspend fun sendFindNode(endpoint: PeerEndpoint, target: DhtNodeId): List<DhtContact> = withContext(Dispatchers.IO) {
        val (txId, deferred) = transactionManager.createTransaction()
        val query = KrpcMessage.Query(
            transactionId = txId,
            queryType = KrpcMessage.QUERY_FIND_NODE,
            arguments = mapOf(
                "id" to BencodeElement.BString(localNodeId.bytes),
                "target" to BencodeElement.BString(target.bytes)
            )
        )
        sendMessage(query, endpoint)

        val resp = transactionManager.awaitResponse(txId, deferred) as? KrpcMessage.Response ?: return@withContext emptyList()
        val idBytes = (resp.returnValues["id"] as? BencodeElement.BString)?.value
        if (idBytes != null && idBytes.size == 20) {
            routingTable.addContact(DhtContact(DhtNodeId(idBytes), endpoint))
        }

        val nodesBytes = (resp.returnValues["nodes"] as? BencodeElement.BString)?.value
        if (nodesBytes != null) {
            val contacts = DhtContact.parseCompactNodes(nodesBytes)
            for (c in contacts) {
                routingTable.addContact(c)
            }
            contacts
        } else emptyList()
    }

    suspend fun sendGetPeers(endpoint: PeerEndpoint, infoHash: ByteArray): GetPeersResult? = withContext(Dispatchers.IO) {
        val (txId, deferred) = transactionManager.createTransaction()
        val query = KrpcMessage.Query(
            transactionId = txId,
            queryType = KrpcMessage.QUERY_GET_PEERS,
            arguments = mapOf(
                "id" to BencodeElement.BString(localNodeId.bytes),
                "info_hash" to BencodeElement.BString(infoHash)
            )
        )
        sendMessage(query, endpoint)

        val resp = transactionManager.awaitResponse(txId, deferred) as? KrpcMessage.Response ?: return@withContext null
        val idBytes = (resp.returnValues["id"] as? BencodeElement.BString)?.value ?: return@withContext null
        if (idBytes.size != 20) return@withContext null

        val responderId = DhtNodeId(idBytes)
        routingTable.addContact(DhtContact(responderId, endpoint))

        val token = (resp.returnValues["token"] as? BencodeElement.BString)?.value

        val peers = mutableListOf<PeerEndpoint>()
        val values = resp.returnValues["values"] as? BencodeElement.BList
        if (values != null) {
            for (elem in values.value) {
                val peerBytes = (elem as? BencodeElement.BString)?.value ?: continue
                if (peerBytes.size >= 6) {
                    val ip0 = peerBytes[0].toInt() and 0xFF
                    val ip1 = peerBytes[1].toInt() and 0xFF
                    val ip2 = peerBytes[2].toInt() and 0xFF
                    val ip3 = peerBytes[3].toInt() and 0xFF
                    val port = ((peerBytes[4].toInt() and 0xFF) shl 8) or (peerBytes[5].toInt() and 0xFF)
                    if (port > 0) {
                        peers.add(PeerEndpoint("$ip0.$ip1.$ip2.$ip3", port))
                    }
                }
            }
        }

        val closestNodes = mutableListOf<DhtContact>()
        val nodesBytes = (resp.returnValues["nodes"] as? BencodeElement.BString)?.value
        if (nodesBytes != null) {
            val parsedNodes = DhtContact.parseCompactNodes(nodesBytes)
            for (c in parsedNodes) {
                routingTable.addContact(c)
                closestNodes.add(c)
            }
        }

        GetPeersResult(responderId, token, peers, closestNodes)
    }

    suspend fun sendAnnouncePeer(
        endpoint: PeerEndpoint,
        infoHash: ByteArray,
        port: Int,
        token: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        val (txId, deferred) = transactionManager.createTransaction()
        val query = KrpcMessage.Query(
            transactionId = txId,
            queryType = KrpcMessage.QUERY_ANNOUNCE_PEER,
            arguments = mapOf(
                "id" to BencodeElement.BString(localNodeId.bytes),
                "info_hash" to BencodeElement.BString(infoHash),
                "port" to BencodeElement.BInteger(port.toLong()),
                "token" to BencodeElement.BString(token)
            )
        )
        sendMessage(query, endpoint)

        val resp = transactionManager.awaitResponse(txId, deferred)
        resp is KrpcMessage.Response
    }

    /**
     * Performs iterative Kademlia lookup for peers downloading [infoHash].
     * Emits discovered peers as a Flow.
     */
    fun findPeers(infoHash: ByteArray): Flow<PeerEndpoint> = channelFlow {
        val target = DhtNodeId(infoHash)
        val queried = ConcurrentHashMap.newKeySet<PeerEndpoint>()
        val candidates = CopyOnWriteArrayList<DhtContact>()

        // Seed with closest known contacts from local routing table
        candidates.addAll(routingTable.findClosestNodes(target, maxCount = 24))

        // If few candidates, bootstrap from well-known DHT routers using find_node
        if (candidates.size < 8) {
            kotlinx.coroutines.coroutineScope {
                BOOTSTRAP_NODES.forEach { bNode ->
                    launch {
                        try {
                            val discovered = sendFindNode(bNode, target)
                            for (c in discovered) {
                                if (c.endpoint !in queried && candidates.none { it.endpoint == c.endpoint }) {
                                    candidates.add(c)
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore individual bootstrap failures
                        }
                    }
                }
            }
        }

        var rounds = 0
        val announcePort = if (boundPort > 0) boundPort else 6881

        while (candidates.isNotEmpty() && rounds < 15 && isActive) {
            rounds++
            // Pick up to alpha = 8 unqueried closest candidates
            val batch = candidates.filter { it.endpoint !in queried }.take(8)
            if (batch.isEmpty()) break

            for (c in batch) {
                queried.add(c.endpoint)
            }

            kotlinx.coroutines.coroutineScope {
                batch.forEach { contact ->
                    launch {
                        try {
                            val result = sendGetPeers(contact.endpoint, infoHash)
                            if (result != null) {
                                for (peer in result.peers) {
                                    send(peer)
                                }
                                if (result.token != null && result.peers.isNotEmpty()) {
                                    launch {
                                        try {
                                            sendAnnouncePeer(contact.endpoint, infoHash, announcePort, result.token)
                                        } catch (ignored: Exception) {}
                                    }
                                }
                                for (node in result.closestNodes) {
                                    if (node.endpoint !in queried && candidates.none { it.endpoint == node.endpoint }) {
                                        candidates.add(node)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            routingTable.onQueryFailed(contact.id)
                        }
                    }
                }
            }

            // Sort remaining candidates by distance to target
            val sorted = candidates.sortedWith { a, b -> target.compareDistance(a.id, b.id) }
            candidates.clear()
            candidates.addAll(sorted)
        }
    }.flowOn(Dispatchers.IO)

    private fun resolveAddress(ipOrHost: String): InetAddress? {
        return try {
            val addrs = InetAddress.getAllByName(ipOrHost)
            addrs.firstOrNull { it is java.net.Inet4Address } ?: addrs.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun sendMessage(msg: KrpcMessage, target: PeerEndpoint) {
        try {
            val bytes = msg.toBencodeBytes()
            val addr = resolveAddress(target.ip) ?: return
            val packet = DatagramPacket(bytes, bytes.size, addr, target.port)
            socket?.send(packet)
        } catch (e: Exception) {
            // Log warning or ignore network drop
        }
    }

    private fun sendError(txId: ByteArray, code: Int, message: String, target: PeerEndpoint) {
        val err = KrpcMessage.Error(txId, code, message)
        sendMessage(err, target)
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            listenerJob?.cancel()
            listenerJob = null
            transactionManager.clear()
            try {
                socket?.close()
            } catch (ignored: Exception) {}
            socket = null
        }
    }
}
