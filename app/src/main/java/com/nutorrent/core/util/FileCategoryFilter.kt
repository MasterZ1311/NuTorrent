package com.nutorrent.core.util

import com.nutorrent.core.model.TorrentFile

/**
 * File categorization and selective filtering system.
 * Adapted from Motrix (`src/shared/utils/index.js` file filtering utilities).
 */
enum class TorrentFileCategory(val label: String) {
    ALL("All"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    IMAGE("Images"),
    DOCUMENT("Documents"),
    OTHER("Other")
}

object FileCategoryFilter {

    /**
     * Common video formats (from Motrix VIDEO_SUFFIXES).
     */
    val VIDEO_EXTENSIONS = setOf(
        "3gp", "asf", "avi", "dat", "f4v", "flv", "m4v", "mkv", "mov", "mp4",
        "mpe", "mpeg", "mpg", "rm", "rmvb", "ts", "vob", "webm", "wmv"
    )

    /**
     * Subtitle formats associated with video viewing (from Motrix SUB_SUFFIXES).
     */
    val SUBTITLE_EXTENSIONS = setOf(
        "ass", "srt", "sub", "ssa", "smi", "idx", "vtt"
    )

    /**
     * Audio file formats (from Motrix AUDIO_SUFFIXES).
     */
    val AUDIO_EXTENSIONS = setOf(
        "aac", "ape", "flac", "m4a", "mid", "mp3", "ogg", "wav", "wma", "opus"
    )

    /**
     * Image formats (from Motrix IMAGE_SUFFIXES).
     */
    val IMAGE_EXTENSIONS = setOf(
        "bmp", "gif", "jpeg", "jpg", "png", "svg", "webp", "tiff"
    )

    /**
     * Document and text formats (from Motrix DOCUMENT_SUFFIXES).
     */
    val DOCUMENT_EXTENSIONS = setOf(
        "doc", "docx", "pdf", "ppt", "pptx", "rtf", "txt", "xls", "xlsx", "epub", "mobi"
    )

    /**
     * Extracts extension from a relative or absolute file path.
     */
    fun extractExtension(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val dotIdx = fileName.lastIndexOf('.')
        return if (dotIdx != -1 && dotIdx < fileName.length - 1) {
            fileName.substring(dotIdx + 1).lowercase()
        } else {
            ""
        }
    }

    /**
     * Maps a file path to its corresponding high-level category.
     */
    fun categorize(path: String): TorrentFileCategory {
        val ext = extractExtension(path)
        return when {
            ext in VIDEO_EXTENSIONS || ext in SUBTITLE_EXTENSIONS -> TorrentFileCategory.VIDEO
            ext in AUDIO_EXTENSIONS -> TorrentFileCategory.AUDIO
            ext in IMAGE_EXTENSIONS -> TorrentFileCategory.IMAGE
            ext in DOCUMENT_EXTENSIONS -> TorrentFileCategory.DOCUMENT
            else -> TorrentFileCategory.OTHER
        }
    }

    /**
     * Returns true if file is a video or subtitle stream.
     */
    fun isVideoOrSubtitle(path: String): Boolean {
        val ext = extractExtension(path)
        return ext in VIDEO_EXTENSIONS || ext in SUBTITLE_EXTENSIONS
    }

    /**
     * Returns true if file is an audio track.
     */
    fun isAudio(path: String): Boolean = extractExtension(path) in AUDIO_EXTENSIONS

    /**
     * Returns true if file is an image.
     */
    fun isImage(path: String): Boolean = extractExtension(path) in IMAGE_EXTENSIONS

    /**
     * Returns true if file is a document.
     */
    fun isDocument(path: String): Boolean = extractExtension(path) in DOCUMENT_EXTENSIONS

    /**
     * Filters a list of [TorrentFile]s matching the specified category.
     */
    fun filterFiles(files: List<TorrentFile>, category: TorrentFileCategory): List<TorrentFile> {
        if (category == TorrentFileCategory.ALL) return files
        return files.filter { categorize(it.relativePath) == category }
    }
}
