package com.nutorrent.data.storage

import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Fast Resume metadata stored for a torrent to bypass redundant disk hash verifications on startup.
 */
data class FastResumeData(
    val infoHash: String,
    val isComplete: Boolean,
    val completedPieces: Int,
    val totalPieces: Int,
    val verifiedBytes: Long,
    val totalBytes: Long,
    val bitfield: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FastResumeData) return false
        return infoHash == other.infoHash &&
                isComplete == other.isComplete &&
                completedPieces == other.completedPieces &&
                totalPieces == other.totalPieces &&
                verifiedBytes == other.verifiedBytes &&
                totalBytes == other.totalBytes &&
                bitfield.contentEquals(other.bitfield)
    }

    override fun hashCode(): Int {
        var result = infoHash.hashCode()
        result = 31 * result + isComplete.hashCode()
        result = 31 * result + completedPieces
        result = 31 * result + totalPieces
        result = 31 * result + verifiedBytes.hashCode()
        result = 31 * result + totalBytes.hashCode()
        result = 31 * result + bitfield.contentHashCode()
        return result
    }
}

/**
 * Manages fast-resume caching for instant startup without disk re-verification.
 */
object FastResumeManager {

    private val memoryCache = ConcurrentHashMap<String, FastResumeData>()
    var cacheDir: File? = null

    fun setBaseDirectory(dir: File) {
        cacheDir = File(dir, "fastresume").apply { mkdirs() }
    }

    /**
     * Persists fast-resume information in-memory and to disk.
     */
    fun save(data: FastResumeData) {
        memoryCache[data.infoHash] = data
        val dir = cacheDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${data.infoHash}.resume")
            ObjectOutputStream(FileOutputStream(file)).use { oos ->
                oos.writeObject(data)
            }
        } catch (e: Exception) {
            Lg.w(LogCategory.STORAGE, "Failed writing fastresume for ${data.infoHash}: ${e.message}")
        }
    }

    /**
     * Loads fast-resume data for a given info-hash.
     */
    fun load(infoHash: String): FastResumeData? {
        memoryCache[infoHash]?.let { return it }
        val dir = cacheDir ?: return null
        val file = File(dir, "$infoHash.resume")
        if (!file.exists()) return null
        return try {
            ObjectInputStream(FileInputStream(file)).use { ois ->
                val data = ois.readObject() as? FastResumeData
                if (data != null) {
                    memoryCache[infoHash] = data
                }
                data
            }
        } catch (e: Exception) {
            Lg.w(LogCategory.STORAGE, "Failed reading fastresume for $infoHash: ${e.message}")
            null
        }
    }

    /**
     * Removes fast-resume cache (used on Force Recheck).
     */
    fun remove(infoHash: String) {
        memoryCache.remove(infoHash)
        val dir = cacheDir ?: return
        val file = File(dir, "$infoHash.resume")
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Clears all in-memory and disk cache.
     */
    fun clearAll() {
        memoryCache.clear()
        cacheDir?.listFiles()?.forEach { it.delete() }
    }
}
