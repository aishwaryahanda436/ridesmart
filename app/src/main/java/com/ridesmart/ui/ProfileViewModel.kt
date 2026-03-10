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

    // Current saved profile — exposed as StateFlow for Compose to observe
    // Starts with RiderProfile defaults until DataStore loads
    val profile: StateFlow<RiderProfile> = repository.profileFlow
        .stateIn(
            scope          = viewModelScope,
            started        = SharingStarted.WhileSubscribed(5000),
            initialValue   = RiderProfile()
        )

    // Called when driver taps Save button
    // Launches coroutine so UI is never blocked
    fun saveProfile(profile: RiderProfile) {
        viewModelScope.launch {
            repository.saveProfile(profile)
        }
    }
}
