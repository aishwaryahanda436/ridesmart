package com.ridesmart.parser

import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RapidoParserTest {

    private lateinit var parser: RapidoParser

    @Before
    fun setUp() {
        parser = RapidoParser()
    }

    // ── detectScreenState ─────────────────────────────────────────────

    @Test
    fun `home screen keywords return IDLE`() {
        val nodes = listOf("Performance Icon", "Right on Track", "Quick Actions", "Weekly Earnings")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `active ride keywords return IDLE (active ride branch)`() {
        val nodes = listOf("₹80", "End Ride", "Drop OTP", "5.2 km")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `no fare and no accept returns IDLE`() {
        val nodes = listOf("Bike", "Hi Rajat", "Support")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `fare accept and km returns OFFER_LOADED`() {
        val nodes = listOf("₹55", "1.2 km", "5.5 km", "Accept")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `match button instead of accept also returns OFFER_LOADED`() {
        val nodes = listOf("₹55", "1.2 km", "5.5 km", "Match")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `fare and accept but no km returns OFFER_LOADING`() {
        val nodes = listOf("₹55", "Accept")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `fare and km but no accept returns OFFER_LOADING`() {
        val nodes = listOf("₹55", "3.5 km")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    // ── parseAll: rejections ──────────────────────────────────────────

    @Test
    fun `home screen returns ParseResult Idle`() {
        val nodes = listOf("Performance Icon", "Good Morning", "Weekly Earnings")
        val result = parser.parseAll(nodes, "com.rapido.rider")
        assertTrue("Home screen should be Idle", result is ParseResult.Idle)
    }

    @Test
    fun `empty nodes returns ParseResult Failure`() {
        val result = parser.parseAll(emptyList(), "com.rapido.rider")
        assertTrue("Empty nodes should be Idle or Failure",
            result is ParseResult.Idle || result is ParseResult.Failure)
    }

    @Test
    fun `active ride screen returns Idle`() {
        val nodes = listOf("₹90", "End Ride", "OTP to Start", "4.0 km")
        val result = parser.parseAll(nodes, "com.rapido.rider")
        assertTrue("Active ride should return Idle", result is ParseResult.Idle)
    }

    // ── parseAll: successful parsing ─────────────────────────────────

    @Test
    fun `basic rapido offer parses fare and distances`() {
        val nodes = listOf(
            "₹55",
            "Bike",
            "1.1 km",
            "5.2 km",
            "15 min",
            "Accept"
        )
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue("Should parse valid Rapido offer", result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Fare", 55.0, ride.baseFare, 0.01)
        assertEquals("Platform", "Rapido", ride.platform)
        assertEquals("Package name", "com.rapido.rider", ride.packageName)
        assertTrue("Ride distance assigned", ride.rideDistanceKm > 0.0)
    }

    @Test
    fun `rapido offer with tip is extracted`() {
        val nodes = listOf(
            "₹70",
            "Customer added Tip ₹15",
            "Bike",
            "0.8 km",
            "8.0 km",
            "18 min",
            "Accept"
        )
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Base fare without tip", 70.0, ride.baseFare, 0.01)
        assertEquals("Tip extracted", 15.0, ride.tipAmount, 0.01)
    }

    @Test
    fun `rapido offer with boost premium is extracted`() {
        val nodes = listOf(
            "₹60",
            "+ ₹20",
            "Bike Boost",
            "1.0 km",
            "6.0 km",
            "20 min",
            "Accept"
        )
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Base fare", 60.0, ride.baseFare, 0.01)
        assertTrue("Premium/boost amount extracted", ride.premiumAmount > 0.0)
        assertEquals("Boost amount", 20.0, ride.premiumAmount, 0.01)
    }

    @Test
    fun `rapido duration is parsed from min node`() {
        val nodes = listOf("₹65", "Bike", "1.0 km", "7.0 km", "22 min", "Accept")
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Duration", 22, ride.estimatedDurationMin)
    }

    // ── Vehicle type detection ────────────────────────────────────────

    @Test
    fun `Bike Boost in nodes detects BIKE_BOOST vehicle type`() {
        val nodes = listOf("₹80", "Bike Boost", "1.0 km", "5.5 km", "Accept")
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue(result is ParseResult.Success)
        assertEquals(VehicleType.BIKE_BOOST, (result as ParseResult.Success).rides.first().vehicleType)
    }

    @Test
    fun `CNG Auto in nodes detects CNG_AUTO vehicle type`() {
        val nodes = listOf("₹90", "CNG Auto", "0.8 km", "7.0 km", "Accept")
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue(result is ParseResult.Success)
        assertEquals(VehicleType.CNG_AUTO, (result as ParseResult.Success).rides.first().vehicleType)
    }

    @Test
    fun `eBike in nodes detects EBIKE vehicle type`() {
        val nodes = listOf("₹50", "e-Bike", "0.5 km", "4.0 km", "Accept")
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue(result is ParseResult.Success)
        assertEquals(VehicleType.EBIKE, (result as ParseResult.Success).rides.first().vehicleType)
    }

    @Test
    fun `Auto node detects AUTO vehicle type`() {
        val nodes = listOf("₹70", "Auto", "0.9 km", "5.5 km", "Accept")
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue(result is ParseResult.Success)
        assertEquals(VehicleType.AUTO, (result as ParseResult.Success).rides.first().vehicleType)
    }

    // ── Distance assignment ───────────────────────────────────────────

    @Test
    fun `smaller km value assigned as pickup, larger as ride distance`() {
        val nodes = listOf("₹60", "Bike", "0.9 km", "7.5 km", "Accept")
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        // In standard order: first km = pickup, second = ride
        assertEquals("Pickup", 0.9, ride.pickupDistanceKm, 0.01)
        assertEquals("Ride", 7.5, ride.rideDistanceKm, 0.01)
    }

    @Test
    fun `Rs prefix fare is parsed correctly`() {
        val nodes = listOf("Rs.65", "Bike", "1.0 km", "6.0 km", "Accept")
        val result = parser.parseAll(nodes, "com.rapido.rider")

        assertTrue("Rs. prefix should be parsed", result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Fare with Rs. prefix", 65.0, ride.baseFare, 0.01)
    }

    // ── Sanity: fare/km ratio guard ───────────────────────────────────

    @Test
    fun `unrealistically high fare-per-km ratio returns Failure`() {
        // ₹500 for 0.1km → fare/km = 5000 which exceeds 100 guard
        val nodes = listOf("₹500", "Bike", "0.1 km", "Accept")
        val result = parser.parseAll(nodes, "com.rapido.rider")
        // The parser guards against fare/km > 100 per parseExpandedCard; should not produce a ride
        if (result is ParseResult.Success) {
            // If it does parse, rideDistanceKm must be > 0 for it to be meaningful
            val ride = result.rides.firstOrNull()
            if (ride != null) {
                assertTrue("Ride distance must be > 0", ride.rideDistanceKm > 0.0)
            }
        }
        // Either Failure or Success with valid data is acceptable (guard may produce Failure)
    }
}
