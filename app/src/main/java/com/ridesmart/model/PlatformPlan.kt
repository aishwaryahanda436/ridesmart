package com.ridesmart.model

enum class PlanType {
    COMMISSION,  // Platform deducts a % from each fare (e.g. Ola 20%)
    PASS         // Rider pays a fixed daily/weekly/monthly subscription
                 // Pass cost is NOT deducted per ride — settled in dashboard
}

data class PlatformPlan(
    val planType: PlanType = PlanType.COMMISSION,

    // Used when planType == COMMISSION
    val commissionPercent: Double = 0.0,
    // e.g. 20.0 for Ola, 10.0 for inDrive, 0.0 for Rapido subscription

    // Used when planType == PASS
    val passAmount: Double = 0.0,
    // Total cost of the pass in ₹ (e.g. 30.0 for Rapido daily pass)

    val passDurationDays: Int = 1,
    // How many days the pass covers (1, 3, 7, 20, etc.)
)

data class IncentiveProfile(
    val enabled: Boolean = false,
    val targetRides: Int = 0,
    // Total rides needed to unlock the reward

    val rewardAmount: Double = 0.0,
    // ₹ reward unlocked after completing targetRides

    val completedToday: Int = 0
    // How many rides the driver has completed toward this target today.
    // Updated manually by the driver. Used for bMarg calculation.
)
