package com.ridesmart.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridesmart.data.DailyPlatformRow
import com.ridesmart.data.RideHistoryRepository
import com.ridesmart.data.ProfileRepository
import com.ridesmart.model.IncentiveProfile
import com.ridesmart.model.PlatformPlan
import com.ridesmart.model.PlanType
import com.ridesmart.model.RiderProfile
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val rideRepo    = RideHistoryRepository(application)
    private val profileRepo = ProfileRepository(application)

    private val _dayOffset = MutableStateFlow(0)
    // 0 = today, -1 = yesterday, -2 = two days ago (max -30)
    val dayOffset: StateFlow<Int> = _dayOffset.asStateFlow()

    // ── Offset-aware date range ─────────────────────────────────────
    private fun startOfDayMs(offset: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun endOfDayMs(offset: Int = 0): Long =
        startOfDayMs(offset) + 24 * 60 * 60 * 1000L - 1L

    // ── Rider profile ───────────────────────────────────────────────
    val profile: StateFlow<RiderProfile> = profileRepo.profileFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RiderProfile())

    // ── Platform rows (from Room) reacting to dayOffset ─────────────
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val platformRows: StateFlow<List<DailyPlatformRow>> =
        _dayOffset.flatMapLatest { offset ->
            rideRepo.getDailyPlatformSummary(startOfDayMs(offset), endOfDayMs(offset))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dayLabel: StateFlow<String> = _dayOffset.map { offset ->
        when (offset) {
            0  -> "Today"
            -1 -> "Yesterday"
            else -> {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, offset)
                java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault())
                    .format(cal.time)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Today")

    // ── Settled day profit (with pass deduction + incentive unlock) ─
    val settledSummary: StateFlow<SettledDaySummary> = combine(
        platformRows, profile
    ) { rows, prof ->
        computeSettledSummary(rows, prof)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettledDaySummary())

    private fun computeSettledSummary(
        rows: List<DailyPlatformRow>,
        profile: RiderProfile
    ): SettledDaySummary {
        var totalGross           = 0.0
        var totalRideCost        = 0.0   // fuel + wear across all rides
        var totalCommission      = 0.0
        var totalPassCost        = 0.0
        var totalIncentive       = 0.0
        var totalRides           = 0
        var totalOperatingProfit = 0.0
        val platformDetails      = mutableListOf<SettledPlatformDetail>()

        for (row in rows) {
            val plan      = profile.platformPlans[row.platform] ?: PlatformPlan()
            val incentive = profile.incentiveProfiles[row.platform] ?: IncentiveProfile()

            // Commission already deducted from operatingProfit in ProfitCalculator
            // Reconstruction for UI only
            val commissionDeducted = if (plan.planType == PlanType.COMMISSION) {
                row.grossEarnings * (plan.commissionPercent / 100.0)
            } else 0.0

            // Pass cost: one-time deduction for the platform's pass
            val passCost = if (plan.planType == PlanType.PASS && plan.passAmount > 0.0) {
                plan.passAmount / plan.passDurationDays.coerceAtLeast(1).toDouble()
            } else 0.0

            // Incentive: add only when target is met
            val incentiveEarned = if (
                incentive.enabled &&
                incentive.targetRides > 0 &&
                incentive.completedToday >= incentive.targetRides
            ) incentive.rewardAmount else 0.0

            // FIXED Bug 4: use direct sum from stored netProfit
            val operatingProfit = row.operatingProfit

            // Platform final settled profit
            val settledProfit = operatingProfit - passCost + incentiveEarned

            platformDetails.add(
                SettledPlatformDetail(
                    platform         = row.platform,
                    rides            = row.rideCount,
                    grossEarnings    = row.grossEarnings,
                    rideOperatingProfit = operatingProfit,
                    commissionDeducted  = commissionDeducted,
                    passCost         = passCost,
                    incentiveEarned  = incentiveEarned,
                    settledProfit    = settledProfit,
                    totalKm          = row.totalKm,
                    incentiveProgress= incentive.completedToday to incentive.targetRides,
                    incentiveTarget  = incentive.rewardAmount
                )
            )

            totalGross           += row.grossEarnings
            totalRideCost        += row.fuelCost + row.wearCost
            totalCommission      += commissionDeducted
            totalPassCost        += passCost
            totalIncentive       += incentiveEarned
            totalRides           += row.rideCount
            totalOperatingProfit += operatingProfit
        }

        val totalSettled = totalOperatingProfit - totalPassCost + totalIncentive

        return SettledDaySummary(
            totalRides          = totalRides,
            grossEarnings       = totalGross,
            totalRideCost       = totalRideCost,
            totalCommission     = totalCommission,
            totalPassCost       = totalPassCost,
            totalIncentive      = totalIncentive,
            totalOperatingProfit= totalOperatingProfit,
            totalSettledProfit  = totalSettled,
            platforms           = platformDetails
        )
    }

    fun updateIncentiveProgress(platform: String, completed: Int) {
        viewModelScope.launch {
            profileRepo.updateIncentiveProgress(platform, completed)
        }
    }

    fun goToPreviousDay() { if (_dayOffset.value > -30) _dayOffset.value -= 1 }
    fun goToNextDay()     { if (_dayOffset.value < 0)  _dayOffset.value += 1 }
    fun goToToday()       { _dayOffset.value = 0 }
}

// ── Result data classes ─────────────────────────────────────────────

data class SettledDaySummary(
    val totalRides:           Int    = 0,
    val grossEarnings:        Double = 0.0,
    val totalRideCost:        Double = 0.0,
    val totalCommission:      Double = 0.0,
    val totalPassCost:        Double = 0.0,
    val totalIncentive:       Double = 0.0,
    val totalOperatingProfit: Double = 0.0,
    val totalSettledProfit:   Double = 0.0,
    val platforms:            List<SettledPlatformDetail> = emptyList()
)

data class SettledPlatformDetail(
    val platform:              String,
    val rides:                 Int,
    val grossEarnings:         Double,
    val rideOperatingProfit:   Double,
    val commissionDeducted:    Double,
    val passCost:              Double,
    val incentiveEarned:       Double,
    val settledProfit:         Double,
    val totalKm:               Double,
    val incentiveProgress:     Pair<Int, Int>,   // completed to target
    val incentiveTarget:       Double             // reward ₹
)
