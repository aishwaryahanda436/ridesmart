package com.ridesmart.model

/**
 * Stores the rider's personal vehicle and cost settings.
 * Set ONCE by the rider in the profile screen.
 * Used for EVERY ride calculation automatically.
 * 
 * Spec v3.0 Update: Added EV support, adaptive thresholds, and performance cache.
 */
data class RiderProfile(

    // ── VEHICLE & ENERGY SETTINGS ──
    val mileageKmPerLitre: Double = 45.0, // 2W typical: 45, 4W typical: 15

    // Current petrol price in rider's city (₹ per litre)
    // Delhi NCR petrol as of March 2026: ₹94.77/L
    // Update this to your city's current rate
    val fuelPricePerLitre: Double = 94.77,

    val dieselPricePerLitre: Double = 87.67, // Delhi Diesel Mar 2026
    val cngPricePerKg: Double = 77.09, // Delhi CNG Mar 2026
    val electricityRatePerKWh: Double = 8.00, // EV charging rate
    val evConsumptionKWhPerKm: Double = 0.12, // Typical EV efficiency

    // ── MAINTENANCE & DEPRECIATION (Amortised per km) ──
    val maintenancePerKm: Double = 0.80, // Tyre, oil, service
    val depreciationPerKm: Double = 0.50, // WDV Method capital loss

    // ── EARNING TARGETS (CPH Baseline) ──
    val targetEarningPerHour: Double = 180.0, // Minimum target to cover EMI + Livelihood
    val minAcceptableNetProfit: Double = 30.0,
    val minAcceptablePerKm: Double = 3.00, // 2W: 3.0, 4W: 5.0

    // ── PLATFORM SETTING ──
    val platformCommissionPercent: Double = 20.0, // legacy commission (Uber/Ola ~20-25%)
    val subscriptionDailyCost: Double = 0.0, // flat fee (Rapido/Namma Yatri/Driver Pass)
    val avgTripsPerDay: Double = 10.0, // for subscription amortisation

    // ── CITY & CONGESTION SETTINGS ──
    val cityAvgSpeedKmH: Double = 25.0, // Delhi: 25.5, Bengaluru: 17
    val congestionFactor: Double = 1.3 // 1.0 to 2.5
)
