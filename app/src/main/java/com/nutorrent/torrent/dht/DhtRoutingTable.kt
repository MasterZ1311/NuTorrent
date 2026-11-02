package com.nutorrent.torrent.dht

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kademlia Routing Table conforming to BEP 5.
 * Uses k-buckets where k = 8.
 * Automatically splits buckets containing our own local node ID.
 */
class DhtRoutingTable(
    val localNodeId: DhtNodeId,
    val k: Int = 8
) {
    internal class Bucket(val depth: Int) {
        val contacts = mutableListOf<DhtContact>()
        var lastModified: Long = System.currentTimeMillis()

        fun isFull(k: Int): Boolean = contacts.size >= k

        fun find(id: DhtNodeId): DhtContact? = contacts.find { it.id == id }

        fun remove(id: DhtNodeId): Boolean {
            val removed = contacts.removeAll { it.id == id }
            if (removed) lastModified = System.currentTimeMillis()
            return removed
        }
    }

    private val buckets = CopyOnWriteArrayList<Bucket>()

    init {
        buckets.add(Bucket(depth = 0))
    }

    @Synchronized
    fun addContact(contact: DhtContact): Boolean {
        if (contact.id == localNodeId) return false // Do not store self

        var bucketIndex = findBucketIndex(contact.id)
        var bucket = buckets[bucketIndex]

        val existing = bucket.find(contact.id)
        if (existing != null) {
            // Update existing contact
            bucket.remove(contact.id)
            existing.lastSeen = System.currentTimeMillis()
            existing.failedQueries = 0
            bucket.contacts.add(existing)
            bucket.lastModified = System.currentTimeMillis()
            return true
        }

        if (!bucket.isFull(k)) {
            bucket.contacts.add(contact)
            bucket.lastModified = System.currentTimeMillis()
            return true
        }

        // Bucket is full: check if we can split
        // Can split if bucket depth < 160 and bucket covers localNodeId prefix
        if (canSplit(bucketIndex, bucket)) {
            splitBucket(bucketIndex)
            // Re-try adding after split
            bucketIndex = findBucketIndex(contact.id)
            bucket = buckets[bucketIndex]
            if (!bucket.isFull(k)) {
                bucket.contacts.add(contact)
                bucket.lastModified = System.currentTimeMillis()
                return true
            }
        }

        // If bucket is still full, check if there's a dead contact (failedQueries >= 3)
        val deadContact = bucket.contacts.find { it.failedQueries >= 3 }
        if (deadContact != null) {
            bucket.remove(deadContact.id)
            bucket.contacts.add(contact)
            bucket.lastModified = System.currentTimeMillis()
            return true
        }

        return false
    }

    @Synchronized
    fun onQueryFailed(nodeId: DhtNodeId) {
        val bucket = buckets[findBucketIndex(nodeId)]
        val contact = bucket.find(nodeId)
        if (contact != null) {
            contact.failedQueries++
            if (contact.failedQueries >= 3) {
                bucket.remove(nodeId)
            }
        }
    }

    @Synchronized
    fun findClosestNodes(target: DhtNodeId, maxCount: Int = k): List<DhtContact> {
        val all = mutableListOf<DhtContact>()
        for (b in buckets) {
            all.addAll(b.contacts)
        }

        all.sortWith { a, b ->
            target.compareDistance(a.id, b.id)
        }

        return all.take(maxCount)
    }

    @Synchronized
    fun totalContacts(): Int = buckets.sumOf { it.contacts.size }

    @Synchronized
    fun getAllContacts(): List<DhtContact> = buckets.flatMap { it.contacts.toList() }

    private fun findBucketIndex(id: DhtNodeId): Int {
        var prefixLen = 0
        for ((idx, bucket) in buckets.withIndex()) {
            if (idx == buckets.size - 1) {
                return idx
            }
            // If ID matches the prefix of this bucket
            val bit = id.bitAt(bucket.depth)
            if (!bit) {
                return idx
            }
        }
        return buckets.size - 1
    }

    private fun canSplit(bucketIndex: Int, bucket: Bucket): Boolean {
        if (bucket.depth >= 159) return false
        // A bucket can be split if its prefix range contains our localNodeId
        // In standard binary tree splitting, the bucket that contains the localNodeId is at index buckets.size - 1
        return bucketIndex == buckets.size - 1 || localNodeId.commonPrefixBits(localNodeId) >= bucket.depth
    }

    private fun splitBucket(bucketIndex: Int) {
        val oldBucket = buckets[bucketIndex]
        val depth = oldBucket.depth

        val bucket0 = Bucket(depth = depth + 1)
        val bucket1 = Bucket(depth = depth + 1)

        for (c in oldBucket.contacts) {
            if (!c.id.bitAt(depth)) {
                bucket0.contacts.add(c)
            } else {
                bucket1.contacts.add(c)
            }
        }

        buckets.removeAt(bucketIndex)
        buckets.add(bucketIndex, bucket1)
        buckets.add(bucketIndex, bucket0)
    }
}
