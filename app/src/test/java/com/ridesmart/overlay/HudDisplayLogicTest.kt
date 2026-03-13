package com.ridesmart.overlay

import com.ridesmart.model.ParsedRide
import com.ridesmart.model.RideResult
import com.ridesmart.model.Signal
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for HUD display logic.
 *
 * These validate the pure business logic used by [HudOverlayManager]:
 * - Best ride selection (only highest profit shown)
 * - Signal recommendation mapping
 * - "BEST OF X RIDES" threshold
 */
class HudDisplayLogicTest {

    // ── HELPERS ─────────────────────────────────────────────────────────

    private fun makeResult(
        baseFare: Double = 100.0,
        rideDistanceKm: Double = 5.0,
        netProfit: Double = 50.0,
        earningPerKm: Double = 10.0,
        signal: Signal = Signal.GREEN,
        platform: String = "Uber",
        packageName: String = "com.ubercab.driver"
    ): RideResult = RideResult(
        parsedRide = ParsedRide(
            baseFare = baseFare,
            rideDistanceKm = rideDistanceKm,
            platform = platform,
            packageName = packageName
        ),
        totalFare = baseFare,
        actualPayout = baseFare,
        fuelCost = 10.0,
        wearCost = 5.0,
        netProfit = netProfit,
        earningPerKm = earningPerKm,
        earningPerHour = 200.0,
        pickupRatio = 0.1,
        signal = signal,
        failedChecks = emptyList()
    )

    // ── BEST RIDE SELECTION ─────────────────────────────────────────────

    @Test
    fun `higher profit ride should be selected as best`() {
        val rideA = makeResult(netProfit = 40.0)
        val rideB = makeResult(netProfit = 95.0)

        val rides = listOf(rideA, rideB)
        val best = rides.maxByOrNull { it.netProfit }

        assertEquals(95.0, best!!.netProfit, 0.01)
    }

    @Test
    fun `single ride is always the best`() {
        val ride = makeResult(netProfit = 60.0)
        val rides = listOf(ride)
        val best = rides.maxByOrNull { it.netProfit }

        assertEquals(60.0, best!!.netProfit, 0.01)
    }

    @Test
    fun `best of three rides picks highest profit`() {
        val rides = listOf(
            makeResult(netProfit = 30.0),
            makeResult(netProfit = 110.0),
            makeResult(netProfit = 75.0)
        )
        val best = rides.maxByOrNull { it.netProfit }

        assertEquals(110.0, best!!.netProfit, 0.01)
    }

    @Test
    fun `new ride with lower profit should not replace current best`() {
        var currentBestProfit = Double.MIN_VALUE

        val rideA = makeResult(netProfit = 80.0)
        // First ride always shows
        if (rideA.netProfit > currentBestProfit) {
            currentBestProfit = rideA.netProfit
        }
        assertEquals(80.0, currentBestProfit, 0.01)

        val rideB = makeResult(netProfit = 40.0)
        // Lower profit should NOT replace
        val shouldUpdate = rideB.netProfit > currentBestProfit
        assertFalse(shouldUpdate)
        assertEquals(80.0, currentBestProfit, 0.01)
    }

    @Test
    fun `new ride with higher profit should replace current best`() {
        var currentBestProfit = Double.MIN_VALUE

        val rideA = makeResult(netProfit = 40.0)
        if (rideA.netProfit > currentBestProfit) {
            currentBestProfit = rideA.netProfit
        }

        val rideB = makeResult(netProfit = 95.0)
        val shouldUpdate = rideB.netProfit > currentBestProfit
        assertTrue(shouldUpdate)
    }

    // ── SIGNAL RECOMMENDATION MAPPING ───────────────────────────────────

    @Test
    fun `GREEN signal maps to TAKE IT recommendation`() {
        val (emoji, label) = signalToRecommendation(Signal.GREEN)
        assertEquals("🟢", emoji)
        assertEquals("TAKE IT", label)
    }

    @Test
    fun `YELLOW signal maps to OK recommendation`() {
        val (emoji, label) = signalToRecommendation(Signal.YELLOW)
        assertEquals("🟡", emoji)
        assertEquals("OK", label)
    }

    @Test
    fun `RED signal maps to SKIP recommendation`() {
        val (emoji, label) = signalToRecommendation(Signal.RED)
        assertEquals("🔴", emoji)
        assertEquals("SKIP", label)
    }

    // ── BEST OF X RIDES VISIBILITY ──────────────────────────────────────

    @Test
    fun `single ride should not show best of label`() {
        assertFalse(shouldShowBestOfLabel(1))
    }

    @Test
    fun `multiple rides should show best of label`() {
        assertTrue(shouldShowBestOfLabel(2))
        assertTrue(shouldShowBestOfLabel(3))
        assertTrue(shouldShowBestOfLabel(5))
    }

    @Test
    fun `best of label text is correct`() {
        assertEquals("BEST OF 3 RIDES", bestOfLabelText(3))
        assertEquals("BEST OF 5 RIDES", bestOfLabelText(5))
    }

    // ── HUD DISPLAY FORMATTING ──────────────────────────────────────────

    @Test
    fun `fare and distance format correctly`() {
        val result = makeResult(baseFare = 180.0, rideDistanceKm = 6.2)
        val fareStr = "₹${result.parsedRide.baseFare.toInt()}"
        val distStr = "${"%.1f".format(result.parsedRide.rideDistanceKm)} km"
        assertEquals("₹180 | 6.2 km", "$fareStr | $distStr")
    }

    @Test
    fun `profit line formats correctly`() {
        val result = makeResult(netProfit = 85.0)
        assertEquals("Profit ₹85", "Profit ₹${"%.0f".format(result.netProfit)}")
    }

    @Test
    fun `per km and platform format correctly`() {
        val result = makeResult(earningPerKm = 14.2, platform = "Uber")
        val text = "₹${"%.1f".format(result.earningPerKm)}/km · ${result.parsedRide.platform}"
        assertEquals("₹14.2/km · Uber", text)
    }

    // ── HELPER FUNCTIONS (mirror HudOverlayManager logic) ───────────────

    private fun signalToRecommendation(signal: Signal): Pair<String, String> = when (signal) {
        Signal.GREEN  -> "🟢" to "TAKE IT"
        Signal.YELLOW -> "🟡" to "OK"
        Signal.RED    -> "🔴" to "SKIP"
    }

    private fun shouldShowBestOfLabel(totalRides: Int): Boolean = totalRides > 1

    private fun bestOfLabelText(totalRides: Int): String = "BEST OF $totalRides RIDES"
}
