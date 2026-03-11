package com.ridesmart.model

/**
 * Combined data class for the overlay to show.
 * Contains both the input ride and the calculated profit.
 */
data class RideResult(
    val parsedRide: ParsedRide,
    val totalFare: Double,
    val actualPayout: Double,
    val fuelCost: Double,
    val wearCost: Double,
    val netProfit: Double,
    val earningPerKm: Double,
    val earningPerHour: Double,
    val pickupRatio: Double,
    val signal: Signal,
    val failedChecks: List<String>
)

