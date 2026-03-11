package com.ridesmart.engine

import com.ridesmart.model.FuelType
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.PlatformConfig
import com.ridesmart.model.ProfitResult
import com.ridesmart.model.RiderProfile
import com.ridesmart.model.Signal
import com.ridesmart.model.VehicleType
import java.time.LocalTime

/**
 * The core math engine of RideSmart — 6-Component Weighted RideScore System.
 *
 * Takes a ParsedRide (what we read from screen) and a RiderProfile (rider's personal
 * vehicle/cost settings) and returns a ProfitResult with full breakdown, RideScore (0–100),
 * and GREEN/YELLOW/RED signal.
 *
 * RideScore components:
 *   S1 — Net Profit Score      (30%)
 *   S2 — Profit Per Km (EPK)   (20%)
 *   S3 — Time Efficiency (TES) (20%)
 *   S4 — Pickup Penalty Score  (15%)
 *   S5 — Surge & Bonus Score   (10%)
 *   S6 — Opportunity Cost Score (5%)
 *
 * Hard Override → RED if: NetProfit < 0, MinViableFare fail, PPR > 0.80, TES < 25%, EPK < ₹1.50
 */
class ProfitCalculator {

    // ── DYNAMIC WEIGHT SETS BY TIME-OF-DAY ──────────────────────────
    data class ScoreWeights(
        val profit: Double,
        val epk: Double,
        val time: Double,
        val pickup: Double,
        val surge: Double,
        val opp: Double
    )

    companion object {
        // Default baseline weights
        private val BASELINE_WEIGHTS = ScoreWeights(0.30, 0.20, 0.20, 0.15, 0.10, 0.05)

        // Hard override threshold for override score
        private const val OVERRIDE_SCORE = 15.0
    }

    /**
     * Returns time-of-day adjusted weights per the spec:
     * - Morning/Evening rush: boost wTime
     * - Off-peak: boost wProfit
     * - Midday lull: standard
     * - Night: boost wProfit
     * - Late night: boost wSurge
     */
    internal fun getTimeAdjustedWeights(hour: Int): ScoreWeights {
        return when (hour) {
            in 6..8   -> ScoreWeights(0.28, 0.18, 0.23, 0.15, 0.11, 0.05) // Morning Rush
            in 9..11  -> ScoreWeights(0.32, 0.20, 0.18, 0.15, 0.10, 0.05) // Off-Peak
            in 12..14 -> BASELINE_WEIGHTS                                   // Midday Lull
            in 15..18 -> ScoreWeights(0.28, 0.18, 0.23, 0.15, 0.11, 0.05) // Evening Rush
            in 19..22 -> ScoreWeights(0.32, 0.20, 0.18, 0.15, 0.10, 0.05) // Night
            else      -> ScoreWeights(0.25, 0.17, 0.17, 0.13, 0.18, 0.10) // Late Night (23-5)
        }
    }

    fun calculate(ride: ParsedRide, profile: RiderProfile): ProfitResult {
        return calculate(ride, profile, LocalTime.now().hour)
    }

