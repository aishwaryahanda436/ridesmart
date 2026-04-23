package com.ridesmart.parser

import com.ridesmart.model.ParseResult
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShadowfaxParserTest {

    private lateinit var parser: ShadowfaxParser

    @Before
    fun setUp() {
        parser = ShadowfaxParser()
    }

    // ── detectScreenState ─────────────────────────────────────────────

    @Test
    fun `idle screen with no fare returns IDLE`() {
        val nodes = listOf("Home", "Orders", "Support")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `active OTP keyword returns ACTIVE_RIDE`() {
        val nodes = listOf("₹80", "OTP", "Navigate to Drop", "5.2 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `arrived at pickup returns ACTIVE_RIDE`() {
        val nodes = listOf("₹60", "Arrived at Pickup", "3.0 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `fare accept and km returns OFFER_LOADED`() {
        val nodes = listOf("₹70", "1.0 km", "6.0 km", "Accept")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `choose order button also returns OFFER_LOADED`() {
        val nodes = listOf("₹70", "1.0 km", "Choose Order")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `fare and km but no accept returns OFFER_LOADING`() {
        val nodes = listOf("₹70", "5.5 km")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `fare only returns OFFER_LOADING`() {
        val nodes = listOf("₹70")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    // ── parseAll: rejections ──────────────────────────────────────────

    @Test
    fun `idle screen returns ParseResult Idle`() {
        val nodes = listOf("Home", "Orders", "Support")
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")
        assertTrue("Idle screen returns Idle", result is ParseResult.Idle)
    }

    @Test
    fun `active ride returns Failure`() {
        val nodes = listOf("₹80", "OTP", "Start Delivery", "4.0 km")
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")
        assertTrue("Active ride should return Failure", result is ParseResult.Failure)
    }

    @Test
    fun `no ride distance returns Failure`() {
        val nodes = listOf("₹70", "Accept")
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")
        assertTrue("No distance should return Failure", result is ParseResult.Failure)
    }

    // ── Bike taxi parsing ─────────────────────────────────────────────

    @Test
    fun `basic bike taxi offer parses correctly`() {
        val nodes = listOf(
            "₹75",
            "Bike",
            "1.2 km",
            "6.5 km",
            "18 min",
            "Accept"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue("Should parse valid bike taxi offer", result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Fare", 75.0, ride.baseFare, 0.01)
        assertEquals("Platform", "Shadowfax", ride.platform)
        assertEquals("Vehicle type", VehicleType.BIKE, ride.vehicleType)
        assertEquals("Ride distance", 6.5, ride.rideDistanceKm, 0.01)
        assertEquals("Pickup distance", 1.2, ride.pickupDistanceKm, 0.01)
        assertEquals("Duration", 18, ride.estimatedDurationMin)
    }

    @Test
    fun `bike taxi with boost premium is extracted`() {
        val nodes = listOf(
            "₹60",
            "+ ₹15",
            "Bike",
            "0.8 km",
            "5.5 km",
            "Accept"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Base fare", 60.0, ride.baseFare, 0.01)
        assertEquals("Boost premium", 15.0, ride.premiumAmount, 0.01)
    }

    @Test
    fun `bike taxi with star-prefix boost is extracted`() {
        val nodes = listOf(
            "₹55",
            "* ₹10",
            "Bike",
            "0.7 km",
            "4.5 km",
            "Accept"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Star-prefix boost", 10.0, ride.premiumAmount, 0.01)
    }

    @Test
    fun `bike taxi payment type defaults to cash`() {
        val nodes = listOf("₹70", "1.0 km", "6.0 km", "Accept")
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        assertEquals("Shadowfax default payment is cash", "cash", (result as ParseResult.Success).rides.first().paymentType)
    }

    @Test
    fun `auto vehicle type is detected in bike taxi`() {
        val nodes = listOf("₹80", "Auto", "0.9 km", "5.0 km", "Accept")
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        assertEquals("Auto vehicle type", VehicleType.AUTO, (result as ParseResult.Success).rides.first().vehicleType)
    }

    // ── Delivery parsing ──────────────────────────────────────────────

    @Test
    fun `delivery offer with Choose Order button is detected`() {
        val nodes = listOf(
            "₹90",
            "Package",
            "Pickup:",
            "1.5 km",
            "Some Street",
            "Drop:",
            "7.0 km",
            "Another Street",
            "Choose Order"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue("Choose Order triggers delivery parsing", result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Delivery vehicle type", VehicleType.DELIVERY, ride.vehicleType)
        assertEquals("Fare", 90.0, ride.baseFare, 0.01)
    }

    @Test
    fun `delivery with Sort By keyword triggers delivery parsing`() {
        val nodes = listOf(
            "₹85",
            "Sort By: Distance",
            "Pickup:",
            "1.0 km",
            "Pickup Location",
            "Drop:",
            "6.5 km",
            "Drop Location"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertEquals("Delivery vehicle type", VehicleType.DELIVERY, ride.vehicleType)
    }

    @Test
    fun `delivery with COD payment returns cash paymentType`() {
        val nodes = listOf(
            "₹80",
            "COD",
            "Package",
            "Pickup:",
            "1.0 km",
            "From Location",
            "Drop:",
            "5.5 km",
            "To Location",
            "Choose Order"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        assertEquals("COD = cash payment", "cash", (result as ParseResult.Success).rides.first().paymentType)
    }

    @Test
    fun `delivery with prepaid returns digital paymentType`() {
        val nodes = listOf(
            "₹80",
            "Prepaid",
            "Package",
            "Pickup:",
            "1.0 km",
            "From Location",
            "Drop:",
            "5.0 km",
            "To Location",
            "Choose Order"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        assertEquals("Prepaid = digital payment", "digital", (result as ParseResult.Success).rides.first().paymentType)
    }

    @Test
    fun `delivery rideKm uses drop distance`() {
        val nodes = listOf(
            "₹95",
            "Package",
            "Pickup:",
            "1.2 km",
            "Pickup Address",
            "Drop:",
            "8.0 km",
            "Drop Address",
            "Choose Order"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        // Delivery uses dropKm as rideDistanceKm
        assertEquals("rideKm = drop distance", 8.0, ride.rideDistanceKm, 0.01)
        assertEquals("pickupKm = pickup distance", 1.2, ride.pickupDistanceKm, 0.01)
    }

    @Test
    fun `delivery fallback km extraction when no pickup drop labels`() {
        // No "Pickup:" or "Drop:" labels — should fall back to min/max km extraction
        val nodes = listOf(
            "₹70",
            "Package",
            "2.0 km",
            "9.0 km",
            "Choose Order"
        )
        val result = parser.parseAll(nodes, "in.shadowfax.gandalf")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.first()
        assertTrue("rideKm should be > 0", ride.rideDistanceKm > 0.0)
    }
}
