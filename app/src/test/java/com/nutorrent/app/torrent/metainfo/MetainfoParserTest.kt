package com.nutorrent.app.torrent.metainfo

import com.nutorrent.app.core.model.AppError
import com.nutorrent.app.torrent.bencode.BencodeElement
import com.nutorrent.app.torrent.bencode.BencodeEncoder
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class MetainfoParserTest {

    @Test
    fun `test single file torrent parsing and info hash`() {
        // Construct a synthetic single-file torrent
        val fakeHash1 = ByteArray(20) { 1 }
        val fakeHash2 = ByteArray(20) { 2 }
        val piecesBytes = fakeHash1 + fakeHash2

        val infoMap = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(32768),
            "name" to BencodeElement.BString("sample.iso".toByteArray()),
            "piece length" to BencodeElement.BInteger(16384),
            "pieces" to BencodeElement.BString(piecesBytes)
        )
        val infoDict = BencodeElement.BDictionary(infoMap)

        val rootMap = linkedMapOf<String, BencodeElement>(
            "announce" to BencodeElement.BString("http://tracker.example.com/announce".toByteArray()),
            "info" to infoDict
        )
        val torrentBytes = BencodeEncoder.encode(BencodeElement.BDictionary(rootMap))

        val meta = MetainfoParser.parse(torrentBytes)
        assertEquals("sample.iso", meta.name)
        assertEquals(32768L, meta.totalLength)
        assertEquals(16384L, meta.pieceLength)
        assertEquals(2, meta.piecesCount)
        assertTrue(meta.isSingleFile)
        assertEquals(1, meta.files.size)
        assertEquals("sample.iso", meta.files[0].relativePath)
        assertEquals("http://tracker.example.com/announce", meta.announceList.first())

        // Verify SHA-1 of info dictionary matches expected
        val expectedSha1 = MessageDigest.getInstance("SHA-1").digest(BencodeEncoder.encode(infoDict))
        assertArrayEquals(expectedSha1, meta.torrentId.bytes)
    }

    @Test
    fun `test multi-file torrent parsing`() {
        val fakeHash1 = ByteArray(20) { 1 }
        val fakeHash2 = ByteArray(20) { 2 }
        val piecesBytes = fakeHash1 + fakeHash2

        val file1Map = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(10000),
            "path" to BencodeElement.BList(listOf(BencodeElement.BString("doc".toByteArray()), BencodeElement.BString("readme.txt".toByteArray())))
        )
        val file2Map = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(15000),
            "path" to BencodeElement.BList(listOf(BencodeElement.BString("data.bin".toByteArray())))
        )

        val infoMap = linkedMapOf<String, BencodeElement>(
            "files" to BencodeElement.BList(listOf(BencodeElement.BDictionary(file1Map), BencodeElement.BDictionary(file2Map))),
            "name" to BencodeElement.BString("bundle".toByteArray()),
            "piece length" to BencodeElement.BInteger(16384),
            "pieces" to BencodeElement.BString(piecesBytes)
        )
        val rootMap = linkedMapOf<String, BencodeElement>(
            "announce" to BencodeElement.BString("https://tracker.org/announce".toByteArray()),
            "info" to BencodeElement.BDictionary(infoMap)
        )
        val torrentBytes = BencodeEncoder.encode(BencodeElement.BDictionary(rootMap))

        val meta = MetainfoParser.parse(torrentBytes)
        assertFalse(meta.isSingleFile)
        assertEquals(25000L, meta.totalLength)
        assertEquals(2, meta.files.size)

        // Check file 1 offsets
        assertEquals(0L, meta.files[0].startByte)
        assertEquals(10000L, meta.files[0].endByte)
        assertTrue(meta.files[0].relativePath.contains("readme.txt"))

        // Check file 2 offsets
        assertEquals(10000L, meta.files[1].startByte)
        assertEquals(25000L, meta.files[1].endByte)
        assertTrue(meta.files[1].relativePath.contains("data.bin"))
    }

    @Test
    fun `test path traversal sanitization`() {
        val clean1 = MetainfoParser.sanitizePathComponent("../")
        assertEquals("", clean1)

        val clean2 = MetainfoParser.sanitizePathComponent("..")
        assertEquals("", clean2)

        val clean3 = MetainfoParser.sanitizePathComponent("../../etc/passwd")
        assertFalse(clean3.contains("/"))
        assertFalse(clean3.contains(".."))
    }

    @Test
    fun `test piece count mismatch rejected`() {
        val fakeHash1 = ByteArray(20) { 1 } // only 1 hash, but total length requires 2 pieces
        val infoMap = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(32768),
            "name" to BencodeElement.BString("bad.iso".toByteArray()),
            "piece length" to BencodeElement.BInteger(16384),
            "pieces" to BencodeElement.BString(fakeHash1)
        )
        val rootMap = linkedMapOf<String, BencodeElement>(
            "announce" to BencodeElement.BString("http://tracker.example.com/announce".toByteArray()),
            "info" to BencodeElement.BDictionary(infoMap)
        )
        val torrentBytes = BencodeEncoder.encode(BencodeElement.BDictionary(rootMap))

        assertThrows(AppError.InvalidTorrent::class.java) {
            MetainfoParser.parse(torrentBytes)
        }
    }

    @Test
    fun `test parseFromInfoBytes and createTorrentBytesFromInfoDict`() {
        val fakeHash1 = ByteArray(20) { 1 }
        val fakeHash2 = ByteArray(20) { 2 }
        val piecesBytes = fakeHash1 + fakeHash2

        val infoMap = linkedMapOf<String, BencodeElement>(
            "length" to BencodeElement.BInteger(32768),
            "name" to BencodeElement.BString("sample.iso".toByteArray()),
            "piece length" to BencodeElement.BInteger(16384),
            "pieces" to BencodeElement.BString(piecesBytes)
        )
        val infoDict = BencodeElement.BDictionary(infoMap)
        val infoBytes = BencodeEncoder.encode(infoDict)

        val trackers = listOf("udp://tracker.opentrackr.org:1337/announce")
        val meta = MetainfoParser.parseFromInfoBytes(infoBytes, trackers)
        assertEquals("sample.iso", meta.name)
        assertEquals(32768L, meta.totalLength)
        assertEquals(2, meta.piecesCount)
        assertEquals(trackers, meta.announceList)

        // Verify createTorrentBytesFromInfoDict creates a full valid torrent
        val fullTorrentBytes = MetainfoParser.createTorrentBytesFromInfoDict(infoBytes, trackers)
        val parsedFull = MetainfoParser.parse(fullTorrentBytes)
        assertEquals(meta.torrentId, parsedFull.torrentId)
        assertEquals(meta.name, parsedFull.name)
        assertEquals(meta.totalLength, parsedFull.totalLength)
        assertEquals(trackers.first(), parsedFull.announceList.first())
    }
}
