package com.nutorrent.app.torrent.peer

import com.nutorrent.app.core.model.AppError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * BitTorrent v1 peer wire handshake (68 bytes).
 * Layout:
 * 1 byte: pstrlen (19)
 * 19 bytes: pstr ("BitTorrent protocol")
 * 8 bytes: reserved (all 0x00 for standard v1)
 * 20 bytes: info_hash
 * 20 bytes: peer_id
 */
data class PeerHandshake(
    val infoHash: ByteArray,
    val peerId: ByteArray,
    val reserved: ByteArray = createDefaultReserved()
) {
    val supportsExtensionProtocol: Boolean
        get() = (reserved[5].toInt() and 0x10) != 0

    val supportsDht: Boolean
        get() = (reserved[7].toInt() and 0x01) != 0

    val supportsFastExtension: Boolean
        get() = (reserved[7].toInt() and 0x04) != 0

    init {
        require(infoHash.size == 20) { "infoHash must be 20 bytes" }
        require(peerId.size == 20) { "peerId must be 20 bytes" }
        require(reserved.size == 8) { "reserved must be 8 bytes" }
    }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(HANDSHAKE_LENGTH)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.put(PROTOCOL_STRING_BYTES.size.toByte())
        buffer.put(PROTOCOL_STRING_BYTES)
        buffer.put(reserved)
        buffer.put(infoHash)
        buffer.put(peerId)
        return bytes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerHandshake) return false
        return Arrays.equals(infoHash, other.infoHash) &&
                Arrays.equals(peerId, other.peerId) &&
                Arrays.equals(reserved, other.reserved)
    }

    override fun hashCode(): Int {
        var result = Arrays.hashCode(infoHash)
        result = 31 * result + Arrays.hashCode(peerId)
        result = 31 * result + Arrays.hashCode(reserved)
        return result
    }

    companion object {
        const val HANDSHAKE_LENGTH = 68
        const val PROTOCOL_STRING = "BitTorrent protocol"
        val PROTOCOL_STRING_BYTES = PROTOCOL_STRING.toByteArray(Charsets.US_ASCII)

        fun createDefaultReserved(): ByteArray {
            val res = ByteArray(8)
            res[5] = (res[5].toInt() or 0x10).toByte() // BEP 10 Extension Protocol
            res[7] = (res[7].toInt() or 0x01 or 0x04).toByte() // BEP 5 DHT + BEP 6 Fast Extension
            return res
        }

        fun parse(bytes: ByteArray, offset: Int = 0): PeerHandshake {
            if (bytes.size - offset < HANDSHAKE_LENGTH) {
                throw AppError.InvalidTorrent("Handshake byte array too short: ${bytes.size - offset}")
            }
            val buffer = ByteBuffer.wrap(bytes, offset, HANDSHAKE_LENGTH)
            val pstrlen = buffer.get().toInt() and 0xFF
            if (pstrlen != PROTOCOL_STRING_BYTES.size) {
                throw AppError.InvalidTorrent("Invalid protocol string length: $pstrlen")
            }

            val pstrBytes = ByteArray(pstrlen)
            buffer.get(pstrBytes)
            val pstr = String(pstrBytes, Charsets.US_ASCII)
            if (pstr != PROTOCOL_STRING) {
                throw AppError.InvalidTorrent("Invalid protocol identifier: $pstr")
            }

            val reserved = ByteArray(8)
            buffer.get(reserved)

            val infoHash = ByteArray(20)
            buffer.get(infoHash)

            val peerId = ByteArray(20)
            buffer.get(peerId)

            return PeerHandshake(infoHash, peerId, reserved)
        }
    }
}

/**
 * Standard BitTorrent peer-wire messages according to BEP 3.
 */
sealed class PeerMessage {
    abstract val messageId: Byte?

    object KeepAlive : PeerMessage() {
        override val messageId: Byte? = null
    }

    object Choke : PeerMessage() {
        override val messageId: Byte = 0
    }

    object Unchoke : PeerMessage() {
        override val messageId: Byte = 1
    }

    object Interested : PeerMessage() {
        override val messageId: Byte = 2
    }

    object NotInterested : PeerMessage() {
        override val messageId: Byte = 3
    }