    /**
     * Main calculation with explicit hour parameter for testability.
     */
    fun calculate(
        ride: ParsedRide,
        profile: RiderProfile,
        currentHour: Int,
        idleMinutes: Double = 0.0
    ): ProfitResult {

        // ── STEP 1: GROSS FARE WITH SURGE/BONUS/COMMISSION/SUBSCRIPTION ─
        val surgedBase = ride.baseFare * ride.surgeMultiplier
        val grossBeforeDeductions = surgedBase + ride.bonus + ride.tipAmount + ride.premiumAmount

        val subscriptionAmortised = if (profile.subscriptionDailyCost > 0.0 && profile.avgTripsPerDay > 0.0) {
            profile.subscriptionDailyCost / profile.avgTripsPerDay
        } else {
            0.0
        }

        val actualPayout = if (profile.platformCommissionPercent > 0.0) {
            // Commission model: deduct percentage
            grossBeforeDeductions * (1.0 - profile.platformCommissionPercent / 100.0)
        } else {
            // Subscription model or zero-commission: deduct amortised subscription
            val payoutFromPlatformConfig = PlatformConfig.effectivePayout(
                surgedBase + ride.bonus, ride.packageName
            ) + ride.tipAmount + ride.premiumAmount
            payoutFromPlatformConfig - subscriptionAmortised
        }

        val totalFare = ride.baseFare + ride.tipAmount + ride.premiumAmount + ride.bonus

        // ── STEP 2: TOTAL DISTANCE ──────────────────────────────────────
        val totalDistanceKm = ride.pickupDistanceKm + ride.rideDistanceKm

        // ── STEP 3: FUEL COST ───────────────────────────────────────────
        val profileIsDefault = profile.mileageKmPerLitre == 45.0  // driver never customised mileage
        val effectiveMileage = if (ride.vehicleType != VehicleType.UNKNOWN && profileIsDefault) {
            // Driver hasn't set a custom mileage — use the vehicle type's known default
            ride.vehicleType.defaultMileageKmPerLitre
        } else {
            // Driver explicitly set a custom mileage — always honour their setting
            profile.mileageKmPerLitre
        }

        val fuelUnitsUsed = if (effectiveMileage > 0.0) totalDistanceKm / effectiveMileage else 0.0
        val fuelCost = when (ride.vehicleType.fuelType) {
            FuelType.ELECTRIC -> 0.0
            FuelType.CNG      -> fuelUnitsUsed * profile.cngPricePerKg
            FuelType.DIESEL   -> fuelUnitsUsed * profile.dieselPricePerLitre
            FuelType.PETROL   -> fuelUnitsUsed * profile.fuelPricePerLitre
        }

        val fuelCPK = if (effectiveMileage > 0.0) {
            when (ride.vehicleType.fuelType) {
                FuelType.ELECTRIC -> 0.0
                FuelType.CNG      -> profile.cngPricePerKg / effectiveMileage
                FuelType.DIESEL   -> profile.dieselPricePerLitre / effectiveMileage
                FuelType.PETROL   -> profile.fuelPricePerLitre / effectiveMileage
            }
        } else 0.0

        // ── STEP 4: WEAR AND TEAR COST ──────────────────────────────────
        val vehicleMultiplier = ride.vehicleType.wearMultiplier
        val maintenanceCPK = (profile.maintenancePerKm + profile.depreciationPerKm) * vehicleMultiplier
        val wearCost = totalDistanceKm * maintenanceCPK

        // ── STEP 5: CPK WITH CONGESTION ─────────────────────────────────
        val timeCostPerKm = if (profile.cityAvgSpeedKmH > 0.0) {
            profile.targetEarningPerHour / profile.cityAvgSpeedKmH
        } else 0.0
        val cappedCongestion = profile.congestionFactor.coerceIn(1.0, 2.5)
        val operationalCPK = fuelCPK + maintenanceCPK
        val cpk = operationalCPK + (timeCostPerKm * cappedCongestion)

        // ── STEP 6: IDLE TIME COST ──────────────────────────────────────
        val idleTimeCost = (ride.waitTimeMin / 60.0) * profile.targetEarningPerHour

        // ── STEP 7: NET PROFIT ──────────────────────────────────────────
        val netProfit = actualPayout - fuelCost - wearCost - idleTimeCost

        // ── STEP 8: PICKUP PENALTY RATIO (PPR) ─────────────────────────
        val ppr = if (ride.rideDistanceKm > 0.0) {
            ride.pickupDistanceKm / ride.rideDistanceKm
        } else 0.0

        // ── STEP 9: TIME METRICS — TRT, TES, HRR ──────────────────────
        // Pickup time: use provided value or compute from distance/speed
        val pickupTimeMin = if (ride.pickupTimeMin > 0) {
            ride.pickupTimeMin.toDouble()
        } else if (ride.pickupDistanceKm > 0.0 && profile.cityAvgSpeedKmH > 0.0) {
            (ride.pickupDistanceKm / profile.cityAvgSpeedKmH) * 60.0 * cappedCongestion
        } else 0.0

        // Trip time: use rideTimeMin if available, else estimatedDurationMin
        val tripTimeMin = when {
            ride.rideTimeMin > 0 -> ride.rideTimeMin.toDouble()
            ride.estimatedDurationMin > 0 -> ride.estimatedDurationMin.toDouble()
            else -> 0.0
        }

        val trt = pickupTimeMin + tripTimeMin + ride.waitTimeMin.toDouble()
        val tes = if (trt > 0.0 && tripTimeMin > 0.0) (tripTimeMin / trt) * 100.0 else 0.0
        val hrr = if (trt > 0.0) (netProfit / trt) * 60.0 else 0.0

        // ── STEP 10: EARNING METRICS ────────────────────────────────────
        val epk = if (ride.rideDistanceKm > 0.0) netProfit / ride.rideDistanceKm else 0.0
        val earningPerHour = if (tripTimeMin > 0.0) {
            netProfit / (tripTimeMin / 60.0)
        } else if (ride.estimatedDurationMin > 0) {
            netProfit / (ride.estimatedDurationMin.toDouble() / 60.0)
        } else 0.0

        // ── STEP 11: MINIMUM VIABLE FARE CHECK ─────────────────────────
        // Uses full CPK (fuel + maintenance + time cost) per the spec
        val minViableFare = cpk * totalDistanceKm * 1.25

        // ── STEP 12: HARD OVERRIDE CHECK ────────────────────────────────
        val failedChecks = mutableListOf<String>()
        var overrideActive = false

        if (netProfit < 0) {
            failedChecks.add("Net profit ₹${netProfit.toInt()} is negative — ride costs you money")
            overrideActive = true
        }
        if (totalDistanceKm > 0.0 && actualPayout < minViableFare) {
            failedChecks.add("Fare ₹${"%.0f".format(actualPayout)} below minimum viable ₹${"%.0f".format(minViableFare)}")
            overrideActive = true
        }
        if (ppr > 0.80) {
            failedChecks.add("Pickup ${ride.pickupDistanceKm}km is ${(ppr * 100).toInt()}% of ride — extreme deadhead")
            overrideActive = true
        }
        if (tes > 0.0 && tes < 25.0) {
            failedChecks.add("Time efficiency ${tes.toInt()}% — over 75% of ride-cycle earns nothing")
            overrideActive = true
        }
        if (epk < 1.5 && ride.rideDistanceKm > 0.0) {
            failedChecks.add("₹/km ₹${"%.1f".format(epk)} below ₹1.50 minimum — deeply unprofitable")
            overrideActive = true
        }

        // INFORMATIONAL WARNINGS
        if (netProfit < profile.minAcceptableNetProfit) {
            failedChecks.add(
                "Profit ₹${netProfit.toInt()} below your ₹${profile.minAcceptableNetProfit.toInt()} minimum"
            )
        }
        if (epk < profile.minAcceptablePerKm) {
            failedChecks.add(
                "₹/km ₹${"%.1f".format(epk)} below your ₹${"%.1f".format(profile.minAcceptablePerKm)} target"
            )
        }
        if (earningPerHour > 0.0 && earningPerHour < profile.targetEarningPerHour) {
            failedChecks.add(
                "₹/hr pace ₹${earningPerHour.toInt()} below your ₹${profile.targetEarningPerHour.toInt()} goal"
            )
        }
        if (ppr > 0.40) {
            failedChecks.add(
                "Pickup ${ride.pickupDistanceKm}km is ${(ppr * 100).toInt()}% of ride — too far"
            )
        }

        // Hard override → instant RED with complete failure list
        if (overrideActive) {
            return ProfitResult(
                totalFare      = totalFare,
                actualPayout   = actualPayout,
                fuelCost       = fuelCost,
                wearCost       = wearCost,
                idleTimeCost   = idleTimeCost,
                netProfit      = netProfit,
                earningPerKm   = epk,
                earningPerHour = earningPerHour,
                pickupRatio    = ppr,
                rideScore      = OVERRIDE_SCORE,
                tes            = tes,
                trt            = trt,
                hrr            = hrr,
                cpk            = cpk,
                overrideActive = true,
                signal         = Signal.RED,
                failedChecks   = failedChecks
            )
        }

        // ── STEP 13: 6 SUB-SCORES ──────────────────────────────────────
        // S1 — Net Profit Score (target = hourly target × trip time proportion)
        val targetNetProfit = if (tripTimeMin > 0.0) {
            profile.targetEarningPerHour * (tripTimeMin / 60.0)
        } else {
            profile.minAcceptableNetProfit
        }
        val s1 = clamp((netProfit / targetNetProfit) * 100.0)

        // S2 — EPK Score (target = full CPK × 2 — per spec)
        val targetEPK = if (cpk > 0.0) cpk * 2.0 else profile.minAcceptablePerKm * 2.0
        val s2 = clamp((epk / targetEPK) * 100.0)

        // S3 — Time Efficiency Score (neutral 50 when no time data available)
        val s3 = if (tripTimeMin > 0.0) clamp(tes) else clamp(50.0)

        // S4 — Pickup Penalty Score (lower PPR = higher score)
        val s4 = clamp((1.0 - ppr) * 100.0)

        // S5 — Surge & Bonus Score
        val s5 = clamp((ride.surgeMultiplier - 1.0) * 50.0 + ride.bonus / 5.0)

        // S6 — Opportunity Cost Score
        // IdleUrgency: 0.0 when just started, 1.0 after 5+ idle minutes
        // When idle (driver waiting for rides), accepting even mediocre rides is better
        val idleUrgency = (idleMinutes / 5.0).coerceIn(0.0, 1.0)
        // Default zone heat = 0.5 (neutral). High idle urgency → higher S6 → accept more rides
        val zoneHeatIndex = 0.5  // neutral; future: replace with real zone demand data
        val s6 = clamp(100.0 - (zoneHeatIndex * (1.0 - idleUrgency) * 100.0))

        // ── STEP 14: DYNAMIC WEIGHTS ────────────────────────────────────
        val w = getTimeAdjustedWeights(currentHour)

        // ── STEP 15: COMPOSITE RIDESCORE ────────────────────────────────
        val rideScore = s1 * w.profit + s2 * w.epk + s3 * w.time + s4 * w.pickup + s5 * w.surge + s6 * w.opp

        // ── STEP 17: SIGNAL FROM RIDESCORE ──────────────────────────────
        val signal = when {
            rideScore >= 75.0 -> Signal.GREEN
            rideScore >= 45.0 -> Signal.YELLOW
            else              -> Signal.RED
        }

        return ProfitResult(
            totalFare      = totalFare,
            actualPayout   = actualPayout,
            fuelCost       = fuelCost,
            wearCost       = wearCost,
            idleTimeCost   = idleTimeCost,
            netProfit      = netProfit,
            earningPerKm   = epk,
            earningPerHour = earningPerHour,
            pickupRatio    = ppr,
            rideScore      = rideScore,
            tes            = tes,
            trt            = trt,
            hrr            = hrr,
            cpk            = cpk,
            overrideActive = false,
            signal         = signal,
            failedChecks   = failedChecks
        )
    }

    private fun clamp(value: Double, min: Double = 0.0, max: Double = 100.0): Double {
        return value.coerceIn(min, max)
    }
}
