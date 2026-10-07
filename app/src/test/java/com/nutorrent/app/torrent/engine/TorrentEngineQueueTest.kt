package com.nutorrent.app.torrent.engine

import com.nutorrent.app.core.model.ResourceGovernor
import com.nutorrent.app.core.model.ResourceGovernorConfig
import com.nutorrent.app.core.model.TorrentFile
import com.nutorrent.app.core.model.TorrentId
import com.nutorrent.app.core.model.TorrentMetadata
import com.nutorrent.app.core.model.TorrentState
import com.nutorrent.app.data.storage.TorrentStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TorrentEngineQueueTest {

    private fun createMockStorage(): TorrentStorage {
        return object : TorrentStorage {
            override suspend fun prepare() {}
            override suspend fun writePiece(globalOffset: Long, data: ByteArray) {}
            override suspend fun readBytes(globalOffset: Long, length: Int): ByteArray = ByteArray(length)
            override suspend fun verifyStoredPieces(onPieceResult: (Int, Boolean) -> Unit) {}
            override fun close() {}
        }
    }

    private fun createMetadata(idByte: Byte, name: String): TorrentMetadata {
        val hash = ByteArray(20) { idByte }
        return TorrentMetadata(
            torrentId = TorrentId(hash),
            name = name,
            pieceLength = 16384,
            piecesCount = 1,
            pieceHashes = listOf(ByteArray(20)),
            totalLength = 16384,
            files = listOf(TorrentFile(0, name, 16384, 0, 16384)),
            announceList = emptyList()
        )
    }

    @Test
    fun `test resource governor limit validation and dynamic peer budget`() {
        val config = ResourceGovernorConfig(
            maxActiveDownloads = 2,
            maxActiveSeeds = 3,
            maxTotalPeerConnections = 60
        )
        val governor = ResourceGovernor(config)

        assertTrue(governor.canStartDownload(0))
        assertTrue(governor.canStartDownload(1))
        assertFalse(governor.canStartDownload(2))

        assertTrue(governor.canStartSeed(2))
        assertFalse(governor.canStartSeed(3))

        // 60 total peer budget / 4 active sessions = 15 per session
        assertEquals(15, governor.allocatePeerLimit(30, 4))
        // 60 / 20 = 3, coerced to minimum 5
        assertEquals(5, governor.allocatePeerLimit(30, 20))
    }

    @Test
    fun `test queue auto-promotes queued torrent when active slot becomes available`() = runBlocking {
        val governor = ResourceGovernor(ResourceGovernorConfig(maxActiveDownloads = 1))
        val engine = TorrentEngine(governor = governor)

        val meta1 = createMetadata(1, "torrent1")
        val meta2 = createMetadata(2, "torrent2")

        val s1 = engine.createSession(meta1, createMockStorage())
        val s2 = engine.createSession(meta2, createMockStorage())

        engine.startOrQueue(meta1.torrentId)
        engine.startOrQueue(meta2.torrentId)

        // Torrent 1 was allowed to start
        assertNotEquals(TorrentState.QUEUED, s1.state.value)
        // Torrent 2 had to wait in QUEUED because maxActiveDownloads = 1
        assertEquals(TorrentState.QUEUED, s2.state.value)

        // Now pause torrent 1 to free up the download slot
        engine.pauseSession(meta1.torrentId)

        // Engine must automatically start torrent 2
        assertNotEquals(TorrentState.QUEUED, s2.state.value)

        engine.shutdown()
    }

    @Test
    fun `test queue reordering`() {
        val engine = TorrentEngine()

        val id1 = TorrentId(ByteArray(20) { 1 })
        val id2 = TorrentId(ByteArray(20) { 2 })
        val id3 = TorrentId(ByteArray(20) { 3 })

        val s1 = engine.createSession(createMetadata(1, "t1"), createMockStorage())
        val s2 = engine.createSession(createMetadata(2, "t2"), createMockStorage())
        val s3 = engine.createSession(createMetadata(3, "t3"), createMockStorage())

        assertEquals(1, engine.getQueuePosition(id1))
        assertEquals(2, engine.getQueuePosition(id2))
        assertEquals(3, engine.getQueuePosition(id3))

        // Move item 3 up
        engine.moveUpInQueue(id3)
        assertEquals(listOf(id1, id3, id2), engine.queueOrderFlow.value)
        assertEquals(2, engine.getQueuePosition(id3))

        // Move item 1 down
        engine.moveDownInQueue(id1)
        assertEquals(listOf(id3, id1, id2), engine.queueOrderFlow.value)
        assertEquals(1, engine.getQueuePosition(id3))
        assertEquals(2, engine.getQueuePosition(id1))

        engine.shutdown()
    }
}
