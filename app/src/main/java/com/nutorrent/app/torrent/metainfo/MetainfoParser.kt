package com.nutorrent.app.torrent.metainfo

import com.nutorrent.app.core.model.AppError
import com.nutorrent.app.core.model.TorrentFile
import com.nutorrent.app.core.model.TorrentId
import com.nutorrent.app.core.model.TorrentMetadata
import com.nutorrent.app.core.util.Lg
import com.nutorrent.app.core.util.LogCategory
import com.nutorrent.app.torrent.bencode.BencodeDecoder
import com.nutorrent.app.torrent.bencode.BencodeElement
import com.nutorrent.app.torrent.bencode.BencodeEncoder
import com.nutorrent.app.torrent.bencode.BencodeException
import java.io.File
import java.security.MessageDigest

object MetainfoParser {

    /**
     * Parses raw .torrent bytes into a validated TorrentMetadata object.
     * Extracts exact SHA-1 info hash from the raw info dictionary byte slice.
     */
    fun parse(torrentBytes: ByteArray): TorrentMetadata {
        val rootElement: BencodeElement
        val infoRange: IntRange?

        try {
            val decoded = BencodeDecoder.decodeWithInfoRange(torrentBytes)
            rootElement = decoded.first
            infoRange = decoded.second
        } catch (e: BencodeException) {
            throw AppError.InvalidTorrent("Bencode decoding failed: ${e.message}", e)
        }

        if (rootElement !is BencodeElement.BDictionary) {
            throw AppError.InvalidTorrent("Root element is not a dictionary")
        }

        val infoDict = rootElement.getDict("info")
            ?: throw AppError.InvalidTorrent("Missing 'info' dictionary in torrent file")

        // 1. Calculate info hash
        val infoBytes: ByteArray = if (infoRange != null) {
            torrentBytes.sliceArray(infoRange)
        } else {
            // Fallback: canonical bencode encoding of info dictionary
            BencodeEncoder.encode(infoDict)
        }

        val sha1 = MessageDigest.getInstance("SHA-1")
        val infoHash = sha1.digest(infoBytes)
        val torrentId = TorrentId(infoHash)
        Lg.d(LogCategory.METAINFO, "Computed info hash: ${torrentId.hex}")

        // 2. Extract torrent name and sanitize
        val rawName = infoDict.getString("name")
            ?: throw AppError.InvalidTorrent("Missing 'name' in info dictionary")
        val sanitizedName = sanitizePathComponent(rawName)
        if (sanitizedName.isEmpty()) {
            throw AppError.InvalidTorrent("Torrent name '$rawName' is invalid or empty after sanitization")
        }

        // 3. Extract piece length
        val pieceLength = infoDict.getInteger("piece length")
            ?: throw AppError.InvalidTorrent("Missing 'piece length' in info dictionary")
        if (pieceLength <= 0) {
            throw AppError.InvalidTorrent("Invalid piece length: $pieceLength")
        }

        // 4. Extract pieces binary string
        val rawPieces = infoDict.getByteArray("pieces")
            ?: throw AppError.InvalidTorrent("Missing 'pieces' in info dictionary")
        if (rawPieces.size % 20 != 0) {
            throw AppError.InvalidTorrent("Pieces byte length (${rawPieces.size}) is not a multiple of 20")
        }
        val pieceHashesCount = rawPieces.size / 20
        val pieceHashes = ArrayList<ByteArray>(pieceHashesCount)
        for (i in 0 until pieceHashesCount) {
            val hash = ByteArray(20)
            System.arraycopy(rawPieces, i * 20, hash, 0, 20)
            pieceHashes.add(hash)
        }

        // 5. Parse files (single-file vs multi-file)
        val files = mutableListOf<TorrentFile>()
        val singleFileLength = infoDict.getInteger("length")

        if (singleFileLength != null) {
            // Single-file torrent
            if (singleFileLength < 0) {
                throw AppError.InvalidTorrent("Invalid single-file length: $singleFileLength")
            }
            files.add(
                TorrentFile(
                    index = 0,
                    relativePath = sanitizedName,
                    length = singleFileLength,
                    startByte = 0L,
                    endByte = singleFileLength
                )
            )
        } else {
            // Multi-file torrent
            val filesList = infoDict.getList("files")
                ?: throw AppError.InvalidTorrent("Neither 'length' nor 'files' found in info dictionary")

            var currentOffset = 0L
            for ((index, fileItem) in filesList.withIndex()) {
                if (fileItem !is BencodeElement.BDictionary) {
                    throw AppError.InvalidTorrent("File entry $index is not a dictionary")
                }
                val fileLen = fileItem.getInteger("length")
                    ?: throw AppError.InvalidTorrent("Missing 'length' in file entry $index")
                if (fileLen < 0) {
                    throw AppError.InvalidTorrent("Negative length in file entry $index: $fileLen")
                }

                val pathList = fileItem.getList("path")
                    ?: throw AppError.InvalidTorrent("Missing 'path' in file entry $index")
                if (pathList.isEmpty()) {
                    throw AppError.InvalidTorrent("Empty path list in file entry $index")
                }

                val pathSegments = mutableListOf<String>()
                // Root folder is sanitizedName
                pathSegments.add(sanitizedName)
                for (segmentItem in pathList) {
                    val segmentStr = (segmentItem as? BencodeElement.BString)?.asUtf8String
                        ?: throw AppError.InvalidTorrent("Path segment is not a string in file entry $index")
                    val sanitizedSegment = sanitizePathComponent(segmentStr)
                    if (sanitizedSegment.isEmpty()) {
                        throw AppError.InvalidTorrent("Invalid path segment '$segmentStr' in file entry $index")
                    }
                    pathSegments.add(sanitizedSegment)
                }

                val relativePath = pathSegments.joinToString(File.separator)

                files.add(
                    TorrentFile(
                        index = index,
                        relativePath = relativePath,
                        length = fileLen,
                        startByte = currentOffset,
                        endByte = currentOffset + fileLen
                    )
                )
                currentOffset += fileLen
            }
        }

        val totalLength = files.sumOf { it.length }

        // Validate pieces count against total length
        val expectedPiecesCount = if (totalLength == 0L) {
            0
        } else {
            ((totalLength + pieceLength - 1) / pieceLength).toInt()
        }

        if (expectedPiecesCount != pieceHashesCount) {
            throw AppError.InvalidTorrent(
                "Pieces count mismatch: total size $totalLength with piece length $pieceLength expects $expectedPiecesCount pieces, but found $pieceHashesCount hashes"
            )
        }

        // 6. Trackers (announce and announce-list)
        val announceList = mutableListOf<String>()
        val mainAnnounce = rootElement.getString("announce")
        if (!mainAnnounce.isNullOrBlank()) {
            announceList.add(mainAnnounce.trim())
        }

        val rawAnnounceList = rootElement.getList("announce-list")
        if (rawAnnounceList != null) {
            for (tier in rawAnnounceList) {
                if (tier is BencodeElement.BList) {
                    for (trackerItem in tier.value) {
                        val tr = (trackerItem as? BencodeElement.BString)?.asUtf8String?.trim()
                        if (!tr.isNullOrBlank() && !announceList.contains(tr)) {
                            announceList.add(tr)
                        }
                    }
                }
            }
        }

        val creationDate = rootElement.getInteger("creation date")
        val comment = rootElement.getString("comment")
        val createdBy = rootElement.getString("created by")
        val isPrivate = (infoDict.getInteger("private") ?: 0L) == 1L

        return TorrentMetadata(
            torrentId = torrentId,
            name = sanitizedName,
            pieceLength = pieceLength,
            totalLength = totalLength,
            piecesCount = pieceHashesCount,
            pieceHashes = pieceHashes,
            files = files,
            announceList = announceList,
            creationDate = creationDate,
            comment = comment,
            createdBy = createdBy,
            isPrivate = isPrivate
        )
    }

