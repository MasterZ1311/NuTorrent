package com.nutorrent.torrent.peer

import com.nutorrent.core.model.BlockRequest
import com.nutorrent.core.model.FilePriority
import com.nutorrent.core.model.PeerBitfield
import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.torrent.piece.PieceManager
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class FastExtensionTest {

    @Test
    fun `test reserved bytes include fast extension bit`() {
        val reserved = PeerHandshake.createDefaultReserved()
        assertEquals(8, reserved.size)
        // Bit 0x04 in byte 7 indicates BEP 6 Fast Extension support
        assertTrue((reserved[7].toInt() and 0x04) != 0)
        // Bit 0x10 in byte 5 indicates BEP 10 Extension Protocol support
        assertTrue((reserved[5].toInt() and 0x10) != 0)
    }

    @Test
    fun `test BEP 6 messages serialization and parsing roundtrip`() {
        // HaveAll (ID 14)
        val haveAllWire = PeerMessage.HaveAll.toWireBytes()
        assertEquals(5, haveAllWire.size) // 4 len + 1 id
        val readHaveAll = PeerWireCodec.readMessage(ByteArrayInputStream(haveAllWire))
        assertTrue(readHaveAll is PeerMessage.HaveAll)

        // HaveNone (ID 15)
        val haveNoneWire = PeerMessage.HaveNone.toWireBytes()
        assertEquals(5, haveNoneWire.size) // 4 len + 1 id
        val readHaveNone = PeerWireCodec.readMessage(ByteArrayInputStream(haveNoneWire))
        assertTrue(readHaveNone is PeerMessage.HaveNone)

        // SuggestPiece (ID 13)
        val suggestWire = PeerMessage.SuggestPiece(42).toWireBytes()
        assertEquals(9, suggestWire.size) // 4 len + 1 id + 4 index
        val readSuggest = PeerWireCodec.readMessage(ByteArrayInputStream(suggestWire)) as PeerMessage.SuggestPiece
        assertEquals(42, readSuggest.pieceIndex)

        // RejectRequest (ID 16)
        val rejectWire = PeerMessage.RejectRequest(7, 16384, 16384).toWireBytes()
        assertEquals(17, rejectWire.size) // 4 len + 1 id + 12 payload
        val readReject = PeerWireCodec.readMessage(ByteArrayInputStream(rejectWire)) as PeerMessage.RejectRequest
        assertEquals(7, readReject.index)
        assertEquals(16384, readReject.begin)
        assertEquals(16384, readReject.length)

        // AllowedFast (ID 17)
        val allowedWire = PeerMessage.AllowedFast(99).toWireBytes()
        assertEquals(9, allowedWire.size) // 4 len + 1 id + 4 index
        val readAllowed = PeerWireCodec.readMessage(ByteArrayInputStream(allowedWire)) as PeerMessage.AllowedFast
        assertEquals(99, readAllowed.pieceIndex)
    }

    @Test
    fun `test PeerBitfield setAll true sets all pieces with trailing bit mask sanitization`() {
        val totalPieces = 10
        val bitfield = PeerBitfield(totalPieces)
        assertEquals(0, bitfield.countPieces())

        bitfield.setAll(true)
        assertEquals(10, bitfield.countPieces())
        for (i in 0 until 10) {
            assertTrue(bitfield.hasPiece(i))
        }

        // Check trailing bits: byte 0 has 8 bits, byte 1 has 2 bits set, 6 bits cleared
        val raw = bitfield.bytes
        assertEquals(2, raw.size)
        assertEquals(0xFF.toByte(), raw[0])
        assertEquals(0xC0.toByte(), raw[1]) // 1100 0000

        // Reset with setAll(false)
        bitfield.setAll(false)
        assertEquals(0, bitfield.countPieces())
        assertEquals(0x00.toByte(), raw[0])
        assertEquals(0x00.toByte(), raw[1])
    }

    @Test
    fun `test PieceManager hasPiecesWeNeed and requestNextBlockForAllowedFast`() {
        val totalLength = 65536L // 4 pieces of 16384 bytes
        val metadata = TorrentMetadata(
            torrentId = TorrentId(ByteArray(20)),
            name = "fast_ext_test",
            pieceLength = 16384,
            piecesCount = 4,
            pieceHashes = List(4) { ByteArray(20) { 1 } },
            totalLength = totalLength,
            files = listOf(TorrentFile(0, "test.dat", totalLength, 0, totalLength)),
            announceList = emptyList()
        )
        val pieceManager = PieceManager(metadata)

        val peerBitfield = PeerBitfield(4)
        assertFalse(pieceManager.hasPiecesWeNeed(peerBitfield))

        // Peer has piece 2
        peerBitfield.setPiece(2, true)
        assertTrue(pieceManager.hasPiecesWeNeed(peerBitfield))

        // Request next block for AllowedFast when peer is choked
        val allowed = setOf(2)
        val block = pieceManager.requestNextBlockForAllowedFast(peerBitfield, allowed)
        assertNotNull(block)
        assertEquals(2, block!!.pieceIndex)
        assertEquals(0, block.beginOffset)
        assertEquals(16384, block.length)

        // Mark piece 2 complete
        pieceManager.markPieceCompleteFromStorage(2)
        // Now peer bitfield (which only has piece 2) has no pieces we need
        assertFalse(pieceManager.hasPiecesWeNeed(peerBitfield))
    }
}
