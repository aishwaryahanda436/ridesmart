package com.ridesmart.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UberOcrEngine text parsing logic.
 * These tests exercise parseFromNodes() which handles the core
 * OCR text → ParsedRide conversion without requiring a Bitmap or ML Kit.
 */
class UberOcrEngineTest {

    private lateinit var engine: UberOcrEngine

    @Before
    fun setUp() {
        engine = UberOcrEngine()
    }

    // ── Blacklist / rejection tests ──────────────────────────────────

    @Test
    fun `idle screen with reject phrases returns null`() {
        val nodes = listOf("You're online", "Finding trips", "₹0.00")
        val result = engine.parseFromNodes(nodes)
        assertNull("Idle screen should be rejected", result)
    }

    @Test
    fun `blacklisted phrase 'copied to' returns null`() {
        val nodes = listOf("₹120", "Copied to clipboard", "Uber", "Match")
        val result = engine.parseFromNodes(nodes)
        assertNull("Blacklisted screen should return null", result)
    }

    @Test
    fun `missing uber identifiers returns null`() {
        val nodes = listOf("₹74", "7.4 km", "12 min")
        val result = engine.parseFromNodes(nodes)
        assertNull("Lines without Uber identifiers should return null", result)
    }

    // ── Successful parsing tests ─────────────────────────────────────

    @Test
    fun `basic uber offer parses fare correctly`() {
        val nodes = listOf(
            "₹120",
            "5 mins (4.2 km)",
            "3 min (1.1 km)",
            "Match",
            "Uber"
        )
        val result = engine.parseFromNodes(nodes)

        assertNotNull("Should parse valid Uber offer", result)
        assertEquals("Fare", 120.0, result!!.baseFare, 0.01)
        assertEquals("Platform", "Uber", result.platform)
    }

    @Test
    fun `offer with Rs prefix parses fare`() {
        val nodes = listOf(
            "Rs.85",
            "8 mins (6.0 km)",
            "2 min (0.9 km)",
            "Match",
            "Uber request"
        )
        val result = engine.parseFromNodes(nodes)

        assertNotNull("Should parse Rs. prefix fare", result)
        assertEquals("Fare", 85.0, result!!.baseFare, 0.01)
    }

    @Test
    fun `offer with bonus adds to effective fare`() {
        val nodes = listOf(
            "₹100",
            "+ ₹20",
            "10 mins (7.5 km)",
            "3 min (1.5 km)",
            "Match",
            "Uber"
        )
        val result = engine.parseFromNodes(nodes)

        assertNotNull("Should parse offer with bonus", result)
        assertEquals("Effective fare includes bonus", 120.0, result!!.baseFare, 0.01)
    }

    @Test
    fun `cash payment detected from text`() {
        val nodes = listOf(
            "₹90",
            "cash payment",
            "8 mins (5.0 km)",
            "Match",
            "Uber"
        )
        val result = engine.parseFromNodes(nodes)

        assertNotNull(result)
        assertEquals("Payment type should be cash", "cash", result!!.paymentType)
    }

    @Test
    fun `digital payment when no cash keyword`() {
        val nodes = listOf(
            "₹90",
            "8 mins (5.0 km)",
            "Match",
            "Uber"
        )
        val result = engine.parseFromNodes(nodes)

        assertNotNull(result)
        assertEquals("Payment type should be digital", "digital", result!!.paymentType)
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun `empty input returns null`() {
        val result = engine.parseFromNodes(emptyList())
        assertNull("Empty input should return null", result)
    }

    @Test
    fun `fare below minimum threshold returns null`() {
        val nodes = listOf(
            "₹10",
            "Match",
            "Uber"
        )
        val result = engine.parseFromNodes(nodes)
        assertNull("Fare below ₹25 threshold should return null", result)
    }

    // ── Hybrid detection: hasOfferSignals tests ──────────────────────

    @Test
    fun `hasOfferSignals detects offer with fare and match`() {
        val nodes = listOf("₹120", "Match")
        assertTrue("Should detect offer signals from fare + match", engine.hasOfferSignals(nodes))
    }

    @Test
    fun `hasOfferSignals detects offer with km and accept`() {
        val nodes = listOf("4.2 km", "Accept")
        assertTrue("Should detect offer signals from km + accept", engine.hasOfferSignals(nodes))
    }

    @Test
    fun `hasOfferSignals rejects idle screen`() {
        val nodes = listOf("You're online", "Finding trips")
        assertFalse("Should reject idle screen", engine.hasOfferSignals(nodes))
    }

    @Test
    fun `hasOfferSignals rejects empty input`() {
        assertFalse("Should reject empty input", engine.hasOfferSignals(emptyList()))
    }

    @Test
    fun `hasOfferSignals rejects single unrelated keyword`() {
        val nodes = listOf("Settings", "Profile")
        assertFalse("Should reject unrelated text", engine.hasOfferSignals(nodes))
    }

    @Test
    fun `hasOfferSignals detects partial offer with fare and min`() {
        val nodes = listOf("₹95", "12 min")
        assertTrue("Should detect partial offer signals", engine.hasOfferSignals(nodes))
    }

    @Test
    fun `hasOfferSignals detects offer with trip keyword`() {
        val nodes = listOf("New trip request", "₹85")
        assertTrue("Should detect trip + fare", engine.hasOfferSignals(nodes))
    }
}
