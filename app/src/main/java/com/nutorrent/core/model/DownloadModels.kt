package com.nutorrent.core.model

/**
 * Represents a request for a sub-piece block of data.
 * Standard block size is typically 16 KiB (16384 bytes).
 */
data class BlockRequest(
    val pieceIndex: Int,
    val beginOffset: Int,
    val length: Int
) {
    init {
        require(pieceIndex >= 0) { "Piece index cannot be negative" }
        require(beginOffset >= 0) { "Begin offset cannot be negative" }
        require(length in 1..131072) { "Block length must be between 1 and 128 KiB" } // 128 KiB safety bound
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 16384 // 16 KiB standard BitTorrent block
    }
}

data class TorrentProgress(
    val downloadedBytes: Long = 0,
    val uploadedBytes: Long = 0,
    val verifiedBytes: Long = 0,
    val totalBytes: Long = 0,
    val completedPieces: Int = 0,
    val totalPieces: Int = 0,
    val downloadSpeedBps: Long = 0,
    val uploadSpeedBps: Long = 0,
    val etaSeconds: Long? = null,
    val connectedPeers: Int = 0,
    val totalPeers: Int = 0,
    val connectedSeeds: Int = 0,
    val availableSeeds: Int? = null
) {
    val progressFraction: Float = if (totalBytes > 0) {
        (verifiedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else 0f

    val progressPercent: Int = (progressFraction * 100).toInt()

    val shareRatio: Double = if (totalBytes > 0) {
        uploadedBytes.toDouble() / totalBytes.toDouble()
    } else 0.0
}

data class TrackerInfo(
    val url: String,
    val tier: Int = 0,
    val status: String = "Not contacted",
    val peersCount: Int = 0,
    val seedsCount: Int? = null,
    val intervalSeconds: Int = 1800,
    val minIntervalSeconds: Int? = null,
    val lastError: String? = null
)

data class PeerStateInfo(
    val endpoint: PeerEndpoint,
    val isConnected: Boolean = false,
    val isHandshakeComplete: Boolean = false,
    val isChokingUs: Boolean = true,
    val isInterestedInUs: Boolean = false,
    val isWeChoking: Boolean = true,
    val isWeInterested: Boolean = false,
    val downloadedBytes: Long = 0,
    val uploadedBytes: Long = 0,
    val pendingRequestsCount: Int = 0,
    val piecesCount: Int = 0
)

/**
 * Detailed reason why a torrent is in a non-active state.
 * Allows distinguishing user-initiated pauses from rule-based pauses,
 * ensuring automatic rule recovery never overrides manual user decisions.
 */
enum class PauseReason(val label: String) {
    NONE("Active"),
    USER_MANUAL("Paused"),
    RULE_WIFI_ONLY("Paused (Wi-Fi only)"),
    RULE_MOBILE_DATA("Paused (Mobile data)"),
    RULE_LOW_BATTERY("Paused (Low battery)"),
    RULE_NOT_CHARGING("Paused (Not charging)"),
    RULE_BATTERY_SAVER("Paused (Battery saver)"),
    QUEUED("Queued")
}
