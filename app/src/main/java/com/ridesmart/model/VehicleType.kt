package com.ridesmart.model

enum class VehicleType(
    val displayName: String,
    val defaultMileageKmPerLitre: Double,  // petrol km/l, or km/kg for CNG, km/l for diesel, or 0 for electric
    val fuelType: FuelType,
    val wearMultiplier: Double             // relative to bike baseline of 1.0
) {
    BIKE        ("Bike",       45.0,  FuelType.PETROL,   1.0),
    BIKE_BOOST  ("Bike Boost", 45.0,  FuelType.PETROL,   1.0),
    AUTO        ("Auto",       22.0,  FuelType.PETROL,   1.6),
    CNG_AUTO    ("CNG Auto",   28.0,  FuelType.CNG,      1.6),
    EBIKE       ("e-Bike",      0.0,  FuelType.ELECTRIC, 0.7),
    CAR         ("Car",        15.0,  FuelType.PETROL,   2.3),
    DIESEL_CAR  ("Diesel Car", 18.0,  FuelType.DIESEL,   2.3),
    UNKNOWN     ("Unknown",    45.0,  FuelType.PETROL,   1.0)
}

enum class FuelType {
    PETROL,
    DIESEL,
    CNG,
    ELECTRIC
}