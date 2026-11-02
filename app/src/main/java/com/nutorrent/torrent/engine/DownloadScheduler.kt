package com.nutorrent.torrent.engine

object DownloadScheduler {
    /**
     * Determines whether [currentHour] falls within the time window from [startHour] to [endHour].
     * Supports both same-day intervals (e.g. 8 to 22) and overnight intervals (e.g. 23 to 7).
     */
    fun isInsideWindow(currentHour: Int, startHour: Int, endHour: Int): Boolean {
        require(currentHour in 0..23) { "currentHour must be between 0 and 23" }
        require(startHour in 0..23) { "startHour must be between 0 and 23" }
        require(endHour in 0..23) { "endHour must be between 0 and 23" }

        return if (startHour <= endHour) {
            currentHour in startHour until endHour
        } else {
            // Window wraps around midnight (e.g., 22:00 to 06:00)
            currentHour >= startHour || currentHour < endHour
        }
    }
}
