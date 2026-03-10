package com.ridesmart.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests use real text patterns observed from Logcat in Stage 4.
 * Each test simulates exactly what collectAllText() would return
 * for a specific screen state.
 */
class RideDataParserTest {

    private lateinit var parser: RideDataParser

    @Before
    fun setUp() {
        parser = RideDataParser()
    }

    // ── TEST 1: UBER home screen ─────────────────────────────────────
    // Should return NULL — this is the waiting screen, not a popup
    // Based on real Logcat: "₹0.00", "Finding trips", "You\'re online"
    @Test
    fun `uber home screen returns null`() {
        val nodes = listOf(
            "Home", "₹0.00", "Search for places",
            "Finding trips", "You\'re online", "Waybill"
        )
        val result = parser.parse(nodes, "com.ubercab.driver")
        assertNull("Uber home screen should return null", result)
    }

    // ── TEST 2: UBER ride popup — basic fare ─────────────────────────
    // Real Uber popup format with fare + distances + duration
    @Test
    fun `uber basic ride popup parses correctly`() {
        val nodes = listOf(
            "New Trip Request",
            "₹74",
            "7.4 km",
            "1.4 km away",
            "12 min",
            "Accept"
        )
        val result = parser.parse(nodes, "com.ubercab.driver")

        assertNotNull("Should detect Uber ride popup", result)
        assertEquals("com.ubercab.driver", result!!.platform)
        assertEquals("Base fare", 74.0, result.baseFare, 0.01)
        assertEquals("Ride distance", 7.4, result.rideDistanceKm, 0.01)
        assertEquals("Pickup distance", 1.4, result.pickupDistanceKm, 0.01)
        assertEquals("Duration", 12, result.estimatedDurationMin)
        assertEquals("No tip", 0.0, result.tipAmount, 0.01)
    }

    // ── TEST 3: UBER ride popup — with tip ───────────────────────────
    @Test
    fun `uber ride popup with tip parses correctly`() {
        val nodes = listOf(
            "New Trip Request",
            "₹76",
            "Tip ₹10",
            "12.1 km",
            "0.8 km away",
            "18 min",
            "Accept"
        )
        val result = parser.parse(nodes, "com.ubercab.driver")

        assertNotNull(result)
        assertEquals("Base fare excludes tip", 76.0, result!!.baseFare, 0.01)
        assertEquals("Tip extracted", 10.0, result.tipAmount, 0.01)
        assertEquals("Ride distance", 12.1, result.rideDistanceKm, 0.01)
        assertEquals("Pickup distance", 0.8, result.pickupDistanceKm, 0.01)
    }

    // ── TEST 4: RAPIDO ride popup — basic ────────────────────────────
    // Real Rapido format: capital Km, two distance values
    @Test
    fun `rapido basic popup parses correctly`() {
        val nodes = listOf(
            "₹37",
            "1.1 Km",
            "5.2 Km",
            "15 min",
            "Accept"
        )
        val result = parser.parse(nodes, "com.rapido.rider")

        assertNotNull("Should detect Rapido ride popup", result)
        assertEquals("com.rapido.rider", result!!.platform)
        assertEquals("Base fare", 37.0, result.baseFare, 0.01)
        assertEquals("Ride distance = larger km", 5.2, result.rideDistanceKm, 0.01)
        assertEquals("Pickup distance = smaller km", 1.1, result.pickupDistanceKm, 0.01)
        assertEquals("Duration", 15, result.estimatedDurationMin)
    }

    // ── TEST 5: RAPIDO popup with tip ────────────────────────────────
    @Test
    fun `rapido popup with tip parses correctly`() {
        val nodes = listOf(
            "₹76",
            "Tip ₹10",
            "0.8 Km",
            "12.1 Km",
            "20 min",
            "Accept"
        )
        val result = parser.parse(nodes, "com.rapido.rider")

        assertNotNull(result)
        assertEquals("Base fare", 76.0, result!!.baseFare, 0.01)
        assertEquals("Tip", 10.0, result.tipAmount, 0.01)
        assertEquals("Ride distance", 12.1, result.rideDistanceKm, 0.01)
    }

    // ── TEST 6: Non-popup screen (Today\'s Earnings) ──────────────────
    // Based on real Logcat — Uber earnings screen with ₹0
    @Test
    fun `uber earnings screen returns null`() {
        val nodes = listOf(
            "Today\'s Earnings", "₹0", "leading icon",
            "Right on track", "4 Completed Orders",
            "OFF DUTY", "Home", "Orders"
        )
        val result = parser.parse(nodes, "com.ubercab.driver")
        assertNull("Earnings screen should return null", result)
    }
}