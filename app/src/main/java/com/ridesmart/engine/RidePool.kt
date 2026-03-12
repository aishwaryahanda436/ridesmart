package com.ridesmart.engine

import com.ridesmart.model.RideOffer

/**
 * Thread-safe pool of active ride offers awaiting evaluation.
 *
 * Rides are added as they are detected and automatically pruned
 * when they expire.  The pool is the input to [MultiRideComparisonEngine].
 *
 * Design constraints:
 *  - Operates on the main thread or a single-threaded dispatcher.
 *  - No heavy synchronization; callers should access from one thread.
 *  - Maximum pool size capped to avoid unbounded growth.
 */
class RidePool(
    /** Maximum number of offers held at any time. */
    private val maxSize: Int = 20
) {
    private val offers = mutableListOf<RideOffer>()

    /** Add a new ride offer to the pool. Drops oldest if at capacity. */
    fun add(offer: RideOffer) {
        pruneExpired()
        if (offers.size >= maxSize) {
            offers.removeAt(0) // drop oldest
        }
        offers.add(offer)
    }

    /** Add multiple ride offers at once. */
    fun addAll(newOffers: List<RideOffer>) {
        newOffers.forEach { add(it) }
    }

    /** Remove expired offers based on current time. */
    fun pruneExpired(now: Long = System.currentTimeMillis()) {
        offers.removeAll { it.isExpired(now) }
    }

    /** Remove a specific offer by id. */
    fun remove(offerId: String) {
        offers.removeAll { it.id == offerId }
    }

    /** Clear all offers. */
    fun clear() {
        offers.clear()
    }

    /** Snapshot of all active (non-expired) offers. */
    fun getActiveOffers(now: Long = System.currentTimeMillis()): List<RideOffer> {
        pruneExpired(now)
        return offers.toList()
    }

    /** Number of active offers. */
    fun size(now: Long = System.currentTimeMillis()): Int {
        pruneExpired(now)
        return offers.size
    }

    /** Whether the pool is empty after pruning. */
    fun isEmpty(now: Long = System.currentTimeMillis()): Boolean = size(now) == 0
}
