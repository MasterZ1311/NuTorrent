package com.nutorrent.torrent.tracker

import com.nutorrent.core.model.PeerEndpoint
import java.security.SecureRandom

/**
 * Generates BitTorrent Azureus-style peer IDs without using any device-identifying information.
 * Format: -NU1000-xxxxxxxxxxxx (20 bytes total).
 */
object PeerIdGenerator {
    private val random = SecureRandom()
    private const val PREFIX = "-NU1000-" // NU = NuTorrent 1.0.0

    fun generate(): ByteArray {
        val peerId = ByteArray(20)
        val prefixBytes = PREFIX.toByteArray(Charsets.US_ASCII)
        System.arraycopy(prefixBytes, 0, peerId, 0, prefixBytes.size)

        val characters = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        for (i in prefixBytes.size until 20) {
            val charIdx = random.nextInt(characters.length)
            peerId[i] = characters[charIdx].code.toByte()
        }
        return peerId
    }
}

enum class TrackerEvent(val paramValue: String) {
    STARTED("started"),
    STOPPED("stopped"),
    COMPLETED("completed"),
    REGULAR("")
}

data class TrackerAnnounceRequest(
    val trackerUrl: String,
    val infoHash: ByteArray,
    val peerId: ByteArray,
    val port: Int = 6881,
    val uploaded: Long = 0,
    val downloaded: Long = 0,
    val left: Long = 0,
    val compact: Boolean = true,
    val event: TrackerEvent = TrackerEvent.REGULAR
)

data class TrackerAnnounceResponse(
    val intervalSeconds: Int,
    val minIntervalSeconds: Int?,
    val seeders: Int?,
    val leechers: Int?,
    val peers: List<PeerEndpoint>,
    val warningMessage: String? = null
)
