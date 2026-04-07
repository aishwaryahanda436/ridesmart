package com.ridesmart.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridesmart.R
import com.ridesmart.data.ProfileRepository
import com.ridesmart.model.IncentiveProfile
import com.ridesmart.model.PlatformPlan
import com.ridesmart.model.RiderProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProfileRepository(application)

    private val _errorFlow = MutableStateFlow("")
    val errorFlow: StateFlow<String> = _errorFlow.asStateFlow()

    // Current saved profile — exposed as StateFlow for Compose to observe
    // Starts with RiderProfile defaults until DataStore loads
    val profile: StateFlow<RiderProfile> = repository.profileFlow
        .stateIn(
            scope          = viewModelScope,
            started        = SharingStarted.WhileSubscribed(5000),
            initialValue   = RiderProfile()
        )

    fun savePlatformPlans(
        plans: Map<String, PlatformPlan>,
        incentives: Map<String, IncentiveProfile>
    ) {
        viewModelScope.launch {
            val current = profile.value
            repository.saveProfile(
                current.copy(
                    platformPlans      = plans,
                    incentiveProfiles  = incentives
                )
            )
        }
    }

    fun updateIncentiveProgress(platformName: String, completedToday: Int) {
        viewModelScope.launch {
            repository.updateIncentiveProgress(platformName, completedToday)
        }
    }

    // Bug 1B & 3B Fixed: Atomic save and decoupled useCustomCommission
    fun validateAndSaveAll(
        mileage: String,
        fuel: String,
        cng: String,
        maint: String,
        depr: String,
        minProfit: String,
        minKm: String,
        hour: String,
        comm: String,
        useCustomComm: Boolean,
        dailyTarget: String,
        plans: Map<String, PlatformPlan>,
        incentives: Map<String, IncentiveProfile>,
        onSuccess: () -> Unit
    ) {
        val pMileage = mileage.toDoubleOrNull() ?: -1.0
        val pFuel = fuel.toDoubleOrNull() ?: -1.0
        val pCng = cngPrice(cng)
        val pMaint = maint.toDoubleOrNull() ?: -1.0
        val pDepr = depr.toDoubleOrNull() ?: -1.0
        val pMinProfitVal = minProfit.toDoubleOrNull()
        val pMinKmVal = minKm.toDoubleOrNull()
        val pHour = hour.toDoubleOrNull() ?: -1.0
        val pComm = comm.toDoubleOrNull() ?: -1.0
        val pDailyTarget = dailyTarget.toDoubleOrNull() ?: 0.0

        val error = when {
            // Updated to 2024-2025 Indian reality as per Audit
            pMileage !in 25.0..80.0 -> getApplication<Application>().getString(R.string.error_mileage)
            pFuel !in 90.0..115.0 -> getApplication<Application>().getString(R.string.error_fuel_price)
            pCng !in 70.0..95.0 -> getApplication<Application>().getString(R.string.error_cng_price)
            pMaint !in 0.0..3.0 -> "Maintenance must be between 0.0 and 3.0"
            pDepr !in 0.0..3.0 -> "Depreciation must be between 0.0 and 3.0"
            pMinProfitVal == null || pMinProfitVal !in 0.0..1000.0 -> getApplication<Application>().getString(R.string.error_min_profit)
            pMinKmVal == null || pMinKmVal !in 0.0..50.0 -> getApplication<Application>().getString(R.string.error_min_km)
            pHour !in 0.0..500.0 -> "Target earning per hour must be between 0 and 500"
            pComm !in 0.0..50.0 -> getApplication<Application>().getString(R.string.error_commission)
            (dailyTarget.isNotBlank() && (dailyTarget.toDoubleOrNull() == null || dailyTarget.toDouble() < 0.0)) ->
                "Daily target must be 0 or a positive number"
            else -> ""
        }

        if (error.isNotEmpty()) {
            _errorFlow.value = error
        } else {
            _errorFlow.value = ""
            viewModelScope.launch {
                val current = profile.value
                repository.saveProfile(current.copy(
                    isConfigured = true,
                    mileageKmPerLitre = pMileage,
                    fuelPricePerLitre = pFuel,
                    cngPricePerKg = pCng,
                    maintenancePerKm = pMaint,
                    depreciationPerKm = pDepr,
                    minAcceptableNetProfit = pMinProfitVal!!,
                    minAcceptablePerKm = pMinKmVal!!,
                    targetEarningPerHour = pHour,
                    dailyEarningTarget = pDailyTarget.coerceAtLeast(0.0),
                    platformCommissionPercent = pComm,
                    useCustomCommission = useCustomComm,
                    platformPlans = plans,
                    incentiveProfiles = incentives
                ))
                onSuccess()
            }
        }
    }

    fun validateAndSave(
        mileage: String,
        fuel: String,
        cng: String,
        maint: String,
        depr: String,
        minProfit: String,
        minKm: String,
        hour: String,
        comm: String,
        dailyTarget: String = "0",
        onSuccess: () -> Unit
    ) {
        val pMileage = mileage.toDoubleOrNull() ?: -1.0
        val pFuel = fuel.toDoubleOrNull() ?: -1.0
        val pCng = cngPrice(cng)
        val pMaint = maint.toDoubleOrNull() ?: -1.0
        val pDepr = depr.toDoubleOrNull() ?: -1.0
        val pMinProfitVal = minProfit.toDoubleOrNull()
        val pMinKmVal = minKm.toDoubleOrNull()
        val pHour = hour.toDoubleOrNull() ?: -1.0
        val pComm = comm.toDoubleOrNull() ?: -1.0
        val pDailyTarget = dailyTarget.toDoubleOrNull() ?: 0.0

        val error = when {
            pMileage !in 25.0..80.0 -> getApplication<Application>().getString(R.string.error_mileage)
            pFuel !in 90.0..115.0 -> getApplication<Application>().getString(R.string.error_fuel_price)
            pCng !in 70.0..95.0 -> getApplication<Application>().getString(R.string.error_cng_price)
            pMaint !in 0.0..3.0 -> "Maintenance must be between 0.0 and 3.0"
            pDepr !in 0.0..3.0 -> "Depreciation must be between 0.0 and 3.0"
            pMinProfitVal == null || pMinProfitVal !in 0.0..1000.0 -> getApplication<Application>().getString(R.string.error_min_profit)
            pMinKmVal == null || pMinKmVal !in 0.0..50.0 -> getApplication<Application>().getString(R.string.error_min_km)
            pHour !in 0.0..500.0 -> "Target earning per hour must be between 0 and 500"
            pComm !in 0.0..50.0 -> getApplication<Application>().getString(R.string.error_commission)
            (dailyTarget.isNotBlank() && (dailyTarget.toDoubleOrNull() == null || dailyTarget.toDouble() < 0.0)) ->
                "Daily target must be 0 or a positive number"
            else -> ""
        }

        if (error.isNotEmpty()) {
            _errorFlow.value = error
        } else {
            _errorFlow.value = ""
            val current = profile.value
            saveProfile(current.copy(
                isConfigured = true,
                mileageKmPerLitre = pMileage,
                fuelPricePerLitre = pFuel,
                cngPricePerKg = pCng,
                maintenancePerKm = pMaint,
                depreciationPerKm = pDepr,
                minAcceptableNetProfit = pMinProfitVal!!,
                minAcceptablePerKm = pMinKmVal!!,
                targetEarningPerHour = pHour,
                dailyEarningTarget = pDailyTarget.coerceAtLeast(0.0),
                platformCommissionPercent = pComm,
                useCustomCommission = pComm > 0
            ))
            onSuccess()
        }
    }

    private fun cngPrice(cng: String): Double {
        return if (cng.isBlank()) 0.0 else cng.toDoubleOrNull() ?: -1.0
    }

    private fun saveProfile(profile: RiderProfile) {
        viewModelScope.launch {
            repository.saveProfile(profile)
        }
    }
}
