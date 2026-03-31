package com.ridesmart.engine

import com.ridesmart.model.FuelType
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.PlatformConfig
import com.ridesmart.model.ProfitResult
import com.ridesmart.model.RiderProfile
import com.ridesmart.model.Signal
import com.ridesmart.model.VehicleType
import com.ridesmart.model.PlanType
import com.ridesmart.model.IncentiveProfile

class ProfitCalculator {

    companion object {
        private const val MIN_STABLE_KM            = 0.1
        private const val ESTIMATED_RIDES_FLOOR    = 12.0
        private const val EXPECTED_REMAINING_RIDES = 8
        private const val BONUS_CAP_RATIO          = 0.35
    }

    fun calculate(
        ride: ParsedRide,
        profile: RiderProfile,
        bMarg: Double = 0.0,
        todayRideCount: Int = 0
    ): ProfitResult {

        // STEP 1: PAYOUT — unchanged, commission chain is correct
        val platformDisplayName = PlatformConfig.get(ride.packageName).displayName
        val perPlatformPlan     = profile.platformPlans[platformDisplayName]
        val effectiveBaseFare   = when {
            perPlatformPlan?.planType == PlanType.COMMISSION ->
                ride.baseFare * (1.0 - perPlatformPlan.commissionPercent / 100.0)
            perPlatformPlan?.planType == PlanType.PASS -> ride.baseFare
            profile.useCustomCommission ->
                ride.baseFare * (1.0 - profile.platformCommissionPercent / 100.0)
            else -> PlatformConfig.effectivePayout(ride.baseFare, ride.packageName)
        }
        val totalFare    = ride.baseFare + ride.tipAmount + ride.premiumAmount
        val actualPayout = effectiveBaseFare + ride.tipAmount + ride.premiumAmount

        // STEP 2: BONUS CAP — bonus cannot exceed 35% of real payout
        // Prevents a distant streak from making a ₹10 ride look like ₹210
        val bMargCapped = bMarg.coerceAtMost(actualPayout * BONUS_CAP_RATIO)

        // STEP 3: SUBSCRIPTION COST — dynamic, not fixed 15 rides
        val expectedRidesToday = maxOf(
            todayRideCount + EXPECTED_REMAINING_RIDES.toDouble(),
            ESTIMATED_RIDES_FLOOR
        )
        val subscriptionCostPerRide = when {
            perPlatformPlan?.planType == PlanType.PASS && perPlatformPlan.passAmount > 0.0 ->
                perPlatformPlan.passAmount / expectedRidesToday
            else -> {
                val p = PlatformConfig.get(ride.packageName)
                if (p.subscriptionDailyCost > 0.0) p.subscriptionDailyCost / expectedRidesToday
                else 0.0
            }
        }

        // STEP 4: COSTS on TOTAL distance (pickup + ride) — unchanged, already correct
        val totalDistanceKm = ride.pickupDistanceKm + ride.rideDistanceKm
        val effectiveMileage = if (
            ride.vehicleType != VehicleType.UNKNOWN &&
            ride.vehicleType != VehicleType.BIKE &&
            ride.vehicleType != VehicleType.BIKE_BOOST &&
            profile.mileageKmPerLitre == RiderProfile.DEFAULT_MILEAGE
        ) ride.vehicleType.defaultMileageKmPerLitre else profile.mileageKmPerLitre

        val fuelUnitsUsed = if (effectiveMileage > 0.0) totalDistanceKm / effectiveMileage else 0.0
        val fuelCost = when (ride.vehicleType.fuelType) {
            FuelType.ELECTRIC -> 0.0
            FuelType.CNG      -> fuelUnitsUsed * profile.cngPricePerKg
            FuelType.PETROL   -> fuelUnitsUsed * profile.fuelPricePerLitre
        }
        val vehicleMultiplier = ride.vehicleType.wearMultiplier
        val maintenanceCost   = totalDistanceKm * profile.maintenancePerKm  * vehicleMultiplier
        val depreciationCost  = totalDistanceKm * profile.depreciationPerKm * vehicleMultiplier
        val wearCost          = maintenanceCost + depreciationCost

        // STEP 5: PROFIT — bMarg is capped
        val netProfitCash = actualPayout + bMargCapped - fuelCost - maintenanceCost - subscriptionCostPerRide
        val netProfit     = actualPayout + bMargCapped - fuelCost - wearCost        - subscriptionCostPerRide

        // STEP 6: HARD REJECT — before any scoring
        val hardRejectReason: String? = when {
            netProfit < 0.0 ->
                "Losing ₹${(-netProfit).toInt()} after costs"
            ride.pickupDistanceKm > ride.rideDistanceKm && ride.rideDistanceKm < 5.0 ->
                "Pickup ${ride.pickupDistanceKm}km > drop ${ride.rideDistanceKm}km"
            else -> null
        }

        // STEP 7: PRIMARY EFFICIENCY METRIC
        // FIX Bug 1 + Bug 2: use totalDistanceKm, no quadratic penalty
        // Pickup is naturally penalized — it adds to denominator without adding to payout
        val efficiencyPerKm = if (totalDistanceKm > MIN_STABLE_KM)
            netProfit / totalDistanceKm
        else 0.0

        // STEP 8: DISPLAY-ONLY — earningPerHour shown but never used for ranking
        val earningPerHour = if (ride.estimatedDurationMin > 0)
            netProfit / (ride.estimatedDurationMin.toDouble() / 60.0)
        else 0.0

        val pickupRatio = if (totalDistanceKm > MIN_STABLE_KM)
            ride.pickupDistanceKm / totalDistanceKm
        else 0.0

        // STEP 9: SIGNAL — composite from rider's OWN targets, no fixed numbers
        // FIX Bug 3: normalize at 1.0 = threshold met, 2.0 = double target (cap)
        val profitRatio = (netProfit / profile.minAcceptableNetProfit.coerceAtLeast(1.0))
            .coerceIn(0.0, 2.0)
        val kmRatio = (efficiencyPerKm / profile.minAcceptablePerKm.coerceAtLeast(0.1))
            .coerceIn(0.0, 2.0)

        // FIX Bug 4: no free 1.0 bonus when time is missing
        val hasTime = earningPerHour > 0.0 && profile.targetEarningPerHour > 0.0
        val hourRatio = if (hasTime)
            (earningPerHour / profile.targetEarningPerHour).coerceIn(0.0, 2.0)
        else null

        val composite = if (hourRatio != null)
            profitRatio * 0.50 + kmRatio * 0.35 + hourRatio * 0.15
        else
            profitRatio * 0.55 + kmRatio * 0.45   // reweight when no time data

        // FIX Bug 5: hard reject always RED, composite only used when no hard reject
        val signal = when {
            hardRejectReason != null -> Signal.RED
            composite >= 1.0         -> Signal.GREEN   // both targets met
            composite >= 0.65        -> Signal.YELLOW  // close but not there
            else                     -> Signal.RED
        }

        // STEP 10: DECISION SCORE for ranking — relative to rider's targets
        // FIX Bug 3: 1.0 = at target, 2.0 = double target. × 50 gives 0–100.
        val effScore  = (efficiencyPerKm / profile.minAcceptablePerKm.coerceAtLeast(0.1))
            .coerceIn(0.0, 2.0)
        val profScore = (netProfit / profile.minAcceptableNetProfit.coerceAtLeast(1.0))
            .coerceIn(0.0, 2.0)

        val decisionScore = if (hardRejectReason != null) 0.0
        else (effScore * 0.60 + profScore * 0.40) * 50.0

        // STEP 11: FAILED CHECKS — magnitude-aware text for driver
        val failedChecks = mutableListOf<String>()
        if (hardRejectReason != null) {
            failedChecks.add(hardRejectReason)
        } else {
            if (netProfit < profile.minAcceptableNetProfit)
                failedChecks.add("Profit ₹${netProfit.toInt()} — need ₹${profile.minAcceptableNetProfit.toInt()}")
            if (efficiencyPerKm < profile.minAcceptablePerKm)
                failedChecks.add("₹/km ₹${"%.1f".format(efficiencyPerKm)} — need ₹${"%.1f".format(profile.minAcceptablePerKm)}")
            if (hasTime && earningPerHour < profile.targetEarningPerHour)
                failedChecks.add("₹/hr ₹${earningPerHour.toInt()} — target ₹${profile.targetEarningPerHour.toInt()}")
        }

        return ProfitResult(
            totalFare             = totalFare,
            actualPayout          = actualPayout,
            fuelCost              = fuelCost,
            maintenanceCost       = maintenanceCost,
            depreciationCost      = depreciationCost,
            wearCost              = wearCost,
            netProfitCash         = netProfitCash,
            netProfit             = netProfit,
            efficiencyPerKm       = efficiencyPerKm,
            earningPerHour        = earningPerHour,
            pickupRatio           = pickupRatio,
            hardRejectReason      = hardRejectReason,
            decisionScore         = decisionScore,
            signal                = signal,
            failedChecks          = failedChecks
        )
    }

    /**
     * Proportional share × urgency premium
     */
    fun marginalBonusValue(inc: IncentiveProfile): Double {
        if (!inc.enabled || inc.targetRides <= 0 || inc.rewardAmount <= 0.0) return 0.0
        val remaining = (inc.targetRides - inc.completedToday).coerceAtLeast(0)
        if (remaining == 0) return 0.0

        val sharePerRide = inc.rewardAmount / remaining
        val urgency = when {
            remaining == 1 -> 1.00
            remaining <= 3 -> 0.75
            remaining <= 7 -> 0.45
            else           -> 0.15
        }
        return sharePerRide * urgency
    }

    fun marginalBonusValue(
        streakBonusAmount: Double,
        ridesNeededTotal: Int,
        ridesCompletedSoFar: Int
    ): Double {
        val remaining = (ridesNeededTotal - ridesCompletedSoFar).coerceAtLeast(0)
        if (remaining == 0) return 0.0
        
        val sharePerRide = streakBonusAmount / remaining
        val urgency = when {
            remaining == 1 -> 1.00
            remaining <= 3 -> 0.75
            remaining <= 7 -> 0.45
            else           -> 0.15
        }
        return sharePerRide * urgency
    }
}
