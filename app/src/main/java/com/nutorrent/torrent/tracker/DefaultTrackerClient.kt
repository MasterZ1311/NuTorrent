package com.nutorrent.torrent.tracker

import com.nutorrent.core.model.AppError

/**
 * Composite tracker client that automatically delegates to [UdpTrackerClient] or [HttpTrackerClient]
 * depending on the tracker URI scheme.
 */
class DefaultTrackerClient(
    private val httpClient: TrackerClient = HttpTrackerClient(),
    private val udpClient: TrackerClient = UdpTrackerClient()
) : TrackerClient {

    override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse {
        val url = request.trackerUrl.trim()
        return when {
            url.startsWith("udp://", ignoreCase = true) -> udpClient.announce(request)
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> httpClient.announce(request)
            else -> throw AppError.TrackerFailure(url, "Unsupported tracker protocol scheme")
        }
    }
}
