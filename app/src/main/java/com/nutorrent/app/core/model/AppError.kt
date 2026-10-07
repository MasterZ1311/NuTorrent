package com.nutorrent.app.core.model

/**
 * Structured application errors mapped to user-friendly messages.
 */
sealed class AppError(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {

    class InvalidTorrent(detail: String, cause: Throwable? = null) :
        AppError("The torrent file is invalid: $detail", cause)

    class UnsupportedTorrent(feature: String) :
        AppError("This torrent uses features that are not supported yet: $feature")

    class TrackerFailure(val trackerUrl: String, reason: String) :
        AppError("Tracker ($trackerUrl) reported an error: $reason")

    class TrackerTimeout(val trackerUrl: String, cause: Throwable? = null) :
        AppError("Tracker ($trackerUrl) timed out", cause)

    class PeerConnectionFailure(val endpoint: PeerEndpoint, reason: String, cause: Throwable? = null) :
        AppError("Connection to peer $endpoint failed: $reason", cause)

    class StoragePermissionLost(detail: String) :
        AppError("Storage permission was revoked: $detail")

    class StorageFull(requiredBytes: Long, availableBytes: Long) :
        AppError("Insufficient storage space. Required: $requiredBytes, available: $availableBytes")

    class StorageIoError(detail: String, cause: Throwable? = null) :
        AppError("Storage read/write error: $detail", cause)

    class StorageSecurityError(detail: String) :
        AppError("Storage security violation: $detail")

    class PieceHashMismatch(val pieceIndex: Int) :
        AppError("Piece #$pieceIndex failed SHA-1 hash verification and will be re-downloaded")

    class NetworkUnavailable :
        AppError("No active network connection")

    class UnknownError(detail: String, cause: Throwable? = null) :
        AppError("An unexpected error occurred: $detail", cause)
}
