package com.nutorrent.integration

import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.util.Lg
import com.nutorrent.data.storage.FileTorrentStorage
import com.nutorrent.torrent.bencode.BencodeElement
import com.nutorrent.torrent.bencode.BencodeEncoder
import com.nutorrent.torrent.download.TorrentSession
import com.nutorrent.torrent.metainfo.MetainfoParser
import com.nutorrent.torrent.peer.PeerHandshake
import com.nutorrent.torrent.peer.PeerMessage
import com.nutorrent.torrent.peer.PeerWireCodec
import com.nutorrent.torrent.tracker.TrackerAnnounceRequest
import com.nutorrent.torrent.tracker.TrackerAnnounceResponse
import com.nutorrent.torrent.tracker.TrackerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

class DiagnosticDownloadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSmallestWorkingPathDiagnostic() = runBlocking {
        // 1 torrent, 1 peer, 1 piece, 1 block -> verify -> write
        val pieceLen = 16384
        val totalLen = 16384
        val payload = ByteArray(totalLen) { ((it * 37) % 256).toByte() }

        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash0 = sha1.digest(payload)

        val infoMap = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(totalLen.toLong()),
            "name" to BencodeElement.BString("single_piece.dat".toByteArray()),
            "piece length" to BencodeElement.BInteger(pieceLen.toLong()),
            "pieces" to BencodeElement.BString(hash0)
        )
        val rootMap = linkedMapOf<String, BencodeElement>(
            "announce" to BencodeElement.BString("http://127.0.0.1:9999/announce".toByteArray()),
            "info" to BencodeElement.BDictionary(infoMap)
        )
        val torrentBytes = BencodeEncoder.encode(BencodeElement.BDictionary(rootMap))
        val metadata = MetainfoParser.parse(torrentBytes)

        val serverSocket = ServerSocket(0)
        val localPort = serverSocket.localPort

        val peerServerThread = thread(isDaemon = true) {
            try {
                println("[SERVER] Waiting for client...")
                val clientSocket = serverSocket.accept()
                clientSocket.soTimeout = 10000
                println("[SERVER] Client connected from ${clientSocket.remoteSocketAddress}")

                val inp = BufferedInputStream(clientSocket.getInputStream())
                val out = BufferedOutputStream(clientSocket.getOutputStream())

                // 1. Read handshake
                val clientHandshake = PeerWireCodec.readHandshake(inp)
                println("[SERVER] Handshake received, infoHash matches: ${clientHandshake.infoHash.contentEquals(metadata.torrentId.bytes)}")

                // 2. Send handshake back
                val peerId = ByteArray(20) { 8 }
                PeerWireCodec.writeHandshake(out, PeerHandshake(metadata.torrentId.bytes, peerId))
                println("[SERVER] Handshake sent")

                // 3. Send bitfield indicating peer has piece 0
                val bitfield = PeerBitfield(1)
                bitfield.setPiece(0, true)
                PeerWireCodec.writeMessage(out, PeerMessage.Bitfield(bitfield.bytes))
                println("[SERVER] Bitfield sent: piece 0 available")

                // Now observe incoming messages
                var receivedInterested = false
                while (!clientSocket.isClosed) {
                    val msg = try {
                        PeerWireCodec.readMessage(inp)
                    } catch (e: Exception) {
                        println("[SERVER] Exception reading message: ${e.message}")
                        break
                    }
                    println("[SERVER] Received message: ${msg::class.simpleName}")

                    if (msg is PeerMessage.Interested) {
                        receivedInterested = true
                        println("[SERVER] Received Interested! Now sending Unchoke...")
                        PeerWireCodec.writeMessage(out, PeerMessage.Unchoke)
                    } else if (msg is PeerMessage.Request) {
                        println("[SERVER] Received Request for piece=${msg.index}, begin=${msg.begin}, len=${msg.length}")
                        val blockBytes = payload.copyOfRange(msg.begin, msg.begin + msg.length)
                        PeerWireCodec.writeMessage(out, PeerMessage.Piece(msg.index, msg.begin, blockBytes))
                        println("[SERVER] Sent Piece block")
                    }
                }
            } catch (e: Exception) {
                println("[SERVER] Server thread exception: $e")
            } finally {
                serverSocket.close()
            }
        }

        val mockTracker = object : TrackerClient {
            override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse {
                println("[DIAGNOSTIC TRACKER] Announce received for ${request.trackerUrl}")
                return TrackerAnnounceResponse(
                    intervalSeconds = 60,
                    minIntervalSeconds = null,
                    seeders = 1,
                    leechers = 0,
                    peers = listOf(PeerEndpoint("127.0.0.1", localPort))
                )
            }
        }

        val downloadDir = tempFolder.newFolder("diagnostic_download")
        val storage = FileTorrentStorage(downloadDir, metadata)

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TorrentSession(
            metadata = metadata,
            storage = storage,
            trackerClient = mockTracker,
            scope = testScope,
            maxPeerConnections = 5
        )

        println("[DIAGNOSTIC] Starting session...")
        session.start()

        withTimeout(10000) {
            while (session.state.value != TorrentState.SEEDING && session.state.value != TorrentState.COMPLETED) {
                val p = session.progress.value
                val s = session.state.value
                println("[DIAGNOSTIC] state=$s, verified=${p.verifiedBytes}/${p.totalBytes}, dlSpeed=${p.downloadSpeedBps}, peers=${p.connectedPeers}")
                delay(500)
            }
        }

        assertEquals(TorrentState.SEEDING, session.state.value)
        assertEquals(totalLen.toLong(), session.progress.value.verifiedBytes)

        session.stop()
        testScope.cancel()
    }
}
