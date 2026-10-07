package com.nutorrent.app.core.model

/**
 * Configuration for the central BitTorrent resource governor and queue manager.
 */
data class ResourceGovernorConfig(
    val maxActiveDownloads: Int = 3,
    val maxActiveSeeds: Int = 5,
    val maxTotalPeerConnections: Int = 100,
    val maxDownloadSpeedBps: Long = 0L, // 0 = unlimited
    val maxUploadSpeedBps: Long = 0L    // 0 = unlimited
) {
    init {
        require(maxActiveDownloads >= 1) { "maxActiveDownloads must be at least 1" }
        require(maxActiveSeeds >= 1) { "maxActiveSeeds must be at least 1" }
        require(maxTotalPeerConnections >= 5) { "maxTotalPeerConnections must be at least 5" }
    }
}

/**
 * Central governor that coordinates multi-torrent queueing, active concurrency limits,
 * peer connection budgeting, and global bandwidth constraints.
 */
class ResourceGovernor(
    initialConfig: ResourceGovernorConfig = ResourceGovernorConfig()
) {
    @Volatile
    var config: ResourceGovernorConfig = initialConfig
        private set

    fun updateConfig(newConfig: ResourceGovernorConfig) {
        config = newConfig
    }

    /**
     * Checks if a new download slot is available.
     */
    fun canStartDownload(currentActiveDownloads: Int): Boolean {
        return currentActiveDownloads < config.maxActiveDownloads
    }

    /**
     * Checks if a new seeding slot is available.
     */
    fun canStartSeed(currentActiveSeeds: Int): Boolean {
        return currentActiveSeeds < config.maxActiveSeeds
    }

    /**
     * Allocates the peer connection limit for an individual session based on global limits.
     */
    fun allocatePeerLimit(defaultPerSessionLimit: Int, totalActiveSessions: Int): Int {
        if (totalActiveSessions <= 0) return defaultPerSessionLimit
        val perSessionBudget = config.maxTotalPeerConnections / totalActiveSessions
        return perSessionBudget.coerceIn(5, defaultPerSessionLimit)
    }
}
