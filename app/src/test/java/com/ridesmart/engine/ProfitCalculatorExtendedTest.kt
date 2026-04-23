package com.ridesmart.engine

import com.ridesmart.model.IncentiveProfile
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.PlatformPlan
import com.ridesmart.model.PlanType
import com.ridesmart.model.RiderProfile
import com.ridesmart.model.Signal
import com.ridesmart.model.VehicleType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProfitCalculatorExtendedTest {

    private lateinit var calculator: ProfitCalculator
    private lateinit var baseProfile: RiderProfile

    @Before
    fun setUp() {
        calculator = ProfitCalculator()
        baseProfile = RiderProfile(
            isConfigured              = true,
            mileageKmPerLitre         = 45.0,
            fuelPricePerLitre         = 102.0,
            maintenancePerKm          = 0.80,
            depreciationPerKm         = 0.30,
            minAcceptableNetProfit    = 30.0,
            minAcceptablePerKm        = 3.50,
            targetEarningPerHour      = 200.0,
            platformCommissionPercent = 0.0
        )
    }

    // ── Commission Platform (Uber 20%) ────────────────────────────────

    @Test
    fun `uber platform applies 20 percent default commission on baseFare`() {
        val ride = ParsedRide(
            baseFare         = 100.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, baseProfile)

        // Uber 20% commission → actualPayout = 80.0 (tip and premium are 0)
        assertEquals("Uber payout after 20% commission", 80.0, result.actualPayout, 0.01)
        // totalFare always includes full baseFare
        assertEquals("totalFare is gross fare", 100.0, result.totalFare, 0.01)
        assertTrue("netProfit < totalFare due to commission + costs", result.netProfit < 80.0)
    }

    @Test
    fun `per-platform COMMISSION plan overrides default commission`() {
        val profile = baseProfile.copy(
            platformPlans = mapOf(
                "Uber" to PlatformPlan(planType = PlanType.COMMISSION, commissionPercent = 10.0)
            )
        )
        val ride = ParsedRide(
            baseFare         = 100.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, profile)

        // Custom 10% commission → payout = 90.0
        assertEquals("Custom 10% commission payout", 90.0, result.actualPayout, 0.01)
    }

    @Test
    fun `per-platform PASS plan does not deduct commission`() {
        val profile = baseProfile.copy(
            platformPlans = mapOf(
                "Rapido" to PlatformPlan(planType = PlanType.PASS, passAmount = 30.0)
            )
        )
        val ride = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 6.0,
            pickupDistanceKm = 0.5,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile)

        // PASS plan: baseFare goes directly to payout (no per-ride commission)
        assertEquals("PASS plan: no commission deducted from baseFare", 80.0, result.actualPayout, 0.01)
    }

    @Test
    fun `custom commission from profile is applied when useCustomCommission is true`() {
        val profile = baseProfile.copy(
            useCustomCommission       = true,
            platformCommissionPercent = 15.0
        )
        val ride = ParsedRide(
            baseFare         = 100.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 0.5,
            packageName      = "com.rapido.rider"   // Rapido default is 0%, but override to 15%
        )
        val result = calculator.calculate(ride, profile)

        assertEquals("Custom 15% commission payout", 85.0, result.actualPayout, 0.01)
    }

    // ── Hard Reject ────────────────────────────────────────────────────

    @Test
    fun `ride with negative net profit triggers hard reject and RED signal`() {
        val ride = ParsedRide(
            baseFare         = 10.0,   // Very low fare
            rideDistanceKm   = 30.0,   // Very long ride → high costs
            pickupDistanceKm = 10.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, baseProfile)

        assertTrue("Negative-profit ride should be hard rejected", result.hardRejectReason != null)
        assertEquals("Hard reject should produce RED signal", Signal.RED, result.signal)
        assertEquals("Hard reject yields decisionScore = 0", 0.0, result.decisionScore, 0.01)
        assertTrue("hardRejectReason is in failedChecks", result.failedChecks.isNotEmpty())
    }

    @Test
    fun `pickup greater than ride distance and ride under 5km triggers hard reject`() {
        val ride = ParsedRide(
            baseFare         = 60.0,
            rideDistanceKm   = 3.0,   // < 5.0 km
            pickupDistanceKm = 4.0,   // > ride distance
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, baseProfile)

        assertNotNull("Should hard-reject when pickup > ride and ride < 5km", result.hardRejectReason)
        assertEquals(Signal.RED, result.signal)
    }

    @Test
    fun `pickup greater than ride but ride over 5km does NOT trigger hard reject`() {
        val ride = ParsedRide(
            baseFare         = 60.0,
            rideDistanceKm   = 6.0,   // >= 5.0 km — no reject for this condition
            pickupDistanceKm = 7.0,   // > ride distance
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, baseProfile)

        // Hard reject condition: pickup > ride AND ride < 5.0 — only triggers when ride < 5.0
        // Here ride = 6.0 so this specific check should NOT fire (profit check may still fire)
        if (result.hardRejectReason != null) {
            assertFalse(
                "Hard reject due to pickup>ride should not fire when ride >= 5km",
                result.hardRejectReason!!.contains("Pickup") && result.hardRejectReason!!.contains("drop")
            )
        }
    }

    // ── Signal scoring ─────────────────────────────────────────────────

    @Test
    fun `excellent ride with high fare and short distance should be GREEN`() {
        val ride = ParsedRide(
            baseFare             = 200.0,
            rideDistanceKm       = 5.0,
            pickupDistanceKm     = 0.3,
            estimatedDurationMin = 15,
            packageName          = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, baseProfile)

        assertEquals("High-profit ride should be GREEN", Signal.GREEN, result.signal)
        assertTrue("netProfit should exceed minimum", result.netProfit > baseProfile.minAcceptableNetProfit)
        assertTrue("decisionScore should be positive", result.decisionScore > 0.0)
    }

    @Test
    fun `decisionScore is zero when ride is hard rejected`() {
        val ride = ParsedRide(
            baseFare         = 5.0,
            rideDistanceKm   = 50.0,
            pickupDistanceKm = 10.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, baseProfile)

        assertEquals("decisionScore must be 0 on hard reject", 0.0, result.decisionScore, 0.01)
    }

    // ── Vehicle type costs ─────────────────────────────────────────────

    @Test
    fun `car vehicle type has higher wear cost than bike for same distance`() {
        val bikeRide = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 6.0,
            pickupDistanceKm = 1.0,
            vehicleType      = VehicleType.BIKE,
            packageName      = "com.rapido.rider"
        )
        val carRide = bikeRide.copy(vehicleType = VehicleType.CAR)

        val bikeResult = calculator.calculate(bikeRide, baseProfile)
        val carResult  = calculator.calculate(carRide, baseProfile)

        // Car wearMultiplier = 2.3 vs Bike = 1.0 → car wearCost should be much higher
        assertTrue("Car wear cost should exceed bike wear cost", carResult.wearCost > bikeResult.wearCost)
        assertTrue("Car fuel cost should exceed bike fuel cost (lower mileage)", carResult.fuelCost > bikeResult.fuelCost)
    }

    @Test
    fun `auto vehicle type has higher costs than bike due to higher wear multiplier`() {
        val bikeRide = ParsedRide(
            baseFare         = 70.0,
            rideDistanceKm   = 7.0,
            pickupDistanceKm = 1.0,
            vehicleType      = VehicleType.BIKE,
            packageName      = "com.rapido.rider"
        )
        val autoRide = bikeRide.copy(vehicleType = VehicleType.AUTO)

        val bikeResult = calculator.calculate(bikeRide, baseProfile)
        val autoResult = calculator.calculate(autoRide, baseProfile)

        assertTrue("Auto should have higher wear cost than bike", autoResult.wearCost > bikeResult.wearCost)
    }

    @Test
    fun `eBike vehicle type has zero fuel cost and lower wear cost than bike`() {
        val bikeRide = ParsedRide(
            baseFare         = 60.0,
            rideDistanceKm   = 5.0,
            pickupDistanceKm = 1.0,
            vehicleType      = VehicleType.BIKE,
            packageName      = "com.rapido.rider"
        )
        val ebikeRide = bikeRide.copy(vehicleType = VehicleType.EBIKE)

        val bikeResult  = calculator.calculate(bikeRide,  baseProfile)
        val ebikeResult = calculator.calculate(ebikeRide, baseProfile)

        assertEquals("eBike fuel cost is zero", 0.0, ebikeResult.fuelCost, 0.01)
        assertTrue("eBike wear cost < bike wear cost (0.7 vs 1.0 multiplier)", ebikeResult.wearCost < bikeResult.wearCost)
        // eBike profit should be higher than bike for the same fare
        assertTrue("eBike net profit should exceed bike net profit", ebikeResult.netProfit > bikeResult.netProfit)
    }

    // ── Bonus cap ──────────────────────────────────────────────────────

    @Test
    fun `bonus margin is capped at 35 percent of actual payout`() {
        val ride = ParsedRide(
            baseFare         = 100.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 0.5,
            packageName      = "com.rapido.rider"
        )
        // Pass a bMarg that exceeds 35% cap: 100 * 0.35 = 35; pass 60 → should be capped at 35
        val resultCapped = calculator.calculate(ride, baseProfile, bMarg = 60.0)
        val resultNoCap  = calculator.calculate(ride, baseProfile, bMarg = 20.0)

        // resultCapped should have netProfit = resultNoCap + 15 (35 - 20)
        assertEquals(
            "Bonus is capped at 35% of payout",
            resultNoCap.netProfit + 15.0,
            resultCapped.netProfit,
            0.1
        )
    }

    // ── marginalBonusValue (IncentiveProfile overload) ──────────────────

    @Test
    fun `marginalBonusValue returns 0 when incentive is disabled`() {
        val inc = IncentiveProfile(enabled = false, targetRides = 10, rewardAmount = 500.0, completedToday = 5)
        val result = calculator.marginalBonusValue(inc)
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `marginalBonusValue returns 0 when target already reached`() {
        val inc = IncentiveProfile(enabled = true, targetRides = 10, rewardAmount = 500.0, completedToday = 10)
        val result = calculator.marginalBonusValue(inc)
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `marginalBonusValue applies full urgency when 1 ride remaining`() {
        val inc = IncentiveProfile(enabled = true, targetRides = 10, rewardAmount = 500.0, completedToday = 9)
        val result = calculator.marginalBonusValue(inc)
        // sharePerRide = 500 / 1 = 500; urgency = 1.0 → 500 * 1.0 = 500
        assertEquals(500.0, result, 0.001)
    }

    @Test
    fun `marginalBonusValue applies 75 percent urgency when 2 rides remaining`() {
        val inc = IncentiveProfile(enabled = true, targetRides = 10, rewardAmount = 400.0, completedToday = 8)
        val result = calculator.marginalBonusValue(inc)
        // sharePerRide = 400 / 2 = 200; urgency = 0.75 → 200 * 0.75 = 150
        assertEquals(150.0, result, 0.001)
    }

    @Test
    fun `marginalBonusValue applies 45 percent urgency when between 4 and 7 remaining`() {
        val inc = IncentiveProfile(enabled = true, targetRides = 10, rewardAmount = 700.0, completedToday = 5)
        val result = calculator.marginalBonusValue(inc)
        // remaining = 5; sharePerRide = 700/5 = 140; urgency = 0.45 → 140 * 0.45 = 63
        assertEquals(63.0, result, 0.001)
    }

    @Test
    fun `marginalBonusValue applies 15 percent urgency when more than 7 remaining`() {
        val inc = IncentiveProfile(enabled = true, targetRides = 20, rewardAmount = 800.0, completedToday = 5)
        val result = calculator.marginalBonusValue(inc)
        // remaining = 15; sharePerRide = 800/15 ≈ 53.33; urgency = 0.15 → ~8.0
        assertEquals(800.0 / 15.0 * 0.15, result, 0.01)
    }

    // ── marginalBonusValue (raw params overload) ─────────────────────────

    @Test
    fun `marginalBonusValue raw returns 0 when already completed`() {
        val result = calculator.marginalBonusValue(
            streakBonusAmount    = 500.0,
            ridesNeededTotal     = 10,
            ridesCompletedSoFar  = 10
        )
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `marginalBonusValue raw applies full urgency for last ride`() {
        val result = calculator.marginalBonusValue(
            streakBonusAmount    = 300.0,
            ridesNeededTotal     = 10,
            ridesCompletedSoFar  = 9
        )
        // remaining = 1; sharePerRide = 300; urgency = 1.0 → 300
        assertEquals(300.0, result, 0.001)
    }

    @Test
    fun `marginalBonusValue raw applies 75 percent urgency for 3 remaining`() {
        val result = calculator.marginalBonusValue(
            streakBonusAmount    = 300.0,
            ridesNeededTotal     = 10,
            ridesCompletedSoFar  = 7
        )
        // remaining = 3; sharePerRide = 300/3 = 100; urgency = 0.75 → 75
        assertEquals(75.0, result, 0.001)
    }

    // ── failedChecks content ───────────────────────────────────────────

    @Test
    fun `failedChecks mentions profit when net profit is below minimum`() {
        val profile = baseProfile.copy(
            minAcceptableNetProfit = 100.0,
            minAcceptablePerKm     = 1.0
        )
        val ride = ParsedRide(
            baseFare         = 30.0,
            rideDistanceKm   = 4.0,
            pickupDistanceKm = 0.5,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile)

        val checksText = result.failedChecks.joinToString()
        assertTrue(
            "failedChecks should mention profit shortfall",
            checksText.contains("Profit") || checksText.contains("profit") || checksText.contains("Losing")
        )
    }

    @Test
    fun `earningPerHour is zero when no estimated duration`() {
        val ride = ParsedRide(
            baseFare             = 80.0,
            rideDistanceKm       = 6.0,
            pickupDistanceKm     = 1.0,
            estimatedDurationMin = 0,   // unknown duration
            packageName          = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, baseProfile)

        assertEquals("earningPerHour should be 0 when duration is unknown", 0.0, result.earningPerHour, 0.01)
    }

    @Test
    fun `tip and premium are included in totalFare but payout depends on commission`() {
        val ride = ParsedRide(
            baseFare         = 80.0,
            tipAmount        = 15.0,
            premiumAmount    = 10.0,
            rideDistanceKm   = 6.0,
            pickupDistanceKm = 0.5,
            packageName      = "com.ubercab.driver"  // 20% commission on baseFare
        )
        val result = calculator.calculate(ride, baseProfile)

        // totalFare = baseFare + tip + premium = 105
        assertEquals("totalFare includes tip and premium", 105.0, result.totalFare, 0.01)
        // actualPayout = effectiveBaseFare (80*0.8=64) + tip + premium = 64+15+10 = 89
        assertEquals("Uber payout: 80% of base + tip + premium", 89.0, result.actualPayout, 0.01)
    }
}
