package com.ridesmart.engine

import com.ridesmart.model.ParsedRide
import java.util.Locale

/**
 * Stores ride evaluation results across card swipes within a single
 * ride-offer session. Resets when the offer screen disappears (IDLE)
 * or when 45 seconds pass (standard offer expiry).
 *
 * Uses MurmurHash3 for O(1) fingerprinting of unique ride cards.
 */
class RideSessionCache {

    data class CachedResult(
        val ride: ParsedRide,
        val netProfit: Double,
        val smartScore: Double,
        val cardIndex: Int,          // 1-based position when this card was seen
        val timestampMs: Long,
        val fingerprint: Int         // MurmurHash3 result
    )

    private val results = mutableMapOf<Int, CachedResult>()
    private var sessionStartMs = 0L
    private var nextCardIndex = 1

    companion object {
        private const val SESSION_TIMEOUT_MS = 45_000L  // 45 seconds (Spec v2.0)
    }

    // ── SESSION LIFECYCLE ────────────────────────────────────────────

    fun reset() {
        results.clear()
        nextCardIndex = 1
        sessionStartMs = System.currentTimeMillis()
    }

    fun resetBest() {
        results.clear()
        nextCardIndex = 1
    }

    fun isExpired(): Boolean {
        if (sessionStartMs == 0L) return false
        return System.currentTimeMillis() - sessionStartMs > SESSION_TIMEOUT_MS
    }

    // ── ADD / QUERY ──────────────────────────────────────────────────

    fun addResult(ride: ParsedRide, netProfit: Double, smartScore: Double) {
        // Hard cap for Indian platforms: reject fares > 2000.0 (phantom OCR reads)
        if (ride.baseFare > 2000.0) return

        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()

        // Outlier rejection: if smartScore is > 5x the average of existing results, reject it
        if (results.size >= 2) {
            val avg = results.values.map { it.smartScore }.average()
            if (smartScore > avg * 5) return
        }

        // Fingerprint = MurmurHash3(fare + pickup + rideType) as per Spec v2.0
        // Fix: Use 2 decimal places for distances and round baseFare to avoid collisions.
        val key = "${ride.baseFare.toLong()}_${String.format(Locale.US, "%.2f", ride.pickupDistanceKm)}_${String.format(Locale.US, "%.2f", ride.rideDistanceKm)}"
        val fingerprint = MurmurHash3.hash32(key)

        // Only update if this is a new card or a better reading of the same card
        val existing = results[fingerprint]
        val isNewCard = existing == null
        val isBetterScore = existing != null && smartScore > existing.smartScore
        
        if (isNewCard || isBetterScore) {
            results[fingerprint] = CachedResult(
                ride       = ride,
                netProfit  = netProfit,
                smartScore = smartScore,
                cardIndex  = existing?.cardIndex ?: nextCardIndex++,
                timestampMs = System.currentTimeMillis(),
                fingerprint = fingerprint
            )
        }
    }

    fun getBestSeen(): CachedResult? = results.values.maxByOrNull { it.smartScore }

    fun getTotalCardsSeen(): Int = results.size

    fun isEmpty(): Boolean = results.isEmpty()
}
