package com.ridesmart.model

import org.junit.Assert.*
import org.junit.Test

class PlatformConfigTest {

    // ── get() ─────────────────────────────────────────────────────────

    @Test
    fun `get returns correct config for Rapido`() {
        val platform = PlatformConfig.get("com.rapido.rider")
        assertEquals("Rapido", platform.displayName)
        assertEquals(0.0, platform.commissionPercent, 0.01)
        assertEquals(67.0, platform.subscriptionDailyCost, 0.01)
    }

    @Test
    fun `get returns correct config for Uber`() {
        val platform = PlatformConfig.get("com.ubercab.driver")
        assertEquals("Uber", platform.displayName)
        assertEquals(20.0, platform.commissionPercent, 0.01)
        assertEquals(0.0, platform.subscriptionDailyCost, 0.01)
    }

    @Test
    fun `get returns correct config for Ola`() {
        val platform = PlatformConfig.get("com.olacabs.oladriver")
        assertEquals("Ola", platform.displayName)
        assertEquals(20.0, platform.commissionPercent, 0.01)
    }

    @Test
    fun `get returns correct config for Shadowfax`() {
        val platform = PlatformConfig.get("in.shadowfax.gandalf")
        assertEquals("Shadowfax", platform.displayName)
        assertEquals(0.0, platform.commissionPercent, 0.01)
    }

    @Test
    fun `get returns correct config for inDrive`() {
        val platform = PlatformConfig.get("sinet.startup.inDriver")
        assertEquals("inDrive", platform.displayName)
        assertEquals(10.0, platform.commissionPercent, 0.01)
    }

    @Test
    fun `get returns Unknown platform with zero commission for unknown package`() {
        val platform = PlatformConfig.get("com.completely.unknown.app")
        assertEquals("Unknown", platform.displayName)
        assertEquals(0.0, platform.commissionPercent, 0.01)
        assertEquals(0.0, platform.subscriptionDailyCost, 0.01)
    }

    @Test
    fun `get returns Unknown for empty package name`() {
        val platform = PlatformConfig.get("")
        assertEquals("Unknown", platform.displayName)
    }

    // ── effectivePayout() ─────────────────────────────────────────────

    @Test
    fun `effectivePayout deducts 20 percent commission for Uber`() {
        val payout = PlatformConfig.effectivePayout(100.0, "com.ubercab.driver")
        assertEquals("Uber payout after 20% commission", 80.0, payout, 0.01)
    }

    @Test
    fun `effectivePayout deducts 20 percent commission for Ola`() {
        val payout = PlatformConfig.effectivePayout(100.0, "com.olacabs.oladriver")
        assertEquals("Ola payout after 20% commission", 80.0, payout, 0.01)
    }

    @Test
    fun `effectivePayout returns full fare for Rapido subscription model`() {
        val payout = PlatformConfig.effectivePayout(80.0, "com.rapido.rider")
        assertEquals("Rapido subscription: no per-ride commission", 80.0, payout, 0.01)
    }

    @Test
    fun `effectivePayout returns full fare for Shadowfax zero commission`() {
        val payout = PlatformConfig.effectivePayout(75.0, "in.shadowfax.gandalf")
        assertEquals("Shadowfax: no per-ride commission", 75.0, payout, 0.01)
    }

    @Test
    fun `effectivePayout deducts 10 percent commission for inDrive`() {
        val payout = PlatformConfig.effectivePayout(100.0, "sinet.startup.inDriver")
        assertEquals("inDrive payout after 10% commission", 90.0, payout, 0.01)
    }

    @Test
    fun `effectivePayout deducts 15 percent commission for Porter`() {
        val payout = PlatformConfig.effectivePayout(100.0, "porter.in.android")
        assertEquals("Porter payout after 15% commission", 85.0, payout, 0.01)
    }

    @Test
    fun `effectivePayout returns full fare for unknown package`() {
        val payout = PlatformConfig.effectivePayout(90.0, "com.unknown.app")
        assertEquals("Unknown platform: no commission", 90.0, payout, 0.01)
    }

    @Test
    fun `effectivePayout handles zero fare`() {
        val payout = PlatformConfig.effectivePayout(0.0, "com.ubercab.driver")
        assertEquals("Zero fare stays zero", 0.0, payout, 0.01)
    }

    @Test
    fun `effectivePayout handles fractional fares accurately`() {
        val payout = PlatformConfig.effectivePayout(74.40, "com.ubercab.driver")
        assertEquals("Fractional fare with 20% commission", 59.52, payout, 0.01)
    }

    // ── ALL list completeness ─────────────────────────────────────────

    @Test
    fun `ALL list contains at least 10 platforms`() {
        assertTrue("Platform list should be comprehensive", PlatformConfig.ALL.size >= 10)
    }

    @Test
    fun `ALL platform package names are unique`() {
        val packages = PlatformConfig.ALL.map { it.packageName }
        assertEquals("No duplicate package names", packages.size, packages.distinct().size)
    }

    @Test
    fun `ALL platform display names are unique`() {
        val names = PlatformConfig.ALL.map { it.displayName }
        assertEquals("No duplicate display names", names.size, names.distinct().size)
    }

    @Test
    fun `commissionPercent is non-negative for all platforms`() {
        PlatformConfig.ALL.forEach { platform ->
            assertTrue(
                "Commission for ${platform.displayName} should be >= 0",
                platform.commissionPercent >= 0.0
            )
        }
    }

    @Test
    fun `subscriptionDailyCost is non-negative for all platforms`() {
        PlatformConfig.ALL.forEach { platform ->
            assertTrue(
                "Subscription cost for ${platform.displayName} should be >= 0",
                platform.subscriptionDailyCost >= 0.0
            )
        }
    }
}
