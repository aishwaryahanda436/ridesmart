package com.ridesmart.parser

import com.ridesmart.model.ScreenState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for enhanced RideDataParser screen state detection.
 * Covers ride list and Trip Radar detection.
 */
class RideDataParserScreenStateTest {

    private lateinit var parser: RideDataParser

    @Before
    fun setUp() {
        parser = RideDataParser()
    }

    // ── RIDE_LIST detection ─────────────────────────────────────────

    @Test
    fun `detect RIDE_LIST when multiple fares without accept button`() {
        val nodes = listOf("₹120", "5 km", "12 min", "₹85", "3 km", "8 min")
        val state = parser.detectScreenState(nodes)
        assertEquals(ScreenState.RIDE_LIST, state)
    }

    @Test
    fun `detect OFFER_LOADED when fare with accept button`() {
        val nodes = listOf("₹120", "5 km", "12 min", "Accept")
        val state = parser.detectScreenState(nodes)
        assertEquals(ScreenState.OFFER_LOADED, state)
    }

    // ── TRIP_RADAR detection ────────────────────────────────────────

    @Test
    fun `detect TRIP_RADAR when Trip Radar keyword present`() {
        val nodes = listOf("Trip Radar", "₹120", "5 km", "12 min")
        val state = parser.detectScreenState(nodes)
        assertEquals(ScreenState.TRIP_RADAR, state)
    }

    @Test
    fun `detect TRIP_RADAR when See all requests keyword present`() {
        val nodes = listOf("See all requests", "₹150", "8 km", "20 min")
        val state = parser.detectScreenState(nodes)
        assertEquals(ScreenState.TRIP_RADAR, state)
    }

    @Test
    fun `detect TRIP_RADAR when Opportunity keyword present`() {
        val nodes = listOf("Opportunity", "₹200", "10 km", "25 min")
        val state = parser.detectScreenState(nodes)
        assertEquals(ScreenState.TRIP_RADAR, state)
    }

    // ── Existing states preserved ───────────────────────────────────

    @Test
    fun `detect IDLE for home screen`() {
        val nodes = listOf("Home", "₹0.00", "Finding trips", "You're online")
        val state = parser.detectScreenState(nodes)
        assertEquals(ScreenState.IDLE, state)
    }

    @Test
    fun `detect OFFER_LOADING when fare and confirm but no km`() {
        val nodes = listOf("₹120", "Match")
        val state = parser.detectScreenState(nodes)
        assertEquals(ScreenState.OFFER_LOADING, state)
    }

    @Test
    fun `detect IDLE when no fare no confirm`() {
        val nodes = listOf("Home", "Settings")
        val state = parser.detectScreenState(nodes)
        assertEquals(ScreenState.IDLE, state)
    }
}
