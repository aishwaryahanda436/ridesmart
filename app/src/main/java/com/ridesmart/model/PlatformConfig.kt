package com.ridesmart.model

object PlatformConfig {

    data class Platform(
        val packageName: String,
        val displayName: String,
        val commissionPercent: Double,  // 0.0 = subscription model
        val subscriptionDailyCost: Double = 0.0
    )

    val ALL = listOf(
        Platform("com.rapido.rider",            "Rapido",       0.0,  67.0),
        Platform("com.ubercab.driver",          "Uber",         20.0, 0.0), // Bug 2B Fixed: 20% default for Uber India
        Platform("com.olacabs.oladriver",       "Ola",          20.0, 0.0),
        Platform("in.shadowfax.gandalf",        "Shadowfax",    0.0,  0.0),
        Platform("in.juspay.nammayatripartner", "Namma Yatri",  0.0,  0.0),
        Platform("net.openkochi.yatripartner",  "Yatri",        0.0,  0.0),
        Platform("sinet.startup.inDriver",      "inDrive",      10.0, 0.0),
        Platform("com.meru.merumobile",         "Meru",         20.0, 0.0),
        Platform("in.swiggy.partner",           "Swiggy",       0.0,  0.0),
        Platform("com.zomato.captain",          "Zomato",       0.0,  0.0),
        Platform("porter.in.android",           "Porter",       15.0, 0.0)
    )

    fun get(packageName: String) =
        ALL.find { it.packageName == packageName }
            ?: Platform(packageName, "Unknown", 0.0, 0.0)

    fun effectivePayout(grossFare: Double, packageName: String): Double {
        val p = get(packageName)
        return if (p.commissionPercent > 0.0) {
            grossFare * (1.0 - p.commissionPercent / 100.0)
        } else {
            grossFare  // subscription cost is a daily overhead, not per-ride
        }
    }
}
