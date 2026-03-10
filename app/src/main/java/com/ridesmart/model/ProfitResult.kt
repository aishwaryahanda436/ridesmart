package com.ridesmart.model

/**
 * Output of ProfitCalculator.
 * Contains the full breakdown of what a ride is worth.
 * The overlay shows: netProfit, earningPerKm, signal, and failedChecks.
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

    // ── RESULT ──
    val netProfit: Double,
    // actualPayout - fuelCost - wearCost
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

    // ── DECISION ──
    val signal: Signal,
    // GREEN = accept, YELLOW = your call, RED = skip

    val failedChecks: List<String>
    // Which checks failed, shown to driver when signal is YELLOW
    // e.g. ["₹/km ₹11.4 below your ₹15 target"]
    // Empty if GREEN, 1-2 items if YELLOW, 3+ if RED
)
