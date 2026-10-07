package com.nutorrent.app.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "torrents")
data class TorrentEntity(
    @PrimaryKey val infoHash: String,
    val name: String,
    val totalLength: Long,
    val pieceLength: Long,
    val piecesCount: Int,
    val rawTorrentBytes: ByteArray,
    val destinationPath: String,
    val state: String,
    val addedDate: Long = System.currentTimeMillis(),
    val isMagnet: Boolean = false,
    val uploadedBytes: Long = 0L,
    val priority: Int = 0,
    val selectedFiles: String = "",
    val sequentialDownload: Boolean = false,
    val category: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TorrentEntity) return false
        return infoHash == other.infoHash
    }

    override fun hashCode(): Int = infoHash.hashCode()
}

@Entity(
    tableName = "torrent_files",
    foreignKeys = [
        ForeignKey(
            entity = TorrentEntity::class,
            parentColumns = ["infoHash"],
            childColumns = ["torrentInfoHash"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("torrentInfoHash")]
)
data class TorrentFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val torrentInfoHash: String,
    val fileIndex: Int,
    val relativePath: String,
    val length: Long
)

@Entity(
    tableName = "trackers",
    foreignKeys = [
        ForeignKey(
            entity = TorrentEntity::class,
            parentColumns = ["infoHash"],
            childColumns = ["torrentInfoHash"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("torrentInfoHash")]
)
data class TrackerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val torrentInfoHash: String,
    val url: String,
    val tier: Int = 0
)
