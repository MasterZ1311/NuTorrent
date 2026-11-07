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
     * Maximum allowable bytes read from a single remote tracker sync response (256 KiB).
     */
    const val MAX_REMOTE_RESPONSE_BYTES = 256 * 1024

    /**
     * Maximum HTTP redirect hops allowed when fetching remote tracker lists.
     */
    const val MAX_HTTP_REDIRECTS = 3

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
     * Checks if a line contains a valid BitTorrent tracker URL.
     */
    fun isValidTrackerUrl(url: String): Boolean {
        return url.isNotEmpty() &&
            !url.startsWith("#") &&
            (url.startsWith("udp://", ignoreCase = true) ||
             url.startsWith("http://", ignoreCase = true) ||
             url.startsWith("https://", ignoreCase = true) ||
             url.startsWith("wss://", ignoreCase = true))
    }

    /**
     * Parses raw tracker text (newlines or comma separated) into a clean list of validated URLs.
     * Filters out blank lines, comments (#), and unsupported protocols.
     */
    fun parseTrackersText(text: String): List<String> {
        return text.split("\r\n", "\n", "\r", ",")
            .map { it.trim() }
            .filter { isValidTrackerUrl(it) }
            .distinct()
    }

    /**
     * Reads character stream incrementally up to maxBytes, parsing and validating tracker URLs.
     * Enforces limits during streaming to prevent unbounded allocation.
     */
    fun readBoundedTrackers(inputStream: java.io.InputStream, maxBytes: Int = MAX_REMOTE_RESPONSE_BYTES): List<String> {
        val result = mutableListOf<String>()
        var totalBytesRead = 0
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                totalBytesRead += line.length + 1
                if (totalBytesRead > maxBytes) {
                    Lg.w(LogCategory.TRACKER, "[TRACKER_SYNC] Tracker payload exceeded $maxBytes bytes limit; truncated safely.")
                    break
                }
                val trimmed = line.trim()
                if (isValidTrackerUrl(trimmed)) {
                    result.add(trimmed)
                }
            }
        }
        return result.distinct()
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
     * Fetches tracker list from a remote HTTP/HTTPS source with strict size and timeout bounds.
     * Enforces bounded streaming read and bounded redirect loops.
     */
    suspend fun fetchFromUrl(urlStr: String, timeoutMs: Int = 10000): List<String> = withContext(Dispatchers.IO) {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects <= MAX_HTTP_REDIRECTS) {
            if (!currentUrl.startsWith("http://", ignoreCase = true) && !currentUrl.startsWith("https://", ignoreCase = true)) {
                return@withContext emptyList()
            }
            var conn: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "NuTorrent/1.0.0 (compatible; Motrix/1.8)")
                    instanceFollowRedirects = false // Controlled redirect hops
                }

                val responseCode = conn.responseCode
                if (responseCode in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        return@withContext emptyList()
                    }
                    currentUrl = if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    redirects++
                    continue
                }

                if (responseCode in 200..299) {
                    val declaredLen = conn.contentLengthLong
                    if (declaredLen > MAX_REMOTE_RESPONSE_BYTES) {
                        Lg.w(LogCategory.TRACKER, "[TRACKER_SYNC] Content-Length $declaredLen exceeded $MAX_REMOTE_RESPONSE_BYTES bytes")
                        return@withContext emptyList()
                    }
                    return@withContext conn.inputStream.use { stream ->
                        readBoundedTrackers(stream, MAX_REMOTE_RESPONSE_BYTES)
                    }
                } else {
                    return@withContext emptyList()
                }
            } catch (e: Exception) {
                Lg.w(LogCategory.TRACKER, "[TRACKER_SYNC] Failed fetching from $currentUrl: ${e.message}")
                return@withContext emptyList()
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
            }
        }
        Lg.w(LogCategory.TRACKER, "[TRACKER_SYNC] Exceeded max redirects fetching $urlStr")
        emptyList()
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
