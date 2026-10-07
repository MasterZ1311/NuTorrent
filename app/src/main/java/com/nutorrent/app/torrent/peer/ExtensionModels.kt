package com.nutorrent.app.torrent.peer

import com.nutorrent.app.torrent.bencode.BencodeDecoder
import com.nutorrent.app.torrent.bencode.BencodeElement
import com.nutorrent.app.torrent.bencode.BencodeEncoder

/**
 * BEP 10 Extension Protocol Handshake.
 * Sent as extended message ID 0 with a Bencoded dictionary payload.
 */
data class ExtensionHandshake(
    val extensions: Map<String, Int> = mapOf(
        EXTENSION_UT_METADATA to ID_UT_METADATA,
        EXTENSION_UT_PEX to ID_UT_PEX
    ),
    val metadataSize: Int? = null,
    val clientVersion: String = "NuTorrent 1.0.0",
    val port: Int? = null
) {
    companion object {
        const val EXTENSION_UT_METADATA = "ut_metadata"
        const val EXTENSION_UT_PEX = "ut_pex"

        const val ID_HANDSHAKE = 0
        const val ID_UT_METADATA = 1
        const val ID_UT_PEX = 2

        fun parse(payload: ByteArray): ExtensionHandshake {
            val root = BencodeDecoder.decode(payload) as? BencodeElement.BDictionary
                ?: return ExtensionHandshake()

            val mDict = root.getDict("m")
            val extensions = mutableMapOf<String, Int>()
            if (mDict != null) {
                for ((key, value) in mDict.value) {
                    if (value is BencodeElement.BInteger) {
                        extensions[key] = value.value.toInt()
                    }
                }
            }

            val metadataSize = root.getInteger("metadata_size")?.toInt()
            val version = root.getString("v") ?: ""
            val port = root.getInteger("p")?.toInt()

            return ExtensionHandshake(
                extensions = extensions,
                metadataSize = metadataSize,
                clientVersion = version,
                port = port
            )
        }
    }

    fun toBencodeBytes(): ByteArray {
        val map = linkedMapOf<String, BencodeElement>()
        val m = linkedMapOf<String, BencodeElement>()
        for ((k, v) in extensions) {
            m[k] = BencodeElement.BInteger(v.toLong())
        }
        map["m"] = BencodeElement.BDictionary(m)
        map["v"] = BencodeElement.BString(clientVersion.toByteArray(Charsets.UTF_8))
        if (metadataSize != null && metadataSize > 0) {
            map["metadata_size"] = BencodeElement.BInteger(metadataSize.toLong())
        }
        if (port != null && port in 1..65535) {
            map["p"] = BencodeElement.BInteger(port.toLong())
        }
        return BencodeEncoder.encode(BencodeElement.BDictionary(map))
    }
}

/**
 * BEP 9 ut_metadata message types and payload model.
 */
sealed class MetadataMessage {
    data class Request(val piece: Int) : MetadataMessage()
    data class Data(val piece: Int, val totalSize: Int, val data: ByteArray) : MetadataMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return piece == other.piece && totalSize == other.totalSize && data.contentEquals(other.data)
        }
        override fun hashCode(): Int = piece * 31 + data.contentHashCode()
    }
    data class Reject(val piece: Int) : MetadataMessage()

    companion object {
        const val MSG_TYPE_REQUEST = 0
        const val MSG_TYPE_DATA = 1
        const val MSG_TYPE_REJECT = 2
        const val BLOCK_SIZE = 16384 // 16 KiB metadata piece

        fun parse(payload: ByteArray): MetadataMessage? {
            if (payload.isEmpty()) return null
            // The message starts with a bencoded dictionary, followed optionally by raw data
            // We use BencodeDecoder with info range / element decoding to parse the header dict
            val decoder = BencodeDecoder(payload)
            val headerDict = try {
                decoder.decodeElement(0) as? BencodeElement.BDictionary
            } catch (e: Exception) {
                return null
            } ?: return null

            val msgType = headerDict.getInteger("msg_type")?.toInt() ?: return null
            val piece = headerDict.getInteger("piece")?.toInt() ?: return null

            return when (msgType) {
                MSG_TYPE_REQUEST -> Request(piece)
                MSG_TYPE_REJECT -> Reject(piece)
                MSG_TYPE_DATA -> {
                    val totalSize = headerDict.getInteger("total_size")?.toInt() ?: return null
                    // The rest of payload after the bencoded header is the piece data
                    val dataOffset = decoder.getPosition()
                    if (dataOffset > payload.size) return null
                    val dataBytes = payload.copyOfRange(dataOffset, payload.size)
                    Data(piece, totalSize, dataBytes)
                }
                else -> null
            }
        }

        fun createRequestBytes(piece: Int): ByteArray {
            val map = linkedMapOf<String, BencodeElement>(
                "msg_type" to BencodeElement.BInteger(MSG_TYPE_REQUEST.toLong()),
                "piece" to BencodeElement.BInteger(piece.toLong())
            )
            return BencodeEncoder.encode(BencodeElement.BDictionary(map))
        }

        fun createDataBytes(piece: Int, totalSize: Int, pieceData: ByteArray): ByteArray {
            val map = linkedMapOf<String, BencodeElement>(
                "msg_type" to BencodeElement.BInteger(MSG_TYPE_DATA.toLong()),
                "piece" to BencodeElement.BInteger(piece.toLong()),
                "total_size" to BencodeElement.BInteger(totalSize.toLong())
            )
            val header = BencodeEncoder.encode(BencodeElement.BDictionary(map))
            return header + pieceData
        }

        fun createRejectBytes(piece: Int): ByteArray {
            val map = linkedMapOf<String, BencodeElement>(
                "msg_type" to BencodeElement.BInteger(MSG_TYPE_REJECT.toLong()),
                "piece" to BencodeElement.BInteger(piece.toLong())
            )
            return BencodeEncoder.encode(BencodeElement.BDictionary(map))
        }
    }
}
