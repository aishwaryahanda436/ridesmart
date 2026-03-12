package com.ridesmart.engine

import com.ridesmart.model.ComparisonResult
import com.ridesmart.model.ProfitResult
import com.ridesmart.model.RankedRide
import com.ridesmart.model.RideOffer
import com.ridesmart.model.RiderProfile

/**
 * Multi-Ride Comparison Engine.
 *
 * Evaluates all active ride offers simultaneously and recommends
 * the best ride using **relative scoring** — no rigid fixed thresholds.
 *
 * Algorithm:
 *  1. Collect active, non-expired offers with minimum data from the [RidePool].
 *  2. Evaluate each ride with [ProfitCalculator] to get absolute metrics.
 *  3. Compute a **relative score** for each ride by normalising key metrics
 *     across the pool (net profit, earnings per minute, pickup penalty,
 *     trip efficiency).
 *  4. Rank rides by relative score descending.
 *  5. Recommend the highest-scoring ride and flag ties.
 *
 * Performance target: < 50 ms for up to 20 rides on a post-2018 Android device.
 * (ProfitCalculator is < 1 ms per ride, so 20 rides ≈ 20 ms + negligible overhead.)
 *
 * Relative-score weights (configurable):
 *  - Net Profit          35%
 *  - Earnings per Minute 30%
 *  - Trip Efficiency     20%
 *  - Pickup Penalty      15%
 */
