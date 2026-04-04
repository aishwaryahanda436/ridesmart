package com.ridesmart.model

/**
 * Combined data class for the overlay to show.
 * Contains both the input ride and the calculated profit.
 */
data class RideResult(
    val parsedRide:         ParsedRide,
    val totalFare:          Double,
    val actualPayout:       Double,
    val fuelCost:           Double,
    val wearCost:           Double,
    val netProfit:          Double,
    val netProfitCash:      Double,
    val efficiencyPerKm:    Double,     // replaces earningPerKm + adjustedEarningPerKm
    val earningPerHour:     Double,
    val pickupRatio:        Double,
    val hardRejectReason:   String? = null,
    val signal:             Signal,
    val failedChecks:       List<String>,
    val isBestSoFar:        Boolean = true,
    val bestNetProfit:      Double = 0.0,
    val cardIndex:          Int = 1,
    val totalCardsSeen:     Int = 1,
    val todayEarnings:      Double = 0.0,
    val dailyTargetAmount:  Double = 0.0,
    val decisionScore:      Double = 0.0,  // replaces smartScore
    val bestSeenNote:       String? = null
)