    data class Have(val pieceIndex: Int) : PeerMessage() {
        override val messageId: Byte = 4
        init {
            require(pieceIndex >= 0) { "Piece index cannot be negative" }
        }
    }

    class Bitfield(val bitfield: ByteArray) : PeerMessage() {
        override val messageId: Byte = 5

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bitfield) return false
            return Arrays.equals(bitfield, other.bitfield)
        }

        override fun hashCode(): Int = Arrays.hashCode(bitfield)
    }

    data class Request(val index: Int, val begin: Int, val length: Int) : PeerMessage() {
        override val messageId: Byte = 6
        init {
            require(index >= 0 && begin >= 0 && length > 0) { "Invalid request parameters" }
        }
    }

    class Piece(val index: Int, val begin: Int, val block: ByteArray) : PeerMessage() {
        override val messageId: Byte = 7
        init {
            require(index >= 0 && begin >= 0 && block.isNotEmpty()) { "Invalid piece parameters" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Piece) return false
            return index == other.index && begin == other.begin && Arrays.equals(block, other.block)
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + begin
            result = 31 * result + Arrays.hashCode(block)
            return result
        }
    }

    data class Cancel(val index: Int, val begin: Int, val length: Int) : PeerMessage() {
        override val messageId: Byte = 8
        init {
            require(index >= 0 && begin >= 0 && length > 0) { "Invalid cancel parameters" }
        }
    }

    class Extended(val extendedId: Int, val payload: ByteArray) : PeerMessage() {
        override val messageId: Byte = 20
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Extended) return false
            return extendedId == other.extendedId && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = extendedId * 31 + payload.contentHashCode()
    }

    data class SuggestPiece(val pieceIndex: Int) : PeerMessage() {
        override val messageId: Byte = 13
        init {
            require(pieceIndex >= 0) { "Piece index cannot be negative" }
        }
    }

    object HaveAll : PeerMessage() {
        override val messageId: Byte = 14
    }

    object HaveNone : PeerMessage() {
        override val messageId: Byte = 15
    }

    data class RejectRequest(val index: Int, val begin: Int, val length: Int) : PeerMessage() {
        override val messageId: Byte = 16
        init {
            require(index >= 0 && begin >= 0 && length > 0) { "Invalid reject parameters" }
        }
    }

    data class AllowedFast(val pieceIndex: Int) : PeerMessage() {
        override val messageId: Byte = 17
        init {
            require(pieceIndex >= 0) { "Piece index cannot be negative" }
        }
    }

    fun toWireBytes(): ByteArray {
        val id = messageId ?: return ByteArray(4)
        return when (this) {
            is KeepAlive -> {
                // Keep-alive is 4 zero bytes
                ByteArray(4)
            }
            is Choke, is Unchoke, is Interested, is NotInterested -> {
                val bytes = ByteArray(5)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(1) // length = 1
                buf.put(id)
                bytes
            }
            is HaveAll, is HaveNone -> {
                val bytes = ByteArray(5)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(1)
                buf.put(id)
                bytes
            }
            is Have -> {
                val bytes = ByteArray(9)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(5) // length = 5
                buf.put(id)
                buf.putInt(pieceIndex)
                bytes
            }
            is SuggestPiece -> {
                val bytes = ByteArray(9)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(5)
                buf.put(id)
                buf.putInt(pieceIndex)
                bytes
            }
            is AllowedFast -> {
                val bytes = ByteArray(9)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(5)
                buf.put(id)
                buf.putInt(pieceIndex)
                bytes
            }
            is Bitfield -> {
                val len = 1 + bitfield.size
                val bytes = ByteArray(4 + len)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(len)
                buf.put(id)
                buf.put(bitfield)
                bytes
            }
            is Request -> {
                val bytes = ByteArray(17)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(13) // length = 13
                buf.put(id)
                buf.putInt(index)
                buf.putInt(begin)
                buf.putInt(length)
                bytes
            }
            is RejectRequest -> {
                val bytes = ByteArray(17)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(13)
                buf.put(id)
                buf.putInt(index)
                buf.putInt(begin)
                buf.putInt(length)
                bytes
            }
            is Piece -> {
                val len = 9 + block.size
                val bytes = ByteArray(4 + len)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(len)
                buf.put(id)
                buf.putInt(index)
                buf.putInt(begin)
                buf.put(block)
                bytes
            }
            is Cancel -> {
                val bytes = ByteArray(17)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(13)
                buf.put(id)
                buf.putInt(index)
                buf.putInt(begin)
                buf.putInt(length)
                bytes
            }
            is Extended -> {
                val len = 1 + 1 + payload.size // id (1B) + extendedId (1B) + payload
                val bytes = ByteArray(4 + len)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.putInt(len)
                buf.put(id)
                buf.put(extendedId.toByte())
                buf.put(payload)
                bytes
            }
        }
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 131081 // 128 KiB block + 9 bytes header (index + begin + id)

        fun parse(payloadWithId: ByteArray): PeerMessage {
            if (payloadWithId.isEmpty()) {
                return KeepAlive
            }

            val buf = ByteBuffer.wrap(payloadWithId).order(ByteOrder.BIG_ENDIAN)
            val id = buf.get().toInt() and 0xFF

            return when (id) {
                0 -> {
                    if (payloadWithId.size != 1) throw AppError.InvalidTorrent("Invalid Choke message length ${payloadWithId.size}")
                    Choke
                }
                1 -> {
                    if (payloadWithId.size != 1) throw AppError.InvalidTorrent("Invalid Unchoke message length ${payloadWithId.size}")
                    Unchoke
                }
                2 -> {
                    if (payloadWithId.size != 1) throw AppError.InvalidTorrent("Invalid Interested message length ${payloadWithId.size}")
                    Interested
                }
                3 -> {
                    if (payloadWithId.size != 1) throw AppError.InvalidTorrent("Invalid NotInterested message length ${payloadWithId.size}")
                    NotInterested
                }
                4 -> {
                    if (payloadWithId.size != 5) throw AppError.InvalidTorrent("Invalid Have message length ${payloadWithId.size}")
                    Have(buf.getInt())
                }
                5 -> {
                    val bitfieldBytes = ByteArray(payloadWithId.size - 1)
                    buf.get(bitfieldBytes)
                    Bitfield(bitfieldBytes)
                }
                6 -> {
                    if (payloadWithId.size != 13) throw AppError.InvalidTorrent("Invalid Request message length ${payloadWithId.size}")
                    Request(buf.getInt(), buf.getInt(), buf.getInt())
                }
                7 -> {
                    if (payloadWithId.size < 9) throw AppError.InvalidTorrent("Invalid Piece message length ${payloadWithId.size}")
                    val index = buf.getInt()
                    val begin = buf.getInt()
                    val block = ByteArray(payloadWithId.size - 9)
                    buf.get(block)
                    Piece(index, begin, block)
                }
                8 -> {
                    if (payloadWithId.size != 13) throw AppError.InvalidTorrent("Invalid Cancel message length ${payloadWithId.size}")
                    Cancel(buf.getInt(), buf.getInt(), buf.getInt())
                }
                13 -> {
                    if (payloadWithId.size != 5) throw AppError.InvalidTorrent("Invalid SuggestPiece message length ${payloadWithId.size}")
                    SuggestPiece(buf.getInt())
                }
                14 -> {
                    if (payloadWithId.size != 1) throw AppError.InvalidTorrent("Invalid HaveAll message length ${payloadWithId.size}")
                    HaveAll
                }
                15 -> {
                    if (payloadWithId.size != 1) throw AppError.InvalidTorrent("Invalid HaveNone message length ${payloadWithId.size}")
                    HaveNone
                }
                16 -> {
                    if (payloadWithId.size != 13) throw AppError.InvalidTorrent("Invalid RejectRequest message length ${payloadWithId.size}")
                    RejectRequest(buf.getInt(), buf.getInt(), buf.getInt())
                }
                17 -> {
                    if (payloadWithId.size != 5) throw AppError.InvalidTorrent("Invalid AllowedFast message length ${payloadWithId.size}")
                    AllowedFast(buf.getInt())
                }
                20 -> {
                    if (payloadWithId.size < 2) throw AppError.InvalidTorrent("Invalid Extended message length ${payloadWithId.size}")
                    val extendedId = buf.get().toInt() and 0xFF
                    val extPayload = ByteArray(payloadWithId.size - 2)
                    buf.get(extPayload)
                    Extended(extendedId, extPayload)
                }
                else -> {
                    // Unknown message ID - safely ignore
                    KeepAlive
                }
            }
        }
    }
}
