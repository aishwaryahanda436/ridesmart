package com.ridesmart.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

/**
 * Unit tests for OlaParser.
 * Tests ride offer parsing for Ola Driver app accessibility nodes.
 */
class OlaParserTest {

    private lateinit var parser: OlaParser

    @Before
    fun setUp() {
        parser = OlaParser()
    }

    // ── Screen state detection ───────────────────────────────────────

    @Test
    fun `idle screen with offline keywords returns IDLE`() {
        val nodes = listOf("You are offline", "Go online", "Earnings today")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `active ride with trip keywords returns ACTIVE_RIDE`() {
        val nodes = listOf("₹120", "On trip", "Drop passenger", "5 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare and accept returns OFFER_LOADED`() {
        val nodes = listOf("₹85", "3.2 km", "1.1 km", "Accept", "12 min")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare and accept but no km returns OFFER_LOADING`() {
        val nodes = listOf("₹85", "Accept")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `no fare no accept returns IDLE`() {
        val nodes = listOf("Settings", "Profile", "Help")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    // ── Ride parsing ────────────────────────────────────────────────

    @Test
    fun `basic Ola ride parses correctly`() {
        val nodes = listOf(
            "₹120",
            "1.5 km",
            "6.0 km",
            "15 min",
            "Accept",
            "Sector 18, Noida to Greater Noida Expressway"
        )
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull("Should parse valid Ola offer", result)
        assertEquals("Fare", 120.0, result!!.baseFare, 0.01)
        assertEquals("Pickup distance", 1.5, result.pickupDistanceKm, 0.01)
        assertEquals("Ride distance", 6.0, result.rideDistanceKm, 0.01)
        assertEquals("Duration", 15, result.estimatedDurationMin)
        assertEquals("Package", "com.olacabs.oladriver", result.packageName)
    }

    @Test
    fun `Ola ride with single distance parses as ride distance`() {
        val nodes = listOf("₹75", "4.5 km", "10 min", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull(result)
        assertEquals("Ride distance", 4.5, result!!.rideDistanceKm, 0.01)
        assertEquals("No pickup distance", 0.0, result.pickupDistanceKm, 0.01)
    }

    @Test
    fun `Ola Mini detects CAR vehicle type`() {
        val nodes = listOf("Mini", "₹150", "5.0 km", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull(result)
        assertEquals(VehicleType.CAR, result!!.vehicleType)
    }

    @Test
    fun `Ola Auto detects AUTO vehicle type`() {
        val nodes = listOf("Auto", "₹80", "3.0 km", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull(result)
        assertEquals(VehicleType.AUTO, result!!.vehicleType)
    }

    @Test
    fun `Ola Bike detects BIKE vehicle type`() {
        val nodes = listOf("Bike", "₹35", "2.0 km", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull(result)
        assertEquals(VehicleType.BIKE, result!!.vehicleType)
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parser.parse(emptyList(), "com.olacabs.oladriver"))
    }

    @Test
    fun `idle screen returns empty list from parseAll`() {
        val nodes = listOf("You are offline", "Go online")
        val results = parser.parseAll(nodes, "com.olacabs.oladriver")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `Ola ride with cash payment detected`() {
        val nodes = listOf("₹95", "3.0 km", "cash", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull(result)
        assertEquals("cash", result!!.paymentType)
    }

    @Test
    fun `fare below minimum returns null`() {
        val nodes = listOf("₹5", "1.0 km", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")
        assertNull("Fare below ₹10 threshold should return null", result)
    }

    @Test
    fun `Ola ride takes minimum fare for multi-fare nodes`() {
        val nodes = listOf("₹200", "₹120", "5.0 km", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull(result)
        assertEquals("Should use minimum fare (Ola post-commission)", 120.0, result!!.baseFare, 0.01)
    }

    // ── Rs fare format (contentDescription) tests ───────────────────

    @Test
    fun `Ola Rs fare format from contentDescription parses correctly`() {
        val nodes = listOf("Rs 124.00", "3.0 km", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull("Should parse Rs fare format", result)
        assertEquals("Fare from Rs format", 124.0, result!!.baseFare, 0.01)
    }

    @Test
    fun `Ola Rs fare format case insensitive`() {
        val nodes = listOf("rs 95", "2.5 km", "Accept")
        val result = parser.parse(nodes, "com.olacabs.oladriver")

        assertNotNull("Should parse lowercase rs format", result)
        assertEquals("Fare from rs format", 95.0, result!!.baseFare, 0.01)
    }
}
