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

    // ── EXISTING TESTS (UPDATED FOR RIDESCORE) ──────────────────────

    @Test
    fun `rapido basic ride low fare should be YELLOW`() {
        // ₹37, 5.2km ride, 1.1km pickup — low fare but still above hard overrides
        val ride = ParsedRide(
            baseFare         = 37.0,
            rideDistanceKm   = 5.2,
            pickupDistanceKm = 1.1,
            packageName      = "in.rapido.captain"
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
        // ₹76 + ₹10 tip, 12.1km ride, 0.8km pickup — profitable but no time data
        // RideScore is limited without time data (TES defaults to neutral 50)
        val ride = ParsedRide(
            baseFare         = 76.0,
            tipAmount        = 10.0,
            rideDistanceKm   = 12.1,
            pickupDistanceKm = 0.8,
            packageName      = "in.rapido.captain"
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
        // ₹74.40 + ₹18 premium, 7.4km ride, 1.1km pickup, 17 mins
        // With time data, TES is computed properly → higher RideScore
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
        // ₹40 fare, 3.0km ride, 2.5km pickup → PPR = 0.833 > 0.80 → hard override
        val ride = ParsedRide(
            baseFare         = 40.0,
            rideDistanceKm   = 3.0,
            pickupDistanceKm = 2.5,
            packageName      = "in.rapido.captain"
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
        // ₹55 fare, 4.0km ride, 1.0km pickup — profitable but no time data
        // RideScore ~73.75 at midday → just below GREEN threshold
        val ride = ParsedRide(
            baseFare         = 55.0,
            rideDistanceKm   = 4.0,
            pickupDistanceKm = 1.0,
            packageName      = "in.rapido.captain"
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
            packageName      = "in.rapido.captain"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("Fuel cost uses CNG price", 21.25, result.fuelCost, 0.5)
        assertTrue("netProfit above 30", result.netProfit > 30.0)
    }

    @Test
    fun `eBike ride should have zero fuel cost`() {
        val ride = ParsedRide(
            baseFare         = 50.0,
            rideDistanceKm   = 5.0,
            pickupDistanceKm = 0.5,
            vehicleType      = VehicleType.EBIKE,
            packageName      = "in.rapido.captain"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertEquals("Electric vehicle has zero fuel cost", 0.0, result.fuelCost, 0.01)
        assertTrue("netProfit higher due to zero fuel", result.netProfit > 45.0)
    }

    // ── NEW TESTS FOR RIDESCORE FEATURES ────────────────────────────

    @Test
    fun `surge multiplier should boost fare and RideScore`() {
        val rideNoSurge = ParsedRide(
            baseFare         = 60.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            estimatedDurationMin = 20,
            packageName      = "in.rapido.captain"
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
        // Very low fare with long distance → negative profit
        val ride = ParsedRide(
            baseFare         = 10.0,
            rideDistanceKm   = 15.0,
            pickupDistanceKm = 3.0,
            packageName      = "in.rapido.captain"
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
            packageName      = "in.rapido.captain"
        )

        val resultNoSub = calculator.calculate(ride, profile, 12)
        val resultWithSub = calculator.calculate(ride, profileWithSub, 12)

        // Subscription amortised: 100/10 = ₹10 per trip
        assertTrue("subscription reduces payout", resultWithSub.actualPayout < resultNoSub.actualPayout)
        assertEquals("payout reduced by ₹10", resultNoSub.actualPayout - 10.0, resultWithSub.actualPayout, 0.01)
    }

    @Test
    fun `idle time cost should reduce net profit and RideScore`() {
        val rideNoWait = ParsedRide(
            baseFare         = 60.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 1.0,
            packageName      = "in.rapido.captain"
        )
        val rideWithWait = rideNoWait.copy(waitTimeMin = 10)

        val resultNoWait = calculator.calculate(rideNoWait, profile, 12)
        val resultWithWait = calculator.calculate(rideWithWait, profile, 12)

        // Idle cost = (10/60) × 200 = ₹33.33
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

        // DIESEL_CAR: mileage=18 km/l, diesel price=87.67
        // totalDist = 11.5, fuelUnitsUsed = 11.5/18 = 0.639
        // fuelCost = 0.639 × 87.67 = 56.01
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
            packageName      = "in.rapido.captain"
        )

        val resultMorning = calculator.calculate(ride, profile, 7)   // Morning rush
        val resultMidday  = calculator.calculate(ride, profile, 12)  // Midday lull
        val resultNight   = calculator.calculate(ride, profile, 20)  // Night

        // Same ride, different weights → different scores
        assertEquals("same net profit", resultMorning.netProfit, resultMidday.netProfit, 0.01)
        assertTrue("morning score computed", resultMorning.rideScore > 0.0)
        assertTrue("midday score computed", resultMidday.rideScore > 0.0)
        assertTrue("night score computed", resultNight.rideScore > 0.0)
        // Weights differ across time bands so scores differ for non-uniform sub-scores
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
            packageName      = "in.rapido.captain"
        )
        val result = calculator.calculate(ride, profile, 12)

        // pickupTimeMin = (2.0/25) × 60 × 1.3 = 6.24 min
        // tripTimeMin = 25
        // trt = 6.24 + 25 + 0 = 31.24
        // tes = (25/31.24) × 100 = 80.0
        assertTrue("TRT includes pickup and trip time", result.trt > 25.0)
        assertTrue("TES between 0 and 100", result.tes in 0.0..100.0)
        assertTrue("TES shows good efficiency", result.tes > 70.0)
        assertTrue("HRR computed", result.hrr > 0.0)
    }

    @Test
    fun `min viable fare triggers hard override for very cheap long ride`() {
        // Very low fare for a long distance — fare below operational cost × 1.25
        val ride = ParsedRide(
            baseFare         = 15.0,
            rideDistanceKm   = 8.0,
            pickupDistanceKm = 2.0,
            packageName      = "in.rapido.captain"
        )
        val result = calculator.calculate(ride, profile, 12)

        assertTrue("hard override active", result.overrideActive)
        assertEquals("hard override → RED", Signal.RED, result.signal)
    }

    @Test
    fun `high value ride with surge and time data should be GREEN`() {
        // Premium ride: ₹120 base, 1.3× surge, ₹15 bonus, 12km, 25min
        val ride = ParsedRide(
            baseFare         = 120.0,
            surgeMultiplier  = 1.3,
            bonus            = 15.0,
            rideDistanceKm   = 12.0,
            pickupDistanceKm = 1.5,
            estimatedDurationMin = 25,
            packageName      = "in.rapido.captain"
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
            packageName      = "in.rapido.captain"
        )
        val result = calculator.calculate(ride, profile, 12)

        // CPK = fuelCPK + maintenanceCPK + timeCostPerKm × congestion
        // fuelCPK = 102/45 = 2.267
        // maintenanceCPK = (0.80+0.30) × 1.0 = 1.10
        // timeCostPerKm = 200/25 = 8.0
        // cpk = 2.267 + 1.10 + 8.0 × 1.3 = 13.77
        assertTrue("CPK is positive", result.cpk > 0.0)
        assertTrue("CPK includes fuel, maintenance, and time", result.cpk > 10.0)
    }
}
