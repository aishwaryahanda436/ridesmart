package com.ridesmart.parser

import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OlaParserTest {

    private lateinit var parser: OlaParser

    @Before
    fun setUp() {
        parser = OlaParser()
    }

    // ── detectScreenState ─────────────────────────────────────────────

    @Test
    fun `idle screen with no fare returns IDLE`() {
        val nodes = listOf("Home", "ON DUTY", "Earnings", "Support")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `active ride keywords return ACTIVE_RIDE`() {
        val nodes = listOf("₹150", "Start Ride", "OTP", "4.5 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with keywords fare km and accept returns OFFER_LOADED`() {
        val nodes = listOf("New Booking", "₹80", "6.2 km", "Accept")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare and accept but no km returns OFFER_LOADING`() {
        val nodes = listOf("New Booking", "₹80", "Accept")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `fare alone without offer keywords returns OFFER_LOADING`() {
        val nodes = listOf("₹45")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `accept and fare and km without offer keywords returns OFFER_LOADED`() {
        val nodes = listOf("₹60", "5.0 km", "Accept")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    // ── parseAll: rejections ──────────────────────────────────────────

    @Test
    fun `idle screen nodes return ParseResult Idle`() {
        val nodes = listOf("Home", "ON DUTY", "Earnings")
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")
        assertTrue("Idle screen should return Idle", result is ParseResult.Idle)
    }

    @Test
    fun `active ride returns Failure`() {
        val nodes = listOf("₹80", "End Ride", "OTP", "3 km")
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")
        assertTrue("Active ride should return Failure", result is ParseResult.Failure)
    }

    @Test
    fun `screen with no fare returns Failure`() {
        val nodes = listOf("New Booking", "6.2 km", "Accept")
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")
        assertTrue("No fare should return Failure", result is ParseResult.Failure)
    }

    // ── parseAll: successful parse ────────────────────────────────────

    @Test
    fun `basic ola offer parses fare and km correctly`() {
        val nodes = listOf(
            "New Booking",
            "₹85",
            "• 1.2 km • 3 min",   // pickup bullet node
            "15 mins trip",
            "8.5 km Distance",    // ride distance
            "Accept"
        )
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue("Should parse valid Ola offer", result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Fare", 85.0, ride.baseFare, 0.01)
        assertEquals("Platform name", "Ola", ride.platform)
        assertEquals("Package name", "com.olacabs.oladriver", ride.packageName)
    }

    @Test
    fun `trip duration in mins trip pattern is parsed`() {
        val nodes = listOf(
            "Ride Request",
            "₹70",
            "20 mins trip",
            "5.0 km Distance",
            "Accept"
        )
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Trip duration", 20, ride.estimatedDurationMin)
    }

    @Test
    fun `ride distance from km Distance pattern is extracted`() {
        val nodes = listOf(
            "New Booking",
            "₹90",
            "12.3 km Distance",
            "Accept"
        )
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Ride distance", 12.3, ride.rideDistanceKm, 0.01)
    }

    @Test
    fun `fare node containing km is not treated as fare`() {
        // Node "₹85 • 6 km" should not extract ₹85 because it also contains "km"
        val nodes = listOf(
            "New Booking",
            "₹85 • 6 km",  // mixed node — should be ignored for fare
            "₹85",          // clean fare node
            "Accept",
            "6.0 km Distance"
        )
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Clean fare node used", 85.0, ride.baseFare, 0.01)
    }

    // ── Vehicle type detection ────────────────────────────────────────

    @Test
    fun `bike taxi in text detects BIKE vehicle type`() {
        val nodes = listOf("New Booking", "Bike Taxi", "₹40", "3.0 km Distance", "Accept")
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        assertEquals(VehicleType.BIKE, (result as ParseResult.Success).rides.first().vehicleType)
    }

    @Test
    fun `cng auto in text detects CNG_AUTO vehicle type`() {
        val nodes = listOf("New Booking", "CNG Auto", "₹75", "5.0 km Distance", "Accept")
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        assertEquals(VehicleType.CNG_AUTO, (result as ParseResult.Success).rides.first().vehicleType)
    }

    @Test
    fun `sedan in text detects CAR vehicle type`() {
        val nodes = listOf("New Booking", "Sedan", "₹120", "8.0 km Distance", "Accept")
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        assertEquals(VehicleType.CAR, (result as ParseResult.Success).rides.first().vehicleType)
    }

    // ── Payment type ──────────────────────────────────────────────────

    @Test
    fun `cash payment keyword returns cash paymentType`() {
        val nodes = listOf("New Booking", "₹60", "cash payment", "4.0 km Distance", "Accept")
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        assertEquals("cash", (result as ParseResult.Success).rides.first().paymentType)
    }

    @Test
    fun `digital keyword returns digital paymentType`() {
        val nodes = listOf("Ride Request", "₹60", "digital payment", "4.0 km Distance", "Accept")
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        assertEquals("digital", (result as ParseResult.Success).rides.first().paymentType)
    }

    // ── parseFromNotification ─────────────────────────────────────────

    @Test
    fun `notification with booking title and fare parses correctly`() {
        val ride = parser.parseFromNotification(
            title       = "New Booking Request",
            text        = "₹75 • 6.2 km trip",
            packageName = "com.olacabs.oladriver"
        )

        assertNotNull("Notification with booking title should parse", ride)
        assertEquals("Fare from notification", 75.0, ride!!.baseFare, 0.01)
        assertEquals("Platform name", "Ola", ride.platform)
    }

    @Test
    fun `notification with ride title and multiple km values extracts max as rideKm`() {
        val ride = parser.parseFromNotification(
            title       = "Incoming Ride",
            text        = "₹90 • 1.5 km pickup • 8.0 km ride",
            packageName = "com.olacabs.oladriver"
        )

        assertNotNull(ride)
        assertEquals("Ride km = max km value", 8.0, ride!!.rideDistanceKm, 0.01)
        assertEquals("Pickup km = min km value", 1.5, ride.pickupDistanceKm, 0.01)
    }

    @Test
    fun `notification with unrelated title returns null`() {
        val ride = parser.parseFromNotification(
            title       = "Ola Money Offer",
            text        = "Get 20% cashback on recharge",
            packageName = "com.olacabs.oladriver"
        )

        assertNull("Non-ride notification should return null", ride)
    }

    @Test
    fun `notification without fare returns null`() {
        val ride = parser.parseFromNotification(
            title       = "New Booking Request",
            text        = "Trip of 6.2 km",
            packageName = "com.olacabs.oladriver"
        )

        assertNull("Notification without fare should return null", ride)
    }

    @Test
    fun `notification without km values returns null`() {
        val ride = parser.parseFromNotification(
            title       = "New Booking",
            text        = "₹80 trip",
            packageName = "com.olacabs.oladriver"
        )

        assertNull("Notification without km should return null", ride)
    }

    // ── Fallback km guard ─────────────────────────────────────────────

    @Test
    fun `fallback km ignores values over 60km as weekly goal noise`() {
        val nodes = listOf(
            "New Booking",
            "₹70",
            "goal 100 km",   // weekly goal — should be excluded from rideKm
            "5.5 km",        // real ride distance
            "Accept"
        )
        val result = parser.parseAll(nodes, "com.olacabs.oladriver")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Should pick 5.5 km not 100 km", 5.5, ride.rideDistanceKm, 0.01)
    }
}