    /**
     * Sanitizes a path segment against directory traversal, absolute paths, and invalid file characters.
     * Prevents escaping destination directory.
     */
    fun sanitizePathComponent(component: String): String {
        // A single component cannot contain path separators or traversal
        val trimmed = component.trim().replace('\\', '/').trim('/')
        if (trimmed.isEmpty() || trimmed == "." || trimmed == ".." || trimmed.startsWith("../") || trimmed.contains("/../") || trimmed.endsWith("/..") || trimmed.contains("..")) {
            return ""
        }
        // Remove dangerous characters
        val clean = trimmed.replace(Regex("""[/\\:*?"<>|\x00-\x1F]"""), "_")
        if (clean.trim('_').isEmpty() || clean == ".." || clean == ".") {
            return ""
        }
        return clean
    }

    /**
     * Parses raw bencoded 'info' dictionary bytes (as transferred in BEP 9 ut_metadata)
     * directly into a TorrentMetadata instance.
     */
    fun parseFromInfoBytes(
        infoBytes: ByteArray,
        announceList: List<String> = emptyList()
    ): TorrentMetadata {
        val rootElement = try {
            BencodeDecoder.decode(infoBytes)
        } catch (e: BencodeException) {
            throw AppError.InvalidTorrent("Bencode decoding failed for info dictionary: ${e.message}", e)
        }

        if (rootElement !is BencodeElement.BDictionary) {
            throw AppError.InvalidTorrent("Info dictionary root element is not a dictionary")
        }

        val sha1 = MessageDigest.getInstance("SHA-1")
        val infoHash = sha1.digest(infoBytes)
        val torrentId = TorrentId(infoHash)

        val rawName = rootElement.getString("name") ?: torrentId.hex
        val sanitizedName = sanitizePathComponent(rawName).ifEmpty { torrentId.hex }

        val pieceLength = rootElement.getInteger("piece length")
            ?: throw AppError.InvalidTorrent("Missing 'piece length' in info dictionary")
        if (pieceLength <= 0) {
            throw AppError.InvalidTorrent("Invalid piece length: $pieceLength")
        }

        val rawPieces = rootElement.getByteArray("pieces")
            ?: throw AppError.InvalidTorrent("Missing 'pieces' in info dictionary")
        if (rawPieces.size % 20 != 0) {
            throw AppError.InvalidTorrent("Pieces byte length (${rawPieces.size}) is not a multiple of 20")
        }
        val pieceHashesCount = rawPieces.size / 20
        val pieceHashes = ArrayList<ByteArray>(pieceHashesCount)
        for (i in 0 until pieceHashesCount) {
            val hash = ByteArray(20)
            System.arraycopy(rawPieces, i * 20, hash, 0, 20)
            pieceHashes.add(hash)
        }

        val files = mutableListOf<TorrentFile>()
        val singleFileLength = rootElement.getInteger("length")

        if (singleFileLength != null) {
            if (singleFileLength < 0) {
                throw AppError.InvalidTorrent("Invalid single-file length: $singleFileLength")
            }
            files.add(
                TorrentFile(
                    index = 0,
                    relativePath = sanitizedName,
                    length = singleFileLength,
                    startByte = 0L,
                    endByte = singleFileLength
                )
            )
        } else {
            val filesList = rootElement.getList("files")
                ?: throw AppError.InvalidTorrent("Neither 'length' nor 'files' found in info dictionary")

            var currentOffset = 0L
            for ((index, fileItem) in filesList.withIndex()) {
                if (fileItem !is BencodeElement.BDictionary) {
                    throw AppError.InvalidTorrent("File entry $index is not a dictionary")
                }
                val fileLen = fileItem.getInteger("length")
                    ?: throw AppError.InvalidTorrent("Missing 'length' in file entry $index")
                if (fileLen < 0) {
                    throw AppError.InvalidTorrent("Negative length in file entry $index: $fileLen")
                }

                val pathList = fileItem.getList("path")
                    ?: throw AppError.InvalidTorrent("Missing 'path' in file entry $index")
                if (pathList.isEmpty()) {
                    throw AppError.InvalidTorrent("Empty path list in file entry $index")
                }

                val pathSegments = mutableListOf<String>()
                pathSegments.add(sanitizedName)
                for (segmentItem in pathList) {
                    val segmentStr = (segmentItem as? BencodeElement.BString)?.asUtf8String
                        ?: throw AppError.InvalidTorrent("Path segment is not a string in file entry $index")
                    val sanitizedSegment = sanitizePathComponent(segmentStr)
                    if (sanitizedSegment.isNotEmpty()) {
                        pathSegments.add(sanitizedSegment)
                    }
                }

                val relativePath = pathSegments.joinToString(File.separator)

                files.add(
                    TorrentFile(
                        index = index,
                        relativePath = relativePath,
                        length = fileLen,
                        startByte = currentOffset,
                        endByte = currentOffset + fileLen
                    )
                )
                currentOffset += fileLen
            }
        }

        val totalLength = files.sumOf { it.length }
        val isPrivate = (rootElement.getInteger("private") ?: 0L) == 1L

        return TorrentMetadata(
            torrentId = torrentId,
            name = sanitizedName,
            pieceLength = pieceLength,
            totalLength = totalLength,
            piecesCount = pieceHashesCount,
            pieceHashes = pieceHashes,
            files = files,
            announceList = announceList,
            creationDate = null,
            comment = null,
            createdBy = null,
            isPrivate = isPrivate
        )
    }

    /**
     * Reconstructs standard .torrent file bytes from raw info dictionary bytes and tracker URLs.
     */
    fun createTorrentBytesFromInfoDict(
        infoBytes: ByteArray,
        trackers: List<String> = emptyList()
    ): ByteArray {
        val root = linkedMapOf<String, BencodeElement>()
        val infoElement = BencodeDecoder.decode(infoBytes)
        root["info"] = infoElement
        if (trackers.isNotEmpty()) {
            root["announce"] = BencodeElement.BString(trackers.first().toByteArray(Charsets.UTF_8))
            val announceList = trackers.map {
                BencodeElement.BList(listOf(BencodeElement.BString(it.toByteArray(Charsets.UTF_8))))
            }
            root["announce-list"] = BencodeElement.BList(announceList)
        }
        return BencodeEncoder.encode(BencodeElement.BDictionary(root))
    }
}
