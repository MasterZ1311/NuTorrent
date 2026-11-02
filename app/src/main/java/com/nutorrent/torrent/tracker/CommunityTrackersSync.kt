package com.nutorrent.torrent.tracker

import com.nutorrent.core.util.Lg
import com.nutorrent.core.util.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Public BitTorrent tracker synchronization and aggregation engine.
 * Adapted from Motrix (`src/shared/utils/tracker.js` and `src/shared/constants.js`).
 *
 * Automatically fetches and sanitizes community-maintained high-health tracker lists
 * (ngosang and XIU2 collections) with fast jsDelivr CDN mirrors.
 */
object CommunityTrackersSync {

    /**
     * Maximum cumulative characters allowed for trackers payload to prevent buffer overflow.
     * Matches Motrix `MAX_BT_TRACKER_LENGTH = 6144`.
     */
    const val MAX_BT_TRACKER_LENGTH = 6144

    /**
     * Auto-sync interval: 12 Hours (matches Motrix `AUTO_SYNC_TRACKER_INTERVAL`).
     */
    const val AUTO_SYNC_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /**
     * High-reliability public tracker endpoints and their jsDelivr CDN fallbacks.
     */
    val DEFAULT_TRACKER_SOURCES = listOf(
        "https://cdn.jsdelivr.net/gh/ngosang/trackerslist/trackers_best.txt",
        "https://cdn.jsdelivr.net/gh/XIU2/TrackersListCollection/best.txt",
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt",
        "https://raw.githubusercontent.com/XIU2/TrackersListCollection/master/best.txt"
    )

    val POPULAR_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://opentor.org:2710/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://tracker.bitsearch.to:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://movies.zsw.ca:6969/announce",
        "udp://inferno.demonoid.is:3391/announce",
        "udp://ipv4.tracker.harry.lu:80/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "http://open.acgnxtracker.com:80/announce"
    )

    private val cachedTrackers = CopyOnWriteArrayList<String>(POPULAR_TRACKERS)
    private val lastSyncTime = AtomicLong(0L)

    /**
     * Parses raw tracker text (newlines or comma separated) into a clean list of validated URLs.
     * Filters out blank lines, comments (#), and unsupported protocols.
     */
    fun parseTrackersText(text: String): List<String> {
        return text.split("\r\n", "\n", "\r", ",")
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                !line.startsWith("#") &&
                (line.startsWith("udp://", ignoreCase = true) ||
                 line.startsWith("http://", ignoreCase = true) ||
                 line.startsWith("https://", ignoreCase = true) ||
                 line.startsWith("wss://", ignoreCase = true))
            }
            .distinct()
    }

    /**
     * Enforces the maximum length boundary on the tracker list.
     * Derived from Motrix `reduceTrackerString`.
     */
    fun reduceTrackers(trackers: List<String>, maxLength: Int = MAX_BT_TRACKER_LENGTH): List<String> {
        val result = mutableListOf<String>()
        var currentLength = 0
        for (tr in trackers) {
            val addition = if (currentLength == 0) tr.length else tr.length + 1
            if (currentLength + addition > maxLength) break
            result.add(tr)
            currentLength += addition
        }
        return result
    }

    /**
     * Checks if tracker cache is stale and should be updated.
     */
    fun isSyncNeeded(intervalMs: Long = AUTO_SYNC_INTERVAL_MS): Boolean {
        val now = System.currentTimeMillis()
        val last = lastSyncTime.get()
        return cachedTrackers.isEmpty() || (now - last > intervalMs)
    }

    /**
     * Fetches tracker list from a remote HTTP/HTTPS source with a strict timeout.
     */
    suspend fun fetchFromUrl(urlStr: String, timeoutMs: Int = 10000): List<String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "NuTorrent/1.0.0 (compatible; Motrix/1.8)")
            conn.instanceFollowRedirects = true

            if (conn.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val content = reader.readText()
                parseTrackersText(content)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Lg.w(LogCategory.TRACKER, "[TRACKER_SYNC] Failed fetching from $urlStr: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Synchronizes tracker lists from remote sources, merges and deduplicates them.
     */
    suspend fun syncTrackers(
        sources: List<String> = DEFAULT_TRACKER_SOURCES,
        force: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        if (!force && !isSyncNeeded()) {
            return@withContext cachedTrackers.toList()
        }

        val aggregated = mutableSetOf<String>()
        for (source in sources) {
            val list = fetchFromUrl(source)
            if (list.isNotEmpty()) {
                aggregated.addAll(list)
                Lg.i(LogCategory.TRACKER, "[TRACKER_SYNC] Retrieved ${list.size} trackers from $source")
            }
        }

        if (aggregated.isNotEmpty()) {
            val bounded = reduceTrackers(aggregated.toList())
            cachedTrackers.clear()
            cachedTrackers.addAll(bounded)
            lastSyncTime.set(System.currentTimeMillis())
            Lg.i(LogCategory.TRACKER, "[TRACKER_SYNC] Successfully cached ${cachedTrackers.size} community trackers")
        }

        cachedTrackers.toList()
    }

    /**
     * Returns currently cached community trackers or hardcoded defaults if not yet synced.
     */
    fun getCachedTrackers(): List<String> {
        return cachedTrackers.toList()
    }

    /**
     * Merges a torrent's announce list with cached community trackers, ensuring deduplication.
     */
    fun augmentAnnounceList(existingTrackers: List<String>): List<String> {
        val merged = (existingTrackers + cachedTrackers).distinct()
        return reduceTrackers(merged)
    }
}
