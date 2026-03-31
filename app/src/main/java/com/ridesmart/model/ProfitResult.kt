package com.ridesmart.model

/**
 * Output of ProfitCalculator.
 * Contains the full breakdown of what a ride is worth.
 */
data class ProfitResult(
    val totalFare:            Double,
    val actualPayout:         Double,
    val fuelCost:             Double,
    val maintenanceCost:      Double,   // split from wearCost so overlay can show separately
    val depreciationCost:     Double,
    val wearCost:             Double,   // = maintenanceCost + depreciationCost
    val netProfitCash:        Double,   // no depreciation — cash today
    val netProfit:            Double,   // full economic truth
    val efficiencyPerKm:      Double,   // netProfit / totalDistanceKm — replaces both earningPerKm and adjustedEarningPerKm
    val earningPerHour:       Double,   // display only, 0.0 when duration unavailable
    val pickupRatio:          Double,   // pickupKm / totalKm — display only
    val hardRejectReason:     String?,  // non-null = always RED, shown prominently
    val decisionScore:        Double,   // 0–100, replaces smartScore
    val signal:               Signal,
    val failedChecks:         List<String>
)
