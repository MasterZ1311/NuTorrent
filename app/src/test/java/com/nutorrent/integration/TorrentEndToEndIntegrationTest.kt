package com.nutorrent.integration

import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.core.model.TorrentState
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
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

class TorrentEndToEndIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `test end to end torrent download with simulated local peer`() = runBlocking {
        // 1. Prepare synthetic payload (2 pieces: 16384 bytes each = 32768 bytes total)
        val pieceLen = 16384
        val totalLen = 32768
        val payload = ByteArray(totalLen) { ((it * 31) % 256).toByte() }

        val piece0Data = payload.copyOfRange(0, 16384)
        val piece1Data = payload.copyOfRange(16384, 32768)

        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash0 = sha1.digest(piece0Data)
        sha1.reset()
        val hash1 = sha1.digest(piece1Data)

        // 2. Generate valid .torrent metainfo
        val infoMap = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(totalLen.toLong()),
            "name" to BencodeElement.BString("integration_test.dat".toByteArray()),
            "piece length" to BencodeElement.BInteger(pieceLen.toLong()),
            "pieces" to BencodeElement.BString(hash0 + hash1)
        )
        val rootMap = linkedMapOf<String, BencodeElement>(
            "announce" to BencodeElement.BString("http://127.0.0.1:9999/announce".toByteArray()),
            "info" to BencodeElement.BDictionary(infoMap)
        )
        val torrentBytes = BencodeEncoder.encode(BencodeElement.BDictionary(rootMap))
        val metadata = MetainfoParser.parse(torrentBytes)

        // 3. Start local simulated peer server on an ephemeral port
        val serverSocket = ServerSocket(0)
        val localPort = serverSocket.localPort

        val peerServerThread = thread(isDaemon = true) {
            try {
                val clientSocket = serverSocket.accept()
                clientSocket.soTimeout = 10000
                val inp = BufferedInputStream(clientSocket.getInputStream())
                val out = BufferedOutputStream(clientSocket.getOutputStream())

                // 3a. Read handshake from client
                val clientHandshake = PeerWireCodec.readHandshake(inp)
                assertArrayEquals(metadata.torrentId.bytes, clientHandshake.infoHash)

                // 3b. Send peer handshake back
                val simulatedPeerId = ByteArray(20) { 7 }
                PeerWireCodec.writeHandshake(out, PeerHandshake(metadata.torrentId.bytes, simulatedPeerId))

                // 3c. Send Bitfield indicating peer has both pieces
                val peerBitfield = PeerBitfield(2)
                peerBitfield.setPiece(0, true)
                peerBitfield.setPiece(1, true)
                PeerWireCodec.writeMessage(out, PeerMessage.Bitfield(peerBitfield.bytes))

                // 3d. Send Unchoke
                PeerWireCodec.writeMessage(out, PeerMessage.Unchoke)

                // 3e. Serve incoming requests
                while (!clientSocket.isClosed) {
                    val msg = try {
                        PeerWireCodec.readMessage(inp)
                    } catch (e: Exception) {
                        break
                    }

                    if (msg is PeerMessage.Request) {
                        val offsetInTorrent = (msg.index * pieceLen) + msg.begin
                        val blockBytes = payload.copyOfRange(offsetInTorrent, offsetInTorrent + msg.length)
                        PeerWireCodec.writeMessage(out, PeerMessage.Piece(msg.index, msg.begin, blockBytes))
                    }
                }
            } catch (e: Exception) {
                // Ignore socket close
            } finally {
                serverSocket.close()
            }
        }

        // 4. Create mock tracker client that returns our local simulated peer
        val mockTracker = object : TrackerClient {
            override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse {
                return TrackerAnnounceResponse(
                    intervalSeconds = 60,
                    minIntervalSeconds = null,
                    seeders = 1,
                    leechers = 0,
                    peers = listOf(PeerEndpoint("127.0.0.1", localPort))
                )
            }
        }

        // 5. Initialize Storage and Session
        val downloadDir = tempFolder.newFolder("integration_download")
        val storage = FileTorrentStorage(downloadDir, metadata)

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TorrentSession(
            metadata = metadata,
            storage = storage,
            trackerClient = mockTracker,
            scope = testScope,
            maxPeerConnections = 5
        )

        // 6. Start download
        session.start()

        // 7. Wait for download to complete and enter seeding (with 15s timeout)
        withTimeout(15000) {
            while (session.state.value != TorrentState.SEEDING && session.state.value != TorrentState.COMPLETED) {
                delay(100)
            }
        }

        // 8. Verify final states
        assertTrue(session.state.value == TorrentState.SEEDING || session.state.value == TorrentState.COMPLETED)
        assertEquals(totalLen.toLong(), session.progress.value.verifiedBytes)
        assertEquals(2, session.progress.value.completedPieces)

        // 9. Verify written file on disk matches expected payload
        val downloadedFile = java.io.File(downloadDir, metadata.files[0].relativePath)
        assertTrue(downloadedFile.exists())
        assertEquals(totalLen.toLong(), downloadedFile.length())
        val writtenBytes = downloadedFile.readBytes()
        assertArrayEquals(payload, writtenBytes)

        session.stop()
        testScope.cancel()
    }
}
