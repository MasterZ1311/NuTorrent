package com.nutorrent.app.integration

import com.nutorrent.app.torrent.metainfo.MetainfoParser
import com.nutorrent.app.torrent.tracker.DefaultTrackerClient
import com.nutorrent.app.torrent.tracker.TrackerAnnounceRequest
import com.nutorrent.app.torrent.tracker.TrackerEvent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class RealTorrentDiagnosticTest {

    @Test
    fun inspectRealTorrent() = runBlocking {
        val torrentFile = if (File("test.torrent").exists()) File("test.torrent") else File("../test.torrent")
        if (!torrentFile.exists()) {
            println("test.torrent does not exist at ${torrentFile.absolutePath}")
            return@runBlocking
        }
        val bytes = torrentFile.readBytes()
        val metadata = MetainfoParser.parse(bytes)
        println("=== REAL TORRENT METADATA ===")
        println("Name: ${metadata.name}")
        println("InfoHash Hex: ${metadata.torrentId.hex}")
        println("Total Length: ${metadata.totalLength}")
        println("Piece Length: ${metadata.pieceLength}")
        println("Pieces Count: ${metadata.piecesCount}")
        println("Piece Hashes Count: ${metadata.pieceHashes.size}")
        println("Announce List: ${metadata.announceList}")
        println("Files: ${metadata.files.size}")
        for (f in metadata.files) {
            println("  File: ${f.relativePath} (${f.length} bytes, offset=${f.startByte})")
        }

        val trackerClient = DefaultTrackerClient()
        val ourPeerId = ByteArray(20) { 0x42 }
        for (tracker in metadata.announceList) {
            println("\nTesting Announce to: $tracker")
            try {
                val req = TrackerAnnounceRequest(
                    trackerUrl = tracker,
                    infoHash = metadata.torrentId.bytes,
                    peerId = ourPeerId,
                    downloaded = 0L,
                    uploaded = 0L,
                    left = metadata.totalLength,
                    compact = true,
                    event = TrackerEvent.STARTED
                )
                val resp = trackerClient.announce(req)
                println("Tracker SUCCESS: seeders=${resp.seeders}, leechers=${resp.leechers}, peersCount=${resp.peers.size}")
                for (p in resp.peers.take(5)) {
                    println("  Peer: ${p.ip}:${p.port}")
                }
            } catch (e: Exception) {
                println("Tracker FAILED: ${e::class.simpleName} - ${e.message}")
            }
        }
    }

    @Test
    fun testLiveSwarmProgress() = runBlocking {
        val torrentFile = if (File("test.torrent").exists()) File("test.torrent") else File("../test.torrent")
        if (!torrentFile.exists()) return@runBlocking
        val metadata = MetainfoParser.parse(torrentFile.readBytes())

        val tempDir = File(System.getProperty("java.io.tmpdir"), "nutorrent_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        tempDir.deleteOnExit()

        val storage = com.nutorrent.app.data.storage.FileTorrentStorage(tempDir, metadata)
        val trackerClient = DefaultTrackerClient()

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val session = com.nutorrent.app.torrent.download.TorrentSession(
            metadata = metadata,
            storage = storage,
            trackerClient = trackerClient,
            scope = scope,
            maxPeerConnections = 20
        )

        println("\n=== STARTING LIVE SESSION ===")
        session.start()

        for (i in 1..40) {
            kotlinx.coroutines.delay(1000)
            val st = session.state.value
            val prog = session.progress.value
            val peerInfos = session.peers.value
            val unchoked = peerInfos.count { !it.isChokingUs }
            val connected = peerInfos.count { it.isConnected }
            println("[T+${i}s] State: $st | Peers: $connected (${peerInfos.size} total, $unchoked unchoking us) | CompletedPieces: ${prog.completedPieces} | Verified: ${prog.verifiedBytes}/${prog.totalBytes} bytes | DownloadedRaw: ${prog.downloadedBytes} | Speed: ${prog.downloadSpeedBps} B/s")
            for (p in peerInfos.filter { it.isConnected }.take(3)) {
                println("   Peer ${p.endpoint}: chokingUs=${p.isChokingUs}, weInterested=${p.isWeInterested}, pieces=${p.piecesCount}, pendingReqs=${p.pendingRequestsCount}, downBytes=${p.downloadedBytes}")
            }
            if (prog.completedPieces > 0) {
                println(">>> SUCCESS: COMPLETED PIECES: ${prog.completedPieces}, VERIFIED BYTES: ${prog.verifiedBytes} <<<")
                break
            }
        }

        session.stop()
        scope.cancel()
        tempDir.deleteRecursively()
    }
}
