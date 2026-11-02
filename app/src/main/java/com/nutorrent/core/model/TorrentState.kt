package com.nutorrent.core.model

enum class TorrentState {
    QUEUED,
    FETCHING_METADATA,
    DISCOVERING_PEERS,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    SEEDING,
    COMPLETED,
    ERROR;

    val isActive: Boolean
        get() = this == FETCHING_METADATA || this == DISCOVERING_PEERS || this == DOWNLOADING || this == VERIFYING || this == SEEDING

    val isFinished: Boolean
        get() = this == COMPLETED || this == SEEDING
}
