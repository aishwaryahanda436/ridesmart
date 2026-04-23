package com.ridesmart.engine

import com.ridesmart.model.ParsedRide
import com.ridesmart.model.RideResult
import com.ridesmart.model.Signal
import org.junit.Assert.*
import org.junit.Test

class CrossPlatformRankerTest {

    // ── Helper ─────────────────────────────────────────────────────────

    private fun makeRideResult(
        platform: String,
        signal: Signal,
        netProfit: Double,
        efficiencyPerKm: Double,
        decisionScore: Double,
        hardRejectReason: String? = null
    ): RideResult {
        val parsedRide = ParsedRide(
            baseFare       = netProfit + 20.0,
            rideDistanceKm = if (efficiencyPerKm > 0) netProfit / efficiencyPerKm else 1.0,
            platform       = platform,
            packageName    = "pkg.$platform"
        )
        return RideResult(
            parsedRide       = parsedRide,
            totalFare        = parsedRide.baseFare,
            actualPayout     = parsedRide.baseFare,
            fuelCost         = 10.0,
            wearCost         = 5.0,
            netProfit        = netProfit,
            netProfitCash    = netProfit + 3.0,
            efficiencyPerKm  = efficiencyPerKm,
            earningPerHour   = 0.0,
            pickupRatio      = 0.1,
            hardRejectReason = hardRejectReason,
            signal           = signal,
            failedChecks     = emptyList(),
            decisionScore    = decisionScore
        )
    }

    // ── Single ride ────────────────────────────────────────────────────

