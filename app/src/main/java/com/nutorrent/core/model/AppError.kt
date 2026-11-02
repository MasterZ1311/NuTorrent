package com.nutorrent.core.model

import com.nutorrent.core.util.FormatUtils
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ErrorCategory {
    STORAGE,
    NETWORK,
    INTEGRITY,
    ANDROID,
    UNKNOWN
}

enum class ErrorAction {
    RETRY,
    CHOOSE_LOCATION,
    CHECK_NETWORK,
    REANNOUNCE,
    FORCE_RECHECK,
    OPEN_SETTINGS,
    DISMISS
}

/**
 * Structured application errors mapped to user-friendly and actionable messages.
 * Answers:
 * 1. What happened?
 * 2. Why did it happen?
 * 3. What can the user do next?
 */
sealed class AppError(
    val title: String,
    val userMessage: String,
    val actionText: String? = null,
    val action: ErrorAction = ErrorAction.DISMISS,
    val category: ErrorCategory = ErrorCategory.UNKNOWN,
    val technicalDetails: String? = null,
    cause: Throwable? = null
) : Exception(userMessage, cause) {

    // ==========================================
    // 1. STORAGE ERRORS
    // ==========================================

    open class InsufficientStorage(
        val requiredBytes: Long,
        val availableBytes: Long? = null,
        val shortfallBytes: Long? = null,
        cause: Throwable? = null
    ) : AppError(
        title = "Insufficient Storage Space",
        userMessage = buildString {
            if (availableBytes != null && requiredBytes > 0) {
                append("The download location only has ${FormatUtils.formatBytes(availableBytes)} free, but this download requires ${FormatUtils.formatBytes(requiredBytes)}")
                if (shortfallBytes != null && shortfallBytes > 0) {
                    append(" (Shortfall: ${FormatUtils.formatBytes(shortfallBytes)})")
                }
                append(". Please free up space or choose another storage location.")
            } else {
                append("Download paused because the destination does not have enough available space. Please free up space or select another folder.")
            }
        },
        actionText = "Choose Location",
        action = ErrorAction.CHOOSE_LOCATION,
        category = ErrorCategory.STORAGE,
        technicalDetails = "Required: $requiredBytes bytes, Available: ${availableBytes ?: -1} bytes",
        cause = cause
    )

    class FileTooLargeForFilesystem(
        val fileName: String,
        val fileSizeBytes: Long,
        cause: Throwable? = null
    ) : AppError(
        title = "File Exceeds FAT32 4 GB Limit",
        userMessage = "\"$fileName\" (${FormatUtils.formatBytes(fileSizeBytes)}) exceeds the 4 GB single-file limit of FAT32 file systems. Please choose internal storage or an exFAT formatted drive.",
        actionText = "Choose Location",
        action = ErrorAction.CHOOSE_LOCATION,
        category = ErrorCategory.STORAGE,
        technicalDetails = "File $fileName is $fileSizeBytes bytes which exceeds FAT32 4GB limit",
        cause = cause
    )

    class DestinationReadOnly(
        val destination: String,
        cause: Throwable? = null
    ) : AppError(
        title = "Storage is Read-Only",
        userMessage = "The storage destination ($destination) is mounted as read-only or write-protected. Please choose another storage location.",
        actionText = "Choose Location",
        action = ErrorAction.CHOOSE_LOCATION,
        category = ErrorCategory.STORAGE,
        technicalDetails = "Read-only filesystem on $destination",
        cause = cause
    )

    fun serializeToString(): String {
        return when (this) {
            is StorageFull -> "STORAGE_FULL|$requiredBytes|${availableBytes ?: -1}"
            is InsufficientStorage -> "INSUFFICIENT_STORAGE|$requiredBytes|${availableBytes ?: -1}|${shortfallBytes ?: -1}"
            is FileTooLargeForFilesystem -> "FAT32_LIMIT|$fileName|$fileSizeBytes"
            is DestinationPermissionRevoked -> "PERMISSION_REVOKED|$destination"
            is DestinationUnavailable -> "DEST_UNAVAILABLE|$destination|$reason"
            is DestinationReadOnly -> "READ_ONLY|$destination"
            else -> "GENERIC|${title.replace('|', '/')}|${userMessage.replace('|', '/')}|${action.name}|${actionText ?: ""}"
        }
    }

    // Backward-compatible alias for FileTorrentStorage/tests
    class StorageFull(requiredBytes: Long, availableBytes: Long, cause: Throwable? = null) :
        InsufficientStorage(
            requiredBytes = requiredBytes,
            availableBytes = availableBytes,
            shortfallBytes = (requiredBytes - availableBytes).coerceAtLeast(0L),
            cause = cause
        ) {
        override val message: String
            get() = "Insufficient storage space: required $requiredBytes, available $availableBytes"
    }

    class DestinationPermissionRevoked(val destination: String, cause: Throwable? = null) :
        AppError(
            title = "Destination permission revoked",
            userMessage = "NuTorrent lost access to the selected download folder. Storage permission was revoked by the system or user.",
            actionText = "Re-select Folder",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Permission revoked for: $destination",
            cause = cause
        )

    class DestinationUnavailable(val destination: String, val reason: String = "Destination folder is inaccessible", cause: Throwable? = null) :
        AppError(
            title = "Destination folder unavailable",
            userMessage = "Cannot access the download location. The storage volume or drive may be unmounted or disconnected: $reason",
            actionText = "Choose Location",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Destination $destination: $reason",
            cause = cause
        )

    class FolderDeletedOrMoved(val destination: String, cause: Throwable? = null) :
        AppError(
            title = "Folder deleted or moved",
            userMessage = "The download folder was removed, renamed, or moved from its original location.",
            actionText = "Choose Location",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Missing path: $destination",
            cause = cause
        )

    class FileCreationFailed(val path: String, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "File creation failure",
            userMessage = "Could not create target download file on disk: $reason",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Failed creating $path: $reason",
            cause = cause
        )

    class FileWriteFailed(val path: String, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "File write failure",
            userMessage = "Could not write downloaded data blocks to storage: $reason",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Write error on $path: $reason",
            cause = cause
        )

    class FileReadFailed(val path: String, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "File read failure",
            userMessage = "Could not read data from disk for verification or seeding: $reason",
            actionText = "Force Recheck",
            action = ErrorAction.FORCE_RECHECK,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Read error on $path: $reason",
            cause = cause
        )

    class StorageProviderUnavailable(val uri: String, cause: Throwable? = null) :
        AppError(
            title = "Storage provider unavailable",
            userMessage = "The Android storage provider (SAF) failed to open the selected tree document.",
            actionText = "Choose Location",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Provider failed for URI: $uri",
            cause = cause
        )

    class InvalidDestination(val destination: String, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "Invalid destination",
            userMessage = "The selected download path is not a valid or accessible folder: $reason",
            actionText = "Choose Location",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Path $destination: $reason",
            cause = cause
        )

    class FileAlreadyExists(val path: String, cause: Throwable? = null) :
        AppError(
            title = "File already exists",
            userMessage = "A file with the same name already exists and cannot be safely overwritten.",
            actionText = "Choose Location",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.STORAGE,
            technicalDetails = "File conflict: $path",
            cause = cause
        )

    class DestinationNotWritable(val destination: String, cause: Throwable? = null) :
        AppError(
            title = "Destination not writable",
            userMessage = "NuTorrent does not have write permissions for the selected directory.",
            actionText = "Re-select Folder",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.STORAGE,
            technicalDetails = "Cannot write to $destination",
            cause = cause
        )

    class InaccessibleFile(val path: String, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "Inaccessible file",
            userMessage = "A required torrent payload file is incomplete, locked, or inaccessible: $reason",
            actionText = "Force Recheck",
            action = ErrorAction.FORCE_RECHECK,
            category = ErrorCategory.STORAGE,
            technicalDetails = "File $path: $reason",
            cause = cause
        )

    class StoragePermissionLost(detail: String, cause: Throwable? = null) :
        AppError(
            title = "Storage permission revoked",
            userMessage = "Storage permission was revoked: $detail",
            actionText = "Re-select Folder",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.STORAGE,
            technicalDetails = detail,
            cause = cause
        )

    class StorageIoError(detail: String, cause: Throwable? = null) :
        AppError(
            title = "Storage I/O error",
            userMessage = "Storage read/write error occurred: $detail",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.STORAGE,
            technicalDetails = detail,
            cause = cause
        )

    class StorageSecurityError(detail: String, cause: Throwable? = null) :
        AppError(
            title = "Storage security violation",
            userMessage = "Storage operation blocked due to path security restrictions: $detail",
            actionText = "Dismiss",
            action = ErrorAction.DISMISS,
            category = ErrorCategory.STORAGE,
            technicalDetails = detail,
            cause = cause
        )

    // ==========================================
    // 2. NETWORK AND PEER ERRORS
    // ==========================================

    class NoInternetConnection(cause: Throwable? = null) :
        AppError(
            title = "No internet connection",
            userMessage = "Cannot connect to peer swarm or trackers because this device is currently offline.",
            actionText = "Check Network",
            action = ErrorAction.CHECK_NETWORK,
            category = ErrorCategory.NETWORK,
            cause = cause
        )

    class NetworkDisconnected(val reason: String = "Network disconnected", cause: Throwable? = null) :
        AppError(
            title = "Network disconnected",
            userMessage = "Active transfer was paused because network connection was lost: $reason",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.NETWORK,
            technicalDetails = reason,
            cause = cause
        )

    class DnsResolutionFailure(val host: String, cause: Throwable? = null) :
        AppError(
            title = "DNS resolution failure",
            userMessage = "Could not resolve hostname '$host'. Check your internet connection and DNS settings.",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.NETWORK,
            technicalDetails = "DNS lookup failed for $host",
            cause = cause
        )

    class TrackerTimeout(val trackerUrl: String, cause: Throwable? = null) :
        AppError(
            title = "Tracker timeout",
            userMessage = "Tracker could not be reached in time (timed out).",
            actionText = "Retry Trackers",
            action = ErrorAction.REANNOUNCE,
            category = ErrorCategory.NETWORK,
            technicalDetails = "Timeout contacting $trackerUrl",
            cause = cause
        )

    class TrackerFailure(val trackerUrl: String, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "Tracker returned an error",
            userMessage = "Tracker reported an error: $reason",
            actionText = "Retry Trackers",
            action = ErrorAction.REANNOUNCE,
            category = ErrorCategory.NETWORK,
            technicalDetails = "Tracker $trackerUrl: $reason",
            cause = cause
        )

    class TrackerError(val trackerUrl: String, val error: String, cause: Throwable? = null) :
        AppError(
            title = "Tracker returned an error",
            userMessage = "Tracker ($trackerUrl) reported error: $error",
            actionText = "Retry Trackers",
            action = ErrorAction.REANNOUNCE,
            category = ErrorCategory.NETWORK,
            technicalDetails = "Tracker error: $error",
            cause = cause
        )

    class NoPeersAvailable(val reason: String = "No peers found in swarm", cause: Throwable? = null) :
        AppError(
            title = "No peers available",
            userMessage = "No active seeders or peers could be discovered for this torrent.",
            actionText = "Retry Trackers",
            action = ErrorAction.REANNOUNCE,
            category = ErrorCategory.NETWORK,
            technicalDetails = reason,
            cause = cause
        )

    class PeerConnectionTimeout(val endpoint: PeerEndpoint, cause: Throwable? = null) :
        AppError(
            title = "Peer connection timeout",
            userMessage = "Connection attempt to peer $endpoint timed out.",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.NETWORK,
            technicalDetails = "Timeout connecting to $endpoint",
            cause = cause
        )

    class PeerConnectionFailure(val endpoint: PeerEndpoint, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "Peer connection failure",
            userMessage = "Connection to peer $endpoint failed: $reason",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.NETWORK,
            technicalDetails = "Peer $endpoint error: $reason",
            cause = cause
        )

    class PeerDisconnected(val endpoint: PeerEndpoint, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "Peer disconnected",
            userMessage = "Remote peer closed the connection: $reason",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.NETWORK,
            technicalDetails = "Peer $endpoint disconnected: $reason",
            cause = cause
        )

    class InvalidPeerResponse(val endpoint: PeerEndpoint, val reason: String, cause: Throwable? = null) :
        AppError(
            title = "Invalid peer response",
            userMessage = "Peer sent an unexpected or invalid protocol message: $reason",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.NETWORK,
            technicalDetails = "Invalid peer message from $endpoint: $reason",
            cause = cause
        )

    class AllPeersChoking(cause: Throwable? = null) :
        AppError(
            title = "All peers choking",
            userMessage = "All connected peers are currently choking this client. Waiting for unchoke slots.",
            actionText = "Retry Trackers",
            action = ErrorAction.REANNOUNCE,
            category = ErrorCategory.NETWORK,
            cause = cause
        )

    class DhtUnavailable(cause: Throwable? = null) :
        AppError(
            title = "DHT unavailable",
            userMessage = "Distributed Hash Table (DHT) node is not bootstrapped or network UDP is blocked.",
            actionText = "Check Network",
            action = ErrorAction.CHECK_NETWORK,
            category = ErrorCategory.NETWORK,
            cause = cause
        )

    class MagnetMetadataUnavailable(cause: Throwable? = null) :
        AppError(
            title = "Magnet metadata unavailable",
            userMessage = "Unable to fetch metadata from swarm. The torrent may have no active seeders.",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.NETWORK,
            cause = cause
        )

    class RequestTimeout(val pieceIndex: Int, cause: Throwable? = null) :
        AppError(
            title = "Request timeout",
            userMessage = "Piece #$pieceIndex block request timed out from peer.",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.NETWORK,
            technicalDetails = "Timeout on piece $pieceIndex",
            cause = cause
        )

    class NetworkUnavailable(cause: Throwable? = null) :
        AppError(
            title = "Network unavailable",
            userMessage = "No active network connection available.",
            actionText = "Check Network",
            action = ErrorAction.CHECK_NETWORK,
            category = ErrorCategory.NETWORK,
            cause = cause
        )

    // ==========================================
    // 3. TORRENT AND INTEGRITY ERRORS
    // ==========================================

    class InvalidTorrent(detail: String, cause: Throwable? = null) :
        AppError(
            title = "Invalid torrent",
            userMessage = "The torrent file or metainfo is corrupted or invalid: $detail",
            actionText = "Dismiss",
            action = ErrorAction.DISMISS,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = detail,
            cause = cause
        )

    class InvalidMagnetUri(val uri: String, cause: Throwable? = null) :
        AppError(
            title = "Invalid magnet URI",
            userMessage = "The provided magnet URI format could not be parsed.",
            actionText = "Dismiss",
            action = ErrorAction.DISMISS,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = "Malformed magnet: $uri",
            cause = cause
        )

    class InvalidMetadata(val detail: String, cause: Throwable? = null) :
        AppError(
            title = "Invalid metadata",
            userMessage = "The torrent metadata received from peers is malformed: $detail",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = detail,
            cause = cause
        )

    class InfoHashMismatch(val expected: String, val actual: String, cause: Throwable? = null) :
        AppError(
            title = "Info-hash mismatch",
            userMessage = "Downloaded metadata hash ($actual) did not match the expected magnet info-hash ($expected).",
            actionText = "Dismiss",
            action = ErrorAction.DISMISS,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = "Expected $expected, got $actual",
            cause = cause
        )

    class CorruptPiece(val pieceIndex: Int, cause: Throwable? = null) :
        AppError(
            title = "Corrupt piece detected",
            userMessage = "Piece #$pieceIndex failed cryptographic SHA-1 verification and will be discarded.",
            actionText = "Force Recheck",
            action = ErrorAction.FORCE_RECHECK,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = "Piece $pieceIndex hash mismatch",
            cause = cause
        )

    class PieceVerificationFailed(val pieceIndex: Int, cause: Throwable? = null) :
        AppError(
            title = "Piece verification failed",
            userMessage = "Verification check failed for piece #$pieceIndex.",
            actionText = "Force Recheck",
            action = ErrorAction.FORCE_RECHECK,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = "Verification error on piece $pieceIndex",
            cause = cause
        )

    class PieceHashMismatch(val pieceIndex: Int) :
        AppError(
            title = "Piece hash mismatch",
            userMessage = "Piece #$pieceIndex failed SHA-1 hash verification and will be re-downloaded",
            actionText = "Force Recheck",
            action = ErrorAction.FORCE_RECHECK,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = "Hash mismatch on piece $pieceIndex"
        )

    class UnsupportedTorrent(feature: String, cause: Throwable? = null) :
        AppError(
            title = "Unsupported torrent format",
            userMessage = "This torrent uses protocol features that are not yet supported: $feature",
            actionText = "Dismiss",
            action = ErrorAction.DISMISS,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = "Unsupported feature: $feature",
            cause = cause
        )

    class InvalidBlockResponse(val pieceIndex: Int, val offset: Int, cause: Throwable? = null) :
        AppError(
            title = "Invalid block response",
            userMessage = "Received unexpected block for piece #$pieceIndex at offset $offset.",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = "Invalid block at piece $pieceIndex, offset $offset",
            cause = cause
        )

    class TorrentAlreadyExists(val name: String, cause: Throwable? = null) :
        AppError(
            title = "Torrent already exists",
            userMessage = "Torrent '$name' is already in your download queue.",
            actionText = "Dismiss",
            action = ErrorAction.DISMISS,
            category = ErrorCategory.INTEGRITY,
            technicalDetails = "Duplicate info hash for $name",
            cause = cause
        )

    // ==========================================
    // 4. ANDROID AND LIFECYCLE ERRORS
    // ==========================================

    class NotificationPermissionUnavailable(cause: Throwable? = null) :
        AppError(
            title = "Notification permission unavailable",
            userMessage = "Foreground download notification cannot be displayed because notifications are disabled.",
            actionText = "Open Settings",
            action = ErrorAction.OPEN_SETTINGS,
            category = ErrorCategory.ANDROID,
            cause = cause
        )

    class ForegroundServiceStartFailure(val reason: String, cause: Throwable? = null) :
        AppError(
            title = "Service start failure",
            userMessage = "Foreground download service could not be started: $reason",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.ANDROID,
            technicalDetails = reason,
            cause = cause
        )

    class BackgroundTransferInterrupted(val reason: String, cause: Throwable? = null) :
        AppError(
            title = "Transfer interrupted",
            userMessage = "Background transfer was interrupted by system battery optimization or memory management: $reason",
            actionText = "Resume",
            action = ErrorAction.RETRY,
            category = ErrorCategory.ANDROID,
            technicalDetails = reason,
            cause = cause
        )

    class ProcessRestarted(cause: Throwable? = null) :
        AppError(
            title = "Application process restarted",
            userMessage = "The app process was restarted. Downloads are being restored from disk.",
            actionText = "Resume",
            action = ErrorAction.RETRY,
            category = ErrorCategory.ANDROID,
            cause = cause
        )

    class PersistedPermissionLost(val uri: String, cause: Throwable? = null) :
        AppError(
            title = "Storage permission lost",
            userMessage = "Persisted access permission to download folder was lost after device restart.",
            actionText = "Re-select Folder",
            action = ErrorAction.CHOOSE_LOCATION,
            category = ErrorCategory.ANDROID,
            technicalDetails = "Persisted permission lost for $uri",
            cause = cause
        )

    class DatabaseError(val detail: String, cause: Throwable? = null) :
        AppError(
            title = "Database error",
            userMessage = "Database operation failed: $detail",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.ANDROID,
            technicalDetails = detail,
            cause = cause
        )

    class TransferCancelled(cause: Throwable? = null) :
        AppError(
            title = "Transfer cancelled",
            userMessage = "Operation was cancelled.",
            actionText = "Dismiss",
            action = ErrorAction.DISMISS,
            category = ErrorCategory.ANDROID,
            cause = cause
        )

    class UnexpectedError(val detail: String, cause: Throwable? = null) :
        AppError(
            title = "Unexpected error",
            userMessage = "An unexpected error occurred: $detail",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.UNKNOWN,
            technicalDetails = detail,
            cause = cause
        )

    class UnknownError(detail: String, cause: Throwable? = null) :
        AppError(
            title = "Unexpected error",
            userMessage = "An unexpected error occurred: $detail",
            actionText = "Retry",
            action = ErrorAction.RETRY,
            category = ErrorCategory.UNKNOWN,
            technicalDetails = detail,
            cause = cause
        )

    class GenericError(
        title: String,
        userMessage: String,
        actionText: String? = null,
        action: ErrorAction = ErrorAction.DISMISS,
        category: ErrorCategory = ErrorCategory.UNKNOWN,
        technicalDetails: String? = null,
        cause: Throwable? = null
    ) : AppError(title, userMessage, actionText, action, category, technicalDetails, cause)

    companion object {
        fun deserializeFromString(str: String): AppError {
            val parts = str.split('|')
            if (parts.isEmpty()) return UnknownError("Unknown error")
            return try {
                when (parts[0]) {
                    "STORAGE_FULL", "INSUFFICIENT_STORAGE" -> {
                        val req = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        val avail = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it >= 0 }
                        val shortfall = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it >= 0 }
                        InsufficientStorage(req, avail, shortfall)
                    }
                    "FAT32_LIMIT" -> {
                        val name = parts.getOrNull(1) ?: "file"
                        val size = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                        FileTooLargeForFilesystem(name, size)
                    }
                    "PERMISSION_REVOKED" -> DestinationPermissionRevoked(parts.getOrNull(1) ?: "storage")
                    "DEST_UNAVAILABLE" -> DestinationUnavailable(parts.getOrNull(1) ?: "storage", parts.getOrNull(2) ?: "unmounted")
                    "READ_ONLY" -> DestinationReadOnly(parts.getOrNull(1) ?: "storage")
                    "GENERIC" -> {
                        val title = parts.getOrNull(1) ?: "Error"
                        val msg = parts.getOrNull(2) ?: "Error occurred"
                        val actName = parts.getOrNull(3) ?: "DISMISS"
                        val actText = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
                        val act = try { ErrorAction.valueOf(actName) } catch (e: Exception) { ErrorAction.DISMISS }
                        GenericError(title, msg, actText, act)
                    }
                    else -> UnexpectedError(str)
                }
            } catch (e: Exception) {
                UnexpectedError(str)
            }
        }

        /**
         * Translates standard Java, Android, and network exceptions into actionable [AppError].
         */
        fun fromThrowable(t: Throwable, availableBytes: Long? = null, totalBytes: Long? = null): AppError {
            if (t is AppError) return t

            // Traverse cause chain to inspect errno and error messages
            var current: Throwable? = t
            var isNoSpace = false
            var isFileTooLarge = false
            var isReadOnly = false
            var isPermission = false

            while (current != null) {
                val msg = current.message ?: ""
                val className = current.javaClass.name

                if (msg.contains("ENOSPC", ignoreCase = true) ||
                    msg.contains("No space left on device", ignoreCase = true) ||
                    msg.contains("not enough space", ignoreCase = true) ||
                    msg.contains("disk full", ignoreCase = true) ||
                    msg.contains("storage full", ignoreCase = true) ||
                    msg.contains("EDQUOT", ignoreCase = true)
                ) {
                    isNoSpace = true
                }
                if (msg.contains("EFBIG", ignoreCase = true) || msg.contains("File too large", ignoreCase = true)) {
                    isFileTooLarge = true
                }
                if (msg.contains("EROFS", ignoreCase = true) || msg.contains("Read-only file system", ignoreCase = true)) {
                    isReadOnly = true
                }
                if (current is SecurityException || msg.contains("Permission denied", ignoreCase = true) || msg.contains("EACCES", ignoreCase = true)) {
                    isPermission = true
                }

                if (className.contains("ErrnoException", ignoreCase = true)) {
                    try {
                        val errnoField = current.javaClass.getField("errno")
                        val errno = errnoField.getInt(current)
                        if (errno == 28 || errno == 122) isNoSpace = true // ENOSPC = 28, EDQUOT = 122
                        if (errno == 27) isFileTooLarge = true // EFBIG = 27
                        if (errno == 30) isReadOnly = true // EROFS = 30
                        if (errno == 13 || errno == 1) isPermission = true // EACCES = 13, EPERM = 1
                    } catch (ignored: Exception) {}
                }

                current = current.cause
            }

            // Also check if available space on disk is critically low (< 50 MB) when any IOException occurs
            if (!isNoSpace && t is IOException && availableBytes != null && availableBytes < 50 * 1024 * 1024L) {
                isNoSpace = true
            }

            if (isNoSpace) {
                val req = totalBytes ?: 0L
                val avail = availableBytes ?: 0L
                val shortfall = if (req > avail && avail > 0) req - avail else null
                return InsufficientStorage(
                    requiredBytes = req,
                    availableBytes = if (availableBytes != null) avail else null,
                    shortfallBytes = shortfall,
                    cause = t
                )
            }

            if (isFileTooLarge) {
                return FileTooLargeForFilesystem(
                    fileName = "Downloaded File",
                    fileSizeBytes = totalBytes ?: (4L * 1024 * 1024 * 1024L),
                    cause = t
                )
            }

            if (isReadOnly) {
                return DestinationReadOnly(destination = "Download location", cause = t)
            }

            if (isPermission) {
                return DestinationPermissionRevoked(destination = "Storage destination", cause = t)
            }

            val msg = t.message ?: t.javaClass.simpleName
            return when (t) {
                is UnknownHostException -> DnsResolutionFailure(host = t.message ?: "host", cause = t)
                is SocketTimeoutException -> TrackerTimeout(trackerUrl = "Tracker", cause = t)
                is ConnectException -> NoInternetConnection(cause = t)
                is kotlinx.coroutines.CancellationException -> TransferCancelled(cause = t)
                is IOException -> FileWriteFailed(path = "storage", reason = msg, cause = t)
                else -> UnexpectedError(detail = msg, cause = t)
            }
        }
    }
}
