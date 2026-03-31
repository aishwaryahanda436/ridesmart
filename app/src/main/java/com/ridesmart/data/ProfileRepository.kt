package com.ridesmart.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ridesmart.model.IncentiveProfile
import com.ridesmart.model.PlatformPlan
import com.ridesmart.model.PlanType
import com.ridesmart.model.RiderProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

// DataStore instance — created once per application
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "rider_profile")

class ProfileRepository(private val context: Context) {

    // ── DATASTORE KEYS ──────────────────────────────────────────────
    companion object {
        val KEY_MILEAGE         = doublePreferencesKey("mileage_km_per_litre")
        val KEY_FUEL_PRICE      = doublePreferencesKey("fuel_price_per_litre")
        val KEY_CNG_PRICE       = doublePreferencesKey("cng_price_per_kg")
        val KEY_MAINTENANCE     = doublePreferencesKey("maintenance_per_km")
        val KEY_DEPRECIATION    = doublePreferencesKey("depreciation_per_km")
        val KEY_MIN_PROFIT      = doublePreferencesKey("min_acceptable_net_profit")
        val KEY_MIN_PER_KM      = doublePreferencesKey("min_acceptable_per_km")
        val KEY_TARGET_PER_HOUR = doublePreferencesKey("target_earning_per_hour")
        val KEY_DAILY_TARGET    = doublePreferencesKey("daily_earning_target")
        val KEY_COMMISSION      = doublePreferencesKey("platform_commission_percent")
        val KEY_USE_CUSTOM_COMMISSION = booleanPreferencesKey("use_custom_commission")
        
        // Policy Compliance & Online/Offline Status
        val KEY_DISCLOSURE_ACCEPTED = booleanPreferencesKey("accessibility_disclosure_accepted")
        val KEY_IS_ONLINE           = booleanPreferencesKey("is_online")
        val KEY_LAST_INCENTIVE_RESET_DAY = intPreferencesKey("last_incentive_reset_day")

        // ── PER-PLATFORM PLAN KEYS ──────────────────────────────────────────
        // Pattern: plan_{platform}_type / _commission / _pass_amount / _pass_days
        // planType stored as String ("COMMISSION" or "PASS")

        val KEY_RAPIDO_PLAN_TYPE     = stringPreferencesKey("plan_rapido_type")
        val KEY_RAPIDO_COMMISSION    = doublePreferencesKey("plan_rapido_commission")
        val KEY_RAPIDO_PASS_AMOUNT   = doublePreferencesKey("plan_rapido_pass_amount")
        val KEY_RAPIDO_PASS_DAYS     = intPreferencesKey("plan_rapido_pass_days")

        val KEY_UBER_PLAN_TYPE       = stringPreferencesKey("plan_uber_type")
        val KEY_UBER_COMMISSION      = doublePreferencesKey("plan_uber_commission")
        val KEY_UBER_PASS_AMOUNT     = doublePreferencesKey("plan_uber_pass_amount")
        val KEY_UBER_PASS_DAYS       = intPreferencesKey("plan_uber_pass_days")

        val KEY_OLA_PLAN_TYPE        = stringPreferencesKey("plan_ola_type")
        val KEY_OLA_COMMISSION       = doublePreferencesKey("plan_ola_commission")
        val KEY_OLA_PASS_AMOUNT      = doublePreferencesKey("plan_ola_pass_amount")
        val KEY_OLA_PASS_DAYS        = intPreferencesKey("plan_ola_pass_days")

        val KEY_SHADOWFAX_PLAN_TYPE  = stringPreferencesKey("plan_shadowfax_type")
        val KEY_SHADOWFAX_COMMISSION = doublePreferencesKey("plan_shadowfax_commission")
        val KEY_SHADOWFAX_PASS_AMOUNT= doublePreferencesKey("plan_shadowfax_pass_amount")
        val KEY_SHADOWFAX_PASS_DAYS  = intPreferencesKey("plan_shadowfax_pass_days")

        // ── PER-PLATFORM INCENTIVE KEYS ─────────────────────────────────────
        // Pattern: incentive_{platform}_enabled / _target / _reward / _completed

        val KEY_RAPIDO_INC_ENABLED   = booleanPreferencesKey("incentive_rapido_enabled")
        val KEY_RAPIDO_INC_TARGET    = intPreferencesKey("incentive_rapido_target")
        val KEY_RAPIDO_INC_REWARD    = doublePreferencesKey("incentive_rapido_reward")
        val KEY_RAPIDO_INC_COMPLETED = intPreferencesKey("incentive_rapido_completed")

        val KEY_UBER_INC_ENABLED     = booleanPreferencesKey("incentive_uber_enabled")
        val KEY_UBER_INC_TARGET      = intPreferencesKey("incentive_uber_target")
        val KEY_UBER_INC_REWARD      = doublePreferencesKey("incentive_uber_reward")
        val KEY_UBER_INC_COMPLETED   = intPreferencesKey("incentive_uber_completed")

        val KEY_OLA_INC_ENABLED      = booleanPreferencesKey("incentive_ola_enabled")
        val KEY_OLA_INC_TARGET       = intPreferencesKey("incentive_ola_target")
        val KEY_OLA_INC_REWARD       = doublePreferencesKey("incentive_ola_reward")
        val KEY_OLA_INC_COMPLETED    = intPreferencesKey("incentive_ola_completed")

        val KEY_SHADOWFAX_INC_ENABLED   = booleanPreferencesKey("incentive_shadowfax_enabled")
        val KEY_SHADOWFAX_INC_TARGET    = intPreferencesKey("incentive_shadowfax_target")
        val KEY_SHADOWFAX_INC_REWARD    = doublePreferencesKey("incentive_shadowfax_reward")
        val KEY_SHADOWFAX_INC_COMPLETED = intPreferencesKey("incentive_shadowfax_completed")
    }

