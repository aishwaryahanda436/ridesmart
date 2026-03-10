package com.ridesmart.engine

import com.ridesmart.model.FuelType
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.PlatformConfig
import com.ridesmart.model.ProfitResult
import com.ridesmart.model.RiderProfile
import com.ridesmart.model.Signal
import com.ridesmart.model.VehicleType

/**
 * The core math engine of RideSmart.
 * Takes a ParsedRide (what we read from screen) and
 * a RiderProfile (rider's personal bike/cost settings)
 * and returns a ProfitResult with full breakdown + GREEN/YELLOW/RED signal.
 */
class ProfitCalculator {

    fun calculate(ride: ParsedRide, profile: RiderProfile): ProfitResult {

        // ── STEP 1: EFFECTIVE PAYOUT ────────────────────────────────────
        // Use profile commission if driver has set it, otherwise use PlatformConfig
        val effectiveBaseFare = if (profile.platformCommissionPercent > 0.0) {
            ride.baseFare * (1.0 - profile.platformCommissionPercent / 100.0)
        } else {
            PlatformConfig.effectivePayout(ride.baseFare, ride.packageName)
        }
        val totalFare = ride.baseFare + ride.tipAmount + ride.premiumAmount
        val actualPayout = effectiveBaseFare + ride.tipAmount + ride.premiumAmount

        // ── STEP 2: TOTAL DISTANCE TRAVELED ────────────────────────────
        val totalDistanceKm = ride.pickupDistanceKm + ride.rideDistanceKm

        // ── STEP 3: FUEL COST ───────────────────────────────────────────
        // Use vehicle-specific mileage if the driver hasn't set a custom value (i.e. profile
        // mileage is at its default of 45.0 AND the vehicle is not a bike).
        // This prevents Auto/Car rides being calculated with bike fuel efficiency.
        val effectiveMileage = if (ride.vehicleType != com.ridesmart.model.VehicleType.UNKNOWN &&
                                   ride.vehicleType != com.ridesmart.model.VehicleType.BIKE &&
                                   ride.vehicleType != com.ridesmart.model.VehicleType.BIKE_BOOST &&
                                   profile.mileageKmPerLitre == 45.0) {
            // Driver hasn't customised mileage — use vehicle-type default
            ride.vehicleType.defaultMileageKmPerLitre
        } else {
            profile.mileageKmPerLitre
        }

        val fuelUnitsUsed = if (effectiveMileage > 0.0) totalDistanceKm / effectiveMileage else 0.0
        val fuelCost = when (ride.vehicleType.fuelType) {
            FuelType.ELECTRIC -> 0.0
            FuelType.CNG      -> fuelUnitsUsed * profile.cngPricePerKg
            FuelType.PETROL   -> fuelUnitsUsed * profile.fuelPricePerLitre
        }

        // ── STEP 4: WEAR AND TEAR COST ──────────────────────────────────
        // Scale maintenance and depreciation by vehicle type multiplier
        val vehicleMultiplier = ride.vehicleType.wearMultiplier
        val wearRatePerKm = (profile.maintenancePerKm + profile.depreciationPerKm) * vehicleMultiplier
        val wearCost = totalDistanceKm * wearRatePerKm

        // ── STEP 5: NET PROFIT ──────────────────────────────────────────
        val netProfit = actualPayout - fuelCost - wearCost

        // ── STEP 6: EARNING PER KM ──────────────────────────────────────
        val earningPerKm = if (ride.rideDistanceKm > 0.0) {
            netProfit / ride.rideDistanceKm
        } else {
            0.0
        }

        // ── STEP 7: EARNING PER HOUR ────────────────────────────────────
        val earningPerHour = if (ride.estimatedDurationMin > 0.0) {
            netProfit / (ride.estimatedDurationMin.toDouble() / 60.0)
        } else {
            0.0
        }

        // ── STEP 8: PICKUP RATIO ────────────────────────────────────────
        // FIXED: Divide by ride distance, not total distance, to match business logic.
        val pickupRatio = if (ride.rideDistanceKm > 0.0) {
            ride.pickupDistanceKm / ride.rideDistanceKm
        } else {
            0.0
        }

        // ── STEP 9: SCORE AGAINST RIDER THRESHOLDS ─────────────────────
        val failedChecks = mutableListOf<String>()

        if (netProfit < profile.minAcceptableNetProfit) {
            failedChecks.add(
                "Profit ₹${netProfit.toInt()} below your ₹${profile.minAcceptableNetProfit.toInt()} minimum"
            )
        }

        if (earningPerKm < profile.minAcceptablePerKm) {
            failedChecks.add(
                "₹/km ₹${"%.1f".format(earningPerKm)} below your ₹${"%.1f".format(profile.minAcceptablePerKm)} target"
            )
        }

        if (earningPerHour > 0.0 && earningPerHour < profile.targetEarningPerHour) {
            failedChecks.add(
                "₹/hr pace ₹${earningPerHour.toInt()} below your ₹${profile.targetEarningPerHour.toInt()} goal"
            )
        }

        if (pickupRatio > 0.40) {
            failedChecks.add(
                "Pickup ${ride.pickupDistanceKm}km is ${(pickupRatio * 100).toInt()}% of ride — too far"
            )
        }

        // ── STEP 10: FINAL SIGNAL ───────────────────────────────────────
        val signal = when (failedChecks.size) {
            0    -> Signal.GREEN
            1, 2 -> Signal.YELLOW
            else -> Signal.RED
        }

        return ProfitResult(
            totalFare      = totalFare,
            actualPayout   = actualPayout,
            fuelCost       = fuelCost,
            wearCost       = wearCost,
            netProfit      = netProfit,
            earningPerKm   = earningPerKm,
            earningPerHour = earningPerHour,
            pickupRatio    = pickupRatio,
            signal         = signal,
            failedChecks   = failedChecks
        )
    }
}
