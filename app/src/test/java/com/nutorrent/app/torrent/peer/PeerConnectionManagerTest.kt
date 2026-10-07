package com.nutorrent.app.torrent.peer

import com.nutorrent.app.core.model.PeerBitfield
import com.nutorrent.app.core.model.PeerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class PeerConnectionManagerTest {

    @Test
    fun `test known peers are added and activeCount only reflects completed handshakes`() {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dummyListener = object : PeerConnectionListener {
            override fun onHandshakeCompleted(peer: PeerConnection) {}
            override fun onBitfield(peer: PeerConnection, bitfield: PeerBitfield) {}
            override fun onHave(peer: PeerConnection, pieceIndex: Int) {}
            override fun onPieceBlock(peer: PeerConnection, pieceIndex: Int, begin: Int, block: ByteArray) {}
        }

        val manager = PeerConnectionManager(
            infoHash = ByteArray(20) { 1 },
            ourPeerId = ByteArray(20) { 2 },
            totalPieces = 10,
            ourBitfield = PeerBitfield(10),
            scope = testScope,
            maxConnections = 5,
            listener = dummyListener
        )

        assertEquals(0, manager.totalKnownCount)
        assertEquals(0, manager.activeCount)

        val peers = listOf(
            PeerEndpoint("192.168.1.1", 6881),
            PeerEndpoint("192.168.1.2", 6881),
            PeerEndpoint("192.168.1.3", 6881)
        )

        manager.addPeers(peers)
        assertEquals(3, manager.totalKnownCount)
        // Until TCP handshake is actually completed, activeCount must not prematurely claim active peers
        assertEquals(0, manager.activeCount)

        // Duplicate peers are ignored
        manager.addPeers(listOf(PeerEndpoint("192.168.1.1", 6881)))
        assertEquals(3, manager.totalKnownCount)

        manager.disconnectAll()
        assertEquals(0, manager.activeCount)

        testScope.cancel()
    }
}
