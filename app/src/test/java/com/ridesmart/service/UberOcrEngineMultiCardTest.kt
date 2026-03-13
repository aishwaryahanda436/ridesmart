package com.ridesmart.service

import com.ridesmart.model.ScreenState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for enhanced UberOcrEngine multi-card parsing and screen state detection.
 * Covers ride list detection, Trip Radar parsing, and multi-card extraction.
 */
class UberOcrEngineMultiCardTest {

    private lateinit var engine: UberOcrEngine

    @Before
    fun setUp() {
        engine = UberOcrEngine()
    }

    // ── Screen state detection ──────────────────────────────────────

    @Test
    fun `detect TRIP_RADAR state when Trip Radar keyword present with fare`() {
        val nodes = listOf("Trip Radar", "₹120", "5 km", "12 min", "₹85", "3 km")
        val state = engine.detectScreenState(nodes)
        assertEquals(ScreenState.TRIP_RADAR, state)
    }

    @Test
    fun `detect TRIP_RADAR state when See all requests keyword present`() {
        val nodes = listOf("See all requests", "₹150", "8 km", "20 min")
        val state = engine.detectScreenState(nodes)
        assertEquals(ScreenState.TRIP_RADAR, state)
    }

    @Test
    fun `detect TRIP_RADAR state when Opportunity keyword present`() {
        val nodes = listOf("Opportunity", "₹200", "10 km", "25 min")
        val state = engine.detectScreenState(nodes)
        assertEquals(ScreenState.TRIP_RADAR, state)
    }

    @Test
    fun `detect RIDE_LIST state when multiple fares without accept button`() {
        val nodes = listOf("₹120", "5 km", "12 min", "₹85", "3 km", "8 min")
        val state = engine.detectScreenState(nodes)
        assertEquals(ScreenState.RIDE_LIST, state)
    }

    @Test
    fun `detect OFFER_LOADED for single fare with accept button`() {
        val nodes = listOf("₹120", "5 km", "12 min", "Match")
        val state = engine.detectScreenState(nodes)
        assertEquals(ScreenState.OFFER_LOADED, state)
    }

    @Test
    fun `detect IDLE for idle screen phrases`() {
        val nodes = listOf("You're online", "Finding trips")
        val state = engine.detectScreenState(nodes)
        assertEquals(ScreenState.IDLE, state)
    }

    // ── Multi-card parsing ──────────────────────────────────────────

    @Test
    fun `parseMultipleCards splits two rides by fare boundaries`() {
        val lines = listOf(
            "₹120", "5 min (3.2 km)", "10 min (8.5 km)", "Match",
            "₹85", "3 min (1.5 km)", "7 min (4.0 km)", "Match"
        )
        val rides = engine.parseMultipleCards(lines)
        assertEquals("Should find 2 rides", 2, rides.size)
        assertEquals(120.0, rides[0].baseFare, 0.01)
        assertEquals(85.0, rides[1].baseFare, 0.01)
    }

    @Test
    fun `parseMultipleCards returns empty for no rides`() {
        val lines = listOf("You're online", "Finding trips")
        val rides = engine.parseMultipleCards(lines)
        assertTrue("Should return empty for idle screen", rides.isEmpty())
    }

    @Test
    fun `parseMultipleCards handles single ride`() {
        val lines = listOf("₹150", "5 min (3.2 km)", "10 min (8.5 km)", "Match")
        val rides = engine.parseMultipleCards(lines)
        assertEquals("Should find 1 ride", 1, rides.size)
        assertEquals(150.0, rides[0].baseFare, 0.01)
    }

    @Test
    fun `parseMultipleCards handles three rides in Trip Radar`() {
        val lines = listOf(
            "₹120", "5 min (3.2 km)", "10 min (8.5 km)", "Match",
            "₹85", "3 min (1.5 km)", "7 min (4.0 km)", "Confirm",
            "₹200", "8 min (5.0 km)", "15 min (12.0 km)", "Accept"
        )
        val rides = engine.parseMultipleCards(lines)
        assertEquals("Should find 3 rides", 3, rides.size)
        assertEquals(120.0, rides[0].baseFare, 0.01)
        assertEquals(85.0, rides[1].baseFare, 0.01)
        assertEquals(200.0, rides[2].baseFare, 0.01)
    }

    // ── parseAll integration with ride list detection ────────────────

    @Test
    fun `parseAll returns multiple rides for RIDE_LIST state`() {
        val nodes = listOf(
            "₹120", "5 min (3.2 km)", "10 min (8.5 km)",
            "₹85", "3 min (1.5 km)", "7 min (4.0 km)"
        )
        val rides = engine.parseAll(nodes, "com.ubercab.driver")
        assertTrue("Should return at least 1 ride for ride list", rides.isNotEmpty())
    }

    @Test
    fun `parseAll returns rides for TRIP_RADAR state`() {
        val nodes = listOf(
            "Trip Radar", "₹120", "5 km", "12 min", "Match",
            "₹85", "3 km", "8 min", "Match"
        )
        val rides = engine.parseAll(nodes, "com.ubercab.driver")
        assertTrue("Should return rides for Trip Radar screen", rides.isNotEmpty())
    }

    // ── hasOfferSignals ─────────────────────────────────────────────

    @Test
    fun `hasOfferSignals detects trip radar signals`() {
        val nodes = listOf("Trip Radar", "₹120", "5 km")
        assertTrue(engine.hasOfferSignals(nodes))
    }

    @Test
    fun `hasOfferSignals detects stacked ride signals`() {
        val nodes = listOf("stacked", "₹120", "5 km", "Accept")
        assertTrue(engine.hasOfferSignals(nodes))
    }

    @Test
    fun `hasOfferSignals detects opportunity signals`() {
        val nodes = listOf("opportunity", "₹120", "5 km")
        assertTrue(engine.hasOfferSignals(nodes))
    }

    @Test
    fun `hasOfferSignals rejects idle screen with new keywords`() {
        val nodes = listOf("You're online", "Finding trips")
        assertFalse(engine.hasOfferSignals(nodes))
    }
}
