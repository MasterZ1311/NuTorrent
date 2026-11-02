package com.nutorrent.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nutorrent.core.model.TorrentMetadata
import java.io.File

data class StorageAssessment(
    val requiredBytes: Long,
    val availableBytes: Long?,
    val shortfallBytes: Long?,
    val isInsufficient: Boolean,
    val isCapacityUnknown: Boolean,
    val existingBytes: Long,
    val safetyMarginBytes: Long
)

object StorageCalculator {
    const val SAFETY_MARGIN_BYTES = 15 * 1024 * 1024L // 15 MB safety margin for metadata & filesystem overhead

    /**
     * Calculates required disk space for the torrent, accounting for selected vs skipped files,
     * existing downloaded bytes, and a safety margin.
     */
    fun calculateRequiredBytes(
        metadata: TorrentMetadata,
        selectedFileIndices: Set<Int>? = null,
        existingVerifiedBytes: Long = 0L
    ): Long {
        val totalSelectedBytes = if (selectedFileIndices == null || selectedFileIndices.isEmpty()) {
            metadata.totalLength
        } else {
            metadata.files
                .filter { it.index in selectedFileIndices }
                .sumOf { it.length }
        }
        val remaining = (totalSelectedBytes - existingVerifiedBytes).coerceAtLeast(0L)
        return remaining + SAFETY_MARGIN_BYTES
    }

    /**
     * Performs a capacity check against the specific volume for [destinationPath].
     */
    fun assessStorage(
        context: Context,
        destinationPath: String,
        metadata: TorrentMetadata,
        selectedFileIndices: Set<Int>? = null,
        existingVerifiedBytes: Long = 0L
    ): StorageAssessment {
        val required = calculateRequiredBytes(metadata, selectedFileIndices, existingVerifiedBytes)
        val available = getAvailableBytes(context, destinationPath)

        return if (available == null) {
            StorageAssessment(
                requiredBytes = required,
                availableBytes = null,
                shortfallBytes = null,
                isInsufficient = false,
                isCapacityUnknown = true,
                existingBytes = existingVerifiedBytes,
                safetyMarginBytes = SAFETY_MARGIN_BYTES
            )
        } else {
            val shortfall = (required - available).coerceAtLeast(0L)
            StorageAssessment(
                requiredBytes = required,
                availableBytes = available,
                shortfallBytes = if (shortfall > 0) shortfall else null,
                isInsufficient = available < required,
                isCapacityUnknown = false,
                existingBytes = existingVerifiedBytes,
                safetyMarginBytes = SAFETY_MARGIN_BYTES
            )
        }
    }

    /**
     * Queries available capacity in bytes on the destination storage volume.
     * Returns null if capacity cannot be determined (e.g. some SAF providers, cloud providers, or restricted URIs).
     */
    fun getAvailableBytes(context: Context, destinationPath: String): Long? {
        val trimmed = destinationPath.trim()
        if (trimmed.isEmpty()) return null

        return if (trimmed.startsWith("content://", ignoreCase = true)) {
            getAvailableBytesFromTreeUri(context, Uri.parse(trimmed))
        } else {
            val file = if (trimmed.startsWith("file://", ignoreCase = true)) {
                File(Uri.parse(trimmed).path ?: trimmed.removePrefix("file://"))
            } else {
                File(trimmed)
            }
            try {
                // Ensure directory exists or check parent for usable space
                var checkDir: File? = file
                while (checkDir != null && !checkDir.exists()) {
                    checkDir = checkDir.parentFile
                }
                val check = checkDir ?: if (file.exists()) file else null
                if (check != null && check.exists()) {
                    check.usableSpace
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getAvailableBytesFromTreeUri(context: Context, uri: Uri): Long? {
        return try {
            val docId = try {
                if (DocumentsContract.isTreeUri(uri)) {
                    DocumentsContract.getTreeDocumentId(uri)
                } else null
            } catch (e: Exception) {
                null
            } ?: return null

            if (docId.startsWith("primary:", ignoreCase = true)) {
                val statFs = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath)
                statFs.availableBytes
            } else {
                val volumeId = docId.substringBefore(':')
                val externalDirs = try {
                    context.getExternalFilesDirs(null)
                } catch (e: Exception) {
                    emptyArray<File?>()
                }
                val matchedDir = externalDirs.filterNotNull().firstOrNull { it.absolutePath.contains(volumeId, ignoreCase = true) }
                if (matchedDir != null) {
                    val statFs = android.os.StatFs(matchedDir.absolutePath)
                    statFs.availableBytes
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if the target storage volume is formatted with a FAT32 / VFAT file system
     * which enforces a maximum single file size limit of 4 GB.
     */
    fun isFat32(pathOrUri: String): Boolean {
        return try {
            val mounts = File("/proc/mounts")
            if (!mounts.exists() || !mounts.canRead()) return false

            val target = if (pathOrUri.startsWith("content://", ignoreCase = true)) {
                val uri = Uri.parse(pathOrUri)
                val docId = try {
                    if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) else null
                } catch (e: Exception) { null }
                if (docId != null && !docId.startsWith("primary:", ignoreCase = true)) {
                    docId.substringBefore(':')
                } else {
                    return false
                }
            } else {
                pathOrUri.removePrefix("file://")
            }

            var matchedFs: String? = null
            var longestMatchLen = 0
            mounts.forEachLine { line ->
                val tokens = line.split("\\s+".toRegex())
                if (tokens.size >= 3) {
                    val mountPoint = tokens[1]
                    val fsType = tokens[2].lowercase()
                    if (target.contains(mountPoint) || mountPoint.contains(target)) {
                        if (mountPoint.length > longestMatchLen) {
                            longestMatchLen = mountPoint.length
                            matchedFs = fsType
                        }
                    }
                }
            }
            matchedFs in listOf("vfat", "fat32", "msdos", "fat")
        } catch (e: Exception) {
            false
        }
    }
}