    private fun readPlatformPlan(
        preferences: Preferences,
        typeKey: Preferences.Key<String>,
        commissionKey: Preferences.Key<Double>,
        passAmountKey: Preferences.Key<Double>,
        passDaysKey: Preferences.Key<Int>
    ): PlatformPlan {
        val typeStr = preferences[typeKey] ?: PlanType.COMMISSION.name
        val planType = try {
            PlanType.valueOf(typeStr)
        } catch (e: IllegalArgumentException) {
            PlanType.COMMISSION
        }
        return PlatformPlan(
            planType          = planType,
            commissionPercent = preferences[commissionKey] ?: 0.0,
            passAmount        = preferences[passAmountKey] ?: 0.0,
            passDurationDays  = preferences[passDaysKey]   ?: 1
        )
    }

    private fun readIncentiveProfile(
        preferences: Preferences,
        enabledKey: Preferences.Key<Boolean>,
        targetKey:  Preferences.Key<Int>,
        rewardKey:  Preferences.Key<Double>,
        completedKey: Preferences.Key<Int>
    ): IncentiveProfile {
        return IncentiveProfile(
            enabled        = preferences[enabledKey]   ?: false,
            targetRides    = preferences[targetKey]    ?: 0,
            rewardAmount   = preferences[rewardKey]    ?: 0.0,
            completedToday = preferences[completedKey] ?: 0
        )
    }

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
                dailyEarningTarget        = preferences[KEY_DAILY_TARGET]    ?: defaults.dailyEarningTarget,
                platformCommissionPercent = preferences[KEY_COMMISSION]      ?: defaults.platformCommissionPercent,
                useCustomCommission       = preferences[KEY_USE_CUSTOM_COMMISSION] ?: defaults.useCustomCommission,
                
                platformPlans = mapOf(
                    "Rapido"    to readPlatformPlan(preferences,
                                    KEY_RAPIDO_PLAN_TYPE,    KEY_RAPIDO_COMMISSION,
                                    KEY_RAPIDO_PASS_AMOUNT,  KEY_RAPIDO_PASS_DAYS),
                    "Uber"      to readPlatformPlan(preferences,
                                    KEY_UBER_PLAN_TYPE,      KEY_UBER_COMMISSION,
                                    KEY_UBER_PASS_AMOUNT,    KEY_UBER_PASS_DAYS),
                    "Ola"       to readPlatformPlan(preferences,
                                    KEY_OLA_PLAN_TYPE,       KEY_OLA_COMMISSION,
                                    KEY_OLA_PASS_AMOUNT,     KEY_OLA_PASS_DAYS),
                    "Shadowfax" to readPlatformPlan(preferences,
                                    KEY_SHADOWFAX_PLAN_TYPE, KEY_SHADOWFAX_COMMISSION,
                                    KEY_SHADOWFAX_PASS_AMOUNT, KEY_SHADOWFAX_PASS_DAYS)
                ),

