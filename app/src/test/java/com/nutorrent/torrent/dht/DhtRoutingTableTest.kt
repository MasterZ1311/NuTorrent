package com.nutorrent.torrent.dht

import com.nutorrent.core.model.PeerEndpoint
import org.junit.Assert.*
import org.junit.Test

class DhtRoutingTableTest {

    @Test
    fun `test add contacts and find closest`() {
        val localId = DhtNodeId(ByteArray(20) { 0x00 })
        val table = DhtRoutingTable(localId, k = 4)

        // Cannot add self
        assertFalse(table.addContact(DhtContact(localId, PeerEndpoint("127.0.0.1", 6881))))

        // Add 3 contacts
        val c1 = DhtContact(DhtNodeId(ByteArray(20).apply { this[19] = 1 }), PeerEndpoint("10.0.0.1", 5001))
        val c2 = DhtContact(DhtNodeId(ByteArray(20).apply { this[19] = 2 }), PeerEndpoint("10.0.0.2", 5002))
        val c3 = DhtContact(DhtNodeId(ByteArray(20).apply { this[19] = 3 }), PeerEndpoint("10.0.0.3", 5003))

        assertTrue(table.addContact(c1))
        assertTrue(table.addContact(c2))
        assertTrue(table.addContact(c3))
        assertEquals(3, table.totalContacts())

        // Re-adding existing contact updates lastSeen and returns true
        assertTrue(table.addContact(c1))
        assertEquals(3, table.totalContacts())

        val closest = table.findClosestNodes(localId, maxCount = 2)
        assertEquals(2, closest.size)
        assertEquals(c1.id, closest[0].id)
        assertEquals(c2.id, closest[1].id)
    }

    @Test
    fun `test bucket splitting and capacity growth`() {
        val localId = DhtNodeId(ByteArray(20) { 0x00 })
        val table = DhtRoutingTable(localId, k = 2)

        // Add 5 nodes with different prefixes
        for (i in 1..5) {
            val bytes = ByteArray(20)
            bytes[0] = (i * 20).toByte()
            val contact = DhtContact(DhtNodeId(bytes), PeerEndpoint("192.168.1.$i", 6000 + i))
            table.addContact(contact)
        }

        // Table should have grown beyond initial k=2 thanks to splitting
        assertTrue(table.totalContacts() > 2)
    }

    @Test
    fun `test eviction of dead contacts`() {
        val localId = DhtNodeId(ByteArray(20) { 0x00 })
        val table = DhtRoutingTable(localId, k = 2)

        val id1 = DhtNodeId(ByteArray(20).apply { this[19] = 1 })
        val c1 = DhtContact(id1, PeerEndpoint("10.0.0.1", 5001))
        table.addContact(c1)

        assertEquals(1, table.totalContacts())
        table.onQueryFailed(id1)
        table.onQueryFailed(id1)
        assertEquals(1, table.totalContacts())

        // 3rd failure evicts contact
        table.onQueryFailed(id1)
        assertEquals(0, table.totalContacts())
    }
}
