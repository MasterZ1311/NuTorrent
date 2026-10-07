package com.nutorrent.app.torrent.dht

import com.nutorrent.app.core.model.PeerEndpoint
import com.nutorrent.app.torrent.bencode.BencodeDecoder
import com.nutorrent.app.torrent.bencode.BencodeElement
import com.nutorrent.app.torrent.bencode.BencodeEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DhtContact(
    val id: DhtNodeId,
    val endpoint: PeerEndpoint,
    var lastSeen: Long = System.currentTimeMillis(),
    var failedQueries: Int = 0
) {
    fun toCompactBytes(): ByteArray {
        val buf = ByteBuffer.allocate(26).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(id.bytes)
            val parts = endpoint.ip.split(".")
            for (p in parts) {
                put((p.toInt() and 0xFF).toByte())
            }
            putShort(endpoint.port.toShort())
        }
        return buf.array()
    }

    companion object {
        fun fromCompactBytes(bytes: ByteArray, offset: Int = 0): DhtContact? {
            if (offset + 26 > bytes.size) return null
            val idBytes = ByteArray(20)
            System.arraycopy(bytes, offset, idBytes, 0, 20)
            val id = DhtNodeId(idBytes)

            val ip0 = bytes[offset + 20].toInt() and 0xFF
            val ip1 = bytes[offset + 21].toInt() and 0xFF
            val ip2 = bytes[offset + 22].toInt() and 0xFF
            val ip3 = bytes[offset + 23].toInt() and 0xFF
            val port = ((bytes[offset + 24].toInt() and 0xFF) shl 8) or (bytes[offset + 25].toInt() and 0xFF)

            return DhtContact(id, PeerEndpoint("$ip0.$ip1.$ip2.$ip3", port))
        }

        fun parseCompactNodes(bytes: ByteArray): List<DhtContact> {
            val contacts = mutableListOf<DhtContact>()
            var offset = 0
            while (offset + 26 <= bytes.size) {
                fromCompactBytes(bytes, offset)?.let { contacts.add(it) }
                offset += 26
            }
            return contacts
        }

        fun toCompactNodes(contacts: List<DhtContact>): ByteArray {
            val buf = ByteBuffer.allocate(contacts.size * 26)
            for (c in contacts) {
                buf.put(c.toCompactBytes())
            }
            return buf.array()
        }
    }
}

sealed class KrpcMessage(val transactionId: ByteArray) {

    class Query(
        transactionId: ByteArray,
        val queryType: String,
        val arguments: Map<String, BencodeElement>
    ) : KrpcMessage(transactionId)

    class Response(
        transactionId: ByteArray,
        val returnValues: Map<String, BencodeElement>
    ) : KrpcMessage(transactionId)

    class Error(
        transactionId: ByteArray,
        val code: Int,
        val message: String
    ) : KrpcMessage(transactionId)

    fun toBencodeBytes(): ByteArray {
        val root = linkedMapOf<String, BencodeElement>()
        root["t"] = BencodeElement.BString(transactionId)

        when (this) {
            is Query -> {
                root["y"] = BencodeElement.BString("q".toByteArray(Charsets.US_ASCII))
                root["q"] = BencodeElement.BString(queryType.toByteArray(Charsets.US_ASCII))
                root["a"] = BencodeElement.BDictionary(LinkedHashMap(arguments))
            }
            is Response -> {
                root["y"] = BencodeElement.BString("r".toByteArray(Charsets.US_ASCII))
                root["r"] = BencodeElement.BDictionary(LinkedHashMap(returnValues))
            }
            is Error -> {
                root["y"] = BencodeElement.BString("e".toByteArray(Charsets.US_ASCII))
                root["e"] = BencodeElement.BList(
                    listOf(
                        BencodeElement.BInteger(code.toLong()),
                        BencodeElement.BString(message.toByteArray(Charsets.UTF_8))
                    )
                )
            }
        }
        return BencodeEncoder.encode(BencodeElement.BDictionary(root))
    }

    companion object {
        const val QUERY_PING = "ping"
        const val QUERY_FIND_NODE = "find_node"
        const val QUERY_GET_PEERS = "get_peers"
        const val QUERY_ANNOUNCE_PEER = "announce_peer"

        const val ERROR_GENERIC = 201
        const val ERROR_SERVER = 202
        const val ERROR_PROTOCOL = 203
        const val ERROR_METHOD_UNKNOWN = 204

        fun parse(payload: ByteArray): KrpcMessage? {
            val root = try {
                BencodeDecoder.decode(payload) as? BencodeElement.BDictionary
            } catch (e: Exception) {
                return null
            } ?: return null

            val tBytes = root.getByteArray("t") ?: return null
            val y = root.getString("y") ?: return null

            return when (y) {
                "q" -> {
                    val q = root.getString("q") ?: return null
                    val a = root.getDict("a")?.value ?: emptyMap()
                    Query(tBytes, q, a)
                }
                "r" -> {
                    val r = root.getDict("r")?.value ?: emptyMap()
                    Response(tBytes, r)
                }
                "e" -> {
                    val e = root.getList("e") ?: return null
                    val code = (e.getOrNull(0) as? BencodeElement.BInteger)?.value?.toInt() ?: ERROR_GENERIC
                    val msg = (e.getOrNull(1) as? BencodeElement.BString)?.asUtf8String ?: "Unknown error"
                    Error(tBytes, code, msg)
                }
                else -> null
            }
        }
    }
}
