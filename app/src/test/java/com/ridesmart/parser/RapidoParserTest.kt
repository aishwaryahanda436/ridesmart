package com.ridesmart.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

/**
 * Unit tests for RapidoParser.
 * Tests ride offer parsing for Rapido bike-taxi accessibility nodes.
 */
class RapidoParserTest {

    private lateinit var parser: RapidoParser

    @Before
    fun setUp() {
        parser = RapidoParser()
    }

    // ── Screen state detection ───────────────────────────────────────

    @Test
    fun `active ride keywords return ACTIVE_RIDE`() {
        val nodes = listOf("₹80", "End Ride", "3.0 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare accept and km returns OFFER_LOADED`() {
        val nodes = listOf("₹65", "1.2 km", "4.5 km", "Accept", "12 min")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare and accept but no km returns OFFER_LOADING`() {
        val nodes = listOf("₹65", "Accept")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `no fare and no accept returns IDLE`() {
        val nodes = listOf("Settings", "Profile", "Help")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    // ── Vehicle type detection ───────────────────────────────────────

    @Test
    fun `Bike Boost detected as BIKE_BOOST`() {
        val nodes = listOf("Bike Boost", "₹80", "3.0 km", "Accept")
        assertEquals(VehicleType.BIKE_BOOST, parser.detectVehicleType(nodes))
    }

    @Test
    fun `CNG Auto detected as CNG_AUTO`() {
        val nodes = listOf("CNG Auto", "₹90", "3.0 km", "Accept")
        assertEquals(VehicleType.CNG_AUTO, parser.detectVehicleType(nodes))
    }

    @Test
    fun `e-Bike detected as EBIKE`() {
        val nodes = listOf("e-Bike", "₹50", "3.0 km", "Accept")
        assertEquals(VehicleType.EBIKE, parser.detectVehicleType(nodes))
    }

    @Test
    fun `Auto detected as AUTO`() {
        val nodes = listOf("Auto", "₹90", "3.0 km", "Accept")
        assertEquals(VehicleType.AUTO, parser.detectVehicleType(nodes))
    }

    @Test
    fun `Bike detected as BIKE`() {
        val nodes = listOf("Bike", "₹55", "3.0 km", "Accept")
        assertEquals(VehicleType.BIKE, parser.detectVehicleType(nodes))
    }

    @Test
    fun `Car detected as CAR`() {
        val nodes = listOf("Car", "₹180", "6.0 km", "Accept")
        assertEquals(VehicleType.CAR, parser.detectVehicleType(nodes))
    }

    // ── Single-pass parseExpandedCard tests ─────────────────────────

    @Test
    fun `basic Rapido bike offer parses correctly`() {
        val nodes = listOf(
            "Bike",
            "₹65",
            "1.2 km",
            "4.5 km",
            "12 min",
            "Accept",
            "MG Road, Bangalore"
        )
        val result = parser.parseExpandedCard(nodes, "com.rapido.captain")

        assertNotNull("Should parse valid Rapido offer", result)
        assertEquals("Fare", 65.0, result!!.baseFare, 0.01)
        assertEquals("Pickup distance", 1.2, result.pickupDistanceKm, 0.01)
        assertEquals("Ride distance", 4.5, result.rideDistanceKm, 0.01)
        assertEquals("Duration", 12, result.estimatedDurationMin)
        assertEquals("Package", "com.rapido.captain", result.packageName)
    }

    @Test
    fun `Rapido Bike Boost offer extracts boost amount`() {
        val nodes = listOf(
            "Bike Boost",
            "₹80",
            "+₹15",
            "1.0 km",
            "5.0 km",
            "10 min",
            "Accept"
        )
        val result = parser.parseExpandedCard(nodes, "com.rapido.captain")

        assertNotNull("Should parse Bike Boost offer", result)
        assertEquals("Base fare", 80.0, result!!.baseFare, 0.01)
        assertEquals("Boost amount", 15.0, result.premiumAmount, 0.01)
        assertEquals(VehicleType.BIKE_BOOST, result.vehicleType)
    }

    @Test
    fun `single km value parsed as ride distance`() {
        val nodes = listOf("₹55", "3.5 km", "Accept")
        val result = parser.parseExpandedCard(nodes, "com.rapido.captain")

        assertNotNull(result)
        assertEquals("Ride distance", 3.5, result!!.rideDistanceKm, 0.01)
        assertEquals("No pickup distance", 0.0, result.pickupDistanceKm, 0.01)
    }

    @Test
    fun `cash payment type is detected`() {
        val nodes = listOf("Bike", "₹70", "2.0 km", "5.0 km", "Accept", "Cash")
        val result = parser.parseExpandedCard(nodes, "com.rapido.captain")

        assertNotNull(result)
        assertTrue("Payment type contains 'cash'", result!!.paymentType.contains("cash", ignoreCase = true))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parser.parseExpandedCard(emptyList(), "com.rapido.captain"))
    }

    @Test
    fun `active ride screen returns null`() {
        val nodes = listOf("₹80", "End Ride", "Arrived at pickup", "3.0 km")
        assertNull(parser.parseExpandedCard(nodes, "com.rapido.captain"))
    }

    @Test
    fun `idle screen returns null`() {
        val nodes = listOf("Settings", "Profile")
        assertNull(parser.parseExpandedCard(nodes, "com.rapido.captain"))
    }

    @Test
    fun `no fare returns null`() {
        val nodes = listOf("Bike", "3.5 km", "10 min", "Accept")
        assertNull(parser.parseExpandedCard(nodes, "com.rapido.captain"))
    }

    @Test
    fun `sanity check rejects unrealistic fare-per-km`() {
        // 0.5 km pickup distance, 5.0 km ride distance.
        // ₹800 for 5.0 km ride = ₹160/km — well above the ₹80/km sanity threshold.
        val nodes = listOf("₹800", "0.5 km", "5.0 km", "Accept")
        assertNull("Should reject unrealistic fare-per-km", parser.parseExpandedCard(nodes, "com.rapido.captain"))
    }

    @Test
    fun `tip amount is extracted from Tip node`() {
        val nodes = listOf("₹80", "Tip ₹20", "2.0 km", "5.0 km", "Accept")
        val result = parser.parseExpandedCard(nodes, "com.rapido.captain")

        assertNotNull(result)
        assertEquals("Tip amount", 20.0, result!!.tipAmount, 0.01)
    }

    @Test
    fun `address candidates extracted after filtering noise nodes`() {
        val nodes = listOf(
            "Bike",
            "₹75",
            "1.5 km",
            "4.0 km",
            "Accept",
            "Koramangala 5th Block, Bangalore",
            "Whitefield Main Road, Bangalore"
        )
        val result = parser.parseExpandedCard(nodes, "com.rapido.captain")

        assertNotNull(result)
        assertEquals("Pickup address", "Koramangala 5th Block, Bangalore", result!!.pickupAddress)
        assertEquals("Drop address", "Whitefield Main Road, Bangalore", result.dropAddress)
    }

    @Test
    fun `pickup and ride distances swapped if pickup is suspiciously large`() {
        // pickup=8.0 > rideDistance=3.0 and pickup > 5.0 → should swap
        val nodes = listOf("₹90", "8.0 km", "3.0 km", "Accept")
        val result = parser.parseExpandedCard(nodes, "com.rapido.captain")

        assertNotNull(result)
        // After swap: pickup=3.0, ride=8.0
        assertEquals("Pickup distance after swap", 3.0, result!!.pickupDistanceKm, 0.01)
        assertEquals("Ride distance after swap", 8.0, result.rideDistanceKm, 0.01)
    }

    // ── parseAll tests ───────────────────────────────────────────────

    @Test
    fun `parseAll returns single ride for simple offer`() {
        val nodes = listOf("₹70", "2.0 km", "6.0 km", "Accept", "12 min")
        val results = parser.parseAll(nodes, "com.rapido.captain")

        assertEquals("Should return exactly one ride", 1, results.size)
        assertEquals("Fare", 70.0, results[0].baseFare, 0.01)
    }

    @Test
    fun `parseAll returns empty list for idle screen`() {
        val nodes = listOf("Settings", "Profile", "Help")
        assertTrue(parser.parseAll(nodes, "com.rapido.captain").isEmpty())
    }

    @Test
    fun `parseAll splits on card keywords and returns multiple rides`() {
        val nodes = listOf(
            "Bike", "₹65", "2.0 km", "5.0 km", "Accept",
            "Auto", "₹90", "1.5 km", "7.0 km", "Accept"
        )
        val results = parser.parseAll(nodes, "com.rapido.captain")
        assertTrue("Should return multiple rides for multiple cards", results.size >= 1)
    }

    // ── in.rapido.captain package tests ─────────────────────────────

    @Test
    fun `in_rapido_captain package parses correctly`() {
        val nodes = listOf("Bike", "₹60", "1.0 km", "3.5 km", "Accept", "8 min")
        val result = parser.parseExpandedCard(nodes, "in.rapido.captain")

        assertNotNull(result)
        assertEquals("in.rapido.captain", result!!.packageName)
    }
}
