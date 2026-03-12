package com.ridesmart.model

/**
 * Output of ProfitCalculator V3.
 * Contains the full breakdown of what a ride is worth.
 * The overlay shows: netProfit, earningPerKm, signal, rideScore, and failedChecks.
 *
 * V3 additions: pickupCost, totalCost, profitMargin, effectiveHourlyRate,
 * deadMileageCost, sub-scores breakdown for debugging/display.
 */
data class ProfitResult(

    // ── FARE BREAKDOWN ──
    val totalFare: Double,
    // baseFare + tipAmount + premiumAmount

    val actualPayout: Double,
    // totalFare after platform commission deducted
    // This is the real money that hits driver's account

    // ── COST BREAKDOWN ──
    val fuelCost: Double,
    // Fuel cost for ENTIRE journey (pickup km + ride km)
    // Includes unpaid pickup leg — most apps ignore this

    val wearCost: Double,
    // Maintenance + depreciation for entire journey distance

    val idleTimeCost: Double = 0.0,
    // Opportunity cost of wait/idle time before pickup
    // IdleTimeCost = (waitTimeMin / 60) × targetEarningPerHour

    // V3: Explicit pickup cost breakdown
    val pickupCost: Double = 0.0,
    // Fuel + wear cost for JUST the pickup leg (dead mileage)
    // pickupDistanceKm × (fuelCPK + maintenanceCPK)

    // V3: Total cost for transparency
    val totalCost: Double = 0.0,
    // fuelCost + wearCost + idleTimeCost

    // ── RESULT ──
    val netProfit: Double,
    // actualPayout - fuelCost - wearCost - idleTimeCost
    // The real money driver earns from this ride

    // V3: Profit margin percentage
    val profitMargin: Double = 0.0,
    // (netProfit / actualPayout) × 100
    // Shows what percentage of payout is actual profit

    // ── RATE METRICS ──
    val earningPerKm: Double,
    // netProfit / rideDistanceKm (per paid km only)

    val earningPerHour: Double,
    // netProfit / (estimatedTimeMin / 60)
    // Will be 0.0 if Rapido popup had no time data

    // V3: Effective hourly rate over ENTIRE ride cycle (includes pickup + wait)
    val effectiveHourlyRate: Double = 0.0,
    // netProfit / (TRT / 60) — more accurate than earningPerHour
    // This is the HRR metric, aliased for clarity

    // V3.1: Earnings per minute of engaged time (core adaptive metric)
    val earningsPerMinute: Double = 0.0,
    // netProfit / TRT — the real metric drivers optimize for
    // Automatically penalizes long pickups and waiting without hard rules

    // V3.1: Efficiency score relative to driver's target earning rate
    val efficiencyScore: Double = 0.0,
    // earningsPerMinute / driverBaselineRatePerMin
    // 1.0 = meeting target, >1.0 = exceeding target, <1.0 = below target

    // V3.1: Driver's target earning rate per minute (from profile)
    val driverBaselineRatePerMin: Double = 0.0,
    // targetEarningPerHour / 60 — personalized baseline for relative evaluation

    // ── QUALITY METRICS ──
    val pickupRatio: Double,
    // pickupDistanceKm / rideDistanceKm
    // If > 0.4 it means pickup is more than 40% of ride — bad deal

    // ── RIDESCORE METRICS ──
    val rideScore: Double = 0.0,
    // Composite 0-100 score from 6 weighted sub-scores
    // >= 75 → GREEN, 45-74 → YELLOW, < 45 → RED

    val tes: Double = 0.0,
    // Time Efficiency Score: (tripTimeMin / TRT) × 100
    // Percentage of ride-cycle time that earns revenue

    val trt: Double = 0.0,
    // Total Ride Time in minutes: pickupTimeMin + tripTimeMin + waitTimeMin
    // Full ride-cycle duration including unpaid time

    val hrr: Double = 0.0,
    // Hourly Run Rate: (netProfit / TRT) × 60
    // Annualised earning rate for this specific ride

    val cpk: Double = 0.0,
    // Cost Per Km: fuelCPK + maintenanceCPK + timeCostPerKm × congestionFactor
    // Minimum revenue per km to avoid capital erosion

    val overrideActive: Boolean = false,
    // True if a hard override rule forced the signal to RED
    // (NetProfit < 0, PPR > 0.80, TES < 25%, MinViableFare fail)

    // V3: Sub-scores for debugging and display
    val subScores: SubScores = SubScores(),

    // ── DECISION ──
    val signal: Signal,
    // GREEN = accept, YELLOW = your call, RED = skip

    val failedChecks: List<String>
    // Which checks failed, shown to driver when signal is YELLOW/RED
    // e.g. ["₹/km ₹11.4 below your ₹15 target"]
    // Empty if GREEN, 1-2 items if YELLOW, 3+ if RED
) {
    /**
     * V3: Breakdown of individual sub-scores for transparency.
     * Useful for debugging and showing driver which aspects are strong/weak.
     */
    data class SubScores(
        val s1NetProfit: Double = 0.0,     // 0-100: how close to target profit
        val s2EarningsPerKm: Double = 0.0, // 0-100: earning per km efficiency
        val s3TimeEfficiency: Double = 0.0,// 0-100: time revenue ratio
        val s4PickupPenalty: Double = 0.0,  // 0-100: inverse of pickup ratio
        val s5SurgeBonus: Double = 0.0,     // 0-100: surge and bonus value
        val s6OpportunityCost: Double = 0.0 // 0-100: idle urgency factor
    )
}
