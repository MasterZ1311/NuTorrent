package com.nutorrent.torrent.dht

import com.nutorrent.core.model.PeerEndpoint
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Generates and validates time-bounded security tokens for DHT announce_peer.
 * Rotates secrets every 5 minutes and keeps the previous secret to prevent race conditions.
 */
class DhtTokenManager(
    private val rotationIntervalMs: Long = 5 * 60 * 1000L // 5 minutes
) {
    private val random = SecureRandom()
    private var currentSecret = ByteArray(16).also { random.nextBytes(it) }
    private var previousSecret = ByteArray(16).also { random.nextBytes(it) }
    private var lastRotation = System.currentTimeMillis()

    @Synchronized
    fun generateToken(endpoint: PeerEndpoint): ByteArray {
        rotateIfNeeded()
        return hash(endpoint.ip, currentSecret)
    }

    @Synchronized
    fun validateToken(token: ByteArray, endpoint: PeerEndpoint): Boolean {
        rotateIfNeeded()
        val currentHash = hash(endpoint.ip, currentSecret)
        if (token.contentEquals(currentHash)) return true

        val prevHash = hash(endpoint.ip, previousSecret)
        return token.contentEquals(prevHash)
    }

    private fun rotateIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastRotation > rotationIntervalMs) {
            previousSecret = currentSecret
            currentSecret = ByteArray(16).also { random.nextBytes(it) }
            lastRotation = now
        }
    }

    private fun hash(ip: String, secret: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(ip.toByteArray(Charsets.UTF_8))
        md.update(secret)
        val digest = md.digest()
        // Take first 8 bytes for token
        return digest.copyOfRange(0, 8)
    }
}