                incentiveProfiles = mapOf(
                    "Rapido"    to readIncentiveProfile(preferences,
                                    KEY_RAPIDO_INC_ENABLED,    KEY_RAPIDO_INC_TARGET,
                                    KEY_RAPIDO_INC_REWARD,     KEY_RAPIDO_INC_COMPLETED),
                    "Uber"      to readIncentiveProfile(preferences,
                                    KEY_UBER_INC_ENABLED,      KEY_UBER_INC_TARGET,
                                    KEY_UBER_INC_REWARD,       KEY_UBER_INC_COMPLETED),
                    "Ola"       to readIncentiveProfile(preferences,
                                    KEY_OLA_INC_ENABLED,       KEY_OLA_INC_TARGET,
                                    KEY_OLA_INC_REWARD,        KEY_OLA_INC_COMPLETED),
                    "Shadowfax" to readIncentiveProfile(preferences,
                                    KEY_SHADOWFAX_INC_ENABLED, KEY_SHADOWFAX_INC_TARGET,
                                    KEY_SHADOWFAX_INC_REWARD,  KEY_SHADOWFAX_INC_COMPLETED)
                )
            )
        }
        .distinctUntilChanged()

    val isDisclosureAccepted: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DISCLOSURE_ACCEPTED] ?: false }
        .distinctUntilChanged()

    val isOnline: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_IS_ONLINE] ?: true }
        .distinctUntilChanged()

    private fun MutablePreferences.writePlatformPlan(
        plan: PlatformPlan,
        typeKey: Preferences.Key<String>,
        commissionKey: Preferences.Key<Double>,
        passAmountKey: Preferences.Key<Double>,
        passDaysKey: Preferences.Key<Int>
    ) {
        this[typeKey]       = plan.planType.name
        this[commissionKey] = plan.commissionPercent
        this[passAmountKey] = plan.passAmount
        this[passDaysKey]   = plan.passDurationDays
    }

    private fun MutablePreferences.writeIncentiveProfile(
        inc: IncentiveProfile,
        enabledKey:   Preferences.Key<Boolean>,
        targetKey:    Preferences.Key<Int>,
        rewardKey:    Preferences.Key<Double>,
        completedKey: Preferences.Key<Int>
    ) {
        this[enabledKey]   = inc.enabled
        this[targetKey]    = inc.targetRides
        this[rewardKey]    = inc.rewardAmount
        this[completedKey] = inc.completedToday
    }

    suspend fun saveProfile(profile: RiderProfile) {
        // Basic input validation to prevent NaN/Infinity in calculations
        require(profile.mileageKmPerLitre > 0) { "Mileage must be positive" }
        require(profile.fuelPricePerLitre >= 0) { "Fuel price cannot be negative" }
        require(profile.cngPricePerKg >= 0) { "CNG price cannot be negative" }

        context.dataStore.edit { preferences ->
            preferences[KEY_MILEAGE]         = profile.mileageKmPerLitre
            preferences[KEY_FUEL_PRICE]      = profile.fuelPricePerLitre
            preferences[KEY_CNG_PRICE]       = profile.cngPricePerKg
            preferences[KEY_MAINTENANCE]     = profile.maintenancePerKm
            preferences[KEY_DEPRECIATION]    = profile.depreciationPerKm
            preferences[KEY_MIN_PROFIT]      = profile.minAcceptableNetProfit
            preferences[KEY_MIN_PER_KM]      = profile.minAcceptablePerKm
            preferences[KEY_TARGET_PER_HOUR] = profile.targetEarningPerHour
            preferences[KEY_DAILY_TARGET]    = profile.dailyEarningTarget
            preferences[KEY_COMMISSION]      = profile.platformCommissionPercent
            preferences[KEY_USE_CUSTOM_COMMISSION] = profile.useCustomCommission

            val plans = profile.platformPlans
            val incs  = profile.incentiveProfiles

            preferences.writePlatformPlan(
                plans["Rapido"] ?: PlatformPlan(),
                KEY_RAPIDO_PLAN_TYPE, KEY_RAPIDO_COMMISSION,
                KEY_RAPIDO_PASS_AMOUNT, KEY_RAPIDO_PASS_DAYS
            )
            preferences.writePlatformPlan(
                plans["Uber"] ?: PlatformPlan(),
                KEY_UBER_PLAN_TYPE, KEY_UBER_COMMISSION,
                KEY_UBER_PASS_AMOUNT, KEY_UBER_PASS_DAYS
            )
            preferences.writePlatformPlan(
                plans["Ola"] ?: PlatformPlan(commissionPercent = 20.0),
                KEY_OLA_PLAN_TYPE, KEY_OLA_COMMISSION,
                KEY_OLA_PASS_AMOUNT, KEY_OLA_PASS_DAYS
            )
            preferences.writePlatformPlan(
                plans["Shadowfax"] ?: PlatformPlan(),
                KEY_SHADOWFAX_PLAN_TYPE, KEY_SHADOWFAX_COMMISSION,
                KEY_SHADOWFAX_PASS_AMOUNT, KEY_SHADOWFAX_PASS_DAYS
            )

            preferences.writeIncentiveProfile(
                incs["Rapido"] ?: IncentiveProfile(),
                KEY_RAPIDO_INC_ENABLED, KEY_RAPIDO_INC_TARGET,
                KEY_RAPIDO_INC_REWARD, KEY_RAPIDO_INC_COMPLETED
            )
            preferences.writeIncentiveProfile(
                incs["Uber"] ?: IncentiveProfile(),
                KEY_UBER_INC_ENABLED, KEY_UBER_INC_TARGET,
                KEY_UBER_INC_REWARD, KEY_UBER_INC_COMPLETED
            )
            preferences.writeIncentiveProfile(
                incs["Ola"] ?: IncentiveProfile(),
                KEY_OLA_INC_ENABLED, KEY_OLA_INC_TARGET,
                KEY_OLA_INC_REWARD, KEY_OLA_INC_COMPLETED
            )
            preferences.writeIncentiveProfile(
                incs["Shadowfax"] ?: IncentiveProfile(),
                KEY_SHADOWFAX_INC_ENABLED, KEY_SHADOWFAX_INC_TARGET,
                KEY_SHADOWFAX_INC_REWARD, KEY_SHADOWFAX_INC_COMPLETED
            )
        }
    }

    suspend fun updateIncentiveProgress(platformName: String, completedToday: Int) {
        val completedKey = when (platformName) {
            "Rapido"    -> KEY_RAPIDO_INC_COMPLETED
            "Uber"      -> KEY_UBER_INC_COMPLETED
            "Ola"       -> KEY_OLA_INC_COMPLETED
            "Shadowfax" -> KEY_SHADOWFAX_INC_COMPLETED
            else        -> return
        }
        context.dataStore.edit { it[completedKey] = completedToday }
    }

    /**
     * Resets all incentive completedToday counters to 0 if the calendar
     * day has changed since the last reset. Call this on service start.
     */
    suspend fun resetIncentiveProgressIfNewDay() {
        val today = java.util.Calendar.getInstance()
            .get(java.util.Calendar.DAY_OF_YEAR)
        context.dataStore.edit { prefs ->
            val lastReset = prefs[KEY_LAST_INCENTIVE_RESET_DAY] ?: -1
            if (lastReset != today) {
                prefs[KEY_RAPIDO_INC_COMPLETED]    = 0
                prefs[KEY_UBER_INC_COMPLETED]      = 0
                prefs[KEY_OLA_INC_COMPLETED]       = 0
                prefs[KEY_SHADOWFAX_INC_COMPLETED] = 0
                prefs[KEY_LAST_INCENTIVE_RESET_DAY] = today
            }
        }
    }

    suspend fun setDisclosureAccepted(accepted: Boolean) {
        context.dataStore.edit { it[KEY_DISCLOSURE_ACCEPTED] = accepted }
    }

    suspend fun setOnlineStatus(online: Boolean) {
        context.dataStore.edit { it[KEY_IS_ONLINE] = online }
    }
}
