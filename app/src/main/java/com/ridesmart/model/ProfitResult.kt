package com.ridesmart.model

/**
 * Output of ProfitCalculator.
 * Contains the full breakdown of what a ride is worth.
 * The overlay shows: netProfit, earningPerKm, signal, rideScore, and failedChecks.
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

    // ── RESULT ──
    val netProfit: Double,
    // actualPayout - fuelCost - wearCost - idleTimeCost
    // The real money driver earns from this ride

    // ── RATE METRICS ──
    val earningPerKm: Double,
    // netProfit / rideDistanceKm (per paid km only)

    val earningPerHour: Double,
    // netProfit / (estimatedTimeMin / 60)
    // Will be 0.0 if Rapido popup had no time data

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
    // (NetProfit < 0, PPR > 0.80, TES < 25%, EPK < 1.50, MinViableFare fail)

    // ── DECISION ──
    val signal: Signal,
    // GREEN = accept, YELLOW = your call, RED = skip

    val failedChecks: List<String>
    // Which checks failed, shown to driver when signal is YELLOW/RED
    // e.g. ["₹/km ₹11.4 below your ₹15 target"]
    // Empty if GREEN, 1-2 items if YELLOW, 3+ if RED
)
