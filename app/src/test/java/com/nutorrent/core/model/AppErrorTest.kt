package com.nutorrent.core.model

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AppErrorTest {

    @Test
    fun `test all storage error instances have clear titles and user messages`() {
        val storageErrors = listOf(
            AppError.InsufficientStorage(requiredBytes = 5_000_000_000L, availableBytes = 2_000_000_000L),
            AppError.InsufficientStorage(requiredBytes = 1_000_000L, availableBytes = null),
            AppError.DestinationPermissionRevoked("/storage/emulated/0/Download"),
            AppError.DestinationUnavailable("content://tree/primary:Download"),
            AppError.FolderDeletedOrMoved("/sdcard/Download"),
            AppError.FileCreationFailed("test.mp4", "Read-only filesystem"),
            AppError.FileWriteFailed("test.mp4", "Disk full"),
            AppError.FileReadFailed("test.mp4", "I/O error"),
            AppError.StorageProviderUnavailable("SAF provider crashed"),
            AppError.InvalidDestination("null path", "Directory does not exist"),
            AppError.FileAlreadyExists("movie.mkv"),
            AppError.DestinationNotWritable("/system/bin"),
            AppError.InaccessibleFile("sample.iso", "File is locked")
        )

        for (err in storageErrors) {
            assertTrue("Title should not be blank for ${err::class.simpleName}", err.title.isNotBlank())
            assertTrue("Message should not be blank for ${err::class.simpleName}", err.userMessage.isNotBlank())
            assertEquals(ErrorCategory.STORAGE, err.category)
            assertNotNull("Action should not be null for ${err::class.simpleName}", err.action)
        }
    }

    @Test
    fun `test network error instances provide actionable guidance`() {
        val dummyEndpoint = PeerEndpoint("192.168.1.50", 6881)
        val networkErrors = listOf(
            AppError.NoInternetConnection(),
            AppError.NetworkDisconnected("Network changed"),
            AppError.DnsResolutionFailure("tracker.example.com"),
            AppError.TrackerTimeout("http://tracker.example.com/announce"),
            AppError.TrackerError("http://tracker.example.com", "Torrent not registered with this tracker"),
            AppError.NoPeersAvailable(),
            AppError.PeerConnectionTimeout(dummyEndpoint),
            AppError.PeerDisconnected(dummyEndpoint, "Connection reset"),
            AppError.InvalidPeerResponse(dummyEndpoint, "Bad handshake"),
            AppError.AllPeersChoking(),
            AppError.DhtUnavailable(),
            AppError.MagnetMetadataUnavailable(),
            AppError.RequestTimeout(pieceIndex = 1)
        )

        for (err in networkErrors) {
            assertTrue("Title should not be blank for ${err::class.simpleName}", err.title.isNotBlank())
            assertTrue("Message should not be blank for ${err::class.simpleName}", err.userMessage.isNotBlank())
            assertEquals(ErrorCategory.NETWORK, err.category)
            assertNotNull("Action should not be null for ${err::class.simpleName}", err.action)
        }
    }

    @Test
    fun `test integrity error instances correctly identify corruption and formats`() {
        val integrityErrors = listOf(
            AppError.InvalidTorrent("Malformed Bencode"),
            AppError.InvalidMagnetUri("magnet:?invalid"),
            AppError.InvalidMetadata("Invalid info dictionary"),
            AppError.InfoHashMismatch("expected_hash", "actual_hash"),
            AppError.CorruptPiece(pieceIndex = 42),
            AppError.PieceVerificationFailed(pieceIndex = 12),
            AppError.UnsupportedTorrent("V2-only hybrid torrent"),
            AppError.InvalidBlockResponse(pieceIndex = 0, offset = 16384),
            AppError.TorrentAlreadyExists("abc123hash")
        )

        for (err in integrityErrors) {
            assertTrue("Title should not be blank for ${err::class.simpleName}", err.title.isNotBlank())
            assertTrue("Message should not be blank for ${err::class.simpleName}", err.userMessage.isNotBlank())
            assertEquals(ErrorCategory.INTEGRITY, err.category)
            assertNotNull(err.action)
        }
    }

    @Test
    fun `test android and lifecycle errors`() {
        val androidErrors = listOf(
            AppError.NotificationPermissionUnavailable(),
            AppError.ForegroundServiceStartFailure("Service crash"),
            AppError.BackgroundTransferInterrupted("Low memory"),
            AppError.ProcessRestarted(),
            AppError.PersistedPermissionLost("content://tree/primary"),
            AppError.DatabaseError("SQLite constraint error"),
            AppError.TransferCancelled(),
            AppError.UnexpectedError("Null pointer")
        )

        for (err in androidErrors) {
            assertTrue("Title should not be blank for ${err::class.simpleName}", err.title.isNotBlank())
            assertTrue("Message should not be blank for ${err::class.simpleName}", err.userMessage.isNotBlank())
            assertTrue(err.category == ErrorCategory.ANDROID || err.category == ErrorCategory.UNKNOWN)
        }
    }

    @Test
    fun `test fromThrowable maps ENOSPC to InsufficientStorage`() {
        val ex1 = IOException("ENOSPC: No space left on device")
        val mapped1 = AppError.fromThrowable(ex1)
        assertTrue(mapped1 is AppError.InsufficientStorage)
        assertEquals(ErrorAction.CHOOSE_LOCATION, mapped1.action)

        val ex2 = IOException("There is not enough space on the disk")
        val mapped2 = AppError.fromThrowable(ex2)
        assertTrue(mapped2 is AppError.InsufficientStorage)
    }

    @Test
    fun `test fromThrowable maps SecurityException to DestinationPermissionRevoked`() {
        val secEx = SecurityException("Permission Denial: reading com.android.externalstorage")
        val mapped = AppError.fromThrowable(secEx)
        assertTrue(mapped is AppError.DestinationPermissionRevoked)
        assertEquals(ErrorAction.CHOOSE_LOCATION, mapped.action)
    }

    @Test
    fun `test fromThrowable maps UnknownHostException to DnsResolutionFailure`() {
        val dnsEx = UnknownHostException("tracker.opentrackr.org")
        val mapped = AppError.fromThrowable(dnsEx)
        assertTrue(mapped is AppError.DnsResolutionFailure)
        assertEquals(ErrorAction.RETRY, mapped.action)
    }

    @Test
    fun `test fromThrowable maps SocketTimeoutException to TrackerTimeout`() {
        val timeoutEx = SocketTimeoutException("connect timed out")
        val mapped = AppError.fromThrowable(timeoutEx)
        assertTrue(mapped is AppError.TrackerTimeout)
        assertEquals(ErrorAction.REANNOUNCE, mapped.action)
    }

    @Test
    fun `test fromThrowable preserves existing AppError`() {
        val original = AppError.CorruptPiece(7)
        val mapped = AppError.fromThrowable(original)
        assertSame(original, mapped)
    }
}
