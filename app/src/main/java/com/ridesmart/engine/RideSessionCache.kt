package com.ridesmart.engine

import com.ridesmart.model.ParsedRide

/**
 * Stores ride evaluation results across card swipes within a single
 * ride-offer session. Resets when the offer screen disappears (IDLE)
 * or when 90 seconds pass with no new cards.
 *
 * This enables "best so far" comparison even though Rapido only
 * shows full data on the currently expanded card.
 */
class RideSessionCache {

    data class CachedResult(
        val ride: ParsedRide,
        val netProfit: Double,
        val smartScore: Double,
        val cardIndex: Int,          // 1-based position when this card was seen
        val timestampMs: Long
    )

    private val results = mutableMapOf<String, CachedResult>()
    private var sessionStartMs = 0L
    private var nextCardIndex = 1

    companion object {
        private const val SESSION_TIMEOUT_MS = 90_000L  // 90 seconds
    }

    // ── SESSION LIFECYCLE ────────────────────────────────────────────

    fun reset() {
        results.clear()
        nextCardIndex = 1
        sessionStartMs = System.currentTimeMillis()
    }

    fun isExpired(): Boolean {
        if (sessionStartMs == 0L) return false
        return System.currentTimeMillis() - sessionStartMs > SESSION_TIMEOUT_MS
    }

    // ── ADD / QUERY ──────────────────────────────────────────────────

    fun addResult(ride: ParsedRide, netProfit: Double, smartScore: Double) {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()

        // Key = fare + pickup rounded to 1dp + ride rounded to 1dp — identifies unique cards
        val key = "${ride.baseFare}_${String.format("%.1f", ride.pickupDistanceKm)}_${String.format("%.1f", ride.rideDistanceKm)}"

        // Only update if this is a new card or a better reading of the same card
        val existing = results[key]
        val isNewCard = existing == null
        val isBetterScore = existing != null && smartScore > existing.smartScore
        val isBetterData = existing != null &&
                           ride.rideDistanceKm > existing.ride.rideDistanceKm &&
                           smartScore >= existing.smartScore - 0.5  // same score range, more data

        if (isNewCard || isBetterScore || isBetterData) {
            results[key] = CachedResult(
                ride       = ride,
                netProfit  = netProfit,
                smartScore = smartScore,
                cardIndex  = existing?.cardIndex ?: nextCardIndex++,
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    fun getBestSeen(): CachedResult? = results.values.maxByOrNull { it.smartScore }

    fun getTotalCardsSeen(): Int = results.size

    fun isEmpty(): Boolean = results.isEmpty()
}