package com.ridesmart.engine

import com.ridesmart.model.ParsedRide

/**
 * Stores ride evaluation results across card swipes within a single
 * ride-offer session. Resets when the offer screen disappears (IDLE)
 * or when 90 seconds pass with no new cards.
 */
class RideSessionCache {

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
}
