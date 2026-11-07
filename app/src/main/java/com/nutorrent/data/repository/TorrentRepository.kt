package com.nutorrent.data.repository

import android.content.Context
import com.nutorrent.core.model.TorrentFile
import com.nutorrent.core.model.TorrentId
import com.nutorrent.core.model.TorrentMetadata
import com.nutorrent.torrent.metainfo.MagnetUri
import com.nutorrent.core.model.TorrentProgress
import com.nutorrent.core.model.TorrentState
import com.nutorrent.core.model.AppError
import com.nutorrent.core.model.PauseReason
import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import com.nutorrent.data.database.NuTorrentDatabase
import com.nutorrent.data.database.TorrentEntity
import com.nutorrent.data.database.TorrentFileEntity
import com.nutorrent.data.database.TrackerEntity
import com.nutorrent.data.storage.FileTorrentStorage
import com.nutorrent.torrent.download.TorrentSession
import com.nutorrent.torrent.engine.TorrentEngine
import com.nutorrent.torrent.metainfo.MetainfoParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TorrentSummaryItem(
    val torrentId: TorrentId,
    val name: String,
    val totalLength: Long,
    val state: TorrentState,
    val progress: TorrentProgress,
    val destinationPath: String,
    val addedDate: Long,
    val queuePosition: Int = -1,
    val isMagnet: Boolean = false,
    val uploadedBytes: Long = 0L,
    val sequentialDownload: Boolean = false,
    val category: String = "",
    val error: AppError? = null,
    val pauseReason: PauseReason = PauseReason.NONE
)

