package com.ridesmart.model

/**
 * Stores the rider's personal bike and cost settings.
 * Set ONCE by the rider in the profile screen.
 * Used for EVERY ride calculation automatically.
 * All values have sensible defaults for a Delhi bike taxi rider.
 */
data class RiderProfile(

    // ── BIKE SETTINGS ──
    val mileageKmPerLitre: Double = 45.0,
    // How many km the bike gives per litre of fuel
    // Typical range: 35-55 for 100-150cc bikes

    val fuelPricePerLitre: Double = 102.0,
    // Current petrol price in rider's city (₹ per litre)
    // Delhi petrol price as of 2024 ≈ ₹94-104

    val cngPricePerKg: Double = 85.0,
    // Current CNG price in rider's city (₹ per kg)
    // Delhi CNG price as of 2024 ≈ ₹74-85

    // ── COST SETTINGS ──
    val maintenancePerKm: Double = 0.80,
    // Tyre wear, chain, brake pads, engine oil per km
    // Typical range: ₹0.50 to ₹1.50 per km

    val depreciationPerKm: Double = 0.30,
    // Bike losing value through use per km
    // Conservative estimate for 150cc bike

    // ── EARNING TARGETS ──
    val minAcceptableNetProfit: Double = 30.0,
    // Rider will not accept a ride if net profit is below this (₹)

    val minAcceptablePerKm: Double = 3.50,
    // ₹3.50 net profit per km is the correct threshold
    // Drivers think in GROSS ₹/km (fare ÷ distance)
    // We calculate NET ₹/km (profit after all costs ÷ distance)
    // On a typical ₹7/km gross fare, ₹3.50 net = ~50% margin after fuel+wear
    // ₹15 net/km was unrealistically high — it would require fares of ₹25+/km

    val targetEarningPerHour: Double = 200.0,
    // Rider's hourly income goal (₹/hr)
    // Used to score whether ride pace is good or bad

    // ── PLATFORM SETTING ──
    val platformCommissionPercent: Double = 0.0,
    // Commission % the platform takes from the fare shown
    // Set to 0.0 if the fare shown in popup is ALREADY the driver's payout
    // Set to 20.0 if platform takes 20% and fare shown is the gross amount
    // Rapido/Uber typically show post-commission fare to driver — default 0

    // ── CITY & CONGESTION SETTINGS ──
    val cityAvgSpeedKmH: Double = 25.0,
    // Average city driving speed in km/h — used to compute pickup time when not provided
    // Delhi NCR default: 25.5 km/h. Bengaluru: 17 km/h. Tier-2: 30+ km/h.

    val congestionFactor: Double = 1.3,
    // Multiplier for congestion: AvgCitySpeed / CurrentSegmentSpeed (capped at 2.5)
    // 1.0 = free-flowing, 1.3 = moderate, 2.0+ = heavy congestion

    // ── SUBSCRIPTION SETTINGS ──
    val subscriptionDailyCost: Double = 0.0,
    // Daily subscription fee (e.g. Rapido ₹50, Uber Pass ₹113, Ola ₹80)
    // Set to 0.0 for commission-based platforms

    val avgTripsPerDay: Double = 10.0,
    // Average trips per day — used to amortise subscription cost per trip

    // ── DIESEL FUEL PRICE ──
    val dieselPricePerLitre: Double = 87.67
    // Current diesel price in rider's city (₹ per litre)
    // Delhi NCR diesel price as of March 2026 ≈ ₹87.67
)
