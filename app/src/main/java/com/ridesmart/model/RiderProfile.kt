package com.ridesmart.model

/**
 * Stores the rider's personal bike and cost settings.
 * Set ONCE by the rider in the profile screen.
 * Used for EVERY ride calculation automatically.
 * All values have sensible defaults for a Delhi bike taxi rider.
 */
data class RiderProfile(

    // ── BIKE SETTINGS ──
    val mileageKmPerLitre: Double = DEFAULT_MILEAGE,
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
    val minAcceptableNetProfit: Double = 0.0,
    // FIXED Bug 2: 0 = use only the ₹/km scaled minimum.
    // Rider sets a hard floor if they want (e.g. ₹5 = never accept below ₹5 even on 0.5km rides)

    val minAcceptablePerKm: Double = 3.50,
    // ₹3.50 net profit per km is the correct threshold
    // Drivers think in GROSS ₹/km (fare ÷ distance)
    // We calculate NET ₹/km (profit after all costs ÷ distance)
    // On a typical ₹7/km gross fare, ₹3.50 net = ~50% margin after fuel+wear

    val targetEarningPerHour: Double = 200.0,
    // Rider's hourly income goal (₹/hr)
    // Used to score whether ride pace is good or bad

    val dailyEarningTarget: Double = 0.0,
    // Rider's direct daily ₹ goal.
    // 0.0 = not set — overlay hides the "₹X / ₹Y today" progress line.
    // When set, replaces the targetEarningPerHour × 8 approximation
    // used by the overlay and dashboard progress bar.
    // Example: ₹1200 means rider wants ₹1,200 profit today.

    // ── PLATFORM SETTING ──
    val useCustomCommission: Boolean = false,
    val platformCommissionPercent: Double = 0.0,
    // Commission % the platform takes from the fare shown
    // If useCustomCommission is false, we use platform defaults from PlatformConfig.

    // ── PER-PLATFORM PLANS ──
    val platformPlans: Map<String, PlatformPlan> = emptyMap(),
    // Key = platform display name: "Rapido", "Uber", "Ola", "Shadowfax"
    // Value = the rider's personal plan for that platform.
    // If a platform is not in this map, PlatformConfig defaults apply.

    val incentiveProfiles: Map<String, IncentiveProfile> = emptyMap()
    // Key = platform display name: "Rapido", "Uber", "Ola", "Shadowfax"
    // Value = the rider's incentive setup for that platform.
) {
    companion object {
        const val DEFAULT_MILEAGE = 45.0
    }
}
