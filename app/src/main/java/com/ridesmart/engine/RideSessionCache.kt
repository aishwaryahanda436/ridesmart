package com.ridesmart.engine

import com.ridesmart.model.ParsedRide

/**
 * Stores ride evaluation results across card swipes within a single
 * ride-offer session. Resets when the offer screen disappears (IDLE)
 * or when 90 seconds pass with no new cards.
 */
class RideSessionCache {

    sealed class BestSeenStatus {
        data class Active(val result: CachedResult) : BestSeenStatus()
        data class Stale(val result: CachedResult, val staleByMs: Long) : BestSeenStatus()
        object Evicted : BestSeenStatus()
        object Empty   : BestSeenStatus()
    }

    data class CachedResult(
        val ride: ParsedRide,
        val netProfit: Double,
        val decisionScore: Double,
        val cardIndex: Int,          // 1-based position when this card was seen
        val timestampMs: Long
    )

    private val results = mutableMapOf<String, CachedResult>()
    private var sessionStartMs = 0L
    private var nextCardIndex = 1

    companion object {
        private const val SESSION_TIMEOUT_MS = 90_000L
        private const val RIDE_STALE_MS      = 20_000L
    }

    private fun buildKey(ride: ParsedRide): String {
        val pDist = (ride.pickupDistanceKm * 100).toInt()
        val rDist = (ride.rideDistanceKm * 100).toInt()
        val fare  = (ride.baseFare * 10).toInt()
        return "${fare}_${pDist}_${rDist}"
    }

    fun reset() {
        results.clear()
        nextCardIndex = 1
        sessionStartMs = 0L
    }

    fun isExpired(): Boolean {
        if (sessionStartMs == 0L) return false
        return System.currentTimeMillis() - sessionStartMs > SESSION_TIMEOUT_MS
    }

    fun addResult(ride: ParsedRide, netProfit: Double, decisionScore: Double) {
        if (sessionStartMs == 0L || isExpired()) {
            reset()
            sessionStartMs = System.currentTimeMillis()
        }

        val key = buildKey(ride)
        val existing = results[key]
        
        if (existing == null || decisionScore > existing.decisionScore) {
            results[key] = CachedResult(
                ride          = ride,
                netProfit     = netProfit,
                decisionScore = decisionScore,
                cardIndex     = existing?.cardIndex ?: nextCardIndex++,
                timestampMs    = System.currentTimeMillis()
            )
        }
    }

    fun getBestSeen(): CachedResult? = results.values.maxByOrNull { it.decisionScore }
    
    fun getResultFor(ride: ParsedRide): CachedResult? = results[buildKey(ride)]

    fun getTotalCardsSeen(): Int = results.size

    fun isEmpty(): Boolean = results.isEmpty()

    fun markSeen(ride: ParsedRide) {
        val key = buildKey(ride)
        results[key]?.let {
            results[key] = it.copy(timestampMs = System.currentTimeMillis())
        }
    }

    fun addOrRefresh(ride: ParsedRide, netProfit: Double, decisionScore: Double) {
        addResult(ride, netProfit, decisionScore)
        markSeen(ride)
    }

    fun evictExpired(): Int {
        val now         = System.currentTimeMillis()
        val expiredKeys = results.filterValues { now - it.timestampMs > RIDE_STALE_MS }.keys.toList()
        expiredKeys.forEach { results.remove(it) }
        return expiredKeys.size
    }

    fun getBestActiveSeen(): CachedResult? {
        val now = System.currentTimeMillis()
        return results.values
            .filter { now - it.timestampMs <= RIDE_STALE_MS }
            .maxByOrNull { it.decisionScore }
    }

    fun isBestStale(): Boolean {
        val best = getBestSeen() ?: return false
        return System.currentTimeMillis() - best.timestampMs > RIDE_STALE_MS
    }

    fun getBestSeenStatus(): BestSeenStatus {
        if (results.isEmpty()) return BestSeenStatus.Empty
        val best    = results.values.maxByOrNull { it.decisionScore }
                      ?: return BestSeenStatus.Empty
        val staleBy = System.currentTimeMillis() - best.timestampMs
        return if (staleBy <= RIDE_STALE_MS) BestSeenStatus.Active(best)
               else BestSeenStatus.Stale(best, staleBy)
    }
}
