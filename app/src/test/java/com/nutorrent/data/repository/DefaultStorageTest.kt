package com.nutorrent.data.repository

import com.nutorrent.core.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFormatStoragePathWithNuTorrentPaths() {
        val rootPath = "/storage/emulated/0/NuTorrent"
        assertEquals("Internal Storage / NuTorrent", FormatUtils.formatStoragePath(rootPath))

        val appPath = "/storage/emulated/0/Android/data/com.nutorrent/files/NuTorrent"
        assertEquals("Internal Storage / NuTorrent (App Storage)", FormatUtils.formatStoragePath(appPath))

        val customSubpath = "/storage/emulated/0/Download/NuTorrent"
        assertEquals("Internal Storage / Download/NuTorrent", FormatUtils.formatStoragePath(customSubpath))

        val arbitraryNuTorrent = "D:/Storage/NuTorrent"
        assertEquals("Internal Storage / NuTorrent", FormatUtils.formatStoragePath(arbitraryNuTorrent))
    }

    @Test
    fun testGetDefaultDownloadDirCreatesNuTorrentFolder() {
        val fakeBase = tempFolder.newFolder("internal_storage")
        val nuFolder = File(fakeBase, "NuTorrent").apply {
            if (!exists()) {
                mkdirs()
            }
        }

        assertTrue(nuFolder.exists())
        assertTrue(nuFolder.isDirectory)
        assertEquals("NuTorrent", nuFolder.name)
        assertTrue(nuFolder.canWrite())
    }

    @Test
    fun testFormatStoragePathWithSafTree() {
        val safPrimary = "content://com.android.externalstorage.documents/tree/primary%3ANuTorrent"
        val formatted = FormatUtils.formatStoragePath(safPrimary)
        assertTrue(formatted.contains("NuTorrent"))
    }

    @Test
    fun testIsPathWritableValidation() {
        // Blank or null paths
        org.junit.Assert.assertFalse(SettingsRepository.isPathWritable(null))
        org.junit.Assert.assertFalse(SettingsRepository.isPathWritable(""))
        org.junit.Assert.assertFalse(SettingsRepository.isPathWritable("   "))

        // Valid writable temp folder
        val validFolder = tempFolder.newFolder("writable_dir")
        assertTrue(SettingsRepository.isPathWritable(validFolder.absolutePath))

        // A plain file should not be considered a writable directory
        val plainFile = File(validFolder, "sample.txt").apply { createNewFile() }
        org.junit.Assert.assertFalse(SettingsRepository.isPathWritable(plainFile.absolutePath))
    }
}
