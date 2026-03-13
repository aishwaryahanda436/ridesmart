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
    companion object {
        val KEY_HAS_COMPLETED_SETUP = booleanPreferencesKey("has_completed_setup")

        // Driver Profile (Step 2)
        val KEY_DRIVER_NAME     = stringPreferencesKey("driver_name")
        val KEY_PLATFORMS_USED  = stringPreferencesKey("platforms_used") // comma-separated

        // Vehicle Setup (Step 3)
        val KEY_VEHICLE_TYPE    = stringPreferencesKey("vehicle_type") // Bike, Auto, Car

        // Platform Payment (Step 4)
        val KEY_PAYMENT_MODEL   = stringPreferencesKey("payment_model") // commission, daily_pass, rental
        val KEY_DAILY_PASS_COST = doublePreferencesKey("daily_pass_cost")
        val KEY_RENTAL_COST     = doublePreferencesKey("rental_cost")

        // Existing keys
        val KEY_MILEAGE         = doublePreferencesKey("mileage_km_per_litre")
        val KEY_FUEL_PRICE      = doublePreferencesKey("fuel_price_per_litre")
        val KEY_CNG_PRICE       = doublePreferencesKey("cng_price_per_kg")
        val KEY_MAINTENANCE     = doublePreferencesKey("maintenance_per_km")
        val KEY_DEPRECIATION    = doublePreferencesKey("depreciation_per_km")
        val KEY_MIN_PROFIT      = doublePreferencesKey("min_acceptable_net_profit")
        val KEY_MIN_PER_KM      = doublePreferencesKey("min_acceptable_per_km")
        val KEY_TARGET_PER_HOUR = doublePreferencesKey("target_earning_per_hour")
        val KEY_COMMISSION      = doublePreferencesKey("platform_commission_percent")
        val KEY_CITY_AVG_SPEED  = doublePreferencesKey("city_avg_speed_kmh")
        val KEY_CONGESTION      = doublePreferencesKey("congestion_factor")
        val KEY_SUBSCRIPTION    = doublePreferencesKey("subscription_daily_cost")
        val KEY_AVG_TRIPS       = doublePreferencesKey("avg_trips_per_day")
        val KEY_DIESEL_PRICE    = doublePreferencesKey("diesel_price_per_litre")
        val KEY_ELEC_RATE       = doublePreferencesKey("electricity_rate_per_kwh")
        val KEY_EV_CONS         = doublePreferencesKey("ev_consumption_kwh_per_km")

        // Operating Cost (Step 5)
        val KEY_SERVICE_CHARGE  = doublePreferencesKey("platform_service_charge")
    }

    val hasCompletedSetupFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[KEY_HAS_COMPLETED_SETUP] ?: false
        }

    // New onboarding data flows
    val driverNameFlow: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_DRIVER_NAME] ?: "" }

    val platformsUsedFlow: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[KEY_PLATFORMS_USED]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }

    val vehicleTypeFlow: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_VEHICLE_TYPE] ?: "Bike" }

    val paymentModelFlow: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_PAYMENT_MODEL] ?: "commission" }

    val profileFlow: Flow<RiderProfile> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            val defaults = RiderProfile()
            RiderProfile(
                mileageKmPerLitre         = preferences[KEY_MILEAGE]         ?: defaults.mileageKmPerLitre,
                fuelPricePerLitre         = preferences[KEY_FUEL_PRICE]      ?: defaults.fuelPricePerLitre,
                cngPricePerKg             = preferences[KEY_CNG_PRICE]       ?: defaults.cngPricePerKg,
                maintenancePerKm          = preferences[KEY_MAINTENANCE]     ?: defaults.maintenancePerKm,
                depreciationPerKm         = preferences[KEY_DEPRECIATION]    ?: defaults.depreciationPerKm,
                minAcceptableNetProfit    = preferences[KEY_MIN_PROFIT]      ?: defaults.minAcceptableNetProfit,
                minAcceptablePerKm        = preferences[KEY_MIN_PER_KM]      ?: defaults.minAcceptablePerKm,
                targetEarningPerHour      = preferences[KEY_TARGET_PER_HOUR] ?: defaults.targetEarningPerHour,
                platformCommissionPercent = preferences[KEY_COMMISSION]      ?: defaults.platformCommissionPercent,
                cityAvgSpeedKmH           = preferences[KEY_CITY_AVG_SPEED]  ?: defaults.cityAvgSpeedKmH,
                congestionFactor          = preferences[KEY_CONGESTION]      ?: defaults.congestionFactor,
                subscriptionDailyCost     = preferences[KEY_SUBSCRIPTION]    ?: defaults.subscriptionDailyCost,
                avgTripsPerDay            = preferences[KEY_AVG_TRIPS]       ?: defaults.avgTripsPerDay,
                dieselPricePerLitre       = preferences[KEY_DIESEL_PRICE]    ?: defaults.dieselPricePerLitre,
                electricityRatePerKWh     = preferences[KEY_ELEC_RATE]       ?: defaults.electricityRatePerKWh,
                evConsumptionKWhPerKm     = preferences[KEY_EV_CONS]         ?: defaults.evConsumptionKWhPerKm
            )
        }

    suspend fun saveDriverProfile(name: String, platforms: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DRIVER_NAME] = name
            preferences[KEY_PLATFORMS_USED] = platforms.joinToString(",")
        }
    }

    suspend fun saveVehicleSetup(vehicleType: String, mileage: Double) {
        context.dataStore.edit { preferences ->
            preferences[KEY_VEHICLE_TYPE] = vehicleType
            preferences[KEY_MILEAGE] = mileage
        }
    }

    suspend fun savePlatformPayment(model: String, commission: Double, dailyPassCost: Double, rentalCost: Double) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PAYMENT_MODEL] = model
            preferences[KEY_COMMISSION] = commission
            preferences[KEY_DAILY_PASS_COST] = dailyPassCost
            preferences[KEY_RENTAL_COST] = rentalCost
            preferences[KEY_SUBSCRIPTION] = when (model) {
                "daily_pass" -> dailyPassCost
                "rental" -> rentalCost
                else -> 0.0
            }
        }
    }

    suspend fun saveOperatingCosts(fuelPrice: Double, maintenancePerKm: Double, serviceCharge: Double) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FUEL_PRICE] = fuelPrice
            preferences[KEY_MAINTENANCE] = maintenancePerKm
            preferences[KEY_SERVICE_CHARGE] = serviceCharge
            preferences[KEY_HAS_COMPLETED_SETUP] = true
        }
    }

    suspend fun saveProfile(profile: RiderProfile) {
        context.dataStore.edit { preferences ->
            preferences[KEY_HAS_COMPLETED_SETUP] = true
            preferences[KEY_MILEAGE]         = profile.mileageKmPerLitre
            preferences[KEY_FUEL_PRICE]      = profile.fuelPricePerLitre
            preferences[KEY_CNG_PRICE]       = profile.cngPricePerKg
            preferences[KEY_MAINTENANCE]     = profile.maintenancePerKm
            preferences[KEY_DEPRECIATION]    = profile.depreciationPerKm
            preferences[KEY_MIN_PROFIT]      = profile.minAcceptableNetProfit
            preferences[KEY_MIN_PER_KM]      = profile.minAcceptablePerKm
            preferences[KEY_TARGET_PER_HOUR] = profile.targetEarningPerHour
            preferences[KEY_COMMISSION]      = profile.platformCommissionPercent
            preferences[KEY_CITY_AVG_SPEED]  = profile.cityAvgSpeedKmH
            preferences[KEY_CONGESTION]      = profile.congestionFactor
            preferences[KEY_SUBSCRIPTION]    = profile.subscriptionDailyCost
            preferences[KEY_AVG_TRIPS]       = profile.avgTripsPerDay
            preferences[KEY_DIESEL_PRICE]    = profile.dieselPricePerLitre
            preferences[KEY_ELEC_RATE]       = profile.electricityRatePerKWh
            preferences[KEY_EV_CONS]         = profile.evConsumptionKWhPerKm
        }
    }
}
