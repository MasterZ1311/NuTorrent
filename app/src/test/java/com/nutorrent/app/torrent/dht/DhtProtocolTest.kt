package com.nutorrent.app.torrent.dht

import com.nutorrent.app.core.model.PeerEndpoint
import com.nutorrent.app.torrent.bencode.BencodeElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DhtProtocolTest {

    @Test
    fun `test contact compact serialization and parsing`() {
        val id = DhtNodeId(ByteArray(20) { 0x55 })
        val endpoint = PeerEndpoint("192.168.1.42", 6881)
        val contact = DhtContact(id, endpoint)

        val compactBytes = contact.toCompactBytes()
        assertEquals(26, compactBytes.size)

        val parsed = DhtContact.fromCompactBytes(compactBytes)
        assertNotNull(parsed)
        assertEquals(id, parsed!!.id)
        assertEquals("192.168.1.42", parsed.endpoint.ip)
        assertEquals(6881, parsed.endpoint.port)
    }

    @Test
    fun `test krpc query and response bencode serialization`() {
        val txId = byteArrayOf(0x01, 0x02)
        val query = KrpcMessage.Query(
            transactionId = txId,
            queryType = KrpcMessage.QUERY_PING,
            arguments = mapOf("id" to BencodeElement.BString(ByteArray(20) { 0x11 }))
        )

        val encodedBytes = query.toBencodeBytes()
        val parsed = KrpcMessage.parse(encodedBytes) as? KrpcMessage.Query
        assertNotNull(parsed)
        assertArrayEquals(txId, parsed!!.transactionId)
        assertEquals("ping", parsed.queryType)

        // Test response
        val response = KrpcMessage.Response(
            transactionId = txId,
            returnValues = mapOf("id" to BencodeElement.BString(ByteArray(20) { 0x22 }))
        )
        val respEncoded = response.toBencodeBytes()
        val parsedResp = KrpcMessage.parse(respEncoded) as? KrpcMessage.Response
        assertNotNull(parsedResp)
        assertArrayEquals(txId, parsedResp!!.transactionId)
    }

    @Test
    fun `test token manager generation and validation`() {
        val tm = DhtTokenManager(rotationIntervalMs = 1000)
        val endpoint1 = PeerEndpoint("1.2.3.4", 5000)
        val endpoint2 = PeerEndpoint("5.6.7.8", 5000)

        val token1 = tm.generateToken(endpoint1)
        val token2 = tm.generateToken(endpoint2)

        assertTrue(tm.validateToken(token1, endpoint1))
        assertFalse(tm.validateToken(token1, endpoint2))
        assertTrue(tm.validateToken(token2, endpoint2))
    }

    @Test
    fun `test loopback communication between two dht nodes`() = runBlocking {
        val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val node1 = DhtNode(port = 0, scope = scope1)
        val node2 = DhtNode(port = 0, scope = scope2)

        try {
            node1.start()
            node2.start()

            // Wait a brief moment for sockets to bind
            delay(100)

            val port2 = node2.boundPort
            assertTrue(port2 > 0)

            // Node 1 pings Node 2
            val endpoint2 = PeerEndpoint("127.0.0.1", port2)
            val responderId = node1.ping(endpoint2)

            assertNotNull(responderId)
            assertEquals(node2.localNodeId, responderId)

            // Verify Node 1 now has Node 2 in its routing table
            val closest = node1.routingTable.findClosestNodes(node2.localNodeId)
            assertTrue(closest.any { it.id == node2.localNodeId })

            // Test get_peers between the two nodes
            val infoHash = ByteArray(20) { 0x77 }
            val getPeersResult = node1.sendGetPeers(endpoint2, infoHash)
            assertNotNull(getPeersResult)
            assertEquals(node2.localNodeId, getPeersResult!!.responderId)
            assertNotNull(getPeersResult.token)

            // Announce peer from Node 1 to Node 2
            val announced = node1.sendAnnouncePeer(endpoint2, infoHash, 6881, getPeersResult.token!!)
            assertTrue(announced)

            // Now Node 1 asks Node 2 for peers again; Node 2 should now return the announced peer!
            val getPeersResult2 = node1.sendGetPeers(endpoint2, infoHash)
            assertNotNull(getPeersResult2)
            assertTrue(getPeersResult2!!.peers.isNotEmpty())
            assertEquals(6881, getPeersResult2.peers[0].port)
        } finally {
            node1.stop()
            node2.stop()
            scope1.cancel()
            scope2.cancel()
        }
    }
}
