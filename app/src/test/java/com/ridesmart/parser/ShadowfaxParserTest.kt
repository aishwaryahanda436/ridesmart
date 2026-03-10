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
        val result = parser.parse(nodes, "com.shadowfax.driver")

        assertNotNull("Should parse valid Shadowfax delivery", result)
        assertEquals("Fare", 55.0, result!!.baseFare, 0.01)
        assertEquals("Pickup distance", 0.8, result.pickupDistanceKm, 0.01)
        assertEquals("Ride distance", 3.2, result.rideDistanceKm, 0.01)
        assertEquals("Duration", 10, result.estimatedDurationMin)
        assertEquals("Package", "com.shadowfax.driver", result.packageName)
    }

    @Test
    fun `Shadowfax defaults to BIKE vehicle type`() {
        val nodes = listOf("₹40", "2.0 km", "Accept")
        val result = parser.parse(nodes, "com.shadowfax.driver")

        assertNotNull(result)
        assertEquals(VehicleType.BIKE, result!!.vehicleType)
    }

    @Test
    fun `Shadowfax with COD payment detected`() {
        val nodes = listOf("₹60", "3.0 km", "COD", "Accept")
        val result = parser.parse(nodes, "com.shadowfax.driver")

        assertNotNull(result)
        assertEquals("COD", result!!.paymentType)
    }

    @Test
    fun `Shadowfax with prepaid payment detected`() {
        val nodes = listOf("₹50", "2.5 km", "Prepaid", "Accept")
        val result = parser.parse(nodes, "com.shadowfax.driver")

        assertNotNull(result)
        assertEquals("Prepaid", result!!.paymentType)
    }

    @Test
    fun `empty input returns null`() {
        assertNull(parser.parse(emptyList(), "com.shadowfax.driver"))
    }

    @Test
    fun `idle screen returns empty list from parseAll`() {
        val nodes = listOf("No orders", "You are offline")
        val results = parser.parseAll(nodes, "com.shadowfax.driver")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `fare below minimum returns null`() {
        val nodes = listOf("₹3", "1.0 km", "Accept")
        val result = parser.parse(nodes, "com.shadowfax.driver")
        assertNull("Fare below ₹5 threshold should return null", result)
    }

    @Test
    fun `Shadowfax with bonus parses correctly`() {
        val nodes = listOf("₹40", "+₹10", "2.5 km", "Accept")
        val result = parser.parse(nodes, "com.shadowfax.driver")

        assertNotNull(result)
        assertEquals("Base fare", 40.0, result!!.baseFare, 0.01)
        assertEquals("Bonus", 10.0, result.premiumAmount, 0.01)
    }

    @Test
    fun `Shadowfax single distance parses as ride distance`() {
        val nodes = listOf("₹35", "1.8 km", "5 min", "Accept")
        val result = parser.parse(nodes, "com.shadowfax.driver")

        assertNotNull(result)
        assertEquals("Ride distance", 1.8, result!!.rideDistanceKm, 0.01)
        assertEquals("No pickup distance", 0.0, result.pickupDistanceKm, 0.01)
    }
}
