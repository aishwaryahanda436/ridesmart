package com.ridesmart.model

data class ParsedRide(
    val baseFare: Double,
    val tipAmount: Double = 0.0,
    val premiumAmount: Double = 0.0,
    val rideDistanceKm: Double,
    val pickupDistanceKm: Double = 0.0,
    val estimatedDurationMin: Int = 0,
    val platform: String = "",
    val packageName: String = "",
    val rawTextNodes: List<String> = emptyList(),
    val pickupAddress: String = "",
    val dropAddress: String = "",
    val riderRating: Double = 0.0,
    val paymentType: String = "",
    // New fields for enhanced Uber parsing
    val pickupTimeMin: Int = 0,
    val rideTimeMin: Int = 0,
    val bonus: Double = 0.0,
    val fare: Double = 0.0,
    val vehicleType: VehicleType = VehicleType.UNKNOWN,
    val screenState: ScreenState = ScreenState.OFFER_LOADED,
    // RideScore fields — surge multiplier and wait/idle time
    val surgeMultiplier: Double = 1.0,
    val waitTimeMin: Int = 0
)