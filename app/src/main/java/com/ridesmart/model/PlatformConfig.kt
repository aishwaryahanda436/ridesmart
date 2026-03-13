package com.ridesmart.model

object PlatformConfig {

    data class Platform(
        val packageName: String,
        val displayName: String,
        val commissionPercent: Double,  // 0.0 = subscription model
        val subscriptionDailyCost: Double = 0.0
    )

    val ALL = listOf(
        Platform("com.rapido.rider",      "Rapido", 0.0, 0.0),
        Platform("in.rapido.captain",     "Rapido", 0.0, 0.0),
        Platform("com.rapido.captain",    "Rapido", 0.0, 0.0),
        Platform("com.ubercab.driver",    "Uber",   0.0, 0.0),
        Platform("com.ubercab",           "Uber",   0.0, 0.0),
        Platform("com.olacabs.oladriver", "Ola",    20.0, 0.0),
        Platform("com.olacabs.driver",    "Ola",    20.0, 0.0),
        Platform("com.ola.driver",        "Ola",    20.0, 0.0),
        Platform("in.juspay.nammayatri",        "Namma Yatri", 0.0, 0.0),
        Platform("in.juspay.nammayatripartner",  "Namma Yatri", 0.0, 0.0),
        Platform("net.openkochi.yatri",          "Namma Yatri", 0.0, 0.0),
        Platform("in.shadowfax.gandalf",  "Shadowfax", 0.0, 0.0),
        Platform("com.shadowfax.driver",  "Shadowfax", 0.0, 0.0),
        Platform("com.shadowfax.zeus",    "Shadowfax", 0.0, 0.0)
    )

    fun get(packageName: String) =
        ALL.find { it.packageName == packageName }
            ?: Platform(packageName, "Unknown", 20.0, 0.0)

    fun effectivePayout(grossFare: Double, packageName: String): Double {
        val p = get(packageName)
        return if (p.commissionPercent > 0.0) {
            grossFare * (1.0 - p.commissionPercent / 100.0)
        } else {
            grossFare  // subscription cost is a daily overhead, not per-ride
        }
    }
}

