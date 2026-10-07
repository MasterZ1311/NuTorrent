package com.nutorrent.app.torrent.peer

import com.nutorrent.app.core.model.AppError
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PeerWireProtocolTest {

    @Test
    fun `test handshake serialization and parsing`() {
        val infoHash = ByteArray(20) { it.toByte() }
        val peerId = ByteArray(20) { (it + 20).toByte() }
        val reserved = ByteArray(8) { 0 }

        val handshake = PeerHandshake(infoHash, peerId, reserved)
        val bytes = handshake.toByteArray()
        assertEquals(68, bytes.size)

        val parsed = PeerHandshake.parse(bytes)
        assertArrayEquals(infoHash, parsed.infoHash)
        assertArrayEquals(peerId, parsed.peerId)
        assertArrayEquals(reserved, parsed.reserved)
    }

    @Test
    fun `test handshake validation rejects invalid protocol string`() {
        val invalidBytes = ByteArray(68)
        invalidBytes[0] = 19
        System.arraycopy("InvalidProtocolName".toByteArray(Charsets.US_ASCII), 0, invalidBytes, 1, 19)

        assertThrows(AppError.InvalidTorrent::class.java) {
            PeerHandshake.parse(invalidBytes)
        }
    }

    @Test
    fun `test message serialization and parsing roundtrip`() {
        // KeepAlive
        val keepAliveBytes = PeerMessage.KeepAlive.toWireBytes()
        assertEquals(4, keepAliveBytes.size)

        // Choke / Unchoke
        val unchokeWire = PeerMessage.Unchoke.toWireBytes()
        val inUnchoke = ByteArrayInputStream(unchokeWire)
        val readUnchoke = PeerWireCodec.readMessage(inUnchoke)
        assertTrue(readUnchoke is PeerMessage.Unchoke)

        // Interested
        val interestedWire = PeerMessage.Interested.toWireBytes()
        val readInterested = PeerWireCodec.readMessage(ByteArrayInputStream(interestedWire))
        assertTrue(readInterested is PeerMessage.Interested)

        // Have
        val haveWire = PeerMessage.Have(1337).toWireBytes()
        val readHave = PeerWireCodec.readMessage(ByteArrayInputStream(haveWire)) as PeerMessage.Have
        assertEquals(1337, readHave.pieceIndex)

        // Bitfield
        val bitfieldBytes = byteArrayOf(0b10101010.toByte(), 0b11110000.toByte())
        val bitfieldWire = PeerMessage.Bitfield(bitfieldBytes).toWireBytes()
        val readBitfield = PeerWireCodec.readMessage(ByteArrayInputStream(bitfieldWire)) as PeerMessage.Bitfield
        assertArrayEquals(bitfieldBytes, readBitfield.bitfield)

        // Request
        val requestWire = PeerMessage.Request(42, 16384, 16384).toWireBytes()
        val readRequest = PeerWireCodec.readMessage(ByteArrayInputStream(requestWire)) as PeerMessage.Request
        assertEquals(42, readRequest.index)
        assertEquals(16384, readRequest.begin)
        assertEquals(16384, readRequest.length)

        // Piece
        val blockData = "Hello BitTorrent Piece".toByteArray()
        val pieceWire = PeerMessage.Piece(10, 0, blockData).toWireBytes()
        val readPiece = PeerWireCodec.readMessage(ByteArrayInputStream(pieceWire)) as PeerMessage.Piece
        assertEquals(10, readPiece.index)
        assertEquals(0, readPiece.begin)
        assertArrayEquals(blockData, readPiece.block)

        // Cancel
        val cancelWire = PeerMessage.Cancel(10, 0, 16384).toWireBytes()
        val readCancel = PeerWireCodec.readMessage(ByteArrayInputStream(cancelWire)) as PeerMessage.Cancel
        assertEquals(10, readCancel.index)
        assertEquals(0, readCancel.begin)
        assertEquals(16384, readCancel.length)
    }

    @Test
    fun `test oversized message length rejected`() {
        val out = ByteArrayOutputStream()
        // Write length that exceeds MAX_MESSAGE_LENGTH (e.g. 500,000 bytes)
        out.write(byteArrayOf(0x00, 0x07, 0xA1.toByte(), 0x20))
        val input = ByteArrayInputStream(out.toByteArray())

        assertThrows(AppError.InvalidTorrent::class.java) {
            PeerWireCodec.readMessage(input)
        }
    }
}
