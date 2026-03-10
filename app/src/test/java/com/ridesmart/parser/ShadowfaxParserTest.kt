package com.ridesmart.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.ridesmart.model.ScreenState
import com.ridesmart.model.VehicleType

/**
 * Unit tests for ShadowfaxParser.
 * Tests delivery request parsing for Shadowfax Driver app accessibility nodes.
 */
class ShadowfaxParserTest {

    private lateinit var parser: ShadowfaxParser

    @Before
    fun setUp() {
        parser = ShadowfaxParser()
    }

    // ── Screen state detection ───────────────────────────────────────

    @Test
    fun `idle screen with no orders returns IDLE`() {
        val nodes = listOf("No orders", "You are offline")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    @Test
    fun `active delivery returns ACTIVE_RIDE`() {
        val nodes = listOf("₹50", "Delivering", "On the way", "3 km")
        assertEquals(ScreenState.ACTIVE_RIDE, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare and accept returns OFFER_LOADED`() {
        val nodes = listOf("₹45", "2.5 km", "1.0 km", "Accept", "8 min")
        assertEquals(ScreenState.OFFER_LOADED, parser.detectScreenState(nodes))
    }

    @Test
    fun `offer with fare and accept but no km returns OFFER_LOADING`() {
        val nodes = listOf("₹45", "Accept")
        assertEquals(ScreenState.OFFER_LOADING, parser.detectScreenState(nodes))
    }

    @Test
    fun `no fare no accept returns IDLE`() {
        val nodes = listOf("Settings", "Profile")
        assertEquals(ScreenState.IDLE, parser.detectScreenState(nodes))
    }

    // ── Delivery request parsing ────────────────────────────────────

    @Test
    fun `basic Shadowfax delivery parses correctly`() {
        val nodes = listOf(
            "₹55",
            "0.8 km",
            "3.2 km",
            "10 min",
            "Accept",
            "McDonald's MG Road to Sector 14 Gurgaon"
        )
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull("Should parse valid Shadowfax delivery", result)
        assertEquals("Fare", 55.0, result!!.baseFare, 0.01)
        assertEquals("Pickup distance", 0.8, result.pickupDistanceKm, 0.01)
        assertEquals("Ride distance", 3.2, result.rideDistanceKm, 0.01)
        assertEquals("Duration", 10, result.estimatedDurationMin)
        assertEquals("Package", "in.shadowfax.gandalf", result.packageName)
    }

    @Test
    fun `Shadowfax defaults to BIKE vehicle type`() {
        val nodes = listOf("₹40", "2.0 km", "Accept")
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull(result)
        assertEquals(VehicleType.BIKE, result!!.vehicleType)
    }

    @Test
    fun `Shadowfax with COD payment detected`() {
        val nodes = listOf("₹60", "3.0 km", "COD", "Accept")
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull(result)
        assertEquals("COD", result!!.paymentType)
    }

    @Test
    fun `Shadowfax with prepaid payment detected`() {
        val nodes = listOf("₹50", "2.5 km", "Prepaid", "Accept")
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull(result)
        assertEquals("Prepaid", result!!.paymentType)
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parser.parse(emptyList(), "in.shadowfax.gandalf"))
    }

    @Test
    fun `idle screen returns empty list from parseAll`() {
        val nodes = listOf("No orders", "You are offline")
        val results = parser.parseAll(nodes, "in.shadowfax.gandalf")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `fare below minimum returns null`() {
        val nodes = listOf("₹3", "1.0 km", "Accept")
        val result = parser.parse(nodes, "in.shadowfax.gandalf")
        assertNull("Fare below ₹5 threshold should return null", result)
    }

    @Test
    fun `Shadowfax with bonus parses correctly`() {
        val nodes = listOf("₹40", "+₹10", "2.5 km", "Accept")
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull(result)
        assertEquals("Base fare", 40.0, result!!.baseFare, 0.01)
        assertEquals("Bonus", 10.0, result.premiumAmount, 0.01)
    }

    @Test
    fun `Shadowfax single distance parses as ride distance`() {
        val nodes = listOf("₹35", "1.8 km", "5 min", "Accept")
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull(result)
        assertEquals("Ride distance", 1.8, result!!.rideDistanceKm, 0.01)
        assertEquals("No pickup distance", 0.0, result.pickupDistanceKm, 0.01)
    }

    // ── Compose merged-node dual-strategy tests ─────────────────────

    @Test
    fun `Compose merged node with Guaranteed Pay and Surge Bonus parses correctly`() {
        val nodes = listOf(
            "Guaranteed Pay: ₹85 · Surge Bonus: ₹15 · 3.2 km · Accept"
        )
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull("Should parse Compose merged node", result)
        assertEquals("Fare from Guaranteed Pay", 85.0, result!!.baseFare, 0.01)
        assertEquals("Surge bonus", 15.0, result.premiumAmount, 0.01)
        assertEquals("Distance", 3.2, result.rideDistanceKm, 0.01)
    }

    @Test
    fun `multi-stop stacked order sums distances`() {
        val nodes = listOf("₹120", "1.5 km", "2.0 km", "3.5 km", "Accept")
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull("Should parse multi-stop order", result)
        assertEquals("Pickup distance", 1.5, result!!.pickupDistanceKm, 0.01)
        // Remaining distances summed: 2.0 + 3.5 = 5.5
        assertEquals("Total ride distance", 5.5, result.rideDistanceKm, 0.01)
    }

    @Test
    fun `Surge Bonus in individual node is extracted`() {
        val nodes = listOf("₹70", "Surge Bonus: ₹20", "2.0 km", "Accept")
        val result = parser.parse(nodes, "in.shadowfax.gandalf")

        assertNotNull(result)
        assertEquals("Surge bonus extracted", 20.0, result!!.premiumAmount, 0.01)
    }
}
