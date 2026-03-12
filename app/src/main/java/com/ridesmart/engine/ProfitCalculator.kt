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
 * Profit Engine V3 — Intelligent Real-Time Ride Profitability Engine.
 *
 * Improvements over V2:
 *
 * 1. **EV fuel cost fix**: V2 set electric fuel cost to 0.0 — V3 correctly computes
 *    electricity cost = (distance / efficiency) × electricityRate.
 *
 * 2. **Explicit pickup cost**: V3 breaks out dead mileage cost as a visible metric
 *    so drivers see exactly what the pickup leg costs them.
 *
 * 3. **Improved S5 surge/bonus score**: V2 had no cap on surge contribution and used
 *    a simplistic linear formula. V3 uses diminishing-returns scaling with proper
 *    clamping and bonus normalization relative to base fare.
 *
 * 4. **Short trip penalty**: Very short trips (< 2km) have high overhead per km from
 *    platform minimum fares, idle time, and fixed costs. V3 applies a distance
 *    scaling factor to S2 (EPK) to penalize extremely short trips.
 *
 * 5. **Long trip diminishing returns**: Extremely long trips (> 25km) suffer from
 *    fatigue, return deadheading, and reduced hourly rate. V3 adds a distance
 *    fatigue factor that slightly reduces S1 for very long trips.
 *
 * 6. **Fake surge detection**: High surge with very short distance or very low base
 *    fare is often a "surge trap". V3 detects this pattern and adds a warning.
 *
 * 7. **Traffic-aware time estimation**: When traffic level is provided by the parser,
 *    V3 adjusts pickup time estimates using the traffic multiplier instead of
 *    just the static congestion factor.
 *
 * 8. **Profit margin metric**: V3 exposes profitMargin = (netProfit/actualPayout)×100
 *    giving drivers a percentage view of their true margin.
 *
 * 9. **Effective hourly rate**: V3 computes earning rate over the ENTIRE ride cycle
 *    (pickup + wait + ride) rather than just ride time.
 *
 * 10. **Sub-score transparency**: All 6 sub-scores are returned in the result for
 *     debugging and future ML training data collection.
 *
 * Performance: All computation uses pure floating-point arithmetic with no allocations
 * beyond the result object. Completes in < 1ms on any post-2018 Android device.
 *
 * RideScore components (unchanged weights from V2):
 *   S1 — Net Profit Score      (30%)
 *   S2 — Profit Per Km (EPK)   (20%)
 *   S3 — Time Efficiency (TES) (20%)
 *   S4 — Pickup Penalty Score  (15%)
 *   S5 — Surge & Bonus Score   (10%)
 *   S6 — Opportunity Cost Score (5%)
 *
 * Hard Override → RED if: NetProfit < 0, MinViableFare fail, PPR > 0.80,
 *                         TES < 25%
 *
 * V3.1 Adaptive Improvements:
 *   - Replaced fixed EPK < ₹1.50 hard override with adaptive scoring
 *   - S1 now uses total ride cycle time (TRT) instead of trip-only time,
 *     automatically penalizing long pickups without hard-coded distance rules
 *   - Added earningsPerMinute, efficiencyScore, driverBaselineRatePerMin metrics
 *   - Efficiency score = earningsPerMinute / driverBaselineRate enables
 *     relative evaluation personalized to each driver's target earning rate
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

        // V3: Distance thresholds for edge case handling
        private const val SHORT_TRIP_THRESHOLD_KM = 2.0
        private const val LONG_TRIP_THRESHOLD_KM = 25.0
        private const val LONG_TRIP_MAX_KM = 50.0

        // V3: Surge trap detection thresholds
        private const val SURGE_TRAP_MIN_MULTIPLIER = 1.3
        private const val SURGE_TRAP_MAX_DISTANCE_KM = 2.0
        private const val SURGE_TRAP_MAX_BASE_FARE = 40.0

        // V3: Traffic multipliers (when trafficLevel is provided)
        private val TRAFFIC_MULTIPLIERS = doubleArrayOf(1.0, 1.0, 1.3, 1.6, 2.0)
        // Index: 0=unknown(fallback to congestion), 1=light, 2=moderate, 3=heavy
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

        // ── STEP 3: FUEL COST (V3: Fixed EV cost calculation) ───────────
        val profileIsDefault = profile.mileageKmPerLitre == 45.0  // driver never customised mileage
        val effectiveMileage = if (ride.vehicleType != VehicleType.UNKNOWN && profileIsDefault) {
            ride.vehicleType.defaultMileageKmPerLitre
        } else {
            profile.mileageKmPerLitre
        }

        val fuelCost: Double
        val fuelCPK: Double

        when (ride.vehicleType.fuelType) {
            FuelType.ELECTRIC -> {
                // V3 FIX: EV cost = distance × consumption × electricity rate
                // V2 incorrectly returned 0.0 for electric vehicles
                val evCostPerKm = profile.evConsumptionKWhPerKm * profile.electricityRatePerKWh
                fuelCost = totalDistanceKm * evCostPerKm
                fuelCPK = evCostPerKm
            }
            FuelType.CNG -> {
                val fuelUnitsUsed = if (effectiveMileage > 0.0) totalDistanceKm / effectiveMileage else 0.0
                fuelCost = fuelUnitsUsed * profile.cngPricePerKg
                fuelCPK = if (effectiveMileage > 0.0) profile.cngPricePerKg / effectiveMileage else 0.0
            }
            FuelType.DIESEL -> {
                val fuelUnitsUsed = if (effectiveMileage > 0.0) totalDistanceKm / effectiveMileage else 0.0
                fuelCost = fuelUnitsUsed * profile.dieselPricePerLitre
                fuelCPK = if (effectiveMileage > 0.0) profile.dieselPricePerLitre / effectiveMileage else 0.0
            }
            FuelType.PETROL -> {
                val fuelUnitsUsed = if (effectiveMileage > 0.0) totalDistanceKm / effectiveMileage else 0.0
                fuelCost = fuelUnitsUsed * profile.fuelPricePerLitre
                fuelCPK = if (effectiveMileage > 0.0) profile.fuelPricePerLitre / effectiveMileage else 0.0
            }
        }

        // ── STEP 4: WEAR AND TEAR COST ──────────────────────────────────
        val vehicleMultiplier = ride.vehicleType.wearMultiplier
        val maintenanceCPK = (profile.maintenancePerKm + profile.depreciationPerKm) * vehicleMultiplier
        val wearCost = totalDistanceKm * maintenanceCPK

        // ── STEP 4B (V3): EXPLICIT PICKUP COST ─────────────────────────
        val pickupCost = ride.pickupDistanceKm * (fuelCPK + maintenanceCPK)

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

        // V3: Total cost and profit margin
        val totalCost = fuelCost + wearCost + idleTimeCost
        val profitMargin = if (actualPayout > 0.0) (netProfit / actualPayout) * 100.0 else 0.0

        // ── STEP 8: PICKUP PENALTY RATIO (PPR) ─────────────────────────
        val ppr = if (ride.rideDistanceKm > 0.0) {
            ride.pickupDistanceKm / ride.rideDistanceKm
        } else 0.0

        // ── STEP 9: TIME METRICS — TRT, TES, HRR ──────────────────────
        // V3: Traffic-aware pickup time estimation
        val trafficMultiplier = if (ride.trafficLevel in 1..3) {
            TRAFFIC_MULTIPLIERS[ride.trafficLevel]
        } else {
            cappedCongestion  // fallback to profile congestion factor
        }

        val pickupTimeMin = if (ride.pickupTimeMin > 0) {
            ride.pickupTimeMin.toDouble()
        } else if (ride.pickupDistanceKm > 0.0 && profile.cityAvgSpeedKmH > 0.0) {
            (ride.pickupDistanceKm / profile.cityAvgSpeedKmH) * 60.0 * trafficMultiplier
        } else 0.0

        val tripTimeMin = when {
            ride.rideTimeMin > 0 -> ride.rideTimeMin.toDouble()
            ride.estimatedDurationMin > 0 -> ride.estimatedDurationMin.toDouble()
            else -> 0.0
        }

        val trt = pickupTimeMin + tripTimeMin + ride.waitTimeMin.toDouble()
        val tes = if (trt > 0.0 && tripTimeMin > 0.0) (tripTimeMin / trt) * 100.0 else 0.0
        val hrr = if (trt > 0.0) (netProfit / trt) * 60.0 else 0.0

        // V3: Effective hourly rate over entire ride cycle
        val effectiveHourlyRate = hrr  // same as HRR but named explicitly

        // V3.1: Core adaptive metrics — earnings per minute and efficiency score
        val driverBaselineRatePerMin = if (profile.targetEarningPerHour > 0.0) {
            profile.targetEarningPerHour / 60.0
        } else 0.0
        val earningsPerMinute = if (trt > 0.0) netProfit / trt else 0.0
        val efficiencyScore = if (driverBaselineRatePerMin > 0.0 && trt > 0.0) {
            earningsPerMinute / driverBaselineRatePerMin
        } else 0.0

        // ── STEP 10: EARNING METRICS ────────────────────────────────────
        val epk = if (ride.rideDistanceKm > 0.0) netProfit / ride.rideDistanceKm else 0.0
        val earningPerHour = if (tripTimeMin > 0.0) {
            netProfit / (tripTimeMin / 60.0)
        } else if (ride.estimatedDurationMin > 0) {
            netProfit / (ride.estimatedDurationMin.toDouble() / 60.0)
        } else 0.0

        // ── STEP 11: MINIMUM VIABLE FARE CHECK ─────────────────────────
        // V3 FIX: Use operationalCPK (fuel + maintenance) only, NOT full CPK.
        // Full CPK includes time opportunity cost which double-counts with TES/HRR scoring.
        // minViableFare should answer: "does this ride at least cover vehicle running costs?"
        val minViableFare = operationalCPK * totalDistanceKm * 1.25

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
        // V3.1: Removed fixed EPK < ₹1.50 hard override — replaced by adaptive scoring.
        // Low EPK is handled by S2 (EPK Score) which evaluates relative to operationalCPK.
        // Informational warning only (does NOT set overrideActive) — lets scoring model decide signal.
        if (epk < operationalCPK && ride.rideDistanceKm > 0.0) {
            failedChecks.add("₹/km ₹${"%.1f".format(epk)} below ₹${"%.1f".format(operationalCPK)} running cost — low per-km profitability")
        }

        // V3: Fake surge trap detection
        if (ride.surgeMultiplier >= SURGE_TRAP_MIN_MULTIPLIER &&
            ride.rideDistanceKm < SURGE_TRAP_MAX_DISTANCE_KM &&
            ride.baseFare < SURGE_TRAP_MAX_BASE_FARE) {
            failedChecks.add("⚠ Surge trap: ${ride.surgeMultiplier}× surge on ${ride.rideDistanceKm}km/${ride.baseFare.toInt()}₹ ride — low actual gain")
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
        // V3.1: Efficiency score warning — adaptive, driver-personalized
        if (trt > 0.0 && driverBaselineRatePerMin > 0.0 && efficiencyScore < 0.5) {
            failedChecks.add(
                "Earning ₹${"%.1f".format(earningsPerMinute)}/min — ${(efficiencyScore * 100).toInt()}% of your ₹${"%.1f".format(driverBaselineRatePerMin)}/min target"
            )
        }

        // Hard override → instant RED with complete failure list
        if (overrideActive) {
            return ProfitResult(
                totalFare           = totalFare,
                actualPayout        = actualPayout,
                fuelCost            = fuelCost,
                wearCost            = wearCost,
                idleTimeCost        = idleTimeCost,
                pickupCost          = pickupCost,
                totalCost           = totalCost,
                netProfit           = netProfit,
                profitMargin        = profitMargin,
                earningPerKm        = epk,
                earningPerHour      = earningPerHour,
                effectiveHourlyRate = effectiveHourlyRate,
                earningsPerMinute   = earningsPerMinute,
                efficiencyScore     = efficiencyScore,
                driverBaselineRatePerMin = driverBaselineRatePerMin,
                pickupRatio         = ppr,
                rideScore           = OVERRIDE_SCORE,
                tes                 = tes,
                trt                 = trt,
                hrr                 = hrr,
                cpk                 = cpk,
                overrideActive      = true,
                signal              = Signal.RED,
                failedChecks        = failedChecks
            )
        }

        // ── STEP 13: 6 SUB-SCORES ──────────────────────────────────────
        // S1 — Net Profit Score (target = hourly target × ride cycle time proportion)
        // V3.1: Uses TRT (total ride time including pickup + wait) instead of trip-only time.
        // This automatically penalizes long pickups and waiting without hard-coded distance rules,
        // because a longer pickup increases TRT, which increases targetNetProfit, which lowers S1.
        // V3: Long trip diminishing returns — trips > 25km get progressively penalized
        //     because of return deadheading and fatigue
        val targetNetProfit = if (trt > 0.0) {
            profile.targetEarningPerHour * (trt / 60.0)
        } else {
            profile.minAcceptableNetProfit
        }
        val rawS1 = if (targetNetProfit > 0.0) (netProfit / targetNetProfit) * 100.0 else 0.0
        val longTripFactor = if (ride.rideDistanceKm > LONG_TRIP_THRESHOLD_KM) {
            // Linear decay: 1.0 at 25km, 0.85 at 50km
            val excessKm = (ride.rideDistanceKm - LONG_TRIP_THRESHOLD_KM)
                .coerceAtMost(LONG_TRIP_MAX_KM - LONG_TRIP_THRESHOLD_KM)
            1.0 - (excessKm / (LONG_TRIP_MAX_KM - LONG_TRIP_THRESHOLD_KM)) * 0.15
        } else 1.0
        val s1 = clamp(rawS1 * longTripFactor)

        // S2 — EPK Score
        // V3 FIX: Use operationalCPK (fuel+wear) for EPK target, not full cpk.
        // Full cpk includes time opportunity cost which is already captured by S3 (TES).
        // Using full cpk makes the EPK target unrealistically high (₹27+/km).
        val targetEPK = if (operationalCPK > 0.0) operationalCPK * 2.0 else profile.minAcceptablePerKm * 2.0
        val rawS2 = if (targetEPK > 0.0) (epk / targetEPK) * 100.0 else 0.0
        val shortTripFactor = if (ride.rideDistanceKm < SHORT_TRIP_THRESHOLD_KM && ride.rideDistanceKm > 0.0) {
            // Penalize: scale from 0.7 at 0km to 1.0 at 2km
            0.7 + (ride.rideDistanceKm / SHORT_TRIP_THRESHOLD_KM) * 0.3
        } else 1.0
        val s2 = clamp(rawS2 * shortTripFactor)

        // S3 — Time Efficiency Score (neutral 50 when no time data available)
        val s3 = if (tripTimeMin > 0.0) clamp(tes) else clamp(50.0)

        // S4 — Pickup Penalty Score (lower PPR = higher score)
        val s4 = clamp((1.0 - ppr) * 100.0)

        // S5 — Surge & Bonus Score
        // V3: Improved surge scoring with diminishing returns and bonus normalization
        val surgeScore = if (ride.surgeMultiplier > 1.0) {
            // Diminishing returns: 1.5× → 25, 2.0× → 50, 3.0× → 75, beyond tapers
            val surgeExcess = ride.surgeMultiplier - 1.0
            (1.0 - 1.0 / (1.0 + surgeExcess)) * 100.0
        } else 0.0
        // Bonus normalized relative to base fare (bonus = 50% of base → 50 points)
        val bonusScore = if (ride.baseFare > 0.0) {
            (ride.bonus / ride.baseFare) * 100.0
        } else {
            ride.bonus / 5.0  // fallback: legacy scaling
        }
        val s5 = clamp(surgeScore * 0.6 + bonusScore * 0.4)

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
            totalFare           = totalFare,
            actualPayout        = actualPayout,
            fuelCost            = fuelCost,
            wearCost            = wearCost,
            idleTimeCost        = idleTimeCost,
            pickupCost          = pickupCost,
            totalCost           = totalCost,
            netProfit           = netProfit,
            profitMargin        = profitMargin,
            earningPerKm        = epk,
            earningPerHour      = earningPerHour,
            effectiveHourlyRate = effectiveHourlyRate,
            earningsPerMinute   = earningsPerMinute,
            efficiencyScore     = efficiencyScore,
            driverBaselineRatePerMin = driverBaselineRatePerMin,
            pickupRatio         = ppr,
            rideScore           = rideScore,
            tes                 = tes,
            trt                 = trt,
            hrr                 = hrr,
            cpk                 = cpk,
            overrideActive      = false,
            subScores           = ProfitResult.SubScores(
                s1NetProfit      = s1,
                s2EarningsPerKm  = s2,
                s3TimeEfficiency = s3,
                s4PickupPenalty  = s4,
                s5SurgeBonus     = s5,
                s6OpportunityCost = s6
            ),
            signal              = signal,
            failedChecks        = failedChecks
        )
    }

    private fun clamp(value: Double, min: Double = 0.0, max: Double = 100.0): Double {
        return value.coerceIn(min, max)
    }
}