class MultiRideComparisonEngine(
    private val profitCalculator: ProfitCalculator = ProfitCalculator()
) {
    companion object {
        // Relative scoring weights
        private const val W_NET_PROFIT = 0.35
        private const val W_EARNINGS_PER_MIN = 0.30
        private const val W_TRIP_EFFICIENCY = 0.20
        private const val W_PICKUP_PENALTY = 0.15

        // Tie threshold — rides within this many points are considered similar
        private const val TIE_THRESHOLD = 5.0
    }

    /**
     * Compare all active rides in the pool and return a ranked result.
     *
     * @param pool         The ride pool containing detected offers.
     * @param profile      The driver's profile for cost calculations.
     * @param currentHour  Hour of day (0-23) for time-adjusted weights.
     * @param now           Current time in millis for expiry checks.
     * @param idleMinutes  How long the driver has been idle.
     * @return [ComparisonResult] with ranked rides and recommendation.
     */
    fun compare(
        pool: RidePool,
        profile: RiderProfile,
        currentHour: Int,
        now: Long = System.currentTimeMillis(),
        idleMinutes: Double = 0.0
    ): ComparisonResult {
        val startMs = System.nanoTime()

        val activeOffers = pool.getActiveOffers(now)
        return compareOffers(activeOffers, profile, currentHour, idleMinutes, startMs)
    }

    /**
     * Compare a list of ride offers directly (without a pool).
     * Useful when offers come from different sources simultaneously.
     *
     * @param offers       List of ride offers to compare.
     * @param profile      The driver's profile for cost calculations.
     * @param currentHour  Hour of day (0-23) for time-adjusted weights.
     * @param idleMinutes  How long the driver has been idle.
     * @return [ComparisonResult] with ranked rides and recommendation.
     */
    fun compareOffers(
        offers: List<RideOffer>,
        profile: RiderProfile,
        currentHour: Int,
        idleMinutes: Double = 0.0
    ): ComparisonResult {
        return compareOffers(offers, profile, currentHour, idleMinutes, System.nanoTime())
    }

    private fun compareOffers(
        offers: List<RideOffer>,
        profile: RiderProfile,
        currentHour: Int,
        idleMinutes: Double,
        startNanos: Long
    ): ComparisonResult {
        // ── STEP 1: Filter valid offers ───────────────────────────────
        val valid = mutableListOf<RideOffer>()
        var skipped = 0
        for (offer in offers) {
            if (offer.hasMinimumData()) {
                valid.add(offer)
            } else {
                skipped++
            }
        }

        if (valid.isEmpty()) {
            val elapsed = (System.nanoTime() - startNanos) / 1_000_000
            return ComparisonResult(
                rankedRides = emptyList(),
                recommended = null,
                evaluationTimeMs = elapsed,
                skippedCount = skipped
            )
        }

        // ── STEP 2: Evaluate each ride ────────────────────────────────
        val evaluated = Array(valid.size) { i ->
            val result = profitCalculator.calculate(
                valid[i].parsedRide, profile, currentHour, idleMinutes
            )
            Pair(valid[i], result)
        }

        // ── STEP 3: Compute relative scores ──────────────────────────
        val relativeScores = computeRelativeScores(evaluated)

        // ── STEP 4: Rank by relative score (descending) ──────────────
        val indexed = relativeScores.indices.sortedByDescending { relativeScores[it] }

        val rankedRides = ArrayList<RankedRide>(indexed.size)
        for ((rank, idx) in indexed.withIndex()) {
            val (offer, result) = evaluated[idx]
            rankedRides.add(
                RankedRide(
                    rideOffer = offer,
                    profitResult = result,
                    relativeScore = relativeScores[idx],
                    rank = rank + 1
                )
            )
        }

        val recommended = rankedRides.firstOrNull()

        // ── STEP 5: Detect ties ───────────────────────────────────────
        val tieWarning = if (rankedRides.size >= 2) {
            val topScore = rankedRides[0].relativeScore
            val secondScore = rankedRides[1].relativeScore
            (topScore - secondScore) < TIE_THRESHOLD
        } else false

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000

        return ComparisonResult(
            rankedRides = rankedRides,
            recommended = recommended,
            evaluationTimeMs = elapsed,
            skippedCount = skipped,
            tieWarning = tieWarning
        )
    }

    /**
     * Compute relative scores for each evaluated ride.
     *
     * Each metric is normalised to 0–100 relative to the best value
     * in the pool, then combined with weights.  This ensures no
     * fixed thresholds — scoring is always relative to current options.
     */
    internal fun computeRelativeScores(
        evaluated: Array<Pair<RideOffer, ProfitResult>>
    ): DoubleArray {
        val n = evaluated.size
        if (n == 0) return DoubleArray(0)
        if (n == 1) return doubleArrayOf(evaluated[0].second.rideScore.coerceIn(0.0, 100.0))

        // Extract metrics
        val netProfits = DoubleArray(n) { evaluated[it].second.netProfit }
        val epms = DoubleArray(n) { evaluated[it].second.earningsPerMinute }
        val tess = DoubleArray(n) { evaluated[it].second.tes }
        val pickupRatios = DoubleArray(n) { evaluated[it].second.pickupRatio }

        // Normalise each metric to 0-100
        val normProfit = normaliseHigherIsBetter(netProfits)
        val normEpm = normaliseHigherIsBetter(epms)
        val normTes = normaliseHigherIsBetter(tess)
        val normPickup = normaliseLowerIsBetter(pickupRatios)

        // Weighted combination
        val scores = DoubleArray(n) { i ->
            val raw = normProfit[i] * W_NET_PROFIT +
                normEpm[i] * W_EARNINGS_PER_MIN +
                normTes[i] * W_TRIP_EFFICIENCY +
                normPickup[i] * W_PICKUP_PENALTY
            raw.coerceIn(0.0, 100.0)
        }
        return scores
    }

    /**
     * Normalise values where higher is better.
     * Best value → 100, worst → 0.  If all values equal → 50 (neutral).
     * Negative values are supported; normalisation is relative to the range.
     */
    private fun normaliseHigherIsBetter(values: DoubleArray): DoubleArray {
        val min = values.min()
        val max = values.max()
        val range = max - min
        if (range < 1e-9) return DoubleArray(values.size) { 50.0 } // all equal
        return DoubleArray(values.size) { ((values[it] - min) / range) * 100.0 }
    }

    /**
     * Normalise values where lower is better (e.g. pickup ratio).
     * Lowest value → 100, highest → 0.  If all values equal → 50 (neutral).
     */
    private fun normaliseLowerIsBetter(values: DoubleArray): DoubleArray {
        val min = values.min()
        val max = values.max()
        val range = max - min
        if (range < 1e-9) return DoubleArray(values.size) { 50.0 } // all equal
        return DoubleArray(values.size) { ((max - values[it]) / range) * 100.0 }
    }
}