    @Test
    fun `single ride returns itself as winner with no runner-up`() {
        val ride = makeRideResult("Rapido", Signal.GREEN, 50.0, 5.0, 70.0)
        val output = CrossPlatformRanker.rank(listOf(ride))

        assertEquals("Winner is the only ride", ride, output.winner)
        assertNull("No runner-up for single ride", output.runnerUp)
        assertEquals("Ranked list has one element", 1, output.ranked.size)
        assertEquals("profitDelta is 0 for single ride", 0.0, output.profitDelta, 0.01)
        assertEquals("efficiencyDelta is 0 for single ride", 0.0, output.efficiencyDelta, 0.01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty list throws IllegalArgumentException`() {
        CrossPlatformRanker.rank(emptyList())
    }

    // ── Signal tier ────────────────────────────────────────────────────

    @Test
    fun `GREEN signal beats YELLOW regardless of score`() {
        val green  = makeRideResult("Rapido", Signal.GREEN,  40.0, 4.0, 60.0)
        val yellow = makeRideResult("Uber",   Signal.YELLOW, 80.0, 8.0, 90.0)

        val output = CrossPlatformRanker.rank(listOf(yellow, green))

        assertEquals("GREEN beats YELLOW", green, output.winner)
        assertEquals("RunnerUp is YELLOW", yellow, output.runnerUp)
        assertEquals("Won by signal tier", CrossPlatformRanker.Tier.SIGNAL, output.tierWon)
        assertTrue("Summary contains GREEN winner platform", output.summaryLine.contains("RAPIDO"))
    }

    @Test
    fun `GREEN signal beats RED regardless of score`() {
        val green = makeRideResult("Ola",  Signal.GREEN, 30.0, 3.0, 50.0)
        val red   = makeRideResult("Uber", Signal.RED,  100.0, 10.0, 100.0)

        val output = CrossPlatformRanker.rank(listOf(red, green))

        assertEquals("GREEN beats RED", green, output.winner)
        assertEquals("Won by signal tier", CrossPlatformRanker.Tier.SIGNAL, output.tierWon)
    }

    @Test
    fun `YELLOW signal beats RED signal`() {
        val yellow = makeRideResult("Rapido",    Signal.YELLOW, 20.0, 2.0, 30.0)
        val red    = makeRideResult("Shadowfax", Signal.RED,    50.0, 5.0, 80.0)

        val output = CrossPlatformRanker.rank(listOf(red, yellow))

        assertEquals("YELLOW beats RED", yellow, output.winner)
        assertEquals("Won by signal tier", CrossPlatformRanker.Tier.SIGNAL, output.tierWon)
    }

    // ── Score tier ─────────────────────────────────────────────────────

    @Test
    fun `when same signal higher decision score wins if gap exceeds threshold`() {
        // Both GREEN, score gap of 10 (threshold is 5.0)
        val highScore = makeRideResult("Rapido", Signal.GREEN, 50.0, 5.0, 80.0)
        val lowScore  = makeRideResult("Uber",   Signal.GREEN, 50.0, 5.0, 70.0)

        val output = CrossPlatformRanker.rank(listOf(lowScore, highScore))

        assertEquals("Higher decision score should win", highScore, output.winner)
        assertEquals("Won by score tier", CrossPlatformRanker.Tier.SCORE, output.tierWon)
    }

    // ── Efficiency tier ────────────────────────────────────────────────

    @Test
    fun `when same signal and close scores efficiency per km breaks tie`() {
        // Both GREEN, scores differ by only 2.0 (below 5.0 threshold)
        val highEfficiency = makeRideResult("Rapido", Signal.GREEN, 50.0, 6.0, 72.0)
        val lowEfficiency  = makeRideResult("Uber",   Signal.GREEN, 50.0, 4.0, 70.0)

        val output = CrossPlatformRanker.rank(listOf(lowEfficiency, highEfficiency))

        assertEquals("Higher efficiency wins when scores are close", highEfficiency, output.winner)
        assertEquals("Won by efficiency tier", CrossPlatformRanker.Tier.EFFICIENCY, output.tierWon)
    }

    // ── Output metrics ─────────────────────────────────────────────────

    @Test
    fun `profitDelta is winner profit minus runner-up profit`() {
        val winner   = makeRideResult("Rapido", Signal.GREEN, 60.0, 6.0, 80.0)
        val runnerUp = makeRideResult("Uber",   Signal.GREEN, 45.0, 4.0, 70.0)

        val output = CrossPlatformRanker.rank(listOf(runnerUp, winner))

        assertEquals("profitDelta = winner - runnerUp", 15.0, output.profitDelta, 0.01)
    }

    @Test
    fun `efficiencyDelta is winner efficiency minus runner-up efficiency`() {
        val winner   = makeRideResult("Rapido", Signal.GREEN, 50.0, 6.0, 80.0)
        val runnerUp = makeRideResult("Uber",   Signal.GREEN, 50.0, 4.0, 70.0)

        val output = CrossPlatformRanker.rank(listOf(runnerUp, winner))

        assertEquals("efficiencyDelta = winner - runnerUp", 2.0, output.efficiencyDelta, 0.01)
    }

    @Test
    fun `ranked list is sorted with winner first`() {
        val first  = makeRideResult("Rapido",    Signal.GREEN,  60.0, 6.0, 80.0)
        val second = makeRideResult("Uber",      Signal.YELLOW, 50.0, 5.0, 70.0)
        val third  = makeRideResult("Shadowfax", Signal.RED,    40.0, 4.0, 60.0)

        val output = CrossPlatformRanker.rank(listOf(third, first, second))

        assertEquals("First in ranked list is winner", first, output.ranked[0])
        assertEquals("Second in ranked list is runner-up", second, output.ranked[1])
        assertEquals("Third in ranked list is last", third, output.ranked[2])
    }

    @Test
    fun `summary line mentions both winner and runner-up platforms`() {
        val winner   = makeRideResult("Rapido", Signal.GREEN,  60.0, 6.0, 80.0)
        val runnerUp = makeRideResult("Uber",   Signal.YELLOW, 40.0, 4.0, 60.0)

        val output = CrossPlatformRanker.rank(listOf(runnerUp, winner))

        assertTrue("Summary contains winner platform", output.summaryLine.contains("RAPIDO"))
        assertTrue("Summary contains runner-up platform", output.summaryLine.contains("UBER"))
    }
}
