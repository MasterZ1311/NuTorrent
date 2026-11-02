package com.nutorrent.core.model

/**
 * Represents an individual file within a torrent.
 * For single-file torrents, there is one file whose length matches the torrent's total length.
 * For multi-file torrents, each file occupies a contiguous slice of the global torrent byte space [startByte, endByte).
 */
data class TorrentFile(
    val index: Int,
    val relativePath: String,
    val length: Long,
    val startByte: Long,
    val endByte: Long
) {
    init {
        require(index >= 0) { "File index cannot be negative" }
        require(length >= 0) { "File length cannot be negative" }
        require(startByte >= 0) { "Start byte cannot be negative" }
        require(endByte >= startByte) { "End byte cannot be before start byte" }
        require(endByte == startByte + length) { "endByte must equal startByte + length" }
    }

    /**
     * Returns true if this file overlaps with the global byte range [rangeStart, rangeEnd).
     */
    fun overlaps(rangeStart: Long, rangeEnd: Long): Boolean {
        return maxOf(startByte, rangeStart) < minOf(endByte, rangeEnd)
    }

    /**
     * Given a global byte range [rangeStart, rangeEnd), returns the portion of that range
     * that intersects this file, as a Pair of:
     * - fileOffset: byte offset inside this file
     * - byteCount: number of bytes in this file
     * Returns null if there is no intersection.
     */
    fun intersection(rangeStart: Long, rangeEnd: Long): FileSlice? {
        val interStart = maxOf(startByte, rangeStart)
        val interEnd = minOf(endByte, rangeEnd)
        if (interStart >= interEnd) return null

        val fileOffset = interStart - startByte
        val byteCount = interEnd - interStart
        val bufferOffset = interStart - rangeStart

        return FileSlice(
            file = this,
            fileOffset = fileOffset,
            bufferOffset = bufferOffset.toInt(),
            byteCount = byteCount.toInt()
        )
    }
}

data class FileSlice(
    val file: TorrentFile,
    val fileOffset: Long,
    val bufferOffset: Int,
    val byteCount: Int
)
