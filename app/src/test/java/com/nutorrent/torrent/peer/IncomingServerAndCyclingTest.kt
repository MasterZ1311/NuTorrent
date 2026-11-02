package com.nutorrent.torrent.peer

import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.torrent.network.IncomingPeerServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class IncomingServerAndCyclingTest {

    @Test
    fun `test incoming peer server binds port, demuxes info_hash, and accepts incoming peer connection`() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = IncomingPeerServer(testScope)
        val boundPort = server.start()
        assertTrue("Server should bind a valid port", boundPort > 0)

        val targetInfoHash = ByteArray(20) { 0x42.toByte() }
        val targetPeerId = ByteArray(20) { 0x11.toByte() }
        val handshakeReceivedLatch = CountDownLatch(1)
        val handshakeCompletedLatch = CountDownLatch(1)

        val dummyListener = object : PeerConnectionListener {
            override fun onHandshakeCompleted(peer: PeerConnection) {
                handshakeCompletedLatch.countDown()
            }
            override fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield) {}
            override fun onHave(peer: PeerConnection, pieceIndex: Int) {}
            override fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray) {}
        }

        val manager = PeerConnectionManager(
            infoHash = targetInfoHash,
            ourPeerId = targetPeerId,
            totalPieces = 10,
            ourBitfield = PeerBitfield(10),
            scope = testScope,
            maxConnections = 5,
            listener = dummyListener
        )

        server.registerSession(targetInfoHash) { socket, handshake ->
            handshakeReceivedLatch.countDown()
            manager.acceptIncomingPeer(socket, handshake)
        }

        // Simulate remote peer connecting and sending handshake
        val clientSocket = Socket()
        clientSocket.connect(InetSocketAddress("127.0.0.1", boundPort), 3000)
        val clientOut = clientSocket.getOutputStream()
        val clientIn = clientSocket.getInputStream()

        val remotePeerId = ByteArray(20) { 0x99.toByte() }
        val clientHandshake = PeerHandshake(targetInfoHash, remotePeerId)
        PeerWireCodec.writeHandshake(clientOut, clientHandshake)

        // Verify incoming server routed the connection
        assertTrue("Incoming server must receive and route handshake", handshakeReceivedLatch.await(5, TimeUnit.SECONDS))

        // Client reads back our handshake
        val serverResponseHandshake = PeerWireCodec.readHandshake(clientIn)
        assertArrayEquals("Server must reply with matching infoHash", targetInfoHash, serverResponseHandshake.infoHash)
        assertArrayEquals("Server must reply with our peerId", targetPeerId, serverResponseHandshake.peerId)

        // Verify peer handshake completion on manager
        assertTrue("Manager listener must receive onHandshakeCompleted", handshakeCompletedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, manager.activeCount)

        // Verify max connections update works
        manager.updateMaxConnections(50)
        assertEquals(50, manager.maxConnections)

        // Cleanup
        clientSocket.close()
        manager.disconnectAll()
        server.stop()
        testScope.cancel()
    }

    @Test
    fun `test chokeReceivedAt tracking and aggressive cycling conditions`() {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dummyListener = object : PeerConnectionListener {
            override fun onHandshakeCompleted(peer: PeerConnection) {}
            override fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield) {}
            override fun onHave(peer: PeerConnection, pieceIndex: Int) {}
            override fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray) {}
        }

        val peer = PeerConnection(
            endpoint = PeerEndpoint("1.2.3.4", 6881),
            infoHash = ByteArray(20),
            ourPeerId = ByteArray(20),
            totalPieces = 10,
            listener = dummyListener,
            scope = testScope
        )

        // Peer is choked by default
        assertTrue(peer.isChokingUs)
        assertEquals(0L, peer.chokeReceivedAt)

        testScope.cancel()
    }
}
