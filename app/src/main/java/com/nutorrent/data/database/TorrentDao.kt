package com.nutorrent.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TorrentDao {

    @Query("SELECT * FROM torrents ORDER BY addedDate DESC")
    fun getAllTorrentsFlow(): Flow<List<TorrentEntity>>

    @Query("SELECT * FROM torrents ORDER BY addedDate DESC")
    suspend fun getAllTorrents(): List<TorrentEntity>

    @Query("SELECT * FROM torrents WHERE infoHash = :infoHash")
    suspend fun getTorrent(infoHash: String): TorrentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTorrent(torrent: TorrentEntity)

    @Query("UPDATE torrents SET state = :state WHERE infoHash = :infoHash")
    suspend fun updateState(infoHash: String, state: String)

    @Query("DELETE FROM torrents WHERE infoHash = :infoHash")
    suspend fun deleteTorrent(infoHash: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<TorrentFileEntity>)

    @Query("SELECT * FROM torrent_files WHERE torrentInfoHash = :infoHash ORDER BY fileIndex ASC")
    suspend fun getFilesForTorrent(infoHash: String): List<TorrentFileEntity>

    @Query("SELECT * FROM torrent_files WHERE torrentInfoHash = :infoHash ORDER BY fileIndex ASC")
    fun getFilesForTorrentFlow(infoHash: String): Flow<List<TorrentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackers(trackers: List<TrackerEntity>)

    @Query("SELECT * FROM trackers WHERE torrentInfoHash = :infoHash ORDER BY tier ASC")
    suspend fun getTrackersForTorrent(infoHash: String): List<TrackerEntity>

    @Query("UPDATE torrents SET uploadedBytes = :uploadedBytes WHERE infoHash = :infoHash")
    suspend fun updateUploadedBytes(infoHash: String, uploadedBytes: Long)

    @Query("UPDATE torrents SET priority = :priority WHERE infoHash = :infoHash")
    suspend fun updatePriority(infoHash: String, priority: Int)

    @Query("UPDATE torrents SET selectedFiles = :selectedFiles WHERE infoHash = :infoHash")
    suspend fun updateSelectedFiles(infoHash: String, selectedFiles: String)

    @Query("UPDATE torrents SET sequentialDownload = :sequential WHERE infoHash = :infoHash")
    suspend fun updateSequentialDownload(infoHash: String, sequential: Boolean)

    @Query("UPDATE torrents SET category = :category WHERE infoHash = :infoHash")
    suspend fun updateCategory(infoHash: String, category: String)

    @Query("UPDATE torrents SET destinationPath = :destinationPath WHERE infoHash = :infoHash")
    suspend fun updateDestinationPath(infoHash: String, destinationPath: String)

    @Query("UPDATE torrents SET lastError = :error WHERE infoHash = :infoHash")
    suspend fun updateLastError(infoHash: String, error: String?)

    @Query("UPDATE torrents SET name = :name, isMagnet = :isMagnet, totalLength = :totalLength, pieceLength = :pieceLength, piecesCount = :piecesCount, rawTorrentBytes = :rawTorrentBytes, state = :state WHERE infoHash = :infoHash")
    suspend fun updateMetadataForMagnet(
        infoHash: String,
        name: String,
        isMagnet: Boolean,
        totalLength: Long,
        pieceLength: Long,
        piecesCount: Int,
        rawTorrentBytes: ByteArray,
        state: String
    )

    @Query("UPDATE torrents SET completedPieces = :completedPieces, verifiedBytes = :verifiedBytes, isCompleted = :isCompleted WHERE infoHash = :infoHash")
    suspend fun updateProgressCache(infoHash: String, completedPieces: Int, verifiedBytes: Long, isCompleted: Boolean)

    @Query("UPDATE torrents SET state = :state, isCompleted = :isCompleted WHERE infoHash = :infoHash")
    suspend fun updateStateAndCompletion(infoHash: String, state: String, isCompleted: Boolean)
}