class TorrentRepository(
    private val context: Context,
    val engine: TorrentEngine,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository? = null
) {
    private val db = NuTorrentDatabase.getInstance(context)
    private val dao = db.torrentDao()

    private val _torrentsList = MutableStateFlow<List<TorrentSummaryItem>>(emptyList())
    val torrentsList: StateFlow<List<TorrentSummaryItem>> = _torrentsList.asStateFlow()

    init {
        scope.launch {
            loadTorrentsFromDatabase()
            observeTorrents()
        }
    }

    private fun tickerFlow(periodMillis: Long = 1000L): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(periodMillis)
        }
    }

    private suspend fun observeTorrents() {
        combine(
            dao.getAllTorrentsFlow(),
            engine.activeSessionsFlow,
            engine.metadataFetchersFlow,
            tickerFlow(1000L)
        ) { entities, sessions, fetchers, _ ->
            entities.map { entity ->
                val torrentId = TorrentId.fromHex(entity.infoHash)
                val session = sessions.firstOrNull { it.metadata.torrentId == torrentId }
                val fetcher = fetchers[torrentId]

                val state: TorrentState
                val progress: TorrentProgress
                val err: AppError?
                val pauseReason: PauseReason

                if (session != null) {
                    val sessState = session.state.value
                    if (sessState == TorrentState.SEEDING && entity.state != TorrentState.SEEDING.name) {
                        scope.launch(Dispatchers.IO) {
                            dao.updateState(entity.infoHash, TorrentState.SEEDING.name)
                        }
                    }
                    state = sessState
                    progress = session.progress.value
                    err = session.lastError.value
                    pauseReason = session.pauseReason.value
                } else if (fetcher != null) {
                    state = TorrentState.FETCHING_METADATA
                    progress = TorrentProgress(
                        totalBytes = entity.totalLength,
                        totalPieces = entity.piecesCount,
                        connectedPeers = fetcher.connectedPeersCount,
                        totalPeers = fetcher.totalDiscoveredCount
                    )
                    err = null
                    pauseReason = PauseReason.NONE
                } else {
                    state = try {
                        TorrentState.valueOf(entity.state)
                    } catch (e: Exception) {
                        TorrentState.PAUSED
                    }
                    progress = TorrentProgress(
                        totalBytes = entity.totalLength,
                        totalPieces = entity.piecesCount,
                        verifiedBytes = if (state == TorrentState.COMPLETED) entity.totalLength else 0L
                    )
                    err = entity.lastError?.let { AppError.UnexpectedError(it) }
                    pauseReason = PauseReason.NONE
                }

                TorrentSummaryItem(
                    torrentId = torrentId,
                    name = entity.name,
                    totalLength = entity.totalLength,
                    state = state,
                    progress = progress,
                    destinationPath = entity.destinationPath,
                    addedDate = entity.addedDate,
                    queuePosition = engine.getQueuePosition(torrentId),
                    isMagnet = entity.isMagnet,
                    uploadedBytes = if (session != null) session.progress.value.uploadedBytes else entity.uploadedBytes,
                    sequentialDownload = session?.isSequentialDownload ?: entity.sequentialDownload,
                    category = entity.category,
                    error = err,
                    pauseReason = pauseReason
                )
            }
        }.collect { items ->
            _torrentsList.value = items
        }
    }

    suspend fun loadTorrentsFromDatabase() = withContext(Dispatchers.IO) {
        val entities = dao.getAllTorrents()
        Lg.d(LogCategory.STORAGE, "Restoring ${entities.size} torrents from nutorrent.db")

        for (entity in entities) {
            try {
                if (entity.isMagnet && entity.piecesCount == 0) {
                    val torrentId = TorrentId.fromHex(entity.infoHash)
                    val trackers = dao.getTrackersForTorrent(entity.infoHash).map { it.url }
                    if (entity.state == TorrentState.FETCHING_METADATA.name || entity.state == TorrentState.DOWNLOADING.name) {
                        startMagnetMetadataFetch(torrentId, entity.name, trackers, entity.destinationPath, autoStart = true)
                    }
                    continue
                }

                val metadata = MetainfoParser.parse(entity.rawTorrentBytes)
                val storage = com.nutorrent.data.storage.TorrentStorageFactory.create(
                    context = context,
                    destinationPath = entity.destinationPath,
                    metadata = metadata,
                    onDestinationChanged = { newPath ->
                        scope.launch(Dispatchers.IO) {
                            dao.updateDestinationPath(entity.infoHash, newPath)
                        }
                    }
                )
                val session = engine.createSession(metadata, storage)
                session.isSequentialDownload = entity.sequentialDownload

                if (entity.state == TorrentState.DOWNLOADING.name || entity.state == TorrentState.DISCOVERING_PEERS.name) {
                    engine.startOrQueue(metadata.torrentId)
                }
            } catch (e: Exception) {
                Lg.e(LogCategory.STORAGE, "Failed restoring torrent ${entity.name}", e)
            }
        }
    }

    suspend fun addTorrent(
        metadata: TorrentMetadata,
        rawBytes: ByteArray,
        destinationPath: String,
        autoStart: Boolean = true,
        selectedFileIndices: Set<Int>? = null
    ): TorrentSession = withContext(Dispatchers.IO) {
        val resolvedDestPath = if (destinationPath.isNotBlank()) {
            destinationPath
        } else {
            SettingsRepository.getDefaultDownloadDir(context)
        }
        if (!resolvedDestPath.startsWith("content://", ignoreCase = true)) {
            val dir = File(resolvedDestPath)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }

        val infoHex = metadata.torrentId.hex

        val entity = TorrentEntity(
            infoHash = infoHex,
            name = metadata.name,
            totalLength = metadata.totalLength,
            pieceLength = metadata.pieceLength,
            piecesCount = metadata.piecesCount,
            rawTorrentBytes = rawBytes,
            destinationPath = resolvedDestPath,
            state = if (autoStart) TorrentState.DOWNLOADING.name else TorrentState.PAUSED.name
        )
        dao.insertTorrent(entity)

        val fileEntities = metadata.files.map { f ->
            TorrentFileEntity(
                torrentInfoHash = infoHex,
                fileIndex = f.index,
                relativePath = f.relativePath,
                length = f.length
            )
        }
        dao.insertFiles(fileEntities)

        val trackerEntities = metadata.announceList.mapIndexed { idx, url ->
            TrackerEntity(
                torrentInfoHash = infoHex,
                url = url,
                tier = idx
            )
        }
        dao.insertTrackers(trackerEntities)

        val storage = com.nutorrent.data.storage.TorrentStorageFactory.create(
            context = context,
            destinationPath = resolvedDestPath,
            metadata = metadata,
            selectedFileIndices = selectedFileIndices,
            onDestinationChanged = { newPath ->
                scope.launch(Dispatchers.IO) {
                    dao.updateDestinationPath(infoHex, newPath)
                }
            }
        )

        val session = engine.createSession(metadata, storage)

        if (autoStart) {
            engine.startOrQueue(metadata.torrentId)
        }

        session
    }

    suspend fun addMagnetTorrent(
        magnet: MagnetUri,
        destinationPath: String,
        autoStart: Boolean = true,
        selectedFileIndices: Set<Int>? = null
    ): Unit = withContext(Dispatchers.IO) {
        val infoHex = magnet.torrentId.hex
        val resolvedDestPath = if (destinationPath.isNotBlank()) {
            destinationPath
        } else {
            SettingsRepository.getDefaultDownloadDir(context)
        }
        if (!resolvedDestPath.startsWith("content://", ignoreCase = true)) {
            val dir = File(resolvedDestPath)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }

        val entity = TorrentEntity(
            infoHash = infoHex,
            name = magnet.displayName ?: magnet.torrentId.hex,
            totalLength = magnet.exactLength ?: 0L,
            pieceLength = 0L,
            piecesCount = 0,
            rawTorrentBytes = magnet.rawUri.toByteArray(Charsets.UTF_8),
            destinationPath = resolvedDestPath,
            state = if (autoStart) TorrentState.FETCHING_METADATA.name else TorrentState.PAUSED.name,
            isMagnet = true
        )
        dao.insertTorrent(entity)

        val trackerEntities = magnet.trackers.mapIndexed { idx, url ->
            TrackerEntity(
                torrentInfoHash = infoHex,
                url = url,
                tier = idx
            )
        }
        if (trackerEntities.isNotEmpty()) {
            dao.insertTrackers(trackerEntities)
        }

        if (autoStart) {
            startMagnetMetadataFetch(
                torrentId = magnet.torrentId,
                displayName = magnet.displayName,
                trackers = magnet.trackers,
                destinationPath = resolvedDestPath,
                autoStart = true,
                peerHints = magnet.peerHints,
                exactSources = magnet.exactSources,
                selectedFileIndices = selectedFileIndices
            )
        }
    }

    private fun startMagnetMetadataFetch(
        torrentId: TorrentId,
        displayName: String?,
        trackers: List<String>,
        destinationPath: String,
        autoStart: Boolean,
        peerHints: List<String> = emptyList(),
        exactSources: List<String> = emptyList(),
        selectedFileIndices: Set<Int>? = null
    ) {
        engine.startMetadataFetch(
            torrentId = torrentId,
            trackers = trackers,
            peerHints = peerHints,
            exactSources = exactSources,
            onResolved = { metadata, fullTorrentBytes, discoveredPeers ->
                val infoHex = torrentId.hex
                dao.updateMetadataForMagnet(
                    infoHash = infoHex,
                    name = metadata.name,
                    isMagnet = false,
                    totalLength = metadata.totalLength,
                    pieceLength = metadata.pieceLength,
                    piecesCount = metadata.piecesCount,
                    rawTorrentBytes = fullTorrentBytes,
                    state = if (autoStart) TorrentState.DOWNLOADING.name else TorrentState.PAUSED.name
                )

                val fileEntities = metadata.files.map { f ->
                    TorrentFileEntity(
                        torrentInfoHash = infoHex,
                        fileIndex = f.index,
                        relativePath = f.relativePath,
                        length = f.length
                    )
                }
                dao.insertFiles(fileEntities)

                val trackerEntities = metadata.announceList.mapIndexed { idx, url ->
                    TrackerEntity(
                        torrentInfoHash = infoHex,
                        url = url,
                        tier = idx
                    )
                }
                if (trackerEntities.isNotEmpty()) {
                    dao.insertTrackers(trackerEntities)
                }

                val storage = com.nutorrent.data.storage.TorrentStorageFactory.create(
                    context = context,
                    destinationPath = destinationPath,
                    metadata = metadata,
                    selectedFileIndices = selectedFileIndices,
                    onDestinationChanged = { newPath ->
                        scope.launch(Dispatchers.IO) {
                            dao.updateDestinationPath(infoHex, newPath)
                        }
                    }
                )
        
                val session = engine.createSession(metadata, storage, initialPeers = discoveredPeers)

                if (autoStart) {
                    engine.startOrQueue(metadata.torrentId)
                }
            }
        )
    }

    suspend fun pauseTorrent(torrentId: TorrentId) = withContext(Dispatchers.IO) {
        dao.updateState(torrentId.hex, TorrentState.PAUSED.name)
        engine.cancelMetadataFetch(torrentId)
        engine.pauseSession(torrentId)
    }

    suspend fun resumeTorrent(torrentId: TorrentId) = withContext(Dispatchers.IO) {
        val entity = dao.getTorrent(torrentId.hex)
        if (entity != null && entity.isMagnet && entity.piecesCount == 0) {
            dao.updateState(torrentId.hex, TorrentState.FETCHING_METADATA.name)
            val trackers = dao.getTrackersForTorrent(entity.infoHash).map { it.url }
            startMagnetMetadataFetch(torrentId, entity.name, trackers, entity.destinationPath, autoStart = true)
            return@withContext
        }
        if (entity != null && !SettingsRepository.isPathWritable(entity.destinationPath, context)) {
            val safeDir = SettingsRepository.getDefaultDownloadDir(context)
            Lg.w(LogCategory.STORAGE, "Auto-healing destination path for '${entity.name}' from '${entity.destinationPath}' to '$safeDir'")
            dao.updateDestinationPath(torrentId.hex, safeDir)
            updateTorrentDestination(torrentId, safeDir)
        }
        dao.updateState(torrentId.hex, TorrentState.DOWNLOADING.name)
        engine.resumeSession(torrentId)
    }

    suspend fun deleteTorrent(torrentId: TorrentId, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        engine.cancelMetadataFetch(torrentId)
        val entity = dao.getTorrent(torrentId.hex)
        engine.removeSession(torrentId)
        dao.deleteTorrent(torrentId.hex)

        if (deleteFiles && entity != null) {
            try {
                val dir = File(entity.destinationPath)
                // If single file
                val targetFile = File(dir, entity.name)
                if (targetFile.exists()) {
                    targetFile.deleteRecursively()
                }
            } catch (e: Exception) {
                Lg.w(LogCategory.STORAGE, "Failed to delete files for torrent ${torrentId.hex}", e)
            }
        }
    }

    fun moveUpInQueue(torrentId: TorrentId) = engine.moveUpInQueue(torrentId)
    fun moveDownInQueue(torrentId: TorrentId) = engine.moveDownInQueue(torrentId)
    fun getQueuePosition(torrentId: TorrentId): Int = engine.getQueuePosition(torrentId)

    suspend fun setSequentialDownload(torrentId: TorrentId, sequential: Boolean) = withContext(Dispatchers.IO) {
        engine.getSession(torrentId)?.isSequentialDownload = sequential
        dao.updateSequentialDownload(torrentId.hex, sequential)
    }

    suspend fun forceRecheck(torrentId: TorrentId) = withContext(Dispatchers.IO) {
        dao.updateState(torrentId.hex, com.nutorrent.core.model.TorrentState.VERIFYING.name)
        engine.forceRecheck(torrentId)?.join()
    }

    suspend fun setCategory(torrentId: TorrentId, category: String) = withContext(Dispatchers.IO) {
        dao.updateCategory(torrentId.hex, category)
    }

    fun getSession(torrentId: TorrentId): TorrentSession? = engine.getSession(torrentId)

    fun getFilesForTorrentFlow(infoHash: String): Flow<List<TorrentFileEntity>> = dao.getFilesForTorrentFlow(infoHash)

    suspend fun updateTorrentDestination(torrentId: TorrentId, newDestination: String) = withContext(Dispatchers.IO) {
        dao.updateDestinationPath(torrentId.hex, newDestination)
        val session = engine.getSession(torrentId)
        if (session != null) {
            val storage = com.nutorrent.data.storage.TorrentStorageFactory.create(
                context = context,
                destinationPath = newDestination,
                metadata = session.metadata,
                selectedFileIndices = session.storage.selectedFileIndices,
                onDestinationChanged = { newPath ->
                    scope.launch(Dispatchers.IO) {
                        dao.updateDestinationPath(torrentId.hex, newPath)
                    }
                }
            )
            session.storage = storage
            session.uploadManager.storage = storage
        }
    }
}
