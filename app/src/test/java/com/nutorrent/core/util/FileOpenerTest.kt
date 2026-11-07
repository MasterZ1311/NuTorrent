package com.nutorrent.core.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FileOpenerTest {

    @Test
    fun `test file_paths xml does not expose root external storage`() {
        val filePathsXml = File("src/main/res/xml/file_paths.xml").let {
            if (it.exists()) it else File("app/src/main/res/xml/file_paths.xml")
        }
        assertTrue("file_paths.xml must exist", filePathsXml.exists())

        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(filePathsXml)
        val externalPaths = doc.getElementsByTagName("external-path")

        for (i in 0 until externalPaths.length) {
            val node = externalPaths.item(i)
            val pathAttr = node.attributes?.getNamedItem("path")?.nodeValue
            assertNotEquals(
                "FileProvider must not expose root external storage (path='.')",
                ".",
                pathAttr
            )
        }
    }

    @Test
    fun `test mime type mappings`() {
        assertEquals("video/x-matroska", FileOpener.getMimeType("movie.mkv"))
        assertEquals("video/mp4", FileOpener.getMimeType("video.mp4"))
        assertEquals("audio/flac", FileOpener.getMimeType("album.flac"))
        assertEquals("application/pdf", FileOpener.getMimeType("doc.pdf"))
        assertEquals("application/x-iso9660-image", FileOpener.getMimeType("linux.iso"))
        assertEquals("*/*", FileOpener.getMimeType("unknown.xyz123"))
    }

    @Test
    fun `test path traversal detection logic`() {
        val baseDir = File("C:/test/download").canonicalFile
        val safeFile = File(baseDir, "subfolder/movie.mkv").canonicalFile
        val basePath = baseDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }

        assertTrue(safeFile.path.startsWith(basePath))

        val traversalFile = File(baseDir, "../../windows/system32").canonicalFile
        assertFalse(traversalFile.path.startsWith(basePath))
    }
}
