package com.ridesmart.service

import com.ridesmart.model.ParseResult
import com.ridesmart.parser.UberParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UberOcrEngine text parsing logic via UberParser.
 */
class UberOcrEngineTest {

    private lateinit var parser: UberParser

    @Before
    fun setUp() {
        parser = UberParser()
    }

    // ── Blacklist / rejection tests ──────────────────────────────────

    @Test
    fun `idle screen with reject phrases returns idle`() {
        // Reduced number of phrases to match UberParser.SCREEN_REJECT logic
        val nodes = listOf("You're online", "Finding trips")
        val result = parser.parseAll(nodes, "com.ubercab.driver")
        assertTrue("Idle screen should be rejected", result is ParseResult.Idle)
    }

    @Test
    fun `blacklisted phrase 'copied to' returns failure`() {
        val nodes = listOf("₹120", "Copied to clipboard", "Uber", "Match")
        val result = parser.parseAll(nodes, "com.ubercab.driver")
        assertTrue("Blacklisted screen should return failure", result is ParseResult.Failure)
    }

    @Test
    fun `missing uber identifiers returns failure`() {
        // Identifiers: "uber", "accept", "decline", "trip fare", "match", "bike", "cash", etc.
        val nodes = listOf("₹74", "7.4 km", "12 min")
        val result = parser.parseAll(nodes, "com.ubercab.driver")
        assertTrue("Lines without Uber identifiers should return failure", result is ParseResult.Failure)
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
        val result = parser.parseAll(nodes, "com.ubercab.driver")

        assertTrue("Should parse valid Uber offer", result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.firstOrNull()
        assertNotNull(ride)
        assertEquals("Fare", 120.0, ride!!.baseFare, 0.01)
        assertEquals("Platform", "Uber", ride.platform)
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
        val result = parser.parseAll(nodes, "com.ubercab.driver")

        assertTrue("Should parse Rs. prefix fare", result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.firstOrNull()
        assertNotNull(ride)
        assertEquals("Fare", 85.0, ride!!.baseFare, 0.01)
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
        val result = parser.parseAll(nodes, "com.ubercab.driver")

        assertTrue("Should parse offer with bonus", result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.firstOrNull()
        assertNotNull(ride)
        // Note: UberParser sums all fare matches in extractTotalFare
        assertEquals("Effective fare includes bonus", 120.0, ride!!.baseFare, 0.01)
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
        val result = parser.parseAll(nodes, "com.ubercab.driver")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.firstOrNull()
        assertNotNull(ride)
        assertEquals("Payment type should be cash", "cash", ride!!.paymentType.lowercase())
    }

    @Test
    fun `digital payment when no cash keyword`() {
        val nodes = listOf(
            "₹90",
            "8 mins (5.0 km)",
            "Match",
            "Uber"
        )
        val result = parser.parseAll(nodes, "com.ubercab.driver")

        assertTrue(result is ParseResult.Success)
        val ride = (result as ParseResult.Success).rides.firstOrNull()
        assertNotNull(ride)
        assertNotEquals("Payment type should not be cash", "cash", ride!!.paymentType.lowercase())
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun `empty input returns failure`() {
        val result = parser.parseAll(emptyList(), "com.ubercab.driver")
        assertTrue("Empty input should return failure", result is ParseResult.Failure || result is ParseResult.Idle)
    }

    @Test
    fun `fare below minimum threshold returns failure`() {
        val nodes = listOf(
            "₹5",
            "Match",
            "Uber"
        )
        val result = parser.parseAll(nodes, "com.ubercab.driver")
        assertTrue("Fare below threshold should return failure", result is ParseResult.Failure)
    }
}
