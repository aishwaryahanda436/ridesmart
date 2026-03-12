package com.ridesmart.model

/**
 * A single ride with its evaluation result and relative ranking.
 */
data class RankedRide(
    /** The original ride offer. */
    val rideOffer: RideOffer,

    /** Full profit evaluation from ProfitCalculator. */
    val profitResult: ProfitResult,

    /**
     * Relative score (0–100) computed by comparing this ride against
     * all other rides in the pool.  Unlike [ProfitResult.rideScore]
     * which is an absolute score, this is normalised across the pool.
     */
    val relativeScore: Double,

    /** 1-based rank.  1 = best ride in the pool. */
    val rank: Int
)

/**
 * Output of [MultiRideComparisonEngine.compare].
 *
 * Contains every evaluated ride sorted by [RankedRide.rank],
 * the recommended ride (rank 1), and timing metadata.
 */
data class ComparisonResult(
    /** All evaluated rides, ordered by rank (best first). */
    val rankedRides: List<RankedRide>,

    /** The top-ranked ride, or `null` if no valid rides. */
    val recommended: RankedRide?,

    /** Wall-clock time spent evaluating all rides (milliseconds). */
    val evaluationTimeMs: Long,

    /** Number of rides that were skipped (expired or incomplete). */
    val skippedCount: Int = 0,

    /** Rides with similar score to the recommendation (within 5 pts). */
    val tieWarning: Boolean = false
)
