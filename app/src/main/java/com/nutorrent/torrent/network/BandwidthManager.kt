package com.nutorrent.torrent.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance hierarchical Token Bucket rate limiter.
 * Controls both global download/upload limits and per-torrent download/upload limits
 * without busy-waiting.
 */
class TokenBucket(
    @Volatile private var rateBytesPerSec: Long = 0L // 0 = unlimited
) {
    private val mutex = Mutex()
    private fun maxTokens(): Long = if (rateBytesPerSec > 0) rateBytesPerSec else Long.MAX_VALUE

    private var tokens: Long = maxTokens()
    private var lastRefillTimestamp: Long = System.currentTimeMillis()

    suspend fun setRate(newRate: Long) {
        mutex.withLock {
            val oldRate = rateBytesPerSec
            rateBytesPerSec = newRate.coerceAtLeast(0L)
            tokens = if (oldRate == 0L || tokens == 0L) {
                maxTokens()
            } else {
                minOf(tokens, maxTokens())
            }
            lastRefillTimestamp = System.currentTimeMillis()
        }
    }

    fun getRate(): Long = rateBytesPerSec

    suspend fun acquire(bytes: Int) {
        if (bytes <= 0 || rateBytesPerSec <= 0L) return
        while (true) {
            val waitMs = mutex.withLock {
                if (rateBytesPerSec <= 0L) {
                    return
                }

                val now = System.currentTimeMillis()
                val elapsed = now - lastRefillTimestamp
                if (elapsed > 0) {
                    val newTokens = (elapsed * rateBytesPerSec) / 1000L
                    tokens = minOf(maxTokens(), tokens + newTokens)
                    lastRefillTimestamp = now
                }

                val targetRequired = minOf(bytes.toLong(), rateBytesPerSec)
                if (tokens >= targetRequired) {
                    tokens -= bytes
                    return
                }

                val needed = targetRequired - tokens
                ((needed * 1000L) / rateBytesPerSec).coerceIn(10L, 1000L)
            }
            delay(waitMs)
        }
    }
}

/**
 * Central bandwidth manager maintaining global and per-torrent rate limits.
 */
class BandwidthManager {
    val globalDownloadBucket = TokenBucket()
    val globalUploadBucket = TokenBucket()

    private val torrentDownloadBuckets = ConcurrentHashMap<String, TokenBucket>()
    private val torrentUploadBuckets = ConcurrentHashMap<String, TokenBucket>()

    suspend fun setGlobalDownloadLimit(limitBps: Long) {
        globalDownloadBucket.setRate(limitBps)
    }

    suspend fun setGlobalUploadLimit(limitBps: Long) {
        globalUploadBucket.setRate(limitBps)
    }

    suspend fun setTorrentDownloadLimit(infoHashHex: String, limitBps: Long) {
        torrentDownloadBuckets.getOrPut(infoHashHex) { TokenBucket() }.setRate(limitBps)
    }

    suspend fun setTorrentUploadLimit(infoHashHex: String, limitBps: Long) {
        torrentUploadBuckets.getOrPut(infoHashHex) { TokenBucket() }.setRate(limitBps)
    }

    fun removeTorrent(infoHashHex: String) {
        torrentDownloadBuckets.remove(infoHashHex)
        torrentUploadBuckets.remove(infoHashHex)
    }

    suspend fun acquireDownload(infoHashHex: String?, bytes: Int) {
        if (bytes <= 0) return
        globalDownloadBucket.acquire(bytes)
        if (infoHashHex != null) {
            torrentDownloadBuckets[infoHashHex]?.acquire(bytes)
        }
    }

    suspend fun acquireUpload(infoHashHex: String?, bytes: Int) {
        if (bytes <= 0) return
        globalUploadBucket.acquire(bytes)
        if (infoHashHex != null) {
            torrentUploadBuckets[infoHashHex]?.acquire(bytes)
        }
    }
}
