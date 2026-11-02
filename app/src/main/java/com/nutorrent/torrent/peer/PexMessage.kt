package com.nutorrent.torrent.peer

import com.nutorrent.core.model.PeerEndpoint
import com.nutorrent.torrent.bencode.BencodeDecoder
import com.nutorrent.torrent.bencode.BencodeElement
import com.nutorrent.torrent.bencode.BencodeEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BEP 11 Peer Exchange (PEX) message representation and codec.
 */
data class PexMessage(
    val added: List<PeerEndpoint> = emptyList(),
    val dropped: List<PeerEndpoint> = emptyList()
) {
    fun toBencodeBytes(): ByteArray {
        val root = linkedMapOf<String, BencodeElement>()

        if (added.isNotEmpty()) {
            val buf = ByteBuffer.allocate(added.size * 6).apply {
                order(ByteOrder.BIG_ENDIAN)
                for (peer in added) {
                    val parts = peer.ip.split(".")
                    for (p in parts) put((p.toInt() and 0xFF).toByte())
                    putShort(peer.port.toShort())
                }
            }
            root["added"] = BencodeElement.BString(buf.array())
        }

        if (dropped.isNotEmpty()) {
            val buf = ByteBuffer.allocate(dropped.size * 6).apply {
                order(ByteOrder.BIG_ENDIAN)
                for (peer in dropped) {
                    val parts = peer.ip.split(".")
                    for (p in parts) put((p.toInt() and 0xFF).toByte())
                    putShort(peer.port.toShort())
                }
            }
            root["dropped"] = BencodeElement.BString(buf.array())
        }

        return BencodeEncoder.encode(BencodeElement.BDictionary(root))
    }

    companion object {
        fun parse(payload: ByteArray): PexMessage? {
            if (payload.isEmpty()) return null
            val root = try {
                BencodeDecoder.decode(payload) as? BencodeElement.BDictionary
            } catch (e: Exception) {
                return null
            } ?: return null

            val addedList = mutableListOf<PeerEndpoint>()
            val addedBytes = root.getByteArray("added")
            if (addedBytes != null) {
                val buf = ByteBuffer.wrap(addedBytes).apply { order(ByteOrder.BIG_ENDIAN) }
                while (buf.remaining() >= 6) {
                    val ip0 = buf.get().toInt() and 0xFF
                    val ip1 = buf.get().toInt() and 0xFF
                    val ip2 = buf.get().toInt() and 0xFF
                    val ip3 = buf.get().toInt() and 0xFF
                    val port = buf.getShort().toInt() and 0xFFFF
                    if (port > 0) {
                        addedList.add(PeerEndpoint("$ip0.$ip1.$ip2.$ip3", port))
                    }
                }
            }

            val droppedList = mutableListOf<PeerEndpoint>()
            val droppedBytes = root.getByteArray("dropped")
            if (droppedBytes != null) {
                val buf = ByteBuffer.wrap(droppedBytes).apply { order(ByteOrder.BIG_ENDIAN) }
                while (buf.remaining() >= 6) {
                    val ip0 = buf.get().toInt() and 0xFF
                    val ip1 = buf.get().toInt() and 0xFF
                    val ip2 = buf.get().toInt() and 0xFF
                    val ip3 = buf.get().toInt() and 0xFF
                    val port = buf.getShort().toInt() and 0xFFFF
                    if (port > 0) {
                        droppedList.add(PeerEndpoint("$ip0.$ip1.$ip2.$ip3", port))
                    }
                }
            }

            return PexMessage(addedList, droppedList)
        }
    }
}
