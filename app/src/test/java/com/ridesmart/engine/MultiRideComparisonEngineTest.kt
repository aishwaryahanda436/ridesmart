package com.ridesmart.engine

import com.ridesmart.model.ComparisonResult
import com.ridesmart.model.ParsedRide
import com.ridesmart.model.RideOffer
import com.ridesmart.model.RiderProfile
import com.ridesmart.model.Signal
import com.ridesmart.model.VehicleType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MultiRideComparisonEngineTest {

    private lateinit var engine: MultiRideComparisonEngine
    private lateinit var profile: RiderProfile

    @Before
    fun setUp() {
        engine = MultiRideComparisonEngine()
        profile = RiderProfile(
            mileageKmPerLitre         = 45.0,
            fuelPricePerLitre         = 102.0,
            maintenancePerKm          = 0.80,
            depreciationPerKm         = 0.30,
            minAcceptableNetProfit    = 30.0,
            minAcceptablePerKm        = 3.50,
            targetEarningPerHour      = 200.0,
            platformCommissionPercent = 0.0,
            cityAvgSpeedKmH           = 25.0,
            congestionFactor          = 1.3
        )
    }

    // ── HELPER ──────────────────────────────────────────────────────

    private fun offer(
        id: String,
        baseFare: Double,
        rideDistanceKm: Double,
        pickupDistanceKm: Double = 0.5,
        estimatedDurationMin: Int = 15,
        platform: String = "Rapido",
        packageName: String = "com.rapido.rider",
        surgeMultiplier: Double = 1.0,
        bonus: Double = 0.0,
        isDelivery: Boolean = false,
        expiryMs: Long = 10_000L,
        detectedAtMs: Long = System.currentTimeMillis()
    ) = RideOffer(
        id = id,
        parsedRide = ParsedRide(
            baseFare = baseFare,
            rideDistanceKm = rideDistanceKm,
            pickupDistanceKm = pickupDistanceKm,
            estimatedDurationMin = estimatedDurationMin,
            platform = platform,
            packageName = packageName,
            surgeMultiplier = surgeMultiplier,
            bonus = bonus,
            isDelivery = isDelivery
        ),
        expiryMs = expiryMs,
        detectedAtMs = detectedAtMs
    )

    // ── BASIC MULTI-RIDE COMPARISON ─────────────────────────────────

    @Test
    fun `compare 6 rides from 3 platforms recommends best`() {
        // Scenario from issue: Uber 3, Rapido 1, Shadowfax 2
        val offers = listOf(
            offer("u1", baseFare = 120.0, rideDistanceKm = 10.0, pickupDistanceKm = 1.0,
                estimatedDurationMin = 20, platform = "Uber", packageName = "com.ubercab.driver"),
            offer("u2", baseFare = 80.0, rideDistanceKm = 6.0, pickupDistanceKm = 2.0,
                estimatedDurationMin = 15, platform = "Uber", packageName = "com.ubercab.driver"),
            offer("u3", baseFare = 200.0, rideDistanceKm = 18.0, pickupDistanceKm = 0.5,
                estimatedDurationMin = 30, platform = "Uber", packageName = "com.ubercab.driver",
                surgeMultiplier = 1.5),
            offer("r1", baseFare = 55.0, rideDistanceKm = 5.0, pickupDistanceKm = 1.5,
                estimatedDurationMin = 12, platform = "Rapido"),
            offer("s1", baseFare = 90.0, rideDistanceKm = 8.0, pickupDistanceKm = 0.8,
                estimatedDurationMin = 18, platform = "Shadowfax", packageName = "in.shadowfax.gandalf",
                isDelivery = true),
            offer("s2", baseFare = 45.0, rideDistanceKm = 4.0, pickupDistanceKm = 3.0,
                estimatedDurationMin = 10, platform = "Shadowfax", packageName = "in.shadowfax.gandalf",
                isDelivery = true)
        )

        val result = engine.compareOffers(offers, profile, 12)

        assertEquals("all 6 rides ranked", 6, result.rankedRides.size)
        assertNotNull("recommendation exists", result.recommended)
        assertEquals("top ride is rank 1", 1, result.recommended!!.rank)
        assertTrue("evaluation under 50ms", result.evaluationTimeMs < 50)
        assertEquals("no rides skipped", 0, result.skippedCount)

        // Ranks should be 1-6, all distinct
        val ranks = result.rankedRides.map { it.rank }.toSet()
        assertEquals("unique ranks", 6, ranks.size)
        assertTrue("ranks 1-6", ranks.containsAll(listOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `single ride returns that ride as recommended`() {
        val offers = listOf(
            offer("only", baseFare = 80.0, rideDistanceKm = 7.0)
        )

        val result = engine.compareOffers(offers, profile, 12)

        assertEquals(1, result.rankedRides.size)
        assertEquals("only", result.recommended!!.rideOffer.id)
        assertEquals(1, result.recommended!!.rank)
        assertFalse("no tie for single ride", result.tieWarning)
    }

    @Test
    fun `empty offer list returns null recommendation`() {
        val result = engine.compareOffers(emptyList(), profile, 12)

        assertTrue(result.rankedRides.isEmpty())
        assertNull(result.recommended)
        assertEquals(0, result.skippedCount)
    }

    // ── RELATIVE SCORING ────────────────────────────────────────────

    @Test
    fun `higher fare ride scores better than low fare ride`() {
        val highFare = offer("high", baseFare = 150.0, rideDistanceKm = 10.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 20)
        val lowFare = offer("low", baseFare = 40.0, rideDistanceKm = 10.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 20)

        val result = engine.compareOffers(listOf(highFare, lowFare), profile, 12)

        assertEquals("high", result.recommended!!.rideOffer.id)
        assertTrue("high fare has higher relative score",
            result.rankedRides[0].relativeScore > result.rankedRides[1].relativeScore)
    }

    @Test
    fun `shorter pickup preferred over longer pickup same fare`() {
        val shortPickup = offer("short", baseFare = 80.0, rideDistanceKm = 8.0,
            pickupDistanceKm = 0.5, estimatedDurationMin = 15)
        val longPickup = offer("long", baseFare = 80.0, rideDistanceKm = 8.0,
            pickupDistanceKm = 5.0, estimatedDurationMin = 15)

        val result = engine.compareOffers(listOf(shortPickup, longPickup), profile, 12)

        assertEquals("short", result.recommended!!.rideOffer.id)
    }

    @Test
    fun `relative scores are between 0 and 100`() {
        val offers = listOf(
            offer("a", baseFare = 200.0, rideDistanceKm = 15.0, estimatedDurationMin = 25),
            offer("b", baseFare = 50.0, rideDistanceKm = 3.0, estimatedDurationMin = 8),
            offer("c", baseFare = 100.0, rideDistanceKm = 8.0, estimatedDurationMin = 15)
        )

        val result = engine.compareOffers(offers, profile, 12)

        for (ranked in result.rankedRides) {
            assertTrue("score >= 0: ${ranked.relativeScore}", ranked.relativeScore >= 0.0)
            assertTrue("score <= 100: ${ranked.relativeScore}", ranked.relativeScore <= 100.0)
        }
    }

    // ── EDGE CASES ──────────────────────────────────────────────────

    @Test
    fun `incomplete ride data is skipped`() {
        val good = offer("good", baseFare = 80.0, rideDistanceKm = 7.0)
        val noFare = offer("noFare", baseFare = 0.0, rideDistanceKm = 5.0)
        val noDistance = offer("noDist", baseFare = 50.0, rideDistanceKm = 0.0)

        val result = engine.compareOffers(listOf(good, noFare, noDistance), profile, 12)

        assertEquals("only 1 valid ride", 1, result.rankedRides.size)
        assertEquals("2 skipped", 2, result.skippedCount)
        assertEquals("good", result.recommended!!.rideOffer.id)
    }

    @Test
    fun `expired rides are pruned from pool`() {
        val pool = RidePool()
        val now = System.currentTimeMillis()

        pool.add(offer("fresh", baseFare = 80.0, rideDistanceKm = 7.0,
            detectedAtMs = now, expiryMs = 10_000L))
        pool.add(offer("expired", baseFare = 120.0, rideDistanceKm = 10.0,
            detectedAtMs = now - 20_000L, expiryMs = 10_000L))

        val result = engine.compare(pool, profile, 12, now = now)

        assertEquals("only fresh ride", 1, result.rankedRides.size)
        assertEquals("fresh", result.recommended!!.rideOffer.id)
    }

    @Test
    fun `very large pickup distance penalised`() {
        val nearPickup = offer("near", baseFare = 80.0, rideDistanceKm = 8.0,
            pickupDistanceKm = 0.5, estimatedDurationMin = 15)
        val farPickup = offer("far", baseFare = 90.0, rideDistanceKm = 8.0,
            pickupDistanceKm = 12.0, estimatedDurationMin = 15)

        val result = engine.compareOffers(listOf(nearPickup, farPickup), profile, 12)

        // Near pickup should win despite lower fare due to better net profit and efficiency
        assertEquals("near", result.recommended!!.rideOffer.id)
    }

    @Test
    fun `similar rides trigger tie warning`() {
        // Three rides where the top two are nearly identical —
        // the third ride anchors the normalisation range so the close
        // pair lands within the TIE_THRESHOLD (5 pts).
        val a = offer("a", baseFare = 80.0, rideDistanceKm = 7.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 15)
        val b = offer("b", baseFare = 80.5, rideDistanceKm = 7.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 15)
        val anchor = offer("anchor", baseFare = 30.0, rideDistanceKm = 7.0,
            pickupDistanceKm = 5.0, estimatedDurationMin = 15)

        val result = engine.compareOffers(listOf(a, b, anchor), profile, 12)

        assertTrue("tie warning expected", result.tieWarning)
    }

    @Test
    fun `delivery and passenger rides compared together`() {
        val passenger = offer("pass", baseFare = 80.0, rideDistanceKm = 7.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 15, isDelivery = false)
        val delivery = offer("del", baseFare = 90.0, rideDistanceKm = 6.0,
            pickupDistanceKm = 0.5, estimatedDurationMin = 12,
            platform = "Shadowfax", packageName = "in.shadowfax.gandalf", isDelivery = true)

        val result = engine.compareOffers(listOf(passenger, delivery), profile, 12)

        // Both should be evaluated; delivery's isDelivery flag preserved
        assertEquals(2, result.rankedRides.size)
        val delRanked = result.rankedRides.find { it.rideOffer.id == "del" }!!
        assertTrue("delivery flag preserved", delRanked.rideOffer.isDelivery)
    }

    // ── RIDE POOL TESTS ─────────────────────────────────────────────

    @Test
    fun `RidePool add and retrieve`() {
        val pool = RidePool()
        pool.add(offer("a", baseFare = 50.0, rideDistanceKm = 5.0))
        pool.add(offer("b", baseFare = 60.0, rideDistanceKm = 6.0))

        val active = pool.getActiveOffers()
        assertEquals(2, active.size)
    }

    @Test
    fun `RidePool enforces max size`() {
        val pool = RidePool(maxSize = 3)
        pool.add(offer("1", baseFare = 50.0, rideDistanceKm = 5.0))
        pool.add(offer("2", baseFare = 60.0, rideDistanceKm = 6.0))
        pool.add(offer("3", baseFare = 70.0, rideDistanceKm = 7.0))
        pool.add(offer("4", baseFare = 80.0, rideDistanceKm = 8.0))

        val active = pool.getActiveOffers()
        assertEquals("max 3 rides", 3, active.size)
        // Oldest (1) should be dropped
        assertFalse("oldest dropped", active.any { it.id == "1" })
        assertTrue("newest kept", active.any { it.id == "4" })
    }

    @Test
    fun `RidePool prunes expired offers`() {
        val pool = RidePool()
        val now = System.currentTimeMillis()

        pool.add(offer("old", baseFare = 50.0, rideDistanceKm = 5.0,
            detectedAtMs = now - 20_000L, expiryMs = 10_000L))
        pool.add(offer("new", baseFare = 60.0, rideDistanceKm = 6.0,
            detectedAtMs = now, expiryMs = 10_000L))

        assertEquals(1, pool.size(now))
        assertFalse(pool.isEmpty(now))
    }

    @Test
    fun `RidePool remove by id`() {
        val pool = RidePool()
        pool.add(offer("a", baseFare = 50.0, rideDistanceKm = 5.0))
        pool.add(offer("b", baseFare = 60.0, rideDistanceKm = 6.0))

        pool.remove("a")
        val active = pool.getActiveOffers()
        assertEquals(1, active.size)
        assertEquals("b", active[0].id)
    }

    @Test
    fun `RidePool clear removes all`() {
        val pool = RidePool()
        pool.add(offer("a", baseFare = 50.0, rideDistanceKm = 5.0))
        pool.add(offer("b", baseFare = 60.0, rideDistanceKm = 6.0))
        pool.clear()

        assertTrue(pool.isEmpty())
    }

    // ── RIDE OFFER MODEL TESTS ──────────────────────────────────────

    @Test
    fun `RideOffer isExpired returns true after expiry`() {
        val now = System.currentTimeMillis()
        val offer = offer("x", baseFare = 50.0, rideDistanceKm = 5.0,
            detectedAtMs = now - 15_000L, expiryMs = 10_000L)

        assertTrue(offer.isExpired(now))
    }

    @Test
    fun `RideOffer isExpired returns false before expiry`() {
        val now = System.currentTimeMillis()
        val offer = offer("x", baseFare = 50.0, rideDistanceKm = 5.0,
            detectedAtMs = now, expiryMs = 10_000L)

        assertFalse(offer.isExpired(now))
    }

    @Test
    fun `RideOffer hasMinimumData checks baseFare and distance`() {
        assertTrue(offer("ok", baseFare = 50.0, rideDistanceKm = 5.0).hasMinimumData())
        assertFalse(offer("noFare", baseFare = 0.0, rideDistanceKm = 5.0).hasMinimumData())
        assertFalse(offer("noDist", baseFare = 50.0, rideDistanceKm = 0.0).hasMinimumData())
    }

    // ── PERFORMANCE ─────────────────────────────────────────────────

    @Test
    fun `compare 20 rides completes under 50ms`() {
        val offers = (1..20).map { i ->
            offer("r$i",
                baseFare = 30.0 + i * 10.0,
                rideDistanceKm = 3.0 + i * 0.5,
                pickupDistanceKm = 0.5 + i * 0.2,
                estimatedDurationMin = 10 + i
            )
        }

        val result = engine.compareOffers(offers, profile, 12)

        assertEquals(20, result.rankedRides.size)
        // Allow generous margin for CI; real devices target < 50ms
        assertTrue("evaluation under 200ms (CI margin): ${result.evaluationTimeMs}ms",
            result.evaluationTimeMs < 200)
    }

    // ── POOL-BASED COMPARISON ───────────────────────────────────────

    @Test
    fun `compare via pool uses active offers only`() {
        val pool = RidePool()
        val now = System.currentTimeMillis()

        pool.add(offer("a", baseFare = 80.0, rideDistanceKm = 7.0,
            detectedAtMs = now, expiryMs = 30_000L))
        pool.add(offer("b", baseFare = 100.0, rideDistanceKm = 9.0,
            detectedAtMs = now, expiryMs = 30_000L))
        pool.add(offer("c", baseFare = 60.0, rideDistanceKm = 5.0,
            detectedAtMs = now, expiryMs = 30_000L))

        val result = engine.compare(pool, profile, 12, now = now)

        assertEquals(3, result.rankedRides.size)
        assertNotNull(result.recommended)
        assertEquals(1, result.recommended!!.rank)
    }

    @Test
    fun `ranks are consistent and descending by score`() {
        val offers = listOf(
            offer("a", baseFare = 50.0, rideDistanceKm = 5.0, estimatedDurationMin = 10),
            offer("b", baseFare = 100.0, rideDistanceKm = 8.0, estimatedDurationMin = 15),
            offer("c", baseFare = 150.0, rideDistanceKm = 12.0, estimatedDurationMin = 20),
            offer("d", baseFare = 30.0, rideDistanceKm = 3.0, estimatedDurationMin = 8)
        )

        val result = engine.compareOffers(offers, profile, 12)

        // Scores should be non-increasing (rank 1 >= rank 2 >= ...)
        for (i in 0 until result.rankedRides.size - 1) {
            assertTrue("rank ${i + 1} score >= rank ${i + 2} score",
                result.rankedRides[i].relativeScore >= result.rankedRides[i + 1].relativeScore)
        }
    }

    @Test
    fun `profitResult is populated for each ranked ride`() {
        val offers = listOf(
            offer("a", baseFare = 80.0, rideDistanceKm = 7.0, estimatedDurationMin = 15),
            offer("b", baseFare = 100.0, rideDistanceKm = 9.0, estimatedDurationMin = 18)
        )

        val result = engine.compareOffers(offers, profile, 12)

        for (ranked in result.rankedRides) {
            assertTrue("netProfit calculated", ranked.profitResult.netProfit != 0.0)
            assertTrue("rideScore calculated", ranked.profitResult.rideScore > 0.0)
        }
    }

    @Test
    fun `surge ride beats non-surge ride with same base fare`() {
        val surged = offer("surge", baseFare = 80.0, rideDistanceKm = 7.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 15, surgeMultiplier = 2.0)
        val normal = offer("normal", baseFare = 80.0, rideDistanceKm = 7.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 15, surgeMultiplier = 1.0)

        val result = engine.compareOffers(listOf(surged, normal), profile, 12)

        assertEquals("surge ride wins", "surge", result.recommended!!.rideOffer.id)
    }

    @Test
    fun `bonus ride preferred over no-bonus ride`() {
        val bonused = offer("bonus", baseFare = 70.0, rideDistanceKm = 7.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 15, bonus = 30.0)
        val plain = offer("plain", baseFare = 70.0, rideDistanceKm = 7.0,
            pickupDistanceKm = 1.0, estimatedDurationMin = 15, bonus = 0.0)

        val result = engine.compareOffers(listOf(bonused, plain), profile, 12)

        assertEquals("bonus ride wins", "bonus", result.recommended!!.rideOffer.id)
    }

    // ── EXISTING TESTS STILL PASS ───────────────────────────────────

    @Test
    fun `existing ProfitCalculator results are preserved in comparison`() {
        val calc = ProfitCalculator()
        val ride = ParsedRide(
            baseFare = 80.0, rideDistanceKm = 7.0, pickupDistanceKm = 1.0,
            estimatedDurationMin = 15, packageName = "com.rapido.rider"
        )
        val directResult = calc.calculate(ride, profile, 12)

        val offer = RideOffer(id = "direct", parsedRide = ride)
        val compared = engine.compareOffers(listOf(offer), profile, 12)

        val comparedResult = compared.recommended!!.profitResult

        // Single-ride comparison should produce identical ProfitResult
        assertEquals(directResult.netProfit, comparedResult.netProfit, 0.01)
        assertEquals(directResult.rideScore, comparedResult.rideScore, 0.01)
        assertEquals(directResult.signal, comparedResult.signal)
        assertEquals(directResult.earningPerKm, comparedResult.earningPerKm, 0.01)
    }

    @Test
    fun `all skipped when all rides have incomplete data`() {
        val offers = listOf(
            offer("a", baseFare = 0.0, rideDistanceKm = 5.0),
            offer("b", baseFare = 50.0, rideDistanceKm = 0.0)
        )

        val result = engine.compareOffers(offers, profile, 12)

        assertTrue(result.rankedRides.isEmpty())
        assertNull(result.recommended)
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun `addAll adds multiple offers to pool`() {
        val pool = RidePool()
        val offers = listOf(
            offer("a", baseFare = 50.0, rideDistanceKm = 5.0),
            offer("b", baseFare = 60.0, rideDistanceKm = 6.0),
            offer("c", baseFare = 70.0, rideDistanceKm = 7.0)
        )
        pool.addAll(offers)
        assertEquals(3, pool.size())
    }
}
