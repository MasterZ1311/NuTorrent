package com.nutorrent.app.torrent.network

import android.content.Context
import android.net.wifi.WifiManager
import com.nutorrent.app.core.util.Lg
import com.nutorrent.app.core.util.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale

/**
 * Lightweight pure-Kotlin UPnP IGD (Internet Gateway Device) client.
 * Discovers router via SSDP (BEP / standard UPnP) and adds TCP port mapping.
 */
object UpnpClient {

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val DISCOVERY_TIMEOUT_MS = 3000
    private const val HTTP_TIMEOUT_MS = 3000

    private data class IgdService(
        val controlUrl: String,
        val serviceType: String
    )

    suspend fun forwardPort(port: Int, context: Context? = null): Boolean = withContext(Dispatchers.IO) {
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            if (context != null) {
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    multicastLock = wm?.createMulticastLock("nutorrent_upnp_lock")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                } catch (ignored: Exception) {}
            }

            val locationUrl = discoverIgdLocation() ?: return@withContext false
            Lg.d(LogCategory.NETWORK, "[UPnP] Found router description at: $locationUrl")

            val igdService = parseIgdService(locationUrl) ?: return@withContext false
            val localIp = getLocalIpv4Address() ?: return@withContext false
            Lg.d(LogCategory.NETWORK, "[UPnP] Local IP: $localIp, Control URL: ${igdService.controlUrl}")

            val success = addPortMapping(igdService, port, localIp)
            if (success) {
                Lg.i(LogCategory.NETWORK, "[UPnP] Port $port successfully forwarded on router to $localIp")
            } else {
                Lg.w(LogCategory.NETWORK, "[UPnP] Router rejected port mapping for port $port")
            }
            success
        } catch (e: Exception) {
            Lg.d(LogCategory.NETWORK, "[UPnP] Port forwarding error: ${e.message}")
            false
        } finally {
            try {
                multicastLock?.release()
            } catch (ignored: Exception) {}
        }
    }

    suspend fun deletePortMapping(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val locationUrl = discoverIgdLocation() ?: return@withContext false
            val igdService = parseIgdService(locationUrl) ?: return@withContext false
            val soapBody = """
                <?xml version="1.0"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                <u:DeletePortMapping xmlns:u="${igdService.serviceType}">
                <NewRemoteHost></NewRemoteHost>
                <NewExternalPort>$port</NewExternalPort>
                <NewProtocol>TCP</NewProtocol>
                </u:DeletePortMapping>
                </s:Body>
                </s:Envelope>
            """.trimIndent()

            val conn = (URL(igdService.controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"${igdService.serviceType}#DeletePortMapping\"")
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(soapBody) }
            val code = conn.responseCode
            code == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun discoverIgdLocation(): String? {
        val searchTargets = listOf(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1"
        )

        for (st in searchTargets) {
            val location = querySsdp(st)
            if (location != null) return location
        }
        return null
    }

    private fun querySsdp(searchTarget: String): String? {
        val query = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "ST: $searchTarget\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n\r\n"

        val queryBytes = query.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { socket ->
            socket.soTimeout = DISCOVERY_TIMEOUT_MS
            val packet = DatagramPacket(
                queryBytes,
                queryBytes.size,
                InetAddress.getByName(SSDP_ADDRESS),
                SSDP_PORT
            )
            socket.send(packet)

            val buffer = ByteArray(2048)
            val recvPacket = DatagramPacket(buffer, buffer.size)

            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(recvPacket)
                    val response = String(recvPacket.data, 0, recvPacket.length, Charsets.US_ASCII)
                    val location = parseHeader(response, "LOCATION")
                    if (location != null) {
                        return location
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
        return null
    }

    private fun parseHeader(response: String, headerName: String): String? {
        val lines = response.lines()
        for (line in lines) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                if (key.equals(headerName, ignoreCase = true)) {
                    return line.substring(colonIdx + 1).trim()
                }
            }
        }
        return null
    }

    private fun parseIgdService(locationUrl: String): IgdService? {
        val xml = fetchUrlContent(locationUrl) ?: return null

        val serviceTypes = listOf(
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1"
        )

        for (st in serviceTypes) {
            val stIdx = xml.indexOf(st)
            if (stIdx != -1) {
                // Search backwards or forwards for <controlURL> within this <service>
                val serviceStart = xml.lastIndexOf("<service>", stIdx)
                val serviceEnd = xml.indexOf("</service>", stIdx)
                if (serviceStart != -1 && serviceEnd != -1) {
                    val serviceSnippet = xml.substring(serviceStart, serviceEnd)
                    val controlUrlSnippet = extractTagContent(serviceSnippet, "controlURL")
                    if (!controlUrlSnippet.isNullOrBlank()) {
                        val fullControlUrl = resolveUrl(locationUrl, controlUrlSnippet)
                        return IgdService(fullControlUrl, st)
                    }
                }
            }
        }
        return null
    }

    private fun extractTagContent(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start == -1) return null
        val end = xml.indexOf(close, start + open.length)
        if (end == -1) return null
        return xml.substring(start + open.length, end).trim()
    }

    private fun resolveUrl(base: String, relative: String): String {
        return try {
            URL(URL(base), relative).toString()
        } catch (e: Exception) {
            relative
        }
    }

    private fun fetchUrlContent(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            if (conn.responseCode == 200) {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun addPortMapping(service: IgdService, port: Int, localIp: String): Boolean {
        val soapBody = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:AddPortMapping xmlns:u="${service.serviceType}">
            <NewRemoteHost></NewRemoteHost>
            <NewExternalPort>$port</NewExternalPort>
            <NewProtocol>TCP</NewProtocol>
            <NewInternalPort>$port</NewInternalPort>
            <NewInternalClient>$localIp</NewInternalClient>
            <NewEnabled>1</NewEnabled>
            <NewPortMappingDescription>NuTorrent</NewPortMappingDescription>
            <NewLeaseDuration>0</NewLeaseDuration>
            </u:AddPortMapping>
            </s:Body>
            </s:Envelope>
        """.trimIndent()

        val conn = (URL(service.controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPAction", "\"${service.serviceType}#AddPortMapping\"")
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(soapBody) }
        val code = conn.responseCode
        return code == 200
    }

    private fun getLocalIpv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }
}
