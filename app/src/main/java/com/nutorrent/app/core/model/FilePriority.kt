package com.nutorrent.app.core.model

/**
 * Priority for individual files within a multi-file or single-file torrent.
 */
enum class FilePriority(val weight: Int) {
    DO_NOT_DOWNLOAD(0),
    LOW(1),
    NORMAL(2),
    HIGH(3);

    val isDownloadable: Boolean
        get() = this != DO_NOT_DOWNLOAD
}
