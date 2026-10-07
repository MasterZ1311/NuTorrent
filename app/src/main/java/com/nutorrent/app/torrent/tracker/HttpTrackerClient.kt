package com.nutorrent.app.torrent.tracker

import com.nutorrent.app.core.model.AppError
import com.nutorrent.app.core.model.PeerEndpoint
import com.nutorrent.app.core.util.Lg
import com.nutorrent.app.core.util.LogCategory
import com.nutorrent.app.torrent.bencode.BencodeDecoder
import com.nutorrent.app.torrent.bencode.BencodeElement
import com.nutorrent.app.torrent.bencode.BencodeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

interface TrackerClient {
    suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse
}

class HttpTrackerClient(
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000,
    private val maxResponseSize: Int = 1024 * 1024 // 1 MiB limit
) : TrackerClient {

    override suspend fun announce(request: TrackerAnnounceRequest): TrackerAnnounceResponse = withContext(Dispatchers.IO) {
        val url = buildAnnounceUrl(request)
        Lg.d(LogCategory.TRACKER, "[TRACKER] announce url=${request.trackerUrl}")

        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "NuTorrent/1.0.0")
            connection.setRequestProperty("Accept", "*/*")
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            val inputStream: InputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw AppError.TrackerFailure(
                    request.trackerUrl,
                    "HTTP $responseCode ${connection.responseMessage}"
                )
            }

            val bodyBytes = readBoundedStream(inputStream, maxResponseSize)
            parseTrackerResponse(bodyBytes, request.trackerUrl)
        } catch (e: AppError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AppError.TrackerTimeout(request.trackerUrl, e)
        } catch (e: UnknownHostException) {
            throw AppError.TrackerFailure(request.trackerUrl, "Host not found: ${e.message}")
        } catch (e: Exception) {
            throw AppError.TrackerFailure(request.trackerUrl, e.message ?: "Unknown tracker connection error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBoundedStream(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        input.use { s ->
            var read: Int
            while (s.read(buffer).also { read = it } != -1) {
                total += read
                if (total > limit) {
                    throw AppError.InvalidTorrent("Tracker response exceeded size limit of $limit bytes")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    fun buildAnnounceUrl(request: TrackerAnnounceRequest): String {
        val base = request.trackerUrl.trim()
        val separator = if (base.contains("?")) "&" else "?"
        val sb = StringBuilder(base).append(separator)

        sb.append("info_hash=").append(urlEncodeBytes(request.infoHash))
        sb.append("&peer_id=").append(urlEncodeBytes(request.peerId))
        sb.append("&port=").append(request.port)
        sb.append("&uploaded=").append(request.uploaded)
        sb.append("&downloaded=").append(request.downloaded)
        sb.append("&left=").append(request.left)
        sb.append("&compact=").append(if (request.compact) "1" else "0")

        if (request.event != TrackerEvent.REGULAR && request.event.paramValue.isNotEmpty()) {
            sb.append("&event=").append(request.event.paramValue)
        }

        return sb.toString()
    }

    fun urlEncodeBytes(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            val c = u.toChar()
            if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append("%%%02X".format(u))
            }
        }
        return sb.toString()
    }

    fun parseTrackerResponse(responseBytes: ByteArray, trackerUrl: String): TrackerAnnounceResponse {
        val root: BencodeElement
        try {
            root = BencodeDecoder.decode(responseBytes)
        } catch (e: BencodeException) {
            throw AppError.TrackerFailure(trackerUrl, "Malformed bencoded tracker response: ${e.message}")
        }

        if (root !is BencodeElement.BDictionary) {
            throw AppError.TrackerFailure(trackerUrl, "Tracker response is not a bencode dictionary")
        }

        // Check for tracker failure reason
        val failureReason = root.getString("failure reason")
        if (!failureReason.isNullOrBlank()) {
            throw AppError.TrackerFailure(trackerUrl, failureReason)
        }

        val warning = root.getString("warning message")
        val interval = (root.getInteger("interval") ?: 1800L).coerceAtLeast(30L).toInt()
        val minInterval = root.getInteger("min interval")?.toInt()
        val seeders = root.getInteger("complete")?.toInt()
        val leechers = root.getInteger("incomplete")?.toInt()

        val peers = mutableListOf<PeerEndpoint>()
        val peersElement = root["peers"]

        if (peersElement is BencodeElement.BString) {
            // Compact binary representation (IPv4: 4 bytes IP + 2 bytes port = 6 bytes per peer)
            val peerBytes = peersElement.value
            if (peerBytes.size % 6 != 0) {
                Lg.w(LogCategory.TRACKER, "Compact peers byte length ${peerBytes.size} is not multiple of 6")
            }
            val count = peerBytes.size / 6
            for (i in 0 until count) {
                try {
                    val endpoint = PeerEndpoint.fromCompactIpv4(peerBytes, i * 6)
                    if (endpoint.port > 0 && !peers.contains(endpoint)) {
                        peers.add(endpoint)
                    }
                } catch (e: Exception) {
                    Lg.w(LogCategory.TRACKER, "Failed to parse peer at index $i", e)
                }
            }
        } else if (peersElement is BencodeElement.BList) {
            // Dictionary list representation: [{"ip": "...", "port": ...}, ...]
            for (item in peersElement.value) {
                if (item is BencodeElement.BDictionary) {
                    val ip = item.getString("ip")
                    val port = item.getInteger("port")?.toInt()
                    if (!ip.isNullOrBlank() && port != null && port in 1..65535) {
                        val endpoint = PeerEndpoint(ip.trim(), port)
                        if (!peers.contains(endpoint)) {
                            peers.add(endpoint)
                        }
                    }
                }
            }
        }

        Lg.i(LogCategory.TRACKER, "[TRACKER] response: peers=${peers.size} interval=${interval}s")

        return TrackerAnnounceResponse(
            intervalSeconds = interval,
            minIntervalSeconds = minInterval,
            seeders = seeders,
            leechers = leechers,
            peers = peers,
            warningMessage = warning
        )
    }
}
