package com.ridesmart.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ridesmart.model.RiderProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// DataStore instance — created once per application
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rider_profile")

class ProfileRepository(private val context: Context) {

    // ── DATASTORE KEYS ──────────────────────────────────────────────
    // One key per field in RiderProfile
    // Using doublePreferencesKey so values survive as exact decimals
    companion object {
        val KEY_MILEAGE         = doublePreferencesKey("mileage_km_per_litre")
        val KEY_FUEL_PRICE      = doublePreferencesKey("fuel_price_per_litre")
        val KEY_MAINTENANCE     = doublePreferencesKey("maintenance_per_km")
        val KEY_DEPRECIATION    = doublePreferencesKey("depreciation_per_km")
        val KEY_MIN_PROFIT      = doublePreferencesKey("min_acceptable_net_profit")
        val KEY_MIN_PER_KM      = doublePreferencesKey("min_acceptable_per_km")
        val KEY_TARGET_PER_HOUR = doublePreferencesKey("target_earning_per_hour")
        val KEY_COMMISSION      = doublePreferencesKey("platform_commission_percent")
    }

    // ── READ PROFILE ────────────────────────────────────────────────
    // Returns a Flow — updates automatically if profile changes
    // Falls back to RiderProfile defaults if no value saved yet
    val profileFlow: Flow<RiderProfile> = context.dataStore.data
        .catch { exception ->
            // If DataStore file is corrupt, emit empty preferences
            // so app uses default values rather than crashing
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            // Read each key — if missing, use RiderProfile default value
            val defaults = RiderProfile()
            RiderProfile(
                mileageKmPerLitre         = preferences[KEY_MILEAGE]         ?: defaults.mileageKmPerLitre,
                fuelPricePerLitre         = preferences[KEY_FUEL_PRICE]      ?: defaults.fuelPricePerLitre,
                maintenancePerKm          = preferences[KEY_MAINTENANCE]     ?: defaults.maintenancePerKm,
                depreciationPerKm         = preferences[KEY_DEPRECIATION]    ?: defaults.depreciationPerKm,
                minAcceptableNetProfit    = preferences[KEY_MIN_PROFIT]      ?: defaults.minAcceptableNetProfit,
                minAcceptablePerKm        = preferences[KEY_MIN_PER_KM]      ?: defaults.minAcceptablePerKm,
                targetEarningPerHour      = preferences[KEY_TARGET_PER_HOUR] ?: defaults.targetEarningPerHour,
                platformCommissionPercent = preferences[KEY_COMMISSION]      ?: defaults.platformCommissionPercent
            )
        }

    // ── SAVE PROFILE ────────────────────────────────────────────────
    // suspend function — must be called from a coroutine
    // Writes all fields atomically in one DataStore transaction
    suspend fun saveProfile(profile: RiderProfile) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MILEAGE]         = profile.mileageKmPerLitre
            preferences[KEY_FUEL_PRICE]      = profile.fuelPricePerLitre
            preferences[KEY_MAINTENANCE]     = profile.maintenancePerKm
            preferences[KEY_DEPRECIATION]    = profile.depreciationPerKm
            preferences[KEY_MIN_PROFIT]      = profile.minAcceptableNetProfit
            preferences[KEY_MIN_PER_KM]      = profile.minAcceptablePerKm
            preferences[KEY_TARGET_PER_HOUR] = profile.targetEarningPerHour
            preferences[KEY_COMMISSION]      = profile.platformCommissionPercent
        }
    }
}
