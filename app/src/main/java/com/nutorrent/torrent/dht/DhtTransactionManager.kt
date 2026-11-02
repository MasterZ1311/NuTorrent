package com.nutorrent.torrent.dht

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages outgoing KRPC transactions, generating IDs and matching incoming responses.
 */
class DhtTransactionManager(
    private val defaultTimeoutMs: Long = 2500L
) {
    private val txCounter = AtomicInteger(0)
    private val pendingTransactions = ConcurrentHashMap<String, CompletableDeferred<KrpcMessage>>()

    fun createTransaction(): Pair<ByteArray, CompletableDeferred<KrpcMessage>> {
        val count = txCounter.incrementAndGet() and 0xFFFF
        val txId = byteArrayOf((count ushr 8).toByte(), count.toByte())
        val txKey = txId.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val deferred = CompletableDeferred<KrpcMessage>()
        pendingTransactions[txKey] = deferred
        return Pair(txId, deferred)
    }

    fun onResponseReceived(message: KrpcMessage): Boolean {
        val txKey = message.transactionId.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val deferred = pendingTransactions.remove(txKey) ?: return false
        deferred.complete(message)
        return true
    }

    suspend fun awaitResponse(
        txId: ByteArray,
        deferred: CompletableDeferred<KrpcMessage>,
        timeoutMs: Long = defaultTimeoutMs
    ): KrpcMessage? {
        val txKey = txId.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingTransactions.remove(txKey)
        }
    }

    fun clear() {
        pendingTransactions.values.forEach { it.cancel() }
        pendingTransactions.clear()
    }
}
