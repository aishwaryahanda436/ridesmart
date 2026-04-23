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
            minAcceptablePerKm        = 3.50,   // net ₹/km — corrected from 15.0
            targetEarningPerHour      = 200.0,
            platformCommissionPercent = 0.0
        )
    }

    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `rapido basic ride low fare should be YELLOW`() {
        // Real popup: ₹37, 5.2km ride, 1.1km pickup
        val ride = ParsedRide(
            baseFare         = 37.0,
            rideDistanceKm   = 5.2,
            pickupDistanceKm = 1.1,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile)

        assertEquals("totalFare", 37.0, result.totalFare, 0.01)
        assertTrue("netProfit should be positive", result.netProfit > 0.0)
        assertTrue("netProfit below 30 minimum", result.netProfit < 30.0)
        assertEquals("2 failed checks → YELLOW", Signal.YELLOW, result.signal)
    }

    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `rapido ride with tip should be GREEN`() {
        val ride = ParsedRide(
            baseFare         = 76.0,
            tipAmount        = 10.0,
            rideDistanceKm   = 12.1,
            pickupDistanceKm = 0.8,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile)

        assertEquals("totalFare includes tip", 86.0, result.totalFare, 0.01)
        assertTrue("netProfit above 30", result.netProfit > 30.0)
    }

    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `uber ride with premium earningPerHour should be calculated`() {
        val ride = ParsedRide(
            baseFare         = 74.40,
            premiumAmount    = 18.0,
            rideDistanceKm   = 7.4,
            pickupDistanceKm = 1.1,
            estimatedDurationMin = 17,
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, profile)

        assertEquals("totalFare includes premium", 92.40, result.totalFare, 0.01)
        assertTrue("netProfit above 30", result.netProfit > 30.0)
        assertTrue("earningPerHour calculated and not zero", result.earningPerHour > 0.0)
    }

    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `very long pickup should be YELLOW or RED`() {
        val ride = ParsedRide(
            baseFare         = 40.0,
            rideDistanceKm   = 3.0,
            pickupDistanceKm = 2.5,
            packageName      = "com.ubercab.driver"
        )
        val result = calculator.calculate(ride, profile)

        assertNotEquals("Should not be GREEN", Signal.GREEN, result.signal)
    }

    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `CNG auto ride should use CNG price not petrol price`() {
        val ride = ParsedRide(
            baseFare         = 80.0,
            rideDistanceKm   = 6.0,
            pickupDistanceKm = 1.0,
            vehicleType      = VehicleType.CNG_AUTO,
            packageName      = "com.olacabs.oladriver"
        )
        val result = calculator.calculate(ride, profile)

        assertEquals("Fuel cost uses CNG price", 21.25, result.fuelCost, 0.5)
    }

    // ─────────────────────────────────────────────────────────────────
    @Test
    fun `eBike ride should have zero fuel cost`() {
        val ride = ParsedRide(
            baseFare         = 50.0,
            rideDistanceKm   = 5.0,
            pickupDistanceKm = 0.5,
            vehicleType      = VehicleType.EBIKE,
            packageName      = "com.rapido.rider"
        )
        val result = calculator.calculate(ride, profile)

        assertEquals("Electric vehicle has zero fuel cost", 0.0, result.fuelCost, 0.01)
    }

    @Test
    fun `increasing pickup distance decreases efficiency and profitability`() {
        val profile = RiderProfile()
        val calculator = ProfitCalculator()

        fun makeRide(pickupKm: Double) = ParsedRide(
            baseFare             = 60.0,
            tipAmount            = 0.0,
            premiumAmount        = 0.0,
            pickupDistanceKm     = pickupKm,
            rideDistanceKm       = 5.0,
            estimatedDurationMin = 20,
            vehicleType          = VehicleType.BIKE,
            packageName          = "com.rapido.rider"
        )

        val r05 = calculator.calculate(makeRide(0.5), profile)
        val r10 = calculator.calculate(makeRide(1.0), profile)
        val r15 = calculator.calculate(makeRide(1.5), profile)
        val r25 = calculator.calculate(makeRide(2.5), profile)

        // efficiencyPerKm should decrease as pickup increases (more unpaid km = higher cost ratio)
        assertTrue("0.5km pickup should give higher efficiency than 1.0km", r05.efficiencyPerKm > r10.efficiencyPerKm)
        assertTrue("1.0km pickup should give higher efficiency than 1.5km", r10.efficiencyPerKm > r15.efficiencyPerKm)
        assertTrue("1.5km pickup should give higher efficiency than 2.5km", r15.efficiencyPerKm > r25.efficiencyPerKm)

        // netProfit should also decrease as more total distance is covered
        assertTrue("Shorter pickup should yield more net profit", r05.netProfit > r15.netProfit)
        assertTrue("1.0km pickup more profitable than 2.5km pickup", r10.netProfit > r25.netProfit)

        // pickupRatio should increase as pickup distance grows
        assertTrue("pickupRatio grows with pickup distance", r05.pickupRatio < r10.pickupRatio)
        assertTrue("pickupRatio grows with pickup distance", r10.pickupRatio < r15.pickupRatio)
    }
}
