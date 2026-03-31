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
    // Uber pickup ETA (used to build fakeNodes in RideSmartService)
    val pickupTimeMin: Int = 0,
    val vehicleType: VehicleType = VehicleType.UNKNOWN,
    val screenState: ScreenState = ScreenState.OFFER_LOADED,
    val confidence: Float = 1.0f
)
