package com.ridesmart.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridesmart.data.ProfileRepository
import com.ridesmart.model.RiderProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProfileRepository(application)

    // Flow to check if user has completed initial setup
    val hasCompletedSetup: StateFlow<Boolean> = repository.hasCompletedSetupFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Current saved profile
    val profile: StateFlow<RiderProfile> = repository.profileFlow
        .stateIn(
            scope          = viewModelScope,
            started        = SharingStarted.WhileSubscribed(5000),
            initialValue   = RiderProfile()
        )

    // Onboarding data flows
    val driverName: StateFlow<String> = repository.driverNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val platformsUsed: StateFlow<Set<String>> = repository.platformsUsedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val vehicleType: StateFlow<String> = repository.vehicleTypeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Bike")

    val paymentModel: StateFlow<String> = repository.paymentModelFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "commission")

    val dailyPassCost: StateFlow<Double> = repository.dailyPassCostFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val rentalCost: StateFlow<Double> = repository.rentalCostFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // Save driver profile (Step 2)
    fun saveDriverProfile(name: String, platforms: Set<String>) {
        viewModelScope.launch {
            repository.saveDriverProfile(name, platforms)
        }
    }

    // Save vehicle setup (Step 3)
    fun saveVehicleSetup(vehicleType: String, mileage: Double) {
        viewModelScope.launch {
            repository.saveVehicleSetup(vehicleType, mileage)
        }
    }

    // Save platform payment model (Step 4)
    fun savePlatformPayment(model: String, commission: Double, dailyPassCost: Double, rentalCost: Double) {
        viewModelScope.launch {
            repository.savePlatformPayment(model, commission, dailyPassCost, rentalCost)
        }
    }

    // Save operating costs (Step 5) — marks setup as complete
    fun saveOperatingCosts(fuelPrice: Double, maintenancePerKm: Double, serviceCharge: Double) {
        viewModelScope.launch {
            repository.saveOperatingCosts(fuelPrice, maintenancePerKm, serviceCharge)
        }
    }

    // Full profile save (used by SettingsScreen for advanced configuration)
    fun saveProfile(profile: RiderProfile) {
        viewModelScope.launch {
            repository.saveProfile(profile)
        }
    }
}
