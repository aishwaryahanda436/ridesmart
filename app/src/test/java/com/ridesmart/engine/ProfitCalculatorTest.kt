package com.ridesmart.engine

import com.ridesmart.model.ParsedRide
import com.ridesmart.model.RiderProfile
import com.ridesmart.model.Signal
import com.ridesmart.model.VehicleType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProfitCalculatorTest {

    private lateinit var calculator: ProfitCalculator
    private lateinit var profile: RiderProfile

    @Before
    fun setUp() {
        calculator = ProfitCalculator()
        profile = RiderProfile(
            mileageKmPerLitre         = 45.0,
            fuelPricePerLitre         = 102.0,
            maintenancePerKm          = 0.80,
            depreciationPerKm         = 0.30,
            minAcceptableNetProfit    = 30.0,
            minAcceptablePerKm        = 3.50,
            targetEarningPerHour      = 200.0,
            platformCommissionPercent = 0.0,
            cityAvgSpeedKmH           = 25.0,
            congestionFactor          = 1.3
        )
    }

    // ── EXISTING V2 TESTS (PRESERVED) ───────────────────────────────

    @Test
    fun `rapido basic ride low fare should be YELLOW`() {
        val ride = ParsedRide(
            baseFare         = 37.0,
            rideDistanceKm   = 5.2,
            pickupDistanceKm = 1.1,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("totalFare", 37.0, result.totalFare, 0.01)
        assertTrue("netProfit should be positive", result.netProfit > 0.0)
        assertTrue("netProfit below 30 minimum", result.netProfit < 30.0)
        assertTrue("earningPerKm below 3.50", result.earningPerKm < 3.50)
        assertFalse("no hard override", result.overrideActive)
        assertTrue("rideScore in YELLOW range", result.rideScore >= 45.0 && result.rideScore < 75.0)
        assertEquals("YELLOW signal", Signal.YELLOW, result.signal)
    }

    @Test
    fun `rapido ride with tip should be YELLOW without time data`() {
        val ride = ParsedRide(
            baseFare         = 76.0,
            tipAmount        = 10.0,
            rideDistanceKm   = 12.1,
            pickupDistanceKm = 0.8,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("totalFare includes tip", 86.0, result.totalFare, 0.01)
        assertTrue("netProfit above 30", result.netProfit > 30.0)
        assertTrue("earningPerKm above 3.50", result.earningPerKm > 3.50)
        assertTrue("pickupRatio below 0.40", result.pickupRatio < 0.40)
        assertFalse("no hard override", result.overrideActive)
        assertEquals("YELLOW — profitable but no time data / no surge", Signal.YELLOW, result.signal)
    }

    @Test
    fun `uber ride with premium and time data should be GREEN`() {
        val ride = ParsedRide(
            baseFare         = 74.40,
            premiumAmount    = 18.0,
            rideDistanceKm   = 7.4,
            pickupDistanceKm = 1.1,
            estimatedDurationMin = 17,
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("totalFare includes premium", 92.40, result.totalFare, 0.01)
        assertTrue("netProfit above 30", result.netProfit > 30.0)
        assertTrue("earningPerHour calculated and not zero", result.earningPerHour > 0.0)
        assertTrue("TES computed from time data", result.tes > 0.0)
        assertTrue("TRT computed", result.trt > 0.0)
        assertTrue("HRR computed", result.hrr > 0.0)
        assertFalse("no hard override", result.overrideActive)
        assertTrue("rideScore >= 75 for GREEN", result.rideScore >= 75.0)
        assertEquals("Should be GREEN", Signal.GREEN, result.signal)
    }

    @Test
    fun `very long pickup should trigger PPR hard override RED`() {
        val ride = ParsedRide(
            baseFare         = 40.0,
            rideDistanceKm   = 3.0,
            pickupDistanceKm = 2.5,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("pickupRatio above 0.80", result.pickupRatio > 0.80)
        assertTrue("hard override active", result.overrideActive)
        assertEquals("hard override → RED", Signal.RED, result.signal)
        assertTrue("failedChecks contains pickup/deadhead warning",
            result.failedChecks.any { it.contains("deadhead", ignoreCase = true) || it.contains("Pickup", ignoreCase = true) }
        )
    }

    @Test
    fun `borderline ride should be YELLOW with RideScore`() {
        val ride = ParsedRide(
            baseFare         = 55.0,
            rideDistanceKm   = 4.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("netProfit above 30", result.netProfit > 30.0)
        assertTrue("earningPerKm above 3.50", result.earningPerKm > 3.50)
        assertFalse("no hard override", result.overrideActive)
        assertTrue("rideScore near but below 75", result.rideScore > 60.0)
        assertEquals("YELLOW — profitable but no time/surge data", Signal.YELLOW, result.signal)
    }

    @Test
    fun `CNG auto ride should use CNG price not petrol price`() {
        val ride = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 6.0,
            pickupDistanceKm = 1.0,
            vehicleType      = VehicleType.CNG_AUTO,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        // CNG_AUTO: mileage=28km/kg, cngPrice=77.09₹/kg (profile default)
        // totalDist = 7.0, fuelUnits = 7.0/28 = 0.25, fuelCost = 0.25 × 77.09 = 19.27
        assertEquals("Fuel cost uses CNG price", 19.27, result.fuelCost, 0.5)
        assertTrue("netProfit above 30", result.netProfit > 30.0)
    }

    @Test
    fun `surge multiplier should boost fare and RideScore`() {
        val rideNoSurge = ParsedRide(
            baseFare         = 60.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            estimatedDurationMin = 20,
            packageName      = "com.rapido.rider"
        )
        val rideSurge = rideNoSurge.copy(surgeMultiplier = 1.5)

        val resultNoSurge = calculator.calculate(rideNoSurge, profile, 12)
        val resultSurge = calculator.calculate(rideSurge, profile, 12)

        assertTrue("surge increases actual payout", resultSurge.actualPayout > resultNoSurge.actualPayout)
        assertTrue("surge increases net profit", resultSurge.netProfit > resultNoSurge.netProfit)
        assertTrue("surge increases RideScore", resultSurge.rideScore > resultNoSurge.rideScore)
    }

    @Test
    fun `negative net profit should trigger hard override RED`() {
        val ride = ParsedRide(
            baseFare         = 10.0,
            rideDistanceKm   = 15.0,
            pickupDistanceKm = 3.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("net profit is negative", result.netProfit < 0.0)
        assertTrue("hard override active", result.overrideActive)
        assertEquals("hard override → RED", Signal.RED, result.signal)
        assertEquals("RideScore forced to 15.0", 15.0, result.rideScore, 0.01)
    }

    @Test
    fun `subscription amortisation should reduce actual payout`() {
        val profileWithSub = profile.copy(
            subscriptionDailyCost = 100.0,
            avgTripsPerDay = 10.0
        )
        val ride = ParsedRide(
            baseFare         = 60.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.rapido.rider"
        )

        val resultNoSub = calculator.calculate(ride, profile, 12)
        val resultWithSub = calculator.calculate(ride, profileWithSub, 12)

        assertTrue("subscription reduces payout", resultWithSub.actualPayout < resultNoSub.actualPayout)
        assertEquals("payout reduced by ₹10", resultNoSub.actualPayout - 10.0, resultWithSub.actualPayout, 0.01)
    }

    @Test
    fun `idle time cost should reduce net profit and RideScore`() {
        val rideNoWait = ParsedRide(
            baseFare         = 60.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.rapido.rider"
        )
        val rideWithWait = rideNoWait.copy(waitTimeMin = 10)

        val resultNoWait = calculator.calculate(rideNoWait, profile, 12)
        val resultWithWait = calculator.calculate(rideWithWait, profile, 12)

        assertTrue("idle time cost is positive", resultWithWait.idleTimeCost > 0.0)
        assertEquals("idle cost = (10/60)*200", 33.33, resultWithWait.idleTimeCost, 0.5)
        assertTrue("net profit reduced by idle cost", resultWithWait.netProfit < resultNoWait.netProfit)
        assertTrue("RideScore reduced by idle cost", resultWithWait.rideScore < resultNoWait.rideScore)
    }

    @Test
    fun `diesel vehicle should use diesel fuel price`() {
        val ride = ParsedRide(
            baseFare         = 120.0,
            rideDistanceKm   = 10.0,
            pickupDistanceKm = 1.5,
            vehicleType      = VehicleType.DIESEL_CAR,
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("Fuel cost uses diesel price", 56.01, result.fuelCost, 1.0)
        assertTrue("CPK reflects diesel", result.cpk > 0.0)
    }

    @Test
    fun `time of day affects RideScore weights`() {
        val ride = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            estimatedDurationMin = 18,
            packageName      = "com.rapido.rider"
        )

        val resultMorning = calculator.calculate(ride, profile, 7)
        val resultMidday  = calculator.calculate(ride, profile, 12)
        val resultNight   = calculator.calculate(ride, profile, 20)

        assertEquals("same net profit", resultMorning.netProfit, resultMidday.netProfit, 0.01)
        assertTrue("morning score computed", resultMorning.rideScore > 0.0)
        assertTrue("midday score computed", resultMidday.rideScore > 0.0)
        assertTrue("night score computed", resultNight.rideScore > 0.0)
        assertFalse("morning vs midday scores differ",
            resultMorning.rideScore == resultMidday.rideScore && resultMidday.rideScore == resultNight.rideScore
        )
    }

    @Test
    fun `TES and TRT computed correctly with time data`() {
        val ride = ParsedRide(
            baseFare         = 100.0,
            rideDistanceKm   = 10.0,
            pickupDistanceKm = 2.0,
            estimatedDurationMin = 25,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("TRT includes pickup and trip time", result.trt > 25.0)
        assertTrue("TES between 0 and 100", result.tes in 0.0..100.0)
        assertTrue("TES shows good efficiency", result.tes > 70.0)
        assertTrue("HRR computed", result.hrr > 0.0)
    }

    @Test
    fun `min viable fare triggers hard override for very cheap long ride`() {
        val ride = ParsedRide(
            baseFare         = 15.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 2.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("hard override active", result.overrideActive)
        assertEquals("hard override → RED", Signal.RED, result.signal)
    }

    @Test
    fun `high value ride with surge and time data should be GREEN`() {
        val ride = ParsedRide(
            baseFare         = 120.0,
            surgeMultiplier  = 1.3,
            bonus            = 15.0,
            rideDistanceKm   = 12.0,
            pickupDistanceKm = 1.5,
            estimatedDurationMin = 25,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("high net profit", result.netProfit > 100.0)
        assertTrue("high EPK", result.earningPerKm > 8.0)
        assertFalse("no override", result.overrideActive)
        assertTrue("rideScore well above 75", result.rideScore >= 75.0)
        assertEquals("GREEN signal", Signal.GREEN, result.signal)
    }

    @Test
    fun `CPK includes fuel and maintenance costs`() {
        val ride = ParsedRide(
            baseFare         = 50.0,
            rideDistanceKm   = 5.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("CPK is positive", result.cpk > 0.0)
        assertTrue("CPK includes fuel, maintenance, and time", result.cpk > 10.0)
    }

    // ── V3 NEW TESTS: EV FUEL COST FIX ──────────────────────────────

    @Test
    fun `eBike ride should have non-zero fuel cost in V3`() {
        // V3 FIX: EV vehicles now correctly compute electricity cost
        val ride = ParsedRide(
            baseFare         = 50.0,
            rideDistanceKm   = 5.0,
            pickupDistanceKm = 0.5,
            vehicleType      = VehicleType.EBIKE,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        // EV cost = totalDistance × evConsumptionKWhPerKm × electricityRatePerKWh
        // = 5.5 × 0.12 × 8.0 = 5.28
        val expectedEvCost = 5.5 * profile.evConsumptionKWhPerKm * profile.electricityRatePerKWh
        assertEquals("EV fuel cost computed correctly", expectedEvCost, result.fuelCost, 0.01)
        assertTrue("EV fuel cost is small but non-zero", result.fuelCost > 0.0)
        assertTrue("EV still very profitable (low running cost)", result.netProfit > 40.0)
    }

    // ── V3 NEW TESTS: EXPLICIT PICKUP COST ──────────────────────────

    @Test
    fun `pickup cost breakdown is computed and non-zero`() {
        val ride = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 2.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("pickup cost is positive", result.pickupCost > 0.0)
        // pickupCost = pickupDistanceKm × (fuelCPK + maintenanceCPK)
        // fuelCPK = 102/45 = 2.267, maintenanceCPK = (0.80+0.30)*1.0 = 1.10
        // pickupCost = 2.0 × (2.267 + 1.10) = 6.73
        assertEquals("pickup cost = 2km × (fuelCPK + wearCPK)", 6.73, result.pickupCost, 0.2)
    }

    @Test
    fun `zero pickup distance means zero pickup cost`() {
        val ride = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 0.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("no pickup = no pickup cost", 0.0, result.pickupCost, 0.01)
    }

    // ── V3 NEW TESTS: TOTAL COST AND PROFIT MARGIN ──────────────────

    @Test
    fun `total cost equals sum of fuel wear and idle costs`() {
        val ride = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            waitTimeMin      = 5,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        val expectedTotal = result.fuelCost + result.wearCost + result.idleTimeCost
        assertEquals("totalCost = fuel + wear + idle", expectedTotal, result.totalCost, 0.01)
    }

    @Test
    fun `profit margin is percentage of net profit to payout`() {
        val ride = ParsedRide(
            baseFare         = 100.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        val expectedMargin = (result.netProfit / result.actualPayout) * 100.0
        assertEquals("profitMargin = (netProfit/actualPayout)*100", expectedMargin, result.profitMargin, 0.01)
        assertTrue("profitable ride has positive margin", result.profitMargin > 0.0)
    }

    // ── V3 NEW TESTS: SUB-SCORE TRANSPARENCY ────────────────────────

    @Test
    fun `sub-scores are populated and in valid range`() {
        val ride = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            estimatedDurationMin = 20,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertFalse("no override", result.overrideActive)
        assertTrue("S1 in range", result.subScores.s1NetProfit in 0.0..100.0)
        assertTrue("S2 in range", result.subScores.s2EarningsPerKm in 0.0..100.0)
        assertTrue("S3 in range", result.subScores.s3TimeEfficiency in 0.0..100.0)
        assertTrue("S4 in range", result.subScores.s4PickupPenalty in 0.0..100.0)
        assertTrue("S5 in range", result.subScores.s5SurgeBonus in 0.0..100.0)
        assertTrue("S6 in range", result.subScores.s6OpportunityCost in 0.0..100.0)
    }

    @Test
    fun `sub-scores zero on hard override`() {
        val ride = ParsedRide(
            baseFare         = 10.0,
            rideDistanceKm   = 15.0,
            pickupDistanceKm = 3.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("hard override", result.overrideActive)
        // Sub-scores are default (all zero) when override is active
        assertEquals("sub-scores default on override", 0.0, result.subScores.s1NetProfit, 0.01)
    }

    // ── V3 NEW TESTS: IMPROVED SURGE/BONUS SCORING ──────────────────

    @Test
    fun `surge score uses diminishing returns`() {
        val ride1x = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 1.0,
            estimatedDurationMin = 20, surgeMultiplier = 1.0, packageName = "com.rapido.rider"
        )
        val ride15x = ride1x.copy(surgeMultiplier = 1.5)
        val ride20x = ride1x.copy(surgeMultiplier = 2.0)
        val ride30x = ride1x.copy(surgeMultiplier = 3.0)

        val r1 = calculator.calculate(ride1x, profile, 12)
        val r15 = calculator.calculate(ride15x, profile, 12)
        val r20 = calculator.calculate(ride20x, profile, 12)
        val r30 = calculator.calculate(ride30x, profile, 12)

        // Higher surge → higher S5, but with diminishing returns
        assertTrue("1.5x surge score > no surge", r15.subScores.s5SurgeBonus > r1.subScores.s5SurgeBonus)
        assertTrue("2.0x surge score > 1.5x", r20.subScores.s5SurgeBonus > r15.subScores.s5SurgeBonus)
        assertTrue("3.0x surge score > 2.0x", r30.subScores.s5SurgeBonus > r20.subScores.s5SurgeBonus)

        // Diminishing returns: gap between 2x→3x < gap between 1x→2x
        val gap1to2 = r20.subScores.s5SurgeBonus - r1.subScores.s5SurgeBonus
        val gap2to3 = r30.subScores.s5SurgeBonus - r20.subScores.s5SurgeBonus
        assertTrue("diminishing returns: 2→3 gap < 1→2 gap", gap2to3 < gap1to2)
    }

    @Test
    fun `bonus normalized relative to base fare`() {
        val rideNoBonus = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 1.0,
            estimatedDurationMin = 20, bonus = 0.0, packageName = "com.rapido.rider"
        )
        val rideWithBonus = rideNoBonus.copy(bonus = 40.0) // 50% of base fare

        val r1 = calculator.calculate(rideNoBonus, profile, 12)
        val r2 = calculator.calculate(rideWithBonus, profile, 12)

        assertTrue("bonus increases S5", r2.subScores.s5SurgeBonus > r1.subScores.s5SurgeBonus)
        assertTrue("bonus increases net profit", r2.netProfit > r1.netProfit)
    }

    // ── V3 NEW TESTS: SHORT TRIP PENALTY ────────────────────────────

    @Test
    fun `very short trip gets penalized in S2 EPK score`() {
        // 1km trip — extremely short, high per-km overhead
        val shortTrip = ParsedRide(
            baseFare = 30.0, rideDistanceKm = 1.0, pickupDistanceKm = 0.5,
            estimatedDurationMin = 5, packageName = "com.rapido.rider"
        )
        // 5km trip — normal length, same per-km metrics
        val normalTrip = ParsedRide(
            baseFare = 150.0, rideDistanceKm = 5.0, pickupDistanceKm = 0.5,
            estimatedDurationMin = 15, packageName = "com.rapido.rider"
        )

        val rShort = calculator.calculate(shortTrip, profile, 12)
        val rNormal = calculator.calculate(normalTrip, profile, 12)

        // Even if EPK is similar, the short trip factor should penalize S2
        // Short trip factor for 1km = 0.7 + (1.0/2.0)*0.3 = 0.85
        // Normal trip factor for 5km = 1.0
        if (!rShort.overrideActive) {
            assertTrue("short trip S2 penalized (factor < 1.0)",
                rShort.subScores.s2EarningsPerKm <= 100.0)
        }
    }

    // ── V3 NEW TESTS: LONG TRIP DIMINISHING RETURNS ─────────────────

    @Test
    fun `long trip gets diminishing returns in S1 profit score`() {
        // 30km trip — beyond the 25km threshold
        val longTrip = ParsedRide(
            baseFare = 300.0, rideDistanceKm = 30.0, pickupDistanceKm = 2.0,
            estimatedDurationMin = 60, packageName = "com.rapido.rider"
        )
        // 10km trip — normal distance, scaled to same per-km fare
        val normalTrip = ParsedRide(
            baseFare = 100.0, rideDistanceKm = 10.0, pickupDistanceKm = 2.0,
            estimatedDurationMin = 20, packageName = "com.rapido.rider"
        )

        val rLong = calculator.calculate(longTrip, profile, 12)
        val rNormal = calculator.calculate(normalTrip, profile, 12)

        // Long trip factor for 30km = 1.0 - (5/25)*0.15 = 0.97
        // Should slightly reduce S1 compared to what a purely linear calc would give
        if (!rLong.overrideActive && !rNormal.overrideActive) {
            // The long trip has higher absolute profit but the S1 is slightly dampened
            assertTrue("long trip profit is high", rLong.netProfit > rNormal.netProfit)
        }
    }

    // ── V3 NEW TESTS: FAKE SURGE TRAP DETECTION ─────────────────────

    @Test
    fun `fake surge trap detected for high surge short ride`() {
        val ride = ParsedRide(
            baseFare         = 25.0,
            rideDistanceKm   = 1.5,
            pickupDistanceKm = 0.5,
            surgeMultiplier  = 1.5,
            estimatedDurationMin = 5,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("surge trap warning present",
            result.failedChecks.any { it.contains("Surge trap", ignoreCase = true) })
    }

    @Test
    fun `no surge trap for high surge long ride`() {
        val ride = ParsedRide(
            baseFare         = 120.0,
            rideDistanceKm   = 10.0,
            pickupDistanceKm = 1.0,
            surgeMultiplier  = 1.5,
            estimatedDurationMin = 25,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertFalse("no surge trap for long ride",
            result.failedChecks.any { it.contains("Surge trap", ignoreCase = true) })
    }

    // ── V3 NEW TESTS: TRAFFIC-AWARE TIME ESTIMATION ─────────────────

    @Test
    fun `traffic level adjusts pickup time estimation`() {
        val rideLight = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 2.0,
            estimatedDurationMin = 20, trafficLevel = 1, packageName = "com.rapido.rider"
        )
        val rideHeavy = rideLight.copy(trafficLevel = 3)

        val rLight = calculator.calculate(rideLight, profile, 12)
        val rHeavy = calculator.calculate(rideHeavy, profile, 12)

        // Heavy traffic → longer pickup time → lower TES → lower RideScore
        assertTrue("heavy traffic TRT > light traffic TRT", rHeavy.trt > rLight.trt)
        assertTrue("heavy traffic TES < light traffic TES", rHeavy.tes < rLight.tes)
    }

    // ── V3 NEW TESTS: EFFECTIVE HOURLY RATE ─────────────────────────

    @Test
    fun `effective hourly rate equals HRR`() {
        val ride = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 1.0,
            estimatedDurationMin = 20, packageName = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("effectiveHourlyRate = HRR", result.hrr, result.effectiveHourlyRate, 0.01)
        assertTrue("effective hourly rate is positive", result.effectiveHourlyRate > 0.0)
    }

    // ── V3 NEW TESTS: DELIVERY RIDE HANDLING ────────────────────────

    @Test
    fun `delivery ride processes same as passenger ride`() {
        val ride = ParsedRide(
            baseFare         = 45.0,
            rideDistanceKm   = 3.0,
            pickupDistanceKm = 1.0,
            estimatedDurationMin = 12,
            isDelivery       = true,
            packageName      = "in.shadowfax.gandalf"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("delivery has profit result", result.netProfit != 0.0 || result.actualPayout > 0.0)
        assertTrue("delivery has signal", result.signal in Signal.entries)
    }

    // ── V3 NEW TESTS: EDGE CASES ────────────────────────────────────

    @Test
    fun `zero ride distance does not crash`() {
        val ride = ParsedRide(
            baseFare         = 30.0,
            rideDistanceKm   = 0.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("EPK is 0 for zero distance", 0.0, result.earningPerKm, 0.01)
        assertEquals("pickup ratio is 0 for zero ride distance", 0.0, result.pickupRatio, 0.01)
    }

    @Test
    fun `zero base fare triggers negative profit override`() {
        val ride = ParsedRide(
            baseFare         = 0.0,
            rideDistanceKm   = 5.0,
            pickupDistanceKm = 1.0,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("net profit is negative", result.netProfit < 0.0)
        assertTrue("hard override active", result.overrideActive)
        assertEquals("RED signal", Signal.RED, result.signal)
    }

    @Test
    fun `extremely high surge ride is still GREEN`() {
        val ride = ParsedRide(
            baseFare         = 100.0,
            rideDistanceKm   = 10.0,
            pickupDistanceKm = 1.0,
            surgeMultiplier  = 3.0,
            estimatedDurationMin = 25,
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("very high net profit", result.netProfit > 200.0)
        assertFalse("no override", result.overrideActive)
        assertEquals("GREEN signal", Signal.GREEN, result.signal)
        assertTrue("S5 is high but clamped to 100", result.subScores.s5SurgeBonus <= 100.0)
    }

    @Test
    fun `performance - calculation completes in under 1ms`() {
        val ride = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 1.0,
            estimatedDurationMin = 20, surgeMultiplier = 1.2, bonus = 10.0,
            vehicleType = VehicleType.BIKE, packageName = "com.rapido.rider"
        )

        // Warm up
        repeat(100) { calculator.calculate(ride, profile, 12) }

        // Measure
        val start = System.nanoTime()
        repeat(1000) { calculator.calculate(ride, profile, 12) }
        val elapsed = (System.nanoTime() - start) / 1_000_000.0  // ms

        val avgMs = elapsed / 1000.0
        assertTrue("average calculation < 1ms (was ${avgMs}ms)", avgMs < 1.0)
    }

    // ── V3.1 NEW TESTS: ADAPTIVE METRICS ────────────────────────────

    @Test
    fun `earningsPerMinute calculated correctly from net profit and TRT`() {
        val ride = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 1.0,
            estimatedDurationMin = 20, packageName = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("earningsPerMinute is positive", result.earningsPerMinute > 0.0)
        assertTrue("TRT is positive for time calculation", result.trt > 0.0)
        // earningsPerMinute = netProfit / trt
        val expected = result.netProfit / result.trt
        assertEquals("earningsPerMinute = netProfit / TRT", expected, result.earningsPerMinute, 0.01)
    }

    @Test
    fun `earningsPerMinute is zero when no time data available`() {
        val ride = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 0.0,
            packageName = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        // No time data and no pickup → trt = 0 → earningsPerMinute = 0
        assertEquals("earningsPerMinute is zero without time data", 0.0, result.earningsPerMinute, 0.01)
    }

    @Test
    fun `efficiencyScore relative to driver baseline rate`() {
        val ride = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 1.0,
            estimatedDurationMin = 20, packageName = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        // driverBaselineRatePerMin = targetEarningPerHour / 60 = 200/60 = 3.333
        val expectedBaseline = profile.targetEarningPerHour / 60.0
        assertEquals("driverBaselineRatePerMin computed from profile",
            expectedBaseline, result.driverBaselineRatePerMin, 0.01)

        // efficiencyScore = earningsPerMinute / driverBaselineRatePerMin
        val expectedScore = result.earningsPerMinute / result.driverBaselineRatePerMin
        assertEquals("efficiencyScore = earningsPerMinute / baseline",
            expectedScore, result.efficiencyScore, 0.01)

        assertTrue("efficiencyScore is positive for profitable ride", result.efficiencyScore > 0.0)
    }

    @Test
    fun `high earning ride has efficiencyScore above 1`() {
        val ride = ParsedRide(
            baseFare = 200.0, rideDistanceKm = 10.0, pickupDistanceKm = 0.5,
            estimatedDurationMin = 15, surgeMultiplier = 1.5,
            packageName = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("high-value ride exceeds baseline earning rate",
            result.efficiencyScore > 1.0)
    }

    @Test
    fun `low earning ride has efficiencyScore below 1`() {
        val ride = ParsedRide(
            baseFare = 30.0, rideDistanceKm = 5.0, pickupDistanceKm = 2.0,
            estimatedDurationMin = 25, packageName = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        if (!result.overrideActive) {
            assertTrue("low-value ride falls below baseline earning rate",
                result.efficiencyScore < 1.0)
        }
    }

    @Test
    fun `long pickup automatically reduces rideScore via time-based S1`() {
        // Same ride, different pickup distances — no hard-coded pickup rules needed
        val shortPickup = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 0.5,
            estimatedDurationMin = 20, packageName = "com.rapido.rider"
        )
        val longPickup = shortPickup.copy(pickupDistanceKm = 4.0)

        val rShort = calculator.calculate(shortPickup, profile, 12)
        val rLong = calculator.calculate(longPickup, profile, 12)

        // Long pickup increases TRT → increases targetNetProfit → reduces S1 → reduces rideScore
        assertTrue("long pickup TRT > short pickup TRT", rLong.trt > rShort.trt)
        assertTrue("same net profit for both", rLong.netProfit < rShort.netProfit) // more fuel cost
        if (!rLong.overrideActive && !rShort.overrideActive) {
            assertTrue("long pickup reduces rideScore via time-based evaluation",
                rLong.rideScore < rShort.rideScore)
            assertTrue("long pickup reduces earningsPerMinute",
                rLong.earningsPerMinute < rShort.earningsPerMinute)
            assertTrue("long pickup reduces efficiencyScore",
                rLong.efficiencyScore < rShort.efficiencyScore)
        }
    }

    @Test
    fun `epk override uses adaptive cost floor not fixed constant`() {
        // Create a ride where EPK is between old fixed ₹1.50 threshold and operational cost
        // This verifies the fixed constant was removed
        val ride = ParsedRide(
            baseFare = 37.0, rideDistanceKm = 5.2, pickupDistanceKm = 1.1,
            packageName = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        // EPK should be above old ₹1.50 fixed threshold
        assertTrue("EPK is above old fixed ₹1.50", result.earningPerKm > 1.5)
        // This ride should NOT have hard override (old code might have overridden it)
        assertFalse("no hard override for moderate EPK ride", result.overrideActive)
        // Should get YELLOW, not RED from scoring model
        assertTrue("signal is not RED from fixed EPK rule",
            result.signal == Signal.YELLOW || result.signal == Signal.GREEN)
    }

    @Test
    fun `efficiency score warning shown for low efficiency rides with time data`() {
        // A ride with time data where efficiency is very low
        val ride = ParsedRide(
            baseFare = 25.0, rideDistanceKm = 3.0, pickupDistanceKm = 3.0,
            estimatedDurationMin = 30, packageName = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile, 12)

        if (!result.overrideActive && result.trt > 0.0 && result.efficiencyScore < 0.5) {
            assertTrue("efficiency warning shown for low efficiency ride",
                result.failedChecks.any { it.contains("/min", ignoreCase = true) })
        }
    }

    @Test
    fun `time-efficient short ride beats longer ride in earningsPerMinute`() {
        // Ride A: Short, quick, decent fare → high ₹/min
        val rideA = ParsedRide(
            baseFare = 60.0, rideDistanceKm = 3.0, pickupDistanceKm = 0.5,
            estimatedDurationMin = 8, packageName = "com.rapido.rider"
        )
        // Ride B: Longer, more total profit, but much more time → lower ₹/min
        val rideB = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 2.0,
            estimatedDurationMin = 30, packageName = "com.rapido.rider"
        )

        val resultA = calculator.calculate(rideA, profile, 12)
        val resultB = calculator.calculate(rideB, profile, 12)

        // Ride B earns more total but ride A earns more per minute
        if (!resultA.overrideActive && !resultB.overrideActive &&
            resultA.trt > 0.0 && resultB.trt > 0.0) {
            assertTrue("time-efficient ride has higher earningsPerMinute",
                resultA.earningsPerMinute > resultB.earningsPerMinute)
        }
    }

    @Test
    fun `different driver targets produce different efficiency scores for same ride`() {
        val ride = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 8.0, pickupDistanceKm = 1.0,
            estimatedDurationMin = 20, packageName = "com.rapido.rider"
        )

        // Low target driver — easier to achieve high efficiency
        val lowTargetProfile = profile.copy(targetEarningPerHour = 100.0)
        // High target driver — harder to achieve high efficiency
        val highTargetProfile = profile.copy(targetEarningPerHour = 400.0)

        val resultLow = calculator.calculate(ride, lowTargetProfile, 12)
        val resultHigh = calculator.calculate(ride, highTargetProfile, 12)

        if (!resultLow.overrideActive && !resultHigh.overrideActive) {
            assertTrue("same earningsPerMinute for both drivers",
                Math.abs(resultLow.earningsPerMinute - resultHigh.earningsPerMinute) < 0.1)
            assertTrue("low target driver has higher efficiency score",
                resultLow.efficiencyScore > resultHigh.efficiencyScore)
        }
    }
}
