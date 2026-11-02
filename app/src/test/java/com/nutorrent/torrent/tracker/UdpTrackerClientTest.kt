package com.nutorrent.torrent.tracker

import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.PeerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpTrackerClientTest {

    @Test
    fun `test udp tracker connect and announce successful lifecycle`() = runBlocking {
        // Start local mock UDP tracker server
        val serverSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val serverPort = serverSocket.localPort

        val serverJob = launch(Dispatchers.IO) {
            try {
                // 1. Receive Connect Request
                val connectBuf = ByteArray(1024)
                val connectPacket = DatagramPacket(connectBuf, connectBuf.size)
                serverSocket.receive(connectPacket)

                val connectIn = ByteBuffer.wrap(connectBuf, 0, connectPacket.length).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                val protocolId = connectIn.getLong()
                val action = connectIn.getInt()
                val txId = connectIn.getInt()

                assertEquals(UdpTrackerClient.PROTOCOL_ID, protocolId)
                assertEquals(UdpTrackerClient.ACTION_CONNECT, action)

                // Send Connect Response
                val connectionId = 0x0102030405060708L
                val connectResp = ByteBuffer.allocate(16).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    putInt(UdpTrackerClient.ACTION_CONNECT)
                    putInt(txId)
                    putLong(connectionId)
                }
                serverSocket.send(DatagramPacket(connectResp.array(), 16, connectPacket.address, connectPacket.port))

                // 2. Receive Announce Request
                val announceBuf = ByteArray(1024)
                val announcePacket = DatagramPacket(announceBuf, announceBuf.size)
                serverSocket.receive(announcePacket)

                val announceIn = ByteBuffer.wrap(announceBuf, 0, announcePacket.length).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                val recConnectionId = announceIn.getLong()
                val announceAction = announceIn.getInt()
                val announceTxId = announceIn.getInt()

                assertEquals(connectionId, recConnectionId)
                assertEquals(UdpTrackerClient.ACTION_ANNOUNCE, announceAction)

                // Send Announce Response with 2 compact peers: 127.0.0.1:5000 and 192.168.1.1:6881
                val announceResp = ByteBuffer.allocate(20 + 12).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    putInt(UdpTrackerClient.ACTION_ANNOUNCE)
                    putInt(announceTxId)
                    putInt(1800) // interval = 1800s
                    putInt(5)    // leechers = 5
                    putInt(42)   // seeders = 42

                    // Peer 1: 127.0.0.1:5000 (0x1388)
                    put(127.toByte()); put(0.toByte()); put(0.toByte()); put(1.toByte())
                    putShort(5000.toShort())

                    // Peer 2: 192.168.1.1:6881 (0x1AE1)
                    put(192.toByte()); put(168.toByte()); put(1.toByte()); put(1.toByte())
                    putShort(6881.toShort())
                }
                serverSocket.send(DatagramPacket(announceResp.array(), announceResp.capacity(), announcePacket.address, announcePacket.port))
            } finally {
                serverSocket.close()
            }
        }

        try {
            val client = UdpTrackerClient(socketTimeoutMs = 5000)
            val request = TrackerAnnounceRequest(
                trackerUrl = "udp://127.0.0.1:$serverPort/announce",
                infoHash = ByteArray(20) { 0x11 },
                peerId = ByteArray(20) { 0x22 },
                downloaded = 0,
                left = 1000,
                uploaded = 0
            )

            val response = client.announce(request)
            assertEquals(1800, response.intervalSeconds)
            assertEquals(42, response.seeders)
            assertEquals(5, response.leechers)
            assertEquals(2, response.peers.size)

            assertEquals("127.0.0.1", response.peers[0].ip)
            assertEquals(5000, response.peers[0].port)

            assertEquals("192.168.1.1", response.peers[1].ip)
            assertEquals(6881, response.peers[1].port)
        } finally {
            serverJob.join()
        }
    }

    @Test
    fun `test udp tracker error response parsing`() = runBlocking {
        val serverSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val serverPort = serverSocket.localPort

        val serverJob = launch(Dispatchers.IO) {
            try {
                val connectBuf = ByteArray(1024)
                val connectPacket = DatagramPacket(connectBuf, connectBuf.size)
                serverSocket.receive(connectPacket)

                val connectIn = ByteBuffer.wrap(connectBuf, 0, connectPacket.length).apply {
                    order(ByteOrder.BIG_ENDIAN)
                }
                connectIn.getLong() // protocol id
                connectIn.getInt()  // action
                val txId = connectIn.getInt()

                // Send Error Response (action = 3)
                val errorMsg = "Torrent not registered"
                val errBytes = errorMsg.toByteArray(Charsets.UTF_8)
                val errResp = ByteBuffer.allocate(8 + errBytes.size).apply {
                    order(ByteOrder.BIG_ENDIAN)
                    putInt(UdpTrackerClient.ACTION_ERROR)
                    putInt(txId)
                    put(errBytes)
                }
                serverSocket.send(DatagramPacket(errResp.array(), errResp.capacity(), connectPacket.address, connectPacket.port))
            } finally {
                serverSocket.close()
            }
        }

        try {
            val client = UdpTrackerClient(socketTimeoutMs = 5000)
            val request = TrackerAnnounceRequest(
                trackerUrl = "udp://127.0.0.1:$serverPort",
                infoHash = ByteArray(20) { 0x33 },
                peerId = ByteArray(20) { 0x44 }
            )

            try {
                client.announce(request)
                fail("Expected TrackerFailure")
            } catch (e: AppError.TrackerFailure) {
                assertTrue(e.message?.contains("Torrent not registered") == true)
            }
        } finally {
            serverJob.join()
        }
    }

    @Test
    fun `test default tracker client routes properly`() = runBlocking {
        var httpCalled = false
        var udpCalled = false

        val mockHttp = object : TrackerClient {
            override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse {
                httpCalled = true
                return TrackerAnnounceResponse(60, null, 1, 1, emptyList())
            }
        }

        val mockUdp = object : TrackerClient {
            override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse {
                udpCalled = true
                return TrackerAnnounceResponse(120, null, 2, 2, emptyList())
            }
        }

        val router = DefaultTrackerClient(httpClient = mockHttp, udpClient = mockUdp)

        router.announce(
            TrackerAnnounceRequest(
                trackerUrl = "udp://tracker.openbittorrent.com:6969/announce",
                infoHash = ByteArray(20),
                peerId = ByteArray(20)
            )
        )
        assertTrue(udpCalled)
        assertFalse(httpCalled)

        router.announce(
            TrackerAnnounceRequest(
                trackerUrl = "http://tracker.openbittorrent.com:80/announce",
                infoHash = ByteArray(20),
                peerId = ByteArray(20)
            )
        )
        assertTrue(httpCalled)
    }
}
